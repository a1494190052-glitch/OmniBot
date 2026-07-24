package cn.com.omnimind.bot.vlm

import cn.com.omnimind.baselib.llm.SceneModelBindingStore

internal object VlmModelCapabilityGuard {
    private const val ERROR =
        "model_native_tool_calls_unsupported: selected model declares toolCall=false"

    fun violation(
        model: String,
        capabilityLookup: (String) -> Boolean? = ::storedCapability,
    ): String? = ERROR.takeIf { capabilityLookup(model.trim()) == false }

    fun requireSupported(model: String) {
        violation(model)?.let { throw IllegalArgumentException(it) }
    }

    private fun storedCapability(model: String): Boolean? {
        if (!SceneModelBindingStore.isValidSceneId(model)) return null
        return runCatching { SceneModelBindingStore.getBinding(model)?.toolCall }.getOrNull()
    }
}
