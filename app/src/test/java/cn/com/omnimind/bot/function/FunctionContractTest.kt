package cn.com.omnimind.bot.function

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FunctionContractTest {
    @Test
    fun `stored function keeps references without source state`() {
        val sanitized = FunctionContract.sanitize(
            linkedMapOf(
                "function_id" to "fn_settings",
                "source_run_ids" to listOf("run-1"),
                "source" to mapOf("kind" to "run_log", "run_id" to "run-1"),
                "actions" to listOf(
                    mapOf(
                        "tool" to "click",
                        "args" to mapOf(
                            "x" to 500,
                            "y" to 500,
                            "xml" to "<hierarchy />",
                            "screenshot_path" to "/tmp/source.png",
                            "source_context" to mapOf(
                                "page" to "<hierarchy />",
                                "coordinate_space" to "relative_0_1000",
                                "action_index" to 2,
                            ),
                        ),
                    ),
                ),
                "metadata" to mapOf(
                    "source_run_ids" to listOf("run-1"),
                    "oob_function_evidence" to mapOf(
                        "latest_run_id" to "run-1",
                        "latest_analysis" to mapOf(
                            "summary" to "raw evidence",
                            "source_xml" to "<hierarchy />",
                        ),
                    ),
                ),
            ),
        )

        val action = FunctionJson.mapArg(FunctionJson.listArg(sanitized["actions"]).single())
        val args = FunctionJson.mapArg(action["args"])
        assertEquals(
            mapOf(
                "x" to 500,
                "y" to 500,
                "source_context" to mapOf(
                    "coordinate_space" to "relative_0_1000",
                    "action_index" to 2,
                ),
            ),
            args,
        )
        assertEquals(listOf("run-1"), FunctionJson.listArg(sanitized["source_run_ids"]))
        assertEquals("run-1", FunctionJson.mapArg(sanitized["source"])["run_id"])
        val evidence = FunctionJson.mapArg(FunctionJson.mapArg(sanitized["metadata"])["oob_function_evidence"])
        assertEquals("run-1", evidence["latest_run_id"])
        assertFalse(evidence.containsKey("latest_analysis"))
        assertFalse(sanitized.toString().contains("<hierarchy"))
    }
}
