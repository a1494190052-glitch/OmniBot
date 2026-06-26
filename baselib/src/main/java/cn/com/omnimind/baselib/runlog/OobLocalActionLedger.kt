package cn.com.omnimind.baselib.runlog

import android.content.Context
import cn.com.omnimind.baselib.util.OmniLog
import com.google.gson.GsonBuilder
import java.io.File
import java.security.MessageDigest
import java.util.Locale

data class OobLocalActionRecord(
    val schemaVersion: String = OobLocalActionLedger.SCHEMA_VERSION,
    val recordId: String = "",
    val source: String,
    val tool: String,
    val args: Map<String, Any?> = emptyMap(),
    val taskId: String = "",
    val runId: String = "",
    val functionId: String = "",
    val stepId: String = "",
    val packageName: String = "",
    val activityName: String = "",
    val beforeXmlSha256: String = "",
    val beforeXmlChars: Int = 0,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val durationMs: Long = (finishedAtMs - startedAtMs).coerceAtLeast(0L),
    val success: Boolean,
    val blocked: Boolean = false,
    val errorCode: String = "",
    val errorMessage: String = "",
    val diagnostics: Map<String, Any?> = emptyMap(),
)

data class OobLocalActionRiskDecision(
    val allowed: Boolean,
    val errorCode: String = "",
    val reason: String = "",
    val matchedText: String = "",
    val category: String = "",
) {
    fun diagnostics(): Map<String, String> {
        if (allowed) return emptyMap()
        return linkedMapOf(
            "local_action_error_code" to errorCode,
            "local_action_block_reason" to reason,
            "local_action_risk_category" to category,
            "local_action_matched_text" to matchedText,
        )
    }
}

object OobLocalActionRiskPolicy {
    const val ERROR_DANGEROUS_ACTION_BLOCKED = "OOB_DANGEROUS_ACTION_BLOCKED"

    fun evaluate(
        tool: String,
        args: Map<String, Any?>,
        pageXml: String = "",
        packageName: String = "",
        activityName: String = "",
    ): OobLocalActionRiskDecision {
        val normalizedTool = tool.trim().lowercase(Locale.ROOT)
        val targetText = actionTargetText(args)
        val pageText = pageRiskText(pageXml, packageName, activityName)
        val targetAndPageText = "$targetText $pageText"

        if (normalizedTool in targetDrivenTools) {
            findMatch(targetAndPageText, paymentTerms)?.let { term ->
                return blocked(
                    category = "payment_or_order",
                    reason = "Payment, order submission, purchase, transfer, or stored-value action requires the user",
                    matchedText = term,
                )
            }
        }

        if (normalizedTool == OobActionSchema.TOOL_PRESS_KEY) {
            val key = args["key"]?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (key in submitKeys) {
                findMatch(pageText, submitPageTerms)?.let { term ->
                    return blocked(
                        category = "submit_key",
                        reason = "Submit key on a risky page requires the user",
                        matchedText = term,
                    )
                }
            }
        }

        return OobLocalActionRiskDecision(allowed = true)
    }

    private fun blocked(
        category: String,
        reason: String,
        matchedText: String,
    ): OobLocalActionRiskDecision =
        OobLocalActionRiskDecision(
            allowed = false,
            errorCode = ERROR_DANGEROUS_ACTION_BLOCKED,
            reason = reason,
            matchedText = matchedText,
            category = category,
        )

    private fun actionTargetText(args: Map<String, Any?>): String =
        listOf(
            "target_description",
            "description",
            "label",
            "text",
            "content_desc",
            "contentDescription",
            "node_resource_id",
            "resource_id",
            "key",
        ).joinToString(" ") { key -> args[key]?.toString().orEmpty() }
            .lowercase(Locale.ROOT)

