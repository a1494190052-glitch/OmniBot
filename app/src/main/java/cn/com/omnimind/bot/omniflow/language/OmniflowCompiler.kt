package cn.com.omnimind.bot.omniflow.language

import cn.com.omnimind.baselib.runlog.OobCanonicalActionSchema
import cn.com.omnimind.bot.runlog.OobActionCodec
import cn.com.omnimind.bot.runlog.OobActionCodec.firstNonBlank
import cn.com.omnimind.bot.runlog.OobActionCodec.mapArg
import cn.com.omnimind.bot.runlog.OobActionCodec.listArg
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy
import cn.com.omnimind.bot.runlog.RunLogReplayStepCompiler

/**
 * Single-pass compiler from RunLog cards to the stored OmniflowFunction model.
 * Replaces the 5-stage Map→Map pipeline (Compiler + Parameterizer +
 * SchemaBuilder + SpecBuilder + PatchAppliers).
 */
object OmniflowCompiler {

    private val EXECUTION_BINDING_REGEX =
        Regex("""^\$\.(?:execution\.steps|actions)\[(\d+)]\.args\.(.+)$""")

    // -----------------------------------------------------------------------
    // Compile
    // -----------------------------------------------------------------------

    fun compile(
        cards: List<Map<String, Any?>>,
        functionId: String,
        goal: String = "",
        packageName: String? = null,
        skipPerceptionTools: Boolean = true,
    ): OmniflowFunction {
        // Phase 1: card → Map step (reuse existing card compiler)
        val rawSteps = cards.mapIndexedNotNull { i, card ->
            RunLogReplayStepCompiler.compileCard(
                card = card,
                skipPerceptionTools = skipPerceptionTools,
                nextReplayableCard = cards.getOrNull(i + 1),
            )
        }

        // Phase 2: infer parameters and build binding map (stepIndex, argKey) → parameterId
        val inferredParams = inferParameters(rawSteps)
        val bindingMap: Map<Pair<Int, String>, String> = inferredParams
            .flatMap { p -> p.bindings.map { b -> (b.stepIndex to b.argPath) to p.id } }
            .toMap()

        // Phase 3: translate Map steps to UIStep.
        val steps = rawSteps.mapIndexed { index, step ->
            stepFromMap(step, index, bindingMap)
        }

        // Phase 4: extract source node ID from first executable step
        val sourceNodeId = extractSourceNodeId(rawSteps)

        return OmniflowFunction(
            id = functionId,
            name = goal.trim().takeIf { it.isNotEmpty() } ?: functionId,
            description = goal.trim(),
            parameters = inferredParams,
            steps = steps,
            metadata = FunctionMetadata(
                packageConstraint = packageName,
                sourceNodeId = sourceNodeId,
            ),
        )
    }

    // -----------------------------------------------------------------------
    // Patch operations (replace old PatchApplier classes)
    // -----------------------------------------------------------------------

    fun replaceStepTarget(
        fn: OmniflowFunction,
        stepIndex: Int,
        x: Float? = null,
        y: Float? = null,
        targetDescription: String? = null,
        nodeId: String? = null,
    ): OmniflowFunction {
        val steps = fn.steps.toMutableList()
        if (stepIndex !in steps.indices) return fn
        val old = steps[stepIndex]
        val newArgs = old.arguments.toMutableMap()
        if (x != null) newArgs["x"] = ParameterValue.Literal(x.toString())
        if (y != null) newArgs["y"] = ParameterValue.Literal(y.toString())
        if (targetDescription != null) newArgs["target_description"] = ParameterValue.Literal(targetDescription)
        if (nodeId != null) newArgs["node_id"] = ParameterValue.Literal(nodeId)
        steps[stepIndex] = old.copy(arguments = newArgs)
        return fn.copy(steps = steps)
    }

    fun insertStep(fn: OmniflowFunction, index: Int, step: UIStep): OmniflowFunction {
        val steps = fn.steps.toMutableList()
        steps.add(index.coerceIn(0, steps.size), step)
        return fn.copy(steps = steps)
    }

