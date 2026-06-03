# OOB MCP Capabilities

This document is the operator-facing inventory of capabilities exposed by the
OOB MCP server. Treat `tools/list`, `resources/list`, and `prompts/list` as the
runtime source of truth; this file explains what those surfaces mean and how a
Python script should call them.

Source files:

- `app/src/main/java/cn/com/omnimind/bot/mcp/McpRoutes.kt`
- `app/src/main/java/cn/com/omnimind/bot/mcp/McpToolDefinitions.kt`
- `app/src/main/java/cn/com/omnimind/bot/mcp/McpToolExecutors.kt`
- `app/src/main/java/cn/com/omnimind/bot/mcp/McpPromptDefinitions.kt`
- `app/src/main/java/cn/com/omnimind/bot/workbench/WorkbenchRuntime.kt`
- `omniflow_agentkit/mcp.py`

## Transport

The primary external API is JSON-RPC over HTTP:

```text
POST /mcp
Authorization: Bearer <token>
Content-Type: application/json
```

Basic JSON-RPC calls:

```json
{"jsonrpc":"2.0","id":"1","method":"initialize","params":{"protocolVersion":"2024-11-05"}}
{"jsonrpc":"2.0","id":"2","method":"tools/list"}
{"jsonrpc":"2.0","id":"3","method":"tools/call","params":{"name":"get_state","arguments":{}}}
{"jsonrpc":"2.0","id":"4","method":"resources/list"}
{"jsonrpc":"2.0","id":"5","method":"resources/read","params":{"uri":"oob://projects/active","limit":50}}
{"jsonrpc":"2.0","id":"6","method":"prompts/list"}
{"jsonrpc":"2.0","id":"7","method":"prompts/get","params":{"name":"inspect_active_toolbox"}}
```

The server also exposes authenticated compatibility routes:

- `GET /mcp/state`: current MCP server state.
- `GET|POST /mcp/list_tools`: REST-style tool discovery.
- `POST /mcp/call_tool`: REST-style tool call with `{name, arguments}`.
- `POST /mcp/v1/task/vlm`: legacy VLM task entry.
- `GET /mcp/v1/task/{taskId}/status`: legacy task status.
- `POST /mcp/v1/task/{taskId}/reply`: legacy task reply.
- `POST /mcp/workbench/call`: local Dashboard/E2E Workbench debug transport.
- `GET /mcp/file/{fileId}`: temporary file download; uses file token or bearer
  auth.
- `GET /mcp/health`: unauthenticated health check.

For external agents, prefer JSON-RPC `tools/list` and `tools/call`. Do not
depend on hidden or legacy routes that are not returned by `tools/list`.

## Tool Discovery Model

`tools/list` returns:

1. Fixed OOB MCP tools from `McpToolDefinitions.fixedTools`.
2. Dynamic Project Toolbox tools from the currently active Workbench Project.

Dynamic Project tools are mounted only when a Project is active and Project
capabilities are enabled. Their names are generated from the Project/toolbox
contract, for example `<project-or-namespace>.<api-or-tool>`. Call them exactly
as returned by `tools/list`; arguments are forwarded to the Project Tool
executor.

## Fixed Tools

