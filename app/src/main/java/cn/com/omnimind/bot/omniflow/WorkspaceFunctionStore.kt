package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.baselib.runlog.InternalRunLogRecord
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.runlog.RunLogReusableFunctionCompiler
import com.google.gson.GsonBuilder
import java.io.File

/**
 * Portable OOB function store backed by workspace JSON files.
 *
 * Function specs are stored under {workspaceRoot}/.omnibot/omniflow and
 * travel with the workspace on export/import.
 */
class WorkspaceFunctionStore(private val workspaceRoot: File) {

    private val gson = GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create()

    private val omniflowRootDir: File
        get() = File(File(workspaceRoot, ".omnibot"), "omniflow").apply { mkdirs() }

    private val functionsDir: File
        get() = File(omniflowRootDir, "functions").apply { mkdirs() }

    private val runLogsDir: File
        get() = File(omniflowRootDir, "run_logs").apply { mkdirs() }

    fun register(spec: Map<String, Any?>): Map<String, Any?> {
        val functionId = OobFunctionSchemaBuilder.functionId(spec).takeIf { it.isNotEmpty() }
            ?: return mapOf("success" to false, "errorMessage" to "function_id required")
        val existing = get(functionId)
        val sourceRunIds = (existing?.let(OobFunctionSchemaBuilder::sourceRunIds).orEmpty() +
            OobFunctionSchemaBuilder.sourceRunIds(spec)).distinct()
        val now = System.currentTimeMillis().toString()
        val existingRegistry = OobFunctionJson.mapArg(existing?.get("_oob_registry"))
        val incomingRegistry = OobFunctionJson.mapArg(spec["_oob_registry"])
        val storedSpec = linkedMapOf<String, Any?>().apply {
            putAll(spec)
            put("function_id", functionId)
            putIfAbsent("name", functionId)
            put(
                "_oob_registry",
                linkedMapOf<String, Any?>().apply {
                    putAll(existingRegistry)
                    putAll(incomingRegistry)
                    put(
                        "registered_at",
                        existingRegistry["registered_at"]
                            ?: incomingRegistry["registered_at"]
                            ?: now
                    )
                    put("updated_at", now)
                    put("runner", incomingRegistry["runner"] ?: existingRegistry["runner"] ?: RUNNER)
                }
            )
            if (sourceRunIds.isNotEmpty()) {
                put(
                    "metadata",
                    linkedMapOf<String, Any?>().apply {
                        putAll(OobFunctionJson.mapArg(existing?.get("metadata")))
                        putAll(OobFunctionJson.mapArg(spec["metadata"]))
                        put("source_run_ids", sourceRunIds)
                    }
                )
            }
        }
        val file = functionFile(functionId)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        return runCatching {
            tmp.writeText(gson.toJson(storedSpec))
            if (!tmp.renameTo(file)) {
                file.writeText(gson.toJson(storedSpec))
                tmp.delete()
            }
            mapOf("success" to true, "function_id" to functionId, "path" to file.absolutePath)
        }.onFailure {
            tmp.delete()
            OmniLog.w(TAG, "register function failed: $functionId, ${it.message}")
        }.getOrElse { mapOf("success" to false, "errorMessage" to it.message) }
    }

