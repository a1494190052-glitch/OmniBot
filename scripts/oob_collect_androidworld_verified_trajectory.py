#!/usr/bin/env python3
"""Script AndroidWorld tasks while collecting OOB XML/screenshot evidence.

No model is called. AndroidWorld owns reset, task initialization, and final
verification. OOB owns state capture and primitive action execution.
"""

from __future__ import annotations

import argparse
import base64
import dataclasses
import datetime as dt
import functools
import http.server
import json
import os
import re
import shlex
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from xml.etree import ElementTree


DEFAULT_PACKAGE = "cn.com.omnimind.bot.debug"
DEFAULT_PORT = 8910
ANDROID_WORLD_ROOT = Path(
    os.environ.get("ANDROID_WORLD_ROOT", str(Path.home() / "Projects" / "android_world"))
).expanduser()
BROWSER_HTTP_SERVERS: list[Any] = []


def repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def find_adb() -> str:
    for candidate in (
        Path.home() / "Library" / "Android" / "sdk" / "platform-tools" / "adb",
        Path.home() / "Android" / "Sdk" / "platform-tools" / "adb",
    ):
        if candidate.exists():
            return str(candidate)
    return "adb"


def run(command: list[str], *, timeout: int = 60, check: bool = True) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        command,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout,
        check=False,
    )
    if check and result.returncode != 0:
        raise RuntimeError(
            f"Command failed ({result.returncode}): {' '.join(command)}\n"
            f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
        )
    return result


class Adb:
    def __init__(self, adb_path: str, device: str, package_name: str) -> None:
        self.adb_path = adb_path
        self.device = device
        self.package_name = package_name

    def shell(self, *args: str, timeout: int = 60, check: bool = True) -> subprocess.CompletedProcess[str]:
        return run([self.adb_path, "-s", self.device, "shell", *args], timeout=timeout, check=check)

    def adb(self, *args: str, timeout: int = 60, check: bool = True) -> subprocess.CompletedProcess[str]:
        return run([self.adb_path, "-s", self.device, *args], timeout=timeout, check=check)

    def prepare_oob(self) -> None:
        service = f"{self.package_name}/com.google.android.accessibility.selecttospeak.SelectToSpeakService"
        current = self.shell("settings", "get", "secure", "enabled_accessibility_services", check=False).stdout.strip()
        services = [] if current in {"", "null"} else [part for part in current.split(":") if part]
        if service not in services:
            services.append(service)
        self.shell("settings", "put", "secure", "enabled_accessibility_services", ":".join(services), check=False)
        self.shell("settings", "put", "secure", "accessibility_enabled", "1", check=False)
        self.shell("appops", "set", self.package_name, "SYSTEM_ALERT_WINDOW", "allow", check=False)
        self.shell(
            "monkey",
            "-p",
            self.package_name,
            "-c",
            "android.intent.category.LAUNCHER",
            "1",
            timeout=30,
            check=False,
        )
        self.adb("forward", f"tcp:{DEFAULT_PORT}", f"tcp:{DEFAULT_PORT}", timeout=10)


