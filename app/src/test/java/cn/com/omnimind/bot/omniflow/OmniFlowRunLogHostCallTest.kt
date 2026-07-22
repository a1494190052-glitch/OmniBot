package cn.com.omnimind.bot.omniflow

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class OmniFlowRunLogHostCallTest {
    private val state = mapOf<String, Any?>(
        "state_id" to "target",
        "xml" to "<hierarchy><node bounds=\"[0,0][1440,3168]\" /></hierarchy>",
        "display" to mapOf("width" to 1080, "height" to 1920),
    )

    @Test
    fun `host act consumes supplied state without observing`() = runBlocking {
        var observeCount = 0
        var receivedState = emptyMap<String, Any?>()
        var receivedAction = emptyMap<String, Any?>()
        val host = omniFlowAndroidHostCall(
            loadRunLog = { emptyMap() },
            loadState = { emptyMap() },
            observe = {
                observeCount += 1
                state
            },
            act = { suppliedAction, suppliedState ->
                receivedAction = suppliedAction
                receivedState = suppliedState
                mapOf("success" to true)
            },
        )

        val result = host.invoke(
            "act",
            mapOf(
                "action" to mapOf(
                    "tool" to "click",
                    "args" to mapOf("x" to 500, "y" to 500),
                ),
                "state" to state,
            ),
        )

        assertEquals(true, result["success"])
        assertEquals(0, observeCount)
        assertEquals(
            mapOf("tool" to "click", "args" to mapOf("x" to 500, "y" to 500)),
            receivedAction,
        )
        assertEquals(state, receivedState)
    }
}
