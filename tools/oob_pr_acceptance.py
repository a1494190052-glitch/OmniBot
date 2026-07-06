#!/usr/bin/env python3
"""Run the OmniFlow/VLM PR acceptance loop on a connected debug device.

The script is intentionally an external harness. It calls the existing debug
receivers and writes a summary consumed by tools/oob_pr_freeze_check.py.
"""

from __future__ import annotations

import argparse
import base64
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
import shlex
import socket
import subprocess
import sys
import threading
import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any


PACKAGE = "cn.com.omnimind.bot.debug"

ACTION_HUMAN_RECORDING = "cn.com.omnimind.bot.debug.HUMAN_RUN_RECORDING"
ACTION_FUNCTION_UPDATE = "cn.com.omnimind.bot.debug.UPDATE_OOB_FUNCTION"
ACTION_FUNCTION_RUN = "cn.com.omnimind.bot.debug.RUN_OOB_FUNCTION"
ACTION_FUNCTION_IMPORT = "cn.com.omnimind.bot.debug.IMPORT_OOB_FUNCTION"
ACTION_FUNCTION_RECALL = "cn.com.omnimind.bot.debug.RUN_OOB_RECALL"
ACTION_VLM_RUNLOG = "cn.com.omnimind.bot.debug.RUN_VLM_RUNLOG"
ACTION_CANCEL = "cn.com.omnimind.bot.debug.CANCEL_VLM_TASK"
ACTION_PROVIDER_CONFIG = "cn.com.omnimind.bot.debug.CONFIGURE_MODEL_PROVIDER"


class MockVlmProvider:
    """Tiny OpenAI-compatible SSE provider used only by this acceptance harness."""

    def __init__(self, *, port: int = 0, model: str, stop_delay_seconds: float) -> None:
        self.requested_port = port
        self.model = model
        self.stop_delay_seconds = stop_delay_seconds
        self.port = 0
        self.device_base_url = ""
        self.reverse_enabled = False
        self._server: ThreadingHTTPServer | None = None
        self._thread: threading.Thread | None = None
        self._lock = threading.Lock()
        self._hits: list[dict[str, Any]] = []
        self._requests: list[dict[str, Any]] = []

    def start(self, adb: "Adb") -> None:
        server = ThreadingHTTPServer(("127.0.0.1", self.requested_port), _MockVlmProviderHandler)
        server.daemon_threads = True
        server.provider = self  # type: ignore[attr-defined]
        self._server = server
        self.port = int(server.server_address[1])
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
        if self.reverse_enabled and self.port:
            adb.run(["reverse", "--remove", f"tcp:{self.port}"], check=False, timeout=10)
        if self._server is not None:
            self._server.shutdown()
            self._server.server_close()

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            hits = list(self._hits)
            requests = list(self._requests)
        return {
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
        messages_text = json.dumps(body.get("messages", []), ensure_ascii=False)
        should_delay = self._is_stop_request(messages_text)
        tools = body.get("tools") if isinstance(body.get("tools"), list) else []
        selected_tool, selected_args, tool_names, dynamic_tool_names = self._select_tool(tools)
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
            "delayed_for_stop": should_delay,
        }
        with self._lock:
            self._requests.append(entry)

        if should_delay and self.stop_delay_seconds > 0:
            time.sleep(self.stop_delay_seconds)

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

    def _select_tool(self, tools: list[Any]) -> tuple[str, dict[str, Any], list[str], list[str]]:
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
            or item["tool_type"] == "oob_recalled_function"
        ]
        selected = (
            dynamic[0]
            if dynamic
            else next((item for item in tool_infos if item["name"] == "finished"), None)
            or (tool_infos[0] if tool_infos else None)
        )
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

    def _write_json(self, handler: BaseHTTPRequestHandler, status: int, payload: dict[str, Any]) -> None:
        data = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        handler.send_response(status)
        handler.send_header("Content-Type", "application/json; charset=utf-8")
        handler.send_header("Content-Length", str(len(data)))
        handler.end_headers()
        handler.wfile.write(data)


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


