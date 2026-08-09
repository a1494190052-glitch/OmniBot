package cn.com.omnimind.baselib.runlog

import android.content.Context
import android.util.Base64
import cn.com.omnimind.baselib.util.OmniLog
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.google.gson.annotations.SerializedName
import java.io.File
import java.security.MessageDigest

private const val CANONICAL_RUN_LOG_SCHEMA_VERSION = "omniflow.canonical_run_log.v1"

private data class CanonicalRunLogRecord(
    @SerializedName("schema_version")
    val schemaVersion: String = CANONICAL_RUN_LOG_SCHEMA_VERSION,
    @SerializedName("run_id")
    val runId: String,
    val goal: String = "",
    val status: String = "running",
    val success: Boolean = false,
    val error: String? = null,
    @SerializedName("started_at_ms")
    val startedAtMs: Long = System.currentTimeMillis(),
    @SerializedName("finished_at_ms")
    val finishedAtMs: Long? = null,
    val steps: List<Map<String, Any?>> = emptyList(),
    @SerializedName("final_state_id")
    val finalStateId: String? = null,
    val diagnostics: Map<String, Any?> = emptyMap(),
)

object InternalRunLogStore {
    private const val TAG = "InternalRunLogStore"
    private const val STORAGE_DIR_NAME = "run_logs"
    private const val MAX_RUN_COUNT = 200
    private const val MAX_STATE_COUNT = 2_000

    private val gson = GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
        .create()

    fun canonicalStep(step: Map<String, Any?>): Map<String, Any?> {
        val value = sanitizeMap(step)
        val required = setOf(
            "step_index",
            "before_state_id",
            "action",
            "result",
            "after_state_id",
        )
        require(value.keys.containsAll(required)) { "run_log_step_required_fields_missing" }
        require(value.keys.all { it in required || it == "metadata" }) {
            "run_log_step_fields_invalid"
        }
        val stepIndex = value["step_index"] as? Number
        require(
            stepIndex != null &&
                stepIndex.toDouble().isFinite() &&
                stepIndex.toDouble() == stepIndex.toLong().toDouble() &&
                stepIndex.toLong() >= 0L
        ) { "run_log_step_index_invalid" }
        require(text(value["before_state_id"]).isNotEmpty()) {
            "run_log_before_state_id_required"
        }
        require(text(value["after_state_id"]).isNotEmpty()) {
            "run_log_after_state_id_required"
        }
        val action = stringMap(value["action"])
        require(action.keys == setOf("tool", "args")) { "canonical_action_fields_invalid" }
        val tool = text(action["tool"])
        require(tool.isNotEmpty() && OobActionSchema.canonicalToolName(tool) == tool) {
            "canonical_action_tool_invalid"
        }
        require(action["args"] is Map<*, *>) { "canonical_action_args_invalid" }
        val result = stringMap(value["result"])
        require(result.keys.all { it == "success" || it == "error" }) {
            "run_log_result_fields_invalid"
        }
        require(result["success"] is Boolean) { "run_log_result_success_required" }
        require(result["error"] == null || result["error"] is String) {
            "run_log_result_error_invalid"
        }
        require(value["metadata"] == null || value["metadata"] is Map<*, *>) {
            "run_log_step_metadata_invalid"
        }
        return value
    }

    @Synchronized
    fun beginRun(
        context: Context,
        runId: String,
        goal: String,
        source: String,
        toolName: String = "",
        operationDescription: String = goal,
        startedAtMs: Long = System.currentTimeMillis(),
    ) {
        val normalizedRunId = runId.trim()
        if (normalizedRunId.isEmpty()) return
        saveRun(
            context,
            CanonicalRunLogRecord(
                runId = normalizedRunId,
                goal = goal,
                startedAtMs = startedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
                diagnostics = sanitizeMap(
                    linkedMapOf(
                        "source" to source,
                        "tool_name" to toolName,
                        "description" to operationDescription,
                    ),
                ),
            ),
        )
        pruneRuns(context, normalizedRunId)
    }

