#!/usr/bin/env python3
"""Batch AndroidWorld goal-derived OOB collection, replay, and memory export."""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import json
import re
import shlex
import subprocess
import sys
import time
from pathlib import Path
from typing import Any
from urllib.parse import urlencode
from urllib.request import urlopen


REPO_ROOT = Path(__file__).resolve().parents[1]
PAPER_EXPORT_ROOT = Path("/Users/wuzewen/Projects/Omni/omniflow-paper-export")
ANDROID_WORLD_ROOT = Path.home() / "Projects" / "android_world"
OMNIFLOW_ROOT = Path("/Users/wuzewen/Projects/Omni/Omniflow")
DEFAULT_PACKAGE = "cn.com.omnimind.bot.debug"


DEFAULT_CASES = [
    {"task": "AudioRecorderRecordAudio", "params": {}},
    {"task": "AudioRecorderRecordAudioWithFileName", "params": {"file_name": "oob_audio_phi"}},
    {"task": "CameraTakePhoto", "params": {}},
    {"task": "CameraTakeVideo", "params": {}},
    {"task": "ClockStopWatchPausedVerify", "params": {}},
    {"task": "ClockStopWatchRunning", "params": {}},
    {"task": "ClockTimerEntry", "params": {"hours": 0, "minutes": 1, "seconds": 15}},
    {"task": "ContactsAddContact", "params": {"name": "OOB Contact Omicron", "number": "5558642097"}},
    {
        "task": "ContactsNewContactDraft",
        "params": {"first": "OOB", "last": "Enhanced", "phone": "5551234567", "phone_label": "Mobile"},
    },
    {"task": "MarkorCreateFolder", "params": {"folder_name": "oob_folder_omega"}},
    {
        "task": "MarkorCreateNote",
        "params": {"file_name": "note_oob_tau.md", "text": "OOB tau note body for enhanced Function replay."},
    },
    {"task": "OpenAppTaskEval", "params": {"app_name": "settings"}},
    {
        "task": "RecipeAddSingleRecipe",
        "params": {
            "title": "OOB Herb Rice",
            "description": "A fast savory rice bowl",
            "servings": "3",
            "preparationTime": "12 min",
            "source": "Codex kitchen",
            "ingredients": "rice\nherbs\nsalt",
            "directions": "Warm rice\nMix herbs\nSeason lightly",
        },
    },
    {
        "task": "SimpleCalendarAddOneEvent",
        "params": {
            "year": 2023,
            "month": 10,
            "day": 16,
            "hour": 16,
            "duration_mins": 30,
            "event_title": "OOB Enhanced Calendar Replay",
            "event_description": "Enhanced function keeps minute focus clicks.",
        },
    },
    {"task": "SimpleCalendarAddOneEventTomorrow", "params": {"day": 16, "hour": 16, "duration_mins": 30}},
    {"task": "SimpleSmsSend", "params": {"number": "5551237788", "message": "OOB enhanced SMS replay."}},
    {"task": "SystemBluetoothTurnOff", "params": {}},
    {"task": "SystemBluetoothTurnOffVerify", "params": {}},
    {"task": "SystemBluetoothTurnOn", "params": {}},
    {"task": "SystemBluetoothTurnOnVerify", "params": {}},
    {"task": "SystemBrightnessMax", "params": {"max_or_min": "max"}},
    {"task": "SystemBrightnessMaxVerify", "params": {}},
    {"task": "SystemBrightnessMin", "params": {"max_or_min": "min"}},
    {"task": "SystemBrightnessMinVerify", "params": {}},
    {"task": "SystemWifiTurnOff", "params": {}},
    {"task": "SystemWifiTurnOffVerify", "params": {}},
    {"task": "SystemWifiTurnOn", "params": {}},
    {"task": "SystemWifiTurnOnVerify", "params": {}},
    {"task": "TurnOffWifiAndTurnOnBluetooth", "params": {}},
    {"task": "TurnOnWifiAndOpenApp", "params": {"app_name": "settings"}},
]

TASK_APP_NAMES = {
    "AudioRecorderRecordAudio": "audio_recorder",
    "AudioRecorderRecordAudioWithFileName": "audio_recorder",
    "BrowserDraw": "chrome",
    "BrowserMaze": "chrome",
    "BrowserMultiply": "chrome",
    "CameraTakePhoto": "camera",
    "CameraTakeVideo": "camera",
    "ClockStopWatchPausedVerify": "clock",
    "ClockStopWatchRunning": "clock",
    "ClockTimerEntry": "clock",
    "ContactsAddContact": "contacts",
    "ContactsNewContactDraft": "contacts",
    "MarkorCreateFolder": "markor",
    "MarkorCreateNote": "markor",
    "OpenAppTaskEval": "system",
    "RecipeAddSingleRecipe": "broccoli",
    "SimpleCalendarAddOneEvent": "simple_calendar",
    "SimpleCalendarAddOneEventTomorrow": "simple_calendar",
    "SimpleSmsSend": "simple_sms_messenger",
    "SystemBrightnessMax": "settings",
    "SystemBrightnessMaxVerify": "settings",
    "SystemBrightnessMin": "settings",
    "SystemBrightnessMinVerify": "settings",
    "SystemBluetoothTurnOff": "settings",
    "SystemBluetoothTurnOffVerify": "settings",
    "SystemBluetoothTurnOn": "settings",
    "SystemBluetoothTurnOnVerify": "settings",
    "SystemWifiTurnOff": "settings",
    "SystemWifiTurnOffVerify": "settings",
    "SystemWifiTurnOn": "settings",
    "SystemWifiTurnOnVerify": "settings",
    "TurnOffWifiAndTurnOnBluetooth": "settings",
    "TurnOnWifiAndOpenApp": "settings",
}


def _calendar_expected_event(params: dict[str, Any]) -> dict[str, Any]:
    title = str(params.get("event_title") or params.get("title") or "Project Sync")
    description = str(params.get("event_description") or params.get("description") or "Discuss launch plan.")
    hour = int(params.get("hour") if params.get("hour") is not None else 16)
    duration_mins = int(params.get("duration_mins") if params.get("duration_mins") is not None else 30)
    start_dt = dt.datetime(2023, 10, 16, hour, tzinfo=dt.timezone.utc)
    start_ts = int(params.get("start_ts") or start_dt.timestamp())
    end_ts = int(params.get("end_ts") or (start_ts + duration_mins * 60))
    return {
        "title": title,
        "description": description,
        "hour": hour,
        "duration_mins": duration_mins,
        "start_ts": start_ts,
        "end_ts": end_ts,
    }


