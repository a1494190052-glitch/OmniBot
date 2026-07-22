package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.baselib.runlog.CanonicalActionConverter
import java.security.MessageDigest

internal object OmniFlowState {
    fun normalize(value: Map<String, Any?>): Map<String, Any?> {
        val display = value["display"] as? Map<*, *>
        val state = build(
            xml = value["xml"]?.toString().orEmpty(),
            packageName = value["package_name"]?.toString().orEmpty(),
            activityName = value["activity_name"]?.toString().orEmpty(),
            displayWidth = (display?.get("width") as? Number)?.toInt(),
            displayHeight = (display?.get("height") as? Number)?.toInt(),
        ).toMutableMap()
        value["state_id"]?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let {
            state["state_id"] = it
        }
        return state
    }

    fun build(
        xml: String,
        packageName: String = "",
        activityName: String = "",
        displayWidth: Int? = null,
        displayHeight: Int? = null,
    ): Map<String, Any?> {
        val explicitDisplay = CanonicalActionConverter.DisplaySize(
            width = displayWidth?.toDouble() ?: 0.0,
            height = displayHeight?.toDouble() ?: 0.0,
        ).takeIf { it.width > 0.0 && it.height > 0.0 }
        val identity = listOf(
            packageName,
            activityName,
            xml,
            explicitDisplay?.width?.toInt()?.toString().orEmpty(),
            explicitDisplay?.height?.toInt()?.toString().orEmpty(),
        ).joinToString("\u0000")
        return linkedMapOf<String, Any?>(
            "state_id" to "state_${sha256(identity).take(20)}",
            "xml" to xml,
            "package_name" to packageName,
            "activity_name" to activityName,
            "display" to explicitDisplay?.let {
                linkedMapOf("width" to it.width.toInt(), "height" to it.height.toInt())
            },
        ).filterValues { value ->
            value != null && (value !is String || value.isNotEmpty())
        }
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }
}
