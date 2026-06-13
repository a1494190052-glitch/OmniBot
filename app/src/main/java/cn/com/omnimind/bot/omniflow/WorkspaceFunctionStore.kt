package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.baselib.runlog.InternalRunLogRecord
import cn.com.omnimind.baselib.runlog.OobReusableFunctionStore
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.runlog.RunLogReusableFunctionCompiler
import com.google.gson.GsonBuilder
import java.io.File

/**
 * Portable OOB function store backed by workspace files instead of SharedPreferences.
 *
 * Function specs are stored at {workspaceRoot}/commands/{functionId}.json and
 * travel with the workspace on export/import. The agent function runtime checks
 * this store alongside the SharedPreferences-based OobReusableFunctionStore.
 */
class WorkspaceFunctionStore(private val workspaceRoot: File) {

    private val gson = GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create()

    private val functionsDir: File
        get() = File(workspaceRoot, "commands").apply { mkdirs() }

    private val runLogsDir: File
        get() = File(workspaceRoot, "run_logs").apply { mkdirs() }

    fun register(spec: Map<String, Any?>): Map<String, Any?> {
        val functionId = OobFunctionSchemaBuilder.functionId(spec).takeIf { it.isNotEmpty() }
            ?: return mapOf("success" to false, "errorMessage" to "function_id required")
        val existing = get(functionId)
        val sourceRunIds = (existing?.let(OobReusableFunctionStore::sourceRunIds).orEmpty() +
            OobReusableFunctionStore.sourceRunIds(spec)).distinct()
        val storedSpec = linkedMapOf<String, Any?>().apply {
            putAll(spec)
            put("function_id", functionId)
            putIfAbsent("name", functionId)
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

    fun mirrorRunLog(record: InternalRunLogRecord) {
        val safeId = record.runId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val file = File(runLogsDir, "$safeId.json")
        runCatching {
            file.writeText(gson.toJson(record))
        }.onFailure {
            OmniLog.w(TAG, "mirror run log failed: ${record.runId}, ${it.message}")
        }
    }

    fun distillFromRun(
        context: Context,
        record: InternalRunLogRecord
    ): Map<String, Any?> {
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
        runCatching { OobReusableFunctionStore.register(context, storedSpec) }

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
    }
}
