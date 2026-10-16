# Read-only MCP protocol probes

These Python 3 standard-library tools validate a small subset of
[`docs/E2E_TESTING.md`](../../docs/E2E_TESTING.md). They are not the complete
86-case device E2E runner, a Mock model server, or the planned debug test hooks.

## Coverage

| Case | Coverage |
| --- | --- |
| I2 | Missing token and wrong token return HTTP 401; a valid token with `Origin` returns HTTP 403. |
| I3, partial | Negotiate `2025-11-25`, obtain a session ID, list valid tool records, and exclude `skills_*`. Tool-setting/UI consistency and disabled-tool disappearance are not tested. |
| I4 | Stateless `2026-07-28` tool listing returns `resultType: complete` and `ttlMs: 0`. |

Additional probes check unknown tools, missing tool names, invalid argument
types, and unknown methods. These are JSON-RPC errors, not I5's tool execution
`isError` outcomes. The client deletes only the session it creates.

No valid device tool is called: the client never operates the screen, runs
`terminal`, launches a model task, or modifies device settings. Initializing and
deleting its own protocol session still change temporary server session state.
Session idle expiry (I9), service lifecycle, settings changes, and all other
device E2E cases remain outside its scope.

## Offline self-test

From the repository root:

```powershell
python -B -m unittest discover -s tools/e2e -p "test_*.py" -v
```

The tests start a fake HTTP server on an ephemeral `127.0.0.1` port. They check
success, malformed-response failure, cleanup, secret redaction, missing-token
rejection, and report overwrite protection. Temporary fixture artifacts are
created below `out/e2e/` and removed afterward. They do not contact a device;
passing them is not a device E2E pass.

## Device prerequisites

1. Install and record the intended APK version/commit and signing identity.
   A successful CI check or a test against an older installed APK does not prove
   the current commit's runtime behavior.
2. Record the original Eta MCP service settings. Enable its localhost-only
   listener and obtain the token through Eta's settings UI. Do not extract
   private preference files or include tokens in screenshots/logs.
3. Use USB forwarding with an explicitly selected device and an unused local
   port. Avoid exposing the listener to a LAN solely for this probe. The client
   makes direct HTTP(S) connections, does not use environment proxy settings,
   and does not follow redirects.

## Run against the device

Replace the serial and device port placeholders with values you have verified.
The forwarding and service changes are operator actions, not performed by the
Python client.

```powershell
$serial = "<device-serial>"
$devicePort = "<port shown in Eta MCP settings>"
$localPort = 18787
adb -s $serial forward "tcp:$localPort" "tcp:$devicePort"

$stamp = Get-Date -Format "yyyyMMdd-HHmmss-fff"
$reportDir = Join-Path "out/e2e" $stamp
New-Item -ItemType Directory -Path $reportDir | Out-Null
$secureToken = Read-Host "Eta MCP token" -AsSecureString
$env:ETA_MCP_TOKEN = [System.Net.NetworkCredential]::new("", $secureToken).Password
try {
    python tools/e2e/mcp_readonly_probe.py `
        --base-url "http://127.0.0.1:$localPort/mcp" `
        --token-env ETA_MCP_TOKEN `
        --report "$reportDir/protocol-report.json"
    $probeExit = $LASTEXITCODE
} finally {
    Remove-Item Env:ETA_MCP_TOKEN -ErrorAction SilentlyContinue
    $secureToken = $null
    adb -s $serial forward --remove "tcp:$localPort"
}
Write-Output "Probe exit code: $probeExit"
```

Restore the original MCP service settings afterward. Check that
`cleanup.delete_own_session` passed; if not, stop the test listener to dispose of
the test session before restoring its original state.

The endpoint must explicitly end in `/mcp`; credentials, query strings, and
fragments in the URL are rejected. Tokens come only from the named environment
variable, never command-line token values. The timeout defaults to 8 seconds
per request and may be set with `--timeout` (maximum 60 seconds).

## Reports and interpretation

Use a fresh `out/e2e/<run>/` directory for every device run. Existing report
files are never overwritten. Reports retain case names, check booleans, HTTP
statuses, RPC error codes, elapsed times, and catalog counts/hashes. They omit
tokens, session IDs, endpoint hostnames, raw response bodies, tool schemas,
and server error messages.

- Exit `0`: all attempted probes passed.
- Exit `1`: at least one probe failed or encountered an error.
- Exit `2`: invalid arguments or unmet local preconditions; no test pass.

Keep the APK/device provenance and the distinction between I3's tested subset
and its untested UI assertions in the surrounding run report. Do not label I3,
I5, I9, or the full E2E suite passed based only on this client's exit code.
