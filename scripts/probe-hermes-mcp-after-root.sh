#!/usr/bin/env bash
set -Eeuo pipefail

# Read-only authenticated MCP acceptance. The probe source is streamed to the
# chroot Python over adb stdin. The device-local token never crosses stdout,
# stderr, an adb argument, or a host file.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROBE="$SCRIPT_DIR/probe-hermes-mcp.py"
OUTPUT_DIR=""
SERIAL=""
ADB_BIN="${ADB_BIN:-adb}"

usage() {
  cat <<'EOF'
Usage: scripts/probe-hermes-mcp-after-root.sh \
  --output-dir PATH [--serial SERIAL]

Requires the provisioned Pearl Agent module and a running bridge. Performs only
loopback MCP requests: missing token and wrong token must be rejected, while the
device-local correct token must initialize and list exactly five Hermes tools.
The token is read and compared only on-device and is never included in evidence.
EOF
}
while (($#)); do
  case "$1" in
    --output-dir) OUTPUT_DIR="${2:?missing output directory}"; shift 2 ;;
    --serial) SERIAL="${2:?missing serial}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done
[[ -n "$OUTPUT_DIR" ]] || { echo "--output-dir is required" >&2; exit 2; }
[[ ! -e "$OUTPUT_DIR" ]] || { echo "Refusing to overwrite evidence: $OUTPUT_DIR" >&2; exit 1; }
[[ -f "$PROBE" ]] || { echo "MCP probe source is missing: $PROBE" >&2; exit 1; }
command -v "$ADB_BIN" >/dev/null 2>&1 || { echo "Required host tool not found: $ADB_BIN" >&2; exit 1; }

adb=("$ADB_BIN")
[[ -z "$SERIAL" ]] || adb+=( -s "$SERIAL" )
state="$("${adb[@]}" get-state 2>&1)" || { echo "ADB target is unavailable: $state" >&2; exit 1; }
[[ "$state" == device ]] || { echo "ADB state is not device: $state" >&2; exit 1; }
product="$("${adb[@]}" shell getprop ro.product.device | tr -d '\r')"
sdk="$("${adb[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
root_id="$("${adb[@]}" shell su -c id | tr -d '\r')" || { echo "Magisk root unavailable" >&2; exit 1; }
[[ "$product" == pearl ]] || { echo "Refusing non-pearl device: $product" >&2; exit 1; }
[[ "$sdk" == 36 ]] || { echo "Expected experimental carrier SDK 36, got $sdk" >&2; exit 1; }
[[ "$root_id" == uid=0* ]] || { echo "Root identity rejected: $root_id" >&2; exit 1; }

parent="$(dirname -- "$OUTPUT_DIR")"
mkdir -p "$parent"
parent="$(cd -- "$parent" && pwd -P)"
OUTPUT_DIR="$parent/$(basename -- "$OUTPUT_DIR")"
stage="$OUTPUT_DIR.partial.$$"
rm -rf "$stage"
mkdir "$stage"
chmod 0700 "$stage" 2>/dev/null || true
trap 'rm -rf "$stage"' EXIT
cp "$PROBE" "$stage/probe-hermes-mcp.py"
chmod 0600 "$stage/probe-hermes-mcp.py" 2>/dev/null || true

device_probe="$stage/device-probe.sh"
{
  cat <<'DEVICE_SH'
output="$(
  chroot /data/adb/pearl-agent/rootfs /opt/pearl-agent/venv/bin/python - \
    --url http://127.0.0.1:51338/mcp \
    --token-file /data/pearl-agent/config/mcp-token 2>&1 <<'PEARL_MCP_PROBE_PY'
DEVICE_SH
  cat "$PROBE"
  cat <<'DEVICE_SH'
PEARL_MCP_PROBE_PY
)"
status=$?
token="$(cat /data/adb/pearl-agent/data/config/mcp-token)" || exit 1
case "$output" in
  *"$token"*) echo "probe output contained MCP token" >&2; exit 97 ;;
esac
printf '%s\n' "$output"
exit "$status"
DEVICE_SH
} > "$device_probe"
chmod 0600 "$device_probe" 2>/dev/null || true
device_probe_hash="$(sha256sum "$device_probe" | awk '{print $1}')"
{
  printf 'name\texit_code\tcommand\n'
  printf 'mcp-probe\t0\t'
  printf '%q ' "${adb[@]}" shell su -c sh
  printf '<stdin-sha256:%s>\n' "$device_probe_hash"
} > "$stage/commands.tsv"
chmod 0600 "$stage/commands.tsv" 2>/dev/null || true

set +e
"${adb[@]}" shell su -c sh < "$device_probe" > "$stage/probe.txt" 2> "$stage/probe-error.txt"
status=$?
set -e
tr -d '\r' < "$stage/probe.txt" > "$stage/probe.normalized.txt"
mv "$stage/probe.normalized.txt" "$stage/probe.txt"
chmod 0600 "$stage/probe.txt" "$stage/probe-error.txt" 2>/dev/null || true
if [[ "$status" != 0 ]]; then
  sed -i "2s/\t0\t/\t$status\t/" "$stage/commands.tsv"
  result=FAIL
else
  result=PASS
  grep -Eq '^unauthenticated_status=(401|403)$' "$stage/probe.txt" || result=FAIL
  grep -Eq '^wrong_token_status=(401|403)$' "$stage/probe.txt" || result=FAIL
  grep -Fx 'authenticated=true' "$stage/probe.txt" >/dev/null || result=FAIL
  grep -Eq '^protocol=[0-9]{4}-[0-9]{2}-[0-9]{2}$' "$stage/probe.txt" || result=FAIL
  grep -Fx 'tools=hermes_cancel,hermes_health,hermes_run,hermes_submit,hermes_task_status' "$stage/probe.txt" >/dev/null || result=FAIL
  grep -Fx 'secrets_output=false' "$stage/probe.txt" >/dev/null || result=FAIL
fi
{
  printf 'result=%s\n' "$result"
  printf 'collector=probe-hermes-mcp-after-root.sh\n'
  printf 'device_mutation_commands=none\n'
  printf 'secrets_collected=false\n'
  printf 'probe_source_sha256=%s\n' "$(sha256sum "$PROBE" | awk '{print $1}')"
} > "$stage/summary.txt"
chmod 0600 "$stage/summary.txt" 2>/dev/null || true
(
  cd "$stage"
  find . -maxdepth 1 -type f ! -name manifest.sha256 -print0 | sort -z | xargs -0 sha256sum > manifest.sha256
)
chmod 0600 "$stage/manifest.sha256" 2>/dev/null || true
mv "$stage" "$OUTPUT_DIR"
trap - EXIT
printf 'Read-only authenticated MCP evidence: %s result=%s\n' "$OUTPUT_DIR" "$result"
[[ "$result" == PASS ]]