    fun get(functionId: String): Map<String, Any?>? {
        val file = functionFile(functionId.trim())
        if (!file.exists()) return null
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(file.readText(), Map::class.java) as? Map<String, Any?>
        }.getOrNull()
    }

    fun list(limit: Int = 100): List<Map<String, Any?>> {
        val dir = functionsDir
        return dir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit.coerceIn(1, 500))
            ?.mapNotNull { file ->
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    gson.fromJson(file.readText(), Map::class.java) as? Map<String, Any?>
                }.getOrNull()
            }
            .orEmpty()
    }

    fun functionIds(limit: Int = 500): List<String> =
        list(limit).mapNotNull { spec ->
            OobFunctionSchemaBuilder.functionId(spec).takeIf { it.isNotEmpty() }
        }

    fun delete(functionId: String): Boolean =
        functionFile(functionId.trim()).takeIf { it.exists() }?.delete() == true

    fun clear(): Map<String, Any?> {
        val dir = functionsDir
        val files = dir.listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .toList()
        var deleted = 0
        files.forEach { file ->
            if (file.delete()) deleted += 1
        }
        return mapOf(
            "success" to true,
            "deleted_count" to deleted,
            "path" to dir.absolutePath,
        )
    }

    fun canHandle(functionId: String): Boolean = functionFile(functionId.trim()).exists()

    fun recordRun(
        functionId: String,
        success: Boolean,
        runId: String? = null,
        runner: String? = null,
        stepCount: Int? = null,
        errorMessage: String? = null
    ): Map<String, Any?> {
        val normalized = functionId.trim()
        if (normalized.isEmpty()) {
            return linkedMapOf("success" to false, "error_message" to "function_id is empty")
        }
        val existing = get(normalized)
            ?: return linkedMapOf(
                "success" to false,
                "function_id" to normalized,
                "error_message" to "function not found"
            )
        val now = System.currentTimeMillis().toString()
        val existingRegistry = OobFunctionJson.mapArg(existing["_oob_registry"])
        val existingStats = OobFunctionJson.mapArg(existingRegistry["run_stats"])
        val runCount = intValue(existingStats["run_count"]) + 1
        val successCount = intValue(existingStats["success_count"]) + if (success) 1 else 0
        val failCount = intValue(existingStats["fail_count"]) + if (success) 0 else 1
        val lastRun = linkedMapOf<String, Any?>(
            "run_id" to runId?.trim().orEmpty(),
            "success" to success,
            "runner" to runner?.trim().orEmpty(),
            "step_count" to stepCount,
            "error_message" to errorMessage?.trim().orEmpty(),
            "created_at" to now
        )
        val runStats = linkedMapOf<String, Any?>(
            "run_count" to runCount,
            "success_count" to successCount,
            "fail_count" to failCount,
            "last_run_at" to now,
            "last_success" to success,
            "last_run" to lastRun
        )
        val updated = linkedMapOf<String, Any?>().apply {
            putAll(existing)
            put(
                "_oob_registry",
                linkedMapOf<String, Any?>().apply {
                    putAll(existingRegistry)
                    put("updated_at", now)
                    put("runner", existingRegistry["runner"] ?: RUNNER)
                    put("run_stats", runStats)
                }
            )
        }
        register(updated)
        return linkedMapOf(
            "success" to true,
            "function_id" to normalized,
            "run_stats" to runStats,
            "last_run" to lastRun
        )
    }

    fun mirrorRunLog(record: InternalRunLogRecord) {
        val safeId = record.runId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val file = File(runLogsDir, "$safeId.json")
        runCatching {
            file.writeText(gson.toJson(record))
        }.onFailure {
            OmniLog.w(TAG, "mirror run log failed: ${record.runId}, ${it.message}")
        }
    }

    fun distillFromRun(record: InternalRunLogRecord): Map<String, Any?> {
        mirrorRunLog(record)

        val spec = compileRunLogFunctionSpec(record) ?: run {
            return mapOf("success" to false, "reason" to "no_replayable_steps")
        }

        val functionId = OobFunctionSchemaBuilder.functionId(spec)
            .ifEmpty { deriveFunctionId(record) }
        val storedSpec = if (functionId == spec["function_id"]) {
            spec
        } else {
            linkedMapOf<String, Any?>().apply {
                putAll(spec)
                put("function_id", functionId)
            }
        }

        register(storedSpec)

        return mapOf(
            "success" to true,
            "function_id" to functionId,
            "step_count" to OobFunctionSchemaBuilder.materializedSteps(storedSpec).size,
            "path" to functionFile(functionId).absolutePath,
        )
    }

    internal fun compileRunLogFunctionSpec(record: InternalRunLogRecord): Map<String, Any?>? {
        return RunLogReusableFunctionCompiler.compile(record)
    }

    private fun deriveFunctionId(record: InternalRunLogRecord): String {
        val base = record.toolName.ifBlank { record.goal }
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(40)
            .ifBlank { "function" }
        val suffix = record.runId.takeLast(8).replace(Regex("[^A-Za-z0-9]"), "")
        return "oob_fn_${base}_$suffix"
    }

    private fun functionFile(functionId: String): File {
        val safe = functionId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        return File(functionsDir, "$safe.json")
    }

    companion object {
        private const val TAG = "WorkspaceFunctionStore"
        private const val RUNNER = "oob_agent_reusable_function"

        private fun intValue(value: Any?): Int {
            return when (value) {
                is Number -> value.toInt()
                is String -> value.trim().toIntOrNull() ?: 0
                else -> 0
            }
        }
    }
}
