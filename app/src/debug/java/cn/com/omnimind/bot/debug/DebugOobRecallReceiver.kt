package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import cn.com.omnimind.assists.controller.accessibility.AccessibilityController
import cn.com.omnimind.assists.task.vlmserver.UIContext
import cn.com.omnimind.assists.task.vlmserver.VLMRecallContextRequest
import cn.com.omnimind.assists.task.vlmserver.VLMRuntimeConfigRegistry
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.omniflow.OmniFlowFunctionRecallAdapter
import cn.com.omnimind.bot.omniflow.OmniFlowPythonRuntime
import cn.com.omnimind.bot.vlm.AndroidGuiPolicy
import cn.com.omnimind.bot.vlm.VlmFunctionRecall
import com.google.gson.GsonBuilder
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class DebugOobRecallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                run(appContext, intent)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun run(appContext: Context, intent: Intent?) {
        val goal = intent.decodeBase64Extra("goalBase64")
            ?: intent?.getStringExtra("goal").orEmpty()
        val requestedPackage = intent?.getStringExtra("currentPackage")
            ?: intent?.getStringExtra("current_package")
        val currentPackage = requestedPackage?.trim()?.takeIf { it.isNotEmpty() }
            ?: runCatching { AccessibilityController.getPackageName() }.getOrNull().orEmpty()
        val currentXml = intent.decodeBase64Extra("currentXmlBase64")
            ?: intent?.getStringExtra("currentXml")?.trim()?.takeIf { it.isNotEmpty() }
            ?: runCatching { AccessibilityController.getCaptureScreenShotXml(true) }.getOrNull().orEmpty()
        val topK = intent?.getIntExtra("k", 8)?.coerceIn(1, 50) ?: 8

        val recallRequest = linkedMapOf<String, Any?>(
            "goal" to goal,
            "current_package" to currentPackage,
            "current_xml" to currentXml,
            "k" to topK,
        )
        val recallResult = runCatching {
            OmniFlowFunctionRecallAdapter(
                bridgeCall = { operation, payload ->
                    OmniFlowPythonRuntime.call(appContext, operation, payload)
                },
            ).recall(recallRequest)
        }.getOrElse { error ->
            linkedMapOf<String, Any?>(
                "success" to false,
                "phase" to "exception",
                "error_message" to error.message.orEmpty(),
                "error_type" to error.javaClass.name,
            )
        }
        val preview = buildVlmRecallPreview(
            appContext = appContext,
            goal = goal,
            currentPackage = currentPackage,
            currentXml = currentXml,
        )
        val payload = linkedMapOf<String, Any?>(
            "goal" to goal,
            "current_package" to currentPackage,
            "current_xml_present" to currentXml.isNotBlank(),
            "current_xml_chars" to currentXml.length,
            "recall" to recallResult,
            "vlm_request_preview" to preview,
        )
        val json = gson.toJson(payload)
        File(appContext.filesDir, "debug-oob-recall-result.json").writeText(json)
        OmniLog.i(TAG, json)
    }

    private suspend fun buildVlmRecallPreview(
        appContext: Context,
        goal: String,
        currentPackage: String,
        currentXml: String,
    ): Map<String, Any?> {
        val baseContext = UIContext(
            overallTask = goal,
            currentStepGoal = goal,
            currentPackageName = currentPackage,
            pageDiagnostics = mapOf("debug_oob_recall_receiver" to "true"),
        )
        val enriched = VlmFunctionRecall(appContext).enrich(
            VLMRecallContextRequest(
                context = baseContext,
                currentXml = currentXml,
                currentPackageName = currentPackage,
                screenshotBase64 = null,
                stepIndex = 0,
                disableFunctionRecall = false,
            )
        )
        val envelope = AndroidGuiPolicy(
            systemPromptBuilder = { "debug recall preview" },
            turnPromptBuilder = { context, _ -> context.overallTask }
        ).buildRequest(
            context = enriched,
            screenshot = null,
            model = VLMRuntimeConfigRegistry.get().primarySceneId,
        )
        val modelToolNames = envelope.request.tools.map { it.function.name }
        return linkedMapOf(
            "page_diagnostics" to enriched.pageDiagnostics,
            "dynamic_tool_definitions" to enriched.dynamicToolDefinitions.map(::dynamicToolSummary),
            "model_visible_tool_names" to modelToolNames,
            "selected_base_tool_names" to envelope.selectedBaseToolNames.toList(),
            "dynamic_function_tool_names" to envelope.dynamicFunctionToolNames.toList(),
            "dynamic_function_tool_mappings" to envelope.dynamicFunctionToolMappings,
            "dynamic_function_required_arguments" to envelope.dynamicFunctionRequiredArguments
                .mapValues { (_, value) -> value.toList() },
            "tool_summaries" to envelope.request.tools.map { tool ->
                val parameters = tool.function.parameters
                linkedMapOf(
                    "name" to tool.function.name,
                    "description_chars" to tool.function.description.length,
                    "strict" to tool.function.strict,
                    "properties" to ((parameters["properties"] as? JsonObject)?.keys?.toList().orEmpty()),
                    "required" to requiredNames(parameters),
                )
            },
            "contains_call_tool" to modelToolNames.any { it.equals("call_tool", ignoreCase = true) },
            "contains_raw_function_id_tool" to modelToolNames.any { it.startsWith("oob_fn_") },
            "system_prompt_chars" to envelope.systemPromptChars,
            "current_user_text_chars" to envelope.currentUserText.length,
        )
    }

    private fun dynamicToolSummary(definition: JsonObject): Map<String, Any?> {
        val function = definition["function"] as? JsonObject ?: JsonObject(emptyMap())
        val name = function["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val description = function["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val parameters = function["parameters"] as? JsonObject ?: JsonObject(emptyMap())
        return linkedMapOf(
            "name" to name,
            "function_id" to definition["function_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            "tool_type" to function["toolType"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            "description_chars" to description.length,
            "properties" to ((parameters["properties"] as? JsonObject)?.keys?.toList().orEmpty()),
            "required" to requiredNames(parameters),
        )
    }

    private fun requiredNames(parameters: JsonObject): List<String> =
        (parameters["required"] as? JsonArray)
            .orEmpty()
            .mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }

    private fun Intent?.decodeBase64Extra(name: String): String? {
        val raw = this?.getStringExtra(name)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8).trim()
                .takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "DebugOobRecallReceiver"
        private val gson = GsonBuilder().disableHtmlEscaping().create()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
