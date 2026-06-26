package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.runlog.OobActionSchema

object VLMAllowedToolSelector {
    private val ALL_TOOL_NAMES = linkedSetOf(
        OobActionSchema.TOOL_CLICK,
        OobActionSchema.TOOL_LONG_PRESS,
        OobActionSchema.TOOL_INPUT_TEXT,
        OobActionSchema.TOOL_SWIPE,
        OobActionSchema.TOOL_OPEN_APP,
        OobActionSchema.TOOL_PRESS_KEY,
        OobActionSchema.TOOL_WAIT,
        OobActionSchema.TOOL_FINISHED,
        OobActionSchema.TOOL_INFO,
        OobActionSchema.TOOL_FEEDBACK,
        OobActionSchema.TOOL_ABORT,
        OobActionSchema.TOOL_REQUIRE_USER_CHOICE,
        OobActionSchema.TOOL_REQUIRE_USER_CONFIRMATION,
    )

    fun select(context: UIContext): Set<String> {
        val denied = VLMToolDenylistRegistry.get()
        if (denied.isEmpty()) return ALL_TOOL_NAMES
        return ALL_TOOL_NAMES.filterTo(linkedSetOf()) { it !in denied }
    }
}
