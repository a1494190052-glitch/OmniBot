from __future__ import annotations

from pathlib import Path
import unittest


INSTALL_SCRIPT = Path(__file__).resolve().parents[1] / "install-dev.sh"


class InstallDevProxyContractTest(unittest.TestCase):
    def test_proxy_is_supervised_and_health_checks_ignore_host_proxy(self) -> None:
        source = INSTALL_SCRIPT.read_text(encoding="utf-8")
        function = source.split("start_adb_provider_proxy() {", 1)[1].split(
            "\n}\n\nAPI_KEY_RESOLVED_ENV", 1
        )[0]

        self.assertIn('launchctl submit -l "$service_label"', function)
        self.assertGreaterEqual(function.count("--noproxy '*' --fail"), 3)
        self.assertIn("curl -sSf --max-time 5", function)


if __name__ == "__main__":
    unittest.main()
