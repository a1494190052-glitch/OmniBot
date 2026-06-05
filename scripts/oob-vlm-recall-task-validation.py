#!/usr/bin/env python3
"""Run an OOB VLM task on emulators and report Function recall evidence.

This script intentionally keeps the Android execution inside the OOB APK. Python
only prepares the device, sends debug broadcasts, and summarizes the returned
JSON so we can verify the real VLM API path, Function recall payload, and the
composable segment/function execution hypothesis.
"""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from typing import Any


DEFAULT_PACKAGE = "cn.com.omnimind.bot.debug"
DEFAULT_DEVICES = ("emulator-5554", "emulator-5556")
DEFAULT_PROFILE_ID = "profile-dashscope"
DEFAULT_MODEL_ID = os.environ.get("OMNIMIND_MODEL") or os.environ.get("OPENAI_MODEL") or "qwen-vl-plus"
DEFAULT_BASE_URL = os.environ.get("OMNIMIND_API_BASE_URL") or "https://dashscope.aliyuncs.com/compatible-mode/v1"
DEFAULT_API_KEY_ENV = os.environ.get("OMNIMIND_API_KEY_ENV") or "DASHSCOPE_API_KEY"
DEFAULT_TARGET_PACKAGE = "com.android.settings"
DEFAULT_TASK = "打开设置"
DEFAULT_SEED_GOAL = "Validate reusable Function segment"

VLM_ACTION = "cn.com.omnimind.bot.debug.RUN_VLM_RUNLOG"
RECALL_ACTION = "cn.com.omnimind.bot.debug.RUN_OOB_RECALL"
SEGMENT_ACTION = "cn.com.omnimind.bot.debug.VALIDATE_OOB_FUNCTION_SEGMENT"
AGENT_FUNCTION_ACTION = "cn.com.omnimind.bot.debug.RUN_AGENT_FUNCTION_MANAGEMENT_VALIDATION"
VLM_RESULT_FILE = "files/debug-vlm-runlog-result.json"
RECALL_RESULT_FILE = "files/debug-oob-recall-result.json"
SEGMENT_RESULT_FILE = "files/debug-oob-function-segment-result.json"
AGENT_FUNCTION_RESULT_FILE = "files/debug-agent-function-management-result.json"


def repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def run_command(
    command: list[str],
    *,
    timeout: int = 60,
    cwd: Path | None = None,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        command,
        cwd=str(cwd) if cwd else None,
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


def base64_text(value: str) -> str:
    return base64.b64encode(value.encode("utf-8")).decode("ascii")


def now_stamp() -> str:
    return dt.datetime.now().strftime("%Y%m%d-%H%M%S")


def as_list(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def as_dict(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def first_non_empty(*values: Any) -> str:
    for value in values:
        if value is None:
            continue
        text = str(value).strip()
        if text:
            return text
    return ""


class DeviceClient:
    def __init__(self, *, serial: str, package_name: str, timeout_seconds: int) -> None:
        self.serial = serial
        self.package_name = package_name
        self.timeout_seconds = timeout_seconds
        self.vlm_receiver = f"{package_name}/cn.com.omnimind.bot.debug.DebugVlmRunLogReceiver"
        self.recall_receiver = f"{package_name}/cn.com.omnimind.bot.debug.DebugOobRecallReceiver"
        self.segment_receiver = f"{package_name}/cn.com.omnimind.bot.debug.DebugOobFunctionSegmentReceiver"
        self.agent_function_receiver = (
            f"{package_name}/cn.com.omnimind.bot.debug.DebugAgentFunctionManagementReceiver"
        )

    def adb(self, *args: str, timeout: int = 60, check: bool = True) -> subprocess.CompletedProcess[str]:
        return run_command(["adb", "-s", self.serial, *args], timeout=timeout, check=check)

    def prepare(self) -> None:
        self.adb("get-state", timeout=10)
        self.adb("shell", "input", "keyevent", "KEYCODE_WAKEUP", check=False)
        self.adb("shell", "appops", "set", self.package_name, "SYSTEM_ALERT_WINDOW", "allow", check=False)
        self._enable_accessibility()
        self._restore_clock_best_effort()
        self.adb(
            "shell",
            "monkey",
            "-p",
            self.package_name,
            "-c",
            "android.intent.category.LAUNCHER",
            "1",
            timeout=30,
            check=False,
        )

    def configure_provider(
        self,
        *,
        profile_id: str,
        model_id: str,
        base_url: str,
        api_key_env: str,
        skip_configure: bool,
    ) -> dict[str, Any]:
        if skip_configure:
            return {"configured": False, "reason": "skip_configure"}
        if not os.environ.get(api_key_env):
            return {
                "configured": False,
                "reason": f"env_missing:{api_key_env}",
                "note": "Will use any provider already configured inside the app.",
            }
        script = repo_root() / "scripts" / "configure-oob-model-provider.sh"
        result = run_command(
            [
                "bash",
                str(script),
                "--device",
                self.serial,
                "--package",
                self.package_name,
                "--base-url",
                base_url,
                "--api-key-env",
                api_key_env,
                "--model",
                model_id,
                "--profile-id",
                profile_id,
                "--name",
                "DashScope",
                "--no-read-result",
            ],
            cwd=repo_root(),
            timeout=120,
        )
        return {"configured": True, "stdout": result.stdout.strip()[-500:]}

    def clear_result(self, *paths: str) -> None:
        self.adb(
            "shell",
            "run-as",
            self.package_name,
            "rm",
            "-f",
            *paths,
            timeout=20,
            check=False,
        )

    def read_app_file(self, path: str) -> str:
        result = self.adb(
            "shell",
            "run-as",
            self.package_name,
            "cat",
            path,
            timeout=8,
            check=False,
        )
        if result.returncode != 0:
            return ""
        return result.stdout.strip()

    def wait_json(self, path: str, label: str) -> dict[str, Any]:
        deadline = time.time() + self.timeout_seconds
        last = ""
        while time.time() < deadline:
            content = self.read_app_file(path)
            if content:
                last = content
                try:
                    return json.loads(content)
                except json.JSONDecodeError:
                    time.sleep(1)
                    continue
            time.sleep(1)
        raise TimeoutError(f"Timed out waiting for {label} on {self.serial}: {path}; last={last[:500]}")

    def seed_segment_function(self, *, goal: str) -> dict[str, Any]:
        self.clear_result(SEGMENT_RESULT_FILE)
        self.adb(
            "shell",
            "am",
            "broadcast",
            "-a",
            SEGMENT_ACTION,
            "-n",
            self.segment_receiver,
            "--es",
            "goal",
            goal,
            timeout=30,
        )
        return self.wait_json(SEGMENT_RESULT_FILE, "segment seed")

    def seed_agent_function(
        self,
        *,
        function_id: str,
        name: str,
        description: str,
        target_package: str,
    ) -> dict[str, Any]:
        self.clear_result(AGENT_FUNCTION_RESULT_FILE)
        self.adb(
            "shell",
            "am",
            "broadcast",
            "-a",
            AGENT_FUNCTION_ACTION,
            "-n",
            self.agent_function_receiver,
            "--es",
            "function_id",
            function_id,
            "--es",
            "nameBase64",
            base64_text(name),
            "--es",
            "descriptionBase64",
            base64_text(description),
            "--es",
            "targetPackage",
            target_package,
            "--ez",
            "run",
            "false",
            "--ez",
            "deleteBefore",
            "true",
            timeout=30,
        )
        return self.wait_json(AGENT_FUNCTION_RESULT_FILE, "agent function seed")

    def recall(self, *, goal: str, k: int) -> dict[str, Any]:
        self.clear_result(RECALL_RESULT_FILE)
        self.adb(
            "shell",
            "am",
            "broadcast",
            "-a",
            RECALL_ACTION,
            "-n",
            self.recall_receiver,
            "--es",
            "goalBase64",
            base64_text(goal),
            "--ei",
            "k",
            str(k),
            timeout=30,
        )
        return self.wait_json(RECALL_RESULT_FILE, "recall result")

    def run_vlm(
        self,
        *,
        goal: str,
        model_id: str,
        profile_id: str,
        skill_id: str,
        max_steps: int,
        register: bool,
        start_from_current: bool,
        target_package: str,
        disable_recall: bool,
    ) -> dict[str, Any]:
        self.clear_result(VLM_RESULT_FILE)
        self.adb("shell", "input", "keyevent", "KEYCODE_HOME", timeout=10, check=False)
        time.sleep(1)
        command = [
            "shell",
            "am",
            "broadcast",
            "-a",
            VLM_ACTION,
            "-n",
            self.vlm_receiver,
            "--es",
            "goalBase64",
            base64_text(goal),
            "--ei",
            "maxSteps",
            str(max_steps),
            "--el",
            "timeoutMs",
            str(self.timeout_seconds * 1000),
            "--ez",
            "register",
            str(register).lower(),
            "--es",
            "profileId",
            profile_id,
            "--es",
            "modelId",
            model_id,
            "--es",
            "skillId",
            skill_id,
            "--ez",
            "disableOmniFlowRecall",
            str(disable_recall).lower(),
        ]
        if start_from_current:
            command.extend(["--ez", "startFromCurrent", "true", "--ez", "skipGoHome", "true"])
        else:
            command.extend(["--es", "packageName", target_package])
        self.adb(*command, timeout=30)
        return self.wait_json(VLM_RESULT_FILE, "VLM task")

    def _enable_accessibility(self) -> None:
        component = f"{self.package_name}/com.google.android.accessibility.selecttospeak.SelectToSpeakService"
        current = self.adb(
            "shell",
            "settings",
            "get",
            "secure",
            "enabled_accessibility_services",
            timeout=10,
            check=False,
        ).stdout.strip()
        parts = [] if current in {"", "null"} else [part for part in current.split(":") if part]
        if component not in parts:
            parts.append(component)
        self.adb(
            "shell",
            "settings",
            "put",
            "secure",
            "enabled_accessibility_services",
            ":".join(parts),
            timeout=10,
            check=False,
        )
        self.adb("shell", "settings", "put", "secure", "accessibility_enabled", "1", timeout=10, check=False)

    def _restore_clock_best_effort(self) -> None:
        utc = dt.datetime.now(dt.UTC).strftime("%m%d%H%M%y.%S")
        self.adb("shell", "date", utc, timeout=10, check=False)


def compact_candidate(candidate: dict[str, Any]) -> dict[str, Any]:
    schema = as_dict(candidate.get("inputSchema") or candidate.get("input_schema"))
    properties = as_dict(schema.get("properties"))
    return {
        "function_id": candidate.get("function_id"),
        "name": candidate.get("name"),
        "description": candidate.get("description"),
        "score": candidate.get("score"),
        "text_score": candidate.get("text_score"),
        "page_similarity": candidate.get("page_similarity"),
        "recall_scope": candidate.get("recall_scope"),
        "source": candidate.get("source"),
        "node_id": candidate.get("node_id"),
        "strict_direct_hit": candidate.get("strict_direct_hit"),
        "requires_arguments": candidate.get("requires_arguments"),
        "argument_names": list(properties.keys()),
        "step_count": candidate.get("step_count"),
        "remaining_step_count": candidate.get("remaining_step_count"),
        "call": candidate.get("call"),
    }


def recall_candidates(recall_payload: dict[str, Any]) -> list[dict[str, Any]]:
    recall = as_dict(recall_payload.get("recall"))
    buckets = [
        as_dict(recall.get("hit")),
        *[as_dict(item) for item in as_list(recall.get("candidates"))],
        *[as_dict(item) for item in as_list(recall.get("capability_candidates"))],
        *[as_dict(item) for item in as_list(recall.get("node_function_capabilities"))],
        *[as_dict(item) for item in as_list(recall.get("node_capabilities"))],
    ]
    seen: set[str] = set()
    compacted: list[dict[str, Any]] = []
    for item in buckets:
        function_id = first_non_empty(item.get("function_id"))
        if not function_id or function_id in seen:
            continue
        seen.add(function_id)
        compacted.append(compact_candidate(item))
    return compacted


def find_function_call_evidence(value: Any, path: str = "$") -> list[dict[str, Any]]:
    evidence: list[dict[str, Any]] = []
    if isinstance(value, dict):
        tool = first_non_empty(value.get("tool"), value.get("name"), value.get("action"), value.get("type"))
        args = as_dict(value.get("arguments") or value.get("args") or value.get("action_args") or value.get("params"))
        function_id = first_non_empty(
            value.get("function_id"),
            args.get("function_id"),
            as_dict(value.get("function_result")).get("function_id"),
        )
        has_function_result = "function_result" in value or "action_result_data" in value
        if tool == "oob_function_run" or function_id or has_function_result:
            evidence.append(
                {
                    "path": path,
                    "tool": tool or None,
                    "function_id": function_id or None,
                    "arguments": args.get("arguments") if "arguments" in args else args,
                    "has_function_result": has_function_result,
                    "success": value.get("success"),
                    "summary": first_non_empty(value.get("summary"), value.get("message"), value.get("title"))[:300],
                }
            )
        for key, child in value.items():
            evidence.extend(find_function_call_evidence(child, f"{path}.{key}"))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            evidence.extend(find_function_call_evidence(child, f"{path}[{index}]"))
    return evidence


def token_total(vlm_result: dict[str, Any]) -> int:
    raw = vlm_result.get("token_usage_total")
    if isinstance(raw, int):
        return raw
    if isinstance(raw, float):
        return int(raw)
    usage = as_dict(vlm_result.get("token_usage"))
    for key in ("total_tokens", "total", "tokens"):
        if isinstance(usage.get(key), (int, float)):
            return int(usage[key])
    calls = as_list(vlm_result.get("token_usage_by_call"))
    total = 0
    for call in calls:
        call_map = as_dict(call)
        value = call_map.get("total_tokens") or call_map.get("tokens")
        if isinstance(value, (int, float)):
            total += int(value)
    return total


def build_segment_hypothesis(candidates: list[dict[str, Any]]) -> dict[str, Any]:
    if not candidates:
        return {
            "available": False,
            "reason": "no_recalled_function_candidates",
            "expected_runtime": "VLM must continue with normal live actions.",
        }
    top = candidates[0]
    return {
        "available": True,
        "candidate_function_id": top.get("function_id"),
        "candidate_name": top.get("name"),
        "candidate_description": top.get("description"),
        "recall_scope": top.get("recall_scope"),
        "node_id": top.get("node_id"),
        "score": top.get("score"),
        "step_count": top.get("step_count"),
        "remaining_step_count": top.get("remaining_step_count"),
        "arguments_required": top.get("requires_arguments"),
        "argument_names": top.get("argument_names"),
        "expected_vlm_decision": (
            "If the candidate matches the user goal, the real VLM should emit "
            "a native tool_call whose tool name is the recalled function_id and "
            "whose arguments match the Function schema. The Function may complete "
            "only part of the goal, so VLM continues after success/result and the "
            "next fresh observe when needed."
        ),
        "execution_hypothesis": (
            "This script does not auto-run the candidate. It verifies the "
            "hypothesis exposed to VLM: recalled Function as a native tool "
            "choice, with local replay result returned as success/result."
        ),
    }


def summarize_device_result(
    *,
    device: str,
    provider: dict[str, Any],
    seed: dict[str, Any] | None,
    agent_seed: dict[str, Any] | None,
    vlm_seed: dict[str, Any] | None,
    recall: dict[str, Any],
    vlm: dict[str, Any],
) -> dict[str, Any]:
    recall_body = as_dict(recall.get("recall"))
    candidates = recall_candidates(recall)
    function_evidence = find_function_call_evidence(as_dict(vlm.get("run_log")))
    return {
        "device": device,
        "provider": provider,
        "seed": {
            "enabled": seed is not None,
            "success": as_dict(seed).get("success") if seed else None,
            "child_function_id": as_dict(seed).get("child_function_id") if seed else None,
            "parent_function_id": as_dict(seed).get("parent_function_id") if seed else None,
            "recall_decision": as_dict(as_dict(seed).get("recall")).get("decision") if seed else None,
        },
        "agent_seed": {
            "enabled": agent_seed is not None,
            "success": as_dict(agent_seed).get("success") if agent_seed else None,
            "source": as_dict(agent_seed).get("source") if agent_seed else None,
            "function_id": as_dict(agent_seed).get("function_id") if agent_seed else None,
            "register_success": as_dict(agent_seed).get("register_success") if agent_seed else None,
            "list_contains_function": as_dict(agent_seed).get("list_contains_function") if agent_seed else None,
            "guard_decision": as_dict(agent_seed).get("guard_decision") if agent_seed else None,
            "tool_profile": as_dict(agent_seed).get("tool_profile") if agent_seed else None,
        },
        "vlm_seed": {
            "enabled": vlm_seed is not None,
            "success": as_dict(vlm_seed).get("success") if vlm_seed else None,
            "run_id": as_dict(vlm_seed).get("run_id") if vlm_seed else None,
            "runlog_found": as_dict(vlm_seed).get("runlog_found") if vlm_seed else None,
            "runlog_success": as_dict(vlm_seed).get("runlog_success") if vlm_seed else None,
            "token_usage_total": token_total(as_dict(vlm_seed)) if vlm_seed else None,
            "convert_success": as_dict(as_dict(vlm_seed).get("convert")).get("success") if vlm_seed else None,
            "function_id": as_dict(as_dict(vlm_seed).get("convert")).get("function_id") if vlm_seed else None,
            "error_message": as_dict(vlm_seed).get("error_message") or as_dict(as_dict(vlm_seed).get("outcome")).get("errorMessage") if vlm_seed else None,
        },
        "recall": {
            "goal": recall.get("goal"),
            "decision": recall_body.get("decision"),
            "reason": recall_body.get("reason"),
            "current_package": recall.get("current_package") or recall_body.get("current_package"),
            "current_node_id": recall_body.get("current_node_id"),
            "candidate_count": len(candidates),
            "candidates": candidates[:5],
            "timing": as_dict(recall_body.get("timing")),
        },
        "segment_execution_hypothesis": build_segment_hypothesis(candidates),
        "vlm_task": {
            "success": vlm.get("success"),
            "run_id": vlm.get("run_id"),
            "runlog_found": vlm.get("runlog_found"),
            "runlog_success": vlm.get("runlog_success"),
            "runlog_card_count": vlm.get("runlog_card_count"),
            "outcome_status": as_dict(vlm.get("outcome")).get("status"),
            "execution_route": as_dict(vlm.get("outcome")).get("executionRoute"),
            "token_usage_total": token_total(vlm),
            "real_api_observed": token_total(vlm) > 0 or bool(as_list(vlm.get("token_usage_by_call"))),
            "function_call_observed": bool(function_evidence),
            "function_call_evidence": function_evidence[:10],
            "error_message": vlm.get("error_message") or as_dict(vlm.get("outcome")).get("errorMessage"),
        },
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--devices", default=",".join(DEFAULT_DEVICES), help="Comma-separated adb serials.")
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--task", default=DEFAULT_TASK)
    parser.add_argument("--seed-goal", default=DEFAULT_SEED_GOAL)
    parser.add_argument("--target-package", default=DEFAULT_TARGET_PACKAGE)
    parser.add_argument("--profile-id", default=DEFAULT_PROFILE_ID)
    parser.add_argument("--model", default=DEFAULT_MODEL_ID)
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--api-key-env", default=DEFAULT_API_KEY_ENV)
    parser.add_argument("--skill-id", default="vlm-android-gui")
    parser.add_argument("--max-steps", type=int, default=3)
    parser.add_argument("--recall-k", type=int, default=5)
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--skip-configure", action="store_true")
    parser.add_argument("--skip-seed", action="store_true")
    parser.add_argument("--skip-agent-seed", action="store_true")
    parser.add_argument("--skip-vlm-seed", action="store_true")
    parser.add_argument("--register-vlm-run", action="store_true")
    parser.add_argument("--disable-recall-in-vlm", action="store_true")
    parser.add_argument("--output-dir", default="")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    output_dir = Path(args.output_dir) if args.output_dir else repo_root() / "runtime" / "vlm_recall_validation" / now_stamp()
    output_dir.mkdir(parents=True, exist_ok=True)

    devices = [item.strip() for item in args.devices.split(",") if item.strip()]
    summaries: list[dict[str, Any]] = []
    failures: list[str] = []
    for device in devices:
        print(f"[oob-vlm-recall-validation] device={device}", flush=True)
        client = DeviceClient(serial=device, package_name=args.package, timeout_seconds=args.timeout)
        try:
            client.prepare()
            provider = client.configure_provider(
                profile_id=args.profile_id,
                model_id=args.model,
                base_url=args.base_url,
                api_key_env=args.api_key_env,
                skip_configure=args.skip_configure,
            )
            seed = None if args.skip_seed else client.seed_segment_function(goal=args.seed_goal)
            agent_seed = None
            if not args.skip_agent_seed:
                agent_seed = client.seed_agent_function(
                    function_id="debug_agent_function_open_settings",
                    name="打开设置",
                    description=(
                        "打开 Android 设置。Use this Function when the user asks to 打开设置, "
                        "open settings, or view Android Settings."
                    ),
                    target_package=args.target_package,
                )
            vlm_seed = None
            if not args.skip_vlm_seed:
                vlm_seed = client.run_vlm(
                    goal=args.task,
                    model_id=args.model,
                    profile_id=args.profile_id,
                    skill_id=args.skill_id,
                    max_steps=args.max_steps,
                    register=True,
                    start_from_current=True,
                    target_package=args.target_package,
                    disable_recall=True,
                )
            client.adb("shell", "input", "keyevent", "KEYCODE_HOME", timeout=10, check=False)
            time.sleep(1)
            recall = client.recall(goal=args.task, k=args.recall_k)
            vlm = client.run_vlm(
                goal=args.task,
                model_id=args.model,
                profile_id=args.profile_id,
                skill_id=args.skill_id,
                max_steps=args.max_steps,
                register=args.register_vlm_run,
                start_from_current=True,
                target_package=args.target_package,
                disable_recall=args.disable_recall_in_vlm,
            )
            summary = summarize_device_result(
                device=device,
                provider=provider,
                seed=seed,
                agent_seed=agent_seed,
                vlm_seed=vlm_seed,
                recall=recall,
                vlm=vlm,
            )
            summaries.append(summary)
            device_dir = output_dir / device.replace(":", "_")
            device_dir.mkdir(parents=True, exist_ok=True)
            (device_dir / "seed.json").write_text(
                json.dumps(seed, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            (device_dir / "agent_seed.json").write_text(
                json.dumps(agent_seed, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            (device_dir / "recall.json").write_text(
                json.dumps(recall, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            (device_dir / "vlm_seed.json").write_text(
                json.dumps(vlm_seed, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            (device_dir / "vlm.json").write_text(
                json.dumps(vlm, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            (device_dir / "summary.json").write_text(
                json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            print(json.dumps(summary, ensure_ascii=False, indent=2), flush=True)
        except Exception as error:  # noqa: BLE001 - CLI validation should keep going across devices.
            failure = f"{device}: {error}"
            failures.append(failure)
            print(f"[oob-vlm-recall-validation] error={failure}", file=sys.stderr, flush=True)

    validation_passed = bool(summaries) and not failures and all(
        as_dict(item.get("recall")).get("candidate_count", 0) > 0 and
        as_dict(item.get("vlm_task")).get("real_api_observed") is True
        for item in summaries
    )
    aggregate = {
        "success": validation_passed,
        "output_dir": str(output_dir),
        "task": args.task,
        "model": args.model,
        "devices": devices,
        "summary_count": len(summaries),
        "summaries": summaries,
        "failures": failures,
    }
    (output_dir / "summary.json").write_text(
        json.dumps(aggregate, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(aggregate, ensure_ascii=False, indent=2), flush=True)
    return 0 if aggregate["success"] else 1


if __name__ == "__main__":
    sys.exit(main())
