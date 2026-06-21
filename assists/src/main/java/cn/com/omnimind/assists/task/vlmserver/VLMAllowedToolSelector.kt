package cn.com.omnimind.assists.task.vlmserver

import cn.com.omnimind.baselib.runlog.OobCanonicalActionSchema

object VLMAllowedToolSelector {
    private val ALL_TOOL_NAMES = linkedSetOf(
        OobCanonicalActionSchema.TOOL_CLICK,
        OobCanonicalActionSchema.TOOL_LONG_PRESS,
        OobCanonicalActionSchema.TOOL_INPUT_TEXT,
        OobCanonicalActionSchema.TOOL_SWIPE,
        OobCanonicalActionSchema.TOOL_OPEN_APP,
        OobCanonicalActionSchema.TOOL_PRESS_KEY,
        OobCanonicalActionSchema.TOOL_WAIT,
        OobCanonicalActionSchema.TOOL_FINISHED,
        OobCanonicalActionSchema.TOOL_INFO,
        OobCanonicalActionSchema.TOOL_FEEDBACK,
        OobCanonicalActionSchema.TOOL_ABORT,
        OobCanonicalActionSchema.TOOL_REQUIRE_USER_CHOICE,
        OobCanonicalActionSchema.TOOL_REQUIRE_USER_CONFIRMATION,
    )

    fun select(context: UIContext): Set<String> = ALL_TOOL_NAMES
}
