package cn.com.omnimind.bot.runlog

import com.google.gson.Gson
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

        assertTrue(plan.contains("examples/unified-entry-surfaces.json"))
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
        assertTrue(plan.contains("\"offline_job\": true"))
        assertTrue(plan.contains("\"auto_analyze_with_model\": true"))
        assertTrue(plan.contains("Enhancement is offline"))
        assertTrue(plan.contains("returns the analysis"))
        assertTrue(plan.contains("offline_job=true"))
        assertTrue(plan.contains("/omniflow/tool"))
        assertTrue(plan.contains("/omniflow/function/run"))

        assertTrue(plan.contains("## Shared Acceptance Gates"))
        assertTrue(plan.contains("Manual recording: a visible `录制轨迹` entry starts recording"))
        assertTrue(plan.contains("First VLM run: `vlm_task` succeeds"))
        assertTrue(plan.contains("Second VLM run: from the same or equivalent page"))
        assertTrue(plan.contains("No inline enhancement"))
        assertTrue(plan.contains("Python compatibility"))
        assertTrue(plan.contains("UI stability"))
        assertTrue(plan.contains("Localization/product wording"))
        assertTrue(plan.contains("`复用指令` as the product name"))
        assertTrue(plan.contains("`复用记忆` is accepted only as compatibility wording"))
    }

    @Test
    fun `vlm recall loop smoke doc covers first run auto register and second fast path`() {
        val smoke = readSource(
            "app/src/main/assets/omniflow/runlog/examples/vlm-task-recall-loop.md"
        )
        val example = readJsonMap(
            "app/src/main/assets/omniflow/runlog/examples/vlm-task-recall-loop.json"
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

        assertTrue(example["schema_version"] == "oob.vlm_task_recall_loop_example.v1")
        assertTrue(example["runtime_owner"] == "oob_native_kotlin")
        assertTrue(example["enhancement_policy"] == "offline_only")
        val steps = listMaps(example["steps"])
        val firstRun = phase(steps, "first_vlm_run")
        val firstRunPayload = mapArg(mapArg(firstRun["tool_payload"])["arguments"])
        assertTrue(mapArg(firstRun["tool_payload"])["tool"] == "vlm_task")
        assertTrue(firstRunPayload["registerRunLog"] == true)
        assertTrue(firstRunPayload["allowOmniFlowFunctionAutoExecute"] == true)
        assertTrue(mapArg(firstRun["expected"])["function_spec.metadata.enhancement_policy"] == "offline_only")

        val fastRun = phase(steps, "second_fast_vlm_run")
        assertTrue(mapArg(mapArg(fastRun["tool_payload"])["arguments"])["startFromCurrent"] == true)
        assertTrue(mapArg(fastRun["expected"])["outcome.executionRoute"] == "omniflow_recall_hit*")
        assertTrue(mapArg(fastRun["expected"])["new_vlm_action_loop_after_hit"] == false)

        val directRun = phase(steps, "direct_function_debug")
        assertTrue(mapArg(directRun["http_payload"])["path"] == "/omniflow/function/run")
        assertTrue(mapArg(directRun["expected"])["arguments_shape"] == "nested_arguments_preserved")

        val enhance = phase(steps, "offline_enhance")
        assertTrue(mapArg(mapArg(enhance["tool_payload"])["arguments"])["mode"] == "enhance")
        assertTrue(mapArg(enhance["expected"])["blocks_auto_registration"] == false)
        assertTrue(mapArg(enhance["expected"])["blocks_recall_hit"] == false)
        assertTrue(mapArg(enhance["expected"])["blocks_direct_replay"] == false)
        assertTrue(mapArg(enhance["expected"])["replay_uses_enhanced_function"] == false)
    }

    @Test
    fun `execution plan defines vlm accuracy measurement boundaries`() {
        val plan = readSource(
            "app/src/main/assets/omniflow/runlog/unified-execution-plan.md"
        )

        assertTrue(plan.contains("## VLM Accuracy Measurement"))
        assertTrue(plan.contains("Online device metrics come from OOB native RunLog/debug result envelopes"))
        assertTrue(plan.contains("Task success rate"))
        assertTrue(plan.contains("Action success rate"))
        assertTrue(plan.contains("First-run registration rate"))
        assertTrue(plan.contains("Recall hit rate"))
        assertTrue(plan.contains("Fast-path execution rate"))
        assertTrue(plan.contains("Latency split"))
        assertTrue(plan.contains("scripts/oob-vlm-accuracy-report.py"))
        assertTrue(plan.contains("task_success_rate"))
        assertTrue(plan.contains("second_fast_path_rate"))
        assertTrue(plan.contains("--output-dir runtime/vlm-recall-loop/<case-name>"))
        assertTrue(plan.contains("offline reader only"))
        assertTrue(plan.contains("not a replacement for the smoke"))
        assertTrue(plan.contains("Offline compatibility metrics may use OmniFlow Python"))
        assertTrue(plan.contains("Schema validity"))
        assertTrue(plan.contains("Recall top-1 / recall@k / margin"))
        assertTrue(plan.contains("Page-match and action-transfer accuracy"))
        assertTrue(plan.contains("Offline Python eval proves schema/recall/action-transfer compatibility"))
        assertTrue(plan.contains("Real-device OOB smoke proves native Android execution"))
        assertTrue(plan.contains("Python eval can explain why recall or transfer failed"))
        assertTrue(plan.contains("cannot certify live"))
        assertTrue(plan.contains("phone execution by itself"))
    }

    @Test
    fun `vlm android gui skill keeps enhancement out of online replay path`() {
        val skill = readSource(
            "app/src/main/assets/builtin_skills/vlm-android-gui/SKILL.md"
        )

        assertTrue(skill.contains("Auto-registration saves the replayable Function first"))
        assertTrue(skill.contains("Do not call"))
        assertTrue(skill.contains("`update_function`"))
        assertTrue(skill.contains("`enhance`"))
        assertTrue(skill.contains("inline before VLM"))
        assertTrue(skill.contains("RunLog registration"))
        assertTrue(skill.contains("recall-hit replay"))
        assertTrue(skill.contains("debug"))
        assertTrue(skill.contains("convert-and-replay"))
        assertTrue(skill.contains("Enhancement is an explicit offline/background maintenance"))
        assertTrue(skill.contains("must not block direct replay"))
        assertTrue(skill.contains("second-run recall fast path"))
    }

    @Test
    fun `builtin skills treat reusable memory as compatibility wording`() {
        val manifest = readSource("app/src/main/assets/builtin_skills/manifest.json")
        val omniflow = readSource("app/src/main/assets/builtin_skills/omniflow/SKILL.md")
        val management = readSource("app/src/main/assets/builtin_skills/oob-function-management/SKILL.md")
        val zhArb = readJsonMap("ui/lib/l10n/app_zh.arb")
        val enArb = readJsonMap("ui/lib/l10n/app_en.arb")

        assertTrue(manifest.contains("复用记忆"))
        assertTrue(omniflow.contains("\"复用记忆\""))
        assertTrue(omniflow.contains("compatibility phrase for saved"))
        assertTrue(omniflow.contains("keep the product wording as \"复用指令\""))
        assertTrue(management.contains("\"复用记忆\""))
        assertTrue(zhArb["memoryCommandsTitle"] == "复用指令")
        assertTrue(zhArb["functionLibraryTitle"] == "复用指令库")
        assertTrue(
            zhArb["functionLibraryEnhanceOfflineHint"].toString()
                .contains("语义升级是离线后台步骤")
        )
        assertTrue(!zhArb.values.any { it == "复用记忆" })
        assertTrue(enArb["memoryCommandsTitle"] == "Reusable Commands")
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
        assertTrue(script.contains("--output-dir DIR"))
        assertTrue(script.contains("KEEP_WORK_DIR"))
        assertTrue(script.contains("scripts/oob-vlm-accuracy-report.py"))
        assertTrue(script.contains("--strict"))
        assertTrue(script.contains("enhancement_policy") && script.contains("offline_only"))
        assertTrue(script.contains("replay_uses_enhanced_function"))
        assertTrue(script.contains("json.load(open(sys.argv[1], encoding=\"utf-8\"))"))
        assertTrue(script.contains("then\n      return 0"))

        val permissions = Files.getPosixFilePermissions(scriptPath)
        assertTrue(permissions.contains(PosixFilePermission.OWNER_EXECUTE))
    }

    @Test
    fun `vlm accuracy report is offline and covers native recall-loop metrics`() {
        val scriptPath = findSource("scripts/oob-vlm-accuracy-report.py")
        val script = String(Files.readAllBytes(scriptPath))

        assertTrue(script.contains("SCHEMA_VERSION = \"oob.vlm_accuracy_report.v1\""))
        assertTrue(script.contains("first-vlm.json"))
        assertTrue(script.contains("recall.json"))
        assertTrue(script.contains("recall-hit.json"))
        assertTrue(script.contains("second-vlm.json"))
        assertTrue(script.contains("enhance-offline.json"))
        assertTrue(script.contains("first_run_registration_rate"))
        assertTrue(script.contains("recall_hit_rate"))
        assertTrue(script.contains("recall_hit_replay_rate"))
        assertTrue(script.contains("second_fast_path_rate"))
        assertTrue(script.contains("offline_enhance_policy_rate"))
        assertTrue(script.contains("latency_ms"))
        assertTrue(script.contains("offline_python_compatibility_required_separately"))
        assertTrue(script.contains("never talks to adb"))
        assertTrue(!script.contains("subprocess"))
        assertTrue(!script.contains("adb -s"))
        assertTrue(!script.contains("adb devices"))
        assertTrue(!script.contains("os.system"))

        val permissions = Files.getPosixFilePermissions(scriptPath)
        assertTrue(permissions.contains(PosixFilePermission.OWNER_EXECUTE))
    }

    @Test
    fun `omniflow python offline contract smoke is static and keeps python out of phone execution`() {
        val scriptPath = findSource("scripts/oob-omniflow-python-offline-contract-smoke.py")
        val script = String(Files.readAllBytes(scriptPath))
        val plan = readSource("app/src/main/assets/omniflow/runlog/unified-execution-plan.md")

        assertTrue(script.contains("DEFAULT_OMNIFLOW_ROOT"))
        assertTrue(script.contains("STANDALONE_TOOL_NAMES"))
        assertTrue(script.contains("\"omniflow.recall\""))
        assertTrue(script.contains("\"omniflow.ingest_run_log\""))
        assertTrue(script.contains("forbidden_mcp_tools"))
        assertTrue(script.contains("omniflow.call_function"))
        assertTrue(script.contains("runtime_owner"))
        assertTrue(script.contains("oob_native_kotlin"))
        assertTrue(script.contains("offline_fixture_eval"))
        assertTrue(script.contains("mcp_cache_recall_ingest"))
        assertTrue(script.contains("offline_enhance"))
        assertTrue(script.contains("replay_uses_enhanced_function"))
        assertTrue(!script.contains("adb "))
        assertTrue(!script.contains("am broadcast"))

        assertTrue(plan.contains("scripts/oob-omniflow-python-offline-contract-smoke.py"))
        assertTrue(plan.contains("standalone"))
        assertTrue(plan.contains("MCP exposes only"))
        assertTrue(plan.contains("Kotlin"))
        assertTrue(plan.contains("live runtime"))

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

    @Test
    fun `debug http omniflow routes delegate to native toolkit facade`() {
        val httpHost = readSource(
            "app/src/main/java/cn/com/omnimind/bot/devicehost/LocalDeviceHttpHostManager.kt"
        )
        val toolkit = readSource(
            "app/src/main/java/cn/com/omnimind/bot/runlog/OobOmniFlowToolkitService.kt"
        )

        assertTrue(httpHost.contains("post(\"/omniflow/tool\")"))
        assertTrue(httpHost.contains("post(\"/omniflow/function/run\")"))
        assertTrue(httpHost.contains("executeOmniFlowTool(context, body)"))
        assertTrue(httpHost.contains("executeOmniFlowFunction(context, body)"))
        assertTrue(httpHost.contains("OobOmniFlowToolkitService(context).executeTool(toolName, args)"))
        assertTrue(httpHost.contains("OobOmniFlowToolkitService(context).executeTool(\"run_function\", args)"))
        assertTrue(httpHost.contains("val publicArguments = mapArg(body[\"arguments\"])"))
        assertTrue(httpHost.contains("put(\"arguments\", publicArguments)"))
        assertTrue(!httpHost.contains("putAll(mapArg(body[\"arguments\"]).ifEmpty { body })"))
        assertTrue(httpHost.contains("adapter_source"))
        assertTrue(httpHost.contains("post(\"/act\")"))
        assertTrue(httpHost.contains("McpToolExecutors.executeAct(context, body)"))
        assertTrue(toolkit.contains("\"run_function\", \"oob_function_run\" -> runFunction(args)"))
        assertTrue(toolkit.contains("val arguments = functionArguments(request)"))
        assertTrue(toolkit.contains("mapArg(request[\"arguments\"])"))
    }

    @Test
    fun `machine readable entry surface contract matches native adapters`() {
        val contract = readJsonMap(
            "app/src/main/assets/omniflow/runlog/examples/unified-entry-surfaces.json"
        )
        val plan = readSource(
            "app/src/main/assets/omniflow/runlog/unified-execution-plan.md"
        )
        val readme = readSource("app/src/main/assets/omniflow/runlog/README.md")
        val httpHost = readSource(
            "app/src/main/java/cn/com/omnimind/bot/devicehost/LocalDeviceHttpHostManager.kt"
        )
        val mcpRoutes = readSource(
            "app/src/main/java/cn/com/omnimind/bot/mcp/McpRoutes.kt"
        )
        val assistsManager = readSource(
            "app/src/main/java/cn/com/omnimind/bot/manager/AssistsCoreManager.kt"
        )
        val coordinator = readSource(
            "app/src/main/java/cn/com/omnimind/bot/vlm/VlmToolCoordinator.kt"
        )
        val toolkit = readSource(
            "app/src/main/java/cn/com/omnimind/bot/runlog/OobOmniFlowToolkitService.kt"
        )

        assertTrue(contract["schema_version"] == "oob.omniflow_entry_surface_contract.v1")
        assertTrue(contract["runtime_owner"] == "oob_native_kotlin")
        assertTrue(contract["native_facade"] == "OobOmniFlowToolkitService")
        assertTrue(contract["phone_action_runner"] == "OobFunctionRunner")
        assertTrue(contract["enhancement_policy"] == "offline_only")
        assertTrue(mapArg(contract["product_label"])["zh"] == "复用指令")
        assertTrue(listAny(mapArg(contract["product_label"])["compatibility_aliases"]).contains("复用记忆"))
        assertTrue(plan.contains("examples/unified-entry-surfaces.json"))
        assertTrue(readme.contains("examples/unified-entry-surfaces.json"))

        val surfaces = listMaps(contract["entry_surfaces"])
        val byId = surfaces.associateBy { it["id"]?.toString().orEmpty() }
        assertTrue(byId.keys.containsAll(listOf(
            "vlm_task_recall_fast_path",
            "ui_direct_function_run",
            "ui_update_function",
            "mcp_function_lifecycle_tools",
            "http_function_run",
            "debug_recall_hit_only",
            "python_omniflow_offline",
        )))

        val vlm = byId.getValue("vlm_task_recall_fast_path")
        assertTrue(vlm["may_execute_phone_actions"] == true)
        assertTrue(vlm["phone_action_owner"] == "kotlin_only")
        assertTrue(vlm["model_visible_function_execution_tool"] == false)
        assertTrue(vlm["result_route_prefix"] == "omniflow_recall_hit")
        assertTrue(coordinator.contains("OobOmniFlowToolkitService(context).runFunction("))
        assertTrue(coordinator.contains("tryExecuteRecallHitOnly"))

        val uiRun = byId.getValue("ui_direct_function_run")
        assertTrue(uiRun["requires_concrete_function_id"] == true)
        assertTrue(uiRun["arguments_field_policy"] == "nested_arguments_preserved")
        assertTrue(assistsManager.contains("fun runOobReusableFunction("))
        assertTrue(assistsManager.contains("OobOmniFlowToolkitService(context).runFunction("))
        assertTrue(assistsManager.contains("\"arguments\" to callArguments"))
        assertTrue(assistsManager.contains("\"frontend_parent\" to \"oob_direct_replay\""))

        val uiUpdate = byId.getValue("ui_update_function")
        assertTrue(uiUpdate["may_execute_phone_actions"] == false)
        assertTrue(uiUpdate["enhance_policy"] == "offline_background_only")
        assertTrue(assistsManager.contains("fun updateOobFunction("))
        assertTrue(assistsManager.contains("toolName = OobFunctionToolNames.FUNCTION_UPDATE"))

        val mcp = byId.getValue("mcp_function_lifecycle_tools")
        val allowedTools = listAny(mcp["allowed_tools"]).map { it.toString() }
        assertTrue(allowedTools.contains("update_function"))
        assertTrue(allowedTools.contains("oob_run_log_convert"))
        assertTrue(listAny(mcp["forbidden_public_tools"]).contains("run_function"))
        assertTrue(mcpRoutes.contains("OMNIFLOW_MCP_TOOL_NAMES"))
        assertTrue(mcpRoutes.contains("omniflowToolkit.executeTool(name, args)"))
        assertTrue(!mcpRoutes.contains("\"run_function\" ->"))

        val http = byId.getValue("http_function_run")
        assertTrue(http["debug_or_dev_only"] == true)
        assertTrue(http["arguments_field_policy"] == "nested_arguments_preserved")
        assertTrue(httpHost.contains("post(\"/omniflow/function/run\")"))
        assertTrue(httpHost.contains("OobOmniFlowToolkitService(context).executeTool(\"run_function\", args)"))
        assertTrue(httpHost.contains("val publicArguments = mapArg(body[\"arguments\"])"))

        val debugRecall = byId.getValue("debug_recall_hit_only")
        assertTrue(debugRecall["debug_or_dev_only"] == true)
        assertTrue(debugRecall["result_route_prefix"] == "omniflow_recall_hit")

        val python = byId.getValue("python_omniflow_offline")
        assertTrue(python["may_execute_phone_actions"] == false)
        assertTrue(python["must_call_oob_adapter_for_phone_execution"] == true)
        assertTrue(python["enhance_policy"] == "offline_patches_only")

        val invariants = mapArg(contract["invariants"])
        assertTrue(invariants["direct_function_calls_require_id"] == true)
        assertTrue(invariants["enhance_never_blocks_registration_recall_or_replay"] == true)
        assertTrue(invariants["python_never_owns_accessibility_or_overlay"] == true)
        assertTrue(invariants["ui_never_interprets_function_steps_in_dart"] == true)
        assertTrue(toolkit.contains("\"run_function\", \"oob_function_run\" -> runFunction(args)"))
        assertTrue(toolkit.contains("suspend fun updateFunction(args: Map<String, Any?>?)"))
        assertTrue(toolkit.contains("functionStepwiseUpdateOrchestrator.updateFunction(args)"))
    }

    private fun readSource(relativePath: String): String {
        return String(Files.readAllBytes(findSource(relativePath)))
    }

    @Suppress("UNCHECKED_CAST")
    private fun readJsonMap(relativePath: String): Map<String, Any?> =
        Gson().fromJson(String(Files.readAllBytes(findSource(relativePath))), Map::class.java)
            as Map<String, Any?>

    private fun listMaps(value: Any?): List<Map<String, Any?>> =
        (value as? List<*>).orEmpty().map { mapArg(it) }

    private fun listAny(value: Any?): List<Any?> =
        value as? List<*> ?: emptyList<Any?>()

    private fun phase(steps: List<Map<String, Any?>>, name: String): Map<String, Any?> =
        steps.firstOrNull { it["phase"] == name } ?: error("Missing example phase: $name")

    private fun mapArg(value: Any?): Map<String, Any?> =
        when (value) {
            is Map<*, *> -> value.entries.associate { (key, item) -> key.toString() to item }
            else -> emptyMap()
        }

    private fun findSource(relativePath: String) =
        listOf(
            Paths.get(relativePath),
            Paths.get("..").resolve(relativePath)
        ).firstOrNull { Files.exists(it) }
            ?: error("Missing source file: $relativePath from ${Paths.get("").toAbsolutePath()}")
}
