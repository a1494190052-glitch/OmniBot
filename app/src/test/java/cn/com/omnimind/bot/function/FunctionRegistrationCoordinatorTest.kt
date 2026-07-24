package cn.com.omnimind.bot.function

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionRegistrationCoordinatorTest {
    @Test
    fun `registers base function before queuing offline enhancement`() = runBlocking {
        val calls = mutableListOf<Pair<String, Map<String, Any?>>>()
        val diagnostics = mutableListOf<Map<String, Any?>>()
        var backgroundTask: (suspend () -> Unit)? = null
        val baseFunction = mapOf(
            "function_id" to "wait_once",
            "name" to "wait once",
            "steps" to listOf<Map<String, Any?>>(),
        )
        val coordinator = FunctionRegistrationCoordinator(
            managementCall = { operation, payload ->
                calls += operation to payload
                assertEquals("compile", operation)
                mapOf(
                    "success" to true,
                    "registered" to true,
                    "run_id" to "run-source",
                    "function_id" to "wait_once",
                    "function" to baseFunction,
                    "enhancement_status" to "none",
                )
            },
            enhancementCall = { functionId, runId ->
                calls += "isolated_enhancement" to mapOf(
                    "function_id" to functionId,
                    "run_id" to runId,
                )
                mapOf(
                    "success" to true,
                    "changed" to true,
                    "enhancement_status" to "enhanced",
                    "changes" to listOf(mapOf("field" to "name")),
                    "message" to "Function enhancement completed.",
                )
            },
            launchBackground = { backgroundTask = it },
            updateEnhancementDiagnostics = { _, value -> diagnostics += value },
            currentTimeMillis = { 123L },
        )

        val result = coordinator.convert(
            mapOf(
                "run_id" to "run-source",
                "register" to true,
                "enhance" to true,
            ),
        )

        assertEquals(listOf("compile"), calls.map { it.first })
        assertEquals(false, calls.single().second["enhance"])
        assertEquals(baseFunction, result["function"])
        assertEquals("enhancing", result["enhancement_status"])
        assertEquals(true, result["enhancement_queued"])
        assertEquals("enhancing", diagnostics.single()["status"])
        assertNotNull(backgroundTask)

        backgroundTask!!.invoke()

        assertEquals(listOf("compile", "isolated_enhancement"), calls.map { it.first })
        assertEquals(
            mapOf(
                "function_id" to "wait_once",
                "run_id" to "run-source",
            ),
            calls.last().second,
        )
        assertEquals(listOf("enhancing", "enhanced"), diagnostics.map { it["status"] })
    }

    @Test
    fun `background enhancement failure preserves registration response`() = runBlocking {
        val diagnostics = mutableListOf<Map<String, Any?>>()
        var backgroundTask: (suspend () -> Unit)? = null
        val coordinator = FunctionRegistrationCoordinator(
            managementCall = { operation, _ ->
                assertEquals("compile", operation)
                mapOf(
                    "success" to true,
                    "registered" to true,
                    "run_id" to "run-source",
                    "function_id" to "wait_once",
                    "function" to mapOf("function_id" to "wait_once"),
                )
            },
            enhancementCall = { _, _ ->
                mapOf(
                    "success" to false,
                    "error_code" to "offline_model_unavailable",
                    "error_message" to "Offline model unavailable",
                )
            },
            launchBackground = { backgroundTask = it },
            updateEnhancementDiagnostics = { _, value -> diagnostics += value },
        )

        val result = coordinator.convert(
            mapOf("run_id" to "run-source", "register" to true),
        )
        backgroundTask!!.invoke()

        assertTrue(result["success"] == true)
        assertTrue(result["registered"] == true)
        assertEquals("wait_once", result["function_id"])
        assertEquals("failed", diagnostics.last()["status"])
        assertEquals("offline_model_unavailable", diagnostics.last()["error_code"])
    }

    @Test
    fun `explicit enhancement opt out only registers base function`() = runBlocking {
        var backgroundQueued = false
        val coordinator = FunctionRegistrationCoordinator(
            managementCall = { _, payload ->
                assertEquals(false, payload["enhance"])
                mapOf(
                    "success" to true,
                    "registered" to true,
                    "enhancement_status" to "none",
                )
            },
            enhancementCall = { _, _ -> error("Enhancement must not run") },
            launchBackground = { backgroundQueued = true },
            updateEnhancementDiagnostics = { _, _ -> },
        )

        val result = coordinator.convert(
            mapOf(
                "run_id" to "run-source",
                "register" to true,
                "enhance" to false,
            ),
        )

        assertFalse(backgroundQueued)
        assertEquals("none", result["enhancement_status"])
    }
}
