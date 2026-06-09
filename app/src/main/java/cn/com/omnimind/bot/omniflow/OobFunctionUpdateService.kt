package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.bot.omniflow.OobFunctionJson.boolArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.boolArgOrDefault
import cn.com.omnimind.bot.omniflow.OobFunctionJson.firstNonBlank
import cn.com.omnimind.bot.omniflow.OobFunctionJson.intArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.listArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mapArg
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mutableJsonList
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mutableJsonMap
import cn.com.omnimind.bot.omniflow.OobFunctionJson.mutableJsonValue
import cn.com.omnimind.bot.runlog.OobActionCodec
import cn.com.omnimind.bot.runlog.OmniflowCheckerRule
import cn.com.omnimind.bot.runlog.OobStepRoleClassifier

/**
 * Applies agent-provided updates to registered OOB Functions.
 * Consolidates OobFunctionStructuralPatchApplier, OobFunctionMetadataPatchApplier,
 * and OobFunctionCheckerPatchService into a single self-contained service.
 */
class OobFunctionUpdateService(
    private val context: Context,
    private val functionRepository: OobFunctionRepository,
    private val targetSourceMatcher: OobFunctionTargetSourceMatcher = OobFunctionTargetSourceMatcher(),
    private val evidencePackager: OobFunctionRunLogEvidencePackager = OobFunctionRunLogEvidencePackager(),
    private val intentParser: OobFunctionUpdateIntentParser = OobFunctionUpdateIntentParser(),
) {
    fun updateFunction(args: Map<String, Any?>?): Map<String, Any?> {
        val updateStartedAtMs = System.currentTimeMillis()
        val request = args ?: emptyMap()
        val runId = firstNonBlank(request["run_id"])
        val runLogTimeline = if (runId.isNotEmpty()) {
            val timeline = InternalRunLogStore.timelinePayload(context, runId)
            if (timeline["success"] != true) {
                return errorPayload(code = "RUN_LOG_NOT_FOUND", message = "RunLog not found: $runId")
            }
            timeline
        } else {
            emptyMap()
        }
        val functionId = firstNonBlank(
            request["function_id"], request["functionId"],
            runLogTimeline["registered_function_id"],
            mapArg(runLogTimeline["registered_function_spec"])["function_id"],
        )
        if (functionId.isEmpty()) {
            return errorPayload(code = "FUNCTION_ID_EMPTY", message = "update_function requires function_id")
        }
        val original = functionRepository.get(functionId)
            ?: return errorPayload(
                code = "OOB_FUNCTION_NOT_FOUND",
                message = "OOB reusable function not found: $functionId",
                functionId = functionId
            )
        val requestedMode = firstNonBlank(request["mode"], request["operation"]).lowercase().ifBlank { "enhance" }
        val dryRun = boolArg(request["dry_run"]) || boolArg(request["dryRun"])
        val instruction = firstNonBlank(request["instruction"], request["request"], request["user_instruction"])
        val analysis = mapArg(request["analysis"]).ifEmpty { mapArg(request["evidence_analysis"]) }
        val patch = mapArg(request["patch"])
            .ifEmpty { mapArg(request["function_patch"]) }
            .ifEmpty { mapArg(request["updates"]) }
            .ifEmpty { mapArg(analysis["recommended_patch"]) }
        val updateCost = updateCostPayload(
            request = request,
            analysis = analysis,
            patch = patch,
            startedAtMs = updateStartedAtMs,
            mode = requestedMode,
        )

        if (runId.isNotEmpty() && analysis.isEmpty() && patch.isEmpty()) {
            val analysisContext = evidencePackager.analysisContext(
                functionId = functionId, functionSpec = original,
                runLogTimeline = runLogTimeline, instruction = instruction,
            )
            return linkedMapOf(
                "success" to true, "function_id" to functionId, "run_id" to runId,
                "mode" to requestedMode, "changed" to false, "saved" to false,
                "dry_run" to dryRun, "requires_confirmation" to false,
                "function" to original, "updated_function" to original,
                "needs_agent_analysis" to true, "analysis_context" to analysisContext,
                "agent_prompt" to evidencePackager.agentPrompt(analysisContext),
                "message" to "已读取 Function 和 RunLog，等待 agent 分析后再保存。",
                "cost" to updateCost,
                "source" to "oob_native_omniflow_toolkit"
            )
        }

        val updated = mutableJsonMap(original)
        val changes = mutableListOf<Map<String, Any?>>()
        val explicitOps = intentParser.operationsFromPatch(patch)
        val inferredOps = if (explicitOps.isEmpty()) intentParser.operationsFromInstruction(instruction) else emptyList()
        val ops = explicitOps + inferredOps
        val inferredRepairIntent = requestedMode == "enhance" && ops.any(intentParser::isReplaceTargetOperation)
        val inferredStructuralIntent = requestedMode == "enhance" && ops.any(intentParser::isStructuralOperation)
        val mode = if (inferredRepairIntent || inferredStructuralIntent) "repair" else requestedMode
        val allowExecutionChange = boolArg(request["allow_execution_change"]) ||
            boolArg(request["allowExecutionChange"]) ||
            mode in setOf("repair", "fix", "correction")
        val allowStructuralChange = boolArg(request["allow_structural_change"]) || boolArg(request["allowStructuralChange"])

        if (patch.isNotEmpty()) changes += applyPatch(updated, patch)
        if (analysis.isNotEmpty()) changes += applyRunLogEvidenceAnalysis(updated, runId, analysis)

        val allCandidates = mutableListOf<Map<String, Any?>>()
        ops.forEach { op ->
            when (firstNonBlank(op["op"], op["type"], op["operation"]).lowercase()) {
                "replace_target", "replace_click_target", "retarget_action" -> {
                    if (!allowExecutionChange) {
                        return errorPayload(
                            code = "EXECUTION_CHANGE_NOT_ALLOWED",
                            message = "replace_target requires mode=repair or allowExecutionChange=true",
                            functionId = functionId
                        ) + linkedMapOf("mode" to mode, "requires_confirmation" to true, "operation" to op)
                    }
                    val result = applyReplaceTargetOperation(updated, op)
                    allCandidates += result.candidates
                    if (result.requiresConfirmation) {
                        return linkedMapOf(
                            "success" to true, "function_id" to functionId, "mode" to mode,
                            "changed" to false, "saved" to false, "dry_run" to dryRun,
                            "requires_confirmation" to true, "reason" to result.reason,
                            "function" to original, "updated_function" to original,
                            "candidates" to allCandidates, "message" to "需要确认要修改哪一步，Function 未保存。",
                            "source" to "oob_native_omniflow_toolkit"
                        )
                    }
                    changes += result.changes
                }
                "insert_step", "add_step", "insert_action", "add_action" -> {
                    if (!allowStructuralChange) {
                        return errorPayload(
                            code = "STRUCTURAL_CHANGE_NOT_ALLOWED",
                            message = "insert_step requires allowStructuralChange=true",
                            functionId = functionId
                        ) + linkedMapOf("mode" to mode, "requires_confirmation" to true, "operation" to op)
                    }
                    changes += applyInsertStepOperation(updated, op)
                }
                "delete_step", "remove_step", "delete_action", "remove_action" -> {
                    if (!allowStructuralChange) {
                        return errorPayload(
                            code = "STRUCTURAL_CHANGE_NOT_ALLOWED",
                            message = "delete_step requires allowStructuralChange=true",
                            functionId = functionId
                        ) + linkedMapOf("mode" to mode, "requires_confirmation" to true, "operation" to op)
                    }
                    changes += applyDeleteStepOperation(updated, op)
                }
            }
        }
        updated["function_id"] = functionId

        val changed = changes.isNotEmpty()
        appendUpdateAudit(
            spec = updated,
            mode = mode,
            instruction = instruction,
            changed = changed,
            dryRun = dryRun,
            changes = changes,
            updateCost = updateCost,
        )
        if (!changed) {
            return linkedMapOf(
                "success" to true, "function_id" to functionId, "mode" to mode,
                "changed" to false, "saved" to false, "dry_run" to dryRun,
                "requires_confirmation" to false, "message" to "未找到可安全应用的 Function 更新。",
                "function" to original, "updated_function" to original,
                "changes" to changes, "cost" to updateCost, "source" to "oob_native_omniflow_toolkit"
            )
        }
        if (dryRun) {
            return linkedMapOf(
                "success" to true, "function_id" to functionId, "mode" to mode,
                "changed" to true, "saved" to false, "dry_run" to true,
                "requires_confirmation" to false, "changes" to changes,
                "function" to original, "updated_function" to updated, "message" to "已生成 Function 更新预览，未保存。",
                "cost" to updateCost,
                "source" to "oob_native_omniflow_toolkit"
            )
        }

        val save = functionRepository.register(updated)
        val savedFunctionId = firstNonBlank(save["function_id"], functionId)
        val identityPreserved = savedFunctionId == functionId && firstNonBlank(updated["function_id"]) == functionId
        val saved = save["success"] == true && identityPreserved
        return linkedMapOf(
            "success" to saved, "function_id" to savedFunctionId,
            "updated_function_id" to firstNonBlank(updated["function_id"], functionId),
            "mode" to mode, "changed" to changed, "saved" to saved, "dry_run" to false,
            "requires_confirmation" to false, "changes" to changes, "save" to save,
            "function" to original,
            "updated_function" to updated,
            "message" to if (saved) "Function 已更新并保存。" else if (!identityPreserved) "Function 更新必须保持同一个 function_id。" else save["error_message"]?.toString() ?: "Function 更新保存失败。",
            "cost" to updateCost,
            "source" to "oob_native_omniflow_toolkit"
        )
    }

    // -----------------------------------------------------------------------
    // Structural patching (was OobFunctionStructuralPatchApplier)
    // -----------------------------------------------------------------------

    private data class ReplaceTargetResult(
        val changes: List<Map<String, Any?>>,
        val candidates: List<Map<String, Any?>>,
        val requiresConfirmation: Boolean,
        val reason: String = "",
    )

    private fun applyReplaceTargetOperation(spec: MutableMap<String, Any?>, op: Map<String, Any?>): ReplaceTargetResult {
        val desiredText = firstNonBlank(op["desired_text"], op["desiredText"], op["new_text"], op["newText"],
            op["prefer_text"], op["preferText"], op["target_text"], op["targetText"])
        val wrongText = firstNonBlank(op["wrong_text"], op["wrongText"], op["old_text"], op["oldText"],
            op["avoid_text"], op["avoidText"])
        if (desiredText.isBlank()) return ReplaceTargetResult(emptyList(), emptyList(), true, "desired_text_missing")
        val rawAction = firstNonBlank(
            op["tool"],
            op["action"],
            op["tool_name"],
            op["toolName"],
            op["action_name"],
            op["actionName"],
        )
        val action = OobActionCodec.canonicalActionForName(rawAction) ?: OobActionCodec.normalizeName(rawAction).ifBlank { OobActionCodec.ACTION_CLICK }
        val execution = mutableJsonMap(mapArg(spec["execution"]))
        val steps = mutableJsonList(listArg(execution["steps"]))
        val explicitIndex = intArg(op["step_index"], op["stepIndex"], op["index"], defaultValue = -1)
        val candidates = targetReplacementCandidates(steps, action, wrongText, explicitIndex)
        val selected = candidates.firstOrNull()
        val ambiguous = selected == null || (explicitIndex < 0 && candidates.size > 1 && targetCandidateScore(candidates[0]) == targetCandidateScore(candidates[1]))
        if (ambiguous) return ReplaceTargetResult(emptyList(), candidates.take(5), true,
            if (candidates.isEmpty()) "target_step_not_found" else "ambiguous_target_step")
        val stepIndex = intArg(selected["step_index"], defaultValue = -1)
        if (stepIndex !in steps.indices) return ReplaceTargetResult(emptyList(), candidates.take(5), true, "selected_step_out_of_range")
        val step = mutableJsonMap(mapArg(steps[stepIndex]))
        val args = mutableJsonMap(mapArg(step["args"]))
        val changes = mutableListOf<Map<String, Any?>>()
        val oldTarget = firstNonBlank(args["target_description"])
        setArgIfChanged(args, "target_description", desiredText, changes, stepIndex)
        val selectorHints = mutableJsonMap(mapArg(args["selector_hints"]))
        val updatedHints = linkedMapOf<String, Any?>().apply {
            putAll(selectorHints); put("strategy", "text_first")
            put("prefer_text", mergeStringList(selectorHints["prefer_text"], desiredText))
            if (wrongText.isNotBlank()) put("avoid_text", mergeStringList(selectorHints["avoid_text"], wrongText))
            put("updated_by", OobFunctionToolNames.FUNCTION_UPDATE)
        }
        if (selectorHints != updatedHints) {
            args["selector_hints"] = updatedHints
            changes += changeMap("step_args", "selector_hints", selectorHints.takeIf { it.isNotEmpty() }, updatedHints, stepIndex)
        }
        val desiredNode = targetSourceMatcher.match(step, args, desiredText, action)
        if (desiredNode != null) {
            setArgIfChanged(args, "x", desiredNode.bounds.centerX, changes, stepIndex)
            setArgIfChanged(args, "y", desiredNode.bounds.centerY, changes, stepIndex)
            setArgIfChanged(args, "bounds", desiredNode.bounds.raw, changes, stepIndex)
            if (desiredNode.resourceId.isNotBlank()) setArgIfChanged(args, "node_resource_id", desiredNode.resourceId, changes, stepIndex)
            args["target_resolution"] = linkedMapOf(
                "source" to "${OobFunctionToolNames.FUNCTION_UPDATE}.source_context_xml",
                "matched_text" to desiredNode.text.takeIf { it.isNotBlank() },
                "matched_content_desc" to desiredNode.contentDesc.takeIf { it.isNotBlank() },
                "resource_id" to desiredNode.resourceId.takeIf { it.isNotBlank() },
                "bounds" to desiredNode.bounds.raw, "score" to desiredNode.score,
            ).filterValues { it != null }
        } else {
            args["target_resolution"] = mapOf("source" to OobFunctionToolNames.FUNCTION_UPDATE, "matched" to false, "reason" to "desired_text_not_found_in_source_context")
        }
        updateStepTextField(step, "title", wrongText, desiredText, action, changes, stepIndex)
        updateStepTextField(step, "summary", wrongText, desiredText, action, changes, stepIndex)
        updateStepTextField(step, "description", wrongText, desiredText, action, changes, stepIndex)
        step["args"] = args; step["updated_by"] = OobFunctionToolNames.FUNCTION_UPDATE
        steps[stepIndex] = step; execution["steps"] = steps; execution["step_count"] = steps.size; spec["execution"] = execution
        changes += linkedMapOf("part" to "repair", "op" to "replace_target", "step_index" to stepIndex, "action" to action,
            "old_target" to oldTarget.takeIf { it.isNotBlank() }, "wrong_text" to wrongText.takeIf { it.isNotBlank() },
            "desired_text" to desiredText, "coordinate_update_applied" to (desiredNode != null)).filterValues { it != null }
        return ReplaceTargetResult(changes, candidates.take(5), false)
    }

    private fun applyInsertStepOperation(spec: MutableMap<String, Any?>, op: Map<String, Any?>): List<Map<String, Any?>> {
        val execution = mutableJsonMap(mapArg(spec["execution"]))
        val steps = mutableJsonList(listArg(execution["steps"]))
        val rawStep = mapArg(op["step"]).ifEmpty { mapArg(op["action_step"]) }.ifEmpty { mapArg(op["new_step"]) }
            .ifEmpty { structuralStepFromOperation(op) }
        if (rawStep.isEmpty()) return emptyList()
        val requestedIndex = intArg(op["step_index"], op["stepIndex"], op["index"], op["before_step_index"], op["beforeStepIndex"], defaultValue = -1)
        val afterIndex = intArg(op["after_step_index"], op["afterStepIndex"], defaultValue = -1)
        val insertIndex = when {
            requestedIndex >= 0 -> requestedIndex.coerceIn(0, steps.size)
            afterIndex >= 0 -> (afterIndex + 1).coerceIn(0, steps.size)
            else -> steps.size
        }
        val inheritedCtx = mapArg(rawStep["source_context"]).ifEmpty {
            if (insertIndex > 0) mapArg(mapArg(steps[insertIndex - 1])["source_context"]) else emptyMap()
        }
        val normalizedStep = if (looksLikeCanonicalStep(rawStep)) mutableJsonMap(rawStep)
            else mutableJsonMap(OobFunctionStepNormalizer.normalizeSimpleRegisteredStep(rawStep, insertIndex, inheritedCtx))
        normalizedStep["index"] = insertIndex
        val existingIds = steps.mapNotNull { firstNonBlank(mapArg(it)["id"]).takeIf { id -> id.isNotBlank() } }.toSet()
        val requestedId = firstNonBlank(rawStep["id"], rawStep["step_id"])
        normalizedStep["id"] = if (requestedId.isNotBlank() && requestedId !in existingIds) requestedId else uniqueStepId(existingIds, insertIndex)
        steps.add(insertIndex, normalizedStep)
        replaceExecutionSteps(spec, execution, steps)
        return listOf(linkedMapOf("part" to "execution", "op" to "insert_step", "step_index" to insertIndex, "step" to compactStep(normalizedStep)))
    }

    private fun applyDeleteStepOperation(spec: MutableMap<String, Any?>, op: Map<String, Any?>): List<Map<String, Any?>> {
        val execution = mutableJsonMap(mapArg(spec["execution"]))
        val steps = mutableJsonList(listArg(execution["steps"]))
        if (steps.isEmpty()) return emptyList()
        val explicitIndex = intArg(op["step_index"], op["stepIndex"], op["index"], defaultValue = -1)
        val stepId = firstNonBlank(op["step_id"], op["stepId"], op["id"])
        val deleteIndex = when {
            explicitIndex in steps.indices -> explicitIndex
            stepId.isNotBlank() -> steps.indexOfFirst { firstNonBlank(mapArg(it)["id"]) == stepId }
            else -> -1
        }
        if (deleteIndex !in steps.indices) return emptyList()
        val removed = mutableJsonMap(mapArg(steps.removeAt(deleteIndex)))
        replaceExecutionSteps(spec, execution, steps)
        return listOf(linkedMapOf("part" to "execution", "op" to "delete_step", "step_index" to deleteIndex,
            "step" to compactStep(removed), "reason" to firstNonBlank(op["reason"]).takeIf { it.isNotBlank() }).filterValues { it != null })
    }

    private fun structuralStepFromOperation(op: Map<String, Any?>): Map<String, Any?> {
        val action = firstNonBlank(
            op["tool"],
            op["action"],
            op["tool_name"],
            op["toolName"],
            op["action_name"],
            op["actionName"],
        )
        if (action.isBlank()) return emptyMap()
        return linkedMapOf<String, Any?>(
            "tool" to action, "title" to firstNonBlank(op["title"], op["summary"], op["description"]).takeIf { it.isNotBlank() },
            "description" to firstNonBlank(op["description"]).takeIf { it.isNotBlank() },
            "args" to mapArg(op["args"]).ifEmpty { mapArg(op["arguments"]) }.takeIf { it.isNotEmpty() },
            "target_description" to firstNonBlank(op["target_description"]).takeIf { it.isNotBlank() },
            "text" to firstNonBlank(op["text"]).takeIf { it.isNotBlank() },
            "x" to op["x"], "y" to op["y"],
            "direction" to firstNonBlank(op["direction"]).takeIf { it.isNotBlank() },
            "package_name" to firstNonBlank(op["package_name"]).takeIf { it.isNotBlank() },
            "source_context" to mapArg(op["source_context"]).takeIf { it.isNotEmpty() },
        ).filterValues { it != null }
    }

    private fun looksLikeCanonicalStep(step: Map<String, Any?>): Boolean =
        firstNonBlank(step["kind"]).isNotBlank() && firstNonBlank(step["executor"]).isNotBlank() &&
            (step.containsKey("args") || step.containsKey("tool"))

    private fun replaceExecutionSteps(spec: MutableMap<String, Any?>, execution: MutableMap<String, Any?>, steps: MutableList<Any?>) {
        val seenIds = mutableSetOf<String>()
        val normalizedSteps = steps.mapIndexed { index, raw ->
            mutableJsonMap(mapArg(raw)).apply {
                put("index", index)
                val currentId = firstNonBlank(this["id"])
                val normalizedId = if (currentId.isNotBlank() && currentId !in seenIds) currentId else uniqueStepId(seenIds, index)
                put("id", normalizedId); seenIds += normalizedId
            }
        }
        val capabilities = OobFunctionStepNormalizer.executionCapabilities(normalizedSteps)
        execution["steps"] = normalizedSteps
        execution["step_count"] = normalizedSteps.size
        execution["omniflow_step_count"] = capabilities["omniflow_step_count"]
        execution["agent_step_count"] = capabilities["agent_step_count"]
        execution["has_agent_steps"] = capabilities["has_agent_steps"]
        execution.remove("requires_agent_fallback")
        execution["capabilities"] = linkedMapOf<String, Any?>().apply {
            putAll(mapArg(execution["capabilities"])); remove("requires_agent_fallback"); putAll(capabilities)
        }
        spec["execution"] = execution
    }

    private fun compactStep(step: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
        "id" to firstNonBlank(step["id"]), "index" to step["index"],
        "title" to firstNonBlank(step["title"], step["summary"]),
        "tool" to OobActionCodec.actionNameForStep(step), "executor" to firstNonBlank(step["executor"]),
    ).filterValues { it != null && it.toString().isNotBlank() }

    private fun uniqueStepId(existingIds: Set<String>, index: Int): String {
        val base = "step_${index + 1}"
        if (base !in existingIds) return base
        var suffix = 1
        while (true) { val c = "${base}_inserted_$suffix"; if (c !in existingIds) return c; suffix++ }
    }

    private fun targetReplacementCandidates(steps: List<Any?>, action: String, wrongText: String, explicitIndex: Int): List<Map<String, Any?>> =
        steps.mapIndexedNotNull { index, rawStep ->
            val step = mapArg(rawStep); val tool = OobActionCodec.actionNameForStep(step)
            val actionMatches = action.isBlank() || action == tool || OobActionCodec.canonicalActionForName(action) == tool
            if (explicitIndex >= 0 && explicitIndex != index) return@mapIndexedNotNull null
            if (!actionMatches && explicitIndex < 0) return@mapIndexedNotNull null
            val args = mapArg(step["args"])
            val argsText = listOf(args["target_description"], args["text"], args["selector"], args["node_resource_id"]).joinToString(" ")
            val labelText = listOf(step["title"], step["summary"], step["description"]).joinToString(" ")
            val score = when {
                explicitIndex == index -> 100
                wrongText.isBlank() && actionMatches -> 10
                containsLoose(argsText, wrongText) -> 80
                containsLoose(labelText, wrongText) -> 55
                else -> 0
            } + if (actionMatches) 10 else 0
            if (score <= 0) return@mapIndexedNotNull null
            linkedMapOf("step_index" to index, "id" to firstNonBlank(step["id"], "step_${index + 1}"),
                "title" to firstNonBlank(step["title"], step["summary"], tool), "tool" to tool, "score" to score,
                "current_target" to firstNonBlank(args["target_description"], args["text"]).takeIf { it.isNotBlank() },
            ).filterValues { it != null }
        }.sortedWith(compareByDescending<Map<String, Any?>> { intArg(it["score"], defaultValue = 0) }.thenBy { intArg(it["step_index"], defaultValue = Int.MAX_VALUE) })

    private fun targetCandidateScore(c: Map<String, Any?>) = intArg(c["score"], defaultValue = 0)
    private fun containsLoose(h: String, n: String) = n.isNotBlank() && h.trim().lowercase().replace(Regex("\\s+"), " ").contains(n.trim().lowercase().replace(Regex("\\s+"), " "))

    private fun setArgIfChanged(args: MutableMap<String, Any?>, field: String, value: Any?, changes: MutableList<Map<String, Any?>>, stepIndex: Int) {
        if (value == null || value.toString().isBlank() || args[field] == value) return
        changes += changeMap("step_args", field, args[field], value, stepIndex); args[field] = value
    }

    private fun updateStepTextField(step: MutableMap<String, Any?>, field: String, wrongText: String, desiredText: String, action: String, changes: MutableList<Map<String, Any?>>, stepIndex: Int) {
        val old = step[field]?.toString()?.takeIf { it.isNotBlank() }
        val next = when {
            old != null && wrongText.isNotBlank() && old.contains(wrongText) -> old.replace(wrongText, desiredText)
            old != null && containsLoose(old, desiredText) -> old
            field == "title" && old.isNullOrBlank() -> when (action) { OobActionCodec.ACTION_INPUT_TEXT -> "填写$desiredText"; OobActionCodec.ACTION_LONG_PRESS -> "长按$desiredText"; else -> "点击$desiredText" }
            field == "description" && old.isNullOrBlank() -> "${when (action) { OobActionCodec.ACTION_INPUT_TEXT -> "填写$desiredText"; else -> "点击$desiredText" }}，避免误选其他相近目标。"
            else -> old
        } ?: return
        if (old == next) return; step[field] = next; changes += changeMap("step_label", field, old, next, stepIndex)
    }

    private fun mergeStringList(raw: Any?, value: String): List<String> {
        val merged = listArg(raw).mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }.toMutableList()
        if (value.isNotBlank() && merged.none { it == value }) merged += value
        return merged
    }

    // -----------------------------------------------------------------------
    // Metadata patching (was OobFunctionMetadataPatchApplier)
    // -----------------------------------------------------------------------

    private fun applyPatch(spec: MutableMap<String, Any?>, patch: Map<String, Any?>): List<Map<String, Any?>> {
        val changes = mutableListOf<Map<String, Any?>>()
        setStringFieldIfChanged(spec, "name", patch["name"], changes, "header")
        setStringFieldIfChanged(spec, "description", patch["description"], changes, "header")
        applyStepLabelPatches(spec, patch, changes)
        applyParameterPatch(spec, patch, changes)
        applyAgentReusePatch(spec, patch, changes)
        applyMetadataPatch(spec, patch, changes)
        applyTopLevelCheckerRulesPatch(spec, patch, changes)
        changes += applyOptionalCheckerMetadataFromSteps(spec)
        return changes
    }

    private fun applyRunLogEvidenceAnalysis(spec: MutableMap<String, Any?>, runId: String, analysis: Map<String, Any?>): List<Map<String, Any?>> {
        val metadata = mutableJsonMap(mapArg(spec["metadata"]))
        val existing = mutableJsonMap(mapArg(metadata["oob_function_evidence"]))
        val sourceRunIds = listArg(existing["source_run_ids"]).mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }.toMutableList()
        if (runId.isNotBlank() && sourceRunIds.none { it == runId }) sourceRunIds += runId
        val evidence = linkedMapOf<String, Any?>().apply {
            putAll(existing); put("schema_version", "oob.function_evidence.v1")
            put("source", "update_function.runlog_analysis")
            put("latest_run_id", runId.takeIf { it.isNotBlank() })
            put("source_run_ids", sourceRunIds); put("latest_analysis", mutableJsonValue(analysis))
            put("updated_at_ms", System.currentTimeMillis())
        }.filterValues { it != null }
        if (existing == evidence) return emptyList()
        metadata["oob_function_evidence"] = evidence; spec["metadata"] = metadata
        return listOf(changeMap("metadata", "oob_function_evidence", existing.takeIf { it.isNotEmpty() }, evidence))
    }

    private fun appendUpdateAudit(
        spec: MutableMap<String, Any?>,
        mode: String,
        instruction: String,
        changed: Boolean,
        dryRun: Boolean,
        changes: List<Map<String, Any?>>,
        updateCost: Map<String, Any?>,
    ) {
        val metadata = mutableJsonMap(mapArg(spec["metadata"]))
        metadata["oob_function_update"] = linkedMapOf(
            "schema_version" to "oob.function_update.v1", "tool" to OobFunctionToolNames.FUNCTION_UPDATE,
            "mode" to mode, "status" to if (changed) "updated" else "unchanged",
            "changed" to changed, "dry_run" to dryRun,
            "instruction" to instruction.takeIf { it.isNotBlank() },
            "change_count" to changes.size, "updated_at_ms" to System.currentTimeMillis(),
            "cost" to updateCost.takeIf { it.isNotEmpty() },
        ).filterValues { it != null }
        if (mode == "enhance" || metadata["oob_enhancement"] != null) {
            metadata["oob_enhancement"] = linkedMapOf(
                "schema_version" to "oob.function_enhancement.v1",
                "source" to OobFunctionToolNames.FUNCTION_UPDATE,
                "status" to if (changed) "enhanced" else "unchanged", "changed" to changed,
                "message" to if (changed) "Agent enhancement applied through update_function." else "No safe useful enhancement was applied.",
                "updated_at_ms" to System.currentTimeMillis(),
                "cost" to updateCost.takeIf { it.isNotEmpty() },
            )
        }
        spec["metadata"] = metadata
    }

    private fun updateCostPayload(
        request: Map<String, Any?>,
        analysis: Map<String, Any?>,
        patch: Map<String, Any?>,
        startedAtMs: Long,
        mode: String,
    ): Map<String, Any?> {
        val usage = firstNonEmptyMap(
            request["usage"],
            request["token_usage"],
            request["tokenUsage"],
            analysis["usage"],
            analysis["token_usage"],
            analysis["tokenUsage"],
            patch["usage"],
            patch["token_usage"],
            patch["tokenUsage"],
        )
        val cost = firstCostMap(
            request["cost"],
            request["cost_estimate"],
            request["costEstimate"],
            analysis["cost"],
            analysis["cost_estimate"],
            analysis["costEstimate"],
            patch["cost"],
            patch["cost_estimate"],
            patch["costEstimate"],
        )
        val endedAtMs = System.currentTimeMillis()
        return linkedMapOf(
            "mode" to mode.takeIf { it.isNotBlank() },
            "backend" to (firstNonBlank(request["source"], analysis["source"]).takeIf { it.isNotBlank() }
                ?: "oob_native_omniflow_toolkit"),
            "started_at_ms" to startedAtMs,
            "ended_at_ms" to endedAtMs,
            "duration_ms" to (endedAtMs - startedAtMs).coerceAtLeast(0L),
            "usage" to usage.takeIf { it.isNotEmpty() },
            "cost" to cost.takeIf { it.isNotEmpty() },
        ).filterValues { it != null }
    }

    private fun firstNonEmptyMap(vararg values: Any?): Map<String, Any?> {
        for (value in values) {
            val mapped = mapArg(value).filterKeys { it.isNotBlank() }
            if (mapped.isNotEmpty()) return mapped
        }
        return emptyMap()
    }

    private fun firstCostMap(vararg values: Any?): Map<String, Any?> {
        for (value in values) {
            val mapped = mapArg(value).filterKeys { it.isNotBlank() }
            if (mapped.isNotEmpty()) return mapped
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) return linkedMapOf("total" to text)
        }
        return emptyMap()
    }

    private fun applyStepLabelPatches(spec: MutableMap<String, Any?>, patch: Map<String, Any?>, changes: MutableList<Map<String, Any?>>) {
        val stepPatches = listArg(patch["steps"]).mapNotNull { mapArg(it).takeIf { sp -> sp.isNotEmpty() } }
        if (stepPatches.isEmpty()) return
        val execution = mutableJsonMap(mapArg(spec["execution"]))
        val steps = mutableJsonList(listArg(execution["steps"]))
        stepPatches.forEach { sp ->
            val index = intArg(sp["index"], sp["step_index"], sp["stepIndex"], defaultValue = -1)
            val stepIndex = if (index >= 0) index else {
                val stepId = firstNonBlank(sp["id"], sp["step_id"], sp["stepId"])
                steps.indexOfFirst { firstNonBlank(mapArg(it)["id"]) == stepId }
            }
            if (stepIndex !in steps.indices) return@forEach
            val step = mutableJsonMap(mapArg(steps[stepIndex]))
            setStringFieldIfChanged(step, "title", sp["title"], changes, "step_label", stepIndex)
            setStringFieldIfChanged(step, "summary", sp["summary"], changes, "step_label", stepIndex)
            setStringFieldIfChanged(step, "description", sp["description"], changes, "step_label", stepIndex)
            val ca = mapArg(sp["cleanup_annotation"]).ifEmpty { mapArg(sp["cleanupAnnotation"]) }
            val oldCleanupAnnotation = mapArg(step["cleanup_annotation"])
            if (ca.isNotEmpty() && oldCleanupAnnotation != ca) {
                step["cleanup_annotation"] = ca
                changes += changeMap("step_cleanup", "cleanup_annotation", oldCleanupAnnotation.takeIf { it.isNotEmpty() }, ca, stepIndex)
            }
            steps[stepIndex] = step
        }
        execution["steps"] = steps; execution["step_count"] = steps.size; spec["execution"] = execution
    }

    private fun applyParameterPatch(spec: MutableMap<String, Any?>, patch: Map<String, Any?>, changes: MutableList<Map<String, Any?>>) {
        val parameters = listArg(patch["parameters"]).mapNotNull { mapArg(it).takeIf { p -> p.isNotEmpty() } }.filter(::isSafeParameterPatch)
        if (parameters.isNotEmpty() && spec["parameters"] != parameters) {
            val old = spec["parameters"]; spec["parameters"] = parameters; changes += changeMap("parameters", "parameters", old, parameters)
        }
    }

    private fun applyAgentReusePatch(spec: MutableMap<String, Any?>, patch: Map<String, Any?>, changes: MutableList<Map<String, Any?>>) {
        val agentReuse = mapArg(patch["agent_reuse"]).ifEmpty { mapArg(patch["agentReuse"]) }
        if (agentReuse.isEmpty()) return
        val old = mutableJsonMap(mapArg(spec["agent_reuse"]))
        val merged = linkedMapOf<String, Any?>().apply { putAll(old); putAll(agentReuse) }
        if (old != merged) { spec["agent_reuse"] = merged; changes += changeMap("agent_reuse", "agent_reuse", old.takeIf { it.isNotEmpty() }, merged) }
    }

    private fun applyMetadataPatch(spec: MutableMap<String, Any?>, patch: Map<String, Any?>, changes: MutableList<Map<String, Any?>>) {
        val mp = mapArg(patch["metadata"]); if (mp.isEmpty()) return
        val metadata = mutableJsonMap(mapArg(spec["metadata"]))
        mp.forEach { (key, value) ->
            if (key == "function_id" || key == "execution") return@forEach
            val metadataKey = if (key == "checkerRules") "checker_rules" else key
            if (metadataKey == "checker_rules") { changes += applyCheckerRulesPatch(metadata, value); return@forEach }
            val safeValue = mutableJsonValue(value)
            if (metadata[metadataKey] != safeValue) { changes += changeMap("metadata", metadataKey, metadata[metadataKey], safeValue); metadata[metadataKey] = safeValue }
        }
        spec["metadata"] = metadata
    }

    private fun applyTopLevelCheckerRulesPatch(spec: MutableMap<String, Any?>, patch: Map<String, Any?>, changes: MutableList<Map<String, Any?>>) {
        val rules = listArg(patch["checker_rules"]).ifEmpty { listArg(patch["checkerRules"]) }
        if (rules.isEmpty()) return
        val metadata = mutableJsonMap(mapArg(spec["metadata"]))
        changes += applyCheckerRulesPatch(metadata, rules); spec["metadata"] = metadata
    }

    private fun isSafeParameterPatch(parameter: Map<String, Any?>): Boolean {
        val bindings = listArg(parameter["bindings"]).mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        if (bindings.isEmpty()) return true
        val forbidden = listOf(".x", ".y", "bounds", "center_x", "center_y", "width", "height", "screenshot", "xml", "source_context")
        return bindings.all { b -> forbidden.none { b.lowercase().contains(it) } }
    }

    private fun setStringFieldIfChanged(target: MutableMap<String, Any?>, field: String, rawValue: Any?, changes: MutableList<Map<String, Any?>>, part: String, stepIndex: Int? = null) {
        val value = rawValue?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val old = target[field]?.toString(); if (old == value) return
        target[field] = value; changes += changeMap(part, field, old, value, stepIndex)
    }

    // -----------------------------------------------------------------------
    // Checker patching (was OobFunctionCheckerPatchService)
    // -----------------------------------------------------------------------

    private fun applyCheckerRulesPatch(metadata: MutableMap<String, Any?>, rawRules: Any?): List<Map<String, Any?>> {
        val additions = listArg(rawRules).mapNotNull { sanitizeCheckerRule(mapArg(it)) }
        if (additions.isEmpty()) return emptyList()
        val existing = listArg(metadata["checker_rules"])
        val merged = mergeCheckerRules(existing, additions)
        if (existing == merged) return emptyList()
        metadata["checker_rules"] = merged
        return listOf(changeMap("metadata", "checker_rules", existing.takeIf { it.isNotEmpty() }, merged))
    }

    private fun applyOptionalCheckerMetadataFromSteps(spec: MutableMap<String, Any?>): List<Map<String, Any?>> {
        val steps = listArg(mapArg(spec["execution"])["steps"]).mapNotNull { mapArg(it).takeIf { s -> s.isNotEmpty() } }
        if (steps.isEmpty()) return emptyList()
        val changes = mutableListOf<Map<String, Any?>>()
        val metadata = mutableJsonMap(mapArg(spec["metadata"]))
        val existingRules = listArg(metadata["checker_rules"])
        val mergedRules = mergeCheckerRules(existingRules, steps.mapIndexedNotNull { i, s -> optionalCheckerRuleForStep(s, i) })
        val signatureToId = checkerRuleSignatureToId(mergedRules)
        val checkerAssets = steps.mapIndexedNotNull { i, s ->
            val rule = optionalCheckerRuleForStep(s, i) ?: return@mapIndexedNotNull null
            checkerAssetForStep(signatureToId[checkerRuleSignature(rule)] ?: firstNonBlank(rule["id"]), s, i)
        }
        if (existingRules != mergedRules) {
            changes += changeMap("metadata", "checker_rules", existingRules.takeIf { it.isNotEmpty() }, mergedRules)
            metadata["checker_rules"] = mergedRules; spec["metadata"] = metadata
        }
        if (checkerAssets.isNotEmpty()) {
            val agentReuse = mutableJsonMap(mapArg(spec["agent_reuse"]))
            val existingAssets = listArg(agentReuse["checker_assets"])
            val mergedAssets = mergeCheckerAssets(existingAssets, checkerAssets)
            if (existingAssets != mergedAssets) {
                changes += changeMap("agent_reuse", "checker_assets", existingAssets.takeIf { it.isNotEmpty() }, mergedAssets)
                agentReuse["checker_assets"] = mergedAssets; spec["agent_reuse"] = agentReuse
            }
        }
        return changes
    }

    private fun optionalCheckerRuleForStep(step: Map<String, Any?>, stepIndex: Int): Map<String, Any?>? {
        val annotation = mapArg(step["cleanup_annotation"]); if (!isOptionalCheckerAnnotation(annotation)) return null
        val text = checkerInferenceText(step, annotation)
        val condition = when {
            containsAnyIn(text, listOf("resolver", "chooser", "open with", "always open", "始终打开", "打开方式")) -> OmniflowCheckerRule.COND_RESOLVER_DIALOG
            containsAnyIn(text, listOf("upgrade", "update", "version", "hi升级", "新版本", "升级", "更新")) -> OmniflowCheckerRule.COND_APP_UPGRADE_PROMPT
            containsAnyIn(text, listOf("keyboard", "ime", "键盘", "输入法")) -> OmniflowCheckerRule.COND_KEYBOARD_OBSCURING
            containsAnyIn(text, listOf("permission", "allow", "authorize", "grant", "权限", "授权", "允许")) -> OmniflowCheckerRule.COND_PERMISSION_DIALOG
            else -> OmniflowCheckerRule.COND_OVERLAY_BLOCKING
        }
        return linkedMapOf("id" to "optional_checker_step_${stepIndex}_$condition",
            "phase" to OmniflowCheckerRule.phaseForCondition(condition), "condition" to condition,
            "action" to OmniflowCheckerRule.actionForCondition(condition), "enabled" to true, "params" to emptyMap<String, Any?>())
    }

    private fun isOptionalCheckerAnnotation(a: Map<String, Any?>) = listOf(
        firstNonBlank(a["cleanup_action"], a["cleanupAction"], a["action"]),
        firstNonBlank(a["usefulness"]), firstNonBlank(a["category"]), firstNonBlank(a["role"]), firstNonBlank(a["kind"])
    ).any { OobStepRoleClassifier.isCheckerCandidateRole(it) }

    private fun checkerInferenceText(step: Map<String, Any?>, a: Map<String, Any?>) = listOf(
        step["title"], step["summary"], step["description"], a["optional_condition"], a["optionalCondition"],
        a["reason"], a["action_purpose"], mapArg(step["args"])["target_description"], mapArg(step["args"])["text"]
    ).joinToString(" ") { it?.toString().orEmpty() }.lowercase()

    private fun sanitizeCheckerRule(raw: Map<String, Any?>): Map<String, Any?>? {
        if (raw.isEmpty()) return null
        val condition = OmniflowCheckerRule.normalizeCondition(firstNonBlank(raw["condition"], raw["when"], raw["type"]))
        if (condition.isBlank()) return null
        val action = OmniflowCheckerRule.normalizeAction(raw = firstNonBlank(raw["action"], raw["then"], raw["effect"]), condition = condition)
        if (action.isBlank() || !OmniflowCheckerRule.isSupportedPair(condition, action)) return null
        val params = mutableMapOf<String, Any?>()
        val rp = mapArg(raw["params"])
        val pkg = firstNonBlank(rp["package_name"], rp["packageName"], raw["package_name"], raw["packageName"])
        if (condition == OmniflowCheckerRule.COND_PACKAGE_MISMATCH && Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$").matches(pkg)) params["package_name"] = pkg
        return linkedMapOf("id" to safeCheckerRuleId(firstNonBlank(raw["id"], "function_checker")),
            "phase" to OmniflowCheckerRule.phaseForCondition(condition), "condition" to condition,
            "action" to action, "enabled" to boolArgOrDefault(raw["enabled"], true), "params" to params)
    }

    private fun mergeCheckerRules(existing: List<Any?>, additions: List<Map<String, Any?>>): List<Any?> {
        if (additions.isEmpty()) return existing
        val output = existing.map { mutableJsonValue(it) }.toMutableList()
        val signatures = existing.mapNotNull { sanitizeCheckerRule(mapArg(it))?.let(::checkerRuleSignature) }.toMutableSet()
        val usedIds = existing.mapNotNull { firstNonBlank(mapArg(it)["id"]).takeIf(String::isNotBlank) }.toMutableSet()
        additions.forEach { rule ->
            val sig = checkerRuleSignature(rule); if (!signatures.add(sig)) return@forEach
            val r = mutableJsonMap(rule); r["id"] = uniqueCheckerRuleId(firstNonBlank(r["id"], "function_checker"), usedIds); output += r
        }
        return output
    }

    private fun checkerRuleSignatureToId(rules: List<Any?>): Map<String, String> =
        rules.mapNotNull { sanitizeCheckerRule(mapArg(it))?.let { s -> checkerRuleSignature(s) to firstNonBlank(mapArg(it)["id"], s["id"]) } }.toMap()

    private fun checkerRuleSignature(rule: Map<String, Any?>): String {
        val p = mapArg(rule["params"])
        return listOf(rule["phase"], rule["condition"], rule["action"], firstNonBlank(p["package_name"], p["packageName"])).joinToString("|") { it?.toString().orEmpty() }
    }

    private fun checkerAssetForStep(checkerId: String, step: Map<String, Any?>, stepIndex: Int): Map<String, Any?>? {
        if (checkerId.isBlank()) return null
        val a = mapArg(step["cleanup_annotation"])
        val reason = firstNonBlank(a["optional_condition"], a["reason"], a["action_purpose"], step["description"], step["summary"], step["title"])
        return linkedMapOf("checker_id" to checkerId, "step_index" to stepIndex,
            "step_id" to firstNonBlank(step["id"], "step_${stepIndex + 1}"),
            "role" to OobStepRoleClassifier.ROLE_CHECKER_CANDIDATE, "materialization" to "metadata_checker_rule",
            "reason" to reason.takeIf { it.isNotBlank() }).filterValues { it != null }
    }

    private fun mergeCheckerAssets(existing: List<Any?>, additions: List<Map<String, Any?>>): List<Any?> {
        if (additions.isEmpty()) return existing
        val output = existing.map { mutableJsonValue(it) }.toMutableList()
        val seen = existing.mapNotNull { checkerAssetSignature(mapArg(it)).takeIf(String::isNotBlank) }.toMutableSet()
        additions.forEach { if (checkerAssetSignature(it).isNotBlank() && seen.add(checkerAssetSignature(it))) output += mutableJsonMap(it) }
        return output
    }

    private fun checkerAssetSignature(asset: Map<String, Any?>): String {
        val id = firstNonBlank(asset["checker_id"], asset["checkerId"])
        val idx = intArg(asset["step_index"], asset["stepIndex"], asset["index"], defaultValue = -1)
        val stepId = firstNonBlank(asset["step_id"], asset["stepId"])
        return if (id.isBlank() || idx < 0) "" else "$id|$idx|$stepId"
    }

    private fun safeCheckerRuleId(raw: String): String {
        val n = raw.replace(Regex("([a-z])([A-Z])"), "$1_$2").replace(Regex("[^A-Za-z0-9_]+"), "_")
            .lowercase().replace(Regex("_+"), "_").trim('_').take(80).trim('_')
        return n.ifBlank { "function_checker" }
    }

    private fun uniqueCheckerRuleId(raw: String, usedIds: MutableSet<String>): String {
        val base = safeCheckerRuleId(raw); var candidate = base; var suffix = 2
        while (candidate in usedIds) { val s = "_$suffix"; candidate = base.take((80 - s.length).coerceAtLeast(1)).trimEnd('_') + s; suffix++ }
        usedIds += candidate; return candidate
    }

    private fun containsAnyIn(text: String, needles: List<String>) = needles.any { text.contains(it) }

    // -----------------------------------------------------------------------
    // Shared utilities
    // -----------------------------------------------------------------------

    private fun changeMap(part: String, field: String, old: Any?, new: Any?, stepIndex: Int? = null): Map<String, Any?> =
        linkedMapOf<String, Any?>("part" to part, "field" to field, "step_index" to stepIndex, "old" to old, "new" to new).filterValues { it != null }

    private fun errorPayload(code: String, message: String, functionId: String = ""): Map<String, Any?> =
        linkedMapOf("success" to false, "error_code" to code, "error_message" to message, "function_id" to functionId)
}
