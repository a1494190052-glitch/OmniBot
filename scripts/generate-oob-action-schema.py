#!/usr/bin/env python3
"""Generate OOB action schema accessors from the canonical JSON schema."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
SCHEMA_PATH = ROOT / "schemas/oob/oob_canonical_actions.v1.json"
KOTLIN_OUT = ROOT / "baselib/src/main/java/cn/com/omnimind/baselib/runlog/OobCanonicalActionSchema.kt"
DART_OUT = ROOT / "ui/lib/features/task/run_log/oob_canonical_action_schema.dart"


def read_schema() -> dict[str, Any]:
    return json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))


def const_name(prefix: str, value: str) -> str:
    token = re.sub(r"[^A-Za-z0-9]+", "_", value).strip("_").upper()
    if token and token[0].isdigit():
        token = f"_{token}"
    return f"{prefix}_{token}"


def kt_str(value: str) -> str:
    return json.dumps(value, ensure_ascii=False)


def kt_bool(value: bool) -> str:
    return "true" if value else "false"


def kt_number(value: Any) -> str:
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        return repr(value)
    raise TypeError(f"Unsupported Kotlin number: {value!r}")


def kt_localized(value: dict[str, str]) -> str:
    return f"LocalizedText(zhCn = {kt_str(value.get('zh_cn', ''))}, enUs = {kt_str(value.get('en_us', ''))})"


def kt_arg(arg: dict[str, Any], indent: str = "                ") -> str:
    parts = [
        f"name = {kt_str(arg['name'])}",
        f"type = Type.{arg['type'].upper()}",
    ]
    if arg.get("required", False):
        parts.append("required = true")
    description = arg.get("description", {"zh_cn": "", "en_us": ""})
    parts.append(f"description = {kt_localized(description)}")
    enum_values = arg.get("enum_values") or []
    if enum_values:
        values = ", ".join(kt_str(item) for item in enum_values)
        parts.append(f"enumValues = listOf({values})")
    if "minimum" in arg:
        parts.append(f"minimum = {kt_number(arg['minimum'])}")
    if "maximum" in arg:
        parts.append(f"maximum = {kt_number(arg['maximum'])}")
    if arg.get("additional_properties", False):
        parts.append("additionalProperties = true")
    joined = (",\n" + indent + "    ").join(parts)
    return f"ArgSpec(\n{indent}    {joined},\n{indent})"


def kt_tool(tool: dict[str, Any]) -> str:
    args = tool.get("args", [])
    args_code = "emptyList()" if not args else "listOf(\n" + ",\n".join(
        kt_arg(arg) for arg in args
    ) + ",\n            )"
    return f"""        ToolSpec(
            name = {kt_str(tool["name"])},
            uiLabel = {kt_localized(tool.get("ui_label", {"zh_cn": tool["name"], "en_us": tool["name"]}))},
            description = {kt_localized(tool["description"])},
            promptGuide = {kt_localized(tool["prompt_guide"])},
            argsTemplate = {kt_map_literal(tool.get("args_template", {}))},
            args = {args_code},
            modelVisible = {kt_bool(tool.get("model_visible", True))},
            replayable = {kt_bool(tool.get("replayable", True))},
            editorVisible = {kt_bool(tool.get("editor_visible", tool.get("replayable", True)))},
            recordable = {kt_bool(tool.get("recordable", False))},
            coordinateAction = {kt_bool(tool.get("coordinate_action", False))},
            pointTargetAction = {kt_bool(tool.get("point_target_action", False))},
            routeAction = {kt_bool(tool.get("route_action", False))},
        )"""


def kt_map_literal(value: dict[str, Any]) -> str:
    if not value:
        return "emptyMap()"
    pairs = []
    for key, item in value.items():
        if isinstance(item, str):
            pairs.append(f"{kt_str(key)} to {kt_str(item)}")
        elif isinstance(item, bool):
            pairs.append(f"{kt_str(key)} to {kt_bool(item)}")
        elif isinstance(item, (int, float)):
            pairs.append(f"{kt_str(key)} to {kt_number(item)}")
        else:
            raise TypeError(f"Unsupported Kotlin map literal value: {item!r}")
    return "mapOf(" + ", ".join(pairs) + ")"


def generate_kotlin(schema: dict[str, Any]) -> str:
    tools = schema["tools"]
    tool_consts = "\n".join(
        f"    const val {const_name('TOOL', tool['name'])} = {kt_str(tool['name'])}"
        for tool in tools
    )
    arg_names: list[str] = []
    for tool in tools:
        for arg in tool.get("args", []):
            if arg["name"] not in arg_names:
                arg_names.append(arg["name"])
    arg_consts = "\n".join(
        f"    const val {const_name('ARG', name)} = {kt_str(name)}" for name in arg_names
    )
    source_context_arg_names = schema.get("source_context_arg_names", arg_names)
    source_context_args_code = ", ".join(kt_str(name) for name in source_context_arg_names)
    tools_code = ",\n".join(kt_tool(tool) for tool in tools)
    return f"""package cn.com.omnimind.baselib.runlog

