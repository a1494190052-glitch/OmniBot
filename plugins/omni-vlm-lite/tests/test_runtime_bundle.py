from __future__ import annotations

import importlib.util
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest


BUNDLE_ROOT = Path(__file__).resolve().parents[1] / "runtime-skill/omniflow-gui-runtime"
BOOTSTRAP_PATH = BUNDLE_ROOT / "scripts/bootstrap_runtime.py"


def load_bootstrap():
    spec = importlib.util.spec_from_file_location("omniflow_runtime_bootstrap", BOOTSTRAP_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError("bootstrap module is unavailable")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class RuntimeBundleTest(unittest.TestCase):
    def test_download_cache_is_outside_versioned_skill_directory(self) -> None:
        bootstrap = load_bootstrap()
        skill_root = Path("/workspace/.omnibot/skills/omniflow-gui-runtime")
        self.assertEqual(
            bootstrap.download_cache_root(skill_root),
            Path("/workspace/.omnibot/runtime-cache/omniflow-gui-runtime"),
        )

    def test_download_many_reuses_verified_artifacts(self) -> None:
        bootstrap = load_bootstrap()
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            source_a = root / "source-a.bin"
            source_b = root / "source-b.bin"
            source_a.write_bytes(b"runtime-a")
            source_b.write_bytes(b"runtime-b")

            def digest(path: Path) -> str:
                return hashlib.sha256(path.read_bytes()).hexdigest()

            target_a = root / "cache-a.bin"
            target_b = root / "cache-b.bin"
            jobs = (
                ("a", source_a.as_uri(), digest(source_a), target_a, ()),
                ("b", source_b.as_uri(), digest(source_b), target_b, ()),
            )
            bootstrap.download_many(jobs)
            bootstrap.download_many(jobs)

            self.assertEqual(target_a.read_bytes(), source_a.read_bytes())
            self.assertEqual(target_b.read_bytes(), source_b.read_bytes())

    def test_staged_runtime_satisfies_compiler_import(self) -> None:
        bootstrap = load_bootstrap()
        values = bootstrap.read_properties(BUNDLE_ROOT / "scripts/runtime/runtime.properties")
        with TemporaryDirectory() as temporary:
            python_root = Path(temporary) / "python"
            compiler = python_root / "omniflow/functions/compiler.py"
            compiler.parent.mkdir(parents=True)
            compiler.write_text(
                "from src.integrations.runlog import project_androidworld_step_actions\n",
                encoding="utf-8",
            )
            (python_root / "omniflow/__init__.py").write_text("", encoding="utf-8")
            core = python_root / "omniflow/core"
            core.mkdir()
            (core / "__init__.py").write_text("", encoding="utf-8")
            (core / "schemas.py").write_text(
                "def canonicalize_action(value, **_kwargs): return value\n",
                encoding="utf-8",
            )
            (core / "trajectory.py").write_text(
                "def observation_display(value): return tuple(value['display'])\n",
                encoding="utf-8",
            )

            bootstrap.stage_compat_sources(BUNDLE_ROOT, python_root, values)
            script = """
from omniflow.functions.compiler import project_androidworld_step_actions

actions = project_androidworld_step_actions({
    "observation": {"display": [100, 50]},
    "action": {"action_type": "click", "x": 50, "y": 25},
})
assert actions == [{"tool": "click", "args": {"x": 500.0, "y": 500.0}}], actions
"""
            environment = os.environ.copy()
            environment["PYTHONPATH"] = str(python_root)
            result = subprocess.run(
                [sys.executable, "-c", script],
                capture_output=True,
                check=False,
                env=environment,
                text=True,
            )
            self.assertEqual(
                result.returncode,
                0,
                json.dumps({"stdout": result.stdout, "stderr": result.stderr}),
            )


if __name__ == "__main__":
    unittest.main()
