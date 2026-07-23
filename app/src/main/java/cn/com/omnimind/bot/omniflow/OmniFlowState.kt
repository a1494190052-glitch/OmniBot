package cn.com.omnimind.bot.omniflow

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
        value["screenshot_path"]?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let {
            state["screenshot_path"] = it
        }
        return state
    }

    fun build(
        xml: String,
        packageName: String = "",
        activityName: String = "",
        displayWidth: Int? = null,
        displayHeight: Int? = null,
        screenshotPath: String? = null,
    ): Map<String, Any?> {
        val explicitDisplay = if (
            displayWidth != null && displayWidth > 0 && displayHeight != null && displayHeight > 0
        ) {
            displayWidth to displayHeight
        } else {
            null
        }
        val identity = listOf(
            packageName,
            activityName,
            xml,
            explicitDisplay?.first?.toString().orEmpty(),
            explicitDisplay?.second?.toString().orEmpty(),
        ).joinToString("\u0000")
        return linkedMapOf<String, Any?>(
            "state_id" to "state_${sha256(identity).take(20)}",
            "xml" to xml,
            "package_name" to packageName,
            "activity_name" to activityName,
            "display" to explicitDisplay?.let {
                linkedMapOf("width" to it.first, "height" to it.second)
            },
            "screenshot_path" to screenshotPath?.trim()?.takeIf(String::isNotEmpty),
        ).filterValues { value ->
            value != null && (value !is String || value.isNotEmpty())
        }
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }
}
