from __future__ import annotations

import json
from pathlib import Path

from omniflow.artifact import parse_function_artifact, validate_function_artifact
from omniflow.model import Function

STORE_VERSION = "omniflow.store.v2"


class FunctionStore:
    def __init__(self, path: str | Path):
        self.path = Path(path)
        self.functions: dict[str, Function] = {}
        self.load_errors: dict[str, str] = {}
        self._load()

    def list_functions(self, *, offset: int = 0, limit: int = 100) -> list[Function]:
        start = max(0, int(offset))
        end = start + max(1, min(int(limit), 500))
        return sorted(self.functions.values(), key=lambda item: item.id)[start:end]

    def get_function(self, function_id: str) -> Function | None:
        return self.functions.get(str(function_id or "").strip())

    def put_function(self, value: Function | dict) -> Function:
        function = value if isinstance(value, Function) else parse_function_artifact(value)
        validate_function_artifact(function)
        self.functions[function.id] = function
        self.load_errors.clear()
        self.save()
        return function

    def replace_functions(self, values: list[object]) -> dict[str, str]:
        functions: dict[str, Function] = {}
        errors: dict[str, str] = {}
        for index, value in enumerate(values):
            key = (
                str(value.get("function_id") or "").strip()
                if isinstance(value, dict)
                else ""
            ) or f"index_{index}"
            try:
                function = parse_function_artifact(value)
                validate_function_artifact(function)
                if function.id in functions:
                    raise ValueError("function_catalog_duplicate_id")
            except (TypeError, ValueError) as error:
                errors[key] = str(error) or type(error).__name__
                continue
            functions[function.id] = function
        self.functions = functions
        self.load_errors = errors
        self.save()
        return errors

    def delete_function(self, function_id: str) -> bool:
        normalized = str(function_id or "").strip()
        if normalized not in self.functions:
            return False
        del self.functions[normalized]
        self.load_errors.clear()
        self.save()
        return True

    def clear_functions(self) -> int:
        deleted = len(self.functions)
        self.functions.clear()
        self.load_errors.clear()
        self.save()
        return deleted

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "schema_version": STORE_VERSION,
            "functions": {
                key: value.to_dict() for key, value in sorted(self.functions.items())
            },
        }
        temporary = self.path.with_suffix(f"{self.path.suffix}.tmp")
        temporary.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        temporary.replace(self.path)

    def _load(self) -> None:
        if not self.path.exists():
            return
        payload = json.loads(self.path.read_text(encoding="utf-8"))
        if not isinstance(payload, dict) or payload.get("schema_version") != STORE_VERSION:
            raise ValueError("unsupported_store_version")
        raw_functions = payload.get("functions")
        if not isinstance(raw_functions, dict):
            raise ValueError("function_store_functions_must_be_object")
        loaded: dict[str, Function] = {}
        load_errors: dict[str, str] = {}
        for key, value in raw_functions.items():
            try:
                function = parse_function_artifact(value)
                if str(key) != function.id:
                    raise ValueError("function_store_key_mismatch")
            except (TypeError, ValueError) as error:
                load_errors[str(key)] = str(error) or type(error).__name__
                continue
            loaded[function.id] = function
        self.functions = loaded
        self.load_errors = load_errors