    private fun pageRiskText(pageXml: String, packageName: String, activityName: String): String {
        val attrs = Regex("""(?:text|content-desc|hint-text|resource-id|class|package)="([^"]*)"""")
            .findAll(pageXml.take(MAX_PAGE_XML_SCAN_CHARS))
            .map { it.groupValues[1] }
            .take(MAX_PAGE_ATTRS)
            .joinToString(" ")
        return "$packageName $activityName $attrs".lowercase(Locale.ROOT)
    }

    private fun findMatch(text: String, terms: List<String>): String? {
        if (text.isBlank()) return null
        return terms.firstOrNull { term -> text.contains(term.lowercase(Locale.ROOT)) }
    }

    private val targetDrivenTools = setOf(
        OobActionSchema.TOOL_CLICK,
        OobActionSchema.TOOL_LONG_PRESS,
        OobActionSchema.TOOL_INPUT_TEXT,
        OobActionSchema.TOOL_SWIPE,
        OobActionSchema.TOOL_PRESS_KEY,
    )

    private val submitKeys = setOf("enter", "return", "done", "go", "search")

    private val paymentTerms = listOf(
        "确认支付",
        "立即支付",
        "去支付",
        "支付",
        "付款",
        "提交订单",
        "确认下单",
        "立即下单",
        "去下单",
        "下单",
        "购买",
        "确认购买",
        "转账",
        "汇款",
        "充值",
        "提现",
        "扣款",
        "pay now",
        "confirm payment",
        "submit order",
        "place order",
        "purchase",
        "transfer",
        "top up",
        "withdraw",
    )

    private val destructiveTerms = listOf(
        "删除",
        "永久删除",
        "清空",
        "清除数据",
        "卸载",
        "恢复出厂",
        "恢复出厂设置",
        "重置手机",
        "格式化",
        "抹掉",
        "注销账号",
        "关闭账号",
        "delete",
        "permanently delete",
        "clear data",
        "uninstall",
        "factory reset",
        "erase",
        "format",
        "close account",
    )

    private val submitPageTerms = paymentTerms

    private const val MAX_PAGE_XML_SCAN_CHARS = 80_000
    private const val MAX_PAGE_ATTRS = 240
}

object OobLocalActionLedger {
    const val SCHEMA_VERSION = "oob.local_action.v1"
    private const val TAG = "OobLocalActionLedger"
    private const val DIR_NAME = "oob-primitive-actions"
    private const val FILE_NAME = "primitive-actions.ndjson"
    private const val MAX_RECENT_RECORDS = 500
    private const val MAX_FILE_BYTES = 8L * 1024L * 1024L
    private const val REDACTED_TEXT = "<redacted>"

    val plannerRecordableToolNames: Set<String> = linkedSetOf(
        OobActionSchema.TOOL_CLICK,
        OobActionSchema.TOOL_LONG_PRESS,
        OobActionSchema.TOOL_INPUT_TEXT,
        OobActionSchema.TOOL_SWIPE,
        OobActionSchema.TOOL_OPEN_APP,
        OobActionSchema.TOOL_PRESS_KEY,
        OobActionSchema.TOOL_WAIT,
    )

    interface Sink {
        fun append(record: OobLocalActionRecord)
    }

    private val gson = GsonBuilder()
        .disableHtmlEscaping()
        .create()
    private val recentRecords = ArrayDeque<OobLocalActionRecord>()
    private var nextSeq = 0L

    @Volatile
    private var sink: Sink? = null

    fun bind(context: Context) {
        val appContext = context.applicationContext ?: context
        sink = FileSink(File(appContext.filesDir, DIR_NAME))
    }

    fun readRecentRecords(context: Context, limit: Int = 100): List<OobLocalActionRecord> {
        val appContext = context.applicationContext ?: context
        val file = File(File(appContext.filesDir, DIR_NAME), FILE_NAME)
        if (!file.exists()) return emptyList()
        val boundedLimit = limit.coerceIn(1, MAX_RECENT_RECORDS)
        return runCatching {
            val records = ArrayDeque<OobLocalActionRecord>()
            file.useLines(Charsets.UTF_8) { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    val record = runCatching {
                        gson.fromJson(line, OobLocalActionRecord::class.java)
                    }.getOrNull() ?: return@forEach
                    records += record
                    while (records.size > boundedLimit) {
                        records.removeFirst()
                    }
                }
            }
            records.toList()
        }.getOrElse { error ->
            OmniLog.w(TAG, "local action record read failed: ${error.message}")
            emptyList()
        }
    }

