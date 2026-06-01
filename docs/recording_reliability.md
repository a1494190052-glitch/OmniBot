# 手动录制可靠性文档

本文档描述 OOB 手动录制链路的已知问题、根本原因和修复规范，供长期维护参考。

---

## 架构概览

```
用户触摸
  └─ ManualTouchRecordLoader (overlay, main thread)
       └─ pendingGestures 队列
            └─ processGestureQueue (IO coroutine)
                 └─ processQueuedGesture
                      ├─ unlockTouchLocked()         ← 解锁 overlay，main thread
                      ├─ HumanTrajectoryLearningSession.recordOverlayGesture()
                      │    ├─ performOverlayGesture() ← GestureDescription，IO
                      │    ├─ onGestureDispatched()   ← 回调，withContext(Main)
                      │    └─ appendOverlayClickGesture / appendOverlaySwipeGesture
                      └─ lockTouchLocked()            ← 重锁 overlay，main thread

用户按暂停/完成
  └─ ManualRecordingControlOverlay
       ├─ pauseActive()   ← BUG: 主线程直接调用
       └─ completeActive() ← OK: recordingControlScope (IO)
```

---

## 2026-06-01 当前手动录制策略

当前稳定策略是单向链路：overlay 捕获真实触摸，录制器短超时采
`beforeXml`，临时放行 overlay 后用 `dispatchGesture` 重放，再立刻重锁
overlay。Accessibility 不反向控制 overlay，不生成 click/swipe/long_press。

关键约束：

- 普通动作只依赖真实 overlay/raw touch，缺 XML 时记录坐标兜底。
- `afterXml` / `afterScreenshot` 不作为手动录制必填项。
- `TYPE_VIEW_TEXT_CHANGED` 只更新真实触摸锚点上的最终 `input_text`。
- `TYPE_VIEW_CLICKED` / focused / scrolled 只计数或 suppress，不补录动作。
- 键盘打开时 overlay 保持 touchable，但高度裁剪到 keyboard top；键盘区域
  放行，键盘上方 App 区域继续被 overlay 捕获。
- 如果裁剪竞态导致 overlay 兜住键盘区域触摸，该触摸只做黑盒 replay，不采
  XML/截图，不生成 click；最终输入结果仍由 `TYPE_VIEW_TEXT_CHANGED` 合并成
  一条 `input_text`。键盘区域判定不能只信 `imeTop`；已有真实输入锚点时，
  `imeTop` 缺失或不准也要用保守底部估算把键盘按键挡进黑盒。
- 刚点击可能打开输入框但还没有真实 keyboard top 时，短时间使用保守估算高度，
  优先保证键盘区域可触摸，随后由 `WindowInsets` 或“过滤输入法后的前景
  App XML 可见底边”修正。
- 不用 `TYPE_INPUT_METHOD` window frame / 输入法子节点作为键盘 top。vivo 等
  设备上输入法 window frame 可能从状态栏下方开始，和真实按键区域无关。
- `dispatchGesture` 超时只写 `dispatch_timeout` 诊断，不阻塞下一次操作。

---

## 2026-06-01 rejected attempts / lessons

这些是已经验证过会导致卡死、闪退、漏记或保存不稳的失败尝试，不要在后续修复中恢复：

1. **A11/IME 事件反向控制 overlay 状态**
   - 失败方案：`TYPE_VIEW_TEXT_CHANGED` / focus 事件通过
     `ManualRecordingImeBypassSignal` 让 overlay 进入/退出 IME bypass。
   - 问题：A11 事件和 overlay replay 互相触发，容易形成 relock/bypass 循环；
     主线程频繁 `updateViewLayout`，用户输入时明显卡顿，极端情况下 ANR。
   - 结论：A11 只能作为文本 evidence，不能控制 overlay 状态机。

2. **键盘打开后全屏 `NOT_TOUCHABLE` bypass**
   - 失败方案：IME 可见时让全屏 overlay 不可触摸，所有触摸直接传给 App/键盘。
   - 问题：键盘能输入，但键盘上方 App 区域点击完全录不到，比如“搜索/发送”。
   - 结论：只裁剪 overlay 高度到 keyboard top，不能全屏 bypass。

3. **A11 post-input click 补录**
   - 失败方案：输入后用 `TYPE_VIEW_CLICKED` 的 source 节点补录“搜索/发送”。
   - 问题：source 可能 stale/null，坐标和真实触摸顺序不可靠，容易重复/误记；
     安全 snapshot 仍会引入额外 binder 访问和闪退风险。
   - 结论：post-input click 仍必须靠 overlay 捕获；A11 click 永不生成动作。