    @Synchronized
    fun upsertRecordedStep(
        context: Context,
        runId: String,
        record: RunLogStepRecord,
    ) {
        val normalizedRunId = runId.trim()
        if (normalizedRunId.isEmpty()) return
        record.states.forEach { state ->
            val stateId = text(state["state_id"])
            require(stateId.isNotEmpty()) { "state_id_required" }
            persistStateLocked(context, stateId, sanitizeMap(state))
        }
        val run = readRun(context, normalizedRunId)
            ?: CanonicalRunLogRecord(runId = normalizedRunId)
        val step = canonicalStep(record.step)
        val stepIndex = (step["step_index"] as Number).toLong()
        val steps = run.steps.toMutableList()
        val existingIndex = steps.indexOfFirst {
            (it["step_index"] as? Number)?.toLong() == stepIndex
        }
        if (existingIndex >= 0) steps[existingIndex] = step else steps += step
        saveRun(context, run.copy(steps = steps))
        pruneRuns(context, normalizedRunId)
    }

    @Synchronized
    fun finishRun(
        context: Context,
        runId: String,
        success: Boolean,
        doneReason: String,
        errorMessage: String? = null,
        finishedAtMs: Long = System.currentTimeMillis(),
        finalStateId: String? = null,
    ) {
        val normalizedRunId = runId.trim()
        if (normalizedRunId.isEmpty()) return
        val run = readRun(context, normalizedRunId)
            ?: CanonicalRunLogRecord(runId = normalizedRunId)
        saveRun(
            context,
            run.copy(
                status = when {
                    doneReason == "cancelled" -> "cancelled"
                    success -> "succeeded"
                    else -> "failed"
                },
                success = success,
                error = errorMessage?.takeIf(String::isNotBlank),
                finishedAtMs = finishedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
                finalStateId = finalStateId?.trim()?.takeIf(String::isNotEmpty),
                diagnostics = sanitizeMap(run.diagnostics + ("done_reason" to doneReason)),
            ),
        )
        pruneRuns(context, normalizedRunId)
    }

    @Synchronized
    fun persistState(
        context: Context,
        state: State,
        screenshotJpeg: ByteArray? = null,
    ): State {
        val payload = state.asMap().toMutableMap().apply {
            screenshotJpeg?.takeIf(ByteArray::isNotEmpty)?.let { bytes ->
                put("screenshot_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
        }
        val stored = persistStateLocked(context, state.stateId, sanitizeMap(payload))
        return state.copy(
            screenshotPath = text(stored["screenshot_path"]).takeIf(String::isNotEmpty),
        )
    }

    private fun persistStateLocked(
        context: Context,
        stateId: String,
        state: Map<String, Any?>,
    ): Map<String, Any?> {
        val allowed = setOf(
            "state_id",
            "package_name",
            "activity_name",
            "display",
            "xml",
            "screenshot_path",
            "screenshot_base64",
        )
        require(state.keys.all(allowed::contains)) { "state_contract_fields_invalid" }
        require(text(state["state_id"]) == stateId) { "state_id_mismatch" }
        val stateJsonFile = stateFile(context, stateId)
        val existing = readMap(stateJsonFile).takeIf {
            text(it["state_id"]) == stateId
        }.orEmpty()
        val stored = linkedMapOf<String, Any?>("state_id" to stateId).apply {
            listOf("screenshot_path", "package_name", "activity_name", "display").forEach { key ->
                existing[key]?.let { put(key, it) }
            }
            listOf("screenshot_path", "package_name", "activity_name").forEach { key ->
                state[key]?.let { put(key, it) }
            }
        }
        validDisplay(state["display"])?.let { stored["display"] = it }

        val xmlFile = stateXmlFile(context, stateId)
        text(state["xml"]).takeIf(String::isNotBlank)?.let { writeAtomically(xmlFile, it) }
        text(state["screenshot_base64"]).takeIf(String::isNotBlank)?.let { encoded ->
            runCatching {
                val screenshotFile = stateAssetFile(context, stateId, "jpg")
                screenshotFile.writeBytes(Base64.decode(encoded.substringAfter(",", encoded), Base64.DEFAULT))
                stored["screenshot_path"] = screenshotFile.absolutePath
            }.onFailure { error ->
                OmniLog.w(TAG, "persist screenshot failed for $stateId: ${error.message}")
            }
        }
        writeAtomically(stateJsonFile, gson.toJson(stored))
        pruneStates(context, stateId)
        return stored
    }

    private fun readRun(context: Context, runId: String): CanonicalRunLogRecord? {
        val file = runFile(context, runId)
        if (!file.isFile) return null
        return runCatching {
            gson.fromJson(file.readText(), CanonicalRunLogRecord::class.java).also { record ->
                require(record.schemaVersion == CANONICAL_RUN_LOG_SCHEMA_VERSION) {
                    "run_log_schema_version_invalid"
                }
                require(record.runId == runId) { "run_id_mismatch" }
            }
        }.onFailure { error ->
            OmniLog.w(TAG, "read run log failed: ${file.absolutePath}, ${error.message}")
        }.getOrNull()
    }

    private fun saveRun(context: Context, record: CanonicalRunLogRecord) {
        val file = runFile(context, record.runId)
        runCatching { writeAtomically(file, gson.toJson(record)) }
            .onFailure { error ->
                OmniLog.w(TAG, "save run log failed: ${file.absolutePath}, ${error.message}")
            }
    }

    private fun readMap(file: File): Map<String, Any?> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(file.readText(), Map::class.java) as? Map<String, Any?>
        }.getOrNull().orEmpty()
    }

