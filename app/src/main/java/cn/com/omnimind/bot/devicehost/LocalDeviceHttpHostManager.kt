package cn.com.omnimind.bot.devicehost
import cn.com.omnimind.bot.runlog.resolveActionName
import cn.com.omnimind.baselib.runlog.OobActionSchema

import android.content.Context
import cn.com.omnimind.assists.task.vlmserver.AndroidDeviceOperator
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.BuildConfig
import cn.com.omnimind.bot.function.FunctionRun
import cn.com.omnimind.bot.mcp.McpToolExecutors
import cn.com.omnimind.bot.function.FunctionService
import cn.com.omnimind.omniintelligence.models.ScrollDirection
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Debug-only local HTTP host for desktop recorders.
 *
 * The server binds to device loopback only. Desktop tools should reach it with
 * `adb forward tcp:8910 tcp:8910`, so no MCP bearer token or LAN exposure is
 * needed for physical-device recording.
 */
object LocalDeviceHttpHostManager {
    private const val TAG = "LocalDeviceHttpHost"
    private const val HOST = "127.0.0.1"
    private const val DEFAULT_PORT = 8910

    @Volatile
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    fun startIfDebug(context: Context, port: Int = DEFAULT_PORT) {
        if (!BuildConfig.DEBUG) return
        synchronized(this) {
            if (server != null) return
            runCatching {
                val appContext = context.applicationContext
                server = buildServer(appContext, port).also { it.start(wait = false) }
                OmniLog.i(TAG, "debug HTTP host started at http://$HOST:$port")
            }.onFailure { error ->
                server = null
                OmniLog.w(TAG, "debug HTTP host not started: ${error.message}")
            }
        }
    }

    private fun buildServer(
        context: Context,
        port: Int,
    ): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> =
        embeddedServer(CIO, host = HOST, port = port) {
            install(ContentNegotiation) { gson() }
            routing {
                get("/health") {
                    call.respond(
                        linkedMapOf<String, Any?>(
                            "success" to true,
                            "name" to "oob-local-device-http-host",
                            "debug_only" to true,
                            "host" to HOST,
                            "port" to port,
                        )
                    )
                }
                get("/get_state") {
                    call.respond(captureState(context, call.request.queryParameters.toSingleValueMap()))
                }
                get("/state") {
                    call.respond(captureState(context, call.request.queryParameters.toSingleValueMap()))
                }
                post("/get_state") {
                    val body = call.receiveMap()
                    call.respond(captureState(context, body))
                }
                post("/state") {
                    val body = call.receiveMap()
                    call.respond(captureState(context, body))
                }
                post("/act") {
                    val body = call.receiveMap()
                    val result = executeAction(context, body)
                    val status = if (result["success"] == true) HttpStatusCode.OK else HttpStatusCode.BadRequest
                    call.respond(status, result)
                }
                post("/action") {
                    val body = call.receiveMap()
                    val result = executeAction(context, body)
                    val status = if (result["success"] == true) HttpStatusCode.OK else HttpStatusCode.BadRequest
                    call.respond(status, result)
                }
                post("/omniflow/tool") {
                    val body = call.receiveMap()
                    val result = executeFunctionManagementTool(context, body)
                    val status = if (result["success"] == true || result["accepted"] == true) {
                        HttpStatusCode.OK
                    } else {
                        HttpStatusCode.BadRequest
                    }
                    call.respond(status, result)
                }
                post("/omniflow/function/run") {
                    val body = call.receiveMap()
                    val result = executeFunctionRun(context, body)
                    val status = if (result["success"] == true) HttpStatusCode.OK else HttpStatusCode.BadRequest
                    call.respond(status, result)
                }
            }
        }

    private suspend fun captureState(
        context: Context,
        args: Map<String, Any?>,
    ): Map<String, Any?> {
        val deviceOperator = AndroidDeviceOperator(null, context)
        if (!deviceOperator.isReady()) {
            return linkedMapOf(
                "success" to false,
                "error" to "Accessibility action backend is not ready",
                "source" to "oob_local_device_http_host",
            )
        }
        return McpToolExecutors.executeGetState(context, args)
    }

    private suspend fun executeAction(context: Context, body: Map<String, Any?>): Map<String, Any?> =
        runCatching {
            McpToolExecutors.executeAct(context, body) + mapOf("source" to "oob_local_device_http_host")
        }.getOrElse { error ->
            linkedMapOf(
                "success" to false,
                "error" to error.message.orEmpty().ifBlank { error::class.java.simpleName },
                "source" to "oob_local_device_http_host",
            )
        }

    private suspend fun executeFunctionManagementTool(context: Context, body: Map<String, Any?>): Map<String, Any?> =
        runCatching {
            val toolName = firstNonBlank(body["tool"], body["tool_name"], body["toolName"], body["name"])
            val args = mapArg(body["arguments"]).ifEmpty { mapArg(body["args"]) }.ifEmpty { body }
            withHttpAdapterSource(FunctionService(context).executeTool(toolName, args))
        }.getOrElse { error ->
            linkedMapOf(
                "success" to false,
                "error" to error.message.orEmpty().ifBlank { error::class.java.simpleName },
                "source" to "oob_local_device_http_host",
            )
        }

