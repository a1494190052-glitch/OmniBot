from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
from pathlib import Path
import re
import sys
import tempfile
from typing import Any, TextIO

from omniflow.artifact import parse_function_artifact
from omniflow.compile import compile_runlog_to_store
from omniflow.execute import execute_action, prepare_action
from omniflow.function_management import edit_function, enhance_function
from omniflow.model import Action, ActionResult, Function, Observation
from omniflow.runtime import OmniFlow
from omniflow.schemas import canonicalize_action
from omniflow.trajectory import canonicalize_run_log_step, canonicalize_state

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
        if operation == "recall":
            return self._recall(body)
        if operation == "enhance":
            return self._enhance(request_id, body)
        if operation == "prepare_action":
            return self._prepare_action(request_id, body)
        if operation == "control_act":
            return self._control_act(request_id, body)
        if operation == "record_step":
            return self._record_step(body)
        if operation == "run":
            self.flow.host = _BridgeHost(self, request_id)
            function_id = str(body.get("function_id") or "").strip()
            if function_id:
                result = asyncio.run(
                    self.flow.arun_function(
                        function_id,
                        arguments=dict(body.get("arguments") or {}),
                    )
                )
            else:
                result = self.flow.run(str(body.get("goal") or ""))
            return _run_result(result)
        raise ValueError(f"unsupported_operation:{operation}")

    def _catalog(self, body: dict[str, Any]) -> dict[str, Any]:
        action = str(body.get("action") or "list")
        if action == "list":
            include_hidden = body.get("include_hidden") is True
            functions = self.flow.store.list_functions(
                offset=int(body.get("offset") or 0),
                limit=int(body.get("limit") or 100),
                include_hidden=include_hidden,
            )
            total = sum(
                1
                for item in self.flow.store.functions.values()
                if include_hidden or item.agent_visible
            )
            return {
                "functions": [item.to_dict() for item in functions],
                "count": len(functions),
                "total": total,
            }
        if action == "get":
            function = self.flow.store.get_function(str(body.get("function_id") or ""))
            return {"function": function.to_dict() if function else None}
        if action == "put":
            function = self.flow.store.put_function(dict(body.get("function") or {}))
            return {"function": function.to_dict(), "function_id": function.function_id}
        if action == "edit":
            function_id = str(body.get("function_id") or "").strip()
            original = self.flow.store.get_function(function_id)
            if original is None:
                return {"function": None, "function_id": function_id, "found": False}
            edits = body.get("action_edits")
            if not isinstance(edits, list):
                raise ValueError("catalog_action_edits_must_be_array")
            updated, changes = edit_function(original.to_dict(), edits)
            dry_run = body.get("dry_run") is True
            if changes and not dry_run:
                self.flow.store.put_function(updated)
            return {
                "function": original.to_dict(),
                "updated_function": updated,
                "function_id": function_id,
                "found": True,
                "changed": bool(changes),
                "saved": bool(changes) and not dry_run,
                "dry_run": dry_run,
                "changes": changes,
            }
        if action == "delete":
            return {
                "deleted": self.flow.store.delete_function(
                    str(body.get("function_id") or "")
                )
            }
        if action == "clear":
            return {"deleted_count": self.flow.store.clear_functions()}
        raise ValueError(f"unsupported_catalog_action:{action}")

    def _compile(self, request_id: str, body: dict[str, Any]) -> dict[str, Any]:
        _require_contract(body, {"run_id"}, {"run_id"})
        run_id = str(body.get("run_id") or "").strip()
        if not run_id:
            raise ValueError("run_id_required")
        run_log = self.host_call(request_id, "get_run_log", {"run_id": run_id})
        if not isinstance(run_log, dict):
            raise ValueError("run_log_invalid")

        with tempfile.TemporaryDirectory(prefix="omniflow-compile-") as output_root:
            report = compile_runlog_to_store(run_log, output_root)
            compiled = OmniFlow(Path(output_root) / "store.json")
            function_id = next(iter(report["function_ids"]), "")
            function = compiled.store.get_function(function_id)
        if function is None:
            return {"success": False, "function": None, "error": "no_actions"}
        return {"success": True, "function": function.to_dict(), "error": None}

    def _enhance(self, request_id: str, body: dict[str, Any]) -> dict[str, Any]:
        function_id = str(body.get("function_id") or "").strip()
        function = self.flow.store.get_function(function_id)
        if function is None:
            return {"function_id": function_id, "found": False}
        run_log = body.get("run_log")
        if not isinstance(run_log, dict):
            run_log = {}

        def complete_json(prompt: str) -> str:
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

        updated, changes, status = enhance_function(
            function.to_dict(),
            run_log,
            complete_json,
        )
        self.flow.store.put_function(updated)
        return {
            "function_id": function_id,
            "found": True,
            "function": function.to_dict(),
            "updated_function": updated,
            "changed": bool(changes),
            "saved": True,
            "changes": changes,
            "enhancement_status": status,
        }

    def _recall(self, body: dict[str, Any]) -> dict[str, Any]:
        _require_contract(body, {"goal", "state"}, {"goal", "state"})
        if not isinstance(body.get("state"), dict):
            raise ValueError("state_must_be_object")
        _state_observation(body["state"])
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
        return {
            "candidates": [
                {
                    "function": function.to_dict(),
                    "retrieval": {
                        "score": score,
                        "source": "goal_token_jaccard",
                        "rank": rank,
                    },
                }
                for rank, (score, function) in enumerate(ranked, start=1)
            ]
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

    def _record_step(self, body: dict[str, Any]) -> dict[str, Any]:
        return {"step": canonicalize_run_log_step(body)}

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


def _run_result(result) -> dict[str, Any]:
    return {
        "success": result.success,
        "function_id": result.function_id,
        "actions_executed": result.actions_executed,
        "model_calls": result.model_calls,
        "fallback_steps": result.fallback_steps,
        "error": result.error,
        "final_state": _state_from_observation(result.final_state),
        "detail": result.detail,
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