    private fun validDisplay(value: Any?): Map<String, Any?>? {
        val display = stringMap(value)
        val width = positiveLong(display["width"]) ?: return null
        val height = positiveLong(display["height"]) ?: return null
        return linkedMapOf("width" to width, "height" to height)
    }

    private fun writeAtomically(file: File, value: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(value)
        if (!temporary.renameTo(file)) {
            file.writeText(value)
            temporary.delete()
        }
    }

    private fun pruneRuns(context: Context, preserveRunId: String) {
        val preserved = runFile(context, preserveRunId)
        storageDir(context)
            .listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .sortedByDescending(File::lastModified)
            .filter { it != preserved }
            .drop((MAX_RUN_COUNT - 1).coerceAtLeast(0))
            .forEach(File::delete)
    }

    private fun pruneStates(context: Context, preserveStateId: String) {
        val preserved = stateFile(context, preserveStateId)
        statesDir(context)
            .listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .sortedByDescending(File::lastModified)
            .filter { it != preserved }
            .drop((MAX_STATE_COUNT - 1).coerceAtLeast(0))
            .forEach { file ->
                listOf("xml", "jpg").forEach { extension ->
                    File(file.parentFile, "${file.nameWithoutExtension}.$extension").delete()
                }
                file.delete()
            }
    }

    private fun storageDir(context: Context): File =
        File(context.applicationContext.filesDir, STORAGE_DIR_NAME).apply { mkdirs() }

    private fun statesDir(context: Context): File =
        File(storageDir(context), "states").apply { mkdirs() }

    private fun runFile(context: Context, runId: String): File =
        File(storageDir(context), "${safeId(runId)}.json")

    private fun stateFile(context: Context, stateId: String): File =
        stateAssetFile(context, stateId, "json")

    private fun stateXmlFile(context: Context, stateId: String): File =
        stateAssetFile(context, stateId, "xml")

    private fun stateAssetFile(context: Context, stateId: String, extension: String): File =
        File(statesDir(context), "${safeId(stateId)}.$extension")

    private fun safeId(value: String): String =
        "${sha256(value).take(16)}_${value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)}"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun text(value: Any?): String = value?.toString()?.trim().orEmpty()

    private fun positiveLong(value: Any?): Long? = when (value) {
        is Number -> value.toLong().takeIf { it > 0L }
        else -> value?.toString()?.trim()?.toLongOrNull()?.takeIf { it > 0L }
    }

    private fun stringMap(value: Any?): Map<String, Any?> =
        (value as? Map<*, *>)
            ?.entries
            ?.associateTo(linkedMapOf()) { (key, item) -> key.toString() to item }
            .orEmpty()

    internal fun sanitizeMap(value: Map<String, Any?>): Map<String, Any?> =
        value.mapValuesTo(linkedMapOf()) { (_, item) -> sanitizeValue(item) }

    private fun sanitizeValue(value: Any?): Any? = when (value) {
        null -> null
        is Double -> if (value.isFinite() && value % 1.0 == 0.0) value.toLong() else value
        is Float -> if (value.isFinite() && value % 1f == 0f) value.toLong() else value
        is String, is Number, is Boolean -> value
        is Map<*, *> -> value.entries.associateTo(linkedMapOf()) { (key, item) ->
            key.toString() to sanitizeValue(item)
        }
        is List<*> -> value.map(::sanitizeValue)
        else -> value.toString()
    }
}
