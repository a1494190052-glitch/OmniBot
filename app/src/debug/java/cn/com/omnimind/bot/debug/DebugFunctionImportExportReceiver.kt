package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.function.FunctionApi
import cn.com.omnimind.bot.function.FunctionService
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class DebugFunctionImportExportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val pending = goAsync()

        scope.launch {
            val result = runCatching {
                when (intent?.action) {
                    ACTION_IMPORT -> importFunction(appContext, intent)
                    ACTION_EXPORT -> exportFunction(appContext, intent)
                    else -> linkedMapOf<String, Any?>(
                        "success" to false,
                        "error_code" to "OOB_FUNCTION_IMPORT_EXPORT_UNKNOWN_ACTION",
                        "error_message" to "Unsupported action: ${intent?.action.orEmpty()}",
                    )
                }
            }.getOrElse { error ->
                linkedMapOf<String, Any?>(
                    "success" to false,
                    "phase" to "exception",
                    "error_message" to error.fullMessage(),
                    "error_type" to error.javaClass.name,
                    "error_cause_chain" to error.causeChain(),
                    "source" to "debug_oob_function_import_export_receiver",
                )
            }

            val json = gson.toJson(result)
            val resultFile = when (intent?.action) {
                ACTION_IMPORT -> IMPORT_RESULT_FILE
                ACTION_EXPORT -> EXPORT_RESULT_FILE
                else -> RESULT_FILE
            }
            File(appContext.filesDir, resultFile).writeText(json)
            File(appContext.filesDir, RESULT_FILE).writeText(json)
            OmniLog.i(TAG, json)
            pending.finish()
        }
    }

    private suspend fun importFunction(context: Context, intent: Intent): Map<String, Any?> {
        val function = readFunction(context, intent)
        if (function.isEmpty()) {
            return linkedMapOf<String, Any?>(
                "success" to false,
                "error_code" to "OOB_FUNCTION_IMPORT_SPEC_EMPTY",
                "error_message" to "functionPath or functionBase64 must contain a Function JSON object",
                "source" to "debug_oob_function_import_export_receiver",
            )
        }

        val service = FunctionService(context)
        val startedAtMs = System.currentTimeMillis()
        val register = service.executeTool(
            FunctionApi.FUNCTION_REGISTER,
            linkedMapOf("function" to function),
        )
        val endedAtMs = System.currentTimeMillis()
        return linkedMapOf<String, Any?>(
            "success" to (register["success"] == true),
            "function_id" to firstNonBlank(register["function_id"], function["function_id"]),
            "source_path" to intent.firstStringExtra("functionPath", "function_path", "inputPath", "input_path"),
            "duration_ms" to (endedAtMs - startedAtMs).coerceAtLeast(0L),
            "register" to register,
            "source" to "debug_oob_function_import_export_receiver",
        )
    }

    private suspend fun exportFunction(context: Context, intent: Intent): Map<String, Any?> {
        val functionId = intent.firstStringExtra("functionId", "function_id", "id")
        if (functionId.isBlank()) {
            return linkedMapOf<String, Any?>(
                "success" to false,
                "error_code" to "OOB_FUNCTION_EXPORT_ID_REQUIRED",
                "error_message" to "functionId/function_id is required",
                "source" to "debug_oob_function_import_export_receiver",
            )
        }

        val service = FunctionService(context)
        val get = service.executeTool(
            FunctionApi.FUNCTION_GET,
            linkedMapOf("function_id" to functionId),
        )
        val success = get["schema_version"] == "omniflow.function.v2"
        val function = get.takeIf { success }
        val outputPath = intent.firstStringExtra("outputPath", "output_path")
            .ifBlank { "debug-functions/$functionId.function.json" }
        val outputFile = context.fileFromDebugPath(outputPath)
        if (success && function is Map<*, *>) {
            outputFile.parentFile?.mkdirs()
            outputFile.writeText(gson.toJson(function))
        }

        return linkedMapOf<String, Any?>(
            "success" to success,
            "function_id" to functionId,
            "output_path" to outputPath,
            "output_file" to outputFile.absolutePath,
            "exported_at_ms" to System.currentTimeMillis(),
            "function" to function,
            "get" to get,
            "source" to "debug_oob_function_import_export_receiver",
        )
    }

    private fun readFunction(context: Context, intent: Intent): Map<String, Any?> {
        val fromBase64 = intent.decodeJsonMapBase64Extra("functionBase64")
        if (!fromBase64.isNullOrEmpty()) return fromBase64

        val rawPath = intent.firstStringExtra("functionPath", "function_path", "inputPath", "input_path")
        if (rawPath.isBlank()) return emptyMap()
        val file = context.fileFromDebugPath(rawPath)
        if (!file.exists() || !file.isFile) {
            error("Function import file does not exist: $rawPath (${file.absolutePath})")
        }
        return gson.fromJson<Map<String, Any?>>(file.readText(), mapType)
            ?.filterKeys { it.isNotBlank() }
            ?: emptyMap()
    }

    companion object {
        const val ACTION_IMPORT = "cn.com.omnimind.bot.debug.IMPORT_OOB_FUNCTION"
        const val ACTION_EXPORT = "cn.com.omnimind.bot.debug.EXPORT_OOB_FUNCTION"
        const val RESULT_FILE = "debug-oob-function-import-export-result.json"
        const val IMPORT_RESULT_FILE = "debug-oob-function-import-result.json"
        const val EXPORT_RESULT_FILE = "debug-oob-function-export-result.json"
        private const val TAG = "DebugFunctionImportExportReceiver"
        private val gson = GsonBuilder().disableHtmlEscaping().create()
        private val mapType = object : TypeToken<Map<String, Any?>>() {}.type
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private fun Context.fileFromDebugPath(rawPath: String): File {
        val path = rawPath.trim()
        val requested = File(path)
        val root = filesDir.canonicalFile
        val file = if (requested.isAbsolute) requested else File(root, path)
        val canonical = file.canonicalFile
        if (!canonical.path.startsWith(root.path + File.separator) && canonical != root) {
            error("Debug function path must stay under app filesDir: $rawPath")
        }
        return canonical
    }

    private fun Intent?.firstStringExtra(vararg names: String): String {
        if (this == null) return ""
        for (name in names) {
            val value = getStringExtra(name)?.trim()
            if (!value.isNullOrEmpty()) return value
        }
        return ""
    }

    private fun Intent?.decodeBase64Extra(name: String): String? {
        val raw = this?.getStringExtra(name)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8).trim()
                .takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun Intent?.decodeJsonMapBase64Extra(name: String): Map<String, Any?>? {
        val raw = decodeBase64Extra(name) ?: return null
        return runCatching {
            gson.fromJson<Map<String, Any?>>(raw, mapType)
                ?.filterKeys { it.isNotBlank() }
                ?: emptyMap()
        }.getOrNull()
    }

    private fun firstNonBlank(vararg values: Any?): String {
        for (value in values) {
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    private fun Throwable.fullMessage(): String {
        val parts = mutableListOf<String>()
        var current: Throwable? = this
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            parts += current.message?.takeIf(String::isNotBlank)
                ?.let { "${current.javaClass.name}: $it" }
                ?: current.javaClass.name
            current = current.cause
        }
        return parts.joinToString(" <- ")
    }

    private fun Throwable.causeChain(): List<Map<String, String>> {
        val output = mutableListOf<Map<String, String>>()
        var current: Throwable? = this
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            output += linkedMapOf(
                "type" to current.javaClass.name,
                "message" to current.message.orEmpty(),
            )
            current = current.cause
        }
        return output
    }
}
