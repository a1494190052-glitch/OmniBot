from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
from typing import Any

from omniflow.bridge import JsonLineBridge
from omnitransfer import runtime_preflight

CONTRACT_NAME = "omniflow_android_bridge.v2.json"
RUNTIME_PROPERTIES = Path(__file__).resolve().parent.parent / "runtime.properties"


def _load_contract() -> tuple[dict[str, Any], Path]:
    configured = os.environ.get("OOB_OMNIFLOW_BRIDGE_CONTRACT", "").strip()
    source_file = Path(__file__).resolve()
    candidates = [
        Path(configured).expanduser() if configured else None,
        source_file.parent / "schemas" / "oob" / CONTRACT_NAME,
        source_file.parents[3] / "schemas" / "oob" / CONTRACT_NAME,
    ]
    for candidate in candidates:
        if candidate is None or not candidate.is_file():
            continue
        value = json.loads(candidate.read_text(encoding="utf-8"))
        if not isinstance(value, dict):
            raise ValueError("omniflow_bridge_contract_must_be_object")
        if value.get("contract_id") != "oob.omniflow.android_bridge":
            raise ValueError("omniflow_bridge_contract_id_invalid")
        return value, candidate
    raise FileNotFoundError(f"omniflow_bridge_contract_not_found:{CONTRACT_NAME}")


def _runtime_properties(path: Path = RUNTIME_PROPERTIES) -> dict[str, str]:
    properties: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith(("#", "!")) or "=" not in line:
            continue
        key, value = line.split("=", 1)
        properties[key.strip()] = value.strip()
    return properties


BRIDGE_CONTRACT, BRIDGE_CONTRACT_PATH = _load_contract()
PROTOCOL_VERSION = str(BRIDGE_CONTRACT["protocol_version"])
CAPABILITIES = tuple(BRIDGE_CONTRACT["operations"])
CONTRACT_SHA256 = hashlib.sha256(BRIDGE_CONTRACT_PATH.read_bytes()).hexdigest()
RUNTIME_IDENTITY = _runtime_properties()


class OobOmniFlowBridge(JsonLineBridge):
    def _handle(self, request_id: str, operation: str, payload: Any) -> Any:
        if operation not in CAPABILITIES:
            raise ValueError(f"unsupported_operation:{operation}")
        result = super()._handle(request_id, operation, payload)
        if operation != "health":
            return result
        transfer = runtime_preflight()
        return {
            **dict(result),
            "protocol_version": PROTOCOL_VERSION,
            "capabilities": list(CAPABILITIES),
            "contract_sha256": CONTRACT_SHA256,
            "runtime_version": RUNTIME_IDENTITY.get("runtime.version", ""),
            "omniflow_commit": RUNTIME_IDENTITY.get("omniflow.commit", ""),
            "omniflow_source_sha256": RUNTIME_IDENTITY.get("omniflow.source.sha256", ""),
            "omnitransfer_commit": RUNTIME_IDENTITY.get("omnitransfer.commit", ""),
            "omnitransfer_source_sha256": RUNTIME_IDENTITY.get(
                "omnitransfer.source.sha256", ""
            ),
            "omnitransfer_ready": transfer["ready"],
            "omnitransfer_backend": transfer["backend"],
        }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--store", required=True)
    parser.add_argument("--once", action="store_true")
    arguments = parser.parse_args(argv)
    bridge = OobOmniFlowBridge(Path(arguments.store))
    if arguments.once:
        bridge.serve_once()
    else:
        bridge.serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
