package cn.com.omnimind.bot.runlog

import cn.com.omnimind.bot.runlog.OobActionCodec.firstNonBlank

/**
 * Removes deterministic noise from compiled replay steps.
 *
 * This operates after RunLog cards have already been lowered into canonical
 * execution steps. Card filtering, parameter inference, and launch-bridge
 * handling stay in the compiler.
 */
object RunLogReplayStepNoiseNormalizer {
    private val inputTextActions = setOf(OobActionCodec.ACTION_INPUT_TEXT)

    fun normalize(steps: List<Map<String, Any?>>): List<Map<String, Any?>> =
        dropDuplicateTextInputSteps(steps)
            .let(::collapseConsecutiveTextInputSteps)
            .let(::dropKeyboardDismissBackBetweenFormActions)
            .let(::dropRedundantClickBeforeInputText)

    /**
     * Collapses consecutive input_text steps on the same target into the last one.
     *
     * Accessibility text-change events fire on every keystroke, producing one
     * input_text card per character. Only the final value matters for replay.
     */
    private fun collapseConsecutiveTextInputSteps(
        steps: List<Map<String, Any?>>,
    ): List<Map<String, Any?>> {
        if (steps.size < 2) return steps
        val output = mutableListOf<Map<String, Any?>>()
        var i = 0
        while (i < steps.size) {
            val step = steps[i]
            if (replayActionForStep(step) !in inputTextActions) {
                output += step
                i++
                continue
            }
            var last = step
            val lastSig = textInputTargetSignature(last)
            if (lastSig.isBlank()) {
                output += last
                i++
                continue
            }
            while (i + 1 < steps.size) {
                val next = steps[i + 1]
                if (replayActionForStep(next) !in inputTextActions) break
                val nextSig = textInputTargetSignature(next)
                if (nextSig.isBlank() || nextSig != lastSig) break
                last = next
                i++
            }
            output += last
            i++
        }
        return output
    }

    /**
     * Drops a click step that immediately precedes input_text on the same target.
     */
    private fun dropRedundantClickBeforeInputText(
        steps: List<Map<String, Any?>>,
    ): List<Map<String, Any?>> {
        if (steps.size < 2) return steps
        val output = mutableListOf<Map<String, Any?>>()
        var i = 0
        while (i < steps.size) {
            val step = steps[i]
            val next = steps.getOrNull(i + 1)
            if (next != null &&
                replayActionForStep(step) == OobActionCodec.ACTION_CLICK &&
                replayActionForStep(next) in inputTextActions &&
                clickAndInputShareTarget(step, next) &&
                clickTargetIsAlreadyEditable(step, next)
            ) {
                i++
                continue
            }
            output += step
            i++
        }
        return output
    }

    private fun dropDuplicateTextInputSteps(
        steps: List<Map<String, Any?>>,
    ): List<Map<String, Any?>> {
        if (steps.size < 2) return steps
        val output = mutableListOf<Map<String, Any?>>()
        steps.forEach { step ->
            val previous = output.lastOrNull()
            if (previous != null && isDuplicateTextInputStep(previous, step)) {
                return@forEach
            }
            output += step
        }
        return output
    }

    /**
     * Drops BACK presses recorded only to dismiss the keyboard between form actions.
     *
     * Replaying BACK as a fixed action is unsafe: when the keyboard is already
     * hidden, Android may show a discard/leave dialog and block the next input.
     * Runtime checker rules own conditional keyboard dismissal.
     */
    private fun dropKeyboardDismissBackBetweenFormActions(
        steps: List<Map<String, Any?>>,
    ): List<Map<String, Any?>> {
        if (steps.size < 3) return steps
        val output = mutableListOf<Map<String, Any?>>()
        for (index in steps.indices) {
            val previous = steps.getOrNull(index - 1)
            val step = steps[index]
            val next = steps.getOrNull(index + 1)
            if (previous != null &&
                next != null &&
                replayActionForStep(previous) in inputTextActions &&
                isBackPressStep(step) &&
                replayActionForStep(next) in setOf(
                    OobActionCodec.ACTION_INPUT_TEXT,
                    OobActionCodec.ACTION_CLICK,
                )
            ) {
                continue
            }
            output += step
        }
        return output
    }

    private fun isDuplicateTextInputStep(
        previous: Map<String, Any?>,
        current: Map<String, Any?>,
    ): Boolean {
        if (replayActionForStep(previous) !in inputTextActions) return false
        if (replayActionForStep(current) !in inputTextActions) return false
        val previousText = textInputValue(previous)
        val currentText = textInputValue(current)
        if (previousText.isBlank() || previousText != currentText) return false
        val previousTarget = textInputTargetSignature(previous)
        val currentTarget = textInputTargetSignature(current)
        return previousTarget.isNotBlank() && previousTarget == currentTarget
    }

