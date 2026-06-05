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
            if (tool !in INPUT_TEXT_ACTIONS) return@forEachIndexed
            val args = OobActionCodec.argsForStep(step)
            val inputKey = INPUT_TEXT_ARG_KEYS.firstOrNull { k ->
                args[k]?.toString()?.trim()?.isNotEmpty() == true
            } ?: return@forEachIndexed
            val defaultValue = args[inputKey]?.toString()?.takeIf { it.isNotBlank() }
                ?: return@forEachIndexed
            val baseId = paramIdForInputStep(step, index)
            val id = uniqueId(baseId, usedIds)
            usedIds += id
            params += FunctionParameter(
                id = id,
                type = OobCanonicalActionSchema.Type.STRING,
                required = false,
                default = defaultValue,
                description = "Text for step ${index + 1}: ${step["title"] ?: tool}",
                bindings = listOf(StepArgBinding(stepIndex = index, argPath = inputKey)),
            )
        }
        return params
    }

    private fun paramIdForInputStep(step: Map<String, Any?>, index: Int): String {
        val title = firstNonBlank(step["title"], step["summary"])
            .take(30)
            .replace(Regex("[^A-Za-z0-9_]+"), "_")
            .trim('_')
            .lowercase()
        return title.takeIf { it.isNotBlank() } ?: "input_text"
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
}
