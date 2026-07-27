from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import tempfile
import time
from typing import Any, TextIO

from omniflow.artifact import parse_function_artifact
from omniflow.compile import compile_runlog_to_store
from omniflow.config import OmniFlowConfig, RuntimeSettings
from omniflow.function_management import edit_function, enhance_function
from omniflow.gui import (
    ModelToolCallError,
    build_model_turn_request,
    parse_model_turn_response,
)
from omniflow.model import (
    Action,
    ActionResult,
    Function,
    FunctionResolution,
    Observation,
    RunRequest,
)
from omniflow.resolvers.llm_resolver import SYSTEM_PROMPT, parse_resolution
from omniflow.runtime import InputRequired, OmniFlow
from omniflow.trajectory import canonicalize_state

PROTOCOL_VERSION = "omniflow.bridge.v2"
_DEFAULT_GUI_MAX_STEPS = 12
_MODEL_TOOL_CALL_ATTEMPTS = 2

_FUNCTION_CATALOG_ACTIONS = {
    "oob_function_list": "list",
    "oob_function_get": "get",
    "oob_function_register": "put",
    "oob_function_delete": "delete",
    "oob_function_clear": "clear",
}


class JsonLineBridge:
    def __init__(
        self,
        store_path: str | Path,
        *,
        reader: TextIO = sys.stdin,
        writer: TextIO = sys.stdout,
    ):
        self.reader = reader
        self.writer = writer
        self.flow = OmniFlow(store_path)
        self._host_call_index = 0

    def serve_forever(self) -> None:
        for line in self.reader:
            request = self._parse(line)
            if request is not None and self._serve_request(request):
                return

    def serve_once(self) -> None:
        for line in self.reader:
            request = self._parse(line)
            if request is not None:
                self._serve_request(request)
                return

    def _serve_request(self, request: dict[str, Any]) -> bool:
        request_id = str(request.get("id") or "")
        try:
            operation = str(request.get("op") or "")
            if operation == "shutdown":
                self._response(request_id, True, {"stopped": True})
                return True
            result = self._handle(request_id, operation, request.get("payload"))
            self._response(request_id, True, result)
        except Exception as error:  # noqa: BLE001
            self._response(
                request_id,
                False,
                error={
                    "code": str(error) or type(error).__name__,
                    "type": type(error).__name__,
                },
            )
        return False

    def _handle(self, request_id: str, operation: str, payload: Any) -> Any:
        body = payload if isinstance(payload, dict) else {}
        if operation == "health":
            self.flow.store.reload()
            return {
                "protocol_version": PROTOCOL_VERSION,
                "functions": len(self.flow.store.functions),
                "invalid_functions": dict(self.flow.store.load_errors),
                "store_path": str(self.flow.store.path),
            }
        if operation == "function_tool":
            return self._function_tool(request_id, body)
        if operation == "run":
            function_id = str(body.get("function_id") or "").strip()
            goal = str(body.get("goal") or "").strip()
            if not function_id and not goal:
                return _run_error(
                    body,
                    code="OOB_RUN_REQUEST_INVALID",
                    message="run_goal_or_function_id_required",
                )
            model = str(body.get("model") or "").strip()
            if goal and not model:
                return _run_error(
                    body,
                    code="OOB_GUI_MODEL_REQUIRED",
                    message="model_required",
                )
            max_steps = max(
                1,
                min(
                    int(body.get("max_steps") or _DEFAULT_GUI_MAX_STEPS),
                    64,
                ),
            )
            host = _BridgeHost(
                self,
                request_id,
                defer_user_input=body.get("defer_user_input") is True,
            )
            installed_apps = host.installed_apps() if goal else {}
            planner = (
                _BridgePlanner(
                    self,
                    request_id,
                    host,
                    model=model,
                    target_package_name=str(body.get("target_package_name") or ""),
                    step_skill_guidance=str(body.get("step_skill_guidance") or ""),
                    installed_apps=installed_apps,
                    max_steps=max_steps,
                )
                if goal
                else None
            )
            resolver = None
            if goal and body.get("disable_function_recall") is not True:
                resolver = _BridgeResolver(
                    self,
                    request_id,
                    model=str(body.get("resolver_model") or model).strip(),
                )
            flow = OmniFlow(
                self.flow.store.path,
                host=host,
                planner=planner,
                resolver=resolver,
                config=OmniFlowConfig(runtime=RuntimeSettings(max_steps=max_steps)),
            )
            function = flow.store.get_function(function_id) if function_id else None
            result = flow.run(
                RunRequest(
                    goal=goal,
                    function_id=function_id or None,
                    arguments=dict(body.get("arguments") or {}),
                )
            )
            return _run_result(result, body=body, function=function)
        raise ValueError(f"unsupported_operation:{operation}")

    def _function_tool(
        self,
        request_id: str,
        body: dict[str, Any],
    ) -> dict[str, Any]:
        _require_contract(body, {"tool"}, {"tool", "args"})
        tool = str(body.get("tool") or "").strip()
        args = body.get("args")
        if not tool:
            return _management_error(
                "TOOL_NAME_EMPTY",
                "Missing Function management tool name",
            )
        if args is None:
            args = {}
        if not isinstance(args, dict):
            return _management_error(
                "FUNCTION_TOOL_ARGS_INVALID",
                "Function management tool args must be an object",
            )

        catalog_action = _FUNCTION_CATALOG_ACTIONS.get(tool)
        if catalog_action is not None:
            result = self._catalog({**args, "action": catalog_action})
            if tool == "oob_function_get" and result.get("success") is True:
                function = result.get("function")
                return dict(function) if isinstance(function, dict) else {}
            return result
        if tool == "update_function":
            return self._update_function(request_id, args)
        if tool == "function.recall":
            return self._recall(request_id, args)
        if tool == "oob_run_log_convert":
            return self._compile(request_id, args)
        if tool == "oob_run_log_list":
            response = self.host_call(
                request_id,
                "list_run_logs",
                {
                    "limit": max(1, min(_int_arg(args.get("limit"), 50), 200)),
                    "offset": max(0, _int_arg(args.get("offset"), 0)),
                    "source": str(args.get("source") or "").strip(),
                    "status": str(args.get("status") or "").strip(),
                    "model": str(args.get("model") or "").strip(),
                    "query": str(args.get("query") or "").strip(),
                },
            )
            if isinstance(response, dict):
                return response
            return _management_error(
                "RUN_LOG_LIST_INVALID",
                "RunLog list response must be an object",
            )
        if tool == "oob_run_log_get":
            run_id = str(args.get("run_id") or "").strip()
            if not run_id:
                return _management_error("RUN_LOG_ID_EMPTY", "run_id is required")
            response = self.host_call(
                request_id,
                "get_run_log",
                {"run_id": run_id},
            )
            if isinstance(response, dict):
                return response
            return _management_error(
                "RUN_LOG_NOT_FOUND",
                f"RunLog not found: {run_id}",
                run_id=run_id,
            )
        return _management_error(
            "UNKNOWN_FUNCTION_MANAGEMENT_TOOL",
            f"Unknown Function management tool: {tool}",
        )

    def _catalog(self, body: dict[str, Any]) -> dict[str, Any]:
        self.flow.store.reload()
        action = str(body.get("action") or "list")
        if action == "list":
            include_hidden = body.get("include_hidden") is True
            limit = max(1, min(int(body.get("limit") or 100), 500))
            offset = max(0, int(body.get("offset") or 0))
            functions = self.flow.store.list_functions(
                offset=offset,
                limit=limit,
                include_hidden=include_hidden,
            )
            total = sum(
                1
                for item in self.flow.store.functions.values()
                if include_hidden or item.agent_visible
            )
            return {
                "success": True,
                "functions": [item.to_dict() for item in functions],
                "count": len(functions),
                "total": total,
                "limit": limit,
                "offset": offset,
                "next_offset": offset + len(functions),
                "has_more": offset + len(functions) < total,
                "include_hidden": include_hidden,
            }
        if action == "get":
            function_id = str(body.get("function_id") or "").strip()
            if not function_id:
                return _management_error("FUNCTION_ID_EMPTY", "function_id is required")
            function = self.flow.store.get_function(function_id)
            if function is None:
                return _management_error(
                    "OOB_FUNCTION_NOT_FOUND",
                    f"Function not found: {function_id}",
                    function_id=function_id,
                )
            return {
                "success": True,
                "function_id": function_id,
                "function": function.to_dict(),
                "source": "omniflow_python",
            }
        if action == "put":
            value = dict(body.get("function") or {})
            function_id = str(value.get("function_id") or "").strip()
            expected_value = body.get("expected_function")
            if expected_value is not None:
                try:
                    expected = parse_function_artifact(dict(expected_value)).to_dict()
                except (TypeError, ValueError) as error:
                    return _management_error("FUNCTION_SCHEMA_INVALID", str(error))
                current = self.flow.store.get_function(function_id)
                if (
                    expected.get("function_id") != function_id
                    or current is None
                    or current.to_dict() != expected
                ):
                    return _management_error(
                        "FUNCTION_ENHANCEMENT_CONFLICT",
                        "Function changed before offline enhancement could be saved",
                        function_id=function_id,
                    )
            already_exists = bool(
                function_id and self.flow.store.get_function(function_id) is not None
            )
            try:
                function = self.flow.store.put_function(value)
            except ValueError as error:
                return _management_error("FUNCTION_SCHEMA_INVALID", str(error))
            saved = function.to_dict()
            return {
                "success": True,
                "function": saved,
                "function_id": function.function_id,
                "imported": True,
                "already_exists": already_exists,
                "agent_visible": saved["agent_visible"],
                "source": "omniflow_python",
            }
        if action == "delete":
            function_id = str(body.get("function_id") or "").strip()
            deleted = self.flow.store.delete_function(function_id)
            return {
                "success": deleted,
                "function_id": function_id,
                "deleted": deleted,
                "source": "omniflow_python",
            }
        if action == "clear":
            if body.get("confirm") is not True:
                return _management_error(
                    "OOB_FUNCTION_CLEAR_CONFIRMATION_REQUIRED",
                    "Set confirm=true to clear all registered Functions",
                )
            return {
                "success": True,
                "deleted": True,
                "deleted_count": self.flow.store.clear_functions(),
                "source": "omniflow_python",
            }
        raise ValueError(f"unsupported_catalog_action:{action}")

    def _compile(self, request_id: str, body: dict[str, Any]) -> dict[str, Any]:
        _require_contract(
            body,
            {"run_id"},
            {
                "run_id",
                "register",
                "agent_visible",
                "function_id",
                "name",
                "description",
                "enhance",
            },
        )
        run_id = str(body.get("run_id") or "").strip()
        if not run_id:
            return _management_error("RUN_LOG_ID_EMPTY", "run_id is required")
        run_log = self.host_call(request_id, "get_run_log", {"run_id": run_id})
        if not isinstance(run_log, dict):
            return _management_error(
                "RUN_LOG_NOT_FOUND",
                f"RunLog not found: {run_id}",
                run_id=run_id,
            )
        if run_log.get("error_code"):
            return _management_error(
                str(run_log["error_code"]),
                str(run_log.get("error_message") or f"RunLog not found: {run_id}"),
                run_id=run_id,
            )

        try:
            with tempfile.TemporaryDirectory(prefix="omniflow-compile-") as output_root:
                report = compile_runlog_to_store(run_log, output_root)
                compiled = OmniFlow(Path(output_root) / "store.json")
                function_id = next(iter(report["function_ids"]), "")
                function = compiled.store.get_function(function_id)
        except ValueError as error:
            return _compile_error(run_id, error)
        if function is None:
            return _management_error(
                "RUN_LOG_NO_REPLAYABLE_STEPS",
                "RunLog has no replayable steps",
                run_id=run_id,
            )

        value = function.to_dict()
        for field in ("function_id", "name", "description"):
            replacement = str(body.get(field) or "").strip()
            if replacement:
                value[field] = replacement
        value["agent_visible"] = body.get("agent_visible") is True
        try:
            value = parse_function_artifact(value).to_dict()
        except ValueError as error:
            return _management_error(
                "FUNCTION_SCHEMA_INVALID",
                str(error),
                run_id=run_id,
            )

        register = body.get("register") is True
        should_enhance = body.get("enhance") is True
        changes: list[dict[str, Any]] = []
        enhancement_status = "none"
        enhancement_message = ""
        if should_enhance and not register:
            try:
                value, changes, enhancement_status = enhance_function(
                    value,
                    run_log,
                    lambda prompt: self._complete_json(request_id, prompt),
                )
            except Exception as error:
                enhancement_status = "failed"
                enhancement_message = str(error)
        function_id = value["function_id"]
        self.flow.store.reload()
        already_exists = self.flow.store.get_function(function_id) is not None
        if register:
            state_error = self._validate_function_source_states(request_id, value)
            if state_error:
                return _management_error(
                    "FUNCTION_SOURCE_STATE_NOT_FOUND",
                    state_error,
                    function_id=function_id,
                    run_id=run_id,
                )
            self.flow.store.put_function(value)
            if should_enhance:
                try:
                    scheduled = self.host_call(
                        request_id,
                        "schedule_operation",
                        {
                            "operation": "function_tool",
                            "payload": {
                                "tool": "update_function",
                                "args": {
                                    "function_id": function_id,
                                    "mode": "enhance",
                                    "run_id": run_id,
                                },
                            },
                        },
                    )
                    if not isinstance(scheduled, dict) or scheduled.get("accepted") is not True:
                        raise RuntimeError("background_operation_not_accepted")
                    enhancement_status = "enhancing"
                    enhancement_message = (
                        "Base Function registered; offline enhancement is running."
                    )
                except Exception as error:  # noqa: BLE001
                    enhancement_status = "failed"
                    enhancement_message = str(error) or type(error).__name__
        status = "converted"
        if register:
            status = "updated" if already_exists else "created"
        step_count = len(run_log.get("steps") or ())
        return {
            "success": True,
            "accepted": True,
            "status": status,
            "run_id": run_id,
            "function_id": function_id,
            "function": value,
            "registered": register,
            "already_exists": already_exists,
            "step_count": step_count,
            "successful_step_count": sum(
                1
                for step in run_log.get("steps") or ()
                if isinstance(step, dict)
                and isinstance(step.get("result"), dict)
                and step["result"].get("success") is True
            ),
            "compiled_step_count": len(value["steps"]),
            "enhancement_status": enhancement_status,
            "changes": changes,
            "message": enhancement_message,
            "error": None,
            "source": "omniflow_python",
        }

    def _validate_function_source_states(
        self,
        request_id: str,
        function: dict[str, Any],
    ) -> str:
        state_ids = dict.fromkeys(
            str(item.get("source_state_id") or "").strip()
            for collection in (function.get("steps"), function.get("checker_rules"))
            for item in (collection if isinstance(collection, list) else ())
            if isinstance(item, dict)
        )
        for state_id in state_ids:
            if not state_id:
                continue
            try:
                state = self.host_call(request_id, "get_state", {"state_id": state_id})
            except (EOFError, RuntimeError, ValueError) as error:
                return str(error) or f"state_not_found:{state_id}"
            if not isinstance(state, dict) or str(state.get("state_id") or "") != state_id:
                return f"state_not_found:{state_id}"
        return ""

    def _update_function(
        self,
        request_id: str,
        body: dict[str, Any],
    ) -> dict[str, Any]:
        _require_contract(
            body,
            {"function_id"},
            {"function_id", "mode", "patch", "dry_run", "run_id"},
        )
        function_id = str(body.get("function_id") or "").strip()
        if not function_id:
            return _management_error("FUNCTION_ID_EMPTY", "function_id is required")
        self.flow.store.reload()
        original = self.flow.store.get_function(function_id)
        if original is None:
            return _management_error(
                "OOB_FUNCTION_NOT_FOUND",
                f"Function not found: {function_id}",
                function_id=function_id,
            )

        patch = body.get("patch")
        if patch is not None and not isinstance(patch, dict):
            return _management_error(
                "FUNCTION_PATCH_INVALID",
                "patch must be an object",
                function_id=function_id,
            )
        mode = str(body.get("mode") or "").strip().lower()
        if isinstance(patch, dict) or mode == "edit":
            edits = (patch or {}).get("action_edits", [])
            if not isinstance(edits, list):
                return _management_error(
                    "FUNCTION_PATCH_INVALID",
                    "patch.action_edits must be an array",
                    function_id=function_id,
                )
            updated, changes = edit_function(original.to_dict(), edits)
            dry_run = body.get("dry_run") is True
            if changes and not dry_run:
                self.flow.store.put_function(updated)
            return {
                "success": True,
                "function_id": function_id,
                "found": True,
                "function": original.to_dict(),
                "updated_function": updated,
                "changed": bool(changes),
                "saved": bool(changes) and not dry_run,
                "dry_run": dry_run,
                "changes": changes,
                "message": (
                    "No applicable action edits."
                    if not changes
                    else "Function update preview generated."
                    if dry_run
                    else "Function updated."
                ),
                "source": "omniflow_python",
            }
        if mode != "enhance":
            return _management_error(
                "FUNCTION_UPDATE_MODE_REQUIRED",
                "mode must be edit or enhance",
                function_id=function_id,
            )
        run_log: dict[str, Any] = {}
        run_id = str(body.get("run_id") or "").strip()
        if run_id:
            self._write_enhancement_diagnostics(
                request_id,
                run_id,
                {
                    "status": "enhancing",
                    "function_id": function_id,
                    "message": "Base Function registered; offline enhancement is running.",
                    "changes": [],
                },
            )
        if run_id:
            loaded = self.host_call(request_id, "get_run_log", {"run_id": run_id})
            if isinstance(loaded, dict):
                run_log = loaded
        try:
            result = self._enhance(
                request_id,
                {
                    "function_id": function_id,
                    "run_log": run_log,
                    "dry_run": body.get("dry_run") is True,
                },
            )
        except Exception as error:  # noqa: BLE001
            original_value = original.to_dict()
            message = str(error) or type(error).__name__
            result = {
                "success": False,
                "function_id": function_id,
                "found": True,
                "function": original_value,
                "updated_function": original_value,
                "changed": False,
                "saved": False,
                "dry_run": body.get("dry_run") is True,
                "changes": [],
                "enhancement_status": "failed",
                "message": message,
                "error_code": "OOB_FUNCTION_ENHANCEMENT_FAILED",
                "error_message": message,
                "source": "omniflow_python",
            }
        if run_id:
            self._write_enhancement_diagnostics(
                request_id,
                run_id,
                _enhancement_diagnostics(function_id, result),
            )
        return result

    def _write_enhancement_diagnostics(
        self,
        request_id: str,
        run_id: str,
        diagnostics: dict[str, Any],
    ) -> None:
        try:
            self.host_call(
                request_id,
                "update_run_log_diagnostics",
                {
                    "run_id": run_id,
                    "diagnostics": {"function_enhancement": diagnostics},
                },
            )
        except (EOFError, RuntimeError, ValueError):
            pass

    def _enhance(self, request_id: str, body: dict[str, Any]) -> dict[str, Any]:
        function_id = str(body.get("function_id") or "").strip()
        self.flow.store.reload()
        function = self.flow.store.get_function(function_id)
        if function is None:
            return _management_error(
                "OOB_FUNCTION_NOT_FOUND",
                f"Function not found: {function_id}",
                function_id=function_id,
            ) | {"found": False}
        run_log = body.get("run_log")
        if not isinstance(run_log, dict):
            run_log = {}

        def complete_json(prompt: str) -> str:
            return self._complete_json(request_id, prompt)

        updated, changes, status = enhance_function(
            function.to_dict(),
            run_log,
            complete_json,
        )
        dry_run = body.get("dry_run") is True
        if not dry_run:
            self.flow.store.reload()
            current = self.flow.store.get_function(function_id)
            if current is None or current.to_dict() != function.to_dict():
                return _management_error(
                    "FUNCTION_ENHANCEMENT_CONFLICT",
                    "Function changed before offline enhancement could be saved",
                    function_id=function_id,
                ) | {
                    "found": current is not None,
                    "function": function.to_dict(),
                    "updated_function": updated,
                    "changed": bool(changes),
                    "saved": False,
                    "dry_run": False,
                    "changes": changes,
                    "enhancement_status": "conflict",
                }
            self.flow.store.put_function(updated)
        return {
            "success": True,
            "function_id": function_id,
            "found": True,
            "function": function.to_dict(),
            "updated_function": updated,
            "changed": bool(changes),
            "saved": not dry_run,
            "dry_run": dry_run,
            "changes": changes,
            "enhancement_status": status,
            "message": "Function enhancement completed.",
            "source": "omniflow_python",
        }

    def _complete_json(self, request_id: str, prompt: str) -> str:
        response = self.host_call(
            request_id,
            "complete_json",
            {
                "model": "scene.dispatch.model",
                "prompt": prompt,
                "max_tokens": 1800,
                "temperature": 0.1,
            },
        )
        if not isinstance(response, dict):
            raise ValueError("complete_json_response_invalid")
        return str(response.get("content") or "")

    def _recall(self, request_id: str, body: dict[str, Any]) -> dict[str, Any]:
        started_at = time.monotonic()
        _require_contract(
            body,
            {"goal"},
            {"goal", "state", "limit"},
        )
        state = body.get("state")
        if state is None:
            state = self.host_call(
                request_id,
                "observe",
                {"screenshot": False},
            )
        if not isinstance(state, dict):
            raise ValueError("state_must_be_object")
        _state_observation(state)
        self.flow.store.reload()
        limit = max(1, min(int(body.get("limit") or 8), 50))
        goal_tokens = _tokens(str(body.get("goal") or ""))
        scored: list[tuple[float, Function]] = []
        for function in self.flow.store.functions.values():
            if not function.agent_visible:
                continue
            score = max(
                _jaccard(goal_tokens, _tokens(function.name)),
                _jaccard(goal_tokens, _tokens(function.description)),
            )
            if score > 0:
                scored.append((score, function))
        ranked = sorted(scored, key=lambda item: (-item[0], item[1].function_id))
        candidates = [
                _recalled_tool(function, str(body.get("goal") or ""), rank)
                | {
                    "retrieval": {
                        "score": score,
                        "source": "goal_token_jaccard",
                        "rank": rank,
                    }
                }
                for rank, (score, function) in enumerate(ranked, start=1)
            ][:limit]
        return {
            "success": True,
            "retrieval_state": "has_candidates" if candidates else "miss",
            "candidates": candidates,
            "count": len(candidates),
            "reason": "omniflow_python_match" if candidates else "python_recall_miss",
            "runtime_source": "omniflow_python",
            "duration_ms": max(0, int((time.monotonic() - started_at) * 1000)),
        }

    def host_call(self, request_id: str, method: str, payload: dict[str, Any]) -> Any:
        self._host_call_index += 1
        call_id = f"{request_id}:{self._host_call_index}"
        self._write(
            {
                "id": request_id,
                "event": "host_call",
                "call_id": call_id,
                "method": method,
                "payload": payload,
            }
        )
        for line in self.reader:
            response = self._parse(line)
            if response is None:
                continue
            if response.get("op") == "cancel" and str(response.get("id") or "") == request_id:
                raise RuntimeError("cancelled")
            if str(response.get("call_id") or "") != call_id:
                raise RuntimeError("host_response_out_of_order")
            if response.get("ok") is not True:
                error = response.get("error")
                raise RuntimeError(
                    str(error.get("code") if isinstance(error, dict) else error or "host_call_failed")
                )
            return response.get("result")
        raise EOFError("host_response_missing")

    def _response(
        self,
        request_id: str,
        ok: bool,
        result: Any = None,
        *,
        error: Any = None,
    ) -> None:
        self._write(
            {
                "id": request_id,
                "ok": ok,
                "result": result if ok else None,
                "error": error if not ok else None,
            }
        )

    def _write(self, value: dict[str, Any]) -> None:
        self.writer.write(json.dumps(value, ensure_ascii=False, separators=(",", ":")))
        self.writer.write("\n")
        self.writer.flush()

    @staticmethod
    def _parse(line: str) -> dict[str, Any] | None:
        stripped = line.strip()
        if not stripped:
            return None
        value = json.loads(stripped)
        if not isinstance(value, dict):
            raise ValueError("bridge_message_must_be_object")
        return value