    private suspend fun executeFunctionRun(context: Context, body: Map<String, Any?>): Map<String, Any?> =
        runCatching {
            val args = linkedMapOf<String, Any?>().apply {
                putAll(mapArg(body["args"]))
                val publicArguments = mapArg(body["arguments"])
                if (publicArguments.isNotEmpty()) put("arguments", publicArguments)
                val functionId = firstNonBlank(body["function_id"], body["functionId"])
                if (functionId.isNotEmpty()) put("function_id", functionId)
                firstNonBlank(body["goal"], body["query"], body["task"]).takeIf { it.isNotEmpty() }?.let {
                    put("goal", it)
                }
            }
            withHttpAdapterSource(FunctionRun(context).runFunction(args)) + mapOf(
                "tool" to "run_function",
            )
        }.getOrElse { error ->
            linkedMapOf(
                "success" to false,
                "error" to error.message.orEmpty().ifBlank { error::class.java.simpleName },
                "tool" to "run_function",
                "source" to "oob_local_device_http_host",
            )
        }

    private fun withHttpAdapterSource(result: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf<String, Any?>().apply {
            putAll(result)
            if (firstNonBlank(result["source"]).isBlank()) {
                put("source", "oob_local_device_http_host")
            }
            put("adapter_source", "oob_local_device_http_host")
        }

    private fun canonicalAction(raw: String): String {
        val alias = when (raw.trim().lowercase()) {
            "tap" -> OobActionSchema.TOOL_CLICK
            "long_click", "longpress" -> OobActionSchema.TOOL_LONG_PRESS
            "type", "text", "input" -> OobActionSchema.TOOL_INPUT_TEXT
            "key" -> OobActionSchema.TOOL_PRESS_KEY
            "launch_app" -> OobActionSchema.TOOL_OPEN_APP
            "sleep" -> OobActionSchema.TOOL_WAIT
            else -> raw
        }
        return resolveActionName(alias)
            ?: throw IllegalArgumentException("Unsupported action: $raw")
    }

    private fun normalizeArgs(action: String, args: Map<String, Any?>): Map<String, Any?> {
        val normalized = linkedMapOf<String, Any?>()
        normalized.putAll(args)
        if (action == OobActionSchema.TOOL_SWIPE) {
            val startX = floatArg(args["x"] ?: args["x1"], 0f)
            val startY = floatArg(args["y"] ?: args["y1"], 0f)
            val endX = floatArg(args["end_x"] ?: args["x2"], startX)
            val endY = floatArg(args["end_y"] ?: args["y2"], startY)
            val dx = endX - startX
            val dy = endY - startY
            if (firstNonBlank(args["direction"]).isBlank()) {
                normalized["direction"] = if (abs(dx) >= abs(dy)) {
                    if (dx >= 0) ScrollDirection.RIGHT.name else ScrollDirection.LEFT.name
                } else {
                    if (dy >= 0) ScrollDirection.DOWN.name else ScrollDirection.UP.name
                }
            }
            if (normalized["distance"] == null) {
                normalized["distance"] = maxOf(abs(dx), abs(dy)).takeIf { it > 0f } ?: 300f
            }
            normalized["x"] = startX
            normalized["y"] = startY
        }
        if (action == OobActionSchema.TOOL_OPEN_APP && firstNonBlank(normalized["package_name"]).isBlank()) {
            val component = firstNonBlank(args["component"])
            if (component.contains("/")) normalized["package_name"] = component.substringBefore("/")
        }
        return normalized
    }

    private fun io.ktor.http.Parameters.toSingleValueMap(): Map<String, Any?> =
        entries().associate { entry -> entry.key to entry.value.firstOrNull() }

    private fun waitMs(args: Map<String, Any?>): Long {
        val durationMs = longArg(args["duration_ms"], 0L)
        if (durationMs > 0L) return durationMs
        val seconds = args["time_s"]?.toString()?.toDoubleOrNull() ?: 1.0
        return (seconds.coerceAtLeast(0.0) * 1000.0).toLong()
    }

    private suspend fun io.ktor.server.application.ApplicationCall.receiveMap(): Map<String, Any?> =
        runCatching { receive<Map<String, Any?>>() }.getOrDefault(emptyMap())

    private fun mapArg(value: Any?): Map<String, Any?> =
        when (value) {
            is Map<*, *> -> linkedMapOf<String, Any?>().apply {
                value.forEach { (key, item) ->
                    if (key != null) put(key.toString(), item)
                }
            }
            else -> emptyMap()
        }

    private fun firstNonBlank(vararg values: Any?): String =
        values.asSequence()
            .mapNotNull { it?.toString()?.trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()

    private fun floatArg(value: Any?, defaultValue: Float): Float =
        when (value) {
            is Number -> value.toFloat()
            is String -> value.trim().toFloatOrNull() ?: defaultValue
            else -> defaultValue
        }

    private fun longArg(value: Any?, defaultValue: Long): Long =
        when (value) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull() ?: value.trim().toDoubleOrNull()?.toLong() ?: defaultValue
            else -> defaultValue
        }

    private fun redactedArgs(action: String, args: Map<String, Any?>): Map<String, Any?> =
        if (action == OobActionSchema.TOOL_INPUT_TEXT && args.containsKey("text")) {
            args + mapOf("text" to "<redacted>", "text_length" to args["text"].toString().length)
        } else {
            args
        }
}