4. **手动录制 finish 走 `saveSnapshot=false` event-only 保存**
   - 失败方案：finish 时只写事件，不写完整 RunLog snapshot。
   - 问题：列表/详情读取路径不一致，图片和 source context 容易缺失，用户看到
     “无法保存”或详情打不开。
   - 结论：手动录制 finish 必须直接写 RunLog cards、diagnostics 和 finish snapshot。

5. **恢复固定 after evidence 等待**
   - 失败方案：每步等待固定 350ms 再采 `afterXml` / screenshot。
   - 问题：XML 本身多数不慢，但固定等待和窗口事件 burst 会放大延迟；用户连续
     操作时队列堆积，误以为卡死。
   - 结论：手动录制不等 after evidence，后态缺失是允许状态。

6. **每步重建 overlay z-order**
   - 失败方案：每个动作调用 remove/add 或 ensure-on-top。
   - 问题：窗口重建本身在主线程，遇到 IME/系统弹窗时更容易掉帧和输入派发超时。
   - 结论：overlay 录制期间只 add/remove 一次，状态变化用去重后的
     `updateViewLayout`。

7. **用输入法 window frame / 子节点扫描计算 keyboard top**
   - 失败方案：读取 `AccessibilityWindowInfo.TYPE_INPUT_METHOD` 的 bounds，或者
     扫描输入法 root 子节点，再把最小 top 当作 keyboard top。
   - 问题：vivo 设备上输入法 window frame 可从 `y=140` 开始，真实按键区域在
     `y≈1682`；子节点结构由输入法实现决定，不稳定，也会增加 binder 访问。
   - 结论：只把输入法从 XML capture 中过滤掉；overlay 裁剪几何来自 filtered
     foreground App XML 的可见底边，缺失时才短时用保守估算。

---

## 已知 Bug

### BUG-1（严重）：handleCaptureClick 在主线程调用 pauseActive → 死锁 → ANR → 闪退

**文件**：`ManualRecordingControlOverlay.kt`，`handleCaptureClick()`

**触发条件**：用户在一次 overlay 手势正在处理时（`overlayGestureActiveCount > 0`），点击截图/暂停按钮。

**死锁路径**：
```
主线程:
  handleCaptureClick()
    HumanTrajectoryLearningSession.pauseActive()
      ManualVlmTraceRecorder.pause()
        awaitOverlayRecordJobs()                  ← 主线程 Object.wait()
          synchronized(recordingLock)
            while (overlayGestureActiveCount > 0)
              recordingLock.wait(100ms)            ← 阻塞主线程

IO 线程 (recordScope):
  processQueuedGesture → recordOverlayGesture
    performOverlayGesture() ← 完成
    finally: onGestureDispatched()
      withContext(Dispatchers.Main)               ← 等主线程，主线程被 wait() 占用
    ← 永远无法继续
    synchronized(recordingLock) {
      decrementOverlayGestureActiveLocked()       ← 永远不会执行
      recordingLock.notifyAll()                   ← 永远不会执行
    }
```

两端互相等待：主线程等 `count=0`，IO 线程等主线程，`count` 永远不会归零。5 秒后 Android 触发 ANR，用户关闭应用。

**修复**：`handleCaptureClick()` 中的 `pauseActive()` 必须移入 `recordingControlScope.launch {}` 块，与 `resumeActive()` 一起在 IO 线程执行：

```kotlin
// ManualRecordingControlOverlay.kt handleCaptureClick() 修复
private fun handleCaptureClick() {
    val callback = synchronized(this) { captureStateCallback } ?: return
    val previousState = synchronized(this) { state }
    val context = overlayView?.context ?: UIKit.appContext ?: return

    recordingControlScope.launch {                          // IO 线程
        val wasPaused = HumanTrajectoryLearningSession.isPaused()
        val shouldResume = HumanTrajectoryLearningSession.isActive() &&
            !wasPaused &&
            HumanTrajectoryLearningSession.pauseActive()   // 在 IO 线程阻塞，安全
        withContext(Dispatchers.Main) {
            if (shouldResume) markPaused()
            hideTemporarily()
        }
        val result = runCatching { callback() }.getOrElse { ... }
        val resumed = if (shouldResume) HumanTrajectoryLearningSession.resumeActive() else false
        // ... 后续逻辑不变
    }
}
```

---

