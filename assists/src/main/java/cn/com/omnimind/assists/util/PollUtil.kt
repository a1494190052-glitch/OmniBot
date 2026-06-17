package cn.com.omnimind.assists.util

import kotlinx.coroutines.delay

/**
 * 轮询等待直到条件满足或超时。
 * [check] 返回非 null 值表示条件满足，继续轮询则返回 null。
 */
suspend fun <T : Any> pollUntilReady(
    intervalMs: Long = 100L,
    timeoutMs: Long = 3000L,
    check: suspend () -> T?
): T? {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val result = runCatching { check() }.getOrNull()
        if (result != null) return result
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0) break
        delay(minOf(intervalMs, remaining))
    }
    return null
}
