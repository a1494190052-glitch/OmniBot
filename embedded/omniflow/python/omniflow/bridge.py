from __future__ import annotations

import argparse
import asyncio
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
from omniflow.execute import execute_action, prepare_action
from omniflow.function_management import edit_function, enhance_function
from omniflow.model import Action, ActionResult, Function, Observation
from omniflow.runtime import OmniFlow
from omniflow.schemas import canonicalize_action
from omniflow.trajectory import canonicalize_state

PROTOCOL_VERSION = "omniflow.bridge.v2"
_COORDINATE_TOOLS = {"click", "input_text", "long_press", "swipe"}


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
            return {
                "protocol_version": PROTOCOL_VERSION,
                "functions": len(self.flow.store.functions),
                "invalid_functions": dict(self.flow.store.load_errors),
                "store_path": str(self.flow.store.path),
            }
        if operation == "catalog":
            return self._catalog(body)
        if operation == "compile":
            return self._compile(request_id, body)
        if operation == "update_function":
            return self._update_function(request_id, body)
        if operation == "recall":
            return self._recall(body)
        if operation == "enhance":
            return self._enhance(request_id, body)
        if operation == "prepare_action":
            return self._prepare_action(request_id, body)
        if operation == "control_act":
            return self._control_act(request_id, body)
        if operation == "run":
            self.flow.host = _BridgeHost(self, request_id)
            function_id = str(body.get("function_id") or "").strip()
            function = self.flow.store.get_function(function_id) if function_id else None
            if function_id:
                result = asyncio.run(
                    self.flow.arun_function(
                        function_id,
                        arguments=dict(body.get("arguments") or {}),
                    )
                )
            else:
                result = self.flow.run(str(body.get("goal") or ""))
            return _run_result(result, body=body, function=function)
        raise ValueError(f"unsupported_operation:{operation}")

    def _catalog(self, body: dict[str, Any]) -> dict[str, Any]:
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
            function = self.flow.store.get_function(str(body.get("function_id") or ""))
            return {"function": function.to_dict() if function else None}
        if action == "put":
            value = dict(body.get("function") or {})
            function_id = str(value.get("function_id") or "").strip()
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
        function_id = value["function_id"]
        already_exists = self.flow.store.get_function(function_id) is not None
        if register:
            self.flow.store.put_function(value)
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
            "error": None,
            "source": "omniflow_python",
        }

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
            loaded = self.host_call(request_id, "get_run_log", {"run_id": run_id})
            if isinstance(loaded, dict):
                run_log = loaded
        return self._enhance(
            request_id,
            {"function_id": function_id, "run_log": run_log},
        )

    def _enhance(self, request_id: str, body: dict[str, Any]) -> dict[str, Any]:
        function_id = str(body.get("function_id") or "").strip()
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
        self.flow.store.put_function(updated)
        return {
            "success": True,
            "function_id": function_id,
            "found": True,
            "function": function.to_dict(),
            "updated_function": updated,
            "changed": bool(changes),
            "saved": True,
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

    def _recall(self, body: dict[str, Any]) -> dict[str, Any]:
        started_at = time.monotonic()
        _require_contract(body, {"goal", "state"}, {"goal", "state", "limit"})
        if not isinstance(body.get("state"), dict):
            raise ValueError("state_must_be_object")
        _state_observation(body["state"])
        limit = max(1, min(int(body.get("limit") or 8), 50))
        goal_tokens = _tokens(str(body.get("goal") or ""))
        scored: list[tuple[float, Function]] = []
        for function in self.flow.store.functions.values():
            if not function.agent_visible:
                continue
            candidate_tokens = _tokens(f"{function.name} {function.description}")
            if not goal_tokens or not candidate_tokens:
                continue
            score = len(goal_tokens & candidate_tokens) / len(goal_tokens | candidate_tokens)
            if score > 0:
                scored.append((score, function))
        ranked = sorted(scored, key=lambda item: (-item[0], item[1].function_id))
        candidates = [
                {
                    "function": function.to_dict(),
                    "retrieval": {
                        "score": score,
                        "source": "goal_token_jaccard",
                        "rank": rank,
                    },
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

    def _prepare_action(
        self,
        request_id: str,
        body: dict[str, Any],
    ) -> dict[str, Any]:
        _require_contract(
            body,
            {"function_id", "source_state_id", "action", "state"},
            {"function_id", "source_state_id", "action", "state", "checker_rules"},
        )
        function_id = str(body.get("function_id") or "").strip()
        if not function_id:
            raise ValueError("function_id_required")
        action = _action(body.get("action"))
        source_state_id = str(body.get("source_state_id") or "").strip()
        if not source_state_id:
            return _blocked("source_state_missing")
        value = self.host_call(request_id, "get_state", {"state_id": source_state_id})
        source_state = _state_observation(value)
        if action.tool in _COORDINATE_TOOLS and not source_state.xml:
            return _blocked("source_state_missing")
        target_value = body.get("state")
        if not isinstance(target_value, dict):
            raise ValueError("state_must_be_object")
        target_state = _state_observation(target_value)
        if action.tool in _COORDINATE_TOOLS and not target_state.xml:
            return _blocked("target_state_missing")
        decision = asyncio.run(
            prepare_action(
                action,
                observation=target_state,
                source_state=source_state,
                plugins=self.flow.plugins,
            )
        )
        if decision.kind == "block" or decision.action is None:
            return _blocked(
                decision.reason or "action_blocked",
                transfer=decision.detail,
            )
        return {
            "success": True,
            "decision": decision.kind,
            "action": decision.action.to_dict(),
            "reason": decision.reason,
        }

    def _control_act(
        self,
        request_id: str,
        body: dict[str, Any],
    ) -> dict[str, Any]:
        _require_contract(
            body,
            {"action"},
            {"action", "function_id", "source_state_id", "checker_rules", "state"},
        )
        function_id = str(body.get("function_id") or "").strip()
        source_state_id = str(body.get("source_state_id") or "").strip()
        if bool(function_id) != bool(source_state_id):
            raise ValueError("control_act_function_context_invalid")
        action = _action(
            canonicalize_action(body.get("action"), persisted_only=bool(function_id))
        )
        host = _BridgeHost(self, request_id)
        raw_state = body.get("state")
        observation = (
            _state_observation(raw_state)
            if raw_state is not None
            else host.observe(xml=True, app_info=True)
        )
        host.current_observation = observation
        function = _bridge_function(
            function_id,
            tuple(body.get("checker_rules") or ()),
        ) if function_id else None
        source_state = host.get_state(source_state_id) if source_state_id else None
        step = asyncio.run(
            execute_action(
                action,
                observation=observation,
                host=host,
                plugins=self.flow.plugins,
                function=function,
                source_state=source_state,
            )
        )
        result = {
            "success": step.success,
            "action": step.action.to_dict() if step.origin != "blocked" and step.action else None,
            "result": step.result.to_dict() if step.result else None,
            "before_state": _state_from_observation(step.before, include_xml=True),
            "after_state": _state_from_observation(step.after, include_xml=True),
            "error": step.error,
        }
        if step.detail:
            result["transfer"] = dict(step.detail)
        return result

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
    def __init__(self, bridge: JsonLineBridge, request_id: str):
        self.bridge = bridge
        self.request_id = request_id
        self.current_observation: Observation | None = None

    def observe(self, **kwargs: Any) -> Observation:
        self.current_observation = _state_observation(
            self.bridge.host_call(self.request_id, "observe", kwargs)
        )
        return self.current_observation

    def act(self, action: Action) -> ActionResult:
        if self.current_observation is None:
            raise RuntimeError("host_action_state_required")
        return ActionResult.from_value(
            self.bridge.host_call(
                self.request_id,
                "act",
                {
                    "action": action.to_dict(),
                    "state": _state_from_observation(
                        self.current_observation,
                        include_xml=True,
                    ),
                },
            )
        )

    def get_state(self, source_state_id: str) -> Observation:
        return _state_observation(
            self.bridge.host_call(self.request_id, "get_state", {"state_id": source_state_id})
        )

    def record_step(self, step: dict[str, Any]) -> None:
        self.bridge.host_call(self.request_id, "record_step", {"step": step})


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


def _bridge_function(
    function_id: str,
    checker_rules: tuple[Any, ...],
) -> Function:
    return Function(
        schema_version="omniflow.function.v2",
        function_id=function_id,
        name="bridge_function",
        description="Execute one Function action.",
        input_schema={
            "type": "object",
            "properties": {},
            "required": [],
            "additionalProperties": False,
        },
        bindings=(),
        steps=(),
        checker_rules=checker_rules,
        agent_visible=False,
    )


def _blocked(
    reason: str,
    *,
    transfer: dict[str, Any] | None = None,
) -> dict[str, Any]:
    result = {
        "success": False,
        "decision": "block",
        "action": None,
        "reason": reason,
    }
    if transfer:
        result["transfer"] = transfer
    return result


def _tokens(value: str) -> set[str]:
    return set(re.findall(r"[\w\u4e00-\u9fff]+", value.lower()))


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


def _compile_error(run_id: str, error: ValueError) -> dict[str, Any]:
    message = str(error)
    code = {
        "successful_source_actions_required": "RUN_LOG_NO_REPLAYABLE_STEPS",
        "semantic_functions_required": "RUN_LOG_NO_REPLAYABLE_STEPS",
        "default_bundle_actions_required": "RUN_LOG_NO_REPLAYABLE_STEPS",
        "successful_source_run_log_required": "RUN_LOG_NOT_SUCCESSFUL",
        "successful_source_goal_required": "RUN_LOG_GOAL_EMPTY",
    }.get(message, "RUN_LOG_COMPILE_FAILED")
    user_message = {
        "RUN_LOG_NO_REPLAYABLE_STEPS": "RunLog has no replayable steps",
        "RUN_LOG_NOT_SUCCESSFUL": "RunLog did not finish successfully",
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
        "source": "oob_function_replay",
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
