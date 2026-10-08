#!/usr/bin/env bash
#
# Integration test suite for Java LocalServer.
# Starts the server, runs assertions against every feature, then shuts it down.
#
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

HOST="127.0.0.1"
PORT=8080
PORT2=8081
PORT_SECONDARY=9090
BASE="http://$HOST:$PORT"

PASS=0
FAIL=0

# --- helpers ---------------------------------------------------------------

log()  { printf "%s\n" "$*"; }
green(){ printf "\033[32m%s\033[0m\n" "$*"; }
red()  { printf "\033[31m%s\033[0m\n" "$*"; }

assert_code() {
  local desc="$1" expected="$2" url="$3"; shift 3
  local code
  code=$(curl -s -o /dev/null -w "%{http_code}" "$@" "$url")
  if [ "$code" = "$expected" ]; then
    green "PASS: $desc ($code)"; PASS=$((PASS+1))
  else
    red   "FAIL: $desc (expected $expected, got $code)"; FAIL=$((FAIL+1))
  fi
}

assert_contains() {
  local desc="$1" needle="$2" url="$3"; shift 3
  local body
  body=$(curl -s "$@" "$url")
  if printf "%s" "$body" | grep -q "$needle"; then
    green "PASS: $desc"; PASS=$((PASS+1))
  else
    red   "FAIL: $desc (missing '$needle')"; FAIL=$((FAIL+1))
  fi
}

# --- start server ----------------------------------------------------------

make build >/dev/null 2>&1
java -cp out server.Main config.json >/tmp/localserver_test.log 2>&1 &
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null' EXIT

# wait for readiness
for _ in $(seq 1 30); do
  if curl -s -o /dev/null "$BASE/"; then break; fi
  sleep 0.2
done

log "=== Static content & routing ==="
assert_code "GET /"                    200 "$BASE/"
assert_code "GET /about.html"          200 "$BASE/about.html"
assert_code "GET nonexistent -> 404"   404 "$BASE/does-not-exist"
assert_code "Directory listing"        200 "$BASE/uploads/"

log "=== Methods ==="
assert_code "Unsupported method -> 405" 405 "$BASE/" -X PUT
assert_code "Redirect /old -> 302"      302 "$BASE/old"

log "=== Multi-port & virtual servers ==="
assert_code "Second port 8081"          200 "http://$HOST:$PORT2/"
assert_code "Secondary server 9090"     200 "http://$HOST:$PORT_SECONDARY/"

log "=== CGI ==="
assert_contains "CGI GET query"   "Hello, tester" "$BASE/cgi-bin/hello.py?name=tester"
assert_contains "CGI POST body"   "Method: POST"  "$BASE/cgi-bin/hello.py" -d "x=1"

log "=== Upload / Delete ==="
echo "test-content" > /tmp/ls_upload.txt
assert_code "Upload multipart -> 201" 201 "$BASE/uploads" -F "file=@/tmp/ls_upload.txt"
assert_code "Uploaded file readable"  200 "$BASE/uploads/ls_upload.txt"
assert_code "Delete uploaded file"    200 "$BASE/uploads/ls_upload.txt" -X DELETE
assert_code "Deleted file gone -> 404" 404 "$BASE/uploads/ls_upload.txt"

log "=== Body size limit ==="
head -c 11000000 /dev/zero > /tmp/ls_big.bin
assert_code "Oversized body -> 413" 413 "$BASE/" -X POST --data-binary @/tmp/ls_big.bin

log "=== Chunked encoding ==="
assert_contains "Chunked POST to CGI" "chunk=yes" \
  "$BASE/cgi-bin/hello.py" --data-binary "chunk=yes" -H "Transfer-Encoding: chunked"

log "=== Security ==="
assert_code "Path traversal blocked" 403 "$BASE/../../../etc/passwd" --path-as-is

log "=== Sessions ==="
assert_contains "Set-Cookie issued" "SESSIONID" "$BASE/" -D -

log ""
green "Passed: $PASS"
if [ "$FAIL" -gt 0 ]; then
  red "Failed: $FAIL"
  exit 1
fi
log "All tests passed."
