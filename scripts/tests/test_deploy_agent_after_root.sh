#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd -P)"
SCRIPT="$REPO_ROOT/scripts/deploy-agent-after-root.sh"
work="$(mktemp -d -t pearl-agent-deploy-test.XXXXXXXX)"
trap 'rm -rf "$work"' EXIT
release="$work/release"; mkdir -p "$release"
for name in nexus-1.0.1-pearl.3-release.apk Vector-v2.2-3080-Release.zip pearl-agent-magisk-0.1.6-7.zip; do printf x > "$release/$name"; done
cat > "$work/sha256sum" <<'EOF'
#!/usr/bin/env bash
case "$1" in
 *nexus-1.0.1-pearl.3-release.apk) h=2e8daa59fb28c986db70fd54b90962a641da41b882d7bd8d938b65d3dac9ff2f;;
 *Vector-v2.2-3080-Release.zip) h=9ee8323575d615f7b3f1076ff60b2a63a49390ef11881b52632311a37f6f79cc;;
 *pearl-agent-magisk-0.1.6-7.zip) h=bbebae1483cf3a228ad3715e12284739334bafbcc6d9573d83ef3b9b19c51cf2;;
 *) exit 1;; esac
printf '%s  %s\n' "$h" "$1"
EOF
cat > "$work/adb" <<'EOF'
#!/usr/bin/env bash
printf '%s ' "$@" >> "${MOCK_LOG:?}"; printf '\n' >> "$MOCK_LOG"
[[ "${1:-}" == -s ]] && shift 2
case "${1:-}" in
 get-state) echo device;;
 shell)
  case "$*" in
   'shell getprop ro.product.device') echo pearl;;
   'shell getprop ro.build.version.sdk') echo 36;;
   'shell su -c id') echo 'uid=0(root) gid=0(root)';;
  esac;;
 *) :;;
esac
EOF
chmod 0755 "$work/sha256sum" "$work/adb"
export PATH="$work:$PATH" MOCK_LOG="$work/log"
: > "$MOCK_LOG"
ADB_BIN="$work/adb" bash "$SCRIPT" --release-dir "$release" --serial PEARL123 > "$work/dry.txt"
grep -Fx DRY_RUN_ONLY "$work/dry.txt" >/dev/null
if grep -Eq ' install | push | reboot|su -c' "$MOCK_LOG"; then echo 'dry-run performed deployment' >&2; exit 1; fi
if ADB_BIN="$work/adb" bash "$SCRIPT" --release-dir "$release" --serial PEARL123 --execute >/dev/null 2>&1; then
 echo 'missing deployment acknowledgement was accepted' >&2; exit 1
fi
: > "$MOCK_LOG"
PEARL_ACCEPT_AGENT_DEPLOY=YES_INSTALL_VECTOR_NEXUS_HERMES ADB_BIN="$work/adb" \
 bash "$SCRIPT" --release-dir "$release" --serial PEARL123 --execute >/dev/null
grep -F 'install -r' "$MOCK_LOG" >/dev/null
grep -F 'magisk --install-module /data/local/tmp/pearl-agent-deploy/vector.zip' "$MOCK_LOG" >/dev/null
grep -F 'magisk --install-module /data/local/tmp/pearl-agent-deploy/agent.zip' "$MOCK_LOG" >/dev/null
grep -F 'shell su -c sync' "$MOCK_LOG" >/dev/null
grep -F 'reboot' "$MOCK_LOG" >/dev/null
if grep -Eqi 'fastboot| flash |vbmeta|preloader|efuse|super' "$MOCK_LOG"; then echo 'post-root deployment escaped data-only boundary' >&2; exit 1; fi
printf 'POST_ROOT_AGENT_DEPLOY_TEST_OK\n'
