#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
SCRIPT="$ROOT/scripts/provision-hermes-after-root.sh"
tmp="$(mktemp -d "${TMPDIR:-/tmp}/pearl-hermes-provision-test.XXXXXXXX")"
trap 'rm -rf "$tmp"' EXIT
mock="$tmp/adb"
log="$tmp/adb.log"
capture="$tmp/secret.stdin"

cat > "$mock" <<'MOCK'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%q ' "$@" >> "$ADB_TEST_LOG"
printf '\n' >> "$ADB_TEST_LOG"
joined="$*"
case "$joined" in
  "get-state") echo device ;;
  "shell getprop ro.product.device") echo pearl ;;
  "shell getprop ro.build.version.sdk") echo 36 ;;
  "shell su -c id") echo 'uid=0(root) gid=0(root) groups=0(root)' ;;
  *"test -f /data/adb/modules/pearl_agent/module.prop"*) exit 0 ;;
  *"cat > \"\$tmp\""*) cat > "$ADB_TEST_CAPTURE" ;;
  *"mode=\$(stat -c %a"*) echo 'mode=600 key_lines=1 bytes=52' ;;
  "reboot") echo reboot >> "$ADB_TEST_LOG" ;;
  *) echo "Unexpected adb invocation: $joined" >&2; exit 99 ;;
esac
MOCK
chmod +x "$mock"
export ADB_TEST_LOG="$log" ADB_TEST_CAPTURE="$capture"

out="$(ADB_BIN="$mock" bash "$SCRIPT")"
grep -qx 'DRY_RUN_ONLY' <<< "$out"
[[ ! -e "$capture" ]]

if PEARL_ACCEPT_SECRET_PROVISION=WRONG ADB_BIN="$mock" bash "$SCRIPT" --execute </dev/null >"$tmp/wrong.out" 2>&1; then
  echo 'Execution unexpectedly accepted wrong acknowledgement' >&2
  exit 1
fi
grep -q 'Missing exact secret-provisioning acknowledgement' "$tmp/wrong.out"
[[ ! -e "$capture" ]]

secret='sk-test-only-0123456789abcdef0123456789abcdef'
printf '%s\n' "$secret" | \
  PEARL_ACCEPT_SECRET_PROVISION=YES_PROVISION_HERMES_DEEPSEEK_SECRET \
  ADB_BIN="$mock" bash "$SCRIPT" --execute >"$tmp/execute.out" 2>&1

grep -q 'Hermes credential provisioned: mode=600 key_lines=1 bytes=52' "$tmp/execute.out"
grep -qx 'REBOOT_REQUESTED' "$tmp/execute.out"
if grep -Fq "$secret" "$tmp/execute.out"; then
  echo 'Secret leaked to console output' >&2
  exit 1
fi
if grep -Fq "$secret" "$log"; then
  echo 'Secret leaked to an adb command argument' >&2
  exit 1
fi
printf 'DEEPSEEK_API_KEY=%s\n' "$secret" > "$tmp/expected"
cmp -s "$tmp/expected" "$capture"
grep -qx 'reboot' "$log"

echo 'HERMES_SECRET_PROVISION_TEST_OK'
