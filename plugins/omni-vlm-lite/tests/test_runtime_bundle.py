from __future__ import annotations

import importlib.util
import ast
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest
from zipfile import ZipFile


BUNDLE_ROOT = Path(__file__).resolve().parents[1] / "runtime-skill/omniflow-gui-runtime"
BOOTSTRAP_PATH = BUNDLE_ROOT / "scripts/bootstrap_runtime.py"
PREBUILT_RUNTIME_PATH = BUNDLE_ROOT / "scripts/runtime.prebuilt.zip"
CATALOG_PATH = BUNDLE_ROOT.parents[2] / "catalog.v1.json"
REPOSITORY_ROOT = BUNDLE_ROOT.parents[3]
OMNIFLOW_ROOT = REPOSITORY_ROOT.parent / "OmniFlow-exp"
OMNITRANSFER_ROOT = REPOSITORY_ROOT.parent / "OmniTransfer"


def load_bootstrap():
    spec = importlib.util.spec_from_file_location("omniflow_runtime_bootstrap", BOOTSTRAP_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError("bootstrap module is unavailable")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def committed_file(repository: Path, relative: str, *, revision: str = "HEAD") -> str:
    return subprocess.check_output(
        ("git", "-C", str(repository), "show", f"{revision}:{relative}"),
        text=True,
    )


class RuntimeBundleTest(unittest.TestCase):
    def test_prebuilt_runtime_matches_catalog_digest_and_manifest(self) -> None:
        bootstrap = load_bootstrap()
        catalog = json.loads(CATALOG_PATH.read_text(encoding="utf-8"))
        runtime_skill = catalog["plugins"][0]["runtimeSkill"]
        self.assertEqual(
            hashlib.sha256(PREBUILT_RUNTIME_PATH.read_bytes()).hexdigest(),
            runtime_skill["prebuiltRuntimeSha256"],
        )

        values = bootstrap.read_properties(BUNDLE_ROOT / "scripts/runtime/runtime.properties")
        marker = (BUNDLE_ROOT / "PACKAGED_RUNTIME_SKILL").read_text(encoding="utf-8").strip()
        self.assertEqual(marker, values["runtime.version"])
        with TemporaryDirectory() as temporary:
            skill_root = Path(temporary) / "omniflow-gui-runtime"
            runtime_root = skill_root / "scripts/runtime"
            runtime_root.mkdir(parents=True)
            shutil.copyfile(
                BUNDLE_ROOT / "scripts/runtime/runtime.properties",
                runtime_root / "runtime.properties",
            )
            with ZipFile(PREBUILT_RUNTIME_PATH) as archive:
                archive.extractall(runtime_root)

            fingerprint = bootstrap.sha256_file(runtime_root / "runtime.properties")
            self.assertTrue(bootstrap.runtime_ready(skill_root, values, fingerprint))

    def test_prebuilt_runtime_contains_pinned_real_omnitransfer(self) -> None:
        values = load_bootstrap().read_properties(
            BUNDLE_ROOT / "scripts/runtime/runtime.properties"
        )
        with ZipFile(PREBUILT_RUNTIME_PATH) as archive:
            installed = json.loads(
                archive.read(".runtime/installed.json").decode("utf-8")
            )
            names = set(archive.namelist())
            runtime_source = archive.read(
                ".runtime/omnitransfer/src/omnitransfer/runtime.py"
            ).decode("utf-8")

        self.assertEqual(installed["omnitransfer_commit"], values["omnitransfer.commit"])
        self.assertIn(
            ".runtime/omnitransfer/src/omnitransfer/runtime.py",
            names,
        )
        canonical_runtime = committed_file(
            OMNITRANSFER_ROOT,
            "src/omnitransfer/runtime.py",
            revision=values["omnitransfer.commit"],
        )
        self.assertEqual(canonical_runtime, runtime_source)
        self.assertIn("def rank_action_candidates(", runtime_source)
        self.assertIn("min_probability=0.0", runtime_source)
        self.assertIn("min_margin=0.0", runtime_source)
        self.assertIn("return rank_action_candidates(**kwargs)", runtime_source)
        self.assertNotIn('"mapped": True', runtime_source)
        self.assertNotIn("_coordinate_stretch_result", runtime_source)
        self.assertNotIn("coordinate_stretch_fallback", runtime_source)

        with ZipFile(PREBUILT_RUNTIME_PATH) as archive:
            execution_source = archive.read(
                "python/omniflow/runtime/execution.py"
            ).decode("utf-8")
        self.assertIn("_ALIGNMENT_MIN_PROBABILITY = 0.0", execution_source)
        self.assertIn("_OPEN_APP_READY_MAX_ATTEMPTS = 30", execution_source)
        self.assertIn("_OBSERVATION_READY_MAX_ATTEMPTS = 20", execution_source)
        self.assertIn("def _observation_window_outside_display(", execution_source)
        self.assertIn(
            ".runtime/omnitransfer/src/omnitransfer/"
            + values["omnitransfer.checkpoint"],
            names,
        )
        self.assertTrue(
            values["omnitransfer.checkpoint"].endswith(".npz"),
            "the embedded Android runtime has NumPy but not PyTorch",
        )
        self.assertIn("NumpyMutualGraphMatcher", runtime_source)

    def test_runtime_manifest_schema_digests_match_packaged_schemas(self) -> None:
        bootstrap = load_bootstrap()
        values = bootstrap.read_properties(
            BUNDLE_ROOT / "scripts/runtime/runtime.properties"
        )
        schema_root = BUNDLE_ROOT.parents[1] / "schemas"
        for key, expected in values.items():
            if not key.startswith("schema.") or not key.endswith(".sha256"):
                continue
            filename = key.removeprefix("schema.").removesuffix(".sha256")
            schema = schema_root / "oob" / filename
            self.assertTrue(schema.is_file(), schema)
            self.assertEqual(expected, hashlib.sha256(schema.read_bytes()).hexdigest())

    def test_prebuilt_runtime_keeps_canonical_behavior_sources_unchanged(self) -> None:
        with ZipFile(PREBUILT_RUNTIME_PATH) as archive:
            compiler = archive.read("python/omniflow/functions/compiler.py").decode("utf-8")
            execution = archive.read("python/omniflow/runtime/execution.py").decode("utf-8")
            core = archive.read("python/omniflow/runtime/core.py").decode("utf-8")

        self.assertEqual(
            (OMNIFLOW_ROOT / "omniflow/functions/compiler.py").read_text(
                encoding="utf-8"
            ),
            compiler,
        )
        self.assertEqual(
            (OMNIFLOW_ROOT / "omniflow/runtime/execution.py").read_text(
                encoding="utf-8"
            ),
            execution,
        )
        self.assertEqual(
            (OMNIFLOW_ROOT / "omniflow/runtime/core.py").read_text(
                encoding="utf-8"
            ),
            core,
        )

    def test_prebuilt_runtime_seeds_parameterized_beverage_functions_and_checkers(self) -> None:
        with ZipFile(PREBUILT_RUNTIME_PATH) as archive:
            store = json.loads(
                archive.read("python/omniflow/builtin/function_store.json")
            )
            states = json.loads(
                archive.read("python/omniflow/builtin/states.json")
            )
            function_store_source = archive.read(
                "python/omniflow/functions/store.py"
            ).decode("utf-8")
            execution = archive.read(
                "python/omniflow/runtime/execution.py"
            ).decode("utf-8")
            bridge_source = archive.read("python/omniflow/bridge.py").decode("utf-8")

        functions = store["functions"]
        self.assertEqual(
            {
                "manual_americano_checkout_20260806",
                "order_beverage_meituan",
            },
            set(functions),
        )
        generic = functions["order_beverage_meituan"]
        self.assertEqual(["beverage"], generic["input_schema"]["required"])
        self.assertEqual("$.arguments.beverage", generic["bindings"][0]["source"])
        self.assertGreaterEqual(len(generic["checker_rules"]), 10)
        self.assertEqual(
            [
                "click",
                "input_text",
                "press_key",
                "click",
                "click",
                "input_text",
                "press_key",
                "click",
            ],
            [step["action"]["tool"] for step in generic["steps"]],
        )
        self.assertEqual("拿铁", generic["steps"][1]["action"]["args"]["text"])
        self.assertEqual("拿铁", generic["steps"][5]["action"]["args"]["text"])
        self.assertEqual(
            [
                "$.steps[1].action.args.text",
                "$.steps[5].action.args.text",
            ],
            [binding["target"] for binding in generic["bindings"]],
        )
        self.assertEqual(
            "$.arguments.beverage",
            generic["bindings"][1]["source"],
        )
        self.assertEqual(
            "$.steps[1].action.args.text",
            generic["bindings"][0]["target"],
        )
        manual = functions["manual_americano_checkout_20260806"]
        self.assertEqual(14, len(manual["steps"]))
        self.assertEqual([], manual["bindings"])
        self.assertEqual([], manual["checker_rules"])
        self.assertIn("不提交、不支付", manual["description"])
        source_state_ids = {
            step["source_state_id"]
            for function in functions.values()
            for step in function["steps"]
        }
        self.assertTrue(source_state_ids <= set(states))
        self.assertIn(
            "self._seed(seed_functions, replace=replace_seeded)",
            function_store_source,
        )
        self.assertIn("--catalog", bridge_source)
        self.assertIn("load_default_catalog()", bridge_source)
        self.assertIn("payment_confirmation_blocked", execution)

        execution_syntax = ast.parse(execution)
        payment_helper = next(
            node
            for node in execution_syntax.body
            if isinstance(node, ast.FunctionDef)
            and node.name == "_payment_confirmation_visible"
        )
        payment_namespace = {"Observation": object, "Action": object}
        exec(
            compile(
                ast.Module(body=[payment_helper], type_ignores=[]),
                "execution.py",
                "exec",
            ),
            payment_namespace,
        )
        payment_guard = payment_namespace["_payment_confirmation_visible"]
        observation = type("Observation", (), {"xml": "<node text='立即支付'/>"})()
        click = type("Action", (), {"tool": "click"})()
        back = type("Action", (), {"tool": "press_key"})()
        self.assertTrue(payment_guard(observation, click))
        self.assertFalse(payment_guard(observation, back))

        with TemporaryDirectory() as temporary:
            runtime_root = Path(temporary)
            with ZipFile(PREBUILT_RUNTIME_PATH) as archive:
                archive.extractall(runtime_root)
            environment = os.environ.copy()
            environment["PYTHONPATH"] = os.pathsep.join(
                (
                    str(runtime_root / "python"),
                    str(runtime_root / ".runtime/omnitransfer/src"),
                )
            )
            validation = subprocess.run(
                [
                    sys.executable,
                    "-c",
                    "from omniflow.catalog import load_default_catalog; "
                    "from omniflow.functions.store import FunctionStore; "
                    "catalog=load_default_catalog(); "
                    "store=FunctionStore(r'%s', "
                    "seed_functions=catalog.functions.values()); "
                    "assert [item.function_id for item in store.list_functions()] "
                    "== ['manual_americano_checkout_20260806', "
                    "'order_beverage_meituan']"
                    % (runtime_root / "user-store.json"),
                ],
                capture_output=True,
                check=False,
                env=environment,
                text=True,
            )
            self.assertEqual(
                0,
                validation.returncode,
                json.dumps(
                    {"stdout": validation.stdout, "stderr": validation.stderr},
                    ensure_ascii=False,
                ),
            )

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
