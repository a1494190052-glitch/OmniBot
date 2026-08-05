package cn.com.omnimind.bot.plugin.official

import cn.com.omnimind.bot.plugin.OmniPluginToolDefinition
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The small, user-facing toolbox exposed by the OmniLink runtime bundle. */
object OmniLinkAgentTools {
    const val DEVICES = "omnilink_devices"
    const val SEND_MESSAGE = "omnilink_send_message"
    const val READ_EVENTS = "omnilink_read_events"
    const val SUBSCRIBE_EVENTS = "omnilink_subscribe_events"

    val TOOL_NAMES = linkedSetOf(
        DEVICES,
        SEND_MESSAGE,
        READ_EVENTS,
        SUBSCRIBE_EVENTS,
    )

    fun definitions(): List<OmniPluginToolDefinition> = listOf(
        definition(
            name = DEVICES,
            displayName = "查看协作设备",
            description = "列出当前 OmniLink 连接中的设备、连接状态、在线可达性和 readiness。readiness.device 可用于查询电量、充电、网络、交互和锁定状态；只返回安全的设备摘要。",
        ),
        definition(
            name = SEND_MESSAGE,
            displayName = "给协作小万发消息",
            description = "通过 OmniLink 给明确设备上的另一个小万发送一条可重试、可审计的消息。",
            required = listOf("device_id", "message"),
            properties = mapOf(
                "device_id" to stringProperty("来自 omnilink_devices 的稳定设备 ID。"),
                "message" to stringProperty("要发送给对方小万的消息。"),
                "conversation_id" to stringProperty("可选的共享协作会话 ID；默认使用 omnibot-collaboration。"),
                "recipient_agent_id" to stringProperty("可选的接收 Agent ID；默认使用 omnibot-omnilink-agent。"),
                "message_id" to stringProperty("可选的幂等消息 ID；省略时由插件生成。"),
            ),
        ),
        definition(
            name = READ_EVENTS,
            displayName = "读取协作事件",
            description = "从明确设备读取指定类型的 Agent-safe 事件；可用 wait_ms 做一次有界等待，返回的 cursor 支持断线恢复。默认事件类型是 AGENT_MESSAGE_RECEIVED。",
            required = listOf("device_id"),
            properties = mapOf(
                "device_id" to stringProperty("来自 omnilink_devices 的稳定设备 ID。"),
                "event_types" to arrayProperty(
                    description = "要读取的 OmniLink 事件类型，例如 AGENT_MESSAGE_RECEIVED、NOTIFICATION_UPSERTED、NOTIFICATION_REMOVED。只能使用 Agent-safe 类型。",
                ),
                "wait_ms" to integerProperty("最长等待毫秒数，范围 0 到 30000。"),
                "cursor" to stringProperty("上一次返回的 opaque cursor；不要自行修改。"),
            ),
        ),
        definition(
            name = SUBSCRIBE_EVENTS,
            displayName = "订阅协作事件",
            description = "对明确协作设备开启或关闭一组 Agent-safe 事件的后台回流。它是通用事件订阅原语；小万根据用户意图选择 event_types，事件会以安全摘要自动回流当前聊天。",
            required = listOf("device_id", "mode"),
            properties = mapOf(
                "device_id" to stringProperty("来自 omnilink_devices 的稳定设备 ID。"),
                "event_types" to arrayProperty(
                    description = "要持续回流的 Agent-safe OmniLink 事件类型。",
                ),
                "mode" to enumProperty(
                    description = "start 开启持续回流；stop 停止该设备的回流。",
                    values = listOf("start", "stop"),
                ),
            ),
        ),
    )

    private fun definition(
        name: String,
        displayName: String,
        description: String,
        required: List<String> = emptyList(),
        properties: Map<String, kotlinx.serialization.json.JsonObject> = emptyMap(),
    ) = OmniPluginToolDefinition(
        name = name,
        displayName = displayName,
        description = description,
        parameters = buildJsonObject {
            put("type", "object")
            if (required.isNotEmpty()) {
                put("required", buildJsonArray {
                    required.forEach { add(JsonPrimitive(it)) }
                })
            }
            put("properties", buildJsonObject {
                properties.forEach { (key, value) -> put(key, value) }
            })
            put("additionalProperties", false)
        },
    )

    private fun stringProperty(description: String) = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun integerProperty(description: String) = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

    private fun arrayProperty(description: String) = buildJsonObject {
        put("type", "array")
        put("description", description)
        put("items", buildJsonObject {
            put("type", "string")
        })
        put("minItems", 1)
        put("maxItems", 8)
    }

    private fun enumProperty(description: String, values: List<String>) = buildJsonObject {
        put("type", "string")
        put("description", description)
        put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
    }
}