### BUG-2（中）：awaitOverlayRecordJobs 无超时上限

**文件**：`ManualVlmTraceRecorder.kt`，`awaitOverlayRecordJobs()`

**问题**：每次循环等 100ms，但没有总超时。若 `overlayGestureActiveCount` 因意外未归零（如 `decrementOverlayGestureActiveLocked` 未执行），调用方永久阻塞。

**修复**：加最大等待时间：

```kotlin
private fun awaitOverlayRecordJobs() {
    val deadline = System.currentTimeMillis() + OVERLAY_RECORD_DRAIN_TIMEOUT_MS
    synchronized(recordingLock) {
        while (overlayGestureActiveCount > 0 &&
               System.currentTimeMillis() < deadline) {
            try {
                recordingLock.wait(OVERLAY_RECORD_DRAIN_POLL_MS)
            } catch (_: InterruptedException) { }
        }
        if (overlayGestureActiveCount > 0) {
            OmniLog.w(TAG, "awaitOverlayRecordJobs timed out, count=$overlayGestureActiveCount; resetting")
            overlayGestureActiveCount = 0
        }
    }
}
// 当前值: OVERLAY_RECORD_DRAIN_TIMEOUT_MS = 600L
```

---

### BUG-3（严重）：captureCurrentXml 无超时 → 第一个手势永久卡死

**文件**：`ManualVlmTraceRecorder.kt`，`recordOverlayGesture()`

**触发条件**：目标 App 在 UI 切换（页面跳转、弹窗出现）时，`captureCurrentXml()` 被调用。

**根因**：`captureCurrentXml()` → `OmniCaptureAction.captureScreenshotXml()` → `service.windows` → `window.root` 是 binder 调用，当目标 App 未响应时无限阻塞。没有任何超时保护。整个 `recordOverlayGesture` 协程挂死，所有后续手势无法处理。

**修复（已生效）**：
```kotlin
val beforeXml = withTimeoutOrNull(BEFORE_XML_CAPTURE_TIMEOUT_MS) {  // 300ms
    withContext(Dispatchers.IO) { captureCurrentXml() }  // 独立 IO 线程
}?.takeIf { it.isNotBlank() }
```

- `withTimeoutOrNull`：超时后协程继续，不再等待当前操作
- `withContext(Dispatchers.IO)`：binder 在独立线程，超时后该线程后台释放，不阻塞当前协程
- 不回退旧 XML：缺失 XML 是合法状态，动作以坐标兜底记录并标记
  `missing_source_xml=true`

**不变式**：凡是调用 `captureCurrentXml()`（或任何 `window.root` / `getCaptureScreenShotXml`）的地方，都必须有超时保护。

---

### BUG-4（轻）：点击被识别为滑动

**文件**：`ManualTouchRecordLoader.kt`，`handleTouchEvent()`

**问题（已修复）**：原实现在 ACTION_MOVE 期间只要距离超阈值就置 `isSwipe = true`，导致手指轻微漂移的点击被识别为滑动。

**修复（已生效）**：分类只用 ACTION_UP 时的 start→end 净位移，不用中间过程的峰值位移。

---

### BUG-4（轻）：Thread.sleep 在协程中不可取消

**文件**：`ManualVlmTraceRecorder.kt`，`settleAndRecordOverlayGesture()`

**问题（已修复）**：原用 `Thread.sleep(OVERLAY_TOUCH_SETTLE_MS)` 阻塞 IO 线程，超时机制无法中断。

**修复（已生效）**：改为 `suspend fun` + `delay(OVERLAY_TOUCH_SETTLE_MS)`，协程取消可正常传播。

---

## 不变式（所有修改必须保证）

1. **`overlayGestureActiveCount` 必须对称**：每次 `overlayGestureActiveCount += 1` 之后，无论成功/失败/超时，`decrementOverlayGestureActiveLocked()` 必须在 `finally` 块中执行。
2. **`awaitOverlayRecordJobs()` 只能在非主线程调用**：`pause()` / `stop()` 调用链不得出现在主线程上。
3. **`withContext(Dispatchers.Main)` 不得在主线程持有 `recordingLock` 时被等待**：否则形成 monitor-dispatcher 双向等待死锁。
4. **`updateViewLayout` 只在主线程调用**：`lockTouchLocked()` / `unlockTouchLocked()` 调用方必须在主线程或 `withContext(Main)` 中。
5. **`pendingGestures` 写入只在主线程**：`enqueueGesture` 从 touch listener 调用（主线程），`pollFirst` 从 IO 线程调用但持有锁。两者均在 `synchronized(this)` 内，安全。

