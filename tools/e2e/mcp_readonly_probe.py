"""Probe Eta MCP protocol behavior without executing valid device tools."""

import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import sys
import time
from urllib.parse import urlsplit
import uuid


LEGACY_VERSION = "2025-11-25"
LATEST_VERSION = "2026-07-28"
MAX_RESPONSE_BYTES = 4 * 1024 * 1024


class Probe:
    def __init__(self, base_url, token, timeout):
        self.endpoint = urlsplit(base_url)
        if (
            self.endpoint.scheme not in ("http", "https")
            or not self.endpoint.hostname
            or self.endpoint.username is not None
            or self.endpoint.password is not None
            or self.endpoint.query
            or self.endpoint.fragment
            or self.endpoint.path.rstrip("/") != "/mcp"
        ):
            raise ValueError("base URL must be an explicit http(s) /mcp endpoint without credentials or query")
        self.endpoint.port
        self.token = token
        self.timeout = timeout
        self.session = None
        self.next_id = 0
        self.results = []
        self.catalogs = {}

    def request(self, payload=None, auth="valid", extra_headers=None, method="POST", session=None):
        connection_class = http.client.HTTPSConnection if self.endpoint.scheme == "https" else http.client.HTTPConnection
        connection = connection_class(self.endpoint.hostname, self.endpoint.port, timeout=self.timeout)
        headers = {"Accept": "application/json, text/event-stream", "Content-Type": "application/json"}
        if auth == "valid":
            headers["Authorization"] = "Bearer " + self.token
        elif auth == "wrong":
            headers["Authorization"] = "Bearer eta-e2e-invalid-" + uuid.uuid4().hex
        if session:
            headers["Mcp-Session-Id"] = session
        headers.update(extra_headers or {})
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8") if payload is not None else None
        try:
            connection.request(method, self.endpoint.path, body=body, headers=headers)
            response = connection.getresponse()
            raw = response.read(MAX_RESPONSE_BYTES + 1)
            if len(raw) > MAX_RESPONSE_BYTES:
                raise ValueError("response exceeds configured limit")
            try:
                decoded = json.loads(raw) if raw else None
            except (UnicodeDecodeError, json.JSONDecodeError):
                decoded = None
            return {
                "status": response.status,
                "headers": {name.lower(): value for name, value in response.getheaders()},
                "json": decoded,
                "body_bytes": len(raw),
            }
        finally:
            connection.close()

    def rpc(self, method, params=None):
        self.next_id += 1
        return {"jsonrpc": "2.0", "id": self.next_id, "method": method, "params": params or {}}

    def case(self, name, action, assertion):
        started = time.monotonic()
        result = {"name": name, "status": "error"}
        try:
            response = action()
            result.update(http_status=response["status"], response_bytes=response["body_bytes"])
            checks = assertion(response)
            result["checks"] = checks
            result["status"] = "passed" if all(checks.values()) else "failed"
            payload = response["json"]
            if isinstance(payload, dict) and isinstance(payload.get("error"), dict):
                code = payload["error"].get("code")
                if isinstance(code, int):
                    result["rpc_error_code"] = code
        except Exception as failure:
            result["error_type"] = type(failure).__name__
        result["elapsed_ms"] = round((time.monotonic() - started) * 1000)
        self.results.append(result)
        print(name + ": " + result["status"], flush=True)
        return result["status"] == "passed"

    @staticmethod
    def error_checks(response, request_id, error_code, http_status=200):
        payload = response["json"] if isinstance(response["json"], dict) else {}
        error = payload.get("error") if isinstance(payload.get("error"), dict) else {}
        return {
            "http_status": response["status"] == http_status,
            "jsonrpc_2": payload.get("jsonrpc") == "2.0",
            "response_id": payload.get("id") == request_id,
            "rpc_error_code": error.get("code") == error_code,
            "no_success_result": "result" not in payload,
        }

    def initialize_checks(self, response, request_id):
        payload = response["json"] if isinstance(response["json"], dict) else {}
        result = payload.get("result") if isinstance(payload.get("result"), dict) else {}
        session = response["headers"].get("mcp-session-id")
        if isinstance(session, str) and session:
            self.session = session
        server_info = result.get("serverInfo") if isinstance(result.get("serverInfo"), dict) else {}
        return {
            "http_200": response["status"] == 200,
            "jsonrpc_2": payload.get("jsonrpc") == "2.0",
            "response_id": payload.get("id") == request_id,
            "legacy_version": result.get("protocolVersion") == LEGACY_VERSION,
            "session_header_present": self.session is not None,
            "server_is_eta": server_info.get("name") == "eta",
        }

    def list_checks(self, response, request_id, label, latest=False):
        payload = response["json"] if isinstance(response["json"], dict) else {}
        result = payload.get("result") if isinstance(payload.get("result"), dict) else {}
        tools = result.get("tools")
        records_valid = isinstance(tools, list) and all(
            isinstance(tool, dict)
            and isinstance(tool.get("name"), str)
            and bool(tool["name"])
            and isinstance(tool.get("inputSchema"), dict)
            for tool in tools
        )
        names = sorted(tool["name"] for tool in tools) if records_valid else []
        self.catalogs[label] = {
            "count": len(names),
            "names_sha256": hashlib.sha256(json.dumps(names, separators=(",", ":")).encode("utf-8")).hexdigest(),
        }
        checks = {
            "http_200": response["status"] == 200,
            "jsonrpc_2": payload.get("jsonrpc") == "2.0",
            "response_id": payload.get("id") == request_id,
            "valid_tool_records": records_valid,
            "nonempty_catalog": bool(names),
            "unique_tool_names": len(names) == len(set(names)),
            "no_skills_tools": not any(name.startswith("skills_") for name in names),
        }
        if latest:
            checks["complete_result_type"] = result.get("resultType") == "complete"
            checks["zero_ttl"] = type(result.get("ttlMs")) is int and result["ttlMs"] == 0
        return checks

    def run(self):
        payload = self.rpc("tools/list")
        self.case("I2.no_token", lambda: self.request(payload, auth="none"), lambda response: {"http_401": response["status"] == 401})
        self.case("I2.wrong_token", lambda: self.request(payload, auth="wrong"), lambda response: {"http_401": response["status"] == 401})
        self.case(
            "I2.origin_rejected",
            lambda: self.request(payload, extra_headers={"Origin": "https://eta-e2e.invalid"}),
            lambda response: {"http_403": response["status"] == 403},
        )
        payload = self.rpc("initialize", {
            "protocolVersion": LEGACY_VERSION,
            "capabilities": {},
            "clientInfo": {"name": "eta-readonly-e2e", "version": "1"},
        })
        try:
            initialized = self.case("I3.initialize_subset", lambda: self.request(payload), lambda response: self.initialize_checks(response, payload["id"]))
            if initialized:
                self.case(
                    "protocol.initialized_notification",
                    lambda: self.request({"jsonrpc": "2.0", "method": "notifications/initialized"}, session=self.session),
                    lambda response: {"http_202": response["status"] == 202},
                )
                payload = self.rpc("tools/list")
                self.case(
                    "I3.legacy_list_subset",
                    lambda: self.request(payload, session=self.session, extra_headers={"MCP-Protocol-Version": LEGACY_VERSION}),
                    lambda response: self.list_checks(response, payload["id"], "legacy"),
                )
            payload = self.rpc("tools/list")
            self.case(
                "I4.stateless_list",
                lambda: self.request(payload, extra_headers={"MCP-Protocol-Version": LATEST_VERSION}),
                lambda response: self.list_checks(response, payload["id"], "stateless", latest=True),
            )
            payload = self.rpc("tools/call", {"name": "eta_e2e_nonexistent_" + uuid.uuid4().hex, "arguments": {}})
            self.case("protocol.unknown_tool", lambda: self.request(payload, session=self.session), lambda response: self.error_checks(response, payload["id"], -32602))
            payload = self.rpc("tools/call", {"arguments": {}})
            self.case("protocol.missing_tool_name", lambda: self.request(payload, session=self.session), lambda response: self.error_checks(response, payload["id"], -32602))
            payload = self.rpc("tools/call", {"name": "eta_e2e_nonexistent_" + uuid.uuid4().hex, "arguments": []})
            self.case("protocol.invalid_arguments", lambda: self.request(payload, session=self.session), lambda response: self.error_checks(response, payload["id"], -32602))
            payload = self.rpc("eta_e2e_nonexistent_method")
            self.case("protocol.unknown_method", lambda: self.request(payload, session=self.session), lambda response: self.error_checks(response, payload["id"], -32601))
        finally:
            if self.session:
                self.case("cleanup.delete_own_session", lambda: self.request(method="DELETE", session=self.session), lambda response: {"http_204": response["status"] == 204})
        return self.results


