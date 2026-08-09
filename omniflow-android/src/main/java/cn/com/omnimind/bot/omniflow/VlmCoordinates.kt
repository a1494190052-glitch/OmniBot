package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.baselib.runlog.Action
import cn.com.omnimind.baselib.runlog.ActionCoordinateCodec
import cn.com.omnimind.baselib.runlog.OobActionSchema

internal object VlmCoordinates {
    data class DisplaySize(
        val width: Int,
        val height: Int,
    ) {
        init {
            require(width > 0 && height > 0) { "vlm_coordinate_display_invalid" }
        }

        fun canonical(): ActionCoordinateCodec.DisplaySize =
            ActionCoordinateCodec.DisplaySize(width.toDouble(), height.toDouble())
    }

    fun toCanonicalArgs(
        toolName: String,
        rawArgs: Map<String, Any?>,
        display: DisplaySize,
    ): Map<String, Any?> {
        if (toolName !in OobActionSchema.coordinateToolNames) return LinkedHashMap(rawArgs)
        COORDINATES.forEach { (name, axis) ->
            val raw = rawArgs[name] ?: return@forEach
            val value = (raw as? Number)?.toDouble()?.takeIf(Double::isFinite)
                ?: error("vlm_coordinate_invalid:$name")
            val limit = if (axis == Axis.X) display.width else display.height
            require(value >= 0.0 && value < limit.toDouble()) {
                "vlm_coordinate_out_of_range:$name"
            }
        }
        return ActionCoordinateCodec.toRelative(rawArgs, display.canonical())
    }

    fun toRawArgs(action: Action, display: DisplaySize): Map<String, Any?> {
        if (action.tool !in OobActionSchema.coordinateToolNames) return LinkedHashMap(action.args)
        return ActionCoordinateCodec.toScreenPixels(action.args, display.canonical())
    }

    fun maximumFor(name: String, display: DisplaySize): Int? = when (name) {
        "x", "x1", "x2" -> display.width - 1
        "y", "y1", "y2" -> display.height - 1
        else -> null
    }

    private enum class Axis { X, Y }

    private val COORDINATES = mapOf(
        "x" to Axis.X,
        "x1" to Axis.X,
        "x2" to Axis.X,
        "y" to Axis.Y,
        "y1" to Axis.Y,
        "y2" to Axis.Y,
    )
}
