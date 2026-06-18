package cn.com.omnimind.bot.runlog

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedOmniFlowExecutionPlanTest {
    @Test
    fun `execution plan keeps Python offline and Kotlin native for phone actions`() {
        val plan = readSource(
            "app/src/main/assets/omniflow/runlog/unified-execution-plan.md"
        )

        assertTrue(plan.contains("OOB native Function facade"))
        assertTrue(plan.contains("OobOmniFlowToolkitService"))
        assertTrue(plan.contains("OobFunctionRunner"))
        assertTrue(plan.contains("Python must not call Android accessibility actions directly"))
        assertTrue(plan.contains("native HTTP/MCP/debug surface"))
        assertTrue(plan.contains("Enhancement is deliberately out of this critical path"))
        assertTrue(plan.contains("mode=enhance"))
        assertTrue(plan.contains("offline") && plan.contains("maintenance"))
        assertTrue(plan.contains("must not disable direct replay"))
        assertTrue(plan.contains("Replay uses the currently saved Function as-is"))
        assertTrue(plan.contains("Do not mark the end-to-end goal"))
        assertTrue(plan.contains("complete until the device smoke"))
    }

    @Test
    fun `execution plan defines adapter matrix and shared acceptance gates`() {
        val plan = readSource(
            "app/src/main/assets/omniflow/runlog/unified-execution-plan.md"
        )

        assertTrue(plan.contains("## Adapter Matrix"))
        assertTrue(plan.contains("| `vlm_task` | natural-language goal"))
        assertTrue(plan.contains("| UI Function run | concrete `function_id`"))
        assertTrue(plan.contains("| MCP Function tools | concrete Function lifecycle payloads"))
        assertTrue(plan.contains("| HTTP/debug Function run | concrete debug payloads"))
        assertTrue(plan.contains("| `RUN_VLM_RECALL_HIT` | natural-language goal for strict-hit validation"))
        assertTrue(plan.contains("| `update_function` / enhance | concrete `function_id`, RunLog evidence, patch"))
        assertTrue(plan.contains("| Python `omniflow-mcp` in Alpine | `omniflow.recall`, `omniflow.ingest_run_log`"))
        assertTrue(plan.contains("live phone"))
        assertTrue(plan.contains("same native facade and Kotlin replay runner"))
        assertTrue(plan.contains("creating the second execution system this plan rejects"))

        assertTrue(plan.contains("## Call Shapes"))
        assertTrue(plan.contains("\"allowOmniFlowFunctionAutoExecute\": true"))
        assertTrue(plan.contains("\"tool\": \"run_function\""))
        assertTrue(plan.contains("\"tool\": \"update_function\""))
        assertTrue(plan.contains("Enhancement is offline"))

        assertTrue(plan.contains("## Shared Acceptance Gates"))
        assertTrue(plan.contains("Manual recording: a visible `录制轨迹` entry starts recording"))
        assertTrue(plan.contains("First VLM run: `vlm_task` succeeds"))
        assertTrue(plan.contains("Second VLM run: from the same or equivalent page"))
        assertTrue(plan.contains("No inline enhancement"))
        assertTrue(plan.contains("Python compatibility"))
        assertTrue(plan.contains("UI stability"))
    }

    @Test
    fun `vlm recall loop smoke doc covers first run auto register and second fast path`() {
        val smoke = readSource(
            "app/src/main/assets/omniflow/runlog/examples/vlm-task-recall-loop.md"
        )

        assertTrue(smoke.contains("RUN_VLM_RUNLOG"))
        assertTrue(smoke.contains("scripts/oob-vlm-recall-loop-smoke.sh"))
        assertTrue(smoke.contains("--ez register true"))
        assertTrue(smoke.contains("convert.function_spec.metadata.enhancement_policy=offline_only"))
        assertTrue(smoke.contains("RUN_OOB_RECALL"))
        assertTrue(smoke.contains("--ez auto_execute true"))
        assertTrue(smoke.contains("RUN_VLM_RECALL_HIT"))
        assertTrue(smoke.contains("outcome.executionRoute"))
        assertTrue(smoke.contains("omniflow_recall_hit"))
        assertTrue(smoke.contains("CONVERT_RUNLOG_AND_RUN_FUNCTION"))
        assertTrue(smoke.contains("--ez enhance true"))
        assertTrue(smoke.contains("enhancement_policy=offline_only"))
        assertTrue(smoke.contains("replay_uses_enhanced_function=false"))
        assertTrue(smoke.contains("Kotlin owns live phone execution"))
    }

    @Test
    fun `vlm recall loop smoke script is strict and executable`() {
        val scriptPath = findSource("scripts/oob-vlm-recall-loop-smoke.sh")
        val script = String(Files.readAllBytes(scriptPath))

        assertTrue(script.contains("RUN_VLM_RUNLOG"))
        assertTrue(script.contains("RUN_OOB_RECALL"))
        assertTrue(script.contains("RUN_VLM_RECALL_HIT"))
        assertTrue(script.contains("CONVERT_RUNLOG_AND_RUN_FUNCTION"))
        assertTrue(script.contains("validate_first_run"))
        assertTrue(script.contains("validate_recall"))
        assertTrue(script.contains("validate_recall_hit"))
        assertTrue(script.contains("validate_second_run"))
        assertTrue(script.contains("validate_enhance_offline"))
        assertTrue(script.contains("disableOmniFlowRecall true"))
        assertTrue(script.contains("startFromCurrent true"))
        assertTrue(script.contains("enhancement_policy") && script.contains("offline_only"))
        assertTrue(script.contains("replay_uses_enhanced_function"))

        val permissions = Files.getPosixFilePermissions(scriptPath)
        assertTrue(permissions.contains(PosixFilePermission.OWNER_EXECUTE))
    }

    @Test
    fun `auto register and debug replay keep enhancement off the online path`() {
        val autoRegistrar = readSource(
            "app/src/main/java/cn/com/omnimind/bot/runlog/OobVlmRunLogAutoRegistrar.kt"
        )
        val debugReplay = readSource(
            "app/src/debug/java/cn/com/omnimind/bot/debug/DebugRunLogFunctionReplayReceiver.kt"
        )

        assertTrue(autoRegistrar.contains("convertRunLog("))
        assertTrue(autoRegistrar.contains("register = true"))
        assertTrue(autoRegistrar.contains("agentVisible = true"))
        assertTrue(!autoRegistrar.contains("updateFunction("))
        assertTrue(!autoRegistrar.contains("mode\" to \"enhance\""))

        assertTrue(debugReplay.contains("buildOfflineEnhanceStatus("))
        assertTrue(debugReplay.contains("\"policy\" to \"offline_only\""))
        assertTrue(debugReplay.contains("\"replay_uses_enhanced_function\" to false"))
        assertTrue(debugReplay.contains("\"enhancement_policy\" to \"offline_only\""))
        assertTrue(!debugReplay.contains("service.updateFunction("))
        assertTrue(!debugReplay.contains("\"enhanced_function_spec_hash\""))
        assertTrue(!debugReplay.contains("\"enhance_failed\""))
    }

    private fun readSource(relativePath: String): String {
        return String(Files.readAllBytes(findSource(relativePath)))
    }

    private fun findSource(relativePath: String) =
        listOf(
            Paths.get(relativePath),
            Paths.get("..").resolve(relativePath)
        ).firstOrNull { Files.exists(it) }
            ?: error("Missing source file: $relativePath from ${Paths.get("").toAbsolutePath()}")
}