class _BridgeHost:
    def __init__(
        self,
        bridge: JsonLineBridge,
        request_id: str,
        *,
        defer_user_input: bool = False,
    ):
        self.bridge = bridge
        self.request_id = request_id
        self.defer_user_input = bool(defer_user_input)
        self.current_observation: Observation | None = None
        self.current_action_metadata: dict[str, Any] = {}

    def observe(self, **kwargs: Any) -> Observation:
        self.current_observation = _state_observation(
            self.bridge.host_call(self.request_id, "observe", kwargs)
        )
        return self.current_observation

    def installed_apps(self) -> dict[str, str]:
        response = self.bridge.host_call(
            self.request_id,
            "installed_apps",
            {},
        )
        if not isinstance(response, dict) or not isinstance(response.get("apps"), dict):
            raise ValueError("installed_apps_response_invalid")
        return {
            str(label): str(package)
            for label, package in response["apps"].items()
            if str(label).strip() and str(package).strip()
        }

    def act(self, action: Action) -> ActionResult:
        if self.current_observation is None:
            raise RuntimeError("host_action_state_required")
        return ActionResult.from_value(
            self.bridge.host_call(
                self.request_id,
                "act",
                {
                    "action": action.to_dict(),
                    "metadata": dict(self.current_action_metadata),
                    "state": _state_from_observation(
                        self.current_observation,
                        include_xml=True,
                    ),
                },
            )
        )

    def request_input(self, question: str) -> str:
        if self.defer_user_input:
            raise InputRequired(question)
        response = self.bridge.host_call(
            self.request_id,
            "request_input",
            {"question": str(question)},
        )
        if not isinstance(response, dict):
            raise ValueError("request_input_response_invalid")
        return str(response.get("value") or "")

    def get_state(self, source_state_id: str) -> Observation:
        return _state_observation(
            self.bridge.host_call(self.request_id, "get_state", {"state_id": source_state_id})
        )

    def record_step(self, fact: dict[str, Any]) -> dict[str, Any]:
        response = self.bridge.host_call(
            self.request_id,
            "record_step",
            {"fact": fact},
        )
        if not isinstance(response, dict):
            raise ValueError("record_step_response_invalid")
        return response