/**
 * Generated from schemas/oob/oob_canonical_actions.v1.json.
 *
 * Do not edit action/tool names or argument fields here. Update the schema and
 * run `python3 scripts/generate-oob-action-schema.py`.
 */
object OobCanonicalActionSchema {{
    const val SCHEMA_VERSION = {kt_str(schema["schema_version"])}
    const val ROOT_TOOL = {kt_str(schema["root_tool"])}
    const val ROOT_ARGS = {kt_str(schema["root_args"])}

{tool_consts}

{arg_consts}

    enum class Type {{
        STRING,
        NUMBER,
        INTEGER,
        BOOLEAN,
        OBJECT,
        STRING_ARRAY,
    }}

    data class LocalizedText(
        val zhCn: String,
        val enUs: String,
    )

    data class ArgSpec(
        val name: String,
        val type: Type,
        val required: Boolean = false,
        val description: LocalizedText = LocalizedText("", ""),
        val enumValues: List<String> = emptyList(),
        val minimum: Number? = null,
        val maximum: Number? = null,
        val additionalProperties: Boolean = false,
    )

    data class ToolSpec(
        val name: String,
        val uiLabel: LocalizedText,
        val description: LocalizedText,
        val promptGuide: LocalizedText,
        val argsTemplate: Map<String, Any?> = emptyMap(),
        val args: List<ArgSpec> = emptyList(),
        val modelVisible: Boolean = true,
        val replayable: Boolean = true,
        val editorVisible: Boolean = true,
        val recordable: Boolean = false,
        val coordinateAction: Boolean = false,
        val pointTargetAction: Boolean = false,
        val routeAction: Boolean = false,
    )

    val tools: List<ToolSpec> = listOf(
{tools_code},
    )

    val modelVisibleTools: List<ToolSpec> = tools.filter {{ it.modelVisible }}
    val replayableToolNames: Set<String> = tools.filter {{ it.replayable }}.mapTo(linkedSetOf()) {{ it.name }}
    val editorVisibleTools: List<ToolSpec> = tools.filter {{ it.editorVisible }}
    val recordableToolNames: Set<String> = tools.filter {{ it.recordable }}.mapTo(linkedSetOf()) {{ it.name }}
    val coordinateToolNames: Set<String> = tools.filter {{ it.coordinateAction }}.mapTo(linkedSetOf()) {{ it.name }}
    val pointTargetToolNames: Set<String> = tools.filter {{ it.pointTargetAction }}.mapTo(linkedSetOf()) {{ it.name }}
    val routeToolNames: Set<String> = tools.filter {{ it.routeAction }}.mapTo(linkedSetOf()) {{ it.name }}
    val sourceContextArgNames: Set<String> = linkedSetOf({source_context_args_code})

    private val toolsByName: Map<String, ToolSpec> = tools.associateBy {{ it.name }}

    fun tool(name: String): ToolSpec? = toolsByName[normalizeToolName(name)]

    fun normalizeToolName(raw: String): String =
        raw.trim().lowercase()

    fun canonicalToolName(raw: String): String? {{
        val normalized = normalizeToolName(raw)
        return normalized.takeIf {{ toolsByName.containsKey(it) }}
    }}

    fun argNames(toolName: String): Set<String> =
        tool(toolName)?.args?.mapTo(linkedSetOf()) {{ it.name }} ?: emptySet()

    fun requiredArgNames(toolName: String): List<String> =
        tool(toolName)?.args?.filter {{ it.required }}?.map {{ it.name }} ?: emptyList()

    fun argsTemplate(toolName: String): Map<String, Any?> =
        tool(toolName)?.argsTemplate ?: emptyMap()