| Tool | Capability | Main arguments |
| --- | --- | --- |
| `vlm_task` | Run an autonomous visual GUI task on the Android device. Blocks until finish, timeout, pause, or required input. | `goal` required; optional `model`, `packageName`, `maxSteps`, `timeoutMs`, `startFromCurrent`, `needSummary`, `disableOmniFlowRecall`, `allowOmniFlowFunctionAutoExecute`. |
| `task_status` | Query a long-running or timed-out VLM task. | `taskId` required. |
| `task_reply` | Reply to a VLM task that is waiting for user input. | `taskId`, `reply` required. |
| `task_wait_unlock` | Wait for screen unlock and resume or start the paused VLM task. | `taskId` required. |
| `get_state` | Read-only current Android state capture from OOB Accessibility runtime. Returns package/activity, XML metadata, screenshot metadata, optional screenshot data URI, indexed page evidence, and marked screenshot. | Optional `include_xml`, `include_screenshot`, `include_indexed_context`, `include_marked_screenshot`, `include_image_content`, `filter_overlay`, `image_quality`, `max_xml_chars`. |
| `file_transfer` | Read files shared into OOB and expose short-lived download URLs. | Optional `action` (`latest`, `wait`, `list`, `get`, `clear`), `fileId`, `afterFileId`, `timeoutMs`, `limit`. |
| `agent_run` | Submit a prompt into the normal in-app OOB Agent runtime. | `userMessage` required; optional `conversationId`, `conversationMode`, `title`, `taskId`, `attachments`, `modelOverride`, `toolProfile`, `allowedTools`. |
| `oob_tool_call` | Generic bridge to call an OOB capability through the in-app Agent runtime, or run one saved Function segment by `function_id`. | Optional `tool_name`, `function_id`, `arguments`, `goal`. |
| `omniflow.call_tool` | Canonical external OmniFlow/OOB adapter. Use `function_id` for a saved Function segment, or `tool_name` plus `arguments` for another OOB tool. | Optional `tool_name`, `function_id`, `arguments`, `goal`. |
| `omniflow.recall` | Recall page-matched UDEG node context and attached Functions for a goal. | `goal` required; optional `current_package`, `current_node_id`, `current_xml`, `k`, `include_debug`. |
| `omniflow.ingest_run_log` | Convert a successful RunLog into a local manual Function asset. | Optional `run_id`, `run_log`, `register`, `agent_visible`, `auto_enrich`. |
| `omniflow.explore_replay` | Explore UI, persist path as RunLog, convert to Function, and optionally replay it. | `goal` required; optional `package_name`, `max_steps`, `settle_delay_ms`, `stop_text`, `allow_risky_actions`, `function_id`, `replay`, `reset_before_replay`, `reset_back_steps`, `arguments`. |
| `oob_function_list` | List registered reusable Functions. | Optional `limit`. |
| `oob_function_get` | Read one reusable Function. | `function_id` required. |
| `oob_function_register` | Register or update one reusable Function. | Optional `function_id`, `name`, `description`, `package_name`, `source_page`, `parameters`, `steps`, `function_spec`. |
| `update_function` | Update a saved Function from patch, correction, or RunLog evidence. | `function_id` required; optional `run_id`, `instruction`, `mode`, `analysis`, `patch`, `dry_run`, `allow_execution_change`, `allow_structural_change`. |
| `oob_function_guard_check` | Run preflight guard checks for a reusable Function. | `function_id` required; optional `arguments`. |
| `oob_function_run` | Deterministically run one saved Function segment. Supports fallback/resume. | `function_id` required; optional `arguments`, `resume_from_step`, `fallback_session_id`, `fallback_attempt`, `dry_run`, `execution_mode`, `confirmed`. |
| `oob_function_delete` | Delete one reusable Function from registry/workspace/UDEG references. | `function_id` required. |
| `oob_function_clear` | Clear all reusable Functions and detach Function references. | `confirm` required and must be true. |
| `oob_run_log_list` | List recent internal RunLogs. | Optional `limit`. |
| `oob_run_log_get` | Read one internal RunLog timeline payload. | `run_id` required. |
| `oob_run_log_convert` | Convert one successful RunLog into a reusable Function and optionally register it. | `run_id` required; optional `register`, `function_id`, `name`, `description`. |
| `oob_project_create` | Create or reuse a Workbench Project and register its Project Tools. | Optional `projectId`, `name`, `prompt`, `entityName`, `initialItems`, `apis`, `htmlFiles`, `markdownFiles`, `flutterFiles`. |
| `oob_project_activate` | Activate a Workbench Project and mount its Project Toolbox as MCP dynamic tools. | `projectId` required. |
| `oob_project_open` | Open a Project Display route on the Android device. | `projectId` required. |
| `oob_project_progress_get` | Read recent Project creation/import progress rows. | Optional `projectId`, `limit`. |

## High-Value Workflows

### Capture current phone state

Use `get_state` for fast local XML/screenshot capture from the app runtime
instead of slow host-side `adb shell uiautomator dump` plus `screencap`.

```json
{
  "name": "get_state",
  "arguments": {
    "include_xml": true,
    "include_screenshot": true,
    "include_indexed_context": true,
    "include_marked_screenshot": false,
    "filter_overlay": true,
    "image_quality": "medium",
    "max_xml_chars": 200000
  }
}
```

Expected result fields include:

- `schema_version`: `oob.get_state.v1`.
- `package_name`, `activity_name`.
- `xml`, `xml_chars`, `xml_node_count`, `xml_truncated`.
- `indexed_page_evidence` when requested and XML is available.
- `screenshot.present`, `screenshot.data_uri`, dimensions, quality, and screen
  color heuristics.