class _BridgePlanner:
    def __init__(
        self,
        bridge: JsonLineBridge,
        request_id: str,
        host: _BridgeHost,
        *,
        model: str,
        target_package_name: str,
        step_skill_guidance: str,
        installed_apps: dict[str, str],
        max_steps: int,
    ):
        self.bridge = bridge
        self.request_id = request_id
        self.host = host
        self.model = str(model).strip()
        self.target_package_name = str(target_package_name).strip()
        self.step_skill_guidance = str(step_skill_guidance)
        self.installed_apps = {
            str(label): str(package)
            for label, package in installed_apps.items()
            if str(label).strip() and str(package).strip()
        }
        self.max_steps = int(max_steps)
        self._metadata: dict[str, Any] = {}
        self._rejected_tool_calls: list[dict[str, Any]] = []
        self._turn_index = 0
        self._local_app_bootstrap_attempted = False

    def one_step_action(self, goal: str, observation: Observation) -> Action:
        bootstrap = self._local_app_bootstrap(str(goal), observation)
        if bootstrap is not None:
            package_name, label = bootstrap
            self._metadata = {
                "summary": f"打开{label}",
                "execution": "local_app_bootstrap",
            }
            self.host.current_action_metadata = dict(self._metadata)
            return _action(
                {
                    "tool": "open_app",
                    "args": {"package_name": package_name},
                }
            )
        state = _planner_state(observation)
        validation_error = ""
        retry_tool_name = ""
        rejected_tool_call: dict[str, Any] | None = None
        lightweight_retry = False
        self._rejected_tool_calls.clear()
        for attempt in range(_MODEL_TOOL_CALL_ATTEMPTS):
            self._turn_index += 1
            request = build_model_turn_request(
                goal=str(goal),
                model=self.model,
                state=state,
                target_package_name=self.target_package_name,
                step_skill_guidance=self.step_skill_guidance,
                installed_apps=self.installed_apps,
                max_steps=self.max_steps,
                turn_index=self._turn_index,
                validation_error=validation_error,
                retry_tool_name=retry_tool_name,
                rejected_tool_call=rejected_tool_call,
                lightweight_retry=lightweight_retry,
            )
            response = self.bridge.host_call(
                self.request_id,
                "model_turn",
                {
                    "goal": str(goal),
                    "model": self.model,
                    "state": state,
                    "target_package_name": self.target_package_name,
                    "step_skill_guidance": self.step_skill_guidance,
                    "max_steps": self.max_steps,
                    "request": request,
                },
            )
            try:
                action, metadata = parse_model_turn_response(
                    response,
                    requested_model=self.model,
                    turn_index=self._turn_index,
                    display=(
                        state.get("display")
                        if isinstance(state.get("display"), dict)
                        else None
                    ),
                )
                break
            except ModelToolCallError as error:
                rejected_entry = {
                    "turn_index": self._turn_index,
                    "tool": error.tool_name or None,
                    "error": str(error),
                }
                if error.arguments is not None:
                    rejected_entry["arguments"] = error.arguments
                self._rejected_tool_calls.append(rejected_entry)
                if attempt == _MODEL_TOOL_CALL_ATTEMPTS - 1:
                    self._metadata = {
                        "rejected_tool_calls": list(self._rejected_tool_calls)
                    }
                    raise
                validation_error = str(error)
                retry_tool_name = error.tool_name
                rejected_tool_call = {
                    "tool": error.tool_name or None,
                    "arguments": error.arguments,
                }
                lightweight_retry = error.code.endswith(
                    "expected_one_native_tool_call:got_0"
                )
        if self._rejected_tool_calls:
            metadata["rejected_tool_calls"] = list(self._rejected_tool_calls)
        self._metadata = metadata
        self.host.current_action_metadata = dict(self._metadata)
        return _action(action)

    def _local_app_bootstrap(
        self,
        goal: str,
        observation: Observation,
    ) -> tuple[str, str] | None:
        if self._local_app_bootstrap_attempted:
            return None
        self._local_app_bootstrap_attempted = True
        current_package = str(observation.package_name or "").strip()
        target_package = self.target_package_name
        if target_package and target_package != current_package:
            label = next(
                (
                    label
                    for label, package in self.installed_apps.items()
                    if package == target_package
                ),
                target_package,
            )
            return target_package, label
        normalized_goal = _compact_match_text(goal)
        candidates = sorted(
            (
                (label, package)
                for label, package in self.installed_apps.items()
                if _compact_match_text(label)
                and _compact_match_text(label) in normalized_goal
                and package != current_package
            ),
            key=lambda item: len(_compact_match_text(item[0])),
            reverse=True,
        )
        if not candidates:
            return None
        label, package = candidates[0]
        return package, label

    def take_metadata(self) -> dict[str, Any]:
        metadata = dict(self._metadata)
        self._metadata.clear()
        return metadata


