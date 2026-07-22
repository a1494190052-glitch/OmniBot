package cn.com.omnimind.bot.omniflow

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.TimeUnit

class OmniFlowPythonClientTest {
    @Test
    fun `embedded bridge command uses the versioned site packages`() {
        val command = OmniFlowPythonClient.bridgeCommand(
            "/workspace/.omnibot/runtime/omniflow/2026.07.19.1/site-packages"
        )

        assertTrue(command.contains("export PYTHONPATH='/workspace/.omnibot/runtime/omniflow/2026.07.19.1/site-packages'"))
        assertTrue(command.contains("-m oob_omniflow_bridge"))
        assertTrue(!command.contains("/workspace/.venv"))
    }

    @Test
    fun `calls reuse one process until client closes`() = runBlocking {
        val process = FakeProcess(
            stdout = listOf(
                """{"id":"request-1","ok":true,"result":{"call":1}}""",
                """{"id":"request-2","ok":true,"result":{"call":2}}""",
                """{"id":"request-3","ok":true,"result":{"stopped":true}}""",
            ).joinToString("\n", postfix = "\n")
        )
        var starts = 0
        val requestIds = ArrayDeque(listOf("request-1", "request-2", "request-3"))
        val client = OmniFlowPythonClient(
            processStarter = { _, _ ->
                starts += 1
                process
            },
            requestIdFactory = { requestIds.removeFirst() },
        )

        val first = client.call("health")
        val second = client.call("health")

        assertEquals(1L, first["call"])
        assertEquals(2L, second["call"])
        assertEquals(1, starts)
        assertEquals(false, process.destroyed)

        client.close()

        assertTrue(process.destroyed)
        val written = process.writtenText().lines().filter(String::isNotBlank)
        assertEquals(3, written.size)
        assertTrue(written[0].contains("\"op\":\"health\""))
        assertTrue(written[1].contains("\"op\":\"health\""))
        assertTrue(written[2].contains("\"op\":\"shutdown\""))
    }

    @Test
    fun `integral bridge numbers stay integral in nested actions`() = runBlocking {
        val process = FakeProcess(
            stdout = listOf(
                """{"id":"request-1","ok":true,"result":{"function":{"steps":[{"step_index":0,"action":{"tool":"wait","args":{"duration_ms":500}}}]}}}""",
                """{"id":"request-2","ok":true,"result":{"stopped":true}}""",
            ).joinToString("\n", postfix = "\n")
        )
        val requestIds = ArrayDeque(listOf("request-1", "request-2"))
        val client = OmniFlowPythonClient(
            processStarter = { _, _ -> process },
            requestIdFactory = { requestIds.removeFirst() },
        )

        val result = client.call("compile")
        val function = result["function"] as Map<*, *>
        val step = (function["steps"] as List<*>).single() as Map<*, *>
        val action = step["action"] as Map<*, *>
        val args = action["args"] as Map<*, *>

        assertEquals(0L, step["step_index"])
        assertEquals(500L, args["duration_ms"])
        assertFalse(step["step_index"] is Double)
        assertFalse(args["duration_ms"] is Double)
        client.close()
    }

