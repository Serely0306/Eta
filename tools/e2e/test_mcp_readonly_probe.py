"""Exercise the probe against an isolated local HTTP fixture, never a device."""

import http.server
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest


CLIENT = Path(__file__).with_name("mcp_readonly_probe.py")
TEST_OUTPUT_ROOT = CLIENT.parents[2] / "out" / "e2e"
FIXTURE_TOKEN = "fixture-secret-token-not-for-a-device"
FIXTURE_SESSION = "fixture-private-session-id"


class FixtureHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format_string, *arguments):
        pass

    def respond(self, status, payload=None, headers=None):
        body = json.dumps(payload).encode("utf-8") if payload is not None else b""
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)

    def authorized(self):
        if self.headers.get("Origin") is not None:
            self.respond(403)
            return False
        if self.headers.get("Authorization") != "Bearer " + FIXTURE_TOKEN:
            self.respond(401)
            return False
        return True

    def do_POST(self):
        if not self.authorized():
            return
        message = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        method = message.get("method")
        params = message.get("params", {})
        self.server.requests.append((method, params.get("name")))
        if "id" not in message:
            self.respond(202)
            return
        result = {"jsonrpc": "2.0", "id": message["id"]}
        if method == "initialize":
            result["result"] = {
                "protocolVersion": "2025-11-25",
                "serverInfo": {"name": "eta", "version": FIXTURE_TOKEN},
            }
            self.respond(200, result, {"Mcp-Session-Id": FIXTURE_SESSION})
            return
        if method == "tools/list":
            result["result"] = {
                "tools": [
                    {"name": "observe_screen", "inputSchema": {"type": "object"}, "description": FIXTURE_TOKEN},
                    {"name": "terminal", "inputSchema": {"type": "object"}, "description": FIXTURE_SESSION},
                ]
            }
            if self.headers.get("MCP-Protocol-Version") == "2026-07-28":
                result["result"].update(resultType="complete", ttlMs=0)
                if self.server.malformed_latest:
                    result["result"]["resultType"] = "incomplete"
            self.respond(200, result)
            return
        if method == "tools/call":
            name = params.get("name")
            if name and not name.startswith("eta_e2e_nonexistent_"):
                self.server.valid_tool_called = True
                self.respond(500)
                return
            code = -32602
        else:
            code = -32601
        result["error"] = {"code": code, "message": FIXTURE_TOKEN + FIXTURE_SESSION}
        self.respond(200, result)

    def do_DELETE(self):
        if not self.authorized():
            return
        self.server.deleted_own_session = self.headers.get("Mcp-Session-Id") == FIXTURE_SESSION
        self.respond(204 if self.server.deleted_own_session else 404)


class ReadonlyProbeOfflineTest(unittest.TestCase):
    def setUp(self):
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), FixtureHandler)
        self.server.requests = []
        self.server.valid_tool_called = False
        self.server.deleted_own_session = False
        self.server.malformed_latest = False
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        TEST_OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
        self.directory = tempfile.TemporaryDirectory(prefix="offline-fixture-", dir=TEST_OUTPUT_ROOT)
        self.report_path = Path(self.directory.name) / "report.json"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.directory.cleanup()

    def run_client(self, with_token=True):
        environment = os.environ.copy()
        environment.pop("ETA_MCP_FIXTURE_TOKEN", None)
        if with_token:
            environment["ETA_MCP_FIXTURE_TOKEN"] = FIXTURE_TOKEN
        return subprocess.run(
            [
                sys.executable,
                str(CLIENT),
                "--base-url", "http://127.0.0.1:" + str(self.server.server_port) + "/mcp",
                "--token-env", "ETA_MCP_FIXTURE_TOKEN",
                "--report", str(self.report_path),
                "--timeout", "2",
            ],
            env=environment,
            text=True,
            capture_output=True,
            timeout=15,
            check=False,
        )

    def test_protocol_passes_without_device_tools_or_sensitive_report_content(self):
        completed = self.run_client()
        self.assertEqual(0, completed.returncode)
        report_text = self.report_path.read_text(encoding="utf-8")
        report = json.loads(report_text)
        self.assertEqual({"passed": 12, "failed": 0, "error": 0}, report["counts"])
        self.assertFalse(self.server.valid_tool_called)
        self.assertTrue(self.server.deleted_own_session)
        combined_output = report_text + completed.stdout + completed.stderr
        for sensitive in (FIXTURE_TOKEN, FIXTURE_SESSION, "127.0.0.1", "Authorization"):
            self.assertNotIn(sensitive, combined_output)
        self.assertEqual(report["catalogs"]["legacy"], report["catalogs"]["stateless"])

    def test_invalid_latest_response_is_a_failure_and_still_cleans_up(self):
        self.server.malformed_latest = True
        completed = self.run_client()
        self.assertEqual(1, completed.returncode)
        report = json.loads(self.report_path.read_text(encoding="utf-8"))
        self.assertEqual(1, report["counts"]["failed"])
        latest = next(case for case in report["cases"] if case["name"] == "I4.stateless_list")
        self.assertFalse(latest["checks"]["complete_result_type"])
        self.assertTrue(self.server.deleted_own_session)

    def test_missing_token_stops_before_any_request(self):
        completed = self.run_client(with_token=False)
        self.assertEqual(2, completed.returncode)
        self.assertFalse(self.report_path.exists())
        self.assertEqual([], self.server.requests)

    def test_existing_report_is_not_overwritten(self):
        self.report_path.write_text("existing report", encoding="utf-8")
        completed = self.run_client()
        self.assertEqual(2, completed.returncode)
        self.assertEqual("existing report", self.report_path.read_text(encoding="utf-8"))
        self.assertEqual([], self.server.requests)


if __name__ == "__main__":
    unittest.main(verbosity=2)