---

## 操作分类和线程归属

| 操作 | 线程 | 说明 |
|------|------|------|
| 触摸捕获（ACTION_DOWN/MOVE/UP） | 主线程 | touch listener 回调 |
| 手势入队 `enqueueGesture` | 主线程 | 在 `synchronized(this)` 内 |
| 队列处理 `processGestureQueue` | IO 线程 | `recordScope.launch` |
| `performOverlayGesture`（GestureDescription）| IO 线程 | suspend，accessibility service callback 线程完成 |
| `onGestureDispatched` 回调 | IO → Main | `withContext(Main)` 中执行 overlay re-lock |
| `lockTouchLocked / unlockTouchLocked` | 主线程 | `updateViewLayout` 强制要求 |
| `awaitOverlayRecordJobs` | IO 线程 | 阻塞调用，**严禁在主线程** |
| `pauseActive / completeActive / cancelActive` | IO 线程 | 必须从 `recordingControlScope.launch {}` 发出 |
| Accessibility 事件处理 | Accessibility binder 线程 | `synchronized(recordingLock)` 保护 |

---

## 超时参数说明

| 常量 | 位置 | 值 | 含义 |
|------|------|----|------|
| `OVERLAY_UNLOCK_REPLAY_DELAY_MS` | ManualTouchRecordLoader | 32ms | overlay NOT_TOUCHABLE 生效等待 |
| `BEFORE_XML_CAPTURE_TIMEOUT_MS` | ManualVlmTraceRecorder | 300ms | before XML 采集上限；超时后坐标兜底 |
| `OVERLAY_CLICK_REPLAY_TIMEOUT_MS` | ManualVlmTraceRecorder | 500ms | click GestureDescription 最大等待 |
| `OVERLAY_TOUCH_SETTLE_MS` | ManualVlmTraceRecorder | 350ms | 已废弃（beforeXml-only 模式下不等待） |
| `IME_VISIBILITY_PROBE_TIMEOUT_MS` | ManualTouchRecordLoader | 1500ms | 点击后轻量探测 IME 是否出现 |
| `IME_RELOCK_POLL_MS` | ManualTouchRecordLoader | 900ms | IME 打开期间刷新 filtered App XML 裁剪几何 |
| `IME_OPEN_EXPECTED_TTL_MS` | ManualTouchRecordLoader | 1200ms | 输入框点击后的短时估算窗口 |
| `OVERLAY_RECORD_DRAIN_POLL_MS` | ManualVlmTraceRecorder | 100ms | `awaitOverlayRecordJobs` 每轮等待 |
| `OVERLAY_RECORD_DRAIN_TIMEOUT_MS` | ManualVlmTraceRecorder | 600ms | drain 总超时，防永久阻塞 |

---

## NOT_TOUCHABLE 窗口说明

GestureDescription 必须在 overlay NOT_TOUCHABLE 时分发（否则被 overlay 自身拦截）。这是 Android WindowManager 的硬性约束，无法绕过。

当前最小化方案：
- NOT_TOUCHABLE 期间：`unlockTouchLocked()` → `delay(32ms)` → `performOverlayGesture()`
- `onGestureDispatched` 回调后立即 `lockTouchLocked()`（TOUCHABLE）
- 新触摸在 TOUCHABLE 期间进入 `pendingGestures` 队列
- NOT_TOUCHABLE 窗口约 32ms + GestureDescription 执行时间（click ≤500ms）
- 此窗口内的用户触摸直接到 App，无法被 overlay 捕获——这是不可消除的物理约束
- 键盘打开后不是全屏 NOT_TOUCHABLE；overlay 高度裁剪到 keyboard top，键盘区
  放行，App 区域继续捕获。

---

## 诊断字段（RunLog action.eventContext）

每个录制的 action 携带以下诊断：

| 字段 | 含义 |
|------|------|
| `operation_id` | 每次操作的唯一 id，格式 `overlay_{startMs}_{sequence}` |
| `dispatch_status` | `dispatch_completed` / `dispatch_timeout` / `dispatch_failed` / `dispatch_cancelled` |
| `before_xml_present` | beforeXml 是否有效 |
| `error_code` | 失败时的错误代码 |
| `error_message` | 失败时的错误消息 |
| `recording_backend` | `overlay_touch` |

`dispatch_timeout` 不等于操作丢失——RunLog 仍记录坐标、时间、动作类型和失败原因。