def function_stop_spec(function_id: str, tag: str, wait_ms: int) -> dict[str, Any]:
    return {
        "schema_version": "oob.reusable_function.v1",
        "function_id": function_id,
        "name": f"PR 验收停止复用指令 {tag}",
        "description": f"用于验证 Function 执行中 stop 端口的长等待复用指令 {tag}",
        "parameters": {
            "type": "object",
            "properties": {},
            "required": [],
            "additionalProperties": False,
        },
        "execution": {
            "steps": [
                {
                    "id": "wait_for_stop",
                    "index": 0,
                    "title": f"等待停止信号 {wait_ms}ms",
                    "kind": "function",
                    "model_free": True,
                    "scriptable": True,
                    "tool": "wait",
                    "args": {
                        "duration_ms": wait_ms,
                    },
                }
            ]
        },
        "metadata": {
            "agent_visible": False,
            "visibility": "debug_acceptance",
            "registered_via": "pr_acceptance_function_stop",
        },
    }


def launch_package(adb: Adb, package_name: str, wait_s: float = 1.0) -> None:
    adb.run(["shell", "monkey", "-p", package_name, "-c", "android.intent.category.LAUNCHER", "1"], check=False)
    time.sleep(wait_s)


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


def start_mock_provider_if_requested(adb: Adb, args: argparse.Namespace) -> MockVlmProvider | None:
    if not args.mock_provider:
        return None
    provider = MockVlmProvider(
        port=args.mock_provider_port,
        model=args.mock_provider_model,
        stop_delay_seconds=max(args.stop_delay_seconds + 2.0, 4.0),
    )
    provider.start(adb)
    args.provider_base_url = provider.device_base_url
    args.provider_api_key = "oob-acceptance-mock-key"
    args.provider_model = provider.model
    if not args.provider_profile_id:
        args.provider_profile_id = "debug-runtime-provider"
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
    if proxy_snapshot is not None:
        clear_device_proxy_for_mock_provider(adb)
    mock_provider = start_mock_provider_if_requested(adb, args)
    try:
        return _run_acceptance(args=args, adb=adb, mock_provider=mock_provider)
    finally:
        if mock_provider is not None:
            mock_provider.stop(adb)
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
    enhanced_name = args.enhanced_name.format(tag=unique_tag)
    enhanced_description = args.enhanced_description.format(tag=unique_tag)
    enhancement_instruction = args.enhancement_instruction.format(tag=unique_tag)
    replay_goal = args.replay_goal.format(tag=unique_tag)
    recall_goal = args.recall_goal.format(tag=unique_tag)
    vlm_goal = args.vlm_goal.format(tag=unique_tag)
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
        checks.append(check(
            "provider_config",
            provider_config.get("success") is True,
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
    function_id = first_non_blank(
        manual_result.get("function_id"),
        manual_result.get("created_function_id"),
        (manual_result.get("conversion") or {}).get("function_id") if isinstance(manual_result.get("conversion"), dict) else "",
    )
    run_id = first_non_blank(manual_result.get("run_id"))
    manual_ok = (
        manual_start.get("success") is True
        and manual_result.get("recording_success") is True
        and manual_result.get("function_registered") is True
        and function_id
        and int(manual_result.get("action_count") or 0) > 0
    )
    checks.append(check(
        "manual_recording",
        bool(manual_ok),
        "DebugHumanRunRecordingReceiver start/finish generated a RunLog and registered a Function.",
        {"run_id": run_id, "function_id": function_id, "action_count": manual_result.get("action_count")},
    ))
    checks.append(check(
        "convert_register_replay",
        bool(manual_result.get("conversion_success") is True and function_id),
        "Manual RunLog conversion returned a registered Function id.",
        {"function_id": function_id, "conversion": compact_result(manual_result.get("conversion"))},
    ))

    # 2. Enhancement/update.
    clean("debug-oob-function-update-result.json")
    patch = {
        "name": enhanced_name,
        "description": enhanced_description,
        "agent_visible": True,
        "visibility": "agent_reusable",
        "metadata": {
            "agent_visible": True,
            "visibility": "agent_reusable",
            "registered_via": "pr_acceptance",
        },
    }
    adb.broadcast(
        ACTION_FUNCTION_UPDATE,
        [
            *es("functionId", function_id),
            *es("instructionBase64", b64(enhancement_instruction)),
            *es("patchBase64", json_b64(patch)),
            *ez("dryRun", False),
        ],
    )
    update_result = wait_json(adb, "debug-oob-function-update-result.json", args.long_timeout)
    results["function_update"] = update_result
    save_artifact("function_update", update_result)
    checks.append(check(
        "function_update_enhance",
        update_result.get("success") is True and update_result.get("after_found") is True,
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
    pre_replay = direct_run.get("pre_replay_observation")
    if not isinstance(pre_replay, dict):
        pre_replay = (direct_run.get("diagnostics") or {}).get("pre_replay_observation") if isinstance(direct_run.get("diagnostics"), dict) else {}
    checks.append(check(
        "direct_function_run",
        direct_run.get("success") is True,
        "DebugFunctionRunReceiver executed the registered Function directly.",
        compact_result(direct_run),
    ))
    checks.append(check(
        "pre_replay_foreground_not_oob",
        isinstance(pre_replay, dict)
        and pre_replay.get("success") is True
        and pre_replay.get("is_oob_package") is not True,
        "FunctionService captured current XML after hiding replay overlay and the foreground package was not OOB.",
        pre_replay,
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

    # 4. Recall.
    if args.prelaunch_package:
        launch_package(adb, args.prelaunch_package, args.prelaunch_wait_seconds)
    clean("debug-oob-recall-result.json")
    adb.broadcast(
        ACTION_FUNCTION_RECALL,
        [
            *es("goalBase64", b64(recall_goal)),
            *ei("k", args.recall_k),
        ],
    )
    recall_result = wait_json(adb, "debug-oob-recall-result.json", args.long_timeout)
    results["function_recall"] = recall_result
    save_artifact("function_recall", recall_result)
    recall_blob = json.dumps(recall_result, ensure_ascii=False)
    checks.append(check(
        "function_recall",
        function_id in recall_blob and "candidates" in recall_blob,
        "DebugOobRecallReceiver returned a candidate containing the enhanced Function id.",
        compact_result(recall_result),
    ))

    # 5. VLM recall should see and run a Function. This is intentionally strict:
    # if the model does not call the recalled Function, the PR is not accepted.
    if args.prelaunch_package:
        launch_package(adb, args.prelaunch_package, args.prelaunch_wait_seconds)
    clean("debug-vlm-runlog-result.json")
    adb.broadcast(
        ACTION_VLM_RUNLOG,
        [
            *es("goalBase64", b64(vlm_goal)),
            *ez("startFromCurrent", True),
            *ez("skipGoHome", True),
            *ei("maxSteps", args.vlm_max_steps),
            *ez("register", False),
        ],
    )
    vlm_result = wait_json(adb, "debug-vlm-runlog-result.json", args.vlm_timeout)
    results["vlm_recall_run"] = vlm_result
    save_artifact("vlm_recall_run", vlm_result)
    vlm_blob = json.dumps(vlm_result, ensure_ascii=False)
    vlm_provider_failure = provider_failure_kind(vlm_result)
    mock_snapshot_after_vlm = mock_provider.snapshot() if mock_provider is not None else None
    if mock_snapshot_after_vlm is not None:
        results["mock_provider_after_vlm"] = mock_snapshot_after_vlm
        save_artifact("mock_provider_after_vlm", mock_snapshot_after_vlm)
    checks.append(check(
        "vlm_recall_function_run",
        vlm_result.get("success") is True
        and (function_id in vlm_blob or "function_result" in vlm_blob or "Function" in vlm_blob),
        "DebugVlmRunLogReceiver ran a VLM task that selected/executed the recalled Function.",
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
                "functionSpecBase64",
                json_b64(function_stop_spec(stop_function_id, unique_tag, args.function_stop_wait_ms)),
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
    clean("debug-vlm-cancel-result.json")
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
    function_cancel_result = wait_json(adb, "debug-vlm-cancel-result.json", args.short_timeout)
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
        and function_cancel_result.get("running_stop_requested") is True
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

    # 7. VLM stop port. Start a long VLM task and cancel active work through the same
    # debug stop port used for VLM and Function sessions.
    if args.prelaunch_package:
        launch_package(adb, args.prelaunch_package, args.prelaunch_wait_seconds)
    clean("debug-vlm-runlog-result.json")
    clean("debug-vlm-cancel-result.json")
    adb.broadcast(
        ACTION_VLM_RUNLOG,
        [
            *es("goalBase64", b64(stop_goal)),
            *ez("startFromCurrent", True),
            *ez("skipGoHome", True),
            *ei("maxSteps", args.stop_max_steps),
            *ez("register", False),
        ],
    )
    time.sleep(args.stop_delay_seconds)
    adb.broadcast(ACTION_CANCEL, [*es("reason", "pr_acceptance_stop_port")])
    cancel_result = wait_json(adb, "debug-vlm-cancel-result.json", args.short_timeout)
    results["stop_port"] = cancel_result
    save_artifact("stop_port", cancel_result)
    checks.append(check(
        "stop_port",
        cancel_result.get("success") is True
        and (
            cancel_result.get("vlm_cancel_requested") is True
            or cancel_result.get("function_stop_requested") is True
            or cancel_result.get("running_stop_requested") is True
        ),
        "DebugVlmCancelReceiver requested cancellation through the unified VLM/Function stop port.",
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
    parser.add_argument("--enhanced-name", default="PR 验收复用指令增强版 {tag}")
    parser.add_argument("--enhanced-description", default="用于验证 RunLog 注册、Function 增强、复用运行和 VLM recall 的复用指令 {tag}")
    parser.add_argument("--enhancement-instruction", default="把这个复用指令改成更清晰、可被 agent 调用的名称和简介，保留唯一标记 {tag}。")
    parser.add_argument("--replay-goal", default="执行刚注册的复用指令 {tag}")
    parser.add_argument("--recall-goal", default="用刚才录制的方法再执行一次 {tag}")
    parser.add_argument("--vlm-goal", default="用刚才录制的复用指令执行一次 {tag}")
    parser.add_argument("--stop-goal", default="持续执行一个需要停止的 VLM 验收任务 {tag}")
    parser.add_argument("--arguments-json", default="{}", help="Function arguments JSON object")
    parser.add_argument("--prelaunch-package", default="com.android.settings", help="stable package launched before recording/replay checks")
    parser.add_argument("--prelaunch-wait-seconds", type=float, default=2.0)
    parser.add_argument("--provider-base-url", default=os.environ.get("OOB_PROVIDER_BASE_URL", ""))
    parser.add_argument("--provider-api-key", default=os.environ.get("OOB_PROVIDER_API_KEY", ""))
    parser.add_argument("--provider-model", default=os.environ.get("OOB_PROVIDER_MODEL", ""))
    parser.add_argument("--provider-profile-id", default=os.environ.get("OOB_PROVIDER_PROFILE_ID", "debug-runtime-provider"))
    parser.add_argument("--provider-name", default=os.environ.get("OOB_PROVIDER_NAME", "Provider 1"))
    parser.add_argument("--provider-wire-api", default=os.environ.get("OOB_PROVIDER_WIRE_API", "chat_completions"))
    parser.add_argument("--mock-provider", action="store_true", help="start a local OpenAI-compatible SSE provider for deterministic VLM acceptance")
    parser.add_argument("--mock-provider-port", type=int, default=0, help="host port for --mock-provider; default uses an available port")
    parser.add_argument("--mock-provider-model", default="oob-acceptance-mock-vlm")
    parser.add_argument(
        "--provider-scene-ids",
        default=os.environ.get(
            "OOB_PROVIDER_SCENE_IDS",
            "scene.dispatch.model,scene.vlm.operation.primary,scene.compactor.context.chat",
        ),
    )
    parser.add_argument("--manual-gesture", action="append", default=[], help="x,y or x1,y1,x2,y2[,durationMs[,action]]; defaults to a stable Settings-page tap")
    parser.add_argument("--manual-window-seconds", type=float, default=0.0, help="time for manual interaction when scripted gestures are disabled")
    parser.add_argument("--recall-k", type=int, default=3)
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
    summary = run_acceptance(args)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if summary["success"] else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