def main():
    parser = argparse.ArgumentParser(description="Read-only Eta MCP protocol probe; never calls a valid device tool.")
    parser.add_argument("--base-url", required=True, help="Explicit endpoint, for example http://127.0.0.1:18787/mcp")
    parser.add_argument("--token-env", default="ETA_MCP_TOKEN", help="Environment variable containing the token; token values are never written to reports")
    parser.add_argument("--report", type=Path, required=True, help="New JSON report path; existing files are not overwritten")
    parser.add_argument("--timeout", type=float, default=8)
    arguments = parser.parse_args()
    token = os.environ.get(arguments.token_env, "").strip()
    if not token:
        parser.error("token environment variable is empty or unset")
    if "\r" in token or "\n" in token:
        parser.error("token contains an invalid newline")
    if arguments.timeout <= 0 or arguments.timeout > 60:
        parser.error("timeout must be greater than zero and at most 60 seconds")
    if arguments.report.exists():
        parser.error("report path already exists")
    try:
        probe = Probe(arguments.base_url, token, arguments.timeout)
    except ValueError:
        parser.error("invalid base URL; use an explicit http(s) /mcp endpoint without credentials or query")
    report = {
        "schema_version": 1,
        "started_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "scope": "I2; I3 initialization/catalog subset; I4; protocol errors; own-session cleanup",
        "not_covered": ["tool-setting/UI consistency", "valid device tool execution", "I5 isError outcomes", "screen", "terminal", "model runs", "session idle expiry"],
        "redaction": "No token, session ID, endpoint hostname, raw body, tool schema, or error message is stored.",
    }
    report["cases"] = probe.run()
    report["catalogs"] = probe.catalogs
    report["counts"] = {status: sum(case["status"] == status for case in report["cases"]) for status in ("passed", "failed", "error")}
    arguments.report.parent.mkdir(parents=True, exist_ok=True)
    with arguments.report.open("x", encoding="utf-8") as output:
        json.dump(report, output, ensure_ascii=True, indent=2)
        output.write("\n")
    return 0 if not report["counts"]["failed"] and not report["counts"]["error"] else 1


if __name__ == "__main__":
    sys.exit(main())