def _sql_string(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def _recipe_expected(params: dict[str, Any]) -> dict[str, str]:
    return {
        "title": str(params.get("title") or "Lemon Toast"),
        "description": str(params.get("description") or "A quick citrus breakfast."),
        "servings": str(params.get("servings") or "1 serving"),
        "preparationTime": str(params.get("preparationTime") or params.get("preparation_time") or "10 mins"),
        "source": str(params.get("source") or ""),
        "ingredients": str(params.get("ingredients") or "bread, lemon, butter"),
        "directions": str(params.get("directions") or "Toast bread, add butter, and finish with lemon zest."),
    }


def _markor_folder_name(params: dict[str, Any]) -> str:
    return str(params.get("folder_name") or "folder_oob_demo")


def _markor_note(params: dict[str, Any]) -> dict[str, str]:
    return {
        "file_name": str(params.get("file_name") or "note_oob_demo.md"),
        "text": str(params.get("text") or "OOB note body."),
    }


def _slug(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", value).strip("_") or "case"


def _run(command: list[str], *, cwd: Path = REPO_ROOT, timeout: int = 900) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        command,
        cwd=str(cwd),
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout,
        check=False,
    )
    return result


def _json_from_stdout(stdout: str) -> dict[str, Any]:
    text = stdout.strip()
    start = text.rfind("\n{")
    if start >= 0:
        text = text[start + 1 :]
    return json.loads(text)


def _write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def _runlog_input_text_arguments(run_log_path: Path) -> dict[str, Any]:
    """Build public Function arguments from OOB input_text steps.

    The OOB Function enhancer turns recorded input_text steps into public
    Function parameters when it can infer a stable semantic name from the
    RunLog step title. Only fall back to input_text_N when no semantic name is
    available; the replay test must exercise enhanced Function bindings instead
    of silently reusing recorded default values.
    """
    try:
        payload = json.loads(run_log_path.read_text(encoding="utf-8")).get("payload", {})
    except Exception:
        return {}
    values: list[tuple[str, str]] = []
    for card in payload.get("cards", []):
        if str(card.get("tool_name") or card.get("tool") or "") != "input_text":
            continue
        args = (
            card.get("arguments")
            or (card.get("tool_call") or {}).get("arguments")
            or card.get("args")
            or {}
        )
        if not isinstance(args, dict):
            continue
        text = args.get("text")
        if isinstance(text, str) and not _is_internal_constant_input_text(args):
            values.append((str(card.get("title") or card.get("summary") or ""), text))
    arguments: dict[str, Any] = {}
    for index, (title, value) in enumerate(values):
        semantic_name = _semantic_input_argument_name(title)
        if semantic_name:
            arguments[semantic_name] = value
        else:
            arguments["input_text" if index == 0 else f"input_text_{index + 1}"] = value
    return arguments


def _semantic_input_argument_name(title: str) -> str:
    if "录音" in title and "文件名" in title:
        return "audio_file_name"
    if "联系人" in title and "姓名" in title:
        return "contact_name"
    if "电话" in title or "号码" in title:
        return "contact_phone"
    if "名字" in title:
        return "first_name"
    if "姓氏" in title:
        return "last_name"
    if "note" in title.lower() and "file" in title.lower() and "name" in title.lower():
        return "note_file_name"
    if "note" in title.lower() and ("content" in title.lower() or "text" in title.lower()):
        return "note_content"
    normalized = re.sub(r"[^a-z0-9]+", "_", title.lower()).strip("_")
    normalized = re.sub(r"^(fill|enter|input|type|write|填写)_+", "", normalized)
    normalized = re.sub(r"_+", "_", normalized).strip("_")
    if not normalized:
        return ""
    replacements = {
        "note_file_name": "note_file_name",
        "file_name": "note_file_name",
        "note_name": "note_file_name",
        "note_content": "note_content",
        "note_text": "note_content",
        "content": "note_content",
        "event_title": "event_title",
        "title": "event_title",
        "event_description": "event_description",
        "description": "description",
        "contact_name": "contact_name",
        "phone": "contact_phone",
        "phone_number": "contact_phone",
        "number": "contact_phone",
        "first_name": "first_name",
        "last_name": "last_name",
    }
    return replacements.get(normalized, normalized)


def _is_internal_constant_input_text(args: dict[str, Any]) -> bool:
    text = str(args.get("text") or "").strip()
    target = str(args.get("target_description") or "").lower()
    if not text:
        return True
    if re.fullmatch(r"\d{1,2}", text) and any(marker in target for marker in ("hour", "minute", "time")):
        return True
    return False


def _get_state(port: int) -> dict[str, Any]:
    query = urlencode({
        "includeXml": "true",
        "includeScreenshot": "false",
        "filterOverlay": "true",
        "maxXmlChars": "0",
    })
    with urlopen(f"http://127.0.0.1:{port}/get_state?{query}", timeout=10) as response:
        return json.loads(response.read().decode("utf-8"))


def _expected_timer_text(params: dict[str, Any]) -> str:
    return f"{int(params.get('hours') or 0):02d}h {int(params.get('minutes') or 0):02d}m {int(params.get('seconds') or 0):02d}s"


def _expected_timer_desc(params: dict[str, Any]) -> str:
    hours = int(params.get("hours") or 0)
    minutes = int(params.get("minutes") or 0)
    seconds = int(params.get("seconds") or 0)
    h = "hour" if hours == 1 else "hours"
    m = "minute" if minutes == 1 else "minutes"
    s = "second" if seconds == 1 else "seconds"
    return f"{hours} {h}, {minutes} {m}, {seconds} {s}"


def _adb_shell_output(device: str, *cmd: str) -> str:
    result = subprocess.run(
        ["adb", "-s", device, "shell", *cmd],
        cwd=REPO_ROOT,
        text=True,
        capture_output=True,
        timeout=20,
        check=False,
    )
    return result.stdout.strip()


def _adb_shell_string(device: str, command: str) -> str:
    result = subprocess.run(
        ["adb", "-s", device, "shell", command],
        cwd=REPO_ROOT,
        text=True,
        capture_output=True,
        timeout=20,
        check=False,
    )
    return result.stdout.strip()


def _verify_final_state(task: str, params: dict[str, Any], state: dict[str, Any], *, device: str) -> dict[str, Any]:
    xml = str(state.get("xml") or "")
    text = json.dumps(state, ensure_ascii=False)
    if task.startswith("Browser"):
        package_name = str(state.get("package_name") or "")
        return {
            "success": package_name == "com.android.chrome" and "Success!" in xml,
            "package_name": package_name,
            "has_success_text": "Success!" in xml,
        }
    if task == "AudioRecorderRecordAudio":
        files = _adb_shell_string(
            device,
            "find /storage/emulated/0/Android/data/com.dimowner.audiorecorder/files/Music/records "
            "-maxdepth 1 -type f -size +0c 2>/dev/null | wc -l",
        ).strip()
        return {
            "success": files not in {"", "0"},
            "package_name": state.get("package_name"),
            "recording_file_count": files,
        }
    if task == "AudioRecorderRecordAudioWithFileName":
        file_name = str(params.get("file_name") or "oob_audio_named")
        expected = file_name + ".m4a"
        path = f"/storage/emulated/0/Android/data/com.dimowner.audiorecorder/files/Music/records/{expected}"
        exists = _adb_shell_string(device, f"test -f {shlex.quote(path)} && echo 1 || echo 0").strip()
        return {
            "success": exists == "1",
            "package_name": state.get("package_name"),
            "file_name": file_name,
            "expected_path": path,
            "exists": exists,
        }
    if task == "CameraTakePhoto":
        files = _adb_shell_string(
            device,
            "find /sdcard/Pictures /sdcard/DCIM/Camera -maxdepth 1 -type f 2>/dev/null | wc -l",
        ).strip()
        return {
            "success": files not in {"", "0"},
            "package_name": state.get("package_name"),
            "photo_file_count": files,
        }
    if task == "CameraTakeVideo":
        files = _adb_shell_string(device, "find /sdcard/Movies -maxdepth 1 -type f 2>/dev/null | wc -l").strip()
        return {
            "success": files not in {"", "0"},
            "package_name": state.get("package_name"),
            "video_file_count": files,
        }
    if task == "ClockTimerEntry":
        expected_text = _expected_timer_text(params)
        expected_desc = _expected_timer_desc(params)
        return {
            "success": expected_text in xml and expected_desc in xml and "Pause" not in text,
            "package_name": state.get("package_name"),
            "has_timer_page": "Timer" in text,
            "expected_timer_text": expected_text,
            "has_expected_timer_text": expected_text in xml,
            "expected_timer_content_desc": expected_desc,
            "has_expected_timer_content_desc": expected_desc in xml,
            "has_pause": "Pause" in text,
            "has_start_button": 'content-desc="Start"' in xml or "Start" in text,
        }
    if task == "ClockStopWatchRunning":
        return {
            "success": 'content-desc="Pause"' in xml and 'content-desc="Lap"' in xml,
            "package_name": state.get("package_name"),
            "has_stopwatch": "Stopwatch" in text,
            "has_pause": 'content-desc="Pause"' in xml or "Pause" in text,
            "has_lap": 'content-desc="Lap"' in xml or "Lap" in text,
            "has_start": 'content-desc="Start"' in xml or "Start" in text,
        }
    if task == "ClockStopWatchPausedVerify":
        return {
            "success": ('content-desc="Start"' in xml or "Start" in text) and "Stopwatch" in text,
            "package_name": state.get("package_name"),
            "has_stopwatch": "Stopwatch" in text,
            "has_start": 'content-desc="Start"' in xml or "Start" in text,
            "has_pause": 'content-desc="Pause"' in xml or "Pause" in text,
        }
    if task == "ContactsAddContact":
        name = str(params.get("name") or "").strip()
        number = re.sub(r"\D", "", str(params.get("number") or ""))
        normalized_xml_digits = re.sub(r"\D", "", xml)
        return {
            "success": bool(name) and bool(number) and name in text and number in normalized_xml_digits,
            "package_name": state.get("package_name"),
            "expected_name": name,
            "has_expected_name": bool(name) and name in text,
            "expected_number_digits": number,
            "has_expected_number": bool(number) and number in normalized_xml_digits,
            "has_contacts_package": state.get("package_name") == "com.google.android.contacts",
        }
    if task == "ContactsNewContactDraft":
        first = str(params.get("first") or "").strip()
        last = str(params.get("last") or "").strip()
        phone = re.sub(r"\D", "", str(params.get("phone") or ""))
        phone_label = str(params.get("phone_label") or "").strip()
        normalized_xml_digits = re.sub(r"\D", "", xml)
        non_mobile_phone_label = re.search(
            r"Delete (Home|Work|Work Fax|Home Fax|Pager|Other|Custom|Callback|Car|Company Main|ISDN) Phone",
            text,
        )
        label_ok = phone_label in text or (
            phone_label.lower() == "mobile" and "Mobile Phone" in text
        ) or (phone_label.lower() == "mobile" and non_mobile_phone_label is None)
        return {
            "success": (
                bool(first)
                and bool(last)
                and bool(phone)
                and bool(phone_label)
                and first in text
                and last in text
                and phone in normalized_xml_digits
                and label_ok
                and "Create contact" in text
            ),
            "package_name": state.get("package_name"),
            "expected_first": first,
            "has_expected_first": bool(first) and first in text,
            "expected_last": last,
            "has_expected_last": bool(last) and last in text,
            "expected_phone_digits": phone,
            "has_expected_phone": bool(phone) and phone in normalized_xml_digits,
            "expected_phone_label": phone_label,
            "has_expected_phone_label": bool(phone_label) and label_ok,
            "is_create_contact_screen": "Create contact" in text,
            "has_contacts_package": state.get("package_name") == "com.google.android.contacts",
        }
    if task == "OpenAppTaskEval":
        app_name = str(params.get("app_name") or "settings")
        expected_package = {
            "camera": "com.android.camera2",
            "clock": "com.google.android.deskclock",
            "contacts": "com.google.android.contacts",
            "settings": "com.android.settings",
            "dialer": "com.google.android.dialer",
        }.get(app_name)
        actual_package = str(state.get("package_name") or "")
        return {
            "success": bool(expected_package) and actual_package == expected_package,
            "package_name": actual_package,
            "app_name": app_name,
            "expected_package": expected_package,
            "has_expected_package": bool(expected_package) and actual_package == expected_package,
        }
    if task in {"SystemWifiTurnOff", "SystemWifiTurnOffVerify", "SystemWifiTurnOn", "SystemWifiTurnOnVerify"}:
        expected = "0" if "TurnOff" in task else "1"
        wifi_on = _adb_shell_output(device, "settings", "get", "global", "wifi_on")
        return {
            "success": wifi_on in ([expected, "2"] if expected == "1" else [expected]),
            "package_name": state.get("package_name"),
            "expected_wifi_on": expected,
            "actual_wifi_on": wifi_on,
            "on_or_off": params.get("on_or_off") or ("off" if task == "SystemWifiTurnOff" else "on"),
        }
    if task in {
        "SystemBluetoothTurnOff",
        "SystemBluetoothTurnOffVerify",
        "SystemBluetoothTurnOn",
        "SystemBluetoothTurnOnVerify",
    }:
        expected = "0" if "TurnOff" in task else "1"
        bluetooth_on = _adb_shell_output(device, "settings", "get", "global", "bluetooth_on")
        return {
            "success": bluetooth_on == expected,
            "package_name": state.get("package_name"),
            "expected_bluetooth_on": expected,
            "actual_bluetooth_on": bluetooth_on,
            "on_or_off": params.get("on_or_off") or ("off" if task == "SystemBluetoothTurnOff" else "on"),
        }
    if task in {"SystemBrightnessMax", "SystemBrightnessMaxVerify", "SystemBrightnessMin", "SystemBrightnessMinVerify"}:
        expected = "255" if "Max" in task else "1"
        brightness = _adb_shell_output(device, "settings", "get", "system", "screen_brightness")
        return {
            "success": brightness == expected,
            "package_name": state.get("package_name"),
            "expected_screen_brightness": expected,
            "actual_screen_brightness": brightness,
            "max_or_min": params.get("max_or_min") or ("max" if "Max" in task else "min"),
        }
    if task == "TurnOffWifiAndTurnOnBluetooth":
        wifi_on = _adb_shell_output(device, "settings", "get", "global", "wifi_on")
        bluetooth_on = _adb_shell_output(device, "settings", "get", "global", "bluetooth_on")
        return {
            "success": wifi_on == "0" and bluetooth_on == "1",
            "package_name": state.get("package_name"),
            "expected_wifi_on": "0",
            "actual_wifi_on": wifi_on,
            "expected_bluetooth_on": "1",
            "actual_bluetooth_on": bluetooth_on,
        }
    if task == "TurnOnWifiAndOpenApp":
        app_name = str(params.get("app_name") or "settings")
        expected_package = {
            "camera": "com.android.camera2",
            "clock": "com.google.android.deskclock",
            "contacts": "com.google.android.contacts",
            "settings": "com.android.settings",
            "dialer": "com.google.android.dialer",
        }.get(app_name)
        actual_package = str(state.get("package_name") or "")
        wifi_on = _adb_shell_output(device, "settings", "get", "global", "wifi_on")
        return {
            "success": wifi_on in {"1", "2"} and bool(expected_package) and actual_package == expected_package,
            "package_name": actual_package,
            "app_name": app_name,
            "expected_package": expected_package,
            "has_expected_package": bool(expected_package) and actual_package == expected_package,
            "expected_wifi_on": "1",
            "actual_wifi_on": wifi_on,
        }
    if task in {"SimpleCalendarAddOneEvent", "SimpleCalendarAddOneEventTomorrow"}:
        expected = _calendar_expected_event(params)
        query = (
            "select count(*) from events where "
            f"start_ts={expected['start_ts']} and end_ts={expected['end_ts']} "
            f"and title={_sql_string(expected['title'])} "
            f"and description={_sql_string(expected['description'])};"
        )
        count = _adb_shell_string(
            device,
            "sqlite3 /data/data/com.simplemobiletools.calendar.pro/databases/events.db "
            + shlex.quote(query),
        )
        return {
            "success": count.strip() not in {"", "0"},
            "package_name": state.get("package_name"),
            "expected": expected,
            "matching_event_count": count.strip(),
        }
    if task == "RecipeAddSingleRecipe":
        expected = _recipe_expected(params)
        query = (
            "select count(*) from recipes where "
            f"title={_sql_string(expected['title'])} "
            f"and description={_sql_string(expected['description'])} "
            f"and servings={_sql_string(expected['servings'])} "
            f"and preparationTime={_sql_string(expected['preparationTime'])} "
            f"and source={_sql_string(expected['source'])} "
            f"and ingredients={_sql_string(expected['ingredients'])} "
            f"and directions={_sql_string(expected['directions'])};"
        )
        count = _adb_shell_string(
            device,
            "sqlite3 /data/data/com.flauschcode.broccoli/databases/broccoli "
            + shlex.quote(query),
        )
        return {
            "success": count.strip() not in {"", "0"},
            "package_name": state.get("package_name"),
            "expected": expected,
            "matching_recipe_count": count.strip(),
        }
    if task == "MarkorCreateFolder":
        folder_name = _markor_folder_name(params)
        path = f"/storage/emulated/0/Documents/Markor/{folder_name}"
        exists = _adb_shell_string(
            device,
            f"test -d {shlex.quote(path)} && echo 1 || echo 0",
        )
        return {
            "success": exists.strip() == "1",
            "package_name": state.get("package_name"),
            "folder_name": folder_name,
            "path": path,
            "exists": exists.strip(),
        }
    if task == "MarkorCreateNote":
        note = _markor_note(params)
        path = f"/storage/emulated/0/Documents/Markor/{note['file_name']}"
        _adb_shell_string(device, "am force-stop net.gsantner.markor >/dev/null 2>&1 || true")
        exists = "0"
        content = ""
        deadline = time.time() + 20.0
        while time.time() < deadline:
            exists = _adb_shell_string(
                device,
                f"test -f {shlex.quote(path)} && echo 1 || echo 0",
            ).strip()
            content = _adb_shell_string(
                device,
                f"cat {shlex.quote(path)} 2>/dev/null || true",
            )
            if exists == "1" and note["text"] in content:
                break
            time.sleep(0.5)
        return {
            "success": exists == "1" and note["text"] in content,
            "package_name": state.get("package_name"),
            "file_name": note["file_name"],
            "path": path,
            "exists": exists,
            "expected_text": note["text"],
            "content_preview": content[:500],
            "has_expected_text": note["text"] in content,
        }
    if task == "SimpleSmsSend":
        number = re.sub(r"\D", "", str(params.get("number") or ""))
        message = str(params.get("message") or "").strip()
        rows = _adb_shell_string(device, "content query --uri content://sms/sent")
        normalized_rows = re.sub(r"\D", "", rows)
        actual_package = str(state.get("package_name") or "")
        return {
            "success": bool(number) and bool(message) and number in normalized_rows and message in rows
            and actual_package == "com.simplemobiletools.smsmessenger",
            "package_name": actual_package,
            "expected_number_digits": number,
            "has_expected_number": bool(number) and number in normalized_rows,
            "expected_message": message,
            "has_expected_message": bool(message) and message in rows,
        }
    return {"success": False, "reason": f"unsupported_final_verifier:{task}"}


def _wait_for_final_state(
    task: str,
    params: dict[str, Any],
    *,
    device: str,
    port: int,
    timeout_sec: float = 5.0,
    interval_sec: float = 0.5,
) -> dict[str, Any]:
    deadline = time.time() + timeout_sec
    last: dict[str, Any] = {"success": False, "reason": "not_checked"}
    while time.time() < deadline:
        last = _verify_final_state(task, params, _get_state(port), device=device)
        if last.get("success") is True:
            return last
        time.sleep(interval_sec)
    return last


def _app_name_for_case(task: str, params: dict[str, Any]) -> str:
    if task == "OpenAppTaskEval":
        return str(params.get("app_name") or "system").strip() or "system"
    return TASK_APP_NAMES.get(task, _slug(task).lower())


def _reset_androidworld_task(
    task: str,
    params: dict[str, Any],
    *,
    device: str,
    console_port: int,
    grpc_port: int,
    seed: int | None,
) -> dict[str, Any]:
    params_json = json.dumps(params, ensure_ascii=False)
    if task.startswith("Markor") or task.startswith("Camera") or task.startswith("AudioRecorder"):
        _run([
            "adb",
            "-s",
            device,
            "shell",
            "mkdir -p /storage/emulated/0/Documents/Markor /sdcard/Pictures /sdcard/Movies "
            "/storage/emulated/0/Android/data/com.dimowner.audiorecorder/files/Music/records "
            "&& touch /storage/emulated/0/Documents/Markor/.oob_keep",
        ], timeout=20)
    code = f"""
import json, random, shlex, sys
from pathlib import Path
ANDROID_WORLD_ROOT = Path.home() / 'Projects' / 'android_world'
sys.path.insert(0, str(ANDROID_WORLD_ROOT))
from android_world import registry
from android_world.env import android_world_controller, env_launcher
original_controller = android_world_controller.AndroidWorldController
android_world_controller.AndroidWorldController = lambda env: original_controller(
    env,
    a11y_method=android_world_controller.A11yMethod.NONE,
    install_a11y_forwarding_app=False,
)
env = env_launcher.load_and_setup_env(
    console_port={console_port},
    grpc_port={grpc_port},
    adb_path=str(Path.home() / 'Library' / 'Android' / 'sdk' / 'platform-tools' / 'adb'),
    emulator_setup=False,
    freeze_datetime=True,
)
def patch_clear_directory_empty_glob():
    from android_world.env import adb_utils
    from android_world.utils import file_utils
    if getattr(file_utils.clear_directory, "_oob_empty_glob_safe", False):
        return
    def clear_directory_empty_glob_safe(directory_path, env):
        quoted_path = shlex.quote(directory_path)
        adb_utils.issue_generic_request(
            ["shell", "mkdir -p " + quoted_path + " && rm -rf " + quoted_path + "/*"],
            env,
        )
    clear_directory_empty_glob_safe._oob_empty_glob_safe = True
    file_utils.clear_directory = clear_directory_empty_glob_safe
def hydrate_params(task_name, raw):
    params = dict(raw or {{}})
    if task_name == "MarkorCreateFolder":
        params["folder_name"] = str(params.get("folder_name") or "folder_oob_demo")
    if task_name == "MarkorCreateNote":
        params["file_name"] = str(params.get("file_name") or "note_oob_demo.md")
        params["text"] = str(params.get("text") or "OOB note body.")
    if task_name == "RecipeAddSingleRecipe":
        from android_world.task_evals.common_validators import sqlite_validators
        from android_world.task_evals.utils import sqlite_schema_utils
        title = str(params.get("title") or "Lemon Toast")
        description = str(params.get("description") or "A quick citrus breakfast.")
        servings = str(params.get("servings") or "1 serving")
        preparation_time = str(params.get("preparationTime") or params.get("preparation_time") or "10 mins")
        source = str(params.get("source") or "")
        ingredients = str(params.get("ingredients") or "bread, lemon, butter")
        directions = str(params.get("directions") or "Toast bread, add butter, and finish with lemon zest.")
        recipe = sqlite_schema_utils.Recipe(
            title=title,
            description=description,
            servings=servings,
            preparationTime=preparation_time,
            source=source,
            ingredients=ingredients,
            directions=directions,
        )
        params.update({{
            "title": title,
            "description": description,
            "servings": servings,
            "preparationTime": preparation_time,
            "source": source,
            "ingredients": ingredients,
            "directions": directions,
            sqlite_validators.ROW_OBJECTS: [recipe],
            sqlite_validators.NOISE_ROW_OBJECTS: [],
            "text_representation_type": "text_block",
        }})
    if task_name in {"SimpleCalendarAddOneEvent", "SimpleCalendarAddOneEventTomorrow"}:
        from android_world.task_evals.common_validators import sqlite_validators
        from android_world.task_evals.utils import sqlite_schema_utils
        from android_world.utils import datetime_utils
        title = str(params.get("event_title") or params.get("title") or "Project Sync")
        description = str(params.get("event_description") or params.get("description") or "Discuss launch plan.")
        hour = int(params.get("hour") if params.get("hour") is not None else 16)
        duration_mins = int(params.get("duration_mins") if params.get("duration_mins") is not None else 30)
        start_ts = int(params.get("start_ts") or datetime_utils._create_unix_ts(day=16, hour=hour, month=10, year=2023))
        end_ts = int(params.get("end_ts") or start_ts + duration_mins * 60)
        event = sqlite_schema_utils.CalendarEvent(start_ts=start_ts, end_ts=end_ts, title=title, description=description)
        params.update({{
            "year": 2023,
            "month": 10,
            "day": 16,
            "hour": hour,
            "duration_mins": duration_mins,
            "event_title": title,
            "event_description": description,
            sqlite_validators.ROW_OBJECTS: [event],
            sqlite_validators.NOISE_ROW_OBJECTS: [],
        }})
    return params
try:
    if {task!r}.startswith("Markor") or {task!r}.startswith("Camera") or {task!r}.startswith("AudioRecorder"):
        patch_clear_directory_empty_glob()
    if {seed!r} is not None:
        random.seed({seed!r})
    env.reset(go_home=True)
    task_registry = registry.TaskRegistry()
    aw_registry = task_registry.get_registry(task_registry.ANDROID_WORLD_FAMILY)
    task_type = aw_registry[{task!r}]
    task_type.set_device_time(env)
    task_params = hydrate_params({task!r}, json.loads({params_json!r}))
    task = task_type(task_params)
    task.initialize_task(env)
    print(json.dumps({{'success': True, 'goal': str(task.goal), 'params': json.loads({params_json!r}), 'seed': {seed!r}}}, ensure_ascii=False))
finally:
    env.close()
"""
    result = _run([sys.executable, "-c", code], timeout=240)
    if result.returncode != 0:
        return {"success": False, "stdout": result.stdout, "stderr": result.stderr, "returncode": result.returncode}
    return _json_from_stdout(result.stdout)


def _case_seed(case: dict[str, Any], args: argparse.Namespace, *, offset: int) -> int:
    explicit = case.get("replay_seed") if offset != 0 else case.get("seed")
    if explicit is not None:
        return int(explicit)
    basis = json.dumps(
        {"task": case.get("task"), "params": case.get("params") or {}},
        ensure_ascii=False,
        sort_keys=True,
    )
    digest = hashlib.sha256(basis.encode("utf-8")).hexdigest()
    return int(args.seed_base) + int(digest[:8], 16) + offset


def _prepare_oob(device: str, package_name: str, port: int) -> None:
    _run(["adb", "-s", device, "shell", "settings", "put", "secure", "accessibility_enabled", "1"], timeout=20)
    _run(["adb", "-s", device, "shell", "appops", "set", package_name, "SYSTEM_ALERT_WINDOW", "allow"], timeout=20)
    _run(["adb", "-s", device, "shell", "monkey", "-p", package_name, "-c", "android.intent.category.LAUNCHER", "1"], timeout=30)
    _run(["adb", "-s", device, "forward", f"tcp:{port}", f"tcp:{port}"], timeout=20)


def _prepare_replay_state(task: str, *, device: str) -> dict[str, Any]:
    if task.startswith("Browser"):
        from scripts.oob_collect_androidworld_verified_trajectory import Adb, find_adb, open_browser_task_html

        adb = Adb(find_adb(), device, DEFAULT_PACKAGE)
        adb.shell("settings", "put", "global", "http_proxy", ":0", timeout=10, check=False)
        adb.shell("settings", "delete", "global", "global_http_proxy_host", timeout=10, check=False)
        adb.shell("settings", "delete", "global", "global_http_proxy_port", timeout=10, check=False)
        result = open_browser_task_html(adb)
        result["action"] = "open_browser_task_html"
        return result
    if task not in {"ContactsAddContact", "ContactsNewContactDraft"}:
        return {"success": True, "action": "noop"}
    commands = [
        ["adb", "-s", device, "shell", "am", "force-stop", "com.google.android.contacts"],
        ["adb", "-s", device, "shell", "pm", "clear", "com.google.android.contacts"],
        ["adb", "-s", device, "shell", "pm", "clear", "com.android.providers.contacts"],
    ]
    results = []
    success = True
    for command in commands:
        result = _run(command, timeout=30)
        ok = result.returncode == 0
        success = success and ok
        results.append({
            "command": " ".join(shlex.quote(part) for part in command),
            "success": ok,
            "stdout_tail": result.stdout[-500:],
            "stderr_tail": result.stderr[-500:],
        })
    dismiss_result = _dismiss_optional_button_with_uiautomator(
        device=device,
        package_name="com.google.android.contacts",
        labels={"skip"},
    )
    success = success and dismiss_result.get("success") is not False
    return {
        "success": success,
        "action": "clear_contacts_state",
        "results": results,
        "onboarding_dismiss": dismiss_result,
    }


def _dismiss_optional_button_with_uiautomator(
    *,
    device: str,
    package_name: str,
    labels: set[str],
) -> dict[str, Any]:
    _run(["adb", "-s", device, "shell", "monkey", "-p", package_name, "-c", "android.intent.category.LAUNCHER", "1"], timeout=30)
    time.sleep(1.0)
    dump = _run(
        ["adb", "-s", device, "shell", "uiautomator", "dump", "/sdcard/oob_window.xml", ">/dev/null", "&&", "cat", "/sdcard/oob_window.xml"],
        timeout=20,
    )
    if dump.returncode != 0:
        return {
            "success": True,
            "action": "skip",
            "reason": "uiautomator_dump_failed",
            "stderr_tail": dump.stderr[-500:],
        }
    label_pattern = "|".join(re.escape(label) for label in labels)
    pattern = re.compile(
        rf'(?:text|content-desc)="(?P<label>{label_pattern})"[^>]*bounds="\[(?P<l>\d+),(?P<t>\d+)\]\[(?P<r>\d+),(?P<b>\d+)\]"',
        re.IGNORECASE,
    )
    match = pattern.search(dump.stdout)
    if not match:
        return {"success": True, "action": "noop", "reason": "button_not_present"}
    x = (int(match.group("l")) + int(match.group("r"))) // 2
    y = (int(match.group("t")) + int(match.group("b"))) // 2
    tap = _run(["adb", "-s", device, "shell", "input", "tap", str(x), str(y)], timeout=20)
    time.sleep(0.5)
    _run(["adb", "-s", device, "shell", "am", "force-stop", package_name], timeout=20)
    return {
        "success": tap.returncode == 0,
        "action": "tap_button",
        "label": match.group("label"),
        "x": x,
        "y": y,
        "stderr_tail": tap.stderr[-500:],
    }


def _memory_card(case: dict[str, Any], result: dict[str, Any]) -> str:
    return f"""# {result['task']} {result['case_id']}

Status: Completed engineering memory, not frozen paper evaluation.
Date: {dt.date.today().isoformat()}

## Goal

{result.get('goal', '')}

## Source Artifacts

- RunLog: `{result.get('run_log_path')}`
- Artifacts: `{result.get('artifact_dir')}`
- Function: `{result.get('function_path')}`
- OmniFlow canonical run_log: `{result.get('canonical_run_log_path')}`
- Final state summary: `{result.get('final_state_path')}`

## Action Sequence

{chr(10).join(f'{i + 1}. `{step.get("tool")}`: {step.get("title")}' for i, step in enumerate(result.get('action_sequence', [])))}

## Verification

- Collect seed: `{result.get('collect_seed')}`
- Replay seed: `{result.get('replay_seed')}`
- Seed changed for replay: `{result.get('seed_changed')}`
- AndroidWorld reward: `{result.get('androidworld_reward')}`
- AndroidWorld success: `{result.get('androidworld_success')}`
- OOB Function enhanced: `{result.get('oob_function_enhanced')}`
- OOB replay used enhanced Function: `{result.get('oob_replay_uses_enhanced_function')}`
- OOB replay success: `{result.get('oob_replay_success')}`
- Replay steps: `{result.get('oob_success_step_count')}/{result.get('oob_step_count')}`
- Final state success: `{(result.get('final_state') or {}).get('success')}`
- Hit rate for this enhanced Function replay: `{1 if result.get('oob_replay_uses_enhanced_function') and result.get('oob_replay_success') and (result.get('final_state') or {}).get('success') else 0}/1`

## Notes

The trajectory was derived from the AndroidWorld goal and live OOB observations,
then saved as OOB RunLog and exported to OmniFlow canonical run_log schema for
baseline/reuse harnesses.
"""


def _update_memory_index(memory_root: Path, result: dict[str, Any]) -> None:
    index = memory_root / "INDEX.md"
    if not index.exists():
        return
    goal = re.sub(r"\s+", " ", str(result.get("goal") or "")).replace("|", "/").strip()
    line = (
        f"| {dt.date.today().isoformat()} | `{result['task']}` | "
        f"{goal} | {result.get('step_count')} | "
        f"reward `{result.get('androidworld_reward')}` | "
        f"{'success' if result.get('oob_replay_success') else 'failed'} | "
        f"[{result['case_id']}]({Path(result['memory_path']).name}) |\n"
    )
    text = index.read_text(encoding="utf-8")
    if Path(result["memory_path"]).name in text:
        return
    marker = "## Update Rule\n"
    if marker in text:
        text = text.replace(marker, line + "\n" + marker)
    else:
        text += "\n" + line
    index.write_text(text, encoding="utf-8")


def run_case(case: dict[str, Any], args: argparse.Namespace, batch_dir: Path) -> dict[str, Any]:
    task = str(case["task"])
    params = dict(case.get("params") or {})
    collect_seed = _case_seed(case, args, offset=0)
    replay_seed = _case_seed(case, args, offset=args.replay_seed_offset)
    if replay_seed == collect_seed:
        replay_seed += args.replay_seed_offset or 1
    seed_changed = replay_seed != collect_seed
    case_id = f"{_slug(task)}_{_slug(json.dumps(params, sort_keys=True))}_{int(time.time())}"
    collect_cmd = [
        sys.executable,
        "scripts/oob_collect_androidworld_verified_trajectory.py",
        "--task",
        task,
        "--device",
        args.device,
        "--androidworld-a11y-method",
        "none",
        "--verify-state-source",
        "oob_xml",
        "--image-quality",
        args.image_quality,
        "--output-dir",
        str(REPO_ROOT / "runtime" / "androidworld_verified_trajectories"),
        "--seed",
        str(collect_seed),
    ]
    if params:
        collect_cmd.extend(["--task-params-json", json.dumps(params, ensure_ascii=False, separators=(",", ":"))])
    collect = _run(collect_cmd, timeout=args.case_timeout_sec)
    if collect.stdout.strip():
        collect_result = _json_from_stdout(collect.stdout)
        if collect.returncode != 0:
            collect_result.setdefault("androidworld_success", False)
            collect_result["collect_process_error"] = {
                "returncode": collect.returncode,
                "stderr_tail": collect.stderr[-2000:],
            }
    else:
        collect_result = {
            "androidworld_success": False,
            "stdout": collect.stdout,
            "stderr": collect.stderr,
        }
    run_log_path_raw = str(collect_result.get("output_path") or "").strip()
    run_log_path = Path(run_log_path_raw) if run_log_path_raw else None
    has_run_log = run_log_path is not None and run_log_path.is_file()
    canonical_path = (
        batch_dir / "canonical_run_logs" / f"{run_log_path.stem}.omniflow.run_log.json"
        if has_run_log and run_log_path is not None
        else batch_dir / "canonical_run_logs" / f"{case_id}.omniflow.run_log.json"
    )
    if has_run_log and run_log_path is not None:
        export = _run([
            sys.executable,
            "scripts/oob_export_runlog_for_omniflow.py",
            "--input",
            str(run_log_path),
            "--output",
            str(canonical_path),
        ], timeout=60)
    else:
        export = subprocess.CompletedProcess([], 1, "", "missing run_log")
    warm_memory_results: dict[str, Any] = {}
    if args.build_baseline_warm_memory and canonical_path.exists():
        warm_memory_results = _build_baseline_warm_memory(
            canonical_path=canonical_path,
            case_id=case_id,
            task=task,
            params=params,
            batch_dir=batch_dir,
            timeout_sec=args.warm_memory_timeout_sec,
        )

    reset_result = _reset_androidworld_task(
        task,
        params,
        device=args.device,
        console_port=args.console_port,
        grpc_port=args.grpc_port,
        seed=replay_seed,
    )
    replay_state_prepare = _prepare_replay_state(task, device=args.device)
    _prepare_oob(args.device, args.package, args.port)

    function_path = (
        REPO_ROOT / "runtime" / "functions" / f"{run_log_path.stem}.batch.function.json"
        if has_run_log and run_log_path is not None
        else None
    )
    replay_arguments = _runlog_input_text_arguments(run_log_path) if has_run_log and run_log_path is not None else {}
    if has_run_log and run_log_path is not None and function_path is not None:
        replay_cmd = [
            "scripts/oob-convert-runlog.sh",
            "--device",
            args.device,
            "--run-log-path",
            str(run_log_path),
            "--run",
            "--name",
            f"AndroidWorld {task} {case_id}",
            "--description",
            str(collect_result.get("goal") or ""),
            "--output-path",
            str(function_path),
            "--enhance",
            "--timeout",
            str(args.replay_timeout_sec),
        ]
        if replay_arguments:
            replay_cmd.extend([
                "--arguments-json",
                json.dumps(replay_arguments, ensure_ascii=False, separators=(",", ":")),
            ])
        replay = _run(replay_cmd, timeout=args.replay_timeout_sec + 30)
        replay_result = _json_from_stdout(replay.stdout) if replay.returncode == 0 else {
            "success": False,
            "stdout": replay.stdout,
            "stderr": replay.stderr,
        }
    else:
        replay_result = {
            "success": False,
            "reason": "missing_run_log",
            "collect_stdout": collect.stdout,
            "collect_stderr": collect.stderr,
        }
    replay_result_path = batch_dir / "replay_results" / f"{case_id}.enhanced_replay.json"
    _write_json(replay_result_path, replay_result)
    final_state = _wait_for_final_state(task, params, device=args.device, port=args.port)
    final_state_path = batch_dir / "final_states" / f"{case_id}.final_state.json"
    _write_json(final_state_path, final_state)

    action_sequence = []
    if has_run_log and run_log_path is not None:
        payload = json.loads(run_log_path.read_text(encoding="utf-8")).get("payload", {})
        for card in payload.get("cards", []):
            action_sequence.append({
                "tool": card.get("tool_name"),
                "title": card.get("title"),
                "success": card.get("success"),
            })
    replay_payload = replay_result.get("replay") if isinstance(replay_result.get("replay"), dict) else {}
    enhance_success = (replay_result.get("enhance") or {}).get("success") is True
    replay_uses_enhanced_function = replay_result.get("replay_uses_enhanced_function") is True
    replay_success = (
        enhance_success
        and replay_uses_enhanced_function
        and replay_payload.get("success") is True
    )
    replay_failure_reason = None
    if has_run_log:
        if not enhance_success:
            replay_failure_reason = "oob_function_enhancement_failed"
        elif not replay_uses_enhanced_function:
            replay_failure_reason = "oob_replay_did_not_use_enhanced_function"
        elif replay_payload.get("success") is not True:
            replay_failure_reason = "oob_enhanced_function_replay_failed"
    baseline_required = args.build_baseline_warm_memory
    baseline_success = (
        not baseline_required
        or (
            (warm_memory_results.get("autodroid") or {}).get("success") is True
            and (warm_memory_results.get("mobilegpt") or {}).get("success") is True
        )
    )
    result = {
        "case_id": case_id,
        "task": task,
        "params": params,
        "collect_seed": collect_seed,
        "replay_seed": replay_seed,
        "seed_changed": seed_changed,
        "goal": collect_result.get("goal"),
        "step_count": collect_result.get("step_count"),
        "androidworld_reward": collect_result.get("androidworld_reward"),
        "androidworld_success": collect_result.get("androidworld_success"),
        "action_success": collect_result.get("action_success"),
        "run_log_path": str(run_log_path) if has_run_log and run_log_path is not None else None,
        "collect_error": None if collect.returncode == 0 else {
            "returncode": collect.returncode,
            "stdout_tail": collect.stdout[-2000:],
            "stderr_tail": collect.stderr[-2000:],
        },
        "artifact_dir": collect_result.get("artifact_dir"),
        "canonical_run_log_path": str(canonical_path) if canonical_path.exists() else None,
        "canonical_export_success": export.returncode == 0,
        "baseline_warm_memory": warm_memory_results,
        "baseline_warm_memory_required": baseline_required,
        "baseline_warm_memory_success": baseline_success,
        "reset_success": reset_result.get("success"),
        "reset_seed": reset_result.get("seed"),
        "replay_state_prepare": replay_state_prepare,
        "function_path": str(function_path) if function_path is not None and function_path.exists() else None,
        "oob_replay_result_path": str(replay_result_path),
        "oob_function_enhanced": enhance_success,
        "oob_function_enhance_cost": replay_result.get("enhance_cost"),
        "oob_replay_uses_enhanced_function": replay_uses_enhanced_function,
        "oob_function_spec_hash": replay_result.get("function_spec_hash"),
        "oob_enhanced_function_spec_hash": replay_result.get("enhanced_function_spec_hash"),
        "oob_function_spec_before_replay_hash": replay_result.get("function_spec_before_replay_hash"),
        "oob_replay_arguments": replay_result.get("replay_arguments") or replay_arguments,
        "oob_replay_skipped_reason": replay_result.get("replay_skipped_reason"),
        "oob_replay_success": replay_success,
        "oob_replay_failure_reason": replay_failure_reason,
        "oob_step_count": replay_payload.get("step_count"),
        "oob_success_step_count": replay_payload.get("success_step_count"),
        "final_state": final_state,
        "final_state_path": str(final_state_path),
        "action_sequence": action_sequence,
        "stdout_paths": {},
    }
    result["memory_path"] = None
    completed = (
        result["androidworld_success"] is True
        and result["oob_function_enhanced"] is True
        and result["oob_replay_uses_enhanced_function"] is True
        and result["oob_replay_success"] is True
        and (result.get("final_state") or {}).get("success") is True
        and result["baseline_warm_memory_success"] is True
        and result["seed_changed"] is True
    )
    result["completed"] = completed
    if completed:
        memory_root = PAPER_EXPORT_ROOT / "long_term_memory" / "androidworld_trajectories"
        memory_path = memory_root / f"{case_id}.md"
        memory_path.parent.mkdir(parents=True, exist_ok=True)
        memory_path.write_text(_memory_card(case, result), encoding="utf-8")
        result["memory_path"] = str(memory_path)
        _update_memory_index(memory_root, result)
    return result


def _build_baseline_warm_memory(
    *,
    canonical_path: Path,
    case_id: str,
    task: str,
    params: dict[str, Any],
    batch_dir: Path,
    timeout_sec: int,
) -> dict[str, Any]:
    app_name = _app_name_for_case(task, params)
    output_root = batch_dir / "baseline_warm_memory" / case_id
    results: dict[str, Any] = {}
    for backend in ("autodroid", "mobilegpt"):
        output_dir = output_root / backend
        command = [
            str(OMNIFLOW_ROOT / ".venv/bin/python"),
            "scripts/native_warm_memory_runner.py",
            "--backend",
            backend,
            "--mode",
            "build",
            "--run-log-json",
            str(canonical_path),
            "--app-name",
            app_name,
            "--embedding-provider",
            "hash",
            "--output-dir",
            str(output_dir),
        ]
        if backend == "autodroid":
            command.append("--fake-instructor")
        result = _run(command, cwd=OMNIFLOW_ROOT, timeout=timeout_sec)
        summary_path = output_dir / "summary.json"
        summary: dict[str, Any] = {}
        if summary_path.exists():
            try:
                summary = json.loads(summary_path.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                summary = {"summary_parse_error": True}
        results[backend] = {
            "success": result.returncode == 0 and summary_path.exists(),
            "returncode": result.returncode,
            "output_dir": str(output_dir),
            "summary_path": str(summary_path) if summary_path.exists() else None,
            "memory_dir": ((summary.get("memory") or {}).get("memory_dir") if isinstance(summary.get("memory"), dict) else None),
            "conversion_summary": ((summary.get("memory") or {}).get("conversion_summary") if isinstance(summary.get("memory"), dict) else None),
            "stderr_tail": result.stderr[-2000:],
        }
    return results


def parse_cases(raw: str | None) -> list[dict[str, Any]]:
    if not raw:
        return list(DEFAULT_CASES)
    stripped = raw.strip()
    if stripped.startswith("[") or stripped.startswith("{"):
        data = json.loads(raw)
    else:
        path = Path(raw).expanduser()
        if path.exists():
            data = json.loads(path.read_text(encoding="utf-8"))
        else:
            data = json.loads(raw)
    if not isinstance(data, list):
        raise SystemExit("cases must be a JSON list")
    return [dict(item) for item in data]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases-json", help="JSON list or path. Defaults to built-in clock smoke suite.")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--device", default="emulator-5556")
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--port", type=int, default=8910)
    parser.add_argument("--console-port", type=int, default=5556)
    parser.add_argument("--grpc-port", type=int, default=8556)
    parser.add_argument("--image-quality", default="low", choices=["low", "medium", "high"])
    parser.add_argument("--case-timeout-sec", type=int, default=360)
    parser.add_argument("--replay-timeout-sec", type=int, default=160)
    parser.add_argument("--seed-base", type=int, default=1729)
    parser.add_argument("--replay-seed-offset", type=int, default=1000003)
    parser.add_argument("--build-baseline-warm-memory", action="store_true")
    parser.add_argument("--warm-memory-timeout-sec", type=int, default=300)
    parser.add_argument("--output-dir", type=Path, default=REPO_ROOT / "runtime" / "androidworld_goal_reuse_batch")
    args = parser.parse_args()
    cases = parse_cases(args.cases_json)
    if args.limit > 0:
        cases = cases[: args.limit]
    batch_id = dt.datetime.now().strftime("%Y%m%dT%H%M%S")
    batch_dir = args.output_dir.expanduser().resolve() / batch_id
    results = []
    for case in cases:
        result = run_case(case, args, batch_dir)
        results.append(result)
        print(json.dumps({
            "case_id": result["case_id"],
            "task": result["task"],
            "collect_seed": result["collect_seed"],
            "replay_seed": result["replay_seed"],
            "seed_changed": result["seed_changed"],
            "androidworld_success": result["androidworld_success"],
            "oob_function_enhanced": result["oob_function_enhanced"],
            "oob_replay_uses_enhanced_function": result["oob_replay_uses_enhanced_function"],
            "oob_replay_success": result["oob_replay_success"],
            "final_state_success": (result.get("final_state") or {}).get("success"),
            "completed": result.get("completed"),
        }, ensure_ascii=False))
    success_count = sum(1 for item in results if item.get("completed") is True)
    summary = {
        "schema_version": "oob_androidworld_goal_reuse_batch.v1",
        "batch_id": batch_id,
        "case_count": len(results),
        "success_count": success_count,
        "hit_rate": success_count / len(results) if results else 0.0,
        "results": results,
    }
    _write_json(batch_dir / "summary.json", summary)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if success_count == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
