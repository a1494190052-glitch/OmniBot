from __future__ import annotations

import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
from pathlib import Path
import threading
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "debug-provider-proxy.py"


def load_proxy_module():
    spec = importlib.util.spec_from_file_location("debug_provider_proxy", SCRIPT)
    if spec is None or spec.loader is None:
        raise RuntimeError("could not load debug provider proxy")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class RecordingUpstream(BaseHTTPRequestHandler):
    request_path = ""
    request_body = b""
    authorization = ""

    def do_POST(self) -> None:
        type(self).request_path = self.path
        type(self).authorization = self.headers.get("Authorization", "")
        type(self).request_body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        payload = b'{"ok":true}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, format: str, *args: object) -> None:
        return


class DebugProviderProxyTest(unittest.TestCase):
    def test_forwards_path_body_and_authorization(self) -> None:
        proxy_module = load_proxy_module()
        upstream = ThreadingHTTPServer(("127.0.0.1", 0), RecordingUpstream)
        upstream_thread = threading.Thread(target=upstream.serve_forever, daemon=True)
        upstream_thread.start()
        proxy = proxy_module.create_server(
            host="127.0.0.1",
            port=0,
            upstream=f"http://127.0.0.1:{upstream.server_port}/gateway",
        )
        proxy_thread = threading.Thread(target=proxy.serve_forever, daemon=True)
        proxy_thread.start()
        try:
            connection = http.client.HTTPConnection("127.0.0.1", proxy.server_port, timeout=3)
            connection.request(
                "POST",
                "/v1/chat/completions?stream=true",
                body=b'{"model":"GLM-5.1"}',
                headers={
                    "Authorization": "Bearer secret-value",
                    "Content-Type": "application/json",
                },
            )
            response = connection.getresponse()

            self.assertEqual(200, response.status)
            self.assertEqual(b'{"ok":true}', response.read())
            self.assertEqual(
                "/gateway/v1/chat/completions?stream=true",
                RecordingUpstream.request_path,
            )
            self.assertEqual(b'{"model":"GLM-5.1"}', RecordingUpstream.request_body)
            self.assertEqual("Bearer secret-value", RecordingUpstream.authorization)
        finally:
            proxy.shutdown()
            proxy.server_close()
            upstream.shutdown()
            upstream.server_close()


if __name__ == "__main__":
    unittest.main()
