package cn.com.omnimind.bot.omniflow

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import cn.com.omnimind.baselib.util.OmniLog
import java.io.File

class FunctionRecallIndex(private val workspaceRoot: File) {
    data class Hit(
        val functionId: String,
        val score: Double,
    )

    private val dbFile: File
        get() = File(omniflowRootDir, "function_recall.db")

    private val omniflowRootDir: File
        get() = File(File(workspaceRoot, ".omnibot"), "omniflow").apply { mkdirs() }

    fun exists(): Boolean = dbFile.isFile

    fun upsert(spec: Map<String, Any?>) {
        val functionId = OobFunctionSchemaBuilder.functionId(spec).takeIf { it.isNotEmpty() } ?: return
        val document = searchableText(spec).takeIf { it.isNotBlank() } ?: return
        runCatching {
            withDb { db ->
                db.delete(TABLE, "function_id = ?", arrayOf(functionId))
                db.insert(
                    TABLE,
                    null,
                    ContentValues().apply {
                        put("function_id", functionId)
                        put("document", document)
                    }
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

    fun search(goal: String, limit: Int): List<Hit> {
        val query = matchQuery(goal)
        if (query.isBlank()) return emptyList()
        return runCatching {
            withDb { db ->
                db.rawQuery(
                    """
                    SELECT function_id, bm25($TABLE) AS score
                    FROM $TABLE
                    WHERE $TABLE MATCH ?
                    ORDER BY score ASC
                    LIMIT ?
                    """.trimIndent(),
                    arrayOf(query, limit.coerceIn(1, MAX_RESULTS).toString())
                ).use { cursor ->
                    buildList {
                        val idColumn = cursor.getColumnIndexOrThrow("function_id")
                        val scoreColumn = cursor.getColumnIndexOrThrow("score")
                        while (cursor.moveToNext()) {
                            val functionId = cursor.getString(idColumn)?.trim().orEmpty()
                            if (functionId.isNotEmpty()) {
                                add(Hit(functionId = functionId, score = cursor.getDouble(scoreColumn)))
                            }
                        }
                    }
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
            CREATE VIRTUAL TABLE IF NOT EXISTS $TABLE
            USING fts5(function_id UNINDEXED, document)
            """.trimIndent()
        )
    }

    private fun searchableText(spec: Map<String, Any?>): String {
        val source = OobFunctionJson.mapArg(spec["source"])
        val metadata = OobFunctionJson.mapArg(spec["metadata"])
        val agentReuse = OobFunctionJson.mapArg(spec["agent_reuse"])
            .ifEmpty { OobFunctionJson.mapArg(metadata["agent_reuse"]) }
        val constraints = OobFunctionJson.mapArg(spec["constraints"])
        val schema = OobFunctionSchemaBuilder.inputSchema(spec)
        val parameters = OobFunctionJson.mapArg(schema["properties"]).flatMap { (name, raw) ->
            val property = OobFunctionJson.mapArg(raw)
            listOf(name, property["title"], property["description"])
        }
        val steps = OobFunctionSchemaBuilder.stepSummaries(spec).flatMap { step ->
            listOf(step["title"], step["tool"])
        }
        val text = buildList {
            add(OobFunctionSchemaBuilder.functionId(spec))
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

    private fun matchQuery(goal: String): String =
        tokens(goal)
            .distinct()
            .take(MAX_QUERY_TERMS)
            .joinToString(" OR ") { "\"${it.replace("\"", "\"\"")}\"" }

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
        private const val TAG = "FunctionRecallIndex"
        private const val TABLE = "function_recall_fts"
        private const val MAX_QUERY_TERMS = 48
        private const val MAX_RESULTS = 50
    }
}