- `marked_screenshot.data_uri` when requested.

### Run GUI automation

Use `vlm_task` when no deterministic Function is known:

```json
{
  "name": "vlm_task",
  "arguments": {
    "goal": "Open Settings and find the current Wi-Fi network name",
    "startFromCurrent": true,
    "maxSteps": 12
  }
}
```

If it returns a waiting state, call `task_reply`. For a timed-out long task, call
`task_status`.

### Reuse a saved Function

Recommended deterministic flow:

1. `omniflow.recall` with the user goal and current page context.
2. Pick a returned Function and fill its arguments.
3. `oob_function_guard_check`.
4. `oob_function_run`.
5. If `fallback_context` is returned, handle the failed step with a live agent or
   VLM, then call `oob_function_run` with `resume_from_step`.

`omniflow.call_tool` is the external compatibility adapter. New direct clients
should prefer the explicit `oob_function_*` tools for Function lifecycle and
execution.

### Create and use a Workbench Project Toolbox

1. Call `oob_project_create`.
2. Call `oob_project_activate`.
3. Call `tools/list` again.
4. Use returned dynamic Project tools exactly by name.
5. Read resources such as `oob://projects/{projectId}/toolbox` and
   `oob://projects/{projectId}/logs/api_calls` for inspection.

## Resources

`resources/list` exposes read-only Workbench resources. The base resources are:

| URI | Meaning |
| --- | --- |
| `oob://projects` | Registered Workbench Project summaries. |
| `oob://projects/active` | Current active Project and Toolbox. |

For each registered Project, these resources are also exposed:

| URI pattern | Meaning |
| --- | --- |
| `oob://projects/{projectId}` | Project manifest. |
| `oob://projects/{projectId}/toolbox` | Project business tools mounted as MCP tools. |
| `oob://projects/{projectId}/progress` | Recent Project progress events. |
| `oob://projects/{projectId}/logs/api_calls` | Recent Project Tool call log rows. |
| `oob://projects/{projectId}/source/manifest` | Imported source asset summary. |

`resources/read` accepts `limit` for tail-style resources. The server bounds it
to `1..200`.

## Prompts

`prompts/list` exposes built-in Workbench instructions. `prompts/get` returns a
standard MCP prompt message; it does not execute anything by itself.

| Prompt | Purpose |
| --- | --- |
| `create_html_project` | Instructions for creating an HTML Workbench Project. |
| `create_markdown_project` | Instructions for creating a Markdown Workbench Project. |
| `create_project_display` | General Project Display creation instructions. |
| `inspect_active_toolbox` | Instructions for inspecting active Project resources and tools. |
| `fix_project_last_error` | Instructions for diagnosing and fixing the active Project's last failed tool call. |

## Python Client

Use the dependency-free client in `omniflow_agentkit/mcp.py`.

```python
from omniflow_agentkit.mcp import OmniFlowMcpClient

client = OmniFlowMcpClient(
    endpoint="http://127.0.0.1:8899/mcp",
    token="TOKEN_FROM_OOB_SETTINGS",
)

tools = client.list_tools()
state = client.get_state(image_quality="medium", max_xml_chars=200000)
result = client.call_tool("vlm_task", {"goal": "Open Settings", "startFromCurrent": True})
```

CLI examples:

```bash
export OOB_MCP_URL=http://127.0.0.1:8899/mcp
export OOB_MCP_TOKEN=<token-from-oob-settings>

python3 -m omniflow_agentkit mcp-get-state --image-quality medium --max-xml-chars 200000
python3 -m omniflow_agentkit mcp-recall "Open Settings and show Wi-Fi"
python3 -m omniflow_agentkit mcp-run-function settings_click_path_demo --args-json '{}'
python3 -m omniflow_agentkit mcp-list-functions
python3 -m omniflow_agentkit mcp-list-runlogs
```

## Security Notes

- Token auth should remain enabled for real device testing.
- `get_state` can return screenshots and Accessibility XML; treat outputs as
  sensitive.
- `file_transfer` download URLs are short-lived and intended for the same LAN.
- Dynamic Project tools can mutate Project state; inspect `tools/list` and
  Project toolbox resources before calling unknown dynamic tools.
- `/mcp/workbench/call` is for authenticated local Dashboard/E2E control-plane
  testing and is intentionally not part of MCP tool discovery.
