#!/usr/bin/env python3
"""Collect successful AndroidWorld checkpoint episodes into a stable bundle.

This script does not execute AndroidWorld and does not convert schemas. It only
reads AndroidWorld checkpointer outputs, keeps validator-success episodes, copies
their original checkpoint files, and writes a compact JSON action log beside
each accepted episode.
"""

from __future__ import annotations

import argparse
import gzip
import json
import pickle
import shutil
from pathlib import Path
from typing import Any


def _safe_json(value: Any) -> Any:
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, dict):
        return {str(key): _safe_json(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_safe_json(item) for item in value]
    if hasattr(value, "model_dump"):
        try:
            return _safe_json(value.model_dump())
        except Exception:
            pass
    if hasattr(value, "to_dict"):
        try:
            return _safe_json(value.to_dict())
        except Exception:
            pass
    return str(value)


def _seq_get(value: Any, index: int, default: Any = None) -> Any:
    try:
        if isinstance(value, (list, tuple)) and index < len(value):
            return value[index]
    except Exception:
        return default
    return default


def _action_to_dict(value: Any) -> dict[str, Any]:
    converted = _safe_json(value)
    return converted if isinstance(converted, dict) else {}


def _load_checkpoint(path: Path) -> list[dict[str, Any]]:
    with gzip.open(path, "rb") as handle:
        payload = pickle.load(handle)
    if not isinstance(payload, list):
        return []
    return [item for item in payload if isinstance(item, dict)]


def _compact_episode(checkpoint: Path, episode_index: int, episode: dict[str, Any]) -> dict[str, Any]:
    episode_data = episode.get("episode_data")
    if not isinstance(episode_data, dict):
        episode_data = {}
    step_numbers = episode_data.get("step_number") or []
    actions: list[dict[str, Any]] = []
    for index in range(len(step_numbers)):
        raw_action = _action_to_dict(_seq_get(episode_data.get("action_output_json"), index))
        actions.append(
            {
                "step_index": index,
                "step_number": _safe_json(_seq_get(step_numbers, index)),
                "raw_action": raw_action,
                "raw_action_type": str(raw_action.get("action_type") or "").strip(),
                "action_reason": str(_seq_get(episode_data.get("action_reason"), index, "") or ""),
                "action_output": str(_seq_get(episode_data.get("action_output"), index, "") or ""),
                "summary": str(_seq_get(episode_data.get("summary"), index, "") or ""),
                "before_ui_elements": _safe_json(
                    _seq_get(episode_data.get("before_ui_elements"), index, []) or []
                ),
                "after_ui_elements": _safe_json(
                    _seq_get(episode_data.get("after_ui_elements"), index, []) or []
                ),
            }
        )
    return {
        "schema": "androidworld_successful_runlog.v1",
        "source_checkpoint": str(checkpoint),
        "episode_index": episode_index,
        "goal": str(episode.get("goal") or ""),
        "task_template": str(episode.get("task_template") or ""),
        "seed": episode.get("seed"),
        "instance_id": episode.get("instance_id"),
        "agent_name": episode.get("agent_name"),
        "is_successful": episode.get("is_successful"),
        "episode_length": episode.get("episode_length"),
        "run_time": episode.get("run_time"),
        "finish_dtime": str(episode.get("finish_dtime") or ""),
        "screen_config": _safe_json(episode.get("screen_config") or {}),
        "actions": actions,
    }


def collect_successful_runlogs(input_roots: list[Path], output_dir: Path) -> dict[str, Any]:
    checkpoint_paths: list[Path] = []
    for root in input_roots:
        if root.is_file() and root.name.endswith(".pkl.gz"):
            checkpoint_paths.append(root)
        elif root.exists():
            checkpoint_paths.extend(root.rglob("*.pkl.gz"))
    checkpoint_paths = sorted(set(path.resolve() for path in checkpoint_paths))

    checkpoints_dir = output_dir / "checkpoints"
    compact_dir = output_dir / "compact_runlogs"
    checkpoints_dir.mkdir(parents=True, exist_ok=True)
    compact_dir.mkdir(parents=True, exist_ok=True)

    manifest_path = output_dir / "successful_manifest.jsonl"
    failures_path = output_dir / "read_failures.jsonl"
    summary: dict[str, Any] = {
        "schema": "androidworld_successful_runlog_collection.v1",
        "input_roots": [str(path) for path in input_roots],
        "output_dir": str(output_dir),
        "checkpoint_file_count": len(checkpoint_paths),
        "episode_count": 0,
        "successful_episode_count": 0,
        "task_success_counts": {},
        "read_failure_count": 0,
    }

    with manifest_path.open("w", encoding="utf-8") as manifest, failures_path.open(
        "w", encoding="utf-8"
    ) as failures:
        for checkpoint in checkpoint_paths:
            try:
                episodes = _load_checkpoint(checkpoint)
            except Exception as exc:  # noqa: BLE001
                summary["read_failure_count"] += 1
                failures.write(
                    json.dumps(
                        {"checkpoint": str(checkpoint), "error": repr(exc)},
                        ensure_ascii=False,
                    )
                    + "\n"
                )
                continue
            summary["episode_count"] += len(episodes)
            for episode_index, episode in enumerate(episodes):
                try:
                    success = float(episode.get("is_successful") or 0.0) > 0.5
                except Exception:
                    success = False
                if not success:
                    continue
                compact = _compact_episode(checkpoint, episode_index, episode)
                task = compact["task_template"] or "UnknownTask"
                stem = f"{task}_seed_{compact.get('seed')}_idx_{episode_index}"
                copied_checkpoint = checkpoints_dir / f"{stem}.pkl.gz"
                compact_path = compact_dir / f"{stem}.json"
                shutil.copy2(checkpoint, copied_checkpoint)
                compact_path.write_text(
                    json.dumps(compact, ensure_ascii=False, indent=2),
                    encoding="utf-8",
                )
                record = {
                    "task_template": task,
                    "goal": compact["goal"],
                    "seed": compact.get("seed"),
                    "instance_id": compact.get("instance_id"),
                    "episode_length": compact.get("episode_length"),
                    "run_time": compact.get("run_time"),
                    "source_checkpoint": str(checkpoint),
                    "copied_checkpoint": str(copied_checkpoint),
                    "compact_runlog": str(compact_path),
                }
                manifest.write(json.dumps(record, ensure_ascii=False) + "\n")
                summary["successful_episode_count"] += 1
                counts = summary["task_success_counts"]
                counts[task] = int(counts.get(task, 0)) + 1

    summary["unique_successful_task_count"] = len(summary["task_success_counts"])
    summary_path = output_dir / "summary.json"
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Collect validator-success AndroidWorld checkpoint runlogs."
    )
    parser.add_argument("--input-root", action="append", required=True)
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args()
    summary = collect_successful_runlogs(
        [Path(item).expanduser().resolve() for item in args.input_root],
        Path(args.output_dir).expanduser().resolve(),
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