def _compact_match_text(value: str) -> str:
    return re.sub(r"[^0-9a-z\u4e00-\u9fff]+", "", str(value).casefold())


class _BridgeResolver:
    def __init__(self, bridge: JsonLineBridge, request_id: str, *, model: str):
        self.bridge = bridge
        self.request_id = request_id
        self.model = str(model).strip()
        if not self.model:
            raise ValueError("resolver_model_required")

    def resolve(self, goal: str, functions: list[Function]) -> FunctionResolution:
        candidates = [
            {
                "function_id": function.id,
                "description": function.description,
                "input_schema": function.input_schema,
            }
            for function in functions
            if function.actions
        ]
        if not candidates:
            return FunctionResolution(model_calls=0, detail={"reason": "no_candidates"})
        prompt = SYSTEM_PROMPT + "\n\n" + json.dumps(
            {"goal": str(goal), "functions": candidates},
            ensure_ascii=False,
        )
        response = self.bridge.host_call(
            self.request_id,
            "complete_json",
            {
                "model": self.model,
                "prompt": prompt,
                "max_tokens": 1024,
                "temperature": 0,
            },
        )
        if not isinstance(response, dict):
            raise ValueError("complete_json_response_invalid")
        function_id, arguments = parse_resolution(response.get("content"), functions)
        return FunctionResolution(
            function_id=function_id,
            arguments=arguments,
            model_calls=1,
            detail={"model": self.model, "candidate_count": len(candidates)},
        )


