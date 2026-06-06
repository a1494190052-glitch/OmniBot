#!/usr/bin/env python3
"""Script AndroidWorld tasks while collecting OOB XML/screenshot evidence.

No model is called. AndroidWorld owns reset, task initialization, and final
verification. OOB owns state capture and primitive action execution.
"""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import json
import re
import subprocess
import sys
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
ANDROID_WORLD_ROOT = Path.home() / "Projects" / "android_world"


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


TASK_STEPS: dict[str, list[Step]] = {
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
}

SUPPORTED_TASKS = sorted(set(TASK_STEPS) | {"ClockTimerEntry", "OpenAppTaskEval"})


BOUNDS_RE = re.compile(r"\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]")


def now_ms() -> int:
    return time.time_ns() // 1_000_000


def capture_state(host: OobHost, *, image_quality: str) -> dict[str, Any]:
    return host.get(
        "/get_state",
        {
            "includeXml": True,
            "includeScreenshot": True,
            "includeIndexedContext": True,
            "maxXmlChars": 0,
            "imageQuality": image_quality,
            "filterOverlay": True,
        },
        timeout=45,
    )


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
    if step.tool == "click":
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
        else:
            return args, {"success": False, "error": "target_not_found", "target_regex": step.target_regex}
    result = host.post("/act", {"tool": step.tool, "args": args}, timeout=60)
    return args, result


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


def get_task_steps(task_name: str, task_params: dict[str, Any]) -> list[Step]:
    if task_name in TASK_STEPS:
        return TASK_STEPS[task_name]
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
        return [
            Step(
                "open_app",
                f"打开 {app_name}",
                {"package_name": package_name},
                wait_after_ms=1200,
            )
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
    return task_type(dict(params))


def collect_verified(args: argparse.Namespace) -> dict[str, Any]:
    _, env_launcher = load_androidworld()
    from android_world.env import android_world_controller

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
    run_id = f"androidworld_verified_{args.task}_{int(time.time())}"
    output_root = args.output_dir.expanduser().resolve()
    artifact_dir = output_root / f"{run_id}.artifacts"
    cards: list[dict[str, Any]] = []
    started_at_ms = now_ms()
    reward = 0.0
    task_goal = ""
    task_params: dict[str, Any] = {}
    verifier_diagnostics: dict[str, Any] = {}
    try:
        env.reset(go_home=True)
        params_override = json.loads(args.task_params_json) if args.task_params_json else None
        task = instantiate_task(args.task, env, args.seed, params_override)
        task_goal = str(task.goal)
        task_params = dict(getattr(task, "params", {}) or {})
        task.initialize_task(env)
        adb.prepare_oob()
        # Confirm host is up after AndroidWorld setup and OOB relaunch.
        host.get("/health", timeout=10)
        steps = get_task_steps(args.task, task_params)
        for index, step in enumerate(steps):
            before = capture_state(host, image_quality=args.image_quality)
            before_ref = save_state_artifacts(before, artifact_dir, f"step_{index + 1:02d}_before")
            step_started = now_ms()
            step_args, result = execute_step(host, step, before)
            if step.wait_after_ms > 0:
                time.sleep(step.wait_after_ms / 1000.0)
            step_finished = now_ms()
            after = capture_state(host, image_quality=args.image_quality)
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
        verifier_env = OobVerifierEnv(env, final_xml) if args.verify_state_source == "oob_xml" else env
        reward = float(task.is_successful(verifier_env))
        verifier_ui_elements = verifier_env.get_state().ui_elements
        verifier_diagnostics = {
            "verify_state_source": args.verify_state_source,
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
