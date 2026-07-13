#!/usr/bin/env python3
"""Summarize or compare Agent workflow artifacts for branch-vs-main checks.

Inputs are intentionally plain JSON so a failing device run can be captured
without wiring this script into the app:

  tools/agent-workflow-snapshot.py --events-jsonl native-events.jsonl \
    --messages-json messages.json > snapshot.txt

  tools/agent-workflow-snapshot.py \
    --baseline-events-jsonl main-events.jsonl \
    --baseline-messages-json main-messages.json \
    --candidate-events-jsonl branch-events.jsonl \
    --candidate-messages-json branch-messages.json

`--events-jsonl` accepts either raw onAgentStreamEvent payloads or method-call
objects shaped like {"method":"onAgentStreamEvent","arguments":{...}}.
`--messages-json` accepts a ChatMessageModel JSON list or an object containing
`messages`.
"""

from __future__ import annotations

import argparse
import difflib
import json
import sys
from collections import OrderedDict
from pathlib import Path
from typing import Any


def as_map(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def as_list(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def text(value: Any) -> str:
    return "" if value is None else str(value)


def truthy(value: Any) -> bool:
    return value is True or text(value).lower() == "true"


def read_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def read_events(path: Path | None) -> list[dict[str, Any]]:
    if path is None:
        return []
    events: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, start=1):
            raw = line.strip()
            if not raw:
                continue
            try:
                item = json.loads(raw)
            except json.JSONDecodeError as exc:
                raise SystemExit(f"{path}:{line_no}: invalid JSON: {exc}") from exc
            payload = as_map(item)
            if payload.get("method") == "onAgentStreamEvent":
                payload = as_map(payload.get("arguments"))
            events.append(payload)
    return events


def read_messages(path: Path | None) -> list[dict[str, Any]]:
    if path is None:
        return []
    raw = read_json(path)
    if isinstance(raw, dict):
        raw = raw.get("messages", [])
    return [as_map(item) for item in as_list(raw)]


def normalize_event(payload: dict[str, Any]) -> dict[str, Any]:
    return {
        "taskId": text(payload.get("taskId")),
        "seq": int_or(payload.get("seq"), 0),
        "kind": text(payload.get("kind")).strip().lower(),
        "entryId": none_if_blank(payload.get("entryId")),
        "roundIndex": int_or(payload.get("roundIndex"), 0),
        "isFinal": truthy(payload.get("isFinal")),
        "textLen": len(text(payload.get("text", payload.get("message", "")))),
        "thinkingLen": len(
            text(payload.get("thinking", payload.get("reasoning_content", "")))
        ),
        "success": payload.get("success") is not False,
        "outputKind": text(payload.get("outputKind", "none")),
        "errorLen": len(text(payload.get("error", ""))),
    }


def int_or(value: Any, default: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def none_if_blank(value: Any) -> str | None:
    normalized = text(value).strip()
    return normalized or None


def card_data(message: dict[str, Any]) -> dict[str, Any]:
    return as_map(as_map(message.get("content")).get("cardData"))


def message_text(message: dict[str, Any]) -> str:
    return text(as_map(message.get("content")).get("text"))


def message_id(message: dict[str, Any]) -> str:
    return text(message.get("id") or as_map(message.get("content")).get("id"))


def message_stream_meta(message: dict[str, Any]) -> dict[str, Any]:
    return as_map(message.get("streamMeta"))


def message_summary(message: dict[str, Any]) -> dict[str, Any]:
    card = card_data(message)
    meta = message_stream_meta(message)
    reasoning = text(
        message.get("reasoning_content", message.get("reasoningContent", ""))
    )
    return {
        "id": message_id(message),
        "user": int_or(message.get("user"), 0),
        "type": int_or(message.get("type"), 1),
        "cardType": text(card.get("type")),
        "taskId": agent_task_id(message),
        "kind": text(meta.get("kind")),
        "isFinal": truthy(meta.get("isFinal")),
        "isLoading": truthy(message.get("isLoading")),
        "textLen": len(message_text(message)),
        "reasoningLen": len(reasoning),
        "thinkingLen": len(text(card.get("thinkingContent", card.get("thinking", "")))),
    }


def agent_task_id(message: dict[str, Any]) -> str:
    meta = message_stream_meta(message)
    card = card_data(message)
    explicit = none_if_blank(
        meta.get("parentTaskId") or card.get("taskID") or card.get("taskId")
    )
    if explicit:
        return explicit
    entry_id = message_id(message)
    return task_id_from_entry_id(entry_id) or ""


def task_id_from_entry_id(entry_id: str) -> str | None:
    if not entry_id:
        return None
    for marker in ("-thinking", "-tool", "-assistant"):
        if marker in entry_id:
            return entry_id.split(marker, 1)[0] or None
    return None


def is_agent_candidate(message: dict[str, Any]) -> bool:
    user = int_or(message.get("user"), 0)
    msg_type = int_or(message.get("type"), 1)
    if user == 1:
        return False
    if msg_type == 1:
        return user == 2
    if msg_type != 2:
        return False
    return text(card_data(message).get("type")) in {
        "deep_thinking",
        "agent_tool_summary",
        "permission_section",
        "codex_request",
    }


def timeline_summary(messages: list[dict[str, Any]]) -> list[dict[str, Any]]:
    groups: OrderedDict[str, list[dict[str, Any]]] = OrderedDict()
    for message in messages:
        task_id = agent_task_id(message)
        if not task_id or not is_agent_candidate(message):
            continue
        groups.setdefault(task_id, []).append(message)

    output: list[dict[str, Any]] = []
    for task_id, task_messages in groups.items():
        visible = [
            msg
            for msg in task_messages
            if int_or(msg.get("type"), 1) == 1 and int_or(msg.get("user"), 0) == 2
        ]
        process = [msg for msg in task_messages if msg not in visible]
        output.append(
            {
                "taskId": task_id,
                "visible": [message_id(msg) for msg in visible],
                "process": [
                    {
                        "id": message_id(msg),
                        "cardType": text(card_data(msg).get("type")),
                        "isLoading": truthy(msg.get("isLoading")),
                        "thinkingLen": len(
                            text(
                                card_data(msg).get(
                                    "thinkingContent", card_data(msg).get("thinking", "")
                                )
                            )
                        ),
                    }
                    for msg in process
                ],
            }
        )
    return output


def build_snapshot(
    events: list[dict[str, Any]],
    messages: list[dict[str, Any]],
) -> dict[str, list[dict[str, Any]]]:
    normalized_events = [normalize_event(event) for event in events]
    return {
        "native onAgentStreamEvent payloads": normalized_events,
        "Flutter AgentStreamEvent.fromMap equivalent": normalized_events,
        "ChatMessageModel list": [message_summary(msg) for msg in messages],
        "buildAgentRunTimelineEntries equivalent": timeline_summary(messages),
    }


def dump_section(title: str, rows: list[dict[str, Any]]) -> None:
    print(f"## {title}")
    if not rows:
        print("(empty)")
        print()
        return
    for index, row in enumerate(rows):
        print(f"{index:03d} {json.dumps(row, ensure_ascii=False, sort_keys=True)}")
    print()


def dump_snapshot(snapshot: dict[str, list[dict[str, Any]]]) -> None:
    for title, rows in snapshot.items():
        dump_section(title, rows)


def snapshot_text(snapshot: dict[str, list[dict[str, Any]]]) -> str:
    return json.dumps(snapshot, ensure_ascii=False, indent=2, sort_keys=True)


def compare_snapshots(
    baseline: dict[str, list[dict[str, Any]]],
    candidate: dict[str, list[dict[str, Any]]],
) -> int:
    baseline_text = snapshot_text(baseline)
    candidate_text = snapshot_text(candidate)
    if baseline_text == candidate_text:
        print("PASS Agent workflow snapshot matches baseline.")
        return 0
    print("FAIL Agent workflow snapshot differs from baseline.")
    print(
        "\n".join(
            difflib.unified_diff(
                baseline_text.splitlines(),
                candidate_text.splitlines(),
                fromfile="baseline",
                tofile="candidate",
                lineterm="",
            )
        )
    )
    return 1


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--events-jsonl", type=Path)
    parser.add_argument("--messages-json", type=Path)
    parser.add_argument("--baseline-events-jsonl", type=Path)
    parser.add_argument("--baseline-messages-json", type=Path)
    parser.add_argument("--candidate-events-jsonl", type=Path)
    parser.add_argument("--candidate-messages-json", type=Path)
    args = parser.parse_args()

    compare_mode = any(
        value is not None
        for value in (
            args.baseline_events_jsonl,
            args.baseline_messages_json,
            args.candidate_events_jsonl,
            args.candidate_messages_json,
        )
    )
    if compare_mode:
        if args.events_jsonl is not None or args.messages_json is not None:
            parser.error("single snapshot and compare arguments cannot be mixed")
        if (
            args.baseline_events_jsonl is None
            and args.baseline_messages_json is None
        ) or (
            args.candidate_events_jsonl is None
            and args.candidate_messages_json is None
        ):
            parser.error(
                "compare mode requires at least one baseline artifact and one "
                "candidate artifact"
            )
        baseline = build_snapshot(
            read_events(args.baseline_events_jsonl),
            read_messages(args.baseline_messages_json),
        )
        candidate = build_snapshot(
            read_events(args.candidate_events_jsonl),
            read_messages(args.candidate_messages_json),
        )
        return compare_snapshots(baseline, candidate)

    snapshot = build_snapshot(
        read_events(args.events_jsonl),
        read_messages(args.messages_json),
    )
    dump_snapshot(snapshot)
    return 0


if __name__ == "__main__":
    sys.exit(main())