    fun deleteStep(fn: OmniflowFunction, index: Int): OmniflowFunction {
        if (index !in fn.steps.indices) return fn
        return fn.copy(steps = fn.steps.toMutableList().also { it.removeAt(index) })
    }

    fun updateName(fn: OmniflowFunction, name: String): OmniflowFunction =
        fn.copy(name = name.trim())

    fun updateDescription(fn: OmniflowFunction, description: String): OmniflowFunction =
        fn.copy(description = description.trim())

    fun updateParameters(fn: OmniflowFunction, parameters: List<FunctionParameter>): OmniflowFunction =
        fn.copy(parameters = parameters)

    fun applyCheckerRules(fn: OmniflowFunction, rules: List<UIStepCheckerRule>): OmniflowFunction =
        fn.copy(metadata = fn.metadata.copy(checkerRules = rules))

    // -----------------------------------------------------------------------
    // Parameter inference (replaces RunLogReusableFunctionParameterizer)
    // -----------------------------------------------------------------------

    private fun inferParameters(steps: List<Map<String, Any?>>): List<FunctionParameter> {
        val params = mutableListOf<FunctionParameter>()
        val usedIds = mutableSetOf<String>()
        steps.forEachIndexed { index, step ->
            val tool = OobActionCodec.actionNameForStep(step)
            val args = OobActionCodec.argsForStep(step)
            val candidate = when {
                tool in INPUT_TEXT_ACTIONS -> {
                    val inputKey = INPUT_TEXT_ARG_KEYS.firstOrNull { k ->
                        args[k]?.toString()?.trim()?.isNotEmpty() == true
                    } ?: return@forEachIndexed
                    val defaultValue = args[inputKey]?.toString()?.takeIf { it.isNotBlank() }
                        ?: return@forEachIndexed
                    ParameterCandidate(
                        argPath = inputKey,
                        defaultValue = defaultValue,
                        baseId = paramIdForInputStep(step),
                        descriptionPrefix = "Text",
                    )
                }
                tool == OobActionCodec.ACTION_CLICK -> {
                    val defaultValue = args[CLICK_TARGET_ARG]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                        ?: return@forEachIndexed
                    ParameterCandidate(
                        argPath = CLICK_TARGET_ARG,
                        defaultValue = defaultValue,
                        baseId = paramIdForClickTarget(step, defaultValue),
                        descriptionPrefix = "Click target",
                    )
                }
                else -> return@forEachIndexed
            }
            val baseId = candidate.baseId
            val id = uniqueId(baseId, usedIds)
            usedIds += id
            params += FunctionParameter(
                id = id,
                type = OobCanonicalActionSchema.Type.STRING,
                required = false,
                default = candidate.defaultValue,
                description = "${candidate.descriptionPrefix} for step ${index + 1}: ${step["title"] ?: tool}",
                bindings = listOf(StepArgBinding(stepIndex = index, argPath = candidate.argPath)),
            )
        }
        return params
    }

    private fun paramIdForInputStep(step: Map<String, Any?>): String {
        val rawTitle = firstNonBlank(step["title"], step["summary"])
        semanticParamId(rawTitle)?.let { return it }
        val title = rawTitle
            .take(30)
            .replace(Regex("[^A-Za-z0-9_]+"), "_")
            .trim('_')
            .lowercase()
        return title.takeIf { it.isNotBlank() } ?: "input_text"
    }

    private fun paramIdForClickTarget(step: Map<String, Any?>, targetDescription: String): String {
        val rawTitle = firstNonBlank(targetDescription, step["title"], step["summary"])
        val title = rawTitle
            .take(40)
            .replace(Regex("[^A-Za-z0-9_]+"), "_")
            .trim('_')
            .lowercase()
        return title.takeIf { it.isNotBlank() && it !in INTERNAL_PARAMETER_IDS } ?: "click_target"
    }

    private fun semanticParamId(title: String): String? {
        val normalized = title.lowercase()
        return when {
            "录音" in title && "文件名" in title -> "audio_file_name"
            "联系人" in title && "姓名" in title -> "contact_name"
            "电话" in title || "号码" in title -> "contact_phone"
            "名字" in title -> "first_name"
            "姓氏" in title -> "last_name"
            "note" in normalized && "file" in normalized && "name" in normalized -> "note_file_name"
            "note" in normalized && ("content" in normalized || "text" in normalized) -> "note_content"
            "contact" in normalized && "name" in normalized -> "contact_name"
            "phone" in normalized || "number" in normalized -> "contact_phone"
            "first" in normalized && "name" in normalized -> "first_name"
            "last" in normalized && "name" in normalized -> "last_name"
            else -> null
        }
    }

