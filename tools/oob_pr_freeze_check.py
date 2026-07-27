#!/usr/bin/env python3
"""Check the OmniFlow/GUI PR freeze line.

This is intentionally a read-only gate. It answers two questions:

1. Did the device acceptance run prove the required end-to-end behavior?
2. Is the PR diff still confined to the approved GUI/Function/RunLog surface?
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import re
from pathlib import Path


REQUIRED_CHECKS = (
    "manual_recording",
    "convert_register_replay",
    "function_update_enhance",
    "semantic_parameter_binding",
    "semantic_parameter_replay",
    "direct_function_run",
    "function_recall",
    "vlm_provider_ready",
    "vlm_recall_function_run",
    "historical_runlog_archive",
    "historical_vlm_reselection",
    "vlm_argument_reselection",
    "function_stop_port",
    "stop_port",
    "no_no_anchor_match",
    "function_progress_step_detail",
)


CORE_PREFIXES = (
    "app/src/main/java/cn/com/omnimind/bot/function/",
    "app/src/main/java/cn/com/omnimind/bot/gui/",
    "app/src/main/java/cn/com/omnimind/bot/runlog/",
    "androidgui/",
    "omniflow-android/",
    "baselib/src/main/java/cn/com/omnimind/baselib/runlog/",
    "schemas/oob/",
    "app/src/main/assets/omniflow/",
)


FRONTEND_PREFIXES = (
    "ui/lib/features/task/pages/execution_history/",
    "ui/lib/features/task/run_log/",
    "ui/lib/features/home/pages/command_overlay/services/manual_recording_",
)


ADAPTER_EXACT = {
    "accessibility/src/main/java/cn/com/omnimind/accessibility/action/OmniAction.kt",
    "app/build.gradle.kts",
    "settings.gradle.kts",
    "app/src/main/java/cn/com/omnimind/bot/App.kt",
    "app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/GuiTaskToolHandler.kt",
    "app/src/main/java/cn/com/omnimind/bot/omniflow/OmniFlowAppPlatform.kt",
    "app/src/main/java/cn/com/omnimind/bot/manager/AssistsCoreManager.kt",
    "app/src/main/java/cn/com/omnimind/bot/ui/channel/AssistsCoreChannel.kt",
    "app/src/main/java/cn/com/omnimind/bot/ui/channel/ChannelManager.kt",
    "app/src/main/java/cn/com/omnimind/bot/ui/channel/ScreenDialogChannel.kt",
    "app/src/main/java/cn/com/omnimind/bot/util/AssistsUtil.kt",
    "assists/src/main/java/cn/com/omnimind/assists/AssistsCore.kt",
    "assists/src/main/java/cn/com/omnimind/assists/HumanTrajectoryLearningSession.kt",
    "assists/src/main/java/cn/com/omnimind/assists/ManualOverlayTouchGesture.kt",
    "assists/src/main/java/cn/com/omnimind/assists/ManualRecordingRunLogRecovery.kt",
    "assists/src/main/java/cn/com/omnimind/assists/TaskManager.kt",
    "assists/src/main/java/cn/com/omnimind/assists/api/bean/TaskParams.kt",
    "assists/src/main/java/cn/com/omnimind/assists/controller/accessibility/AccessibilityController.kt",
    "assists/src/main/java/cn/com/omnimind/assists/util/PollUtil.kt",
    "baselib/src/main/java/BaseApplication.kt",
    "baselib/src/main/java/cn/com/omnimind/baselib/llm/OpenAIChatCompletionModels.kt",
    # Thin physical-device fallback used by DeviceOperator. This is not
    # Function/RunLog business logic and must remain internal-only.
    "baselib/src/main/java/cn/com/omnimind/baselib/shizuku/PrivilegedActionPolicy.kt",
    "baselib/src/main/java/cn/com/omnimind/baselib/shizuku/PrivilegedCommandExecutor.kt",
    "baselib/src/main/java/cn/com/omnimind/baselib/shizuku/ShizukuCapabilityManager.kt",
    "ui/lib/services/run_log_function_enhancement_job_service.dart",
    "ui/lib/services/screen_dialog_service.dart",
    # Thin generic running-task stop port used by GUI and Function sessions.
    # Function/RunLog business logic must stay out of UIKit.
    "uikit/src/main/java/cn/com/omnimind/uikit/api/callbackimpl/CatStepLayoutApiImpl.kt",
    "uikit/src/main/java/cn/com/omnimind/uikit/loader/cat/DraggableBallInstance.kt",
    "uikit/src/main/java/cn/com/omnimind/uikit/loader/ManualRecordingControlOverlay.kt",
    "uikit/src/main/java/cn/com/omnimind/uikit/loader/ManualTouchRecordLoader.kt",
}


TOOL_EXACT = {
    "tools/oob_pr_acceptance.py",
    "tools/oob_pr_freeze_check.py",
    "tools/test_oob_pr_acceptance.py",
}


DEBUG_EXACT = {
    "app/src/debug/AndroidManifest.xml",
}


FRONTEND_ENTRY_EXACT = {
    "ui/lib/features/home/pages/command_overlay/widgets/chat_input_area.dart",
    "ui/lib/features/home/pages/command_overlay/widgets/chat_input_area_composer.dart",
    "ui/lib/features/home/pages/command_overlay/widgets/chat_input_area_popup.dart",
    "ui/lib/features/memory/pages/memory_center/memory_center_page.dart",
    "ui/lib/features/task/router_config.dart",
    "ui/lib/l10n/app_text_localizer.dart",
    "ui/lib/l10n/l10n.dart",
    "ui/lib/services/assists_core_service.dart",
}


ALLOWED_EXACT = {
    *ADAPTER_EXACT,
    *FRONTEND_ENTRY_EXACT,
    *TOOL_EXACT,
    *DEBUG_EXACT,
    "app/src/test/java/cn/com/omnimind/bot/omniflow/OmniFlowExecutionOverlayContractTest.kt",
}


LEGACY_ALLOWED_EXACT = {
    # Kept only to avoid breaking old summaries if a local branch still carries
    # these names. New code should stay in the grouped constants above.
    "app/src/debug/AndroidManifest.xml",
}


ALLOWED_DEBUG_PREFIX = "app/src/debug/java/cn/com/omnimind/bot/debug/Debug"


ARCHITECTURE_CHECKS = (
    {
        "name": "function_run_not_instantiated_outside_function_package",
        "roots": ("app/src/main/java", "assists/src/main/java"),
        "pattern": r"\bFunctionRun\s*\(",
        "allowed_prefixes": ("app/src/main/java/cn/com/omnimind/bot/function/",),
    },
    {
        "name": "assists_core_manager_has_no_function_channel_business",
        "roots": ("app/src/main/java/cn/com/omnimind/bot/manager/AssistsCoreManager.kt",),
        "pattern": (
            r"handleFunctionChannelMethod|isFunctionChannelMethod|"
            r"fun\s+(getInternalRunLogs|getInternalRunLogTimeline|registerFunction|"
            r"updateFunction|convertInternalRunLogToFunction|startHumanTrajectoryLearning|"
            r"pauseHumanTrajectoryLearning|resumeHumanTrajectoryLearning|"
            r"getHumanTrajectoryLearningStatus|getFunction|listFunctions|deleteFunction|runFunction)\b"
        ),
        "allowed_prefixes": (),
    },
    {
        "name": "gui_task_tool_handler_is_thin_adapter",
        "roots": ("app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/GuiTaskToolHandler.kt",),
        "pattern": r"\b(FunctionService|FunctionRun|ActionExecutor)\b",
        "allowed_prefixes": (),
    },
    {
        "name": "chat_stream_does_not_embed_function_run_cards",
        "roots": ("ui/lib/features/home/pages/chat", "ui/lib/features/home/pages/command_overlay/widgets/cards"),
        "pattern": r"function_run_tool_card_data|manual_recording_result|resultCardData|user_dialog_card|user_dialog_registry",
        "allowed_prefixes": (),
    },
)


STATIC_REQUIREMENT_CHECKS = (
    {
        "name": "function_library_page_runs_function",
        "file": "ui/lib/features/task/pages/execution_history/function_library_page.dart",
        "patterns": (
            r"Future<void>\s+_run\s*\(",
            r"RunLogFunctionService\.runFunction\s*\(",
        ),
    },
    {
        "name": "memory_center_embeds_function_library",
        "file": "ui/lib/features/memory/pages/memory_center/memory_center_page.dart",
        "patterns": (
            r"FunctionLibraryEmbed\s*\(",
            r"复用指令",
        ),
    },
    {
        "name": "function_channel_forwards_to_function_service",
        "file": "app/src/main/java/cn/com/omnimind/bot/ui/channel/AssistsCoreChannel.kt",
        "patterns": (
            r"FunctionService\.isChannelMethod",
            r"\.handleChannelMethod\s*\(",
        ),
    },
)


def is_generated_path(path: str) -> bool:
    return (
        "/__pycache__/" in path
        or path.endswith(".pyc")
        or path.endswith(".pyo")
        or path.endswith(".log")
    )


def git_merge_base(base: str) -> str:
    result = subprocess.run(
        ["git", "merge-base", base, "HEAD"],
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    )
    return result.stdout.strip()


def run_git_diff_names(base: str, committed_only: bool) -> tuple[str, list[str]]:
    diff_target = f"{base}...HEAD" if committed_only else git_merge_base(base)
    result = subprocess.run(
        ["git", "diff", "--name-only", diff_target],
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    )
    paths = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    if not committed_only:
        untracked = subprocess.run(
            ["git", "ls-files", "--others", "--exclude-standard"],
            check=True,
            text=True,
            stdout=subprocess.PIPE,
        )
        paths.extend(line.strip() for line in untracked.stdout.splitlines() if line.strip())
    return diff_target, sorted({path for path in paths if not is_generated_path(path)})


def is_allowed_path(path: str) -> bool:
    return (
        path in ALLOWED_EXACT
        or path.startswith(ALLOWED_DEBUG_PREFIX)
        or any(path.startswith(prefix) for prefix in CORE_PREFIXES)
        or any(path.startswith(prefix) for prefix in FRONTEND_PREFIXES)
    )


def path_category(path: str) -> str:
    if any(path.startswith(prefix) for prefix in CORE_PREFIXES):
        return "core"
    if any(path.startswith(prefix) for prefix in FRONTEND_PREFIXES) or path in FRONTEND_ENTRY_EXACT:
        return "frontend_entry"
    if path.startswith(ALLOWED_DEBUG_PREFIX) or path in DEBUG_EXACT:
        return "debug_acceptance"
    if path in TOOL_EXACT:
        return "freeze_tools"
    if path in ADAPTER_EXACT:
        return "thin_adapter"
    if path in LEGACY_ALLOWED_EXACT:
        return "legacy_allowed"
    return "outside"


def categorize_paths(paths: list[str]) -> dict[str, object]:
    grouped: dict[str, list[str]] = {}
    for path in paths:
        grouped.setdefault(path_category(path), []).append(path)
    return {
        "counts": {name: len(items) for name, items in sorted(grouped.items())},
        "files": {name: sorted(items) for name, items in sorted(grouped.items())},
    }


def tracked_text_files(roots: tuple[str, ...]) -> list[Path]:
    files: set[str] = set()
    for root in roots:
        root_path = Path(root)
        if root_path.is_file():
            files.add(root)
            continue
        if not root_path.exists():
            continue
        result = subprocess.run(
            ["git", "ls-files", "--", root],
            check=True,
            text=True,
            stdout=subprocess.PIPE,
        )
        files.update(line.strip() for line in result.stdout.splitlines() if line.strip())
        untracked = subprocess.run(
            ["git", "ls-files", "--others", "--exclude-standard", "--", root],
            check=True,
            text=True,
            stdout=subprocess.PIPE,
        )
        files.update(line.strip() for line in untracked.stdout.splitlines() if line.strip())
    return [
        Path(path)
        for path in sorted(files)
        if Path(path).is_file()
        and not is_generated_path(path)
        and Path(path).suffix in {".kt", ".java", ".dart", ".py"}
    ]


def run_architecture_checks() -> tuple[bool, list[dict[str, object]]]:
    reports: list[dict[str, object]] = []
    for spec in ARCHITECTURE_CHECKS:
        pattern = re.compile(str(spec["pattern"]))
        allowed_prefixes = tuple(spec["allowed_prefixes"])
        violations = []
        for path in tracked_text_files(tuple(spec["roots"])):
            path_text = path.as_posix()
            if any(path_text.startswith(prefix) for prefix in allowed_prefixes):
                continue
            try:
                text = path.read_text(encoding="utf-8", errors="ignore")
            except OSError as exc:
                violations.append({"file": path_text, "line": 0, "text": f"read failed: {exc}"})
                continue
            for index, line in enumerate(text.splitlines(), start=1):
                if pattern.search(line):
                    violations.append({"file": path_text, "line": index, "text": line.strip()})
        reports.append(
            {
                "name": spec["name"],
                "success": not violations,
                "violations": violations[:50],
                "violation_count": len(violations),
            }
        )
    return all(item["success"] for item in reports), reports


def run_static_requirement_checks() -> tuple[bool, list[dict[str, object]]]:
    reports: list[dict[str, object]] = []
    for spec in STATIC_REQUIREMENT_CHECKS:
        path = Path(str(spec["file"]))
        missing_patterns: list[str] = []
        if not path.is_file():
            reports.append(
                {
                    "name": spec["name"],
                    "success": False,
                    "file": path.as_posix(),
                    "missing_patterns": list(spec["patterns"]),
                    "error": "file not found",
                }
            )
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        for pattern_text in spec["patterns"]:
            if not re.search(str(pattern_text), text):
                missing_patterns.append(str(pattern_text))
        reports.append(
            {
                "name": spec["name"],
                "success": not missing_patterns,
                "file": path.as_posix(),
                "missing_patterns": missing_patterns,
            }
        )
    return all(item["success"] for item in reports), reports


def find_latest_summary(root: Path) -> Path | None:
    candidates = sorted(root.glob("*/summary.json"))
    return candidates[-1] if candidates else None


def check_summary(path: Path | None) -> tuple[bool, dict[str, object]]:
    if path is None:
        return False, {"error": "missing acceptance summary"}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        return False, {"path": str(path), "error": f"invalid json: {exc}"}

    checks = data.get("checks")
    check_map = {}
    check_details = {}
    if isinstance(checks, list):
        for item in checks:
            if isinstance(item, dict) and isinstance(item.get("name"), str):
                check_map[item["name"]] = item.get("success") is True
                check_details[item["name"]] = item.get("details")

    missing = [name for name in REQUIRED_CHECKS if name not in check_map]
    failed = [name for name in REQUIRED_CHECKS if check_map.get(name) is not True]
    ok = data.get("success") is True and not missing and not failed
    return ok, {
        "path": str(path),
        "success": data.get("success") is True,
        "missing_checks": missing,
        "failed_checks": failed,
        "passed_checks": [name for name in REQUIRED_CHECKS if check_map.get(name) is True],
        "failed_details": {name: check_details.get(name) for name in failed if name in check_details},
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="origin/main", help="git base ref")
    parser.add_argument(
        "--committed-only",
        action="store_true",
        help="check origin/main...HEAD instead of the current worktree state",
    )
    parser.add_argument(
        "--summary",
        help="acceptance summary path; defaults to latest runtime/pr_acceptance/*/summary.json",
    )
    parser.add_argument(
        "--skip-summary",
        action="store_true",
        help="only check the git diff boundary",
    )
    args = parser.parse_args()

    diff_target, paths = run_git_diff_names(args.base, args.committed_only)
    outside = [path for path in paths if not is_allowed_path(path)]
    architecture_ok, architecture_report = run_architecture_checks()
    static_requirements_ok, static_requirement_report = run_static_requirement_checks()

    summary_ok = True
    summary_report: dict[str, object] | None = None
    if not args.skip_summary:
        summary_path = Path(args.summary) if args.summary else find_latest_summary(Path("runtime/pr_acceptance"))
        summary_ok, summary_report = check_summary(summary_path)

    report = {
        "success": not outside and architecture_ok and static_requirements_ok and summary_ok,
        "base": args.base,
        "diff_target": diff_target,
        "committed_only": args.committed_only,
        "changed_file_count": len(paths),
        "changed_file_categories": categorize_paths(paths),
        "outside_boundary_count": len(outside),
        "outside_boundary": outside,
        "architecture_checks": architecture_report,
        "static_requirement_checks": static_requirement_report,
        "required_checks": list(REQUIRED_CHECKS),
        "acceptance_summary": summary_report,
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["success"] else 1


if __name__ == "__main__":
    sys.exit(main())
