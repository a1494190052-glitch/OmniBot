package cn.com.omnimind.bot.vlm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VlmModelCapabilityGuardTest {
    @Test
    fun rejectsOnlyExplicitlyUnsupportedNativeToolCalls() {
        assertEquals(
            "model_native_tool_calls_unsupported: selected model declares toolCall=false",
            VlmModelCapabilityGuard.violation("scene.vlm.operation.primary") { false },
        )
        assertNull(VlmModelCapabilityGuard.violation("scene.vlm.operation.primary") { true })
        assertNull(VlmModelCapabilityGuard.violation("scene.vlm.operation.primary") { null })
    }
}
