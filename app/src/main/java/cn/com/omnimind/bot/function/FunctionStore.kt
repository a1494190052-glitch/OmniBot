package cn.com.omnimind.bot.function

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import cn.com.omnimind.baselib.runlog.InternalRunLogRecord
import cn.com.omnimind.baselib.util.OmniLog
import com.google.gson.GsonBuilder
import java.io.File

/**
 * Portable OOB function store backed by workspace JSON files.
 *
 * Function specs are stored under {workspaceRoot}/.omnibot/omniflow and
 * travel with the workspace on export/import.
 */
class FunctionStore(private val workspaceRoot: File) {
    data class RecallHit(
        val functionId: String,
        val score: Double,
    )

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

    private val recallIndex: RecallIndex
        get() = RecallIndex(workspaceRoot)

    fun register(spec: Map<String, Any?>): Map<String, Any?> {
        val functionId = FunctionSchema.functionId(spec).takeIf { it.isNotEmpty() }
            ?: return mapOf("success" to false, "errorMessage" to "function_id required")
        val existing = get(functionId)
        val sourceRunIds = (existing?.let(FunctionSchema::sourceRunIds).orEmpty() +
            FunctionSchema.sourceRunIds(spec)).distinct()
        val now = System.currentTimeMillis().toString()
        val existingRegistry = FunctionJson.mapArg(existing?.get("_oob_registry"))
        val incomingRegistry = FunctionJson.mapArg(spec["_oob_registry"])
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
                        putAll(FunctionJson.mapArg(existing?.get("metadata")))
                        putAll(FunctionJson.mapArg(spec["metadata"]))
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
            recallIndex.upsert(storedSpec)
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
            FunctionSchema.functionId(spec).takeIf { it.isNotEmpty() }
        }

    fun delete(functionId: String): Boolean {
        val normalized = functionId.trim()
        val deleted = functionFile(normalized).takeIf { it.exists() }?.delete() == true
        if (deleted) recallIndex.delete(normalized)
        return deleted
    }

    fun clear(): Map<String, Any?> {
        val dir = functionsDir
        val files = dir.listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .toList()
        var deleted = 0
        files.forEach { file ->
            if (file.delete()) deleted += 1
        }
        recallIndex.clear()
        return mapOf(
            "success" to true,
            "deleted_count" to deleted,
            "path" to dir.absolutePath,
        )
    }

    fun canHandle(functionId: String): Boolean = functionFile(functionId.trim()).exists()

    fun recall(goal: String, limit: Int = 50): List<RecallHit> {
        val index = recallIndex
        if (!index.exists()) {
            list(500).forEach(index::upsert)
        }
        return index.search(goal, limit)
    }

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
        val existingRegistry = FunctionJson.mapArg(existing["_oob_registry"])
        val existingStats = FunctionJson.mapArg(existingRegistry["run_stats"])
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

    private fun functionFile(functionId: String): File {
        val safe = functionId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        return File(functionsDir, "$safe.json")
    }