def _planner_state(observation: Observation) -> dict[str, Any]:
    state = observation.to_dict()
    state["state_id"] = str(observation.extra.get("state_id") or "").strip()
    for key in ("display", "screenshot_path"):
        if observation.extra.get(key) is not None:
            state[key] = observation.extra[key]
    state["extra"] = {
        key: value
        for key, value in observation.extra.items()
        if key not in {"state_id", "display", "screenshot_path"}
    }
    return {key: value for key, value in state.items() if value is not None}


def _state_observation(value: Any) -> Observation:
    if not isinstance(value, dict):
        raise ValueError("state_must_be_object")
    allowed = {
        "state_id",
        "xml",
        "package_name",
        "activity_name",
        "display",
        "screenshot_path",
        "image_base64",
        "extra",
    }
    unknown = sorted(set(value) - allowed)
    if unknown:
        raise ValueError(f"state_unknown_fields:{','.join(unknown)}")
    state_id = str(value.get("state_id") or "").strip()
    if not state_id:
        raise ValueError("state_id_required")
    return Observation.from_value({**value, "state_id": state_id})


def _action(value: Any) -> Action:
    if not isinstance(value, dict) or set(value) != {"tool", "args"}:
        raise ValueError("action_contract_invalid")
    tool = value.get("tool")
    args = value.get("args")
    if not isinstance(tool, str) or not tool.strip():
        raise ValueError("action_tool_required")
    if not isinstance(args, dict):
        raise ValueError("action_args_must_be_object")
    return Action(tool.strip(), dict(args))


