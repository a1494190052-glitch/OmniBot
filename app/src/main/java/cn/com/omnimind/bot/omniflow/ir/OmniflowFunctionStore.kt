package cn.com.omnimind.bot.omniflow.ir

import android.content.Context
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.runlog.RunLogReplayPolicy
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import org.json.JSONArray

/**
 * Stores and retrieves OmniflowFunction IR instances.
 * Uses a separate key namespace from the legacy OobReusableFunctionStore.
 */
object OmniflowFunctionStore {
    private const val TAG = "OmniflowFunctionStore"
    private const val PREFS_NAME = "omniflow_functions_ir"
    private const val INDEX_KEY = "omniflow_ir_index_v1"
    private const val FN_PREFIX = "omniflow_ir_fn:"

    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val mapType = object : TypeToken<Map<String, Any?>>() {}.type

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    @Synchronized
    fun save(context: Context, fn: OmniflowFunction) {
        val id = fn.id.trim()
        require(id.isNotEmpty()) { "OmniflowFunction.id must not be empty" }
        val json = gson.toJson(serialize(fn))
        prefs(context).edit().putString("$FN_PREFIX$id", json).apply()
        val index = readIndex(context).toMutableList()
        if (id !in index) {
            index.add(0, id)
            writeIndex(context, index)
        }
        OmniLog.d(TAG, "saved function $id (${fn.steps.size} steps)")
    }

