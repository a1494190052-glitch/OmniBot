package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class OmniVlmPluginTest {

    @Test
    fun `VLM delegates execution to its configured backend`() = runBlocking {
        val backend = RecordingBackend()
        val runtime = OmniVlmPlugin(backend)
        var afterExecutionCount = 0

        val result = runtime.execute(
            context = TestContext,
            request = OmniVlmPlugin.Request(goal = " open settings ", runId = " run-1 "),
            modelClient = UnusedModelClient,
            hooks = OmniVlmPlugin.Hooks(
                afterExecution = { afterExecutionCount += 1 },
            ),
        )

        assertEquals("open settings", backend.request?.goal)
        assertEquals("run-1", backend.request?.runId)
        assertEquals(true, result.payload["success"])
        assertEquals(1, afterExecutionCount)
    }

    @Test
    fun `VLM defaults to stable search first navigation guidance`() {
        val guidance = OmniVlmPlugin.Request(goal = "find a contact").stepSkillGuidance

        assertEquals(true, guidance.contains("use search"))
        assertEquals(true, guidance.contains("type the requested text directly"))
        assertEquals(true, guidance.contains("before browsing long menus or swiping"))
        assertEquals(true, guidance.contains("Do not select history"))
        assertEquals(true, guidance.contains("Swipe only when no usable search"))
    }

    @Test
    fun `VLM runs completion hook when backend fails`() = runBlocking {
        val runtime = OmniVlmPlugin(FailingBackend)
        var afterExecutionCount = 0

        val error = runCatching {
            runtime.execute(
                context = TestContext,
                request = OmniVlmPlugin.Request(goal = "open settings", runId = "run-2"),
                modelClient = UnusedModelClient,
                hooks = OmniVlmPlugin.Hooks(
                    afterExecution = { afterExecutionCount += 1 },
                ),
            )
        }.exceptionOrNull()

        assertEquals("backend_failed", error?.message)
        assertEquals(1, afterExecutionCount)
    }

    @Test
    fun `successful recalled Function skips online VLM`() = runBlocking {
        var onlineCalls = 0

        val result = executeRecallThenOnline(
            hooks = OmniVlmPlugin.Hooks(),
            recall = {
                OmniVlmPlugin.Result(
                    payload = mapOf("success" to true, "recall_hit" to true),
                    finalStateId = "state-recall",
                )
            },
            online = {
                onlineCalls += 1
                OmniVlmPlugin.Result(mapOf("success" to true), "state-online")
            },
        )

        assertEquals(true, result.payload["recall_hit"])
        assertEquals("state-recall", result.finalStateId)
        assertEquals(0, onlineCalls)
    }

    @Test
    fun `failed recalled Function falls back to online VLM`() = runBlocking {
        val progress = mutableListOf<Map<String, Any?>>()
        var onlineCalls = 0

        val result = executeRecallThenOnline(
            hooks = OmniVlmPlugin.Hooks(
                onProgress = { _, extras -> progress += extras },
            ),
            recall = {
                OmniVlmPlugin.Result(
                    payload = mapOf(
                        "success" to false,
                        "recall_hit" to true,
                        "recalled_function_id" to "create_contact",
                        "error_code" to "omnitransfer_target_candidates_missing",
                    ),
                    finalStateId = "state-recall-failed",
                )
            },
            online = {
                onlineCalls += 1
                OmniVlmPlugin.Result(mapOf("success" to true), "state-online")
            },
        )

        assertEquals(true, result.payload["success"])
        assertEquals("state-online", result.finalStateId)
        assertEquals(1, onlineCalls)
        assertEquals("online_vlm", progress.single()["fallback"])
    }

    @Test
    fun `cancelled recalled Function never falls back`() = runBlocking {
        var onlineCalls = 0

        val result = executeRecallThenOnline(
            hooks = OmniVlmPlugin.Hooks(),
            recall = {
                OmniVlmPlugin.Result(
                    payload = mapOf("success" to false, "done_reason" to "cancelled"),
                    finalStateId = null,
                )
            },
            online = {
                onlineCalls += 1
                OmniVlmPlugin.Result(mapOf("success" to true), null)
            },
        )

        assertEquals("cancelled", result.payload["done_reason"])
        assertEquals(0, onlineCalls)
    }

    @Test(expected = CancellationException::class)
    fun `recall cancellation propagates without fallback`() {
        runBlocking {
            executeRecallThenOnline(
                hooks = OmniVlmPlugin.Hooks(),
                recall = { throw CancellationException("stopped") },
                online = { error("online VLM must not run") },
            )
        }
    }

    private class RecordingBackend : OmniVlmBackend {
        var request: OmniVlmPlugin.Request? = null

        override suspend fun execute(
            context: Context,
            request: OmniVlmPlugin.Request,
            modelClient: OmniFlowModelClient,
            hooks: OmniVlmPlugin.Hooks,
        ): OmniVlmPlugin.Result {
            this.request = request
            return OmniVlmPlugin.Result(mapOf("success" to true), null)
        }

        override fun stop(runId: String): Boolean = false
    }

    private object FailingBackend : OmniVlmBackend {
        override suspend fun execute(
            context: Context,
            request: OmniVlmPlugin.Request,
            modelClient: OmniFlowModelClient,
            hooks: OmniVlmPlugin.Hooks,
        ): OmniVlmPlugin.Result = error("backend_failed")

        override fun stop(runId: String): Boolean = false
    }

    private object UnusedModelClient : OmniFlowModelClient {
        override suspend fun streamTurn(
            request: ChatCompletionRequest,
            onReasoningUpdate: (suspend (String) -> Unit)?,
        ): ChatCompletionTurn = error("not used")
    }

    private object TestContext : android.content.ContextWrapper(null)
}
