from __future__ import annotations

import hashlib
import json
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest
from zipfile import ZipFile


COMPONENT_ROOT = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT = COMPONENT_ROOT.parents[1]
BUILD_SCRIPT = REPOSITORY_ROOT / "scripts/build-omniflow-component.py"
LOCAL_BUILD_SCRIPT = REPOSITORY_ROOT / "scripts/build-local-omniflow.sh"


class OmniFlowComponentBundleTest(unittest.TestCase):
    def test_local_one_click_build_uses_three_repositories_and_latest_outputs(self) -> None:
        script = LOCAL_BUILD_SCRIPT.read_text(encoding="utf-8")

        self.assertIn('omniflow_root="$repo_root/../OmniFlow-exp"', script)
        self.assertIn('omnitransfer_root="$repo_root/../OmniTransfer"', script)
        self.assertIn('"$script_dir/build-foolproof-apk.sh"', script)
        self.assertIn("latest/omniflow-component.zip", script.replace('$latest_dir/', 'latest/'))
        self.assertIn("OpenOmniBot-foolproof-debug.apk", script)
        self.assertIn("SHA256SUMS", script)

    def test_component_contract_matches_current_runtime_layout(self) -> None:
        component = json.loads(
            (COMPONENT_ROOT / "component.json").read_text(encoding="utf-8")
        )
        runtime = component["runtimeSkill"]

        self.assertEqual(component["schemaVersion"], 1)
        self.assertEqual(component["id"], "com.omnimind.omni-vlm-lite")
        self.assertEqual(component["kind"], "runtime_component")
        self.assertEqual(component["androidAdapter"], "omniflow_android_gui")
        self.assertTrue((COMPONENT_ROOT / component["documentation"]).is_file())
        self.assertTrue((COMPONENT_ROOT / component["installLayout"]).is_file())
        self.assertTrue((COMPONENT_ROOT / component["agentSkill"]["path"]).is_dir())
        self.assertEqual(runtime["id"], "omniflow-gui-runtime")
        self.assertTrue((COMPONENT_ROOT / runtime["path"]).is_dir())
        self.assertTrue((COMPONENT_ROOT / component["schemas"]["path"]).is_dir())

    def test_standalone_component_contains_verified_runtime_and_schemas(self) -> None:
        with TemporaryDirectory() as temporary:
            output = Path(temporary) / "omniflow-component.zip"
            subprocess.run(
                [sys.executable, str(BUILD_SCRIPT), "--output", str(output)],
                check=True,
                capture_output=True,
                text=True,
            )
            with ZipFile(output) as archive:
                names = set(archive.namelist())
                component = json.loads(archive.read("component.json"))
                release = json.loads(archive.read("release.json"))
                runtime_manifest_path = (
                    "runtime-skill/omniflow-gui-runtime/"
                    "scripts/runtime.prebuilt.manifest.json"
                )
                runtime_archive_path = (
                    "runtime-skill/omniflow-gui-runtime/"
                    "scripts/runtime.prebuilt.zip"
                )
                runtime_release = json.loads(archive.read(runtime_manifest_path))
                runtime_archive = archive.read(runtime_archive_path)
                readme = archive.read("README.md").decode("utf-8")
                install_layout = json.loads(archive.read("INSTALL_DIR.json"))

            self.assertEqual(release["schema_version"], "oob.component-release.v1")
            self.assertEqual(release["component_id"], component["id"])
            self.assertEqual(
                release["component_version"],
                runtime_release["runtime_version"],
            )
            self.assertEqual(
                hashlib.sha256(runtime_archive).hexdigest(),
                runtime_release["archive_sha256"],
            )
            self.assertIn(
                "schemas/oob/oob_canonical_actions.v1.json",
                names,
            )
            self.assertIn(
                "agent-skill/omniflow-runtime-modifier/SKILL.md",
                names,
            )
            self.assertEqual(
                install_layout["shell_runtime_skill_root"],
                "/workspace/.omnibot/skills/omniflow-gui-runtime",
            )
            self.assertEqual(
                install_layout["shell_developer_override_root"],
                "/workspace/.omnibot/omniflow-developer/python",
            )
            for tool_name in (
                "get_omniflow_python_override",
                "apply_omniflow_python_override",
                "clear_omniflow_python_override",
                "reload_omniflow_python_override",
            ):
                self.assertIn(tool_name, readme)
            self.assertEqual(release["agent_skill_id"], "omniflow-runtime-modifier")
            self.assertFalse(any("__pycache__" in name for name in names))
            self.assertFalse(any(name.endswith((".pyc", ".pyo")) for name in names))


if __name__ == "__main__":
    unittest.main()
