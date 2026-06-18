package cn.com.omnimind.bot.runlog

import java.nio.file.Files
import java.nio.file.Paths
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
        assertTrue(plan.contains("Do not mark the end-to-end goal"))
        assertTrue(plan.contains("complete until the device smoke"))
    }

    @Test
    fun `vlm recall loop smoke doc covers first run auto register and second fast path`() {
        val smoke = readSource(
            "app/src/main/assets/omniflow/runlog/examples/vlm-task-recall-loop.md"
        )

        assertTrue(smoke.contains("RUN_VLM_RUNLOG"))
        assertTrue(smoke.contains("--ez register true"))
        assertTrue(smoke.contains("convert.function_spec.metadata.enhancement_policy=offline_only"))
        assertTrue(smoke.contains("RUN_OOB_RECALL"))
        assertTrue(smoke.contains("--ez auto_execute true"))
        assertTrue(smoke.contains("RUN_VLM_RECALL_HIT"))
        assertTrue(smoke.contains("outcome.executionRoute"))
        assertTrue(smoke.contains("omniflow_recall_hit"))
        assertTrue(smoke.contains("Kotlin owns live phone execution"))
    }

    private fun readSource(relativePath: String): String {
        val candidates = listOf(
            Paths.get(relativePath),
            Paths.get("..").resolve(relativePath)
        )
        val path = candidates.firstOrNull { Files.exists(it) }
            ?: error("Missing source file: $relativePath from ${Paths.get("").toAbsolutePath()}")
        return String(Files.readAllBytes(path))
    }
}