class OobHost:
    def __init__(self, port: int) -> None:
        self.base = f"http://127.0.0.1:{port}"

    def get(self, path: str, query: dict[str, Any] | None = None, *, timeout: int = 30) -> dict[str, Any]:
        url = self.base + path
        if query:
            url += "?" + urllib.parse.urlencode(
                {key: str(value).lower() if isinstance(value, bool) else value for key, value in query.items()}
            )
        with urllib.request.urlopen(url, timeout=timeout) as response:  # noqa: S310 local debug host
            return json.loads(response.read().decode("utf-8"))

    def post(self, path: str, payload: dict[str, Any], *, timeout: int = 60) -> dict[str, Any]:
        request = urllib.request.Request(
            self.base + path,
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:  # noqa: S310 local debug host
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            text = error.read().decode("utf-8", errors="replace")
            try:
                return json.loads(text)
            except json.JSONDecodeError:
                return {"success": False, "error": text, "status": error.code}


@dataclass(frozen=True)
class Step:
    tool: str
    title: str
    args: dict[str, Any]
    target_regex: str = ""
    fallback_relative_xy: tuple[float, float] | None = None
    wait_after_ms: int = 1000
    optional: bool = False


TASK_STEPS: dict[str, list[Step]] = {
    "ClockStopWatchPausedVerify": [
        Step("open_app", "打开 Clock", {"package_name": "com.google.android.deskclock"}, wait_after_ms=1200),
        Step(
            "click",
            "点击 Stopwatch",
            {"target_description": "Stopwatch"},
            target_regex="秒表|Stopwatch",
            fallback_relative_xy=(700, 882),
            wait_after_ms=1000,
        ),
        Step(
            "click",
            "点击 Start",
            {"target_description": "Start"},
            target_regex="开始|Start|启动",
            fallback_relative_xy=(500, 738),
            wait_after_ms=1000,
        ),
        Step(
            "click",
            "点击 Pause",
            {"target_description": "Pause"},
            target_regex="暂停|Pause",
            fallback_relative_xy=(500, 738),
            wait_after_ms=1000,
        ),
    ],
    "ClockStopWatchRunning": [
        Step("open_app", "打开 Clock", {"package_name": "com.google.android.deskclock"}, wait_after_ms=1200),
        Step(
            "click",
            "点击 Stopwatch",
            {"target_description": "Stopwatch"},
            target_regex="秒表|Stopwatch",
            fallback_relative_xy=(700, 882),
            wait_after_ms=800,
        ),
        Step(
            "click",
            "点击 Start",
            {"target_description": "Start"},
            target_regex="开始|Start|启动",
            fallback_relative_xy=(500, 738),
            wait_after_ms=1500,
        ),
    ],
}

OPEN_APP_PACKAGES = {
    "camera": "com.android.camera2",
    "clock": "com.google.android.deskclock",
    "contacts": "com.google.android.contacts",
    "settings": "com.android.settings",
    "dialer": "com.google.android.dialer",
    "simple_sms_messenger": "com.simplemobiletools.smsmessenger",
}

SUPPORTED_TASKS = sorted(set(TASK_STEPS) | {
    "AudioRecorderRecordAudio",
    "AudioRecorderRecordAudioWithFileName",
    "ClockTimerEntry",
    "OpenAppTaskEval",
})
SUPPORTED_TASKS = sorted(set(SUPPORTED_TASKS) | {"CameraTakePhoto", "CameraTakeVideo"})
SUPPORTED_TASKS = sorted(set(SUPPORTED_TASKS) | {"ContactsAddContact", "ContactsNewContactDraft"})
SUPPORTED_TASKS = sorted(set(SUPPORTED_TASKS) | {
    "SystemWifiTurnOff",
    "SystemWifiTurnOffVerify",
    "SystemWifiTurnOn",
    "SystemWifiTurnOnVerify",
})
SUPPORTED_TASKS = sorted(set(SUPPORTED_TASKS) | {
    "SystemBluetoothTurnOff",
    "SystemBluetoothTurnOffVerify",
    "SystemBluetoothTurnOn",
    "SystemBluetoothTurnOnVerify",
})
SUPPORTED_TASKS = sorted(set(SUPPORTED_TASKS) | {
    "SystemBrightnessMax",
    "SystemBrightnessMaxVerify",
    "SystemBrightnessMin",
    "SystemBrightnessMinVerify",
})
SUPPORTED_TASKS = sorted(set(SUPPORTED_TASKS) | {
    "TurnOffWifiAndTurnOnBluetooth",
    "TurnOnWifiAndOpenApp",
})
SUPPORTED_TASKS = sorted(set(SUPPORTED_TASKS) | {"SimpleCalendarAddOneEvent", "SimpleCalendarAddOneEventTomorrow"})
SUPPORTED_TASKS = sorted(set(SUPPORTED_TASKS) | {"RecipeAddSingleRecipe"})
SUPPORTED_TASKS = sorted(set(SUPPORTED_TASKS) | {"MarkorCreateFolder", "MarkorCreateNote"})
SUPPORTED_TASKS = sorted(set(SUPPORTED_TASKS) | {"SimpleSmsSend"})
SUPPORTED_TASKS = sorted(set(SUPPORTED_TASKS) | {"BrowserMaze"})


BOUNDS_RE = re.compile(r"\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]")


def now_ms() -> int:
    return time.time_ns() // 1_000_000


def _looks_like_deskclock_transient_overlay(state: dict[str, Any]) -> bool:
    if state.get("package_name") != "com.google.android.deskclock":
        return False
    xml = str(state.get("xml") or "")
    if not xml.strip():
        return False
    clock_page_markers = (
        "Stopwatch",
        "Timer",
        "Alarm",
        "Clock",
        "stopwatch",
        "timer",
        "秒表",
        "计时器",
        "闹钟",
    )
    if any(marker in xml for marker in clock_page_markers):
        return False
    transient_markers = (
        "privacy policy",
        "consistent bedtime",
        "com.google.android.deskclock:id/body",
    )
    if not any(marker in xml for marker in transient_markers):
        return False
    try:
        root = ElementTree.fromstring(xml.encode("utf-8"))
    except ElementTree.ParseError:
        return False
    return sum(1 for _ in root.iter()) <= 12


def capture_state(host: OobHost, *, image_quality: str) -> dict[str, Any]:
    params = {
        "includeXml": True,
        "includeScreenshot": True,
        "includeIndexedContext": True,
        "maxXmlChars": 0,
        "imageQuality": image_quality,
        "filterOverlay": True,
    }
    state = host.get("/get_state", params, timeout=45)
    if not _looks_like_deskclock_transient_overlay(state):
        return state
    deadline = time.monotonic() + 4.0
    while time.monotonic() < deadline:
        time.sleep(0.4)
        candidate = host.get("/get_state", params, timeout=45)
        if not _looks_like_deskclock_transient_overlay(candidate):
            return candidate
        state = candidate
    return state


def capture_state_for_step(host: OobHost, step: Step, *, image_quality: str) -> dict[str, Any]:
    state = capture_state(host, image_quality=image_quality)
    if step.tool not in {"click", "input_text"} or not step.target_regex:
        return state
    if find_xml_target(str(state.get("xml") or ""), step.target_regex):
        return state
    deadline = time.monotonic() + 12.0
    while time.monotonic() < deadline:
        time.sleep(0.4)
        candidate = capture_state(host, image_quality=image_quality)
        if find_xml_target(str(candidate.get("xml") or ""), step.target_regex):
            return candidate
        state = candidate
    return state


def parse_bounds(value: str) -> tuple[int, int, int, int] | None:
    match = BOUNDS_RE.search(value or "")
    if not match:
        return None
    return tuple(int(group) for group in match.groups())  # type: ignore[return-value]


def label_for(attrs: dict[str, str]) -> str:
    return " ".join(
        value for value in (
            attrs.get("text", ""),
            attrs.get("content-desc", ""),
            attrs.get("hint", ""),
            attrs.get("resource-id", ""),
        )
        if value
    )


def find_xml_target(xml: str, regex: str) -> dict[str, Any] | None:
    if not xml.strip() or not regex:
        return None
    try:
        root = ElementTree.fromstring(xml.encode("utf-8"))
    except ElementTree.ParseError:
        return None
    pattern = re.compile(regex, re.IGNORECASE)
    candidates: list[dict[str, Any]] = []
    for index, node in enumerate(root.iter()):
        attrs = dict(node.attrib)
        label = label_for(attrs)
        if not pattern.search(label):
            continue
        bounds = parse_bounds(attrs.get("bounds", ""))
        if bounds is None:
            continue
        left, top, right, bottom = bounds
        if right <= left or bottom <= top:
            continue
        clickable = attrs.get("clickable", "").lower() == "true"
        score = (100 if clickable else 0) + (20 if attrs.get("text") or attrs.get("content-desc") else 0)
        candidates.append(
            {
                "index": index,
                "label": label[:200],
                "bounds": list(bounds),
                "x": (left + right) / 2.0,
                "y": (top + bottom) / 2.0,
                "clickable": clickable,
                "score": score,
            }
        )
    candidates.sort(key=lambda item: (-item["score"], item["bounds"][1], item["bounds"][0]))
    return candidates[0] if candidates else None


def display_size(state: dict[str, Any]) -> tuple[int, int]:
    screenshot = state.get("screenshot") if isinstance(state.get("screenshot"), dict) else {}
    width = int(screenshot.get("original_width") or screenshot.get("width") or state.get("xml_display_width") or 1)
    height = int(screenshot.get("original_height") or screenshot.get("height") or state.get("xml_display_height") or 1)
    return max(1, width), max(1, height)


def relative_to_absolute(state: dict[str, Any], point: tuple[float, float]) -> tuple[float, float]:
    width, height = display_size(state)
    return (
        max(0.0, min(1000.0, point[0])) / 1000.0 * width,
        max(0.0, min(1000.0, point[1])) / 1000.0 * height,
    )


def save_state_artifacts(state: dict[str, Any], artifact_dir: Path, prefix: str) -> dict[str, Any]:
    artifact_dir.mkdir(parents=True, exist_ok=True)
    ref: dict[str, Any] = {
        "captured_at_ms": state.get("captured_at_ms"),
        "package_name": state.get("package_name"),
        "activity_name": state.get("activity_name"),
    }
    xml = state.get("xml")
    if isinstance(xml, str) and xml:
        path = artifact_dir / f"{prefix}.xml"
        path.write_text(xml, encoding="utf-8")
        ref["xml_path"] = str(path)
    indexed = state.get("indexed_page_evidence")
    if isinstance(indexed, str) and indexed:
        path = artifact_dir / f"{prefix}.indexed.txt"
        path.write_text(indexed, encoding="utf-8")
        ref["indexed_page_evidence_path"] = str(path)
    screenshot = state.get("screenshot") if isinstance(state.get("screenshot"), dict) else {}
    data_uri = screenshot.get("data_uri") if isinstance(screenshot, dict) else None
    if isinstance(data_uri, str) and data_uri:
        payload = data_uri.split(",", 1)[-1]
        path = artifact_dir / f"{prefix}.jpg"
        path.write_bytes(base64.b64decode(payload))
        ref["screenshot_path"] = str(path)
        ref["screenshot"] = {
            "schema_version": "oob.runlog.screenshot_ref.v1",
            "kind": "screenshot",
            "mime_type": "image/jpeg",
            "path": str(path),
            "width": screenshot.get("width"),
            "height": screenshot.get("height"),
            "original_width": screenshot.get("original_width"),
            "original_height": screenshot.get("original_height"),
        }
    return ref


def execute_step(host: OobHost, step: Step, before: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    args = dict(step.args)
    if step.tool in {"click", "input_text"}:
        target = find_xml_target(str(before.get("xml") or ""), step.target_regex)
        if target:
            args["x"] = target["x"]
            args["y"] = target["y"]
            args["target_evidence"] = target
        elif step.fallback_relative_xy:
            x, y = relative_to_absolute(before, step.fallback_relative_xy)
            args["x"] = x
            args["y"] = y
            args["target_evidence"] = {
                "source": "scripted_screenshot_relative_fallback",
                "relative_0_1000": list(step.fallback_relative_xy),
                "display_size": list(display_size(before)),
            }
        elif step.tool == "click":
            return args, {"success": False, "error": "target_not_found", "target_regex": step.target_regex}
    result = host.post("/act", {"tool": step.tool, "args": args}, timeout=60)
    return args, result


def execute_available_optional_steps(
    host: OobHost,
    steps: list[Step],
    *,
    image_quality: str,
) -> list[dict[str, Any]]:
    results: list[dict[str, Any]] = []
    for step in steps:
        if not step.optional or not step.target_regex:
            continue
        before = capture_state_for_step(host, step, image_quality=image_quality)
        if not find_xml_target(str(before.get("xml") or ""), step.target_regex):
            continue
        step_args, result = execute_step(host, step, before)
        if step.wait_after_ms > 0:
            time.sleep(step.wait_after_ms / 1000.0)
        results.append({
            "title": step.title,
            "tool": step.tool,
            "args": step_args,
            "result": result,
        })
    return results


def open_browser_task_html(adb: Adb) -> dict[str, Any]:
    """Open AndroidWorld's generated Downloads/task.html in Chrome."""
    html = adb.adb("exec-out", "cat", "/sdcard/Download/task.html", timeout=20, check=False)
    attempts = []
    if html.returncode == 0 and html.stdout:
        temp_dir = Path(tempfile.mkdtemp(prefix="oob_browser_task_"))
        (temp_dir / "task.html").write_text(html.stdout, encoding="utf-8")
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(temp_dir))
        server = http.server.ThreadingHTTPServer(("127.0.0.1", port), handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        BROWSER_HTTP_SERVERS.append((server, thread, temp_dir))
        url = f"http://10.0.2.2:{port}/task.html"
        adb.shell("am", "force-stop", "com.android.chrome", timeout=10, check=False)
        result = adb.shell(
            "am",
            "start",
            "-a",
            "android.intent.action.VIEW",
            "-d",
            url,
            "-n",
            "com.android.chrome/com.google.android.apps.chrome.Main",
            timeout=30,
            check=False,
        )
        attempts.append({
            "command": f"am start {url}",
            "returncode": result.returncode,
            "stdout_tail": result.stdout[-500:],
            "stderr_tail": result.stderr[-500:],
        })
        if result.returncode == 0:
            time.sleep(2.0)
            return {"success": True, "attempts": attempts, "source": "host_http", "url": url}
    commands = [
        [
            "am",
            "start",
            "-a",
            "android.intent.action.VIEW",
            "-d",
            "file:///sdcard/Download/task.html",
            "-t",
            "text/html",
            "-n",
            "com.android.chrome/com.google.android.apps.chrome.Main",
        ],
        [
            "am",
            "start",
            "-a",
            "android.intent.action.VIEW",
            "-d",
            "file:///storage/emulated/0/Download/task.html",
            "-t",
            "text/html",
            "-n",
            "com.android.chrome/com.google.android.apps.chrome.Main",
        ],
    ]
    for command in commands:
        result = adb.shell(*command, timeout=30, check=False)
        attempts.append({
            "command": " ".join(command),
            "returncode": result.returncode,
            "stdout_tail": result.stdout[-500:],
            "stderr_tail": result.stderr[-500:],
        })
        if result.returncode == 0:
            time.sleep(2.0)
            return {"success": True, "attempts": attempts}
    time.sleep(1.0)
    return {"success": False, "attempts": attempts}


class JsSeededRng:
    def __init__(self, seed: int) -> None:
        self.seed = int(seed) & 0xFFFFFFFF

    def random(self) -> float:
        self.seed = (1664525 * self.seed + 1013904223) % (2**32)
        return self.seed / float(2**32)


def browser_maze_route(seed: int) -> list[str]:
    size = 4
    maze = [["#" for _ in range(size)] for _ in range(size)]
    stack = [(0, 0)]
    directions = [(-1, 0), (1, 0), (0, -1), (0, 1)]
    rng = JsSeededRng(seed)
    while stack:
        row, col = stack.pop()
        maze[row][col] = " "
        if row == size - 1 and col == size - 1:
            break
        for i in range(len(directions) - 1, 0, -1):
            j = int(rng.random() * (i + 1))
            directions[i], directions[j] = directions[j], directions[i]
        for dx, dy in directions:
            nr, nc = row + dx, col + dy
            if 0 <= nr < size and 0 <= nc < size and maze[nr][nc] == "#":
                stack.append((nr, nc))
    maze[0][0] = " "
    maze[size - 1][size - 1] = "$"

    queue: list[tuple[int, int, list[str]]] = [(0, 0, [])]
    seen = {(0, 0)}
    moves = [(-1, 0, "Up"), (1, 0, "Down"), (0, -1, "Left"), (0, 1, "Right")]
    while queue:
        row, col, route = queue.pop(0)
        if row == size - 1 and col == size - 1:
            return route
        for dr, dc, label in moves:
            nr, nc = row + dr, col + dc
            if not (0 <= nr < size and 0 <= nc < size):
                continue
            if maze[nr][nc] == "#" or (nr, nc) in seen:
                continue
            seen.add((nr, nc))
            queue.append((nr, nc, route + [label]))
    raise ValueError(f"BrowserMaze has no route for seed {seed}")


def build_card(
    *,
    run_id: str,
    index: int,
    step: Step,
    args: dict[str, Any],
    result: dict[str, Any],
    before: dict[str, Any],
    after: dict[str, Any],
    before_ref: dict[str, Any],
    after_ref: dict[str, Any],
    started_at_ms: int,
    finished_at_ms: int,
) -> dict[str, Any]:
    card_id = f"{run_id}-step-{index + 1}"
    success = bool(result.get("success"))
    source_context = {
        "src_ctx": {
            "page": before.get("xml"),
            "xml_path": before_ref.get("xml_path"),
            "screenshot": before_ref.get("screenshot"),
            "screenshot_path": before_ref.get("screenshot_path"),
            "package_name": before.get("package_name"),
            "require_unique_action_signature": False,
        },
        "dst_ctx": {
            "page": after.get("xml"),
            "xml_path": after_ref.get("xml_path"),
            "screenshot": after_ref.get("screenshot"),
            "screenshot_path": after_ref.get("screenshot_path"),
            "package_name": after.get("package_name"),
        },
        "action": {"tool": step.tool, **args},
        "_oob_meta": {
            "mode": "androidworld_verified_scripted_collection",
            "recording_backend": "oob_local_device_http_host",
            "action_source": "scripted_solution",
        },
    }
    return {
        "card_id": card_id,
        "tool_call_id": card_id,
        "header": {
            "step_index": index,
            "title": step.title,
            "tool_name": step.tool,
            "status": "success" if success else "failed",
            "success": success,
            "duration_ms": max(0, finished_at_ms - started_at_ms),
        },
        "step_index": index,
        "title": step.title,
        "summary": result.get("message") or result.get("error") or step.title,
        "tool_name": step.tool,
        "toolName": step.tool,
        "tool_type": "androidworld_verified_scripted_collection",
        "toolType": "androidworld_verified_scripted_collection",
        "status": "success" if success else "failed",
        "action_type": step.tool,
        "success": success,
        "duration_ms": max(0, finished_at_ms - started_at_ms),
        "started_at_ms": started_at_ms,
        "finished_at_ms": finished_at_ms,
        "package_name": before.get("package_name") or after.get("package_name"),
        "compile_kind": "androidworld_verified_scripted_collection",
        "source": "androidworld_scripted_collection",
        "source_context": source_context,
        "tool_call": {"id": card_id, "name": step.tool, "arguments": args},
        "params": args,
        "result": result,
        "before": {
            "observation_xml": before.get("xml"),
            "xml_path": before_ref.get("xml_path"),
            "screenshot": before_ref.get("screenshot"),
            "screenshot_path": before_ref.get("screenshot_path"),
            "package_name": before.get("package_name"),
            "activity_name": before.get("activity_name"),
        },
        "after": {
            "observation_xml": after.get("xml"),
            "xml_path": after_ref.get("xml_path"),
            "screenshot": after_ref.get("screenshot"),
            "screenshot_path": after_ref.get("screenshot_path"),
            "package_name": after.get("package_name"),
            "activity_name": after.get("activity_name"),
        },
    }


def load_androidworld() -> tuple[Any, Any]:
    sys.path.insert(0, str(ANDROID_WORLD_ROOT))
    from android_world import registry
    from android_world.env import env_launcher
    return registry, env_launcher


def patch_androidworld_clear_directory_empty_glob() -> None:
    sys.path.insert(0, str(ANDROID_WORLD_ROOT))
    from android_world.env import adb_utils
    from android_world.utils import file_utils

    if getattr(file_utils.clear_directory, "_oob_empty_glob_safe", False):
        return
    original_clear_directory = file_utils.clear_directory

    def clear_directory_empty_glob_safe(directory_path: str, env: Any) -> None:
        quoted_path = shlex.quote(directory_path)
        adb_utils.issue_generic_request(
            ["shell", f"mkdir -p {quoted_path} && rm -rf {quoted_path}/*"],
            env,
        )

    clear_directory_empty_glob_safe._oob_empty_glob_safe = True  # type: ignore[attr-defined]
    clear_directory_empty_glob_safe._oob_original_clear_directory = original_clear_directory  # type: ignore[attr-defined]
    file_utils.clear_directory = clear_directory_empty_glob_safe


def json_safe(value: Any) -> Any:
    if dataclasses.is_dataclass(value) and not isinstance(value, type):
        return {key: json_safe(item) for key, item in dataclasses.asdict(value).items()}
    if isinstance(value, dict):
        return {str(key): json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [json_safe(item) for item in value]
    return value


def hydrate_androidworld_params(task_name: str, params: dict[str, Any]) -> dict[str, Any]:
    if task_name == "AudioRecorderRecordAudioWithFileName":
        hydrated = dict(params)
        hydrated["file_name"] = str(hydrated.get("file_name") or "oob_audio_named")
        hydrated.setdefault("text", "")
        return hydrated
    if task_name == "RecipeAddSingleRecipe":
        sys.path.insert(0, str(ANDROID_WORLD_ROOT))
        from android_world.task_evals.common_validators import sqlite_validators
        from android_world.task_evals.utils import sqlite_schema_utils

        title = str(params.get("title") or "Lemon Toast")
        description = str(params.get("description") or "A quick citrus breakfast.")
        servings = str(params.get("servings") or "1 serving")
        preparation_time = str(params.get("preparationTime") or params.get("preparation_time") or "10 mins")
        source = str(params.get("source") or "")
        ingredients = str(params.get("ingredients") or "bread, lemon, butter")
        directions = str(params.get("directions") or "Toast bread, add butter, and finish with lemon zest.")
        recipe = sqlite_schema_utils.Recipe(
            title=title,
            description=description,
            servings=servings,
            preparationTime=preparation_time,
            source=source,
            ingredients=ingredients,
            directions=directions,
        )
        hydrated = dict(params)
        hydrated.update(
            {
                "title": title,
                "description": description,
                "servings": servings,
                "preparationTime": preparation_time,
                "source": source,
                "ingredients": ingredients,
                "directions": directions,
                sqlite_validators.ROW_OBJECTS: [recipe],
                sqlite_validators.NOISE_ROW_OBJECTS: [],
                "text_representation_type": "text_block",
            }
        )
        return hydrated
    if task_name == "MarkorCreateFolder":
        folder_name = str(params.get("folder_name") or "folder_oob_demo")
        hydrated = dict(params)
        hydrated["folder_name"] = folder_name
        return hydrated
    if task_name == "MarkorCreateNote":
        file_name = str(params.get("file_name") or "note_oob_demo.md")
        text = str(params.get("text") or "OOB note body.")
        hydrated = dict(params)
        hydrated["file_name"] = file_name
        hydrated["text"] = text
        return hydrated
    if task_name == "SimpleSmsSend":
        hydrated = dict(params)
        hydrated["number"] = str(hydrated.get("number") or "5551237788")
        hydrated["message"] = str(hydrated.get("message") or "OOB enhanced SMS replay.")
        return hydrated
    if task_name not in {"SimpleCalendarAddOneEvent", "SimpleCalendarAddOneEventTomorrow"}:
        return params
    sys.path.insert(0, str(ANDROID_WORLD_ROOT))
    from android_world.task_evals.common_validators import sqlite_validators
    from android_world.task_evals.utils import sqlite_schema_utils
    from android_world.utils import datetime_utils

    title = str(params.get("event_title") or params.get("title") or "Project Sync")
    description = str(params.get("event_description") or params.get("description") or "Discuss launch plan.")
    day = int(params.get("day") if params.get("day") is not None else 16)
    hour = int(params.get("hour") if params.get("hour") is not None else 16)
    duration_mins = int(params.get("duration_mins") if params.get("duration_mins") is not None else 30)
    start_ts = int(
        params.get("start_ts")
        or datetime_utils._create_unix_ts(day=day, hour=hour, month=10, year=2023)  # pylint: disable=protected-access
    )
    end_ts = int(params.get("end_ts") or (start_ts + duration_mins * 60))
    event = sqlite_schema_utils.CalendarEvent(
        start_ts=start_ts,
        end_ts=end_ts,
        title=title,
        description=description,
    )
    hydrated = dict(params)
    hydrated.update(
        {
            "year": 2023,
            "month": 10,
            "day": day,
            "hour": hour,
            "duration_mins": duration_mins,
            "event_title": title,
            "event_description": description,
            sqlite_validators.ROW_OBJECTS: [event],
            sqlite_validators.NOISE_ROW_OBJECTS: [],
        }
    )
    return hydrated


class OobVerifierEnv:
    """AndroidWorld env proxy whose state UI tree comes from OOB XML.

    AndroidWorld's verifier is still used unchanged. The proxy only avoids the
    broken a11y collection path by providing UIElement objects parsed from the
    OOB XML captured immediately after the scripted trajectory.
    """

    def __init__(self, env: Any, xml: str) -> None:
        sys.path.insert(0, str(ANDROID_WORLD_ROOT))
        from android_world.env import interface
        from android_world.env import representation_utils
        import numpy as np

        self._env = env
        self._state_cls = interface.State
        self._ui_elements = representation_utils.xml_dump_to_ui_elements(xml) if xml.strip() else []
        self._empty_pixels = np.empty((1, 1, 3), dtype=np.uint8)

    def __getattr__(self, name: str) -> Any:
        return getattr(self._env, name)

    @property
    def controller(self) -> Any:
        return self._env.controller

    def get_state(self, wait_to_stabilize: bool = False) -> Any:
        try:
            base_state = self._env.get_state(wait_to_stabilize=wait_to_stabilize)
            pixels = getattr(base_state, "pixels", self._empty_pixels)
            forest = getattr(base_state, "forest", None)
            auxiliaries = dict(getattr(base_state, "auxiliaries", {}) or {})
        except Exception as error:  # AndroidWorld UI capture may be unavailable.
            pixels = self._empty_pixels
            forest = None
            auxiliaries = {"oob_verifier_state_fallback_error": str(error)}
        auxiliaries["oob_verifier_ui_elements_source"] = "oob_xml"
        return self._state_cls(
            pixels=pixels,
            forest=forest,
            ui_elements=self._ui_elements,
            auxiliaries=auxiliaries,
        )


def oob_xml_reward_for_task(task_name: str, task_params: dict[str, Any], xml: str) -> float | None:
    if task_name == "ContactsNewContactDraft":
        text = xml
        first = str(task_params.get("first") or "").strip()
        last = str(task_params.get("last") or "").strip()
        phone = re.sub(r"\D", "", str(task_params.get("phone") or ""))
        phone_label = str(task_params.get("phone_label") or "").strip()
        xml_digits = re.sub(r"\D", "", text)
        non_mobile_phone_label = re.search(
            r"Delete (Home|Work|Work Fax|Home Fax|Pager|Other|Custom|Callback|Car|Company Main|ISDN) Phone",
            text,
        )
        label_ok = phone_label in text or (
            phone_label.lower() == "mobile" and "Mobile Phone" in text
        ) or (phone_label.lower() == "mobile" and non_mobile_phone_label is None)
        success = (
            bool(first)
            and bool(last)
            and bool(phone)
            and bool(phone_label)
            and first in text
            and last in text
            and phone in xml_digits
            and label_ok
            and "Create contact" in text
        )
        return 1.0 if success else 0.0
    return None


def oob_device_reward_for_task(task_name: str, task_params: dict[str, Any], adb: Adb) -> float | None:
    if task_name == "AudioRecorderRecordAudio":
        files = adb.shell(
            "find",
            "/storage/emulated/0/Android/data/com.dimowner.audiorecorder/files/Music/records",
            "-maxdepth",
            "1",
            "-type",
            "f",
            "-size",
            "+0c",
            check=False,
        ).stdout.strip().splitlines()
        return 1.0 if files else 0.0
    if task_name == "AudioRecorderRecordAudioWithFileName":
        file_name = str(task_params.get("file_name") or "").strip()
        if not file_name:
            return 0.0
        expected = file_name + ".m4a"
        exists = adb.shell(
            "test",
            "-f",
            f"/storage/emulated/0/Android/data/com.dimowner.audiorecorder/files/Music/records/{expected}",
            check=False,
        ).returncode == 0
        return 1.0 if exists else 0.0
    if task_name == "CameraTakePhoto":
        files = adb.shell(
            "find",
            "/sdcard/Pictures",
            "/sdcard/DCIM/Camera",
            "-maxdepth",
            "1",
            "-type",
            "f",
            check=False,
        ).stdout.strip().splitlines()
        return 1.0 if files else 0.0
    if task_name == "CameraTakeVideo":
        files = adb.shell("find", "/sdcard/Movies", "-maxdepth", "1", "-type", "f", check=False).stdout.strip().splitlines()
        return 1.0 if files else 0.0
    if task_name == "TurnOffWifiAndTurnOnBluetooth":
        wifi_on = adb.shell("settings", "get", "global", "wifi_on", check=False).stdout.strip()
        bluetooth_on = adb.shell("settings", "get", "global", "bluetooth_on", check=False).stdout.strip()
        return 1.0 if wifi_on == "0" and bluetooth_on == "1" else 0.0
    if task_name == "TurnOnWifiAndOpenApp":
        wifi_on = adb.shell("settings", "get", "global", "wifi_on", check=False).stdout.strip()
        package = OPEN_APP_PACKAGES.get(str(task_params.get("app_name") or "settings"))
        if not package:
            return 0.0
        current = adb.shell("dumpsys", "window", check=False).stdout
        wifi_ok = wifi_on in {"1", "2"}
        app_ok = package in current
        return 1.0 if wifi_ok and app_ok else 0.0
    if task_name in {"SystemWifiTurnOff", "SystemWifiTurnOffVerify", "SystemWifiTurnOn", "SystemWifiTurnOnVerify"}:
        expected = "0" if "TurnOff" in task_name else "1"
        value = adb.shell("settings", "get", "global", "wifi_on", check=False).stdout.strip()
        if expected == "1":
            return 1.0 if value in {"1", "2"} else 0.0
        return 1.0 if value == "0" else 0.0
    if task_name in {
        "SystemBluetoothTurnOff",
        "SystemBluetoothTurnOffVerify",
        "SystemBluetoothTurnOn",
        "SystemBluetoothTurnOnVerify",
    }:
        expected = "0" if "TurnOff" in task_name else "1"
        value = adb.shell("settings", "get", "global", "bluetooth_on", check=False).stdout.strip()
        return 1.0 if value == expected else 0.0
    if task_name in {"SystemBrightnessMax", "SystemBrightnessMaxVerify", "SystemBrightnessMin", "SystemBrightnessMinVerify"}:
        expected = "255" if "Max" in task_name else "1"
        value = adb.shell("settings", "get", "system", "screen_brightness", check=False).stdout.strip()
        return 1.0 if value == expected else 0.0
    if task_name == "MarkorCreateNote":
        file_name = str(task_params.get("file_name") or "").strip()
        expected_text = str(task_params.get("text") or "").strip()
        if not file_name or not expected_text:
            return 0.0
        path = f"/storage/emulated/0/Documents/Markor/{file_name}"
        deadline = time.time() + 20.0
        while True:
            content = adb.shell("cat", path, check=False).stdout
            if expected_text in content:
                return 1.0
            if time.time() >= deadline:
                return 0.0
            time.sleep(0.5)
    if task_name == "SimpleSmsSend":
        number = re.sub(r"\D", "", str(task_params.get("number") or ""))
        message = str(task_params.get("message") or "").strip()
        if not number or not message:
            return 0.0
        rows = adb.shell("content", "query", "--uri", "content://sms/sent", check=False).stdout
        normalized_rows = re.sub(r"\D", "", rows)
        current = adb.shell("dumpsys", "window", check=False).stdout
        return 1.0 if number in normalized_rows and message in rows and "com.simplemobiletools.smsmessenger" in current else 0.0
    return None


def get_task_steps(task_name: str, task_params: dict[str, Any]) -> list[Step]:
    if task_name in TASK_STEPS:
        return TASK_STEPS[task_name]
    if task_name == "BrowserMaze":
        route = browser_maze_route(int(task_params.get("browser_task_seed") or 0))
        return [
            Step(
                "click",
                "接受 Chrome 首次启动条款",
                {"target_description": "Accept & continue"},
                target_regex=r"Accept & continue|terms_accept",
                wait_after_ms=1200,
                optional=True,
            ),
            Step(
                "click",
                "跳过 Chrome 登录",
                {"target_description": "No thanks"},
                target_regex=r"No thanks|Use without an account|negative_button|signin_fre_dismiss_button",
                wait_after_ms=1500,
                optional=True,
            ),
            Step(
                "click",
                "保留 Google 搜索",
                {"target_description": "Keep Google"},
                target_regex=r"Keep Google|button_secondary",
                wait_after_ms=1000,
                optional=True,
            ),
        ] + [
            Step(
                "click",
                f"点击 {direction}",
                {"target_description": direction},
                target_regex=rf"^{re.escape(direction)}$",
                wait_after_ms=500,
            )
            for direction in route
        ]
    if task_name == "AudioRecorderRecordAudio":
        return [
            Step("open_app", "打开 Audio Recorder", {"package_name": "com.dimowner.audiorecorder"}, wait_after_ms=1500),
            Step(
                "click",
                "关闭录音列表警告弹窗",
                {"target_description": "Ok"},
                target_regex=r"dialog_ok_btn|^Ok$",
                wait_after_ms=700,
                optional=True,
            ),
            Step(
                "click",
                "点击录音按钮",
                {"target_description": "Recording: %s"},
                target_regex="Recording: %s|btn_record",
                fallback_relative_xy=(500, 872),
                wait_after_ms=1800,
            ),
            Step(
                "click",
                "点击停止录音",
                {"target_description": "btn_record_stop"},
                target_regex="btn_record_stop",
                fallback_relative_xy=(714, 872),
                wait_after_ms=900,
            ),
            Step(
                "click",
                "保存录音",
                {"target_description": "Save"},
                target_regex="^Save$|dialog_positive_btn",
                fallback_relative_xy=(731, 406),
                wait_after_ms=1500,
            ),
        ]
    if task_name == "AudioRecorderRecordAudioWithFileName":
        file_name = str(task_params.get("file_name") or "").strip()
        if not file_name:
            raise ValueError("AudioRecorderRecordAudioWithFileName requires file_name")
        return [
            Step("open_app", "打开 Audio Recorder", {"package_name": "com.dimowner.audiorecorder"}, wait_after_ms=1500),
            Step(
                "click",
                "关闭录音列表警告弹窗",
                {"target_description": "Ok"},
                target_regex=r"dialog_ok_btn|^Ok$",
                wait_after_ms=700,
                optional=True,
            ),
            Step(
                "click",
                "打开录音设置",
                {"target_description": "Settings"},
                target_regex="Settings|btn_settings",
                fallback_relative_xy=(117, 872),
                wait_after_ms=700,
            ),
            Step(
                "click",
                "选择 M4a 格式",
                {"target_description": "M4a"},
                target_regex="^M4a$",
                fallback_relative_xy=(128, 888),
                wait_after_ms=700,
            ),
            Step(
                "press_key",
                "返回录音主界面",
                {"key": "back"},
                wait_after_ms=900,
            ),
            Step(
                "click",
                "关闭返回后的录音列表警告弹窗",
                {"target_description": "Ok"},
                target_regex=r"dialog_ok_btn|^Ok$",
                wait_after_ms=700,
                optional=True,
            ),
            Step(
                "click",
                "点击录音按钮",
                {"target_description": "Recording: %s"},
                target_regex="Recording: %s|btn_record",
                fallback_relative_xy=(500, 872),
                wait_after_ms=1800,
            ),
            Step(
                "click",
                "点击停止录音",
                {"target_description": "btn_record_stop"},
                target_regex="btn_record_stop",
                fallback_relative_xy=(714, 872),
                wait_after_ms=900,
            ),
            Step(
                "input_text",
                "填写录音文件名",
                {"target_description": "New name", "text": file_name},
                target_regex="input_name|New name",
                fallback_relative_xy=(500, 246),
                wait_after_ms=500,
            ),
            Step(
                "click",
                "保存命名录音",
                {"target_description": "Save"},
                target_regex="^Save$|dialog_positive_btn",
                fallback_relative_xy=(731, 406),
                wait_after_ms=1500,
            ),
        ]
    if task_name == "CameraTakePhoto":
        return [
            Step("open_app", "打开 Camera", {"package_name": "com.android.camera2"}, wait_after_ms=1500),
            Step(
                "click",
                "打开 Camera mode list",
                {"target_description": "MODE LIST"},
                target_regex="MODE LIST|mode_list",
                fallback_relative_xy=(131, 38),
                wait_after_ms=800,
            ),
            Step(
                "click",
                "切换到 Camera 拍照模式",
                {"target_description": "Camera"},
                target_regex="Switch to Camera Mode",
                fallback_relative_xy=(142, 354),
                wait_after_ms=1000,
            ),
            Step(
                "click",
                "点击 Shutter 拍照",
                {"target_description": "Shutter"},
                target_regex="Shutter|shutter_button",
                fallback_relative_xy=(500, 869),
                wait_after_ms=1800,
            ),
        ]
    if task_name == "CameraTakeVideo":
        return [
            Step("open_app", "打开 Camera", {"package_name": "com.android.camera2"}, wait_after_ms=1500),
            Step(
                "click",
                "打开 Camera mode list",
                {"target_description": "MODE LIST"},
                target_regex="MODE LIST|mode_list",
                fallback_relative_xy=(131, 38),
                wait_after_ms=800,
            ),
            Step(
                "click",
                "切换到 Video Camera",
                {"target_description": "Switch to Video Camera"},
                target_regex="Switch to Video Camera|Video Camera",
                fallback_relative_xy=(216, 448),
                wait_after_ms=1000,
            ),
            Step(
                "click",
                "点击 Shutter 开始录像",
                {"target_description": "Shutter"},
                target_regex="Shutter|shutter_button",
                fallback_relative_xy=(500, 869),
                wait_after_ms=2500,
            ),
            Step(
                "click",
                "点击 Shutter 停止录像",
                {"target_description": "Shutter"},
                target_regex="Shutter|shutter_button",
                fallback_relative_xy=(500, 869),
                wait_after_ms=2500,
            ),
        ]
    if task_name == "TurnOffWifiAndTurnOnBluetooth":
        return [
            *get_task_steps("SystemWifiTurnOff", {"on_or_off": "off"}),
            *get_task_steps("SystemBluetoothTurnOn", {"on_or_off": "on"}),
        ]
    if task_name == "TurnOnWifiAndOpenApp":
        app_name = str(task_params.get("app_name") or "settings")
        return [
            *get_task_steps("SystemWifiTurnOn", {"on_or_off": "on"}),
            *get_task_steps("OpenAppTaskEval", {"app_name": app_name}),
        ]
    if task_name == "ClockTimerEntry":
        hours = int(task_params.get("hours") or 0)
        minutes = int(task_params.get("minutes") or 0)
        seconds = int(task_params.get("seconds") or 0)
        digits = f"{hours:02d}{minutes:02d}{seconds:02d}".lstrip("0") or "0"
        steps = [
            Step("open_app", "打开 Clock", {"package_name": "com.google.android.deskclock"}, wait_after_ms=1200),
            Step(
                "click",
                "点击 Timer",
                {"target_description": "Timer"},
                target_regex="Timer|计时器",
                fallback_relative_xy=(500, 882),
                wait_after_ms=800,
            ),
        ]
        for digit in digits:
            steps.append(
                Step(
                    "click",
                    f"输入数字 {digit}",
                    {"target_description": digit},
                    target_regex=rf"timer_setup_digit_{re.escape(digit)}\b",
                    wait_after_ms=250,
                )
            )
        return steps
    if task_name == "OpenAppTaskEval":
        app_name = str(task_params.get("app_name") or "settings")
        package_name = OPEN_APP_PACKAGES.get(app_name)
        if not package_name:
            raise ValueError(f"Unsupported OpenAppTaskEval app_name: {app_name}")
        steps = [
            Step(
                "open_app",
                f"打开 {app_name}",
                {"package_name": package_name},
                wait_after_ms=1200,
            )
        ]
        if app_name == "settings":
            steps.extend(
                [
                    Step(
                        "click",
                        "点击 Network & internet",
                        {"target_description": "Network & internet"},
                        target_regex="Network & internet|网络和互联网|网络和 Internet",
                        wait_after_ms=1000,
                    ),
                    Step(
                        "press_key",
                        "返回 Settings",
                        {"key": "back"},
                        wait_after_ms=1000,
                    ),
                ]
            )
        return steps
    if task_name in {
        "SystemWifiTurnOffVerify",
        "SystemWifiTurnOnVerify",
        "SystemBluetoothTurnOffVerify",
        "SystemBluetoothTurnOnVerify",
        "SystemBrightnessMaxVerify",
        "SystemBrightnessMinVerify",
    }:
        return [
            Step(
                "open_app",
                "打开 Settings",
                {"package_name": "com.android.settings"},
                wait_after_ms=1200,
            ),
            Step(
                "click",
                "点击 Search settings",
                {"target_description": "Search settings"},
                target_regex="Search settings",
                fallback_relative_xy=(500, 354),
                wait_after_ms=700,
            ),
            Step(
                "press_key",
                "返回 Settings 首页",
                {"key": "back"},
                wait_after_ms=700,
            ),
        ]
    if task_name in {"SystemBrightnessMax", "SystemBrightnessMin"}:
        to_max = task_name == "SystemBrightnessMax"
        slider_steps = [
            Step(
                "swipe",
                "将亮度滑到最大" if to_max else "将亮度滑到最小",
                {
                    "x1": 45 if to_max else 640,
                    "y1": 112,
                    "x2": 710 if to_max else 45,
                    "y2": 112,
                    "duration_ms": 650 if to_max else 500,
                },
                wait_after_ms=500,
            )
        ]
        if to_max:
            slider_steps.append(
                Step(
                    "swipe",
                    "再次确认最大亮度",
                    {"x1": 45, "y1": 112, "x2": 710, "y2": 112, "duration_ms": 650},
                    wait_after_ms=700,
                )
            )
        return [
            Step(
                "open_app",
                "打开 Settings",
                {"package_name": "com.android.settings"},
                wait_after_ms=1200,
            ),
            Step(
                "swipe",
                "滚动到 Display",
                {"x1": 500, "y1": 1050, "x2": 500, "y2": 350, "duration_ms": 400},
                wait_after_ms=700,
            ),
            Step(
                "click",
                "点击 Display",
                {"target_description": "Display"},
                target_regex="Display|Dark theme, font size, brightness|显示",
                fallback_relative_xy=(500, 539),
                wait_after_ms=900,
            ),
            Step(
                "click",
                "点击 Brightness level",
                {"target_description": "Brightness level"},
                target_regex="Brightness level|亮度级别|亮度",
                fallback_relative_xy=(500, 458),
                wait_after_ms=800,
            ),
            *slider_steps,
        ]
    if task_name in {"SystemWifiTurnOff", "SystemWifiTurnOn"}:
        target = str(task_params.get("on_or_off") or ("off" if task_name == "SystemWifiTurnOff" else "on"))
        return [
            Step(
                "open_app",
                "打开 Settings",
                {"package_name": "com.android.settings"},
                wait_after_ms=1200,
            ),
            Step(
                "click",
                "点击 Network & internet",
                {"target_description": "Network & internet"},
                target_regex="Network & internet|Network and internet|Mobile, Wi",
                fallback_relative_xy=(500, 488),
                wait_after_ms=900,
            ),
            Step(
                "click",
                "点击 Internet",
                {"target_description": "Internet"},
                target_regex="^Internet$|Networks available",
                fallback_relative_xy=(500, 378),
                wait_after_ms=1200,
            ),
            Step(
                "click",
                f"切换 Wi-Fi {target}",
                {"target_description": "Wi-Fi"},
                target_regex="^Wi-Fi$|^Wifi$",
                fallback_relative_xy=(500, 490),
                wait_after_ms=2500,
            ),
        ]
    if task_name in {"SystemBluetoothTurnOff", "SystemBluetoothTurnOn"}:
        target = str(task_params.get("on_or_off") or ("off" if task_name == "SystemBluetoothTurnOff" else "on"))
        return [
            Step(
                "open_app",
                "打开 Settings",
                {"package_name": "com.android.settings"},
                wait_after_ms=1200,
            ),
            Step(
                "click",
                "点击 Connected devices",
                {"target_description": "Connected devices"},
                target_regex="Connected devices|Bluetooth, pairing",
                fallback_relative_xy=(500, 626),
                wait_after_ms=900,
            ),
            Step(
                "click",
                "点击 Connection preferences",
                {"target_description": "Connection preferences"},
                target_regex="Connection preferences|Bluetooth, Android Auto",
                fallback_relative_xy=(500, 702),
                wait_after_ms=900,
            ),
            Step(
                "click",
                "点击 Bluetooth",
                {"target_description": "Bluetooth"},
                target_regex="^Bluetooth$",
                fallback_relative_xy=(500, 429),
                wait_after_ms=900,
            ),
            Step(
                "click",
                f"切换 Bluetooth {target}",
                {"target_description": "Use Bluetooth"},
                target_regex="Use Bluetooth",
                fallback_relative_xy=(500, 401),
                wait_after_ms=2500,
            ),
        ]
    if task_name == "ContactsAddContact":
        name = str(task_params.get("name") or "").strip()
        number = str(task_params.get("number") or "").strip()
        if not name or not number:
            raise ValueError("ContactsAddContact requires name and number")
        return [
            Step(
                "open_app",
                "打开 Contacts",
                {"package_name": "com.google.android.contacts"},
                wait_after_ms=1500,
            ),
            Step(
                "click",
                "点击 Create contact",
                {"target_description": "Create contact"},
                target_regex="Create contact|Create new contact|Add contact|新增联系人|新建联系人|创建联系人|floating_action_button",
                fallback_relative_xy=(860, 870),
                wait_after_ms=1200,
            ),
            Step(
                "input_text",
                "填写联系人姓名",
                {"target_description": "First name", "text": name},
                target_regex="First name|Name|姓名",
                fallback_relative_xy=(330, 340),
                wait_after_ms=800,
            ),
            Step(
                "input_text",
                "填写电话号码",
                {"target_description": "Phone", "text": number},
                target_regex="Phone|电话|号码",
                fallback_relative_xy=(330, 560),
                wait_after_ms=800,
            ),
            Step(
                "click",
                "点击 Save",
                {"target_description": "Save"},
                target_regex="Save|保存|Done|完成",
                fallback_relative_xy=(920, 60),
                wait_after_ms=1500,
            ),
        ]
    if task_name == "ContactsNewContactDraft":
        first = str(task_params.get("first") or "").strip()
        last = str(task_params.get("last") or "").strip()
        phone = str(task_params.get("phone") or "").strip()
        phone_label = str(task_params.get("phone_label") or "").strip()
        if not first or not last or not phone or not phone_label:
            raise ValueError("ContactsNewContactDraft requires first, last, phone, and phone_label")
        steps = [
            Step(
                "open_app",
                "打开 Contacts",
                {"package_name": "com.google.android.contacts"},
                wait_after_ms=1500,
            ),
            Step(
                "click",
                "点击 Create contact",
                {"target_description": "Create contact"},
                target_regex="Create contact|Create new contact|Add contact|新增联系人|新建联系人|创建联系人|floating_action_button",
                fallback_relative_xy=(860, 870),
                wait_after_ms=1200,
            ),
            Step(
                "input_text",
                "填写名字",
                {"target_description": "First name", "text": first},
                target_regex="First name|名字|名",
                fallback_relative_xy=(330, 340),
                wait_after_ms=700,
            ),
            Step(
                "input_text",
                "填写姓氏",
                {"target_description": "Last name", "text": last},
                target_regex="Last name|姓氏|姓",
                fallback_relative_xy=(330, 450),
                wait_after_ms=700,
            ),
            Step(
                "input_text",
                "填写电话号码",
                {"target_description": "Phone", "text": phone},
                target_regex="Phone|电话|号码",
                fallback_relative_xy=(330, 560),
                wait_after_ms=700,
            ),
        ]
        if phone_label.lower() == "mobile":
            return steps
        return steps + [
            Step(
                "click",
                "点击 Phone label",
                {"target_description": "Show dropdown menu"},
                target_regex="Show dropdown menu|text_input_end_icon",
                fallback_relative_xy=(553, 951),
                wait_after_ms=800,
            ),
            Step(
                "click",
                f"选择 {phone_label}",
                {"target_description": phone_label},
                target_regex=rf"{re.escape(phone_label)}",
                fallback_relative_xy=(330, 760),
                wait_after_ms=1000,
            ),
        ]
    if task_name in {"SimpleCalendarAddOneEvent", "SimpleCalendarAddOneEventTomorrow"}:
        title = str(task_params.get("event_title") or task_params.get("title") or "").strip()
        description = str(task_params.get("event_description") or task_params.get("description") or "").strip()
        day = int(task_params.get("day") if task_params.get("day") is not None else 16)
        hour = int(task_params.get("hour") if task_params.get("hour") is not None else 16)
        duration_mins = int(task_params.get("duration_mins") if task_params.get("duration_mins") is not None else 30)
        if not title or not description:
            raise ValueError(f"{task_name} requires event_title and event_description")
        if day != 16 or hour != 16 or duration_mins != 30:
            raise ValueError(f"{task_name} v1 script supports day=16, hour=16, duration_mins=30")
        return [
            Step(
                "open_app",
                "打开 Simple Calendar Pro",
                {"package_name": "com.simplemobiletools.calendar.pro"},
                wait_after_ms=1500,
            ),
            Step(
                "click",
                "点击 New Event",
                {"target_description": "New Event"},
                target_regex="New Event|calendar_fab",
                fallback_relative_xy=(878, 894),
                wait_after_ms=700,
            ),
            Step(
                "click",
                "选择 Event",
                {"target_description": "Event"},
                target_regex="^Event$|fab_event_label",
                fallback_relative_xy=(717, 894),
                wait_after_ms=1000,
            ),
            Step(
                "input_text",
                "填写事件标题",
                {"target_description": "Title", "text": title},
                target_regex="Title|event_title",
                fallback_relative_xy=(500, 197),
                wait_after_ms=700,
            ),
            Step(
                "input_text",
                "填写事件描述",
                {"target_description": "Description", "text": description},
                target_regex="Description|event_description",
                fallback_relative_xy=(500, 384),
                wait_after_ms=700,
            ),
            Step(
                "press_key",
                "收起键盘",
                {"key": "BACK"},
                wait_after_ms=700,
            ),
            Step(
                "swipe",
                "滚动到日期时间区域",
                {
                    "x1": 360,
                    "y1": 1050,
                    "x2": 360,
                    "y2": 300,
                    "duration_ms": 600,
                    "target_description": "scroll event form down",
                },
                wait_after_ms=900,
            ),
            Step(
                "click",
                "点击开始日期",
                {"target_description": "October 15 start date"},
                target_regex="event_start_date|October 15",
                fallback_relative_xy=(367, 320),
                wait_after_ms=700,
            ),
            Step(
                "click",
                "选择 16 October 2023",
                {"target_description": "16 October 2023"},
                target_regex="16 October 2023|^16$",
                fallback_relative_xy=(278, 574),
                wait_after_ms=400,
            ),
            Step(
                "click",
                "确认日期",
                {"target_description": "OK"},
                target_regex="^OK$",
                fallback_relative_xy=(822, 837),
                wait_after_ms=900,
            ),
            Step(
                "click",
                "点击开始时间",
                {"target_description": "start time"},
                target_regex="event_start_time",
                fallback_relative_xy=(875, 320),
                wait_after_ms=700,
            ),
            Step(
                "click",
                "切换开始时间为文字输入",
                {"target_description": "Switch to text input mode"},
                target_regex="Switch to text input mode|material_timepicker_mode_button",
                fallback_relative_xy=(178, 847),
                wait_after_ms=500,
            ),
            Step(
                "input_text",
                "设置开始小时 16",
                {"target_description": "start hour", "text": "16"},
                target_regex=r"^(00|16)$",
                fallback_relative_xy=(333, 288),
                wait_after_ms=500,
            ),
            Step(
                "click",
                "聚焦开始分钟",
                {"target_description": "start minute"},
                target_regex="0 minutes|00 minutes|^00$",
                fallback_relative_xy=(667, 288),
                wait_after_ms=400,
            ),
            Step(
                "input_text",
                "设置开始分钟 00",
                {"target_description": "start minute", "text": "00"},
                target_regex=r"^(00|30)$",
                fallback_relative_xy=(667, 288),
                wait_after_ms=500,
            ),
            Step(
                "click",
                "确认开始时间",
                {"target_description": "OK"},
                target_regex="^OK$|material_timepicker_ok_button",
                fallback_relative_xy=(756, 454),
                wait_after_ms=900,
            ),
            Step(
                "click",
                "点击结束时间",
                {"target_description": "end time"},
                target_regex="event_end_time",
                fallback_relative_xy=(875, 405),
                wait_after_ms=700,
            ),
            Step(
                "click",
                "切换结束时间为文字输入",
                {"target_description": "Switch to text input mode"},
                target_regex="Switch to text input mode|material_timepicker_mode_button",
                fallback_relative_xy=(178, 847),
                wait_after_ms=500,
            ),
            Step(
                "input_text",
                "设置结束小时 16",
                {"target_description": "end hour", "text": "16"},
                target_regex=r"^(00|16)$",
                fallback_relative_xy=(333, 288),
                wait_after_ms=500,
            ),
            Step(
                "click",
                "聚焦结束分钟",
                {"target_description": "end minute"},
                target_regex="0 minutes|00 minutes|^00$",
                fallback_relative_xy=(667, 288),
                wait_after_ms=400,
            ),
            Step(
                "input_text",
                "设置结束分钟 30",
                {"target_description": "end minute", "text": "30"},
                target_regex=r"^(00|30)$",
                fallback_relative_xy=(667, 288),
                wait_after_ms=500,
            ),
            Step(
                "click",
                "确认结束时间",
                {"target_description": "OK"},
                target_regex="^OK$|material_timepicker_ok_button",
                fallback_relative_xy=(756, 454),
                wait_after_ms=900,
            ),
            Step(
                "click",
                "点击 Save",
                {"target_description": "Save"},
                target_regex="Save|保存",
                fallback_relative_xy=(933, 88),
                wait_after_ms=1000,
            ),
            Step(
                "click",
                "确认提醒免责声明",
                {"target_description": "OK"},
                target_regex="^OK$",
                fallback_relative_xy=(753, 667),
                wait_after_ms=1800,
            ),
        ]
    if task_name == "RecipeAddSingleRecipe":
        title = str(task_params.get("title") or "").strip()
        description = str(task_params.get("description") or "").strip()
        servings = str(task_params.get("servings") or "").strip()
        preparation_time = str(task_params.get("preparationTime") or task_params.get("preparation_time") or "").strip()
        source = str(task_params.get("source") or "").strip()
        ingredients = str(task_params.get("ingredients") or "").strip()
        directions = str(task_params.get("directions") or "").strip()
        if not all([title, description, servings, preparation_time, source, ingredients, directions]):
            raise ValueError("RecipeAddSingleRecipe requires title, description, servings, preparationTime, source, ingredients, and directions")
        return [
            Step(
                "open_app",
                "打开 Broccoli",
                {"package_name": "com.flauschcode.broccoli"},
                wait_after_ms=1500,
            ),
            Step(
                "click",
                "点击 New Recipe",
                {"target_description": "New Recipe"},
                target_regex="New Recipe|fab_recipes",
                fallback_relative_xy=(878, 894),
                wait_after_ms=900,
            ),
            Step(
                "input_text",
                "填写 recipe title",
                {"target_description": "Title", "text": title},
                target_regex="Title|new_title",
                fallback_relative_xy=(500, 508),
                wait_after_ms=700,
            ),
            Step(
                "swipe",
                "滚动到 recipe 详情字段",
                {
                    "x1": 360,
                    "y1": 1120,
                    "x2": 360,
                    "y2": 420,
                    "duration_ms": 600,
                    "target_description": "scroll recipe form down",
                },
                wait_after_ms=900,
            ),
            Step(
                "input_text",
                "填写 description",
                {"target_description": "Description", "text": description},
                target_regex="Description|new_description",
                fallback_relative_xy=(500, 261),
                wait_after_ms=600,
            ),
            Step(
                "input_text",
                "填写 source",
                {"target_description": "Source", "text": source},
                target_regex="Source|new_source",
                fallback_relative_xy=(500, 382),
                wait_after_ms=600,
            ),
            Step(
                "input_text",
                "填写 servings",
                {"target_description": "Servings", "text": servings},
                target_regex="Servings|new_servings",
                fallback_relative_xy=(500, 502),
                wait_after_ms=600,
            ),
            Step(
                "input_text",
                "填写 preparation time",
                {"target_description": "Time", "text": preparation_time},
                target_regex="Time|new_preparation_time",
                fallback_relative_xy=(500, 622),
                wait_after_ms=600,
            ),
            Step(
                "input_text",
                "填写 ingredients",
                {"target_description": "Ingredients", "text": ingredients},
                target_regex="Ingredients|new_ingredients",
                fallback_relative_xy=(500, 743),
                wait_after_ms=600,
            ),
            Step(
                "input_text",
                "填写 directions",
                {"target_description": "Directions", "text": directions},
                target_regex="Directions|new_directions",
                fallback_relative_xy=(500, 863),
                wait_after_ms=700,
            ),
            Step(
                "click",
                "点击 SAVE",
                {"target_description": "SAVE"},
                target_regex="SAVE|button_save_recipe",
                fallback_relative_xy=(867, 81),
                wait_after_ms=1800,
            ),
        ]
    if task_name == "MarkorCreateFolder":
        folder_name = str(task_params.get("folder_name") or "").strip()
        if not folder_name:
            raise ValueError("MarkorCreateFolder requires folder_name")
        return [
            Step(
                "open_app",
                "打开 Markor",
                {"package_name": "net.gsantner.markor"},
                wait_after_ms=1500,
            ),
            Step(
                "click",
                "点击 Create a new file or folder",
                {"target_description": "Create a new file or folder"},
                target_regex="Create a new file or folder|fab_add_new_item",
                fallback_relative_xy=(878, 806),
                wait_after_ms=800,
            ),
            Step(
                "input_text",
                "填写 folder name",
                {"target_description": "Name", "text": folder_name},
                target_regex="new_file_dialog__name|Name|my_note",
                fallback_relative_xy=(345, 129),
                wait_after_ms=500,
            ),
            Step(
                "click",
                "点击 FOLDER",
                {"target_description": "FOLDER"},
                target_regex="^FOLDER$",
                fallback_relative_xy=(181, 458),
                wait_after_ms=1400,
            ),
        ]
    if task_name == "MarkorCreateNote":
        file_name = str(task_params.get("file_name") or "").strip()
        text = str(task_params.get("text") or "").strip()
        if not file_name or not text:
            raise ValueError("MarkorCreateNote requires file_name and text")
        if "." in file_name:
            name_base, extension = file_name.rsplit(".", 1)
            extension = "." + extension
        else:
            name_base, extension = file_name, ".md"
        if extension != ".md":
            raise ValueError("MarkorCreateNote v1 script supports .md notes")
        return [
            Step(
                "open_app",
                "打开 Markor",
                {"package_name": "net.gsantner.markor"},
                wait_after_ms=1500,
            ),
            Step(
                "click",
                "点击 Create a new file or folder",
                {"target_description": "Create a new file or folder"},
                target_regex="Create a new file or folder|fab_add_new_item",
                fallback_relative_xy=(878, 377),
                wait_after_ms=800,
            ),
            Step(
                "input_text",
                "填写 note file name",
                {"target_description": "Name", "text": name_base},
                target_regex="new_file_dialog__name|Name|my_note",
                fallback_relative_xy=(345, 134),
                wait_after_ms=500,
            ),
            Step(
                "click",
                "点击 OK",
                {"target_description": "OK"},
                target_regex=r"\bOK\b|android:id/button1",
                fallback_relative_xy=(833, 673),
                wait_after_ms=1600,
            ),
            Step(
                "input_text",
                "填写 note content",
                {
                    "target_description": "document__fragment__edit__highlighting_editor",
                    "text": text,
                },
                target_regex="document__fragment__edit__highlighting_editor",
                fallback_relative_xy=(500, 293),
                wait_after_ms=700,
            ),
            Step(
                "press_key",
                "隐藏键盘",
                {"key": "back"},
                wait_after_ms=700,
            ),
            Step(
                "click",
                "点击 Save",
                {"target_description": "Save"},
                target_regex=r"\bSave\b|action_save|menu_save",
                fallback_relative_xy=(722, 81),
                wait_after_ms=1800,
            ),
            Step(
                "press_key",
                "返回文件列表",
                {"key": "back"},
                wait_after_ms=1200,
            ),
            Step(
                "press_key",
                "回到桌面触发保存",
                {"key": "home"},
                wait_after_ms=2200,
            ),
        ]
    if task_name == "SimpleSmsSend":
        number = str(task_params.get("number") or "").strip()
        message = str(task_params.get("message") or "").strip()
        if not number or not message:
            raise ValueError("SimpleSmsSend requires number and message")
        return [
            Step(
                "open_app",
                "打开 Simple SMS Messenger",
                {"package_name": "com.simplemobiletools.smsmessenger"},
                wait_after_ms=1500,
            ),
            Step(
                "click",
                "点击 Start a conversation",
                {"target_description": "Start a conversation"},
                target_regex="Start a conversation|conversations_fab",
                fallback_relative_xy=(500, 244),
                wait_after_ms=1200,
            ),
            Step(
                "input_text",
                "输入短信号码",
                {"target_description": "Add Contact or Number", "text": number},
                target_regex="Add Contact or Number|new_conversation_address",
                fallback_relative_xy=(500, 175),
                wait_after_ms=700,
            ),
            Step(
                "click",
                "确认短信收件人",
                {"target_description": "Confirm recipient"},
                target_regex="new_conversation_confirm",
                fallback_relative_xy=(933, 175),
                wait_after_ms=1200,
            ),
            Step(
                "input_text",
                "输入短信内容",
                {"target_description": "Type a message", "text": message},
                target_regex="Type a message|thread_type_message",
                fallback_relative_xy=(500, 495),
                wait_after_ms=700,
            ),
            Step(
                "click",
                "点击发送短信",
                {"target_description": "Send SMS"},
                target_regex="thread_send_message|OK|SMS",
                fallback_relative_xy=(922, 495),
                wait_after_ms=1800,
            ),
        ]
    raise ValueError(f"Unsupported scripted task: {task_name}")


def instantiate_task(task_name: str, env: Any, seed: int | None, params_override: dict[str, Any] | None) -> Any:
    registry, _ = load_androidworld()
    task_registry = registry.TaskRegistry()
    aw_registry = task_registry.get_registry(task_registry.ANDROID_WORLD_FAMILY)
    if task_name not in aw_registry:
        raise ValueError(f"Unknown AndroidWorld task: {task_name}")
    task_type = aw_registry[task_name]
    task_type.set_device_time(env)
    if seed is not None:
        import random

        random.seed(seed)
    params = dict(params_override) if params_override is not None else task_type.generate_random_params()
    params = hydrate_androidworld_params(task_name, params)
    return task_type(dict(params))


def collect_verified(args: argparse.Namespace) -> dict[str, Any]:
    _, env_launcher = load_androidworld()
    from android_world.env import android_world_controller

    if args.task.startswith("Markor") or args.task.startswith("Camera") or args.task.startswith("AudioRecorder"):
        patch_androidworld_clear_directory_empty_glob()

    if args.androidworld_a11y_method == "uiautomator":
        original_controller = android_world_controller.AndroidWorldController
        android_world_controller.AndroidWorldController = lambda env: original_controller(  # type: ignore[assignment]
            env,
            a11y_method=android_world_controller.A11yMethod.UIAUTOMATOR,
            install_a11y_forwarding_app=False,
        )
    elif args.androidworld_a11y_method == "none":
        original_controller = android_world_controller.AndroidWorldController
        android_world_controller.AndroidWorldController = lambda env: original_controller(  # type: ignore[assignment]
            env,
            a11y_method=android_world_controller.A11yMethod.NONE,
            install_a11y_forwarding_app=False,
        )
    env = env_launcher.load_and_setup_env(
        console_port=args.console_port,
        grpc_port=args.grpc_port,
        adb_path=args.adb_path,
        emulator_setup=False,
        freeze_datetime=True,
    )
    adb = Adb(args.adb_path, args.device, args.package)
    host = OobHost(args.port)
    if args.task.startswith("Markor"):
        adb.shell("mkdir", "-p", "/storage/emulated/0/Documents/Markor", check=False)
        adb.shell("sh", "-c", "touch /storage/emulated/0/Documents/Markor/.oob_keep", check=False)
    if args.task.startswith("Camera"):
        adb.shell("mkdir", "-p", "/sdcard/Pictures", "/sdcard/Movies", check=False)
    if args.task.startswith("AudioRecorder"):
        adb.shell(
            "mkdir",
            "-p",
            "/storage/emulated/0/Android/data/com.dimowner.audiorecorder/files/Music/records",
            check=False,
        )
    run_id = f"androidworld_verified_{args.task}_{int(time.time())}"
    output_root = args.output_dir.expanduser().resolve()
    artifact_dir = output_root / f"{run_id}.artifacts"
    cards: list[dict[str, Any]] = []
    started_at_ms = now_ms()
    reward = 0.0
    task_goal = ""
    task_params: dict[str, Any] = {}
    verifier_diagnostics: dict[str, Any] = {}
    browser_prepare: dict[str, Any] | None = None
    browser_reopen_attempts: list[dict[str, Any]] = []
    try:
        env.reset(go_home=True)
        params_override = json.loads(args.task_params_json) if args.task_params_json else None
        task = instantiate_task(args.task, env, args.seed, params_override)
        task_goal = str(task.goal)
        task_params = json_safe(dict(getattr(task, "params", {}) or {}))
        task.initialize_task(env)
        adb.prepare_oob()
        if args.task.startswith("Browser"):
            browser_prepare = open_browser_task_html(adb)
        # Confirm host is up after AndroidWorld setup and OOB relaunch.
        host.get("/health", timeout=10)
        steps = get_task_steps(args.task, task_params)
        for index, step in enumerate(steps):
            before = capture_state_for_step(host, step, image_quality=args.image_quality)
            if (
                args.task.startswith("Browser")
                and not step.optional
                and step.target_regex
                and not find_xml_target(str(before.get("xml") or ""), step.target_regex)
            ):
                reopen_result = open_browser_task_html(adb)
                browser_reopen_attempts.append({
                    "step_index": index,
                    "target_regex": step.target_regex,
                    "result": reopen_result,
                })
                before = capture_state_for_step(host, step, image_quality=args.image_quality)
            if step.optional and step.target_regex and not find_xml_target(str(before.get("xml") or ""), step.target_regex):
                continue
            before_ref = save_state_artifacts(before, artifact_dir, f"step_{index + 1:02d}_before")
            step_started = now_ms()
            step_args, result = execute_step(host, step, before)
            if step.wait_after_ms > 0:
                time.sleep(step.wait_after_ms / 1000.0)
            step_finished = now_ms()
            after = capture_state(host, image_quality=args.image_quality)
            if (
                step.optional
                and step.tool == "click"
                and result.get("success")
                and step.target_regex
                and find_xml_target(str(after.get("xml") or ""), step.target_regex)
            ):
                retry_started = now_ms()
                retry_result = host.post("/act", {"tool": step.tool, "args": step_args}, timeout=60)
                if step.wait_after_ms > 0:
                    time.sleep(step.wait_after_ms / 1000.0)
                after = capture_state(host, image_quality=args.image_quality)
                result = {
                    **result,
                    "optional_click_retry": {
                        "started_at_ms": retry_started,
                        "finished_at_ms": now_ms(),
                        "result": retry_result,
                    },
                }
            after_ref = save_state_artifacts(after, artifact_dir, f"step_{index + 1:02d}_after")
            cards.append(
                build_card(
                    run_id=run_id,
                    index=index,
                    step=step,
                    args=step_args,
                    result=result,
                    before=before,
                    after=after,
                    before_ref=before_ref,
                    after_ref=after_ref,
                    started_at_ms=step_started,
                    finished_at_ms=step_finished,
                )
            )
            if not result.get("success") and not args.continue_on_error:
                break
        time.sleep(args.verify_settle_seconds)
        final_state = capture_state(host, image_quality=args.image_quality)
        final_xml = str(final_state.get("xml") or "")
        oob_xml_reward = oob_xml_reward_for_task(args.task, task_params, final_xml) if args.verify_state_source == "oob_xml" else None
        oob_device_reward = oob_device_reward_for_task(args.task, task_params, adb)
        verifier_env = OobVerifierEnv(env, final_xml) if args.verify_state_source == "oob_xml" else env
        reward = float(
            oob_device_reward
            if oob_device_reward is not None
            else oob_xml_reward
            if oob_xml_reward is not None
            else task.is_successful(verifier_env)
        )
        verifier_ui_elements = verifier_env.get_state().ui_elements
        verifier_diagnostics = {
            "verify_state_source": args.verify_state_source,
            "oob_xml_reward_override": oob_xml_reward is not None,
            "oob_device_reward_override": oob_device_reward is not None,
            "ui_element_count": len(verifier_ui_elements),
            "matched_stopwatch_running_evidence": [
                {
                    "text": element.text,
                    "content_description": element.content_description,
                    "resource_id": element.resource_id,
                }
                for element in verifier_ui_elements
                if element.content_description in {"Pause", "Lap"} or element.text == "Stopwatch"
            ],
            "package_name": final_state.get("package_name"),
            "activity_name": final_state.get("activity_name"),
        }
    finally:
        env.close()
    finished_at_ms = now_ms()
    action_success = all(card.get("success") is True for card in cards)
    androidworld_success = reward == 1.0
    payload = {
        "run_id": run_id,
        "payload": {
            "success": action_success and androidworld_success,
            "provider": "internal_oob",
            "run_id": run_id,
            "goal": task_goal,
            "source": "androidworld_verified_scripted_collection",
            "tool_name": "androidworld_scripted_trajectory",
            "operation_description": task_goal,
            "androidworld_task": args.task,
            "androidworld_params": task_params,
            "androidworld_reward": reward,
            "androidworld_success": androidworld_success,
            "started_at_ms": started_at_ms,
            "finished_at_ms": finished_at_ms,
            "started_at": dt.datetime.fromtimestamp(started_at_ms / 1000).astimezone().isoformat(),
            "finished_at": dt.datetime.fromtimestamp(finished_at_ms / 1000).astimezone().isoformat(),
            "run_finished": True,
            "run_success": action_success and androidworld_success,
            "run_status": "success" if action_success and androidworld_success else "failed",
            "duration_ms": max(0, finished_at_ms - started_at_ms),
            "step_count": len(cards),
            "done_reason": "androidworld_verified" if androidworld_success else "androidworld_reward_not_satisfied",
            "diagnostics": {
                "schema_version": "oob.androidworld_verified_scripted_collection.v1",
                "artifact_dir": str(artifact_dir),
                "device": args.device,
                "console_port": args.console_port,
                "grpc_port": args.grpc_port,
                "browser_prepare": browser_prepare,
                "browser_reopen_attempts": browser_reopen_attempts,
                "androidworld_verifier": verifier_diagnostics,
            },
            "cards": cards,
        },
    }
    output_root.mkdir(parents=True, exist_ok=True)
    output_path = output_root / f"{run_id}.run_log.json"
    output_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output_root / "latest.verified.run_log.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    return {
        "task": args.task,
        "goal": task_goal,
        "androidworld_reward": reward,
        "androidworld_success": androidworld_success,
        "action_success": action_success,
        "run_id": run_id,
        "step_count": len(cards),
        "output_path": str(output_path),
        "artifact_dir": str(artifact_dir),
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--task", default="ClockStopWatchRunning", choices=SUPPORTED_TASKS)
    parser.add_argument("--device", default="emulator-5556")
    parser.add_argument("--console-port", type=int, default=5556)
    parser.add_argument("--grpc-port", type=int, default=8556)
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--adb-path", default=find_adb())
    parser.add_argument("--seed", type=int)
    parser.add_argument("--task-params-json", help="JSON params passed directly to the AndroidWorld task instance.")
    parser.add_argument("--image-quality", choices=["low", "medium", "high"], default="low")
    parser.add_argument("--androidworld-a11y-method", choices=["uiautomator", "none", "forwarder"], default="uiautomator")
    parser.add_argument("--verify-state-source", choices=["oob_xml", "androidworld"], default="oob_xml")
    parser.add_argument("--verify-settle-seconds", type=float, default=1.0)
    parser.add_argument("--continue-on-error", action="store_true")
    parser.add_argument("--output-dir", type=Path, default=repo_root() / "runtime" / "androidworld_verified_trajectories")
    return parser.parse_args()


def main() -> int:
    result = collect_verified(parse_args())
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["androidworld_success"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
