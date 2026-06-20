package cn.com.omnimind.bot.runlog

import cn.com.omnimind.bot.mcp.McpToolDefinitions
import com.google.gson.Gson
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assert.assertFalse
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
        assertTrue(plan.contains("| Product UI Function run | concrete `function_id`"))
        assertTrue(plan.contains("toolProfile=function_management"))
        assertTrue(plan.contains("allowedTools=[oob_function_run]"))
        assertTrue(plan.contains("| UI direct Function adapter | concrete `function_id`"))
        assertTrue(plan.contains("Ordinary product UI should call the Agent path above"))
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
        assertTrue(plan.contains("provider_tool_call_contract_violation"))
        assertTrue(plan.contains("single OpenAI native `tool_calls[0]`"))
        assertTrue(plan.contains("not a reason to re-enable legacy text parsing"))
        assertTrue(plan.contains("Manual recording: a visible `录制轨迹` entry starts recording"))
        assertTrue(plan.contains("First VLM run: `vlm_task` succeeds"))
        assertTrue(plan.contains("Second VLM run: from the same or equivalent page"))
        assertTrue(plan.contains("No inline enhancement"))
        assertTrue(plan.contains("Python compatibility"))
        assertTrue(plan.contains("UI stability"))
        assertTrue(plan.contains("Localization/product wording"))
        assertTrue(plan.contains("`复用指令` as the product name"))
        assertTrue(plan.contains("`复用记忆` is accepted only as compatibility wording"))
        assertTrue(plan.contains("AndroidWorld replay"))
        assertTrue(plan.contains("scripts/androidworld_eval.py first30 --methods"))
        assertTrue(plan.contains("canonical RunLog import"))
        assertTrue(plan.contains("AndroidWorld validator scoring"))
        assertTrue(plan.contains("`--limit 1` run is a smoke"))
        assertTrue(plan.contains("first30 or"))
        assertTrue(plan.contains("online VLM accuracy is good"))
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
        assertTrue(smoke.contains("## User-Facing Examples"))
        assertTrue(smoke.contains("点击设置搜索框"))
        assertTrue(smoke.contains("Search cats in Xiaohongshu"))
        assertTrue(smoke.contains("OpenAI native tool call"))
        assertTrue(smoke.contains("Do not teach users or models to output `function_id`"))

        assertTrue(example["schema_version"] == "oob.vlm_task_recall_loop_example.v1")
        assertTrue(example["runtime_owner"] == "oob_native_kotlin")
        assertTrue(example["enhancement_policy"] == "offline_only")
        val visibleExamples = mapArg(example["visible_examples"])
        val zhExamples = listMaps(visibleExamples["zh"])
        val enExamples = listMaps(visibleExamples["en"])
        assertTrue(zhExamples.map { it["title"] }.containsAll(listOf("打开网络设置", "点击设置搜索框", "在小红书搜索猫咪")))
        assertTrue(enExamples.map { it["title"] }.containsAll(listOf("Open network settings", "Tap the Settings search field", "Search cats in Xiaohongshu")))
        (zhExamples + enExamples).forEach { visibleExample ->
            val payload = mapArg(visibleExample["tool_payload"])
            val args = mapArg(payload["arguments"])
            assertTrue(payload["tool"] == "vlm_task")
            assertTrue(args["goal"].toString().isNotBlank())
            assertTrue(args["allowOmniFlowFunctionAutoExecute"] == true)
            assertTrue(!args.containsKey("operation"))
            assertTrue(!args.containsKey("function_id"))
            assertTrue(!args.containsKey("call_tool"))
        }
        val examplePolicy = mapArg(example["example_policy"])
        assertTrue(examplePolicy["model_output_contract"] == "native_openai_tool_calls_only")
        assertTrue(listAny(examplePolicy["forbidden_model_output_fields"]).contains("function_id"))
        assertTrue(listAny(examplePolicy["forbidden_model_output_fields"]).contains("call_tool"))
        val steps = listMaps(example["steps"])
        val firstRun = phase(steps, "first_vlm_run")
        val firstRunPayload = mapArg(mapArg(firstRun["tool_payload"])["arguments"])
        assertTrue(mapArg(firstRun["tool_payload"])["tool"] == "vlm_task")
        assertTrue(firstRunPayload["goal"] == "打开网络设置")
        assertTrue(!firstRunPayload.containsKey("operation"))
        assertTrue(firstRunPayload["registerRunLog"] == true)
        assertTrue(firstRunPayload["allowOmniFlowFunctionAutoExecute"] == true)
        assertTrue(mapArg(firstRun["expected"])["function_spec.metadata.enhancement_policy"] == "offline_only")

        val fastRun = phase(steps, "second_fast_vlm_run")
        val fastRunPayload = mapArg(mapArg(fastRun["tool_payload"])["arguments"])
        assertTrue(fastRunPayload["goal"] == "打开网络设置")
        assertTrue(!fastRunPayload.containsKey("operation"))
        assertTrue(fastRunPayload["startFromCurrent"] == true)
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
        assertTrue(plan.contains("AndroidWorld replay evidence is a third, narrower signal"))
        assertTrue(plan.contains("known accepted canonical RunLogs"))
        assertTrue(plan.contains("does not exercise the online `vlm_task` provider"))
        assertTrue(plan.contains("Treat a one-task"))
        assertTrue(plan.contains("`SimpleSmsSend` pass as a smoke only"))
        assertTrue(plan.contains("first30 or a named task set"))
    }

    @Test
    fun `omniflow vlm reference keeps enhancement out of online replay path`() {
        val skill = readSource("app/src/main/assets/builtin_skills/omniflow/SKILL.md")
        val vlm = readSource(
            "app/src/main/assets/builtin_skills/omniflow/references/vlm-online-execution.md"
        )

        assertTrue(skill.contains("references/vlm-online-execution.md"))
        assertTrue(vlm.contains("Auto-registration saves the replayable Function first"))
        assertTrue(vlm.contains("Do not call"))
        assertTrue(vlm.contains("`update_function`"))
        assertTrue(vlm.contains("`enhance`"))
        assertTrue(vlm.contains("inline before VLM"))
        assertTrue(vlm.contains("RunLog registration"))
        assertTrue(vlm.contains("recall-hit replay"))
        assertTrue(vlm.contains("debug"))
        assertTrue(vlm.contains("convert-and-replay"))
        assertTrue(vlm.contains("Enhancement is an explicit offline/background maintenance"))
        assertTrue(vlm.contains("must not block direct replay"))
        assertTrue(vlm.contains("second-run recall fast path"))
        assertTrue(vlm.contains("Tool Boundary"))
        assertTrue(vlm.contains("`vlm_task` is the online device-automation entry"))
        assertTrue(vlm.contains("only"))
        assertTrue(vlm.contains("model-visible action entry"))
        assertTrue(vlm.contains("native"))
        assertTrue(vlm.contains("tool_calls"))
        assertFalse(vlm.contains("`oob_function_list`"))
        assertFalse(vlm.contains("`oob_run_log_list`"))
        assertFalse(vlm.contains("`omniflow.recall`"))
    }

    @Test
    fun `builtin skills treat reusable memory as compatibility wording`() {
        val manifest = readSource("app/src/main/assets/builtin_skills/manifest.json")
        val omniflow = readSource("app/src/main/assets/builtin_skills/omniflow/SKILL.md")
        val zhArb = readJsonMap("ui/lib/l10n/app_zh.arb")
        val enArb = readJsonMap("ui/lib/l10n/app_en.arb")

        assertTrue(manifest.contains("复用记忆"))
        assertTrue(omniflow.contains("\"复用记忆\""))
        assertTrue(omniflow.contains("compatibility phrase for saved"))
        assertTrue(omniflow.contains("keep the product wording as \"复用指令\""))
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
    fun `builtin skills expose a single omniflow entry`() {
        val manifest = readSource("app/src/main/assets/builtin_skills/manifest.json")
        val manifestMap = readJsonMap("app/src/main/assets/builtin_skills/manifest.json")
        val manifestSkills = listMaps(manifestMap["skills"])
        val skillIds = manifestSkills.mapNotNull { it["id"]?.toString() }.toSet()
        val omniflowManifest = manifestSkills.first { it["id"] == "omniflow" }
        val omniflow = readSource("app/src/main/assets/builtin_skills/omniflow/SKILL.md")
        val enhancement = readSource("app/src/main/assets/builtin_skills/omniflow/references/function-enhancement.md")
        val checkers = readSource("app/src/main/assets/builtin_skills/omniflow/references/checkers.md")
        val vlm = readSource("app/src/main/assets/builtin_skills/omniflow/references/vlm-online-execution.md")
        val retiredSkillAssets = listOf(
            "app/src/main/assets/builtin_skills/oob-function-management/SKILL.md",
            "app/src/main/assets/builtin_skills/omniflow-function-enhancer/SKILL.md",
            "app/src/main/assets/builtin_skills/omniflow-checker-maintainer/SKILL.md",
            "app/src/main/assets/builtin_skills/oob-function-management/agents/openai.yaml",
            "app/src/main/assets/builtin_skills/omniflow-function-enhancer/agents/openai.yaml",
            "app/src/main/assets/builtin_skills/omniflow-checker-maintainer/agents/openai.yaml",
            "app/src/main/assets/builtin_skills/omniflow-function-enhancer/references/enhancement-rubric.md",
            "app/src/main/assets/builtin_skills/omniflow-function-enhancer/references/runtime-contract.md",
            "app/src/main/assets/builtin_skills/vlm-android-gui/SKILL.md",
        )

        assertTrue(manifest.contains("\"id\": \"omniflow\""))
        assertFalse(manifest.contains("\"id\": \"vlm-android-gui\""))
        assertTrue(skillIds.contains("omniflow"))
        assertFalse(skillIds.contains("vlm-android-gui"))
        assertTrue(omniflowManifest["hasReferences"] == true)
        assertTrue(sourceExists("app/src/main/assets/builtin_skills/omniflow/references"))
        assertFalse(manifest.contains("\"id\": \"oob-function-management\""))
        assertFalse(manifest.contains("\"id\": \"omniflow-function-enhancer\""))
        assertFalse(manifest.contains("\"id\": \"omniflow-checker-maintainer\""))
        assertTrue(omniflow.contains("Single Entry Point"))
        assertTrue(omniflow.contains("VLM Android GUI"))
        assertTrue(omniflow.contains("references/vlm-online-execution.md"))
        assertTrue(omniflow.contains("Function enhancement"))
        assertTrue(omniflow.contains("checker maintenance"))
        assertTrue(omniflow.contains("runtime checker"))
        assertTrue(omniflow.contains("upgrade/update popup checker"))
        assertTrue(enhancement.contains("Runtime Contract"))
        assertTrue(enhancement.contains("Parameter Rubric"))
        assertTrue(enhancement.contains("auto_analyze_with_model=true"))
        assertTrue(enhancement.contains("observe -> checker -> action_transfer -> execute"))
        assertTrue(checkers.contains("app_upgrade_prompt"))
        assertTrue(checkers.contains("permission_dialog"))
        assertTrue(vlm.contains("Native Tool Call Contract"))
        assertTrue(vlm.contains("Ordinary online VLM output must be OpenAI-compatible native"))
        assertTrue(vlm.contains("`update_function`"))
        assertFalse(vlm.contains("Function enhancement skill"))
        assertFalse(vlm.contains("`oob_function_update`"))
        retiredSkillAssets.forEach { path ->
            assertFalse("Retired OmniFlow skill asset must not be bundled: $path", sourceExists(path))
        }
    }

    @Test
    fun `vlm recall loop smoke script is strict and executable`() {
        val scriptPath = findSource("scripts/oob-vlm-recall-loop-smoke.sh")
        val script = String(Files.readAllBytes(scriptPath))
        val debugReceiver = readSource(
            "app/src/debug/java/cn/com/omnimind/bot/debug/DebugVlmRunLogReceiver.kt"
        )

        assertTrue(script.contains("RUN_VLM_RUNLOG"))
        assertTrue(script.contains("RUN_OOB_RECALL"))
        assertTrue(script.contains("RUN_VLM_RECALL_HIT"))
        assertTrue(script.contains("CONVERT_RUNLOG_AND_RUN_FUNCTION"))
        assertTrue(script.contains("validate_first_run"))
        assertTrue(script.contains("validate_recall"))
        assertTrue(script.contains("validate_recall_hit"))
        assertTrue(script.contains("validate_second_run"))
        assertTrue(script.contains("validate_enhance_offline"))
        assertTrue(script.contains("check_first_run_provider_blocker"))
        assertTrue(script.contains("provider_auth_or_configuration_failed"))
        assertTrue(script.contains("unable to resolve host"))
        assertTrue(script.contains("no address associated with hostname"))
        assertTrue(script.contains("connection reset"))
        assertTrue(script.contains("model provider configuration/authentication/network"))
        assertTrue(script.contains("provider_tool_call_contract_violation"))
        assertTrue(script.contains("Use a VLM provider/model that returns OpenAI native tool_calls"))
        assertTrue(script.contains("raise SystemExit(5)"))
        assertTrue(script.contains("raise SystemExit(4)"))
        assertTrue(
            script.indexOf("check_first_run_provider_blocker \"\$FIRST_JSON\"") <
                script.indexOf("validate_first_run \"\$FIRST_JSON\"")
        )
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
        assertTrue(script.contains("first_vlm_broadcast_args=("))
        assertTrue(script.contains("first_vlm_broadcast_args+=(\"\${offline_seed_args[@]}\")"))
        assertTrue(script.contains("first_vlm_broadcast_args+=(\"\${optional_model_args[@]}\")"))
        assertTrue(script.contains("second_vlm_broadcast_args=("))
        assertTrue(script.contains("second_vlm_broadcast_args+=(\"\${optional_model_args[@]}\")"))
        assertTrue(script.contains("\"${'$'}{ADB[@]}\" \"${'$'}{first_vlm_broadcast_args[@]}\""))
        assertTrue(script.contains("\"${'$'}{ADB[@]}\" \"${'$'}{second_vlm_broadcast_args[@]}\""))
        assertTrue(debugReceiver.contains("\"allowOmniFlowFunctionAutoExecute\""))
        assertTrue(debugReceiver.contains("\"allow_omniflow_function_auto_execute\""))
        assertTrue(debugReceiver.contains("\"auto_execute\""))
        assertTrue(debugReceiver.contains("default = true"))
        assertTrue(debugReceiver.contains("allowOmniFlowFunctionAutoExecute = allowOmniFlowFunctionAutoExecute"))
        assertTrue(debugReceiver.contains("\"allow_omniflow_function_auto_execute\" to allowOmniFlowFunctionAutoExecute"))

        val permissions = Files.getPosixFilePermissions(scriptPath)
        assertTrue(permissions.contains(PosixFilePermission.OWNER_EXECUTE))
    }

    @Test
    fun `mainline acceptance reports online and offline vlm scope separately`() {
        val scriptPath = findSource("scripts/oob-mainline-acceptance.sh")
        val script = String(Files.readAllBytes(scriptPath))

        assertTrue(script.contains("offline_seed mode validates the native OOB RunLog/register/recall/replay loop"))
        assertTrue(script.contains("Use --online-vlm with a configured provider that returns native OpenAI tool_calls"))
        assertTrue(script.contains("online_vlm mode measures the configured provider"))
        assertTrue(script.contains("provider_auth_or_configuration_failed and provider_tool_call_contract_violation"))
        assertTrue(script.contains("not reasons to re-enable legacy text action parsing"))
        assertTrue(script.contains("unable to resolve host"))
        assertTrue(script.contains("no address associated with hostname"))
        assertTrue(script.contains("connection reset"))
        assertTrue(script.contains("Python OmniFlow remains an offline/dev/eval adapter"))
        assertTrue(script.contains("accuracy_scope"))
        assertTrue(script.contains("GIT_BRANCH="))
        assertTrue(script.contains("GIT_COMMIT="))
        assertTrue(script.contains("GIT_DIRTY="))
        assertTrue(script.contains("\"git\""))
        assertTrue(script.contains("\"commit\""))
        assertTrue(script.contains("git_commit"))
        assertTrue(script.contains("online_vlm_plus_native_registration_recall_replay"))
        assertTrue(script.contains("native_registration_recall_replay_only"))
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
    fun `runlog readme code map points at existing sources`() {
        val readme = readSource("app/src/main/assets/omniflow/runlog/README.md")
        val pathPattern = Regex("`((?:app|ui|baselib|scripts|assists)/[^`]+)`")
        val missing = pathPattern.findAll(readme)
            .map { it.groupValues[1] }
            .distinct()
            .filterNot { sourceExists(it) }
            .toList()

        assertTrue("Missing Code Map paths: $missing", missing.isEmpty())
        assertTrue(readme.contains("Function spec/schema normalization"))
        assertTrue(readme.contains("do not recreate the old split patch-applier classes"))
        assertTrue(readme.contains("owns `call_tool`"))
        assertTrue(readme.contains("nested Function dispatch"))
        assertTrue(readme.contains("do not reintroduce separate step executors"))
    }

    @Test
    fun `function readme owner map matches current consolidated implementation`() {
        val readme = readSource("app/src/main/assets/omniflow/function/README.md")
        val updateService = readSource(
            "app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionUpdateService.kt"
        )
        val toolHandler = readSource(
            "app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionToolHandler.kt"
        )
        val stepNormalizer = readSource(
            "app/src/main/java/cn/com/omnimind/bot/omniflow/OobFunctionStepNormalizer.kt"
        )

        assertTrue(readme.contains("OobFunctionStepNormalizer"))
        assertTrue(readme.contains("OobFunctionUpdateService"))
        assertTrue(readme.contains("OobFunctionToolHandler"))
        assertTrue(readme.contains("do not recreate the old split patch-applier classes"))
        assertTrue(readme.contains("do not reintroduce separate `call_tool`"))

        val staleOwnerNames = listOf(
            "OobFunctionSpecBuilder",
            "OobFunctionMetadataPatchApplier",
            "OobFunctionStructuralPatchApplier",
            "OobFunctionCheckerPatchService",
            "OobFunctionStepClassifier",
            "OobFunctionCallToolStepExecutor",
            "OobFunctionNestedFunctionExecutor",
        )
        val staleMentions = staleOwnerNames.filter { readme.contains(it) }
        assertTrue("Stale Function README owner names: $staleMentions", staleMentions.isEmpty())

        assertTrue(updateService.contains("Consolidates OobFunctionStructuralPatchApplier"))
        assertTrue(toolHandler.contains("Inlined from OobFunctionStepClassifier"))
        assertTrue(stepNormalizer.contains("Used by OobFunctionUpdateService"))
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
        val flutterAssistsService = readSource("ui/lib/services/assists_core_service.dart")
        val functionLibraryPage = readSource(
            "ui/lib/features/task/pages/execution_history/function_library_page.dart"
        )
        val runLogTimelinePage = readSource(
            "ui/lib/features/task/pages/execution_history/run_log_timeline_page.dart"
        )
        val functionRunResultSheet = readSource(
            "ui/lib/features/task/pages/execution_history/function_run_result_sheet.dart"
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
            "ui_agent_function_run",
            "ui_direct_function_run_adapter",
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
        assertTrue(
            (listAny(vlm["control_flags"]).contains("disableOmniFlowRecall") &&
                listAny(vlm["control_flags"]).contains("allowOmniFlowFunctionAutoExecute"))
        )
        assertTrue(coordinator.contains("OobOmniFlowToolkitService(context).runFunction("))
        assertTrue(coordinator.contains("tryExecuteRecallHitOnly"))

        val uiRun = byId.getValue("ui_agent_function_run")
        assertTrue(uiRun["requires_concrete_function_id"] == true)
        assertTrue(uiRun["tool_profile"] == "function_management")
        assertTrue(listAny(uiRun["allowed_tools"]).contains("oob_function_run"))
        assertTrue(uiRun["phone_action_owner"] == "agent_profile_then_kotlin_runner")
        assertTrue(uiRun["model_visible_function_execution_tool"] == true)
        assertTrue(flutterAssistsService.contains("runOobReusableFunctionWithAgent"))
        assertTrue(flutterAssistsService.contains("toolProfile: 'function_management'"))
        assertTrue(flutterAssistsService.contains("allowedTools: const ['oob_function_run']"))
        assertTrue(functionLibraryPage.contains("runOobReusableFunctionWithAgent"))
        assertTrue(runLogTimelinePage.contains("runOobReusableFunctionWithAgent"))
        assertTrue(functionRunResultSheet.contains("runOobReusableFunctionWithAgent"))

        val directAdapter = byId.getValue("ui_direct_function_run_adapter")
        assertTrue(directAdapter["requires_concrete_function_id"] == true)
        assertTrue(directAdapter["arguments_field_policy"] == "nested_arguments_preserved")
        assertTrue(directAdapter["debug_or_compat_only"] == true)
        assertTrue(directAdapter["ordinary_product_ui_should_call"] == "ui_agent_function_run")
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
        assertTrue(allowedTools.contains("omniflow.recall"))
        assertTrue(allowedTools.contains("omniflow.ingest_run_log"))
        assertTrue(allowedTools.contains("omniflow.explore_replay"))
        val fixedMcpToolNames = McpToolDefinitions.fixedToolNames
        val missingAllowedTools = allowedTools.filterNot { it in fixedMcpToolNames }
        assertTrue(
            "Entry-surface allowed MCP tools must be exposed by McpToolDefinitions: $missingAllowedTools",
            missingAllowedTools.isEmpty()
        )
        val forbiddenPublicTools = listAny(mcp["forbidden_public_tools"]).map { it.toString() }
        assertTrue(forbiddenPublicTools.contains("run_function"))
        assertTrue(forbiddenPublicTools.contains("call_tool"))
        val exposedForbiddenTools = forbiddenPublicTools.filter { it in fixedMcpToolNames }
        assertTrue(
            "Entry-surface forbidden Function execution tools must not be public MCP tools: $exposedForbiddenTools",
            exposedForbiddenTools.isEmpty()
        )
        assertTrue(mcpRoutes.contains("OMNIFLOW_MCP_TOOL_NAMES"))
        assertTrue(mcpRoutes.contains("omniflowToolkit.executeTool(name, args)"))
        assertTrue(!mcpRoutes.contains("\"run_function\" ->"))
        assertTrue(!mcpRoutes.contains("TOOL_CALL_TOOL ->"))
        assertTrue(!mcpRoutes.contains("executeOobToolCall(context, args)"))

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

    private fun sourceExists(relativePath: String): Boolean =
        listOf(
            Paths.get(relativePath),
            Paths.get("..").resolve(relativePath)
        ).any { Files.exists(it) }
}