def _tokens(value: str) -> set[str]:
    normalized = value.lower()
    tokens = set(re.findall(r"[a-z0-9_]+", normalized))
    chinese = "".join(re.findall(r"[\u4e00-\u9fff]+", normalized))
    if chinese:
        tokens.add(chinese)
    return tokens


def _jaccard(left: set[str], right: set[str]) -> float:
    if not left or not right:
        return 0.0
    return len(left & right) / len(left | right)


def _recalled_tool(function: Function, goal: str, rank: int) -> dict[str, Any]:
    schema = dict(function.input_schema or {})
    properties = dict(schema.get("properties") or {})
    properties["summary"] = {
        "type": "string",
        "description": (
            "Why this saved workflow is the best next step, "
            "in at most 20 Chinese characters or one short sentence."
        ),
    }
    required = ["summary"] + [
        str(name)
        for name in schema.get("required") or ()
        if str(name) and str(name) != "summary"
    ]
    argument_names = [str(name) for name in properties if str(name) != "summary"]
    description = f"Saved workflow {function.function_id}"
    if function.name:
        description += f": {function.name}"
    if function.description:
        description += f". {function.description}"
    normalized_goal = re.sub(r"\s+", " ", goal).strip()[:160]
    if normalized_goal:
        description += f". Current goal: {normalized_goal}"
    if argument_names:
        description += f". Arguments: {', '.join(argument_names[:8])}"
    description += ". Call only when it clearly advances the current goal."
    return {
        "function_id": function.function_id,
        "tool": {
            "type": "function",
            "function": {
                "name": f"recalled_function_{rank}",
                "description": description[:1200],
                "parameters": {
                    "type": "object",
                    "properties": properties,
                    "required": required,
                    "additionalProperties": False,
                },
            },
        },
    }


