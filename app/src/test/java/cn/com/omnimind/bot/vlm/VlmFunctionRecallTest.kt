package cn.com.omnimind.bot.vlm

import cn.com.omnimind.assists.task.vlmserver.UIContext
import cn.com.omnimind.assists.task.vlmserver.VLMRecallContextRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class VlmFunctionRecallTest {
    @Test
    fun `dynamic tool is built from canonical candidate function`() = runBlocking {
        val recall = VlmFunctionRecall(
            configProvider = { VlmWorkspaceConfig.defaultSnapshotForTests() },
            recall = {
                mapOf(
                    "retrieval_state" to "has_candidates",
                    "candidates" to listOf(
                        mapOf(
                            "function" to functionSpec(),
                            "retrieval" to mapOf(
                                "score" to 0.92,
                                "source" to "goal_token_jaccard",
                                "rank" to 1,
                            ),
                        ),
                    ),
                )
            },
        )

        val enriched = recall.enrich(
            VLMRecallContextRequest(
                context = UIContext(overallTask = "open settings"),
                currentXml = "<hierarchy />",
                currentPackageName = "com.example",
                screenshotBase64 = null,
                stepIndex = 0,
            ),
        )

        val definition = enriched.dynamicToolDefinitions.single()
        assertEquals("open_settings", definition["function_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            "run_recalled_workflow_1",
            definition["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun functionSpec(): Map<String, Any?> = linkedMapOf(
        "schema_version" to "omniflow.function.v2",
        "function_id" to "open_settings",
        "name" to "Open device settings",
        "description" to "Open the Android settings app",
        "input_schema" to linkedMapOf(
            "type" to "object",
            "properties" to emptyMap<String, Any?>(),
            "required" to emptyList<String>(),
            "additionalProperties" to false,
        ),
        "bindings" to emptyList<Map<String, Any?>>(),
        "steps" to listOf(
            linkedMapOf(
                "step_index" to 0,
                "source_state_id" to "state-0",
                "action" to linkedMapOf(
                    "tool" to "open_app",
                    "args" to mapOf("package_name" to "com.android.settings"),
                ),
            ),
        ),
        "checker_rules" to emptyList<Map<String, Any?>>(),
        "agent_visible" to true,
    )
}
