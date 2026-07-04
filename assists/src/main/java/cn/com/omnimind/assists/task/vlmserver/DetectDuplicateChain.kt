package cn.com.omnimind.assists.task.vlmserver

object DetectDuplicateChain {
    /**
     * 计算UI步骤链路末尾连续WaitAction的总等待时间
     * @param steps UI步骤列表
     * @return 连续WaitAction的durationMs总和，如果没有WaitAction则返回0
     */
    fun calculateTrailingWaitDuration(steps: List<UIStep>): Long {
        if (steps.isEmpty()) return 0

        var totalDuration = 0L
        // 从列表末尾开始向前遍历
        for (i in steps.size - 1 downTo 0) {
            val step = steps[i]
            if (step.action is WaitAction) {
                val waitAction = step.action as WaitAction
                val duration = waitAction.durationMs ?: 0
                totalDuration += duration
            } else {
                // 遇到非WaitAction节点则停止
                break
            }
        }

        return totalDuration
    }

    /**
     * 检测UI步骤中的重复链路模式
     * @param steps UI步骤列表
     * @param minRepeatCount 最小重复次数，默认为2
     * @return Pair(是否找到重复链路, 重复的步骤模式)
     */
    fun detectUIStepDuplicateChain(
        steps: List<UIStep>,
        minRepeatCount: Int = 2
    ): Pair<Boolean, List<UIStep>?> {
        if (steps.size < 2 || minRepeatCount < 2) return Pair(false, null)

        // 找到最后一个 press_key(back) 的位置
        val lastSpecialIndex = findLastSpecialActionIndex(steps)
        if (lastSpecialIndex < 0) return Pair(false, null)

        // 如果最后一个步骤不是 press_key(back)，则不构成有效链路
        if (!steps.last().action.isBackKeyAction()) {
            return Pair(false, null)
        }

        // 获取最后一个完整段落（从倒数第二个 press_key(back) 到末尾）
        val secondLastSpecialIndex = findLastSpecialActionIndexBefore(steps, lastSpecialIndex)
        if (secondLastSpecialIndex < 0) return Pair(false, null)

        val lastSegment = steps.subList(secondLastSpecialIndex, steps.size)

        // 在之前的序列中统计相同模式出现的次数
        val repeatCount = countMatchingSegments(steps, secondLastSpecialIndex, lastSegment)

        return if (repeatCount >= minRepeatCount) {
            Pair(true, lastSegment)
        } else {
            Pair(false, null)
        }
    }

    /**
     * 统计匹配的步骤段落出现次数
     */
    private fun countMatchingSegments(
        steps: List<UIStep>,
        beforeIndex: Int,
        pattern: List<UIStep>
    ): Int {
        var count = 0
        if (beforeIndex < pattern.size) return count

        // 从前往后查找匹配的段落
        for (i in 0..beforeIndex - pattern.size) {
            // 确保比较的段落也是以 press_key(back) 开始和结束
            if (steps[i].action.isBackKeyAction() &&
                i + pattern.size <= steps.size &&
                steps[i + pattern.size - 1].action.isBackKeyAction()
            ) {

                val candidate = steps.subList(i, i + pattern.size)
                if (isStepPatternEqual(candidate, pattern)) {
                    count++
                }
            }
        }

        return count
    }

    /**
     * 查找之前匹配的步骤段落
     */
    private fun findPreviousMatchingSegment(
        steps: List<UIStep>,
        beforeIndex: Int,
        pattern: List<UIStep>
    ): List<UIStep>? {
        if (beforeIndex < pattern.size) return null

        // 从前往后查找匹配的段落
        for (i in 0..beforeIndex - pattern.size) {
            // 确保比较的段落也是以 press_key(back) 开始和结束
            if (steps[i].action.isBackKeyAction() &&
                i + pattern.size <= steps.size &&
                steps[i + pattern.size - 1].action.isBackKeyAction()
            ) {

                val candidate = steps.subList(i, i + pattern.size)
                if (isStepPatternEqual(candidate, pattern)) {
                    return candidate
                }
            }
        }

        return null
    }

    /**
     * 比较两个步骤模式是否相等
     */
    private fun isStepPatternEqual(pattern1: List<UIStep>, pattern2: List<UIStep>): Boolean {
        if (pattern1.size != pattern2.size) return false

        for (i in pattern1.indices) {
            val action1 = pattern1[i].action
            val action2 = pattern2[i].action

            // 比较动作类型
            when {
                action1 is PressKeyAction && action2 is PressKeyAction -> {
                    if (action1.key.lowercase() != action2.key.lowercase()) return false
                    continue
                }
                action1 is ClickAction && action2 is ClickAction -> continue
                action1 is InputTextAction && action2 is InputTextAction -> {
                    if (action1.text != action2.text) return false
                }

                action1 is SwipeAction && action2 is SwipeAction -> continue
                action1 is LongPressAction && action2 is LongPressAction -> continue
                action1 is OpenAppAction && action2 is OpenAppAction -> {
                    if (action1.packageName != action2.packageName) return false
                }

                action1 is WaitAction && action2 is WaitAction -> continue
                action1 is RecordAction && action2 is RecordAction -> {
                    if (action1.content != action2.content) return false
                }

                action1 is FinishedAction && action2 is FinishedAction -> {
                    if (action1.content != action2.content) return false
                }

                else -> return false
            }
        }

        return true
    }

    /**
     * 查找最后一个特殊动作 press_key(back) 的索引
     */
    private fun findLastSpecialActionIndex(steps: List<UIStep>): Int {
        for (i in steps.indices.reversed()) {
            if (steps[i].action.isBackKeyAction()) {
                return i
            }
        }
        return -1
    }

    /**
     * 查找指定位置之前最后一个特殊动作的索引
     */
    private fun findLastSpecialActionIndexBefore(steps: List<UIStep>, beforeIndex: Int): Int {
        for (i in beforeIndex - 1 downTo 0) {
            if (steps[i].action.isBackKeyAction()) {
                return i
            }
        }
        return -1
    }

    private fun UIAction.isBackKeyAction(): Boolean =
        this is PressKeyAction && key.equals("back", ignoreCase = true)

}