def _require_contract(
    value: dict[str, Any],
    required: set[str],
    allowed: set[str],
) -> None:
    missing = sorted(required - set(value))
    if missing:
        raise ValueError(f"request_missing_fields:{','.join(missing)}")
    unknown = sorted(set(value) - allowed)
    if unknown:
        raise ValueError(f"request_unknown_fields:{','.join(unknown)}")


def _int_arg(value: Any, default: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _compile_error(run_id: str, error: ValueError) -> dict[str, Any]:
    message = str(error)
    code = {
        "successful_source_actions_required": "RUN_LOG_NO_REPLAYABLE_STEPS",
        "semantic_functions_required": "RUN_LOG_NO_REPLAYABLE_STEPS",
        "default_bundle_actions_required": "RUN_LOG_NO_REPLAYABLE_STEPS",
        "successful_source_goal_required": "RUN_LOG_GOAL_EMPTY",
    }.get(message, "RUN_LOG_COMPILE_FAILED")
    user_message = {
        "RUN_LOG_NO_REPLAYABLE_STEPS": "RunLog has no replayable steps",
        "RUN_LOG_GOAL_EMPTY": "RunLog goal is required",
    }.get(code, message)
    return _management_error(code, user_message, run_id=run_id)


def _management_error(
    code: str,
    message: str,
    *,
    function_id: str = "",
    run_id: str = "",
) -> dict[str, Any]:
    return {
        "success": False,
        "accepted": False,
        "status": "rejected",
        "registered": False,
        "function_id": function_id,
        "run_id": run_id,
        "function": None,
        "error": message,
        "error_code": code,
        "error_message": message,
        "source": "omniflow_python",
    }


def _enhancement_diagnostics(
    function_id: str,
    result: dict[str, Any],
) -> dict[str, Any]:
    status = str(result.get("enhancement_status") or "").strip()
    if result.get("success") is not True:
        status = "failed"
    elif not status:
        status = "enhanced" if result.get("changed") is True else "unchanged"
    diagnostics: dict[str, Any] = {
        "status": status,
        "function_id": function_id,
        "message": str(result.get("message") or result.get("error_message") or ""),
        "changes": list(result.get("changes") or ()),
    }
    error_code = str(result.get("error_code") or "").strip()
    if error_code:
        diagnostics["error_code"] = error_code
    return diagnostics


def _run_result(
    result,
    *,
    body: dict[str, Any],
    function: Function | None,
) -> dict[str, Any]:
    finished_at_ms = int(time.time() * 1000)
    started_at_ms = int(body.get("started_at_ms") or finished_at_ms)
    trace = [
        step
        for step in (result.detail.get("trace") or [])
        if isinstance(step, dict)
    ]
    successful_steps = sum(
        1
        for step in trace
        if isinstance(step.get("result"), dict)
        and step["result"].get("success") is True
    )
    failed_step_index = next(
        (
            index
            for index, step in enumerate(trace)
            if not isinstance(step.get("result"), dict)
            or step["result"].get("success") is not True
        ),
        None,
    )
    current_step_index = (
        failed_step_index
        if failed_step_index is not None
        else len(trace) - 1 if trace else None
    )
    error = str(result.error or "")
    error_code = None
    if not result.success:
        error_code = (
            "OOB_FUNCTION_ARGUMENTS_MISSING"
            if error.startswith("function_arguments_invalid:missing:")
            else "OOB_FUNCTION_RUN_FAILED"
        )
    payload: dict[str, Any] = {
        "success": result.success,
        "status": "succeeded" if result.success else "failed",
        "run_id": str(body.get("run_id") or ""),
        "function_id": str(result.function_id or body.get("function_id") or ""),
        "name": function.name if function else "",
        "description": function.description if function else "",
        "source": "oob_function_replay" if function else "vlm",
        "runner": "omniflow_python",
        "execution_mode": str(body.get("execution_mode") or "foreground"),
        "step_count": len(function.steps) if function else 0,
        "success_step_count": successful_steps,
        "completed_step_count": len(trace),
        "actions_executed": int(result.actions_executed),
        "steps": trace,
        "failed_step_index": failed_step_index,
        "current_step_index": current_step_index,
        "current_step_number": (
            current_step_index + 1 if current_step_index is not None else None
        ),
        "started_at_ms": started_at_ms,
        "finished_at_ms": finished_at_ms,
        "duration_ms": max(0, finished_at_ms - started_at_ms),
        "error_code": error_code,
        "error_message": error or None,
        "done_reason": result.detail.get("done_reason")
        or ("finished" if result.success else "error"),
        "finished_content": result.detail.get("finished_content"),
        "model": str(body.get("model") or "") or None,
        "model_calls": int(result.model_calls),
        "fallback_steps": int(result.fallback_steps),
        "planner_diagnostics": result.detail.get("planner_diagnostics") or None,
        "missing_required_arguments": (
            [
                value
                for value in error.removeprefix(
                    "function_arguments_invalid:missing:"
                ).split(",")
                if value
            ]
            if error.startswith("function_arguments_invalid:missing:")
            else None
        ),
        "final_state": _state_from_observation(result.final_state),
    }
    return {key: value for key, value in payload.items() if value is not None}


def _run_error(
    body: dict[str, Any],
    *,
    code: str,
    message: str,
) -> dict[str, Any]:
    finished_at_ms = int(time.time() * 1000)
    started_at_ms = int(body.get("started_at_ms") or finished_at_ms)
    function_id = str(body.get("function_id") or "").strip()
    return {
        "success": False,
        "status": "failed",
        "run_id": str(body.get("run_id") or ""),
        "function_id": function_id,
        "source": "oob_function_replay" if function_id else "omniflow",
        "runner": "omniflow_python",
        "execution_mode": str(body.get("execution_mode") or "foreground"),
        "step_count": 0,
        "success_step_count": 0,
        "completed_step_count": 0,
        "actions_executed": 0,
        "steps": [],
        "started_at_ms": started_at_ms,
        "finished_at_ms": finished_at_ms,
        "duration_ms": max(0, finished_at_ms - started_at_ms),
        "error_code": code,
        "error_message": message,
        "done_reason": "error",
    }


def _state_from_observation(
    value: Observation | None,
    *,
    include_xml: bool = False,
) -> dict[str, Any] | None:
    if value is None:
        return None
    display = value.extra.get("display")
    canonical_display = dict(display) if isinstance(display, dict) else None
    identity = "\0".join(
        (
            str(value.package_name or ""),
            str(value.activity_name or ""),
            str(value.xml or ""),
            str((canonical_display or {}).get("width") or ""),
            str((canonical_display or {}).get("height") or ""),
        )
    )
    state: dict[str, Any] = {
        "state_id": str(value.extra.get("state_id") or "").strip()
        or "state_" + hashlib.sha256(identity.encode()).hexdigest()[:20],
    }
    if value.package_name:
        state["package_name"] = str(value.package_name)
    if value.activity_name:
        state["activity_name"] = str(value.activity_name)
    if canonical_display is not None:
        state["display"] = canonical_display
    canonical = canonicalize_state(state)
    if include_xml and value.xml:
        canonical["xml"] = value.xml
    return canonical


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--store", required=True)
    parser.add_argument("--once", action="store_true")
    arguments = parser.parse_args(argv)
    bridge = JsonLineBridge(arguments.store)
    if arguments.once:
        bridge.serve_once()
    else:
        bridge.serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
