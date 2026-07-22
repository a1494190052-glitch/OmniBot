package cn.com.omnimind.bot.function

import cn.com.omnimind.baselib.util.OmniLog
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import java.io.File

/**
 * Portable OOB function store backed by workspace JSON files.
 *
 * Function specs are stored under {workspaceRoot}/.omnibot/omniflow and
 * travel with the workspace on export/import.
 */
class FunctionStore(private val workspaceRoot: File) {
    private val gson = GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
        .create()

    private val omniflowRootDir: File
        get() = File(File(workspaceRoot, ".omnibot"), "omniflow").apply { mkdirs() }

    private val functionsDir: File
        get() = File(omniflowRootDir, "functions").apply { mkdirs() }

    private val statsDir: File
        get() = File(omniflowRootDir, "stats").apply { mkdirs() }

    fun register(spec: Map<String, Any?>): Map<String, Any?> {
        val storedSpec = runCatching { FunctionContract.canonical(spec) }
            .getOrElse { error ->
                return mapOf(
                    "success" to false,
                    "error_message" to (error.message ?: "invalid Function"),
                )
            }
        val functionId = FunctionSchema.functionId(storedSpec)
        val file = functionFile(functionId)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        return runCatching {
            tmp.writeText(gson.toJson(storedSpec))
            if (!tmp.renameTo(file)) {
                file.writeText(gson.toJson(storedSpec))
                tmp.delete()
            }
            mapOf("success" to true, "function_id" to functionId)
        }.onFailure {
            tmp.delete()
            OmniLog.w(TAG, "register function failed: $functionId, ${it.message}")
        }.getOrElse { mapOf("success" to false, "error_message" to it.message) }
    }

    fun get(functionId: String): Map<String, Any?>? {
        val file = functionFile(functionId.trim())
        if (!file.exists()) return null
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val value = gson.fromJson(file.readText(), Map::class.java) as? Map<String, Any?>
            value?.let(FunctionContract::canonical)
        }.getOrNull()
    }

    fun list(limit: Int = 100): List<Map<String, Any?>> {
        val dir = functionsDir
        return dir.listFiles { file -> file.isFile && file.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit.coerceIn(1, 500))
            ?.mapNotNull { file ->
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    val value = gson.fromJson(file.readText(), Map::class.java) as? Map<String, Any?>
                    value?.let(FunctionContract::canonical)
                }.getOrNull()
            }
            .orEmpty()
    }

    fun functionIds(limit: Int = 500): List<String> =
        list(limit).mapNotNull { spec ->
            FunctionSchema.functionId(spec).takeIf { it.isNotEmpty() }
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
        get(normalized)
            ?: return linkedMapOf(
                "success" to false,
                "function_id" to normalized,
                "error_message" to "function not found"
            )
        val now = System.currentTimeMillis().toString()
        val statsFile = statsFile(normalized)
        val existingStats = runCatching {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(statsFile.readText(), Map::class.java) as? Map<String, Any?>
        }.getOrNull().orEmpty()
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
        statsFile.writeText(gson.toJson(runStats))
        return linkedMapOf(
            "success" to true,
            "function_id" to normalized,
            "run_stats" to runStats,
            "last_run" to lastRun
        )
    }

    private fun functionFile(functionId: String): File {
        val safe = functionId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        return File(functionsDir, "$safe.json")
    }

    private fun statsFile(functionId: String): File {
        val safe = functionId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        return File(statsDir, "$safe.json")
    }

    companion object {
        private const val TAG = "FunctionStore"
        private fun intValue(value: Any?): Int {
            return when (value) {
                is Number -> value.toInt()
                is String -> value.trim().toIntOrNull() ?: 0
                else -> 0
            }
        }
    }
}