    fun recordFile(context: Context): File {
        val appContext = context.applicationContext ?: context
        return File(File(appContext.filesDir, DIR_NAME), FILE_NAME)
    }

    @Synchronized
    fun record(record: OobLocalActionRecord): OobLocalActionRecord {
        val normalizedTool = OobActionSchema.normalizeToolName(record.tool)
        if (normalizedTool !in plannerRecordableToolNames) {
            return record
        }
        val normalized = record.copy(
            recordId = record.recordId.ifBlank {
                "${record.startedAtMs}-${++nextSeq}"
            },
            tool = normalizedTool,
            args = sanitizeArgsForPlannerRecord(normalizedTool, record.args),
            diagnostics = sanitizeMap(record.diagnostics),
        )
        recentRecords += normalized
        while (recentRecords.size > MAX_RECENT_RECORDS) {
            recentRecords.removeFirst()
        }
        runCatching { sink?.append(normalized) }
            .onFailure { error ->
                OmniLog.w(TAG, "local action record sink failed: ${error.message}")
            }
        return normalized
    }

    @Synchronized
    fun recentRecordsForTesting(): List<OobLocalActionRecord> = recentRecords.toList()

    @Synchronized
    fun resetForTesting() {
        recentRecords.clear()
        nextSeq = 0L
        sink = null
    }

    @Synchronized
    fun useSinkForTesting(testSink: Sink?): AutoCloseable {
        val previous = sink
        sink = testSink
        return AutoCloseable {
            synchronized(this) {
                if (sink === testSink) {
                    sink = previous
                }
            }
        }
    }

    fun xmlSha256(xml: String): String {
        if (xml.isBlank()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(xml.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    fun argsForPlannerRecord(tool: String, args: Map<String, Any?>): Map<String, Any?> =
        sanitizeArgsForPlannerRecord(OobActionSchema.normalizeToolName(tool), args)

    fun shouldRecordForPlanner(tool: String): Boolean =
        OobActionSchema.normalizeToolName(tool) in plannerRecordableToolNames

    private fun sanitizeArgsForPlannerRecord(tool: String, args: Map<String, Any?>): Map<String, Any?> {
        val sanitized = LinkedHashMap<String, Any?>()
        sanitizeMap(args).forEach { (key, value) ->
            if (tool == OobActionSchema.TOOL_INPUT_TEXT && key == OobActionSchema.ARG_TEXT) {
                val text = value?.toString().orEmpty()
                sanitized["text_present"] = text.isNotEmpty()
                sanitized["text_length"] = text.length
                sanitized["text_redacted"] = true
                sanitized[key] = REDACTED_TEXT
            } else {
                sanitized[key] = value
            }
        }
        return sanitized
    }

    private fun sanitizeMap(map: Map<String, Any?>): Map<String, Any?> =
        map.entries.associate { (key, value) -> key to sanitizeValue(value) }

    private fun sanitizeValue(value: Any?): Any? =
        when (value) {
            null -> null
            is String, is Number, is Boolean -> value
            is Map<*, *> -> value.entries.associate { (key, item) ->
                key?.toString().orEmpty() to sanitizeValue(item)
            }
            is Iterable<*> -> value.map(::sanitizeValue)
            is Array<*> -> value.map(::sanitizeValue)
            else -> value.toString()
        }

    private class FileSink(private val dir: File) : Sink {
        private val file = File(dir, FILE_NAME)

        @Synchronized
        override fun append(record: OobLocalActionRecord) {
            dir.mkdirs()
            rotateIfNeeded()
            file.appendText(gson.toJson(record) + "\n", Charsets.UTF_8)
        }

        private fun rotateIfNeeded() {
            if (!file.exists() || file.length() < MAX_FILE_BYTES) return
            val rotated = File(dir, "$FILE_NAME.1")
            if (rotated.exists()) rotated.delete()
            file.renameTo(rotated)
        }
    }
}
