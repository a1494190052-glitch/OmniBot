package cn.com.omnimind.bot.omniflow

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniFlowPluginRuntimeTest {
    @Test
    fun `enabling optional plugin warms the resident Python runtime`() = runBlocking {
        val backend = RecordingBackend()
        val runtime = OmniFlowPluginRuntimeController(backend)

        assertFalse(runtime.isEnabled())
        assertEquals(0, backend.warmupCount)

        runtime.install(TestPlatform, OmniFlowRuntimeProvider())
        assertFalse(runtime.isEnabled())
        assertEquals(0, backend.warmupCount)

        runtime.enable(TestContext)
        assertTrue(runtime.isEnabled())
        assertEquals(1, backend.warmupCount)

        runtime.enable(TestContext)
        assertEquals(1, backend.warmupCount)

        runtime.disable()
        assertFalse(runtime.isEnabled())
        assertEquals(1, backend.shutdownCount)
    }

    private class RecordingBackend : OmniFlowPluginBackend {
        var warmupCount = 0
        var shutdownCount = 0

        override fun configure(
            platform: OmniFlowPlatform,
            runtimeProvider: OmniFlowRuntimeProvider,
        ) = Unit

        override fun warmup(context: Context) {
            warmupCount += 1
        }

        override suspend fun shutdown() {
            shutdownCount += 1
        }
    }

    private object TestPlatform : OmniFlowPlatform {
        override suspend fun startProcess(
            context: Context,
            command: String,
            environment: Map<String, String>,
        ): Process = error("not used")

        override suspend fun ensurePython(context: Context, expectedVersion: String) = Unit

        override suspend fun resolveRuntimeSkill(
            context: Context,
            refresh: Boolean,
        ): OmniFlowSkillLocation = error("not used")

        override suspend fun bootstrapRuntimeSkill(
            context: Context,
            location: OmniFlowSkillLocation,
        ) = Unit

        override suspend fun reclaimRuntimeSkill(context: Context) = Unit

        override suspend fun completeJson(request: cn.com.omnimind.baselib.llm.ChatCompletionRequest): String =
            error("not used")
    }

    private object TestContext : android.content.ContextWrapper(null)
}