    @Synchronized
    fun get(context: Context, functionId: String): OmniflowFunction? {
        val id = functionId.trim()
        if (id.isEmpty()) return null
        val json = prefs(context).getString("$FN_PREFIX$id", null)
            ?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val map: Map<String, Any?> = gson.fromJson(json, mapType)
            deserialize(map)
        }.onFailure {
            OmniLog.w(TAG, "failed to deserialize function $id: ${it.message}")
        }.getOrNull()
    }

    @Synchronized
    fun list(context: Context): List<OmniflowFunction> {
        return readIndex(context).mapNotNull { get(context, it) }
    }

    @Synchronized
    fun ids(context: Context): List<String> = readIndex(context)

    @Synchronized
    fun contains(context: Context, functionId: String): Boolean {
        val id = functionId.trim()
        return id.isNotEmpty() && prefs(context).contains("$FN_PREFIX$id")
    }

    @Synchronized
    fun delete(context: Context, functionId: String): Boolean {
        val id = functionId.trim()
        if (id.isEmpty()) return false
        val prefs = prefs(context)
        if (!prefs.contains("$FN_PREFIX$id")) return false
        prefs.edit().remove("$FN_PREFIX$id").apply()
        val index = readIndex(context).toMutableList()
        index.remove(id)
        writeIndex(context, index)
        return true
    }

    @Synchronized
    fun clear(context: Context) {
        val prefs = prefs(context)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(FN_PREFIX) }.forEach { editor.remove(it) }
        editor.remove(INDEX_KEY)
        editor.apply()
    }

    // -----------------------------------------------------------------------
    // Serialization — plain Map<String, Any?> compatible with Gson
    // -----------------------------------------------------------------------

    fun serialize(fn: OmniflowFunction): Map<String, Any?> = linkedMapOf(
        "id" to fn.id,
        "name" to fn.name,
        "description" to fn.description,
        "parameters" to fn.parameters.map { p ->
            linkedMapOf(
                "id" to p.id,
                "type" to p.type.name,
                "required" to p.required,
                "default" to p.default,
                "description" to p.description,
                "bindings" to p.bindings.map { b ->
                    linkedMapOf("step_index" to b.stepIndex, "arg_path" to b.argPath)
                },
            )
        },
        "steps" to fn.steps.map { s ->
            linkedMapOf<String, Any?>(
                "id" to s.id,
                "title" to s.title,
                "tool_name" to s.toolName,
                "arguments" to s.arguments.mapValues { (_, v) -> serializeValue(v) },
                "skip" to s.skip,
                "source_context" to s.sourceContext,
                "checker_rules" to s.checkerRules.map { r ->
                    linkedMapOf(
                        "id" to r.id, "phase" to r.phase, "condition" to r.condition,
                        "action" to r.action, "enabled" to r.enabled, "params" to r.params,
                    )
                },
                "agent_call_context" to s.agentCallContext?.let { a ->
                    linkedMapOf(
                        "original_tool" to a.originalTool,
                        "original_args" to a.originalArgs,
                        "reason" to a.reason,
                        "fallback" to a.fallback,
                    )
                },
            ).also { m -> m.entries.removeIf { it.value == null || it.value == false || (it.value is List<*> && (it.value as List<*>).isEmpty()) } }
        },
        "metadata" to linkedMapOf(
            "package_constraint" to fn.metadata.packageConstraint,
            "source_node_id" to fn.metadata.sourceNodeId,
            "source_run_id" to fn.metadata.sourceRunId,
            "checker_rules" to fn.metadata.checkerRules.map { r ->
                linkedMapOf("id" to r.id, "phase" to r.phase, "condition" to r.condition,
                    "action" to r.action, "enabled" to r.enabled, "params" to r.params)
            },
        ),
    )

    @Suppress("UNCHECKED_CAST")
    fun deserialize(map: Map<String, Any?>): OmniflowFunction {
        fun str(m: Map<String, Any?>, k: String) = m[k]?.toString().orEmpty()
        fun mapOf_(m: Map<String, Any?>, k: String) = (m[k] as? Map<*, *>)
            ?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()
        fun listOf_(m: Map<String, Any?>, k: String) = (m[k] as? List<*>) ?: emptyList<Any?>()

        val parameters = listOf_(map, "parameters").mapNotNull { raw ->
            val p = raw as? Map<*, *> ?: return@mapNotNull null
            val pm = p.entries.associate { it.key.toString() to it.value }
            FunctionParameter(
                id = str(pm, "id"),
                type = runCatching {
                    cn.com.omnimind.baselib.runlog.OobCanonicalActionSchema.Type
                        .valueOf(str(pm, "type").uppercase())
                }.getOrDefault(cn.com.omnimind.baselib.runlog.OobCanonicalActionSchema.Type.STRING),
                required = pm["required"] as? Boolean ?: false,
                default = pm["default"]?.toString(),
                description = str(pm, "description"),
                bindings = listOf_(pm, "bindings").mapNotNull { rb ->
                    val b = rb as? Map<*, *> ?: return@mapNotNull null
                    val bm = b.entries.associate { it.key.toString() to it.value }
                    val idx = (bm["step_index"] as? Number)?.toInt() ?: return@mapNotNull null
                    StepArgBinding(stepIndex = idx, argPath = str(bm, "arg_path"))
                },
            )
        }

        val steps = listOf_(map, "steps").mapNotNull { raw ->
            val s = raw as? Map<*, *> ?: return@mapNotNull null
            val sm = s.entries.associate { it.key.toString() to it.value }
            val arguments = (sm["arguments"] as? Map<*, *>)?.entries?.associate { (k, v) ->
                k.toString() to deserializeValue(v as? Map<*, *>)
            } ?: emptyMap()
            val checkerRules = listOf_(sm, "checker_rules").mapNotNull { cr ->
                val rm = (cr as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
                    ?: return@mapNotNull null
                OmniflowStepCheckerRule(
                    id = str(rm, "id"), phase = str(rm, "phase"),
                    condition = str(rm, "condition"), action = str(rm, "action"),
                    enabled = rm["enabled"] as? Boolean ?: true,
                    params = mapOf_(rm, "params"),
                )
            }
            val acc = (sm["agent_call_context"] as? Map<*, *>)?.let { a ->
                val am = a.entries.associate { it.key.toString() to it.value }
                AgentCallContext(
                    originalTool = str(am, "original_tool"),
                    originalArgs = mapOf_(am, "original_args"),
                    reason = str(am, "reason"),
                    fallback = mapOf_(am, "fallback"),
                )
            }
            OmniflowStep(
                id = str(sm, "id"),
                title = str(sm, "title"),
                toolName = str(sm, "tool_name"),
                arguments = arguments,
                skip = sm["skip"] as? Boolean ?: false,
                sourceContext = (sm["source_context"] as? Map<*, *>)?.entries
                    ?.associate { it.key.toString() to it.value },
                checkerRules = checkerRules,
                agentCallContext = acc,
            )
        }

        val meta = mapOf_(map, "metadata")
        return OmniflowFunction(
            id = str(map, "id"),
            name = str(map, "name"),
            description = str(map, "description"),
            parameters = parameters,
            steps = steps,
            metadata = FunctionMetadata(
                packageConstraint = meta["package_constraint"]?.toString(),
                sourceNodeId = meta["source_node_id"]?.toString(),
                sourceRunId = meta["source_run_id"]?.toString(),
                checkerRules = (listOf_(meta, "checker_rules")).mapNotNull { cr ->
                    val rm = (cr as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
                        ?: return@mapNotNull null
                    OmniflowStepCheckerRule(
                        id = str(rm, "id"), phase = str(rm, "phase"),
                        condition = str(rm, "condition"), action = str(rm, "action"),
                        enabled = rm["enabled"] as? Boolean ?: true,
                        params = (rm["params"] as? Map<*, *>)?.entries
                            ?.associate { it.key.toString() to it.value } ?: emptyMap(),
                    )
                },
            ),
        )
    }

    // -----------------------------------------------------------------------
    // ParameterValue serialization
    // -----------------------------------------------------------------------

    private fun serializeValue(v: ParameterValue): Map<String, Any?> = when (v) {
        is ParameterValue.Literal -> mapOf("t" to "l", "v" to v.value)
        is ParameterValue.Bound   -> mapOf("t" to "b", "p" to v.parameterId)
    }

    private fun deserializeValue(m: Map<*, *>?): ParameterValue {
        if (m == null) return ParameterValue.Literal("")
        return when (m["t"]?.toString()) {
            "b"  -> ParameterValue.Bound(m["p"]?.toString() ?: "")
            else -> ParameterValue.Literal(m["v"]?.toString() ?: "")
        }
    }

    // -----------------------------------------------------------------------
    // Index + prefs helpers
    // -----------------------------------------------------------------------

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readIndex(context: Context): List<String> {
        val raw = prefs(context).getString(INDEX_KEY, null)?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(context: Context, ids: List<String>) {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        prefs(context).edit().putString(INDEX_KEY, arr.toString()).apply()
    }
}