    private fun clickAndInputShareTarget(
        clickStep: Map<String, Any?>,
        inputStep: Map<String, Any?>,
    ): Boolean {
        val clickArgs = OobActionCodec.argsForStep(clickStep)
        val inputArgs = OobActionCodec.argsForStep(inputStep)

        val clickResId = firstNonBlank(
            clickArgs["node_resource_id"],
        )
        val inputResId = firstNonBlank(
            inputArgs["node_resource_id"],
        )
        if (clickResId.isNotBlank() && clickResId == inputResId) return true

        val clickBounds = firstNonBlank(clickArgs["bounds"])
        val inputBounds = firstNonBlank(inputArgs["bounds"])
        if (clickBounds.isNotBlank() && clickBounds == inputBounds) return true

        val cx = firstNonBlank(clickArgs["x"]).toFloatOrNull()
        val cy = firstNonBlank(clickArgs["y"]).toFloatOrNull()
        val ix = firstNonBlank(inputArgs["x"]).toFloatOrNull()
        val iy = firstNonBlank(inputArgs["y"]).toFloatOrNull()
        if (cx != null && cy != null && ix != null && iy != null) {
            return kotlin.math.abs(cx - ix) < 80f && kotlin.math.abs(cy - iy) < 80f
        }

        return false
    }

    private fun clickTargetIsAlreadyEditable(
        clickStep: Map<String, Any?>,
        inputStep: Map<String, Any?>,
    ): Boolean {
        val clickArgs = OobActionCodec.argsForStep(clickStep)
        val inputArgs = OobActionCodec.argsForStep(inputStep)

        val clickResId = firstNonBlank(clickArgs["node_resource_id"])
        val inputResId = firstNonBlank(inputArgs["node_resource_id"])
        if (clickResId.isNotBlank() && clickResId == inputResId) return true

        if (firstNonBlank(clickArgs["editable"]).equals("true", ignoreCase = true)) return true
        if (firstNonBlank(OobActionCodec.sourceActionForStep(clickStep)["editable"]).equals("true", ignoreCase = true)) {
            return true
        }

        val x = firstNonBlank(clickArgs["x"]).toFloatOrNull() ?: return false
        val y = firstNonBlank(clickArgs["y"]).toFloatOrNull() ?: return false
        val srcCtx = OobActionCodec.mapArg(OobActionCodec.sourceContextForStep(clickStep)["src_ctx"])
        val sourceXml = OobActionCodec.pageXmlFromContext(srcCtx)
        return nodeAtPointIsEditable(sourceXml, x, y)
    }

    private fun nodeAtPointIsEditable(sourceXml: String, x: Float, y: Float): Boolean {
        if (sourceXml.isBlank()) return false
        var bestArea: Int? = null
        var bestEditable = false
        nodeRegex.findAll(sourceXml).forEach { match ->
            val tag = match.value
            val bounds = boundsRegex.find(tag)?.destructured ?: return@forEach
            val left = bounds.component1().toIntOrNull() ?: return@forEach
            val top = bounds.component2().toIntOrNull() ?: return@forEach
            val right = bounds.component3().toIntOrNull() ?: return@forEach
            val bottom = bounds.component4().toIntOrNull() ?: return@forEach
            if (x < left || x > right || y < top || y > bottom) return@forEach
            val area = (right - left).coerceAtLeast(0) * (bottom - top).coerceAtLeast(0)
            val currentBestArea = bestArea
            if (currentBestArea == null || area < currentBestArea) {
                bestArea = area
                bestEditable = tag.contains("editable=\"true\"") ||
                    tag.contains("class=\"android.widget.EditText\"")
            }
        }
        return bestEditable
    }

    private fun replayActionForStep(step: Map<String, Any?>): String =
        OobActionCodec.actionNameForStep(step)

    private fun isBackPressStep(step: Map<String, Any?>): Boolean {
        if (replayActionForStep(step) != OobActionCodec.ACTION_PRESS_KEY) return false
        val key = firstNonBlank(OobActionCodec.argsForStep(step)["key"])
            .trim()
            .lowercase()
        return key == "back"
    }

    private fun textInputValue(step: Map<String, Any?>): String {
        val args = OobActionCodec.argsForStep(step)
        return firstNonBlank(args["text"])
    }

    private fun textInputTargetSignature(step: Map<String, Any?>): String {
        val args = OobActionCodec.argsForStep(step)
        val action = OobActionCodec.sourceActionForStep(step)
        return firstNonBlank(
            args["node_resource_id"],
            action["node_resource_id"],
            args["selector"],
            action["selector"],
            args["bounds"],
            action["bounds"],
            args["target_description"],
            action["target_description"],
        )
    }

    private val nodeRegex = Regex("""<node\b[^>]*>""")
    private val boundsRegex = Regex("bounds=\"\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]\"")

}
