#!/usr/bin/env python3
"""Static smoke for the OOB <-> OmniFlow Python offline contract.

This check intentionally does not start Android, ADB, MCP stdio, or the
OmniFlow provider. It validates the local source contract that keeps live phone
execution in OOB Kotlin while allowing the Python package to serve offline
schema/recall/ingest/dev workflows.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OMNIFLOW_ROOT = Path.home() / "Projects" / "Omni" / "OmniFlow"
VLM_EXAMPLE = (
    ROOT
    / "app/src/main/assets/omniflow/runlog/examples/vlm-task-recall-loop.json"
)
ENTRY_SURFACE_CONTRACT = (
    ROOT
    / "app/src/main/assets/omniflow/runlog/examples/unified-entry-surfaces.json"
)
OOB_CANONICAL_ACTION_SCHEMA = ROOT / "schemas/oob/oob_canonical_actions.v1.json"
UNIFIED_PLAN = (
    ROOT / "app/src/main/assets/omniflow/runlog/unified-execution-plan.md"
)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Validate OOB's offline-only OmniFlow Python contract.",
    )
    parser.add_argument(
        "--omniflow-root",
        default=str(DEFAULT_OMNIFLOW_ROOT),
        help="Local OmniFlow Python checkout. Default: ~/Projects/Omni/OmniFlow.",
    )
    args = parser.parse_args()

    omniflow_root = Path(args.omniflow_root).expanduser().resolve()
    failures: list[str] = []
    report: dict[str, Any] = {
        "success": False,
        "oob_root": str(ROOT),
        "omniflow_root": str(omniflow_root),
        "checks": {},
    }

    if not omniflow_root.is_dir():
        return fail(report, [f"Missing OmniFlow root: {omniflow_root}"])

    mcp_server = read_text(
        omniflow_root / "src/integrations/mcp_server.py",
        failures,
    )
    integration_readme = read_text(
        omniflow_root / "src/integrations/README.md",
        failures,
    )
    pyproject = read_text(omniflow_root / "pyproject.toml", failures)
    vlm_example = read_json(VLM_EXAMPLE, failures)
    entry_contract = read_json(ENTRY_SURFACE_CONTRACT, failures)
    oob_action_schema = read_json(OOB_CANONICAL_ACTION_SCHEMA, failures)
    omniflow_action_schema = read_json(
        omniflow_root / "schemas/oob/oob_canonical_actions.v1.json",
        failures,
    )
    unified_plan = read_text(UNIFIED_PLAN, failures)

    if isinstance(oob_action_schema, dict) and isinstance(omniflow_action_schema, dict):
        assert_equal(
            oob_action_schema,
            omniflow_action_schema,
            "OOB and OmniFlow canonical action schema",
            failures,
        )
        report["checks"]["canonical_action_schema_version"] = oob_action_schema.get(
            "schema_version"
        )
        report["checks"]["canonical_action_tools"] = [
            tool.get("name")
            for tool in oob_action_schema.get("tools", [])
            if isinstance(tool, dict)
        ]

    tool_names = parse_standalone_tool_names(mcp_server)
    report["checks"]["python_mcp_tools"] = tool_names
    expected_tools = [
        "omniflow.recall",
        "omniflow.ingest_run_log",
        "omniflow.update_function",
    ]
    if tool_names != expected_tools:
        failures.append(
            f"Expected Python standalone MCP tools {expected_tools}, got {tool_names}"
        )

    forbidden_mcp_tools = [
        "omniflow.call_function",
        "omniflow.run_function",
        "omniflow.execute_function",
    ]
    exposed_tool_decorators = re.findall(r"@mcp\.tool\(name=\"([^\"]+)\"\)", mcp_server)
    report["checks"]["python_mcp_tool_decorators"] = exposed_tool_decorators
    for tool in forbidden_mcp_tools:
        if tool in exposed_tool_decorators:
            failures.append(f"Python standalone MCP exposes forbidden tool: {tool}")

    require_contains(
        mcp_server,
        [
            "It does not",
            "resolve parameters",
            "execute Functions",
            "model-callable",
            "function_id",
        ],
        "mcp_server recall boundary",
        failures,
    )
    require_contains(
        integration_readme,
        [
            "standalone `omniflow-mcp` 也只暴露 recall / ingest",
            "不注册 `omniflow.call_function`",
            "OOB native 自己负责本地执行",
            "不会检查 Android",
        ],
        "OmniFlow integration README boundary",
        failures,
    )
    require_contains(
        pyproject,
        [
            'omniflow-provider = "omniflow.cli:provider_main"',
            'omniflow-mcp = "omniflow.cli:mcp_main"',
        ],
        "OmniFlow console scripts",
        failures,
    )
    require_contains(
        unified_plan,
        [
            "Python `omniflow-mcp` in Alpine",
            "`omniflow.recall`, `omniflow.ingest_run_log`, `omniflow.update_function`",
            "no for OOB phone runtime",
            "Do not use for: Android accessibility actions",
            "If Python needs to execute on this phone",
            "OOB's native MCP/HTTP/debug surface",
        ],
        "OOB unified execution plan Python boundary",
        failures,
    )

    if isinstance(vlm_example, dict):
        report["checks"]["vlm_example_schema_version"] = vlm_example.get(
            "schema_version"
        )
        assert_equal(
            vlm_example.get("runtime_owner"),
            "oob_native_kotlin",
            "vlm example runtime_owner",
            failures,
        )
        roles = vlm_example.get("python_omniflow_role")
        if not isinstance(roles, list):
            failures.append("vlm example python_omniflow_role must be a list")
            roles = []
        report["checks"]["vlm_example_python_roles"] = roles
        for role in [
            "schema_validation",
            "offline_fixture_eval",
            "provider_dev_tools",
            "mcp_cache_recall_ingest",
            "mcp_offline_update_function",
        ]:
            if role not in roles:
                failures.append(f"vlm example missing Python role: {role}")
        assert_equal(
            vlm_example.get("enhancement_policy"),
            "offline_only",
            "vlm example enhancement_policy",
            failures,
        )
        phases = {
            str(step.get("phase")): step
            for step in vlm_example.get("steps", [])
            if isinstance(step, dict)
        }
        report["checks"]["vlm_example_phases"] = list(phases)
        require_phase(phases, "first_vlm_run", failures)
        require_phase(phases, "strict_recall_check", failures)
        require_phase(phases, "second_fast_vlm_run", failures)
        require_phase(phases, "offline_enhance", failures)
        first_tool = nested_get(phases, "first_vlm_run", "tool_payload", "tool")
        assert_equal(first_tool, "vlm_task", "first VLM run tool", failures)
        first_args = nested_get(phases, "first_vlm_run", "tool_payload", "arguments")
        if not isinstance(first_args, dict):
            failures.append("first VLM run arguments must be an object")
            first_args = {}
        if "goal" not in first_args:
            failures.append("first VLM run arguments must use goal")
        if "operation" in first_args:
            failures.append("first VLM run arguments must not use legacy operation")
        second_args = nested_get(phases, "second_fast_vlm_run", "tool_payload", "arguments")
        if not isinstance(second_args, dict):
            failures.append("second fast VLM run arguments must be an object")
            second_args = {}
        if "goal" not in second_args:
            failures.append("second fast VLM run arguments must use goal")
        if "operation" in second_args:
            failures.append("second fast VLM run arguments must not use legacy operation")
        fast_route = nested_get(
            phases,
            "second_fast_vlm_run",
            "expected",
            "outcome.executionRoute",
        )
        assert_equal(
            fast_route,
            "omniflow_recall_hit*",
            "second VLM fast route expectation",
            failures,
        )
        enhance_mode = nested_get(
            phases,
            "offline_enhance",
            "tool_payload",
            "arguments",
            "mode",
        )
        assert_equal(enhance_mode, "enhance", "offline enhance mode", failures)
        replay_uses_enhanced = nested_get(
            phases,
            "offline_enhance",
            "expected",
            "replay_uses_enhanced_function",
        )
        assert_equal(
            replay_uses_enhanced,
            False,
            "offline enhance must not feed replay",
            failures,
        )

    if isinstance(entry_contract, dict):
        report["checks"]["entry_surface_schema_version"] = entry_contract.get(
            "schema_version"
        )
        assert_equal(
            entry_contract.get("runtime_owner"),
            "oob_native_kotlin",
            "entry surface runtime_owner",
            failures,
        )
        assert_equal(
            entry_contract.get("native_facade"),
            "OobOmniFlowToolkitService",
            "entry surface native facade",
            failures,
        )
        assert_equal(
            entry_contract.get("enhancement_policy"),
            "offline_only",
            "entry surface enhancement_policy",
            failures,
        )
        surfaces = {
            str(surface.get("id")): surface
            for surface in entry_contract.get("entry_surfaces", [])
            if isinstance(surface, dict)
        }
        report["checks"]["entry_surface_ids"] = list(surfaces)
        require_phase(surfaces, "vlm_task_recall_fast_path", failures)
        require_phase(surfaces, "mcp_function_lifecycle_tools", failures)
        require_phase(surfaces, "http_function_run", failures)
        require_phase(surfaces, "python_omniflow_offline", failures)
        python_surface = surfaces.get("python_omniflow_offline", {})
        assert_equal(
            python_surface.get("may_execute_phone_actions"),
            False,
            "Python surface may_execute_phone_actions",
            failures,
        )
        assert_equal(
            python_surface.get("must_call_oob_adapter_for_phone_execution"),
            True,
            "Python surface must call OOB adapter for phone execution",
            failures,
        )
        assert_equal(
            python_surface.get("enhance_policy"),
            "offline_patches_only",
            "Python surface enhance policy",
            failures,
        )
        mcp_surface = surfaces.get("mcp_function_lifecycle_tools", {})
        forbidden_public_tools = mcp_surface.get("forbidden_public_tools")
        if not isinstance(forbidden_public_tools, list):
            failures.append("entry surface MCP forbidden_public_tools must be a list")
            forbidden_public_tools = []
        for tool in forbidden_mcp_tools:
            if tool not in forbidden_public_tools:
                failures.append(f"entry surface MCP contract missing forbidden tool: {tool}")
        invariants = entry_contract.get("invariants")
        if not isinstance(invariants, dict):
            failures.append("entry surface invariants must be an object")
            invariants = {}
        assert_equal(
            invariants.get("python_never_owns_accessibility_or_overlay"),
            True,
            "entry surface Python accessibility invariant",
            failures,
        )
        assert_equal(
            invariants.get("enhance_never_blocks_registration_recall_or_replay"),
            True,
            "entry surface enhance nonblocking invariant",
            failures,
        )

    if failures:
        return fail(report, failures)

    report["success"] = True
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


def read_text(path: Path, failures: list[str]) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except FileNotFoundError:
        failures.append(f"Missing file: {path}")
    except UnicodeDecodeError as exc:
        failures.append(f"Could not decode {path}: {exc}")
    return ""


def read_json(path: Path, failures: list[str]) -> Any:
    text = read_text(path, failures)
    if not text:
        return None
    try:
        return json.loads(text)
    except json.JSONDecodeError as exc:
        failures.append(f"Invalid JSON {path}: {exc}")
        return None


def parse_standalone_tool_names(source: str) -> list[str]:
    match = re.search(
        r"STANDALONE_TOOL_NAMES\s*=\s*\((?P<body>.*?)\)",
        source,
        flags=re.DOTALL,
    )
    if not match:
        return []
    return re.findall(r'"([^"]+)"', match.group("body"))


def require_contains(
    source: str,
    needles: list[str],
    label: str,
    failures: list[str],
) -> None:
    for needle in needles:
        if needle not in source:
            failures.append(f"{label} missing text: {needle}")


def assert_equal(actual: Any, expected: Any, label: str, failures: list[str]) -> None:
    if actual != expected:
        failures.append(f"{label}: expected {expected!r}, got {actual!r}")


def require_phase(
    phases: dict[str, dict[str, Any]],
    name: str,
    failures: list[str],
) -> None:
    if name not in phases:
        failures.append(f"vlm example missing phase: {name}")


def nested_get(
    mapping: dict[str, Any],
    *path: str,
) -> Any:
    current: Any = mapping
    for key in path:
        if not isinstance(current, dict):
            return None
        current = current.get(key)
    return current


def fail(report: dict[str, Any], failures: list[str]) -> int:
    report["success"] = False
    report["failures"] = failures
    print(json.dumps(report, ensure_ascii=False, indent=2), file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
