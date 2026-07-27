#!/usr/bin/env python3
"""Run the OmniFlow/GUI PR acceptance loop on a connected debug device.

The script is intentionally an external harness. It calls the existing debug
receivers and writes a summary consumed by tools/oob_pr_freeze_check.py.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import re
import shlex
import subprocess
import sys
import threading
import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Callable
from urllib import error as urllib_error
from urllib import request as urllib_request
from xml.etree import ElementTree


EMBEDDED_OMNIFLOW_PYTHON = Path(__file__).resolve().parents[1] / "embedded/omniflow/python"
if str(EMBEDDED_OMNIFLOW_PYTHON) not in sys.path:
    sys.path.insert(0, str(EMBEDDED_OMNIFLOW_PYTHON))

from omniflow.gui import (  # noqa: E402
    ModelToolCallError,
    build_model_turn_request,
    parse_model_turn_response,
)


PACKAGE = "cn.com.omnimind.bot.debug"

ACTION_HUMAN_RECORDING = "cn.com.omnimind.bot.debug.HUMAN_RUN_RECORDING"
ACTION_FUNCTION_UPDATE = "cn.com.omnimind.bot.debug.UPDATE_OOB_FUNCTION"
ACTION_FUNCTION_RUN = "cn.com.omnimind.bot.debug.RUN_OOB_FUNCTION"
ACTION_FUNCTION_IMPORT = "cn.com.omnimind.bot.debug.IMPORT_OOB_FUNCTION"
ACTION_GUI_TASK = "cn.com.omnimind.bot.debug.RUN_GUI_TASK_VALIDATION"
ACTION_CANCEL = "cn.com.omnimind.bot.debug.CANCEL_AGENT_TASK"
ACTION_PROVIDER_CONFIG = "cn.com.omnimind.bot.debug.CONFIGURE_MODEL_PROVIDER"


class MockVlmProvider:
    """Tiny OpenAI-compatible SSE provider used only by this acceptance harness."""

    def __init__(self, *, port: int = 0, model: str, stop_delay_seconds: float) -> None:
        self.requested_port = port
        self.model = model
        self.stop_delay_seconds = stop_delay_seconds
        self.port = 0
        self.host_base_url = ""
        self.device_base_url = ""
        self.reverse_enabled = False
        self._server: ThreadingHTTPServer | None = None
        self._thread: threading.Thread | None = None
        self._lock = threading.Lock()
        self._hits: list[dict[str, Any]] = []
        self._requests: list[dict[str, Any]] = []
        self._invalid_gui_args_injected = False
        self._valid_gui_action_emitted = False
        self._blocked_recall_goal = ""

    def start(self, adb: "Adb") -> None:
        server = ThreadingHTTPServer(("127.0.0.1", self.requested_port), _MockVlmProviderHandler)
        server.daemon_threads = True
        server.provider = self  # type: ignore[attr-defined]
        self._server = server
        self.port = int(server.server_address[1])
        self.host_base_url = f"http://127.0.0.1:{self.port}"
        reverse = adb.run(
            ["reverse", f"tcp:{self.port}", f"tcp:{self.port}"],
            check=False,
            timeout=10,
        )
        self._thread = threading.Thread(target=server.serve_forever, name="mock-vlm-provider", daemon=True)
        self._thread.start()
        reverse_reachable = (
            reverse.returncode == 0 and adb.http_probe("127.0.0.1", self.port, "/models")
        )
        emulator_host_reachable = adb.http_probe("10.0.2.2", self.port, "/models")
        self.reverse_enabled = reverse_reachable
        self.device_base_url = (
            f"http://127.0.0.1:{self.port}"
            if reverse_reachable
            else f"http://10.0.2.2:{self.port}"
            if emulator_host_reachable
            else f"http://127.0.0.1:{self.port}"
        )

    def stop(self, adb: "Adb") -> None:
        if self.port:
            adb.run(["reverse", "--remove", f"tcp:{self.port}"], check=False, timeout=10)
        if self._server is not None:
            self._server.shutdown()
            self._server.server_close()

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            hits = list(self._hits)
            requests = list(self._requests)
        return {
            "host_base_url": self.host_base_url,
            "base_url": self.device_base_url,
            "port": self.port,
            "model": self.model,
            "reverse_enabled": self.reverse_enabled,
            "hit_count": len(hits),
            "hits": hits,
            "request_count": len(requests),
            "requests": requests,
        }

    def record_hit(self, handler: BaseHTTPRequestHandler, method: str) -> None:
        entry = {
            "method": method,
            "path": handler.path,
            "content_type": handler.headers.get("Content-Type", ""),
            "accept": handler.headers.get("Accept", ""),
            "content_length": handler.headers.get("Content-Length", ""),
        }
        with self._lock:
            self._hits.append(entry)

    def handle_chat_completion(self, body: dict[str, Any], handler: BaseHTTPRequestHandler) -> None:
        messages = body.get("messages") if isinstance(body.get("messages"), list) else []
        messages_text = json.dumps(messages, ensure_ascii=False)
        user_text = self._last_user_text(messages)
        has_tool_result = any(
            isinstance(message, dict) and message.get("role") == "tool"
            for message in messages
        )
        should_delay = self._is_stop_request(user_text)
        tools = body.get("tools") if isinstance(body.get("tools"), list) else []
        gui_probe_completed = self._gui_probe_completed(tools)
        selected_tool, selected_args, tool_names, dynamic_tool_names = self._select_tool(
            tools,
            has_tool_result=has_tool_result,
            reselection_requested=self._has_reselection_signal(messages_text),
            replay_completed=gui_probe_completed
            or ("recent_actions" in messages_text and "function_id" in messages_text),
        )
        if selected_tool == "vlm_task" and user_text:
            selected_args["goal"] = user_text
        selected_args, invalid_gui_args_injected = self._inject_invalid_gui_arguments(
            selected_tool=selected_tool,
            selected_args=selected_args,
            tool_names=tool_names,
        )
        entry = {
            "path": handler.path,
            "model": body.get("model"),
            "stream": body.get("stream"),
            "tool_choice": body.get("tool_choice"),
            "tool_count": len(tools),
            "tool_names": tool_names,
            "dynamic_function_tool_names": dynamic_tool_names,
            "selected_tool": selected_tool,
            "selected_arguments": selected_args,
            "invalid_gui_args_injected": invalid_gui_args_injected,
            "gui_probe_completed": gui_probe_completed,
            "delayed_for_stop": should_delay,
        }
        with self._lock:
            self._requests.append(entry)

        if should_delay and self.stop_delay_seconds > 0:
            time.sleep(self.stop_delay_seconds)

        if has_tool_result and "vlm_task" in tool_names:
            self._write_text_completion(
                handler,
                "GUI task completed.",
                stream=body.get("stream") is True,
            )
            return

        if not tool_names:
            resolution = self._resolver_response(messages)
            if resolution is not None:
                self._write_text_completion(
                    handler,
                    json.dumps(resolution, ensure_ascii=False, separators=(",", ":")),
                    stream=body.get("stream") is True,
                )
                return
            enhancement = self._enhancement_response(messages)
            if enhancement is not None:
                self._write_text_completion(
                    handler,
                    json.dumps(enhancement, ensure_ascii=False, separators=(",", ":")),
                    stream=body.get("stream") is True,
                )
                return
            self._write_text_completion(
                handler,
                json.dumps(
                    {
                        "name": "Reusable GUI action",
                        "description": "Replay the recorded Android GUI action.",
                        "parameters": [],
                        "checker_rules": [],
                    },
                    ensure_ascii=False,
                    separators=(",", ":"),
                ),
                stream=body.get("stream") is True,
            )
            return

        if not selected_tool:
            self._write_json(
                handler,
                400,
                {
                    "error": {
                        "message": "mock provider could not find a model-visible tool",
                        "type": "mock_provider_error",
                    }
                },
            )
            return

        call_id = f"call_mock_{int(time.time() * 1000)}"
        arguments = json.dumps(selected_args, ensure_ascii=False, separators=(",", ":"))
        chunks = [
            {
                "id": "chatcmpl-mock",
                "object": "chat.completion.chunk",
                "created": int(time.time()),
                "model": self.model,
                "choices": [
                    {
                        "index": 0,
                        "delta": {
                            "tool_calls": [
                                {
                                    "index": 0,
                                    "id": call_id,
                                    "type": "function",
                                    "function": {
                                        "name": selected_tool,
                                        "arguments": arguments,
                                    },
                                }
                            ]
                        },
                        "finish_reason": None,
                    }
                ],
            },
            {
                "id": "chatcmpl-mock",
                "object": "chat.completion.chunk",
                "created": int(time.time()),
                "model": self.model,
                "choices": [
                    {
                        "index": 0,
                        "delta": {},
                        "finish_reason": "tool_calls",
                    }
                ],
                "usage": {
                    "prompt_tokens": 1,
                    "completion_tokens": 1,
                    "total_tokens": 2,
                },
            },
        ]
        handler.send_response(200)
        handler.send_header("Content-Type", "text/event-stream; charset=utf-8")
        handler.send_header("Cache-Control", "no-cache")
        handler.send_header("Connection", "close")
        handler.end_headers()
        for chunk in chunks:
            handler.wfile.write(f"data: {json.dumps(chunk, ensure_ascii=False, separators=(',', ':'))}\n\n".encode("utf-8"))
            handler.wfile.flush()
        handler.wfile.write(b"data: [DONE]\n\n")
        handler.wfile.flush()

    def handle_models(self, handler: BaseHTTPRequestHandler) -> None:
        self._write_json(
            handler,
            200,
            {
                "object": "list",
                "data": [
                    {
                        "id": self.model,
                        "object": "model",
                        "owned_by": "oob-acceptance",
                    }
                ],
            },
        )

    def _select_tool(
        self,
        tools: list[Any],
        *,
        has_tool_result: bool,
        reselection_requested: bool,
        replay_completed: bool,
    ) -> tuple[str, dict[str, Any], list[str], list[str]]:
        tool_infos = []
        for item in tools:
            if not isinstance(item, dict):
                continue
            function = item.get("function")
            if not isinstance(function, dict):
                continue
            name = str(function.get("name") or "").strip()
            if not name:
                continue
            tool_type = str(function.get("toolType") or function.get("tool_type") or "").strip()
            parameters = function.get("parameters") if isinstance(function.get("parameters"), dict) else {}
            tool_infos.append(
                {
                    "name": name,
                    "tool_type": tool_type,
                    "parameters": parameters,
                }
            )
        tool_names = [item["name"] for item in tool_infos]
        dynamic = [
            item for item in tool_infos
            if item["name"].startswith("run_recalled_workflow_")
            or item["name"].startswith("recalled_function_")
            or item["tool_type"] == "oob_recalled_function"
        ]
        selected = next((item for item in tool_infos if item["name"] == "vlm_task"), None)
        if selected is None and replay_completed:
            selected = next((item for item in tool_infos if item["name"] == "finished"), None)
        if selected is None and reselection_requested:
            selected = next((item for item in tool_infos if item["name"] == "finished"), None)
        if selected is None and dynamic:
            selected = dynamic[0]
        if selected is None and has_tool_result:
            selected = next((item for item in tool_infos if item["name"] == "finished"), None)
        if selected is None:
            selected = next((item for item in tool_infos if item["name"] == "click"), None)
        if selected is None:
            selected = next((item for item in tool_infos if item["name"] == "finished"), None)
        if selected is None and tool_infos:
            selected = tool_infos[0]
        if selected is None:
            return "", {}, tool_names, [item["name"] for item in dynamic]
        return (
            selected["name"],
            self._arguments_for_schema(selected["parameters"]),
            tool_names,
            [item["name"] for item in dynamic],
        )

    def _arguments_for_schema(self, schema: dict[str, Any]) -> dict[str, Any]:
        properties = schema.get("properties") if isinstance(schema.get("properties"), dict) else {}
        required = schema.get("required") if isinstance(schema.get("required"), list) else []
        result: dict[str, Any] = {}
        for raw_name in required:
            name = str(raw_name).strip()
            if not name:
                continue
            property_schema = properties.get(name) if isinstance(properties.get(name), dict) else {}
            result[name] = self._sample_value(property_schema, name)
        return result

    def _resolver_response(self, messages: list[Any]) -> dict[str, Any] | None:
        content = next(
            (
                str(message.get("content") or "")
                for message in reversed(messages)
                if isinstance(message, dict)
                and "Select one reusable Function" in str(message.get("content") or "")
            ),
            "",
        )
        if not content:
            return None
        try:
            request = json.loads(content.rsplit("\n\n", 1)[-1])
        except (json.JSONDecodeError, TypeError):
            return {"function_id": None, "arguments": {}}
        goal = str(request.get("goal") or "")
        with self._lock:
            blocked_recall_goal = self._blocked_recall_goal
        if blocked_recall_goal and blocked_recall_goal in goal:
            return {"function_id": None, "arguments": {}}
        functions = request.get("functions") if isinstance(request.get("functions"), list) else []
        goal_tokens = set(re.findall(r"[\w-]{4,}", goal.lower()))
        ranked: list[tuple[int, dict[str, Any]]] = []
        for function in functions:
            if not isinstance(function, dict):
                continue
            searchable = " ".join(
                (str(function.get("function_id") or ""), str(function.get("description") or ""))
            ).lower()
            score = max(
                (len(token) for token in goal_tokens if token in searchable),
                default=0,
            )
            ranked.append((score, function))
        if not ranked:
            return {"function_id": None, "arguments": {}}
        score, selected = max(ranked, key=lambda item: item[0])
        schema = selected.get("input_schema") if isinstance(selected.get("input_schema"), dict) else {}
        required = schema.get("required") if isinstance(schema.get("required"), list) else []
        if score <= 0 or required:
            return {"function_id": None, "arguments": {}}
        return {
            "function_id": str(selected.get("function_id") or "") or None,
            "arguments": {},
        }

    def _enhancement_response(self, messages: list[Any]) -> dict[str, Any] | None:
        content = next(
            (
                str(message.get("content") or "")
                for message in reversed(messages)
                if isinstance(message, dict)
                and "Improve the reusable Android automation Function" in str(message.get("content") or "")
            ),
            "",
        )
        if not content:
            return None
        try:
            function = json.loads(content.rsplit("Function:\n", 1)[-1])
        except (json.JSONDecodeError, TypeError):
            return None
        candidates = (
            function.get("parameter_candidates")
            if isinstance(function.get("parameter_candidates"), list)
            else []
        )
        parameters = [
            {
                "name": "query",
                "description": "Text to enter",
                "step_index": int(candidate["step_index"]),
                "arg_name": str(candidate["arg_name"]),
            }
            for candidate in candidates[:1]
            if isinstance(candidate, dict)
            and candidate.get("arg_name") == "text"
            and isinstance(candidate.get("step_index"), int)
        ]
        name = str(function.get("name") or "Reusable GUI action").strip()
        description = str(function.get("description") or "Replay the recorded Android GUI action.").strip()
        return {
            "name": f"{name}（增强）"[:80],
            "description": f"{description} 可用于同类目标。"[:2000],
            "parameters": parameters,
            "checker_rules": [],
        }

    def _sample_value(self, schema: dict[str, Any], name: str) -> Any:
        raw_type = schema.get("type")
        if isinstance(raw_type, list):
            raw_type = next((item for item in raw_type if item != "null"), "string")
        kind = str(raw_type or "string").strip().lower()
        if kind in ("number", "integer"):
            return 1 if kind == "integer" else 1.0
        if kind == "boolean":
            return True
        if kind == "array":
            return []
        if kind == "object":
            return {}
        if name.lower() in ("content", "message", "reason", "value", "text", "prompt", "query"):
            return "mock acceptance"
        return "mock"

    def _is_stop_request(self, text: str) -> bool:
        lowered = text.lower()
        return "stop" in lowered or "停止" in text or "持续执行" in text

    def _has_reselection_signal(self, messages_text: str) -> bool:
        return any(
            signal in messages_text
            for signal in (
                "action_completed_without_state_change",
                "repeated_action_without_progress",
            )
        )

    def _inject_invalid_gui_arguments(
        self,
        *,
        selected_tool: str,
        selected_args: dict[str, Any],
        tool_names: list[str],
    ) -> tuple[dict[str, Any], bool]:
        if selected_tool != "click" or "vlm_task" in tool_names:
            return selected_args, False
        with self._lock:
            if self._invalid_gui_args_injected:
                self._valid_gui_action_emitted = True
                return selected_args, False
            self._invalid_gui_args_injected = True
        invalid_args = dict(selected_args)
        invalid_args["x"] = [1, 2]
        return invalid_args, True

    def _gui_probe_completed(self, tools: list[Any]) -> bool:
        tool_names = {
            str(item.get("function", {}).get("name") or "").strip()
            for item in tools
            if isinstance(item, dict) and isinstance(item.get("function"), dict)
        }
        if "vlm_task" in tool_names or "finished" not in tool_names:
            return False
        with self._lock:
            return self._valid_gui_action_emitted

    def reset_gui_argument_probe(self, blocked_recall_goal: str = "") -> None:
        with self._lock:
            self._invalid_gui_args_injected = False
            self._valid_gui_action_emitted = False
            self._blocked_recall_goal = str(blocked_recall_goal or "").strip()

    def _last_user_text(self, messages: list[Any]) -> str:
        for message in reversed(messages):
            if not isinstance(message, dict) or message.get("role") != "user":
                continue
            content = message.get("content")
            if isinstance(content, str):
                return content.strip()
            if isinstance(content, list):
                return "\n".join(
                    str(item.get("text") or "").strip()
                    for item in content
                    if isinstance(item, dict) and item.get("type") == "text"
                ).strip()
        return ""

    def _write_json(self, handler: BaseHTTPRequestHandler, status: int, payload: dict[str, Any]) -> None:
        data = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        handler.send_response(status)
        handler.send_header("Content-Type", "application/json; charset=utf-8")
        handler.send_header("Content-Length", str(len(data)))
        handler.end_headers()
        handler.wfile.write(data)

    def _write_text_completion(
        self,
        handler: BaseHTTPRequestHandler,
        content: str,
        *,
        stream: bool,
    ) -> None:
        if not stream:
            self._write_json(
                handler,
                200,
                {
                    "id": "chatcmpl-mock",
                    "object": "chat.completion",
                    "created": int(time.time()),
                    "model": self.model,
                    "choices": [
                        {
                            "index": 0,
                            "message": {"role": "assistant", "content": content},
                            "finish_reason": "stop",
                        }
                    ],
                    "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
                },
            )
            return
        chunks = [
            {
                "id": "chatcmpl-mock",
                "object": "chat.completion.chunk",
                "created": int(time.time()),
                "model": self.model,
                "choices": [{"index": 0, "delta": {"content": content}, "finish_reason": None}],
            },
            {
                "id": "chatcmpl-mock",
                "object": "chat.completion.chunk",
                "created": int(time.time()),
                "model": self.model,
                "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
                "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
            },
        ]
        handler.send_response(200)
        handler.send_header("Content-Type", "text/event-stream; charset=utf-8")
        handler.send_header("Cache-Control", "no-cache")
        handler.send_header("Connection", "close")
        handler.end_headers()
        for chunk in chunks:
            handler.wfile.write(
                f"data: {json.dumps(chunk, ensure_ascii=False, separators=(',', ':'))}\n\n".encode("utf-8")
            )
            handler.wfile.flush()
        handler.wfile.write(b"data: [DONE]\n\n")
        handler.wfile.flush()


class _MockVlmProviderHandler(BaseHTTPRequestHandler):
    server_version = "OobMockVlmProvider/1.0"

    def do_GET(self) -> None:  # noqa: N802
        provider: MockVlmProvider = self.server.provider  # type: ignore[attr-defined]
        provider.record_hit(self, "GET")
        if self.path.rstrip("/").endswith("/models"):
            provider.handle_models(self)
            return
        provider._write_json(self, 404, {"error": {"message": "not found"}})

    def do_POST(self) -> None:  # noqa: N802
        provider: MockVlmProvider = self.server.provider  # type: ignore[attr-defined]
        provider.record_hit(self, "POST")
        length = int(self.headers.get("Content-Length") or "0")
        raw = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
        body = json.loads(raw or "{}")
        if self.path.rstrip("/").endswith("/chat/completions"):
            provider.handle_chat_completion(body, self)
            return
        provider._write_json(self, 404, {"error": {"message": "not found"}})

    def log_message(self, format: str, *args: Any) -> None:
        return


@dataclass
class Adb:
    serial: str | None
    package: str

    @property
    def base(self) -> list[str]:
        cmd = ["adb"]
        if self.serial:
            cmd.extend(["-s", self.serial])
        return cmd

    def run(self, args: list[str], *, check: bool = True, timeout: int = 30) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            self.base + args,
            check=check,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
        )

    def shell(self, args: list[str], *, check: bool = True, timeout: int = 30) -> str:
        result = self.run(["shell", *args], check=check, timeout=timeout)
        return result.stdout.strip()

    def http_probe(self, host: str, port: int, path: str = "/models") -> bool:
        request = f"GET {path} HTTP/1.1\\r\\nHost: {host}\\r\\nConnection: close\\r\\n\\r\\n"
        command = f"printf {shell_quote(request)} | nc -w 2 {shell_quote(host)} {int(port)}"
        result = self.run(["shell", command], check=False, timeout=5)
        return " 200 " in result.stdout.splitlines()[0:1][0] if result.stdout.splitlines() else False

    def get_global_setting(self, name: str) -> str:
        return self.shell(["settings", "get", "global", name], check=False, timeout=10).strip()

    def put_global_setting(self, name: str, value: str) -> None:
        self.shell(["settings", "put", "global", name, value], check=False, timeout=10)

    def delete_global_setting(self, name: str) -> None:
        self.shell(["settings", "delete", "global", name], check=False, timeout=10)

    def broadcast(self, action: str, extras: list[str] | None = None, *, timeout: int = 30) -> None:
        self.run(["shell", "am", "broadcast", "-p", self.package, "-a", action, *(extras or [])], timeout=timeout)

    def run_as(self, args: list[str], *, check: bool = True, timeout: int = 30) -> str:
        return self.shell(["run-as", self.package, *args], check=check, timeout=timeout)

    def read_file_json(self, file_name: str) -> dict[str, Any] | None:
        result = self.run(
            ["shell", "run-as", self.package, "cat", f"files/{file_name}"],
            check=False,
            timeout=10,
        )
        if result.returncode != 0 or not result.stdout.strip():
            return None
        try:
            return json.loads(result.stdout)
        except json.JSONDecodeError:
            return {"success": False, "error": "invalid_json", "raw": result.stdout[-2000:]}

    def read_app_file(self, file_name: str, *, timeout: int = 10) -> bytes | None:
        normalized = file_name.strip().lstrip("/")
        if not normalized.startswith("files/") or ".." in Path(normalized).parts:
            raise ValueError(f"invalid app-private file path: {file_name}")
        result = subprocess.run(
            self.base + ["exec-out", "run-as", self.package, "cat", normalized],
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
        )
        return result.stdout if result.returncode == 0 else None

    def remove_file(self, file_name: str) -> None:
        self.run(
            ["shell", "run-as", self.package, "rm", "-f", f"files/{file_name}"],
            check=False,
            timeout=10,
        )


def b64(value: str) -> str:
    return base64.b64encode(value.encode("utf-8")).decode("ascii")


def shell_quote(value: str) -> str:
    return shlex.quote(value)


def json_b64(value: Any) -> str:
    return b64(json.dumps(value, ensure_ascii=False, separators=(",", ":")))


def es(name: str, value: str) -> list[str]:
    return ["--es", name, value]


def ez(name: str, value: bool) -> list[str]:
    return ["--ez", name, "true" if value else "false"]


def ei(name: str, value: int) -> list[str]:
    return ["--ei", name, str(value)]


def el(name: str, value: int | float) -> list[str]:
    return ["--el", name, str(int(value))]


def manual_registration_evidence(payload: dict[str, Any]) -> dict[str, Any]:
    run_log = payload.get("run_log")
    function = payload.get("function")
    canonical_run_log = run_log if isinstance(run_log, dict) else {}
    canonical_function = function if isinstance(function, dict) else {}
    steps = canonical_run_log.get("steps")
    canonical_steps = steps if isinstance(steps, list) else []
    function_id = first_non_blank(canonical_function.get("function_id"))
    recording_success = (
        payload.get("success") is True
        and canonical_run_log.get("schema_version") == "omniflow.canonical_run_log.v1"
        and canonical_run_log.get("status") == "succeeded"
    )
    function_registered = (
        canonical_function.get("schema_version") == "omniflow.function.v2"
        and bool(function_id)
    )
    return {
        "recording_success": recording_success,
        "function_registered": function_registered,
        "function_id": function_id,
        "run_id": first_non_blank(canonical_run_log.get("run_id")),
        "action_count": len(canonical_steps),
    }


def storage_artifact_stem(value: str) -> str:
    normalized = str(value).strip()
    if not normalized:
        raise ValueError("storage artifact id is required")
    safe_part = re.sub(r"[^A-Za-z0-9._-]", "_", normalized)[:80] or "run"
    digest = hashlib.sha256(normalized.encode("utf-8")).hexdigest()[:16]
    return f"{digest}_{safe_part}"


def image_payload_mime_type(payload: bytes) -> str:
    if payload.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if payload.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if len(payload) >= 12 and payload[:4] == b"RIFF" and payload[8:12] == b"WEBP":
        return "image/webp"
    return ""


def historical_runlog_state_ids(run_log: dict[str, Any]) -> list[str]:
    state_ids: list[str] = []
    steps = run_log.get("steps") if isinstance(run_log.get("steps"), list) else []
    candidates = [
        value
        for step in steps
        if isinstance(step, dict)
        for value in (step.get("before_state_id"), step.get("after_state_id"))
    ]
    candidates.append(run_log.get("final_state_id"))
    for value in candidates:
        state_id = first_non_blank(value)
        if state_id and state_id not in state_ids:
            state_ids.append(state_id)
    return state_ids


def archive_historical_run_logs(
    adb: Adb,
    run_ids: list[str],
    out_dir: Path,
) -> dict[str, Any]:
    archive_root = out_dir / "historical_runlogs"
    archive_root.mkdir(parents=True, exist_ok=True)
    normalized_run_ids = list(dict.fromkeys(
        run_id
        for value in run_ids
        if (run_id := first_non_blank(value))
    ))
    archived_runs: list[dict[str, Any]] = []
    missing_artifacts: list[str] = []
    errors: list[str] = []
    archived_state_count = 0

    for run_id in normalized_run_ids:
        run_stem = storage_artifact_stem(run_id)
        run_dir = archive_root / run_stem
        run_dir.mkdir(parents=True, exist_ok=True)
        device_run_path = f"files/run_logs/{run_stem}.json"
        run_bytes = adb.read_app_file(device_run_path)
        run_entry: dict[str, Any] = {
            "run_id": run_id,
            "directory": run_stem,
            "state_ids": [],
            "states": [],
            "rejected_tool_call_count": 0,
            "rejected_tool_call_errors": [],
        }
        run_missing: list[str] = []
        if run_bytes is None:
            missing = f"run:{run_id}:run_log.json"
            run_missing.append(missing)
            missing_artifacts.append(missing)
            run_entry["complete"] = False
            archived_runs.append(run_entry)
            continue
        try:
            run_log = json.loads(run_bytes)
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            errors.append(f"run:{run_id}:invalid_json:{error}")
            run_entry["complete"] = False
            archived_runs.append(run_entry)
            continue
        if not isinstance(run_log, dict):
            errors.append(f"run:{run_id}:canonical_run_log_object_required")
            run_entry["complete"] = False
            archived_runs.append(run_entry)
            continue
        if (
            run_log.get("schema_version") != "omniflow.canonical_run_log.v1"
            or first_non_blank(run_log.get("run_id")) != run_id
        ):
            errors.append(f"run:{run_id}:canonical_run_log_identity_invalid")
            run_entry["complete"] = False
            archived_runs.append(run_entry)
            continue

        (run_dir / "run_log.json").write_bytes(run_bytes)
        events_bytes = adb.read_app_file(f"files/run_logs/{run_stem}.events.ndjson")
        if events_bytes is None:
            missing = f"run:{run_id}:events.ndjson"
            run_missing.append(missing)
            missing_artifacts.append(missing)
        else:
            (run_dir / "events.ndjson").write_bytes(events_bytes)

        state_ids = historical_runlog_state_ids(run_log)
        run_entry["state_ids"] = state_ids
        states_dir = run_dir / "states"
        for state_id in state_ids:
            state_stem = storage_artifact_stem(state_id)
            state_dir = states_dir / state_stem
            state_dir.mkdir(parents=True, exist_ok=True)
            state_entry: dict[str, Any] = {
                "state_id": state_id,
                "directory": f"states/{state_stem}",
                "screenshot_archived": False,
            }
            for extension, destination in (("json", "state.json"), ("xml", "state.xml")):
                state_bytes = adb.read_app_file(
                    f"files/run_logs/states/{state_stem}.{extension}"
                )
                if state_bytes is None:
                    missing = f"run:{run_id}:state:{state_id}:{destination}"
                    run_missing.append(missing)
                    missing_artifacts.append(missing)
                else:
                    (state_dir / destination).write_bytes(state_bytes)
            screenshot = adb.read_app_file(f"files/run_logs/states/{state_stem}.jpg")
            screenshot_mime_type = image_payload_mime_type(screenshot or b"")
            if screenshot is not None and screenshot_mime_type:
                (state_dir / "state.jpg").write_bytes(screenshot)
                state_entry["screenshot_archived"] = True
                state_entry["screenshot_mime_type"] = screenshot_mime_type
            elif screenshot is not None:
                state_entry["screenshot_error"] = "invalid_image_payload"
            run_entry["states"].append(state_entry)
            archived_state_count += 1

        planner = run_log.get("diagnostics")
        planner = planner.get("planner") if isinstance(planner, dict) else None
        rejected_calls = planner.get("rejected_tool_calls") if isinstance(planner, dict) else None
        run_entry["rejected_tool_call_count"] = (
            len(rejected_calls) if isinstance(rejected_calls, list) else 0
        )
        run_entry["rejected_tool_call_errors"] = [
            error
            for item in rejected_calls
            if isinstance(item, dict)
            if (error := first_non_blank(item.get("error")))
        ] if isinstance(rejected_calls, list) else []
        run_entry["complete"] = not run_missing
        archived_runs.append(run_entry)

    result = {
        "success": bool(normalized_run_ids)
        and len(archived_runs) == len(normalized_run_ids)
        and not missing_artifacts
        and not errors,
        "schema_version": "oob.acceptance.runlog_archive.v1",
        "archive_root": str(archive_root),
        "requested_run_count": len(normalized_run_ids),
        "run_count": len(archived_runs),
        "state_count": archived_state_count,
        "runs": archived_runs,
        "missing_artifacts": missing_artifacts,
        "errors": errors,
    }
    (archive_root / "manifest.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    return result


def load_historical_runlog_archive(path: Path) -> dict[str, Any]:
    manifest_path = path / "manifest.json" if path.is_dir() else path
    value = json.loads(manifest_path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("historical_runlog_manifest_object_required")
    if value.get("schema_version") != "oob.acceptance.runlog_archive.v1":
        raise ValueError("historical_runlog_manifest_schema_invalid")
    return {**value, "archive_root": str(manifest_path.parent.resolve())}


def openai_chat_completions_url(base_url: str) -> str:
    normalized = str(base_url or "").strip()
    if not normalized:
        raise ValueError("historical_vlm_provider_base_url_required")
    direct = normalized.endswith("#")
    normalized = normalized[:-1] if direct else normalized
    normalized = normalized.rstrip("/")
    if direct or normalized.endswith("/chat/completions"):
        return normalized
    if re.search(r"/v\d+(?:\.\d+)?$", normalized, flags=re.IGNORECASE):
        return f"{normalized}/chat/completions"
    return f"{normalized}/v1/chat/completions"


def parse_openai_model_turn(
    payload: str,
    *,
    content_type: str,
    requested_model: str,
) -> dict[str, Any]:
    if "text/event-stream" not in content_type.lower():
        value = json.loads(payload)
        if not isinstance(value, dict):
            raise ValueError("historical_vlm_response_object_required")
        choices = value.get("choices") if isinstance(value.get("choices"), list) else []
        choice = choices[0] if choices and isinstance(choices[0], dict) else {}
        message = choice.get("message") if isinstance(choice.get("message"), dict) else {}
        return {
            "requested_model": requested_model,
            "resolved_model": first_non_blank(value.get("model"), requested_model),
            "tool_calls": message.get("tool_calls") if isinstance(message.get("tool_calls"), list) else [],
            "reasoning": first_non_blank(message.get("reasoning_content"), message.get("reasoning")),
            "finish_reason": choice.get("finish_reason"),
            "usage": value.get("usage") if isinstance(value.get("usage"), dict) else {},
        }

    tool_calls: dict[int, dict[str, Any]] = {}
    reasoning_parts: list[str] = []
    usage: dict[str, Any] = {}
    finish_reason: Any = None
    resolved_model = requested_model
    for raw_line in payload.splitlines():
        line = raw_line.strip()
        if not line.startswith("data:"):
            continue
        data = line[5:].strip()
        if not data or data == "[DONE]":
            continue
        chunk = json.loads(data)
        if not isinstance(chunk, dict):
            continue
        resolved_model = first_non_blank(chunk.get("model"), resolved_model)
        if isinstance(chunk.get("usage"), dict):
            usage = dict(chunk["usage"])
        choices = chunk.get("choices") if isinstance(chunk.get("choices"), list) else []
        for choice in choices:
            if not isinstance(choice, dict):
                continue
            if choice.get("finish_reason") is not None:
                finish_reason = choice.get("finish_reason")
            delta = choice.get("delta") if isinstance(choice.get("delta"), dict) else {}
            reasoning = first_non_blank(delta.get("reasoning_content"), delta.get("reasoning"))
            if reasoning:
                reasoning_parts.append(reasoning)
            for raw_tool_call in delta.get("tool_calls") or []:
                if not isinstance(raw_tool_call, dict):
                    continue
                index = int(raw_tool_call.get("index") or 0)
                accumulated = tool_calls.setdefault(
                    index,
                    {
                        "id": "",
                        "type": "function",
                        "function": {"name": "", "arguments": ""},
                    },
                )
                accumulated["id"] += str(raw_tool_call.get("id") or "")
                accumulated["type"] = str(raw_tool_call.get("type") or accumulated["type"])
                function = raw_tool_call.get("function")
                if isinstance(function, dict):
                    accumulated["function"]["name"] += str(function.get("name") or "")
                    accumulated["function"]["arguments"] += str(function.get("arguments") or "")
    return {
        "requested_model": requested_model,
        "resolved_model": resolved_model,
        "tool_calls": [tool_calls[index] for index in sorted(tool_calls)],
        "reasoning": "".join(reasoning_parts),
        "finish_reason": finish_reason,
        "usage": usage,
    }


def request_openai_model_turn(
    request_payload: dict[str, Any],
    *,
    base_url: str,
    api_key: str,
    timeout_seconds: float,
) -> dict[str, Any]:
    headers = {
        "Accept": "text/event-stream",
        "Content-Type": "application/json",
    }
    if api_key.strip():
        headers["Authorization"] = f"Bearer {api_key.strip()}"
    request = urllib_request.Request(
        openai_chat_completions_url(base_url),
        data=json.dumps(request_payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    try:
        with urllib_request.urlopen(request, timeout=timeout_seconds) as response:
            payload = response.read().decode("utf-8")
            content_type = response.headers.get("Content-Type", "")
    except urllib_error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")[-1_000:]
        raise RuntimeError(f"historical_vlm_http_error:{error.code}:{detail}") from error
    except urllib_error.URLError as error:
        raise RuntimeError(f"historical_vlm_connection_failed:{error.reason}") from error
    return parse_openai_model_turn(
        payload,
        content_type=content_type,
        requested_model=str(request_payload.get("model") or ""),
    )


def evaluate_historical_vlm_reselection(
    archive: dict[str, Any],
    *,
    model: str,
    turn_client: Callable[[dict[str, Any]], dict[str, Any]],
    run_ids: set[str] | None = None,
    max_cases: int = 8,
) -> dict[str, Any]:
    archive_root = Path(first_non_blank(archive.get("archive_root")))
    selected_run_ids = {first_non_blank(value) for value in (run_ids or set())}
    cases: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    errors: list[str] = []
    total_rejected_calls = 0
    turn_index = 0
    visible_tools = {
        first_non_blank(item.get("function", {}).get("name"))
        for item in build_model_turn_request(
            goal="historical validation",
            model=model,
            state={"xml": "<hierarchy />"},
            max_steps=1,
            turn_index=0,
        )["tools"]
        if isinstance(item, dict) and isinstance(item.get("function"), dict)
    }
    runs = archive.get("runs") if isinstance(archive.get("runs"), list) else []
    for run_entry in runs:
        if len(cases) >= max(1, int(max_cases)):
            break
        if not isinstance(run_entry, dict) or run_entry.get("complete") is not True:
            continue
        run_id = first_non_blank(run_entry.get("run_id"))
        if selected_run_ids and run_id not in selected_run_ids:
            continue
        run_dir = archive_root / first_non_blank(run_entry.get("directory"))
        try:
            run_log = json.loads((run_dir / "run_log.json").read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            errors.append(f"run:{run_id}:load_failed:{error}")
            continue
        goal = first_non_blank(run_log.get("goal"))
        steps = run_log.get("steps") if isinstance(run_log.get("steps"), list) else []
        recent_actions: list[dict[str, Any]] = []
        for step in sorted(
            (value for value in steps if isinstance(value, dict)),
            key=lambda value: int(value.get("step_index") or 0),
        ):
            if len(cases) >= max(1, int(max_cases)):
                break
            action = step.get("action") if isinstance(step.get("action"), dict) else {}
            result = step.get("result") if isinstance(step.get("result"), dict) else {}
            step_metadata = (
                step.get("metadata") if isinstance(step.get("metadata"), dict) else {}
            )
            expected_tool = first_non_blank(action.get("tool"))
            expected_args = action.get("args") if isinstance(action.get("args"), dict) else {}
            before_state_id = first_non_blank(step.get("before_state_id"))
            function_id = first_non_blank(step_metadata.get("function_id"))
            if function_id:
                skipped.append(
                    {
                        "run_id": run_id,
                        "step_index": step.get("step_index"),
                        "reason": "function_replay_step_not_vlm_selection",
                        "tool": expected_tool,
                        "function_id": function_id,
                    }
                )
                recent_actions.append(
                    {
                        "tool": expected_tool,
                        "args": expected_args,
                        "success": result.get("success") is True,
                        "error": result.get("error"),
                        "function_id": function_id,
                    }
                )
                continue
            if result.get("success") is not True or expected_tool not in visible_tools:
                skipped.append(
                    {
                        "run_id": run_id,
                        "step_index": step.get("step_index"),
                        "reason": "successful_model_visible_step_required",
                        "tool": expected_tool,
                    }
                )
                recent_actions.append(
                    {
                        "tool": expected_tool,
                        "args": expected_args,
                        "success": result.get("success") is True,
                        "error": result.get("error"),
                    }
                )
                continue
            state_dir = run_dir / "states" / storage_artifact_stem(before_state_id)
            try:
                state = json.loads((state_dir / "state.json").read_text(encoding="utf-8"))
                state["xml"] = (state_dir / "state.xml").read_text(encoding="utf-8")
            except (OSError, json.JSONDecodeError) as error:
                errors.append(
                    f"run:{run_id}:step:{step.get('step_index')}:state_load_failed:{error}"
                )
                continue
            state["state_id"] = before_state_id
            screenshot_path = state_dir / "state.jpg"
            if screenshot_path.is_file():
                state["screenshot_path"] = str(screenshot_path.resolve())
                state.pop("image_base64", None)
            extra = state.get("extra") if isinstance(state.get("extra"), dict) else {}
            if recent_actions:
                extra = {**extra, "recent_actions": recent_actions[-8:]}
            state["extra"] = extra

            validation_error = ""
            retry_tool_name = ""
            rejected_tool_call: dict[str, Any] | None = None
            rejected_calls: list[dict[str, Any]] = []
            selected_action: dict[str, Any] | None = None
            metadata: dict[str, Any] = {}
            case_error = ""
            for attempt in range(3):
                turn_index += 1
                request_payload = build_model_turn_request(
                    goal=goal,
                    model=model,
                    state=state,
                    max_steps=max(1, len(steps)),
                    turn_index=turn_index,
                    validation_error=validation_error,
                    retry_tool_name=retry_tool_name,
                    rejected_tool_call=rejected_tool_call,
                )
                try:
                    response = turn_client(request_payload)
                    selected_action, metadata = parse_model_turn_response(
                        response,
                        requested_model=model,
                        turn_index=turn_index,
                    )
                    break
                except ModelToolCallError as error:
                    case_error = str(error)
                    rejected_entry = {
                        "attempt": attempt + 1,
                        "turn_index": turn_index,
                        "tool": error.tool_name or None,
                        "error": str(error),
                    }
                    if error.arguments is not None:
                        rejected_entry["arguments"] = error.arguments
                    rejected_calls.append(rejected_entry)
                    if attempt == 2:
                        break
                    validation_error = str(error)
                    retry_tool_name = error.tool_name
                    rejected_tool_call = {
                        "tool": error.tool_name or None,
                        "arguments": error.arguments,
                    }
                except Exception as error:  # noqa: BLE001
                    case_error = str(error) or type(error).__name__
                    break
            total_rejected_calls += len(rejected_calls)
            schema_valid = selected_action is not None
            tool_match = schema_valid and selected_action.get("tool") == expected_tool
            exact_action_match = schema_valid and selected_action == {
                "tool": expected_tool,
                "args": expected_args,
            }
            cases.append(
                {
                    "run_id": run_id,
                    "step_index": step.get("step_index"),
                    "before_state_id": before_state_id,
                    "expected_action": {"tool": expected_tool, "args": expected_args},
                    "selected_action": selected_action,
                    "schema_valid": schema_valid,
                    "tool_match": tool_match,
                    "exact_action_match": exact_action_match,
                    "summary": metadata.get("summary"),
                    "model_adapter": metadata.get("model_adapter"),
                    "rejected_tool_calls": rejected_calls,
                    "error": case_error or None,
                }
            )
            recent_actions.append(
                {
                    "tool": expected_tool,
                    "args": expected_args,
                    "success": True,
                    "error": result.get("error"),
                }
            )

    schema_valid_count = sum(case["schema_valid"] is True for case in cases)
    tool_match_count = sum(case["tool_match"] is True for case in cases)
    exact_action_match_count = sum(case["exact_action_match"] is True for case in cases)
    return {
        "success": bool(cases)
        and not errors
        and schema_valid_count == len(cases)
        and tool_match_count == len(cases),
        "schema_version": "oob.acceptance.historical_vlm_reselection.v1",
        "model": model,
        "archive_root": str(archive_root),
        "case_count": len(cases),
        "schema_valid_count": schema_valid_count,
        "tool_match_count": tool_match_count,
        "exact_action_match_count": exact_action_match_count,
        "rejected_tool_call_count": total_rejected_calls,
        "cases": cases,
        "skipped": skipped,
        "errors": errors,
    }


def wait_json(adb: Adb, file_name: str, timeout_s: float = 60.0) -> dict[str, Any]:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        payload = adb.read_file_json(file_name)
        if payload is not None:
            return payload
        time.sleep(0.5)
    return {"success": False, "error_code": "RESULT_TIMEOUT", "error_message": f"Timed out waiting for {file_name}"}


def first_non_blank(*values: Any) -> str:
    for value in values:
        if value is None:
            continue
        text = str(value).strip()
        if text:
            return text
    return ""


def deep_contains(value: Any, needle: str) -> bool:
    if isinstance(value, str):
        return needle in value
    if isinstance(value, dict):
        return any(deep_contains(k, needle) or deep_contains(v, needle) for k, v in value.items())
    if isinstance(value, list):
        return any(deep_contains(item, needle) for item in value)
    return False


def deep_find_key(value: Any, key: str) -> list[Any]:
    found: list[Any] = []
    if isinstance(value, dict):
        for k, v in value.items():
            if k == key:
                found.append(v)
            found.extend(deep_find_key(v, key))
    elif isinstance(value, list):
        for item in value:
            found.extend(deep_find_key(item, key))
    return found


def provider_failure_kind(result: dict[str, Any]) -> str:
    candidates: list[str] = []
    for key in ("error_code", "error_message", "message", "phase"):
        raw = result.get(key)
        if raw is not None:
            candidates.append(str(raw))
    outcome = result.get("outcome")
    if isinstance(outcome, dict):
        for key in ("errorCode", "error_code", "errorMessage", "error_message", "message"):
            raw = outcome.get(key)
            if raw is not None:
                candidates.append(str(raw))
    for key in (
        "vlm_provider_failure_kind",
        "vlm_provider_failure_message",
        "vlm_provider_failure_status_code",
    ):
        candidates.extend(str(value) for value in deep_find_key(result, key))
    combined = " ".join(candidates).lower()
    if not combined:
        return ""
    if (
        "provider_auth_or_configuration_failed" in combined
        or "authentication" in combined
        or "unauthorized" in combined
        or "invalid proxy server token" in combined
        or "unable to find token" in combined
        or "api key" in combined
    ):
        return "provider_auth_or_configuration_failed"
    if (
        "provider_network_failed" in combined
        or "unable to resolve host" in combined
        or "failed to connect" in combined
        or "connection refused" in combined
        or "timed out" in combined
    ):
        return "provider_network_failed"
    if "provider_model_or_endpoint_not_found" in combined or "404" in combined:
        return "provider_model_or_endpoint_not_found"
    if "provider_tool_schema_rejected" in combined:
        return "provider_tool_schema_rejected"
    if "provider_streaming_failed" in combined:
        return "provider_streaming_failed"
    if "provider_request_failed" in combined:
        return "provider_request_failed"
    return ""


def function_step_detail_evidence(result: dict[str, Any]) -> tuple[bool, dict[str, Any]]:
    progress_values = [str(value) for value in deep_find_key(result, "progress")]
    step_count_values = deep_find_key(result, "step_count")
    concrete_step_numbers = []
    for key in (
        "current_step_number",
        "current_step_index",
        "completed_step_count",
        "success_step_count",
        "failed_step_index",
    ):
        concrete_step_numbers.extend(deep_find_key(result, key))
    step_summaries = [
        str(value).strip()
        for key in ("summary", "step_summary", "display_name", "action_label")
        for value in deep_find_key(result, key)
        if str(value).strip()
    ]
    evidence = {
        "progress": progress_values[:8],
        "step_count_values": step_count_values[:8],
        "concrete_step_numbers": concrete_step_numbers[:12],
        "step_summaries": step_summaries[:8],
    }
    progress_mentions_steps = any(
        "/" in value or "step" in value.lower() or "步" in value
        for value in progress_values
    )
    has_numbered_step_state = bool(step_count_values) and any(
        str(value).strip() not in ("", "-1")
        for value in concrete_step_numbers
    )
    has_step_summary = any(
        "云端模型执行中" not in summary and len(summary) >= 2
        for summary in step_summaries
    )
    return progress_mentions_steps or has_numbered_step_state or has_step_summary, evidence


def check(name: str, success: bool, evidence: str, details: Any = None) -> dict[str, Any]:
    payload: dict[str, Any] = {"name": name, "success": bool(success), "evidence": evidence}
    if details is not None:
        payload["details"] = details
    return payload


def parse_gesture(raw: str) -> dict[str, str]:
    parts = [part.strip() for part in raw.split(",")]
    if len(parts) not in (2, 4, 5, 6):
        raise ValueError("--manual-gesture expects x,y or x1,y1,x2,y2[,durationMs[,action]]")
    x1, y1 = parts[0], parts[1]
    x2, y2 = (parts[2], parts[3]) if len(parts) >= 4 else (x1, y1)
    duration = parts[4] if len(parts) >= 5 else "80"
    action = parts[5] if len(parts) >= 6 else ("swipe" if (x1, y1) != (x2, y2) else "click")
    return {"x1": x1, "y1": y1, "x2": x2, "y2": y2, "durationMs": duration, "action": action}


def default_manual_gestures(raw_values: list[str]) -> list[str]:
    return raw_values if raw_values else ["360,360"]


def function_stop_spec(
    function_id: str,
    tag: str,
    wait_ms: int,
    source_state_id: str,
) -> dict[str, Any]:
    if not source_state_id.strip():
        raise ValueError("function stop acceptance requires a recorded source_state_id")
    return {
        "schema_version": "omniflow.function.v2",
        "function_id": function_id,
        "name": f"PR 验收停止复用指令 {tag}",
        "description": f"用于验证 Function 执行中 stop 端口的长等待复用指令 {tag}",
        "input_schema": {
            "type": "object",
            "properties": {},
            "required": [],
            "additionalProperties": False,
        },
        "bindings": [],
        "steps": [
            {
                "step_index": 0,
                "source_state_id": source_state_id,
                "action": {
                    "tool": "wait",
                    "args": {"duration_ms": wait_ms},
                }
            }
        ],
        "checker_rules": [],
        "agent_visible": False,
    }


def launch_package(adb: Adb, package_name: str, wait_s: float = 1.0) -> None:
    adb.run(["shell", "monkey", "-p", package_name, "-c", "android.intent.category.LAUNCHER", "1"], check=False)
    time.sleep(wait_s)


def has_focused_edit_text(xml: str) -> bool:
    return any(
        node.attrib.get("class") == "android.widget.EditText"
        and node.attrib.get("focused") == "true"
        for node in ui_nodes(xml)
    )


def settings_search_target(xml: str) -> tuple[int, int] | None:
    nodes = ui_nodes(xml)
    edit_text = next(
        (
            node
            for node in nodes
            if node.attrib.get("class") == "android.widget.EditText"
            and node.attrib.get("enabled") != "false"
        ),
        None,
    )
    if edit_text is not None:
        return bounds_center(edit_text.attrib.get("bounds", ""))

    search_tokens = ("search", "搜索")
    for node in nodes:
        if node.attrib.get("enabled") == "false":
            continue
        searchable_text = " ".join(
            node.attrib.get(key, "")
            for key in ("resource-id", "text", "content-desc", "hint")
        ).lower()
        if not any(token in searchable_text for token in search_tokens):
            continue
        center = bounds_center(node.attrib.get("bounds", ""))
        if center is not None:
            return center
    return None


def ui_nodes(xml: str) -> list[ElementTree.Element]:
    try:
        return list(ElementTree.fromstring(xml).iter("node"))
    except ElementTree.ParseError:
        return []


def bounds_center(raw: str) -> tuple[int, int] | None:
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", raw.strip())
    if match is None:
        return None
    left, top, right, bottom = (int(value) for value in match.groups())
    if right <= left or bottom <= top:
        return None
    return (left + right) // 2, (top + bottom) // 2


def focused_settings_search(adb: Adb) -> bool:
    adb.run(["shell", "am", "start", "-a", "android.settings.SETTINGS"], check=False)
    time.sleep(1.0)
    adb.shell(["uiautomator", "dump", "/sdcard/oob-settings.xml"], check=False)
    xml = adb.shell(["cat", "/sdcard/oob-settings.xml"], check=False)
    if has_focused_edit_text(xml):
        return True
    target = settings_search_target(xml)
    if target is None:
        return False
    adb.shell(["input", "tap", str(target[0]), str(target[1])])
    time.sleep(1.0)
    adb.shell(["uiautomator", "dump", "/sdcard/oob-settings-search.xml"], check=False)
    focused_xml = adb.shell(["cat", "/sdcard/oob-settings-search.xml"], check=False)
    return has_focused_edit_text(focused_xml)


def current_ui_xml(adb: Adb) -> str:
    adb.shell(["uiautomator", "dump", "/sdcard/oob-current-ui.xml"], check=False)
    return adb.shell(["cat", "/sdcard/oob-current-ui.xml"], check=False)


def semantic_binding_evidence(function: Any) -> dict[str, Any]:
    canonical = function if isinstance(function, dict) else {}
    schema = canonical.get("input_schema") if isinstance(canonical.get("input_schema"), dict) else {}
    properties = schema.get("properties") if isinstance(schema.get("properties"), dict) else {}
    required = schema.get("required") if isinstance(schema.get("required"), list) else []
    bindings = canonical.get("bindings") if isinstance(canonical.get("bindings"), list) else []
    expected_binding = {
        "source": "$.arguments.query",
        "target": "$.steps[0].action.args.text",
    }
    return {
        "query_property": isinstance(properties.get("query"), dict),
        "query_required": "query" in required,
        "binding_found": expected_binding in bindings,
        "bindings": bindings,
        "input_schema": schema,
    }


def maybe_configure_provider(adb: Adb, args: argparse.Namespace) -> dict[str, Any] | None:
    if not args.provider_base_url or not args.provider_api_key or not args.provider_model:
        return None
    adb.remove_file("debug-model-provider-config-result.json")
    adb.broadcast(
        ACTION_PROVIDER_CONFIG,
        [
            *es("baseUrl", args.provider_base_url),
            *es("apiKey", args.provider_api_key),
            *es("modelId", args.provider_model),
            *es("profileId", args.provider_profile_id),
            *es("name", args.provider_name),
            *es("sceneIds", args.provider_scene_ids),
            *es("wireApi", args.provider_wire_api),
        ],
        timeout=20,
    )
    return wait_json(adb, "debug-model-provider-config-result.json", args.short_timeout)


def ensure_provider_profile_id(args: argparse.Namespace) -> str:
    profile_id = str(getattr(args, "provider_profile_id", "") or "").strip()
    if not profile_id:
        profile_id = f"oob-pr-acceptance-{os.getpid()}-{time.time_ns()}"
        args.provider_profile_id = profile_id
    return profile_id


def provider_state_operation(adb: Adb, args: argparse.Namespace, operation: str) -> dict[str, Any]:
    adb.remove_file("debug-model-provider-config-result.json")
    adb.broadcast(
        ACTION_PROVIDER_CONFIG,
        [
            *es("operation", operation),
            *es("profileId", args.provider_profile_id),
            *es("sceneIds", args.provider_scene_ids),
        ],
        timeout=20,
    )
    return wait_json(adb, "debug-model-provider-config-result.json", args.short_timeout)


def start_mock_provider_if_requested(adb: Adb, args: argparse.Namespace) -> MockVlmProvider | None:
    if not args.mock_provider:
        return None
    ensure_provider_profile_id(args)
    provider = MockVlmProvider(
        port=args.mock_provider_port,
        model=args.mock_provider_model,
        stop_delay_seconds=max(args.stop_delay_seconds + 2.0, 4.0),
    )
    provider.start(adb)
    args.provider_base_url = provider.device_base_url
    args.provider_api_key = "oob-acceptance-mock-key"
    args.provider_model = provider.model
    if not args.provider_name:
        args.provider_name = "Provider 1"
    return provider


@dataclass
class DeviceProxySnapshot:
    http_proxy: str
    proxy_host: str
    proxy_port: str


def capture_device_proxy(adb: Adb) -> DeviceProxySnapshot:
    return DeviceProxySnapshot(
        http_proxy=adb.get_global_setting("http_proxy"),
        proxy_host=adb.get_global_setting("global_http_proxy_host"),
        proxy_port=adb.get_global_setting("global_http_proxy_port"),
    )


def clear_device_proxy_for_mock_provider(adb: Adb) -> None:
    adb.put_global_setting("http_proxy", ":0")
    adb.delete_global_setting("global_http_proxy_host")
    adb.delete_global_setting("global_http_proxy_port")


def restore_device_proxy(adb: Adb, snapshot: DeviceProxySnapshot) -> None:
    if snapshot.http_proxy and snapshot.http_proxy != "null":
        adb.put_global_setting("http_proxy", snapshot.http_proxy)
    else:
        adb.put_global_setting("http_proxy", ":0")
    restore_or_delete_setting(adb, "global_http_proxy_host", snapshot.proxy_host)
    restore_or_delete_setting(adb, "global_http_proxy_port", snapshot.proxy_port)


def restore_or_delete_setting(adb: Adb, name: str, value: str) -> None:
    if value and value != "null":
        adb.put_global_setting(name, value)
    else:
        adb.delete_global_setting(name)


def run_acceptance(args: argparse.Namespace) -> dict[str, Any]:
    adb = Adb(serial=args.device, package=args.package)
    proxy_snapshot = capture_device_proxy(adb) if args.mock_provider else None
    mock_provider = None
    provider_snapshot = None
    if proxy_snapshot is not None:
        clear_device_proxy_for_mock_provider(adb)
    try:
        mock_provider = start_mock_provider_if_requested(adb, args)
        will_configure_provider = bool(
            args.provider_base_url and args.provider_api_key and args.provider_model
        )
        if will_configure_provider:
            ensure_provider_profile_id(args)
            provider_snapshot = provider_state_operation(adb, args, "snapshot")
            if provider_snapshot.get("success") is not True:
                raise RuntimeError(f"provider state snapshot failed: {provider_snapshot}")
        return _run_acceptance(args=args, adb=adb, mock_provider=mock_provider)
    finally:
        try:
            if mock_provider is not None:
                mock_provider.stop(adb)
        finally:
            try:
                if provider_snapshot is not None:
                    restored = provider_state_operation(adb, args, "restore")
                    if restored.get("success") is not True or restored.get("restored") is not True:
                        raise RuntimeError(f"provider state restore failed: {restored}")
            finally:
                if proxy_snapshot is not None:
                    restore_device_proxy(adb, proxy_snapshot)


def _run_acceptance(
    *,
    args: argparse.Namespace,
    adb: Adb,
    mock_provider: MockVlmProvider | None,
) -> dict[str, Any]:
    started_at = time.time()
    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    unique_tag = args.unique_tag or f"pr_accept_{timestamp}_{args.device or 'adb'}"
    out_dir = Path(args.out_dir or f"runtime/pr_acceptance/{timestamp}")
    out_dir.mkdir(parents=True, exist_ok=True)
    function_name = args.function_name.format(tag=unique_tag)
    function_description = args.function_description.format(tag=unique_tag)
    replay_goal = args.replay_goal.format(tag=unique_tag)
    vlm_goal = args.vlm_goal.format(tag=unique_tag)
    reselection_goal = args.reselection_goal.format(tag=unique_tag)
    stop_goal = args.stop_goal.format(tag=unique_tag)

    artifacts: dict[str, str] = {}
    results: dict[str, Any] = {}
    checks: list[dict[str, Any]] = []

    def save_artifact(name: str, payload: Any) -> None:
        path = out_dir / f"{name}.json"
        path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        artifacts[name] = str(path)

    def clean(file_name: str) -> None:
        adb.remove_file(file_name)

    provider_config = maybe_configure_provider(adb, args)
    if provider_config is not None:
        results["provider_config"] = provider_config
        save_artifact("provider_config", provider_config)
        details = compact_result(provider_config)
        if mock_provider is not None:
            details = {
                **details,
                "mock_provider": {
                    "base_url": mock_provider.device_base_url,
                    "model": mock_provider.model,
                    "reverse_enabled": mock_provider.reverse_enabled,
                },
            }
        configured_profile = provider_config.get("profile")
        configured_profile_id = first_non_blank(
            configured_profile.get("id") if isinstance(configured_profile, dict) else ""
        )
        details = {
            **details,
            "requested_profile_id": args.provider_profile_id,
            "configured_profile_id": configured_profile_id,
        }
        checks.append(check(
            "provider_config",
            provider_config.get("success") is True
            and configured_profile_id == args.provider_profile_id,
            "Configured VLM provider for online recall acceptance.",
            details,
        ))

    # 1. Manual recording start/stop/generate RunLog. The user may either pass
    # --manual-gesture or interact with the device during --manual-window-seconds.
    if args.prelaunch_package:
        launch_package(adb, args.prelaunch_package, args.prelaunch_wait_seconds)
    clean("debug-human-run-recording-start.json")
    clean("debug-human-run-recording-result.json")
    clean("debug-human-run-recording-gesture.json")
    adb.broadcast(
        ACTION_HUMAN_RECORDING,
        [
            *es("op", "start"),
            *es("nameBase64", b64(function_name)),
            *es("descriptionBase64", b64(function_description)),
        ],
    )
    manual_start = wait_json(adb, "debug-human-run-recording-start.json", args.short_timeout)
    results["manual_start"] = manual_start
    save_artifact("manual_start", manual_start)

    for raw_gesture in default_manual_gestures(args.manual_gesture):
        gesture = parse_gesture(raw_gesture)
        clean("debug-human-run-recording-gesture.json")
        adb.broadcast(
            ACTION_HUMAN_RECORDING,
            [
                *es("op", "gesture"),
                *es("action", gesture["action"]),
                *es("x1", gesture["x1"]),
                *es("y1", gesture["y1"]),
                *es("x2", gesture["x2"]),
                *es("y2", gesture["y2"]),
                *es("durationMs", gesture["durationMs"]),
            ],
        )
        gesture_result = wait_json(adb, "debug-human-run-recording-gesture.json", args.short_timeout)
        results.setdefault("manual_gestures", []).append(gesture_result)
        save_artifact(f"manual_gesture_{len(results['manual_gestures'])}", gesture_result)

    if not default_manual_gestures(args.manual_gesture) and args.manual_window_seconds > 0:
        print(
            f"Manual recording is active for {args.manual_window_seconds}s. "
            "Perform one simple action on the device now.",
            flush=True,
        )
        time.sleep(args.manual_window_seconds)

    clean("debug-human-run-recording-result.json")
    adb.broadcast(ACTION_HUMAN_RECORDING, [*es("op", "finish")], timeout=10)
    manual_result = wait_json(adb, "debug-human-run-recording-result.json", args.long_timeout)
    results["manual_result"] = manual_result
    save_artifact("manual_result", manual_result)
    registration = manual_registration_evidence(manual_result)
    function_id = registration["function_id"]
    run_id = registration["run_id"]
    action_count = registration["action_count"]
    recorded_function = manual_result.get("function")
    recorded_steps = recorded_function.get("steps", []) if isinstance(recorded_function, dict) else []
    source_state_id = str(
        recorded_steps[0].get("source_state_id", "")
        if recorded_steps and isinstance(recorded_steps[0], dict)
        else ""
    ).strip()
    manual_ok = (
        manual_start.get("success") is True
        and registration["recording_success"] is True
        and registration["function_registered"] is True
        and function_id
        and action_count > 0
    )
    checks.append(check(
        "manual_recording",
        bool(manual_ok),
        "DebugHumanRunRecordingReceiver start/finish generated a RunLog and registered a Function.",
        {"run_id": run_id, "function_id": function_id, "action_count": action_count},
    ))
    checks.append(check(
        "convert_register_replay",
        bool(registration["function_registered"] is True and function_id),
        "Manual RunLog conversion returned a registered Function id.",
        {"function_id": function_id, "function": compact_result(manual_result.get("function"))},
    ))

    # 2. Enhancement/update.
    clean("debug-oob-function-update-result.json")
    adb.broadcast(
        ACTION_FUNCTION_UPDATE,
        [
            *es("functionId", function_id),
            *es("runId", run_id),
            *es("mode", "enhance"),
            *ez("dryRun", False),
        ],
    )
    update_result = wait_json(adb, "debug-oob-function-update-result.json", args.long_timeout)
    results["function_update"] = update_result
    save_artifact("function_update", update_result)
    checks.append(check(
        "function_update_enhance",
        update_result.get("success") is True
        and update_result.get("after_found") is True
        and update_result.get("changed") is True
        and update_result.get("saved") is True
        and update_result.get("hash_changed") is True,
        "DebugFunctionUpdateReceiver applied an update_function enhancement patch.",
        compact_result(update_result),
    ))

    # 3. Direct Function run.
    if args.prelaunch_package:
        launch_package(adb, args.prelaunch_package, args.prelaunch_wait_seconds)
    clean("debug-oob-function-run-result.json")
    adb.broadcast(
        ACTION_FUNCTION_RUN,
        [
            *es("functionId", function_id),
            *es("goalBase64", b64(replay_goal)),
            *es("argumentsBase64", json_b64(args.arguments)),
        ],
    )
    direct_run = wait_json(adb, "debug-oob-function-run-result.json", args.long_timeout)
    results["direct_function_run"] = direct_run
    save_artifact("direct_function_run", direct_run)
    checks.append(check(
        "direct_function_run",
        direct_run.get("success") is True,
        "DebugFunctionRunReceiver executed the registered Function directly.",
        compact_result(direct_run),
    ))
    checks.append(check(
        "no_no_anchor_match",
        not deep_contains(direct_run, "no_anchor_match")
        and not deep_contains(direct_run, "OOB_FUNCTION_SOURCE_NOT_REACHED"),
        "Direct Function run result did not contain no_anchor_match or OOB_FUNCTION_SOURCE_NOT_REACHED.",
        {"success": direct_run.get("success"), "error_code": direct_run.get("error_code")},
    ))
    step_detail_ok, step_detail_evidence = function_step_detail_evidence(direct_run)
    checks.append(check(
        "function_progress_step_detail",
        step_detail_ok,
        "Function result/progress includes step-level detail instead of only a generic cloud-model message.",
        step_detail_evidence,
    ))

    # 4. Semantic input parameter binding and replay.
    semantic_tag = f"semantic_{timestamp.replace('-', '_')}"
    semantic_original_text = f"old_{timestamp.replace('-', '')}"
    semantic_replay_text = f"new_{timestamp.replace('-', '')}"
    semantic_focus_ready = focused_settings_search(adb)
    semantic_start: dict[str, Any] = {}
    semantic_input: dict[str, Any] = {}
    semantic_recording: dict[str, Any] = {}
    semantic_update: dict[str, Any] = {}
    semantic_run: dict[str, Any] = {}
    semantic_ui_text_found = False
    if semantic_focus_ready:
        clean("debug-human-run-recording-start.json")
        clean("debug-human-run-recording-result.json")
        adb.broadcast(
            ACTION_HUMAN_RECORDING,
            [
                *es("op", "start"),
                *es("nameBase64", b64(f"输入搜索词 {semantic_tag}")),
                *es("descriptionBase64", b64("在设置搜索框输入可替换的搜索词")),
            ],
        )
        semantic_start = wait_json(
            adb,
            "debug-human-run-recording-start.json",
            args.short_timeout,
        )
        clean("debug-human-run-recording-result.json")
        adb.broadcast(
            ACTION_HUMAN_RECORDING,
            [
                *es("op", "input_text"),
                *es("textBase64", b64(semantic_original_text)),
            ],
        )
        semantic_input = wait_json(
            adb,
            "debug-human-run-recording-result.json",
            args.short_timeout,
        )
        clean("debug-human-run-recording-result.json")
        adb.broadcast(ACTION_HUMAN_RECORDING, [*es("op", "finish")], timeout=10)
        semantic_recording = wait_json(
            adb,
            "debug-human-run-recording-result.json",
            args.long_timeout,
        )
        semantic_registration = manual_registration_evidence(semantic_recording)
        semantic_function_id = semantic_registration["function_id"]
        semantic_run_id = semantic_registration["run_id"]
        if semantic_function_id and semantic_run_id:
            clean("debug-oob-function-update-result.json")
            adb.broadcast(
                ACTION_FUNCTION_UPDATE,
                [
                    *es("functionId", semantic_function_id),
                    *es("runId", semantic_run_id),
                    *es("mode", "enhance"),
                    *ez("dryRun", False),
                ],
            )
            semantic_update = wait_json(
                adb,
                "debug-oob-function-update-result.json",
                args.long_timeout,
            )
            updated = semantic_update.get("update")
            updated_function = (
                updated.get("updated_function")
                if isinstance(updated, dict)
                else None
            )
            binding = semantic_binding_evidence(updated_function)
            if focused_settings_search(adb):
                clean("debug-oob-function-run-result.json")
                adb.broadcast(
                    ACTION_FUNCTION_RUN,
                    [
                        *es("functionId", semantic_function_id),
                        *es("goalBase64", b64("输入新的设置搜索词")),
                        *es(
                            "argumentsBase64",
                            json_b64({"query": semantic_replay_text}),
                        ),
                    ],
                )
                semantic_run = wait_json(
                    adb,
                    "debug-oob-function-run-result.json",
                    args.long_timeout,
                )
                semantic_ui_text_found = (
                    f'text="{semantic_replay_text}"' in current_ui_xml(adb)
                )
        else:
            binding = semantic_binding_evidence(None)
    else:
        binding = semantic_binding_evidence(None)
    semantic_result = {
        "focus_ready": semantic_focus_ready,
        "start": semantic_start,
        "input": semantic_input,
        "recording": semantic_recording,
        "update": semantic_update,
        "run": semantic_run,
        "binding": binding,
        "replay_text": semantic_replay_text,
        "ui_text_found": semantic_ui_text_found,
    }
    results["semantic_parameter_binding"] = semantic_result
    save_artifact("semantic_parameter_binding", semantic_result)
    checks.append(check(
        "semantic_parameter_binding",
        semantic_focus_ready
        and semantic_input.get("success") is True
        and binding["query_property"]
        and binding["query_required"]
        and binding["binding_found"],
        "Recorded input_text enhancement produced canonical query binding.",
        compact_result(semantic_result),
    ))
    checks.append(check(
        "semantic_parameter_replay",
        semantic_run.get("success") is True and semantic_ui_text_found,
        "Function replay bound a new query value and entered it on the device.",
        {
            "success": semantic_run.get("success"),
            "replay_text": semantic_replay_text,
            "ui_text_found": semantic_ui_text_found,
        },
    ))

    # 5. The GUI task should see and run a Function. This is intentionally strict:
    # if the model does not call the recalled Function, the PR is not accepted.
    if args.prelaunch_package:
        launch_package(adb, args.prelaunch_package, args.prelaunch_wait_seconds)
    clean("debug-gui-task-result.json")
    adb.broadcast(
        ACTION_GUI_TASK,
        [
            *es("goalBase64", b64(vlm_goal)),
            *el("waitMs", args.vlm_timeout * 1_000),
            *es("profileId", args.provider_profile_id),
            *es("modelId", args.provider_model),
        ],
    )
    vlm_result = wait_json(adb, "debug-gui-task-result.json", args.vlm_timeout)
    results["vlm_recall_run"] = vlm_result
    save_artifact("vlm_recall_run", vlm_result)

    mock_probe_request_start = 0
    if mock_provider is not None:
        mock_probe_request_start = mock_provider.snapshot()["request_count"]
        mock_provider.reset_gui_argument_probe(reselection_goal)
    if args.prelaunch_package:
        launch_package(adb, args.prelaunch_package, args.prelaunch_wait_seconds)
    clean("debug-gui-task-result.json")
    adb.broadcast(
        ACTION_GUI_TASK,
        [
            *es("goalBase64", b64(reselection_goal)),
            *el("waitMs", args.vlm_timeout * 1_000),
            *es("profileId", args.provider_profile_id),
            *es("modelId", args.provider_model),
            *ez("disableFunctionRecall", True),
        ],
    )
    reselection_result = wait_json(adb, "debug-gui-task-result.json", args.vlm_timeout)
    results["vlm_argument_reselection_run"] = reselection_result
    save_artifact("vlm_argument_reselection_run", reselection_result)
    reselection_run_ids = {
        run_id
        for run in reselection_result.get("gui_runs", [])
        if isinstance(run, dict)
        if (run_id := first_non_blank(run.get("run_id")))
    }
    gui_run_ids = list(dict.fromkeys(
        run_id
        for gui_result in (vlm_result, reselection_result)
        for run in gui_result.get("gui_runs", [])
        if isinstance(run, dict)
        if (run_id := first_non_blank(run.get("run_id")))
    ))
    historical_archive = archive_historical_run_logs(adb, gui_run_ids, out_dir)
    results["vlm_runlog_archive"] = historical_archive
    save_artifact("vlm_runlog_archive", historical_archive)
    recalled_function_ids = sorted({
        str(function_id).strip()
        for run in vlm_result.get("gui_runs", [])
        if isinstance(run, dict)
        for function_id in run.get("function_ids", [])
        if str(function_id).strip()
    })
    vlm_provider_failure = provider_failure_kind(vlm_result)
    reselection_provider_failure = provider_failure_kind(reselection_result)
    mock_snapshot_after_vlm = mock_provider.snapshot() if mock_provider is not None else None
    if mock_snapshot_after_vlm is not None:
        results["mock_provider_after_vlm"] = mock_snapshot_after_vlm
        save_artifact("mock_provider_after_vlm", mock_snapshot_after_vlm)
    rejected_tool_call_count = sum(
        int(run.get("rejected_tool_call_count") or 0)
        for run in historical_archive.get("runs", [])
        if isinstance(run, dict)
        if run.get("run_id") in reselection_run_ids
    )
    rejected_tool_call_errors = {
        first_non_blank(error)
        for run in historical_archive.get("runs", [])
        if isinstance(run, dict) and run.get("run_id") in reselection_run_ids
        for error in run.get("rejected_tool_call_errors", [])
        if first_non_blank(error)
    }
    mock_probe_requests = (mock_snapshot_after_vlm or {}).get("requests", [])[mock_probe_request_start:]
    injected_invalid_call_count = sum(
        request.get("invalid_gui_args_injected") is True
        for request in mock_probe_requests
        if isinstance(request, dict)
    )
    historical_reselection: dict[str, Any]
    if historical_archive.get("success") is not True:
        historical_reselection = {
            "success": False,
            "schema_version": "oob.acceptance.historical_vlm_reselection.v1",
            "errors": ["historical_runlog_archive_incomplete"],
        }
    elif args.provider_wire_api.strip().lower() not in {"chat_completions", "chat-completions"}:
        historical_reselection = {
            "success": False,
            "schema_version": "oob.acceptance.historical_vlm_reselection.v1",
            "errors": [f"historical_vlm_wire_api_unsupported:{args.provider_wire_api}"],
        }
    elif not args.provider_base_url or not args.provider_model:
        historical_reselection = {
            "success": False,
            "schema_version": "oob.acceptance.historical_vlm_reselection.v1",
            "errors": ["historical_vlm_explicit_provider_required"],
        }
    else:
        if mock_provider is not None:
            mock_provider.reset_gui_argument_probe()
        historical_provider_base_url = (
            mock_provider.host_base_url
            if mock_provider is not None
            else args.provider_base_url
        )
        try:
            historical_reselection = evaluate_historical_vlm_reselection(
                historical_archive,
                model=args.provider_model,
                turn_client=lambda request_payload: request_openai_model_turn(
                    request_payload,
                    base_url=historical_provider_base_url,
                    api_key=args.provider_api_key,
                    timeout_seconds=args.vlm_timeout,
                ),
                run_ids=reselection_run_ids,
                max_cases=args.historical_reselection_max_cases,
            )
        except Exception as error:  # noqa: BLE001
            historical_reselection = {
                "success": False,
                "schema_version": "oob.acceptance.historical_vlm_reselection.v1",
                "errors": [str(error) or type(error).__name__],
            }
    results["historical_vlm_reselection"] = historical_reselection
    save_artifact("historical_vlm_reselection", historical_reselection)
    if mock_provider is not None:
        mock_snapshot_after_historical_reselection = mock_provider.snapshot()
        results["mock_provider_after_historical_reselection"] = (
            mock_snapshot_after_historical_reselection
        )
        save_artifact(
            "mock_provider_after_historical_reselection",
            mock_snapshot_after_historical_reselection,
        )
    checks.append(check(
        "historical_runlog_archive",
        historical_archive["success"] is True,
        "Archived each GUI RunLog, event stream, and referenced state for historical VLM reselection.",
        historical_archive,
    ))
    checks.append(check(
        "historical_vlm_reselection",
        historical_reselection.get("success") is True
        and (
            mock_provider is None
            or int(historical_reselection.get("rejected_tool_call_count") or 0) >= 1
        ),
        "Re-ran the canonical VLM planner on archived before-state evidence without dispatching an Android action.",
        historical_reselection,
    ))
    checks.append(check(
        "vlm_argument_reselection",
        reselection_result.get("success") is True
        and reselection_result.get("function_recall_disabled") is True
        and reselection_provider_failure == ""
        and historical_archive["success"] is True
        and (
            mock_provider is None
            or (
                injected_invalid_call_count == 1
                and rejected_tool_call_count >= 1
                and "canonical_action_arg_type_invalid:x" in rejected_tool_call_errors
            )
        ),
        "The GUI planner rejected invalid canonical arguments and reselected without argument repair.",
        {
            "mock_invalid_call_count": injected_invalid_call_count,
            "rejected_tool_call_count": rejected_tool_call_count,
            "rejected_tool_call_errors": sorted(rejected_tool_call_errors),
            "run_success": reselection_result.get("success"),
            "function_recall_disabled": reselection_result.get("function_recall_disabled"),
            "provider_failure_kind": reselection_provider_failure,
        },
    ))
    checks.append(check(
        "function_recall",
        bool(recalled_function_ids),
        "The canonical GUI RunLog records a recalled Function id.",
        compact_result(vlm_result) | {"function_ids": recalled_function_ids},
    ))
    checks.append(check(
        "vlm_recall_function_run",
        vlm_result.get("success") is True and bool(recalled_function_ids),
        "DebugGuiTaskReceiver ran the canonical Agent + GUI task path.",
        compact_result(vlm_result) | {
            "provider_failure_kind": vlm_provider_failure,
            "effective_binding": vlm_result.get("effective_binding"),
            "function_recall": (vlm_result.get("outcome") or {}).get("functionRecall")
                if isinstance(vlm_result.get("outcome"), dict) else None,
            "mock_provider": {
                "request_count": mock_snapshot_after_vlm.get("request_count"),
                "last_request": mock_snapshot_after_vlm.get("requests", [])[-1]
                    if mock_snapshot_after_vlm and mock_snapshot_after_vlm.get("requests") else None,
            } if mock_snapshot_after_vlm else None,
        },
    ))
    checks.append(check(
        "vlm_provider_ready",
        vlm_provider_failure == "",
        "VLM provider did not fail before the recall/function execution decision.",
        {
            "provider_failure_kind": vlm_provider_failure,
            "effective_binding": vlm_result.get("effective_binding"),
            "configured_binding": vlm_result.get("configured_binding"),
        },
    ))

    # 6. Function stop port. Import a long-running wait Function, start it, then
    # stop through the same frontend running-task port the user taps.
    stop_function_id = f"oob_fn_stop_acceptance_{unique_tag}".replace("-", "_")
    clean("debug-oob-function-import-result.json")
    adb.broadcast(
        ACTION_FUNCTION_IMPORT,
        [
            *es(
                "functionBase64",
                json_b64(
                    function_stop_spec(
                        stop_function_id,
                        unique_tag,
                        args.function_stop_wait_ms,
                        source_state_id,
                    )
                ),
            ),
        ],
    )
    function_stop_import = wait_json(
        adb,
        "debug-oob-function-import-result.json",
        args.short_timeout,
    )
    results["function_stop_import"] = function_stop_import
    save_artifact("function_stop_import", function_stop_import)

    clean("debug-oob-function-run-result.json")
    clean("debug-agent-cancel-result.json")
    adb.broadcast(
        ACTION_FUNCTION_RUN,
        [
            *es("functionId", stop_function_id),
            *es("goalBase64", b64(f"启动一个会被停止的复用指令 {unique_tag}")),
            *es("argumentsBase64", json_b64({})),
        ],
    )
    time.sleep(args.stop_delay_seconds)
    adb.broadcast(ACTION_CANCEL, [*es("reason", "pr_acceptance_function_stop")])
    function_cancel_result = wait_json(adb, "debug-agent-cancel-result.json", args.short_timeout)
    function_stop_run = wait_json(adb, "debug-oob-function-run-result.json", args.long_timeout)
    results["function_stop_cancel"] = function_cancel_result
    results["function_stop_run"] = function_stop_run
    save_artifact("function_stop_cancel", function_cancel_result)
    save_artifact("function_stop_run", function_stop_run)
    function_stop_blob = json.dumps(function_stop_run, ensure_ascii=False)
    checks.append(check(
        "function_stop_port",
        function_stop_import.get("success") is True
        and function_cancel_result.get("success") is True
        and (
            function_cancel_result.get("running_stop_requested") is True
            or function_cancel_result.get("cancel_mode") == "current_agent_task"
        )
        and function_stop_run.get("success") is not True
        and (
            function_stop_run.get("error_code") == "OOB_FUNCTION_STOPPED"
            or deep_contains(function_stop_run, "OOB_FUNCTION_STOPPED")
            or deep_contains(function_stop_run, "任务已停止")
            or "stopped" in function_stop_blob.lower()
        ),
        "A running Function replay accepted the same stop port and returned a stopped result.",
        {
            "function_id": stop_function_id,
            "import": compact_result(function_stop_import),
            "cancel": function_cancel_result,
            "run": compact_result(function_stop_run),
        },
    ))

    # 7. GUI task stop port. Start a long GUI task and cancel active work through the
    # same debug stop port used for Agent and Function sessions.
    if args.prelaunch_package:
        launch_package(adb, args.prelaunch_package, args.prelaunch_wait_seconds)
    clean("debug-gui-task-result.json")
    clean("debug-agent-cancel-result.json")
    adb.broadcast(
        ACTION_GUI_TASK,
        [
            *es("goalBase64", b64(stop_goal)),
            *el("waitMs", args.vlm_timeout * 1_000),
            *es("profileId", args.provider_profile_id),
            *es("modelId", args.provider_model),
        ],
    )
    time.sleep(args.stop_delay_seconds)
    adb.broadcast(ACTION_CANCEL, [*es("reason", "pr_acceptance_stop_port")])
    cancel_result = wait_json(adb, "debug-agent-cancel-result.json", args.short_timeout)
    results["stop_port"] = cancel_result
    save_artifact("stop_port", cancel_result)
    checks.append(check(
        "stop_port",
        cancel_result.get("success") is True
        and (
            cancel_result.get("vlm_cancel_requested") is True
            or cancel_result.get("function_stop_requested") is True
            or cancel_result.get("running_stop_requested") is True
            or cancel_result.get("cancel_mode") == "current_agent_task"
        ),
        "DebugAgentCancelReceiver requested cancellation through the unified Agent/Function stop port.",
        cancel_result,
    ))

    if mock_provider is not None:
        mock_summary = mock_provider.snapshot()
        results["mock_provider"] = mock_summary
        save_artifact("mock_provider", mock_summary)

    success = all(item["success"] for item in checks)
    summary = {
        "success": success,
        "package": args.package,
        "device": args.device,
        "unique_tag": unique_tag,
        "started_at_ms": int(started_at * 1000),
        "ended_at_ms": int(time.time() * 1000),
        "function_id": function_id,
        "run_id": run_id,
        "checks": checks,
        "artifacts": artifacts,
    }
    save_artifact("summary", summary)
    return summary


def compact_result(value: Any) -> Any:
    if not isinstance(value, dict):
        return value
    keys = (
        "success",
        "accepted",
        "registered",
        "function_id",
        "created_function_id",
        "run_id",
        "error_code",
        "error_message",
        "phase",
        "step_count",
        "success_step_count",
        "failed_step_index",
        "current_step_index",
        "pre_replay_observation",
        "outcome",
        "retrieval_state",
        "count",
    )
    return {key: value[key] for key in keys if key in value}


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", help="adb serial; defaults to adb's selected device")
    parser.add_argument("--package", default=PACKAGE)
    parser.add_argument("--out-dir", help="output directory; default runtime/pr_acceptance/<timestamp>")
    parser.add_argument("--unique-tag", help="unique token inserted into Function names/goals; defaults to timestamp")
    parser.add_argument("--function-name", default="PR 验收复用指令 {tag}")
    parser.add_argument("--function-description", default="PR 验收 {tag}：录制一个简单动作并复用执行")
    parser.add_argument("--replay-goal", default="执行刚注册的复用指令 {tag}")
    parser.add_argument("--vlm-goal", default="用刚才录制的复用指令执行一次 {tag}")
    parser.add_argument(
        "--reselection-goal",
        default="Tap the current screen once, then finish the canonical argument reselection probe.",
    )
    parser.add_argument("--stop-goal", default="持续执行一个需要停止的 VLM 验收任务 {tag}")
    parser.add_argument("--arguments-json", default="{}", help="Function arguments JSON object")
    parser.add_argument("--prelaunch-package", default="com.android.settings", help="stable package launched before recording/replay checks")
    parser.add_argument("--prelaunch-wait-seconds", type=float, default=2.0)
    parser.add_argument("--provider-base-url", default=os.environ.get("OOB_PROVIDER_BASE_URL", ""))
    parser.add_argument("--provider-api-key", default=os.environ.get("OOB_PROVIDER_API_KEY", ""))
    parser.add_argument("--provider-model", default=os.environ.get("OOB_PROVIDER_MODEL", ""))
    parser.add_argument("--provider-profile-id", default=os.environ.get("OOB_PROVIDER_PROFILE_ID", ""))
    parser.add_argument("--provider-name", default=os.environ.get("OOB_PROVIDER_NAME", "Provider 1"))
    parser.add_argument("--provider-wire-api", default=os.environ.get("OOB_PROVIDER_WIRE_API", "chat_completions"))
    parser.add_argument("--mock-provider", action="store_true", help="start a local OpenAI-compatible SSE provider for deterministic VLM acceptance")
    parser.add_argument("--mock-provider-port", type=int, default=0, help="host port for --mock-provider; default uses an available port")
    parser.add_argument("--mock-provider-model", default="oob-acceptance-mock-vlm")
    parser.add_argument(
        "--historical-archive",
        type=Path,
        help="re-run VLM selection from an existing historical_runlogs archive without adb",
    )
    parser.add_argument(
        "--historical-run-id",
        action="append",
        default=[],
        help="limit standalone historical reselection to one run_id; repeat as needed",
    )
    parser.add_argument(
        "--historical-reselection-max-cases",
        type=int,
        default=8,
        help="maximum archived successful steps re-evaluated by the VLM",
    )
    parser.add_argument(
        "--historical-output",
        type=Path,
        help="standalone historical reselection report path",
    )
    parser.add_argument(
        "--provider-scene-ids",
        default=os.environ.get(
            "OOB_PROVIDER_SCENE_IDS",
            "scene.dispatch.model,scene.vlm.operation.primary,scene.compactor.context.chat",
        ),
    )
    parser.add_argument("--manual-gesture", action="append", default=[], help="x,y or x1,y1,x2,y2[,durationMs[,action]]; defaults to a stable Settings-page tap")
    parser.add_argument("--manual-window-seconds", type=float, default=0.0, help="time for manual interaction when scripted gestures are disabled")
    parser.add_argument("--vlm-max-steps", type=int, default=3)
    parser.add_argument("--stop-max-steps", type=int, default=20)
    parser.add_argument("--stop-delay-seconds", type=float, default=2.0)
    parser.add_argument("--function-stop-wait-ms", type=int, default=10000)
    parser.add_argument("--short-timeout", type=float, default=20.0)
    parser.add_argument("--long-timeout", type=float, default=90.0)
    parser.add_argument("--vlm-timeout", type=float, default=180.0)
    args = parser.parse_args(argv)
    parsed_arguments = json.loads(args.arguments_json)
    if not isinstance(parsed_arguments, dict):
        raise SystemExit("--arguments-json must be a JSON object")
    args.arguments = parsed_arguments
    return args


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if args.historical_archive is not None:
        if args.provider_wire_api.strip().lower() not in {"chat_completions", "chat-completions"}:
            raise SystemExit("--historical-archive currently requires --provider-wire-api chat_completions")
        if not args.provider_base_url or not args.provider_model:
            raise SystemExit("--historical-archive requires --provider-base-url and --provider-model")
        archive = load_historical_runlog_archive(args.historical_archive)
        report = evaluate_historical_vlm_reselection(
            archive,
            model=args.provider_model,
            turn_client=lambda request_payload: request_openai_model_turn(
                request_payload,
                base_url=args.provider_base_url,
                api_key=args.provider_api_key,
                timeout_seconds=args.vlm_timeout,
            ),
            run_ids={first_non_blank(value) for value in args.historical_run_id},
            max_cases=args.historical_reselection_max_cases,
        )
        output_path = args.historical_output or (
            Path(first_non_blank(archive.get("archive_root")))
            / "historical_vlm_reselection.json"
        )
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(
            json.dumps(report, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0 if report["success"] else 1
    summary = run_acceptance(args)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if summary["success"] else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
