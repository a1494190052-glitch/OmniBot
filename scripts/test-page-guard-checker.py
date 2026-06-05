#!/usr/bin/env python3
"""Run the real PageGuard checker against a supplied accessibility XML file."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_RESULT = REPO_ROOT / "build" / "page-guard-checker" / "result.json"
TEST_FILTER = "cn.com.omnimind.bot.runlog.PageGuardCheckerScriptTest"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Dry-run the real Kotlin PageGuard checker with fake backend XML. "
            "When --xml is omitted, a built-in splash-ad fixture is used."
        )
    )
    parser.add_argument("--xml", type=Path, help="Accessibility XML file to test.")
    parser.add_argument("--package", default="com.example", help="Current package name.")
    parser.add_argument("--activity", default="ExampleActivity", help="Current activity name.")
    parser.add_argument(
        "--expect",
        choices=("match", "no-match", "any"),
        default="match",
        help="Assertion made by the bridge test.",
    )
    parser.add_argument(
        "--execute",
        action="store_true",
        help="Execute the fake click in the test backend instead of dry-run only.",
    )
    parser.add_argument(
        "--result",
        type=Path,
        default=DEFAULT_RESULT,
        help="Where the bridge test writes the checker result JSON.",
    )
    parser.add_argument(
        "--gradle-task",
        default=":app:testDevelopStandardDebugUnitTest",
        help="Gradle unit-test task to run.",
    )
    parser.add_argument(
        "--verbose-gradle",
        action="store_true",
        help="Stream Gradle output instead of printing it only on failure.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    env = os.environ.copy()
    result_file = args.result.resolve()
    result_file.parent.mkdir(parents=True, exist_ok=True)
    if result_file.exists():
        result_file.unlink()

    if args.xml is not None:
        xml_file = args.xml.resolve()
        if not xml_file.exists():
            print(f"XML file not found: {xml_file}", file=sys.stderr)
            return 2
        env["PAGE_GUARD_CHECKER_XML_FILE"] = str(xml_file)

    env["PAGE_GUARD_CHECKER_PACKAGE"] = args.package
    env["PAGE_GUARD_CHECKER_ACTIVITY"] = args.activity
    env["PAGE_GUARD_CHECKER_EXPECT"] = args.expect
    env["PAGE_GUARD_CHECKER_RESULT_FILE"] = str(result_file)
    env["PAGE_GUARD_CHECKER_EXECUTE"] = "true" if args.execute else "false"

    cmd = [
        str(REPO_ROOT / "gradlew"),
        "--no-daemon",
        args.gradle_task,
        "--tests",
        TEST_FILTER,
    ]
    if args.verbose_gradle:
        completed = subprocess.run(cmd, cwd=REPO_ROOT, env=env)
    else:
        completed = subprocess.run(
            cmd,
            cwd=REPO_ROOT,
            env=env,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        if completed.returncode != 0:
            print(completed.stdout, file=sys.stderr)

    if result_file.exists():
        result = json.loads(result_file.read_text(encoding="utf-8"))
        print(json.dumps(result, ensure_ascii=False, indent=2))
        print(f"result_file={result_file}")
    else:
        print(f"Checker result file was not written: {result_file}", file=sys.stderr)

    return completed.returncode


if __name__ == "__main__":
    raise SystemExit(main())
