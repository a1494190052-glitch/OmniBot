#!/usr/bin/env python3
"""Generate Kotlin accessors from the canonical Function and Checker schemas."""

from __future__ import annotations

import argparse
import copy
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
FUNCTION_SCHEMA = ROOT / "schemas/oob/omniflow_function.v2.json"
CHECKER_SCHEMA = ROOT / "schemas/oob/omniflow_checker_rule.v1.json"
OUTPUT = (
    ROOT
    / "app/src/main/java/cn/com/omnimind/bot/function/GeneratedFunctionContractSchemas.kt"
)


def kotlin_string(value: dict) -> str:
    compact = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    return json.dumps(compact, ensure_ascii=False).replace("$", "${'$'}")


def function_tool_input_schema(function: dict, checker: dict) -> dict:
    tool_schema = copy.deepcopy(function)
    checker_items = tool_schema["properties"]["checker_rules"]["items"]
    if checker_items != {"$ref": "omniflow_checker_rule.v1.json"}:
        raise ValueError("Function checker_rules must reference the canonical Checker schema")
    tool_schema["properties"]["checker_rules"]["items"] = copy.deepcopy(checker)
    return tool_schema


def generate() -> str:
    function = json.loads(FUNCTION_SCHEMA.read_text(encoding="utf-8"))
    checker = json.loads(CHECKER_SCHEMA.read_text(encoding="utf-8"))
    function_tool_input = function_tool_input_schema(function, checker)
    return f'''package cn.com.omnimind.bot.function

import cn.com.omnimind.bot.agent.AgentToolJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Generated from schemas/oob/omniflow_function.v2.json and omniflow_checker_rule.v1.json. */
internal object GeneratedFunctionContractSchemas {{
    val function: Map<String, Any?> by lazy {{ parse(FUNCTION_JSON) }}
    val functionToolInput: Map<String, Any?> by lazy {{ parse(FUNCTION_TOOL_INPUT_JSON) }}
    val checker: Map<String, Any?> by lazy {{ parse(CHECKER_JSON) }}

    private fun parse(value: String): Map<String, Any?> =
        AgentToolJson.jsonObjectToMap(Json.parseToJsonElement(value).jsonObject)

    private const val FUNCTION_JSON = {kotlin_string(function)}
    private const val FUNCTION_TOOL_INPUT_JSON = {kotlin_string(function_tool_input)}
    private const val CHECKER_JSON = {kotlin_string(checker)}
}}
'''


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--stdout", action="store_true")
    args = parser.parse_args()
    content = generate()
    if args.stdout:
        print(content, end="")
        return 0
    if args.check:
        if not OUTPUT.is_file() or OUTPUT.read_text(encoding="utf-8") != content:
            raise SystemExit("Generated Function schema accessor is stale")
        return 0
    OUTPUT.write_text(content, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