    private fun uniqueId(base: String, used: Set<String>): String {
        if (base !in used) return base
        var i = 2
        while ("${base}_$i" in used) i++
        return "${base}_$i"
    }

    // -----------------------------------------------------------------------
    // Step translation: Map → UIStep
    // -----------------------------------------------------------------------

    private fun stepFromMap(
        step: Map<String, Any?>,
        index: Int,
        bindingMap: Map<Pair<Int, String>, String>,
    ): UIStep {
        val toolName = firstNonBlank(step["tool"]).ifBlank { "unknown" }
        val kind = step["kind"]?.toString().orEmpty()
        val rawArgs = mapArg(step["args"])
        val skip = RunLogReplayPolicy.shouldSkipTool(toolName)

        val arguments = rawArgs.entries.associate { (k, v) ->
            val parameterId = bindingMap[index to k]
            k to if (parameterId != null) ParameterValue.Bound(parameterId)
                 else ParameterValue.Literal(v?.toString() ?: "")
        }

        val agentCallContext = if (kind == "agent_call") {
            val agentCall = mapArg(step["agent_call"])
            AgentCallContext(
                originalTool = firstNonBlank(step["tool"]),
                originalArgs = rawArgs,
                reason = firstNonBlank(agentCall["reason"]),
                fallback = mapArg(step["fallback"]),
            )
        } else null

        val sourceCtx = mapArg(step["source_context"]).takeIf { it.isNotEmpty() }

        return UIStep(
            id = firstNonBlank(step["id"]).ifBlank { "step_${index + 1}" },
            title = firstNonBlank(step["title"]).ifBlank { toolName },
            toolName = toolName,
            arguments = arguments,
            skip = skip,
            sourceContext = sourceCtx,
            agentCallContext = agentCallContext,
        )
    }

    // -----------------------------------------------------------------------
    // Source node extraction for go-to navigation
    // -----------------------------------------------------------------------

    private fun extractSourceNodeId(steps: List<Map<String, Any?>>): String? {
        for (step in steps) {
            if (RunLogReplayPolicy.shouldSkipTool(step["tool"]?.toString() ?: "")) continue
            val srcCtx = mapArg(mapArg(step["source_context"])["src_ctx"])
            val nodeId = firstNonBlank(srcCtx["node_id"], srcCtx["udeg_node_id"])
            if (nodeId.isNotBlank()) return nodeId
        }
        return null
    }

    // -----------------------------------------------------------------------
    // Extract StepArgBinding from legacy JSONPath string
    // Used when loading an existing function that has legacy binding strings
    // -----------------------------------------------------------------------

    fun bindingFromJsonPath(jsonPath: String): StepArgBinding? {
        val match = EXECUTION_BINDING_REGEX.matchEntire(jsonPath.trim()) ?: return null
        val stepIndex = match.groupValues[1].toIntOrNull() ?: return null
        val argPath = match.groupValues[2]
        return StepArgBinding(stepIndex = stepIndex, argPath = argPath)
    }

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    private val INPUT_TEXT_ACTIONS = setOf(
        OobActionCodec.ACTION_INPUT_TEXT,
        "input_text",
        "type_text",
    )

    private val INPUT_TEXT_ARG_KEYS = listOf("text", "input", "value", "content")
    private const val CLICK_TARGET_ARG = "target_description"
    private data class ParameterCandidate(
        val argPath: String,
        val defaultValue: String,
        val baseId: String,
        val descriptionPrefix: String,
    )
    private val INTERNAL_PARAMETER_IDS = setOf(
        "package_name", "package", "target_description", "target",
        "selector", "node_id", "node_resource_id", "element_index", "scrollable_index",
        "x", "y", "x1", "y1", "x2", "y2", "bounds", "clear", "duration_ms",
    )
}