    companion object {
        private const val TAG = "FunctionStore"
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

private class RecallIndex(private val workspaceRoot: File) {
    private val dbFile: File
        get() = File(omniflowRootDir, "function_recall.db")

    private val omniflowRootDir: File
        get() = File(File(workspaceRoot, ".omnibot"), "omniflow").apply { mkdirs() }

    fun exists(): Boolean {
        if (!dbFile.isFile) return false
        return runCatching {
            withDb { db ->
                db.rawQuery("SELECT 1 FROM $TABLE LIMIT 1", arrayOf<String>()).use { cursor ->
                    cursor.moveToFirst()
                }
            }
        }.getOrDefault(false)
    }

    fun upsert(spec: Map<String, Any?>) {
        val functionId = FunctionSchema.functionId(spec).takeIf { it.isNotEmpty() } ?: return
        val document = searchableText(spec).takeIf { it.isNotBlank() } ?: return
        runCatching {
            withDb { db ->
                db.insertWithOnConflict(
                    TABLE,
                    null,
                    ContentValues().apply {
                        put("function_id", functionId)
                        put("document", document)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
        }.onFailure {
            OmniLog.w(TAG, "upsert recall index failed: $functionId, ${it.message}")
        }
    }

    fun delete(functionId: String) {
        val normalized = functionId.trim()
        if (normalized.isEmpty()) return
        runCatching {
            withDb { db -> db.delete(TABLE, "function_id = ?", arrayOf(normalized)) }
        }.onFailure {
            OmniLog.w(TAG, "delete recall index failed: $normalized, ${it.message}")
        }
    }

    fun clear() {
        runCatching {
            if (dbFile.exists()) dbFile.delete()
        }.onFailure {
            OmniLog.w(TAG, "clear recall index failed: ${it.message}")
        }
    }

    fun search(goal: String, limit: Int): List<FunctionStore.RecallHit> {
        val queryTokens = tokens(goal).distinct().take(MAX_QUERY_TERMS)
        if (queryTokens.isEmpty()) return emptyList()
        return runCatching {
            withDb { db ->
                db.query(
                    TABLE,
                    arrayOf("function_id", "document"),
                    null,
                    null,
                    null,
                    null,
                    null,
                ).use { cursor ->
                    buildList {
                        val idColumn = cursor.getColumnIndexOrThrow("function_id")
                        val documentColumn = cursor.getColumnIndexOrThrow("document")
                        while (cursor.moveToNext()) {
                            val functionId = cursor.getString(idColumn)?.trim().orEmpty()
                            val document = cursor.getString(documentColumn).orEmpty()
                            val score = score(queryTokens, document)
                            if (functionId.isNotEmpty() && score > 0.0) {
                                add(FunctionStore.RecallHit(functionId = functionId, score = score))
                            }
                        }
                    }
                        .sortedWith(compareByDescending<FunctionStore.RecallHit> { it.score }.thenBy { it.functionId })
                        .take(limit.coerceIn(1, MAX_RESULTS))
                }
            }
        }.onFailure {
            OmniLog.w(TAG, "search recall index failed: ${it.message}")
        }.getOrDefault(emptyList())
    }

    private fun <T> withDb(block: (SQLiteDatabase) -> T): T {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            ensureSchema(db)
            return block(db)
        }
    }

    private fun ensureSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE (
                function_id TEXT PRIMARY KEY NOT NULL,
                document TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun searchableText(spec: Map<String, Any?>): String {
        val source = FunctionJson.mapArg(spec["source"])
        val metadata = FunctionJson.mapArg(spec["metadata"])
        val agentReuse = FunctionJson.mapArg(spec["agent_reuse"])
            .ifEmpty { FunctionJson.mapArg(metadata["agent_reuse"]) }
        val constraints = FunctionJson.mapArg(spec["constraints"])
        val schema = FunctionSchema.inputSchema(spec)
        val parameters = FunctionJson.mapArg(schema["properties"]).flatMap { (name, raw) ->
            val property = FunctionJson.mapArg(raw)
            listOf(name, property["title"], property["description"])
        }
        val steps = FunctionSchema.stepSummaries(spec).flatMap { step ->
            listOf(step["title"], step["tool"])
        }
        val text = buildList {
            add(FunctionSchema.functionId(spec))
            add(spec["name"])
            add(spec["description"])
            add(source["goal"])
            add(source["tool_name"])
            add(source["toolName"])
            add(agentReuse["use_when"])
            add(agentReuse["reuse_when"])
            add(agentReuse["success_signal"])
            addAll(parameters)
            addAll(steps)
            add(constraints["package_name"])
            add(constraints["packageName"])
            add(source["package_name"])
            add(source["packageName"])
        }.joinToString(" ")
        return tokens(text).joinToString(" ")
    }

    private fun score(queryTokens: List<String>, document: String): Double {
        val documentTokens = tokens(document)
        if (documentTokens.isEmpty()) return 0.0
        val counts = documentTokens.groupingBy { it }.eachCount()
        val rawScore = queryTokens.sumOf { token ->
            val count = counts[token] ?: 0
            if (count <= 0) {
                0.0
            } else {
                tokenWeight(token) * (1.0 + kotlin.math.ln(count.toDouble()))
            }
        }
        return rawScore / kotlin.math.sqrt(documentTokens.size.toDouble())
    }

    private fun tokenWeight(token: String): Double =
        when {
            token.length >= 3 -> 1.6
            token.length == 2 -> 1.2
            token.any(::isCjk) -> 0.35
            else -> 0.2
        }

    private fun tokens(value: String): List<String> {
        val tokens = linkedSetOf<String>()
        Regex("[\\p{L}\\p{N}_]+")
            .findAll(value.lowercase())
            .map { it.value }
            .forEach { chunk ->
                if (chunk.length >= 2) tokens += chunk
                if (chunk.any(::isCjk)) {
                    val chars = chunk.filter(::isCjk).map { it.toString() }
                    tokens += chars
                    chars.windowed(size = 2).forEach { tokens += it.joinToString("") }
                    chars.windowed(size = 3).forEach { tokens += it.joinToString("") }
                }
            }
        return tokens.toList()
    }

    private fun isCjk(char: Char): Boolean {
        val block = Character.UnicodeBlock.of(char)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
    }

    private companion object {
        private const val TAG = "FunctionStore.RecallIndex"
        private const val TABLE = "function_recall_entries"
        private const val MAX_QUERY_TERMS = 48
        private const val MAX_RESULTS = 50
    }
}
