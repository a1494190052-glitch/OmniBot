from __future__ import annotations

import importlib
import json
import os
from pathlib import Path
import sys
from typing import Any

TRANSFER_STATE_CATALOG_FILENAME = "transfer_states.json"
TRANSFER_STATE_CATALOG_VERSION = "omniflow.transfer-state-catalog.v1"
_TRANSFER_STATE_FIELDS = {
    "state_id",
    "xml",
    "package_name",
    "activity_name",
    "display",
}


def load_transfer_state_catalog(path: str | Path) -> dict[str, dict[str, Any]]:
    catalog_path = Path(path).expanduser().resolve()
    if not catalog_path.is_file():
        return {}
    payload = json.loads(catalog_path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict) or set(payload) != {
        "schema_version",
        "run_id",
        "states",
    }:
        raise ValueError("transfer_state_catalog_contract_invalid")
    if payload.get("schema_version") != TRANSFER_STATE_CATALOG_VERSION:
        raise ValueError("unsupported_transfer_state_catalog_version")
    if not isinstance(payload.get("run_id"), str) or not payload["run_id"].strip():
        raise ValueError("transfer_state_catalog_run_id_required")
    raw_states = payload.get("states")
    if not isinstance(raw_states, dict):
        raise ValueError("transfer_state_catalog_states_invalid")
    states: dict[str, dict[str, Any]] = {}
    for state_id, value in raw_states.items():
        state = _canonicalize_transfer_state(value)
        if str(state_id) != state["state_id"]:
            raise ValueError("transfer_state_catalog_key_mismatch")
        states[state["state_id"]] = state
    return states


def _canonicalize_transfer_state(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError("transfer_state_must_be_object")
    unknown = sorted(set(value) - _TRANSFER_STATE_FIELDS)
    if unknown:
        raise ValueError(f"transfer_state_unknown_fields:{','.join(unknown)}")
    state_id = str(value.get("state_id") or "").strip()
    if not state_id:
        raise ValueError("transfer_state_id_required")
    state: dict[str, Any] = {"state_id": state_id}
    for key in ("xml", "package_name", "activity_name"):
        item = value.get(key)
        if item is None:
            continue
        if not isinstance(item, str):
            raise ValueError(f"transfer_state_{key}_must_be_string")
        state[key] = item
    display = value.get("display")
    if display is not None:
        if not isinstance(display, dict) or set(display) != {"width", "height"}:
            raise ValueError("transfer_state_display_invalid")
        width = display.get("width")
        height = display.get("height")
    else:
        width = height = None
    if width is not None or height is not None:
        if not all(
            isinstance(item, int) and not isinstance(item, bool) and item > 0
            for item in (width, height)
        ):
            raise ValueError("transfer_state_display_invalid")
        state["display"] = {"width": width, "height": height}
    return state


def load_omnitransfer() -> Any:
    configured_root = os.environ.get("OMNITRANSFER_ROOT")
    if configured_root:
        root = Path(configured_root).expanduser()
        source_root = root / "src"
        if not source_root.is_dir():
            raise RuntimeError(f"omnitransfer_root_missing:{root}")
        resolved_source = source_root.resolve()
        loaded = sys.modules.get("omnitransfer")
        loaded_path = getattr(loaded, "__file__", None)
        if loaded_path is not None and Path(loaded_path).resolve().is_relative_to(
            resolved_source
        ):
            return loaded
        for name in tuple(sys.modules):
            if name == "omnitransfer" or name.startswith("omnitransfer."):
                del sys.modules[name]
        source_path = str(resolved_source)
        if source_path in sys.path:
            sys.path.remove(source_path)
        sys.path.insert(0, source_path)
        importlib.invalidate_caches()
        return importlib.import_module("omnitransfer")
    try:
        return importlib.import_module("omnitransfer")
    except ImportError:
        pass
    root = Path.home() / "Projects" / "Omni" / "OmniTransfer"
    source_root = root / "src"
    if not source_root.is_dir():
        raise RuntimeError(f"omnitransfer_root_missing:{root}")
    source_path = str(source_root.resolve())
    if source_path not in sys.path:
        sys.path.insert(0, source_path)
    return importlib.import_module("omnitransfer")


def transfer_action(**kwargs: Any) -> dict[str, Any]:
    action_transfer = getattr(load_omnitransfer(), "action_transfer", None)
    if not callable(action_transfer):
        raise RuntimeError("omnitransfer_action_transfer_unavailable")
    result = action_transfer(**kwargs)
    if not isinstance(result, dict):
        raise RuntimeError("omnitransfer_result_invalid")
    return result


def describe_action_target(**kwargs: Any) -> dict[str, Any] | None:
    describe = getattr(load_omnitransfer(), "describe_action_target", None)
    if not callable(describe):
        raise RuntimeError("omnitransfer_describe_action_target_unavailable")
    result = describe(**kwargs)
    if result is not None and not isinstance(result, dict):
        raise RuntimeError("omnitransfer_target_invalid")
    return result