    @Test
    fun `run answers host callbacks on the task process`() = runBlocking {
        val process = FakeProcess(
            stdout = listOf(
                """{"id":"request-1","event":"host_call","call_id":"request-1:1","method":"observe","payload":{"xml":true}}""",
                """{"id":"request-1","event":"host_call","call_id":"request-1:2","method":"act","payload":{"tool":"wait","args":{"time_s":0}}}""",
                """{"id":"request-1","ok":true,"result":{"success":true,"actions_executed":1}}""",
                """{"id":"request-2","ok":true,"result":{"stopped":true}}""",
            ).joinToString("\n", postfix = "\n")
        )
        val methods = mutableListOf<String>()
        val requestIds = ArrayDeque(listOf("request-1", "request-2"))
        val client = OmniFlowPythonClient(
            processStarter = { _, _ -> process },
            requestIdFactory = { requestIds.removeFirst() },
        )

        val result = client.call(
            operation = "run",
            payload = mapOf("function_id" to "wait_once"),
            hostCall = OmniFlowPythonHostCall { method, _ ->
                methods += method
                when (method) {
                    "observe" -> mapOf("xml" to "<hierarchy />")
                    "act" -> mapOf("success" to true)
                    else -> error("unsupported host call")
                }
            },
        )

        assertEquals(listOf("observe", "act"), methods)
        assertEquals(true, result["success"])
        val written = process.writtenText().lines().filter(String::isNotBlank)
        assertEquals(3, written.size)
        assertTrue(written[1].contains("\"call_id\":\"request-1:1\""))
        assertTrue(written[2].contains("\"call_id\":\"request-1:2\""))

        client.close()
        assertTrue(process.writtenText().contains("\"op\":\"shutdown\""))
    }

    @Test
    fun `next call starts a new process after bridge exits`() = runBlocking {
        val exitedProcess = FakeProcess(stdout = "")
        val replacementProcess = FakeProcess(
            stdout = listOf(
                """{"id":"request-2","ok":true,"result":{"ready":true}}""",
                """{"id":"request-3","ok":true,"result":{"stopped":true}}""",
            ).joinToString("\n", postfix = "\n")
        )
        val processes = ArrayDeque(listOf(exitedProcess, replacementProcess))
        val requestIds = ArrayDeque(listOf("request-1", "request-2", "request-3"))
        val client = OmniFlowPythonClient(
            processStarter = { _, _ -> processes.removeFirst() },
            requestIdFactory = { requestIds.removeFirst() },
        )

        runCatching { client.call("health") }

        val result = client.call("health")

        assertEquals(true, result["ready"])
        assertTrue(exitedProcess.destroyed)
        client.close()
    }

    @Test
    fun `timeout invalidates session before old process cleanup finishes`() = runBlocking {
        val stalledOutput = PipedOutputStream()
        val stalledProcess = FakeProcess(
            stdoutStream = PipedInputStream(stalledOutput),
            onDestroy = stalledOutput::close,
        )
        val replacementProcess = FakeProcess(
            stdout = listOf(
                """{"id":"request-2","ok":true,"result":{"ready":true}}""",
                """{"id":"request-3","ok":true,"result":{"stopped":true}}""",
            ).joinToString("\n", postfix = "\n")
        )
        val processes = ArrayDeque(listOf(stalledProcess, replacementProcess))
        val requestIds = ArrayDeque(listOf("request-1", "request-2", "request-3"))
        val client = OmniFlowPythonClient(
            processStarter = { _, _ -> processes.removeFirst() },
            requestIdFactory = { requestIds.removeFirst() },
        )

        val timeout = runCatching {
            client.call("health", timeoutMs = 25L)
        }.exceptionOrNull()
        val result = client.call("health")

        assertTrue(timeout != null)
        assertTrue(stalledProcess.destroyed)
        assertEquals(true, result["ready"])
        client.close()
    }

    private class FakeProcess(
        stdout: String = "",
        private val stdoutStream: InputStream = ByteArrayInputStream(stdout.toByteArray()),
        private val onDestroy: () -> Unit = {},
    ) : Process() {
        private val stdin = ByteArrayOutputStream()
        private val stderrStream = ByteArrayInputStream(ByteArray(0))
        var destroyed: Boolean = false
            private set

        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = stdoutStream
        override fun getErrorStream(): InputStream = stderrStream
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = destroyed
        override fun exitValue(): Int = 0
        override fun destroy() {
            destroyed = true
            onDestroy()
        }
        override fun destroyForcibly(): Process {
            destroyed = true
            onDestroy()
            return this
        }
        override fun isAlive(): Boolean = !destroyed

        fun writtenText(): String = stdin.toString(Charsets.UTF_8.name())
    }
}
