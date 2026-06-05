# OOB Agent Context Index

Status: Draft
Last Updated: 2026-06-01

## Fixed Read Order For Workbench Backend Tasks

1. `AGENTS.md`
2. `docs/reference/OOB_INTEGRATION.md`
3. `docs/reference/OOB_WORKBENCH_BACKEND_RUNTIME.md`
4. `docs/agent_context/ROOT_FILE_INVENTORY.md`
5. `docs/agent_context/skills/oob-workbench-backend/SKILL.md`
6. Target source files:
   - `app/src/main/java/cn/com/omnimind/bot/workbench/WorkbenchRuntime.kt`
   - `app/src/main/java/cn/com/omnimind/bot/workbench/WorkbenchToolboxBuilder.kt`
   - `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/WorkbenchToolHandler.kt`
   - `app/src/main/java/cn/com/omnimind/bot/agent/tool/AgentToolDefinitions.kt`
   - `app/src/main/java/cn/com/omnimind/bot/mcp/McpToolDefinitions.kt`
   - `app/src/main/java/cn/com/omnimind/bot/mcp/McpPromptDefinitions.kt`
   - `app/src/main/assets/builtin_skills/oob-project/SKILL.md`
   - `ui/lib/features/workbench/`

## Fixed Read Order For OOB VLM AndroidWorld Tasks

1. `AGENTS.md`
2. `docs/reference/OOB_VLM_ANDROIDWORLD.md`
3. `docs/agent_context/OOB_ONLINE_OFFLINE_SHARED_MEMORY.md`
4. `docs/agent_context/OOB_RUNLOG_UDEG_KEY_FUNCTION_RESEARCH.md`
5. `docs/agent_context/OOB_STARTUP_RUNBOOK.md`
6. `docs/agent_context/OOB_DEVICE_VALIDATION_2026-05-25.md`
7. `app/src/main/assets/builtin_skills/vlm-android-gui/SKILL.md`
8. Target source files:
   - `assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/VLMOperationService.kt`
   - `assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/VLMClient.kt`
   - `assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/VLMIndexedPageContext.kt`
   - `assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/VLMPostActionObservation.kt`
   - `app/src/debug/java/cn/com/omnimind/bot/debug/DebugVlmRunLogReceiver.kt`
   - `app/src/debug/java/cn/com/omnimind/bot/debug/DebugOobFunctionSegmentReceiver.kt`
   - `app/src/main/java/cn/com/omnimind/bot/manager/AssistsCoreManager.kt`
   - `app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionToolHandler.kt`
   - `ui/lib/services/assists_core_service.dart`
   - `ui/lib/features/task/pages/execution_history/function_library_page.dart`
   - `ui/lib/features/task/pages/execution_history/function_run_result_sheet.dart`
   - `ui/lib/features/task/pages/execution_history/run_log_timeline_page.dart`
   - `scripts/demo-vlm-runlog-e2e.sh`
9. Focused tests:
   - `app/src/test/java/cn/com/omnimind/bot/manager/AssistsCoreManagerOobReusableFunctionPayloadTest.kt`
   - `app/src/test/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionToolHandlerOmniFlowExecutionTest.kt`
   - `ui/test/services/oob_reusable_function_execution_service_test.dart`
   - `ui/test/features/task/pages/execution_history/function_library_page_test.dart`
   - `ui/test/features/task/pages/execution_history/function_run_result_sheet_test.dart`
   - `ui/test/features/task/pages/execution_history/run_log_timeline_page_test.dart`
   - `ui/test/widgets/execution/execution_detail_view_test.dart`

## Fixed Read Order For OmniFlow Function Tasks

1. `AGENTS.md`
2. `docs/omniflow/function-replay-unified-design.md`
3. `docs/omniflow/README.md`
4. `docs/omniflow/MCP_CONTRACT.md`
5. `docs/omniflow/FUNCTION_SPEC.md`
6. `docs/omniflow/update-function.md`
7. `app/src/main/assets/builtin_skills/omniflow/SKILL.md`
8. `app/src/main/assets/builtin_skills/omniflow/references/unified-design.md`
9. Target source files:
   - `app/src/main/java/cn/com/omnimind/bot/omniflow/`
   - `app/src/main/java/cn/com/omnimind/bot/runlog/OobActionCodec.kt`
   - `app/src/main/java/cn/com/omnimind/bot/runlog/OobStepRoleClassifier.kt`
   - `app/src/main/java/cn/com/omnimind/bot/runlog/RunLogReplayStepCompiler.kt`
   - `app/src/main/java/cn/com/omnimind/bot/runlog/RunLogReplayStepNoiseNormalizer.kt`
   - `app/src/main/java/cn/com/omnimind/bot/runlog/RunLogReusableFunctionCompiler.kt`
   - `app/src/main/java/cn/com/omnimind/bot/runlog/UIStepExecutor.kt`
   - `app/src/main/java/cn/com/omnimind/bot/mcp/McpToolDefinitions.kt`
   - `app/src/main/java/cn/com/omnimind/bot/vlm/VlmToolCoordinator.kt`
   - `app/src/main/java/cn/com/omnimind/bot/vlm/VlmRecallGuidanceBuilder.kt`
   - `assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/VLMToolDefinitions.kt`
   - `assists/src/main/java/cn/com/omnimind/assists/task/vlmserver/ActionExecutor.kt`
10. Focused tests:
   - `app/src/test/java/cn/com/omnimind/bot/agent/tool/handlers/OobFunctionToolHandlerOmniFlowExecutionTest.kt`
   - `app/src/test/java/cn/com/omnimind/bot/vlm/VlmToolCoordinatorRecallExecutionTest.kt`
   - `app/src/test/java/cn/com/omnimind/bot/vlm/VlmRecallGuidanceBuilderTest.kt`
   - `app/src/test/java/cn/com/omnimind/assists/task/vlmserver/VLMToolDefinitionsTest.kt`

## Current Workbench Focus

- Generic Project container with Project Tools, persistent state, logs, source assets, and export.
- HTML WebView as a first-class renderer for rich reports, dashboards, charts, custom UI, and fast visual iteration.
- Default Project Display as a generic Flutter fallback for structured data and actions.
- `flutter_eval` as a supplemental limited renderer.
- Hot update loop: user context -> Agent edit -> Project source update -> right-side Display refresh.

## Out Of Scope

- Preset app flows.
- Arbitrary native bridges exposed to HTML.
- Native-code network fetch for external repositories.
- Creating replacement Projects for ordinary feature iteration.

## Current Verification State

- Workbench runtime uses one generic Project creation path.
- Project payloads return `frontendHtml`, `frontendFlutter`, `pageSpec`, `tools`, `toolbox`, and `items`.
- HTML sources under `frontend/html/` are bounded, manifest-backed, and loaded through `/workbench/html`.
- Project Tools are exposed through Flutter, Agent, and active MCP Toolbox paths.
- Read-only MCP Resources expose Project, active Project, Toolbox, progress, logs, and source manifest.
