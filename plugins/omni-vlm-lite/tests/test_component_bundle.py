from __future__ import annotations

import json
from pathlib import Path
import unittest


COMPONENT_ROOT = Path(__file__).resolve().parents[1]


class OmniFlowComponentBundleTest(unittest.TestCase):
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

if __name__ == "__main__":
    unittest.main()