    fun supportsAdditionalProperties(toolName: String): Boolean =
        tool(toolName)?.args?.any {{ it.additionalProperties }} == true
}}
"""


def dart_str(value: str) -> str:
    return json.dumps(value, ensure_ascii=False)


def dart_bool(value: bool) -> str:
    return "true" if value else "false"


def dart_number(value: Any) -> str:
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        return repr(value)
    raise TypeError(f"Unsupported Dart number: {value!r}")


def dart_type(value: str) -> str:
    return {
        "string": "OobActionArgType.string",
        "number": "OobActionArgType.number",
        "integer": "OobActionArgType.integer",
        "boolean": "OobActionArgType.boolean",
        "object": "OobActionArgType.object",
        "string_array": "OobActionArgType.stringArray",
    }[value]


def dart_localized(value: dict[str, str]) -> str:
    return f"OobLocalizedText(zhCn: {dart_str(value.get('zh_cn', ''))}, enUs: {dart_str(value.get('en_us', ''))})"


def dart_map(value: dict[str, Any]) -> str:
    if not value:
        return "const <String, Object?>{}"
    pairs = []
    for key, item in value.items():
        if isinstance(item, str):
            literal = dart_str(item)
        elif isinstance(item, bool):
            literal = dart_bool(item)
        elif isinstance(item, (int, float)):
            literal = dart_number(item)
        else:
            raise TypeError(f"Unsupported Dart map literal value: {item!r}")
        pairs.append(f"{dart_str(key)}: {literal}")
    return "const <String, Object?>{" + ", ".join(pairs) + "}"


def dart_arg(arg: dict[str, Any]) -> str:
    fields = [
        f"name: {dart_str(arg['name'])}",
        f"type: {dart_type(arg['type'])}",
    ]
    if arg.get("required", False):
        fields.append("required: true")
    fields.append(f"description: {dart_localized(arg.get('description', {'zh_cn': '', 'en_us': ''}))}")
    enum_values = arg.get("enum_values") or []
    if enum_values:
        fields.append("enumValues: <String>[" + ", ".join(dart_str(item) for item in enum_values) + "]")
    if "minimum" in arg:
        fields.append(f"minimum: {dart_number(arg['minimum'])}")
    if "maximum" in arg:
        fields.append(f"maximum: {dart_number(arg['maximum'])}")
    if arg.get("additional_properties", False):
        fields.append("additionalProperties: true")
    return "OobActionArgSpec(" + ", ".join(fields) + ")"


def dart_tool(tool: dict[str, Any]) -> str:
    args = tool.get("args", [])
    args_code = "const <OobActionArgSpec>[]" if not args else "<OobActionArgSpec>[\n        " + ",\n        ".join(dart_arg(arg) for arg in args) + ",\n      ]"
    return f"""OobActionToolSpec(
      name: {dart_str(tool["name"])},
      uiLabel: {dart_localized(tool.get("ui_label", {"zh_cn": tool["name"], "en_us": tool["name"]}))},
      description: {dart_localized(tool["description"])},
      promptGuide: {dart_localized(tool["prompt_guide"])},
      argsTemplate: {dart_map(tool.get("args_template", {}))},
      args: {args_code},
      modelVisible: {dart_bool(tool.get("model_visible", True))},
      replayable: {dart_bool(tool.get("replayable", True))},
      editorVisible: {dart_bool(tool.get("editor_visible", tool.get("replayable", True)))},
      recordable: {dart_bool(tool.get("recordable", False))},
      coordinateAction: {dart_bool(tool.get("coordinate_action", False))},
      pointTargetAction: {dart_bool(tool.get("point_target_action", False))},
      routeAction: {dart_bool(tool.get("route_action", False))},
    )"""


def generate_dart(schema: dict[str, Any]) -> str:
    tools = schema["tools"]
    tool_consts = "\n".join(
        f"  static const {camel_name('tool', tool['name'])} = {dart_str(tool['name'])};"
        for tool in tools
    )
    # Use explicit readable constants for fields that other code references.
    arg_names: list[str] = []
    for tool in tools:
        for arg in tool.get("args", []):
            if arg["name"] not in arg_names:
                arg_names.append(arg["name"])
    arg_consts = "\n".join(
        f"  static const {camel_name('arg', name)} = {dart_str(name)};" for name in arg_names
    )
    tools_code = ",\n    ".join(dart_tool(tool) for tool in tools)
    replayable = [tool["name"] for tool in tools if tool.get("replayable", True)]
    recordable = [tool["name"] for tool in tools if tool.get("recordable", False)]
    coordinate = [tool["name"] for tool in tools if tool.get("coordinate_action", False)]
    point = [tool["name"] for tool in tools if tool.get("point_target_action", False)]
    route = [tool["name"] for tool in tools if tool.get("route_action", False)]
    source_context_arg_names = schema.get("source_context_arg_names", arg_names)
    return f"""// Generated from schemas/oob/oob_canonical_actions.v1.json.
// Do not edit action/tool names or argument fields here. Update the schema and
// run `python3 scripts/generate-oob-action-schema.py`.

enum OobActionArgType {{ string, number, integer, boolean, object, stringArray }}

class OobLocalizedText {{
  const OobLocalizedText({{required this.zhCn, required this.enUs}});

  final String zhCn;
  final String enUs;

  String text({{bool useEnglish = false}}) => useEnglish ? enUs : zhCn;
}}

class OobActionArgSpec {{
  const OobActionArgSpec({{
    required this.name,
    required this.type,
    this.required = false,
    this.description = const OobLocalizedText(zhCn: '', enUs: ''),
    this.enumValues = const <String>[],
    this.minimum,
    this.maximum,
    this.additionalProperties = false,
  }});

  final String name;
  final OobActionArgType type;
  final bool required;
  final OobLocalizedText description;
  final List<String> enumValues;
  final num? minimum;
  final num? maximum;
  final bool additionalProperties;
}}

class OobActionToolSpec {{
  const OobActionToolSpec({{
    required this.name,
    required this.uiLabel,
    required this.description,
    required this.promptGuide,
    this.argsTemplate = const <String, Object?>{{}},
    this.args = const <OobActionArgSpec>[],
    this.modelVisible = true,
    this.replayable = true,
    this.editorVisible = true,
    this.recordable = false,
    this.coordinateAction = false,
    this.pointTargetAction = false,
    this.routeAction = false,
  }});

  final String name;
  final OobLocalizedText uiLabel;
  final OobLocalizedText description;
  final OobLocalizedText promptGuide;
  final Map<String, Object?> argsTemplate;
  final List<OobActionArgSpec> args;
  final bool modelVisible;
  final bool replayable;
  final bool editorVisible;
  final bool recordable;
  final bool coordinateAction;
  final bool pointTargetAction;
  final bool routeAction;
}}

class OobCanonicalActionSchema {{
  const OobCanonicalActionSchema._();

  static const schemaVersion = {dart_str(schema["schema_version"])};
  static const rootTool = {dart_str(schema["root_tool"])};
  static const rootArgs = {dart_str(schema["root_args"])};

{tool_consts}

{arg_consts}

  static const tools = <OobActionToolSpec>[
    {tools_code},
  ];

  static const replayableToolNames = <String>{{{", ".join(dart_str(name) for name in replayable)}}};
  static const recordableToolNames = <String>{{{", ".join(dart_str(name) for name in recordable)}}};
  static const coordinateToolNames = <String>{{{", ".join(dart_str(name) for name in coordinate)}}};
  static const pointTargetToolNames = <String>{{{", ".join(dart_str(name) for name in point)}}};
  static const routeToolNames = <String>{{{", ".join(dart_str(name) for name in route)}}};
  static const sourceContextArgNames = <String>{{{", ".join(dart_str(name) for name in source_context_arg_names)}}};
  static const actionAliases = <String, String>{{}};

  static List<OobActionToolSpec> get editorVisibleTools =>
      tools.where((tool) => tool.editorVisible).toList(growable: false);

  static String normalizeToolName(String raw) => raw.trim().toLowerCase();

  static String? canonicalToolName(String raw) {{
    final normalized = normalizeToolName(raw);
    for (final tool in tools) {{
      if (tool.name == normalized) return tool.name;
    }}
    return null;
  }}

  static OobActionToolSpec? tool(String raw) {{
    final canonical = canonicalToolName(raw);
    if (canonical == null) return null;
    for (final tool in tools) {{
      if (tool.name == canonical) return tool;
    }}
    return null;
  }}

  static Set<String> argNames(String toolName) =>
      tool(toolName)?.args.map((arg) => arg.name).toSet() ?? const <String>{{}};

  static List<String> requiredArgNames(String toolName) =>
      tool(toolName)
          ?.args
          .where((arg) => arg.required)
          .map((arg) => arg.name)
          .toList(growable: false) ??
      const <String>[];
}}
"""


def camel_name(prefix: str, value: str) -> str:
    parts = re.sub(r"[^A-Za-z0-9]+", "_", value).strip("_").split("_")
    if not parts:
        return prefix
    return prefix + "".join(part[:1].upper() + part[1:] for part in parts)


def main() -> None:
    schema = read_schema()
    KOTLIN_OUT.write_text(generate_kotlin(schema), encoding="utf-8")
    DART_OUT.write_text(generate_dart(schema), encoding="utf-8")
    print(f"Generated {KOTLIN_OUT.relative_to(ROOT)}")
    print(f"Generated {DART_OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
