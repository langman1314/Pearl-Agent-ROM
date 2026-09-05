#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
COLLECTOR="$ROOT/scripts/collect-post-install-acceptance.sh"
work="$(mktemp -d -t pearl-post-acceptance-test.XXXXXXXX)"
trap 'rm -rf "$work"' EXIT
mock="$work/adb"
log="$work/adb.log"

cat > "$mock" <<'MOCK'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%q ' "$@" >> "$MOCK_ADB_LOG"
printf '\n' >> "$MOCK_ADB_LOG"
joined="$*"
if [[ "$joined" == 'shell su -c sh' ]]; then
  root_script="$(cat)"
  joined="$joined $root_script"
fi
case " $joined " in
  *'>'*|*' push '*|*' install '*|*' uninstall '*|*' reboot '*|*' remount '*|*' flash '*|*' erase '*|*' format '*|*' set_active '*|*' update '*|*' dd '*|*' mkfs '*|*' wipe '*|*' setprop '*|*' svc '*|*' kill '*|*' rm '*|*' chmod '*|*' chown '*|*' mount '*|*' tee '*)
    echo "Mutation primitive rejected by mock: $joined" >&2; exit 98 ;;
esac
xiaoai_hash=326fe0601b11698e70f96aca5dc05d1c783cf675ebc872024e96b405aaf64406
[[ "${MOCK_BAD_XIAOAI:-false}" != true ]] || xiaoai_hash=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
if [[ "${MOCK_BAD_AGENT_CHECKS:-false}" == true ]]; then
  case "$joined" in
    *'secret-metadata=valid'*) echo 'secret-metadata=invalid'; exit 0 ;;
    *'supervisor=running'*) echo 'supervisor=invalid'; exit 0 ;;
    *'bridge=running'*) echo 'bridge=invalid'; exit 0 ;;
    *'listener=127.0.0.1:51338'*) echo 'listener=missing'; exit 0 ;;
    *'nexus-settings=provisioned'*) echo 'nexus-settings=invalid'; exit 0 ;;
  esac
fi
case "$joined" in
  'get-state') echo device ;;
  'shell getprop ro.product.device') echo pearl ;;
  'shell getprop ro.build.version.sdk') echo 36 ;;
  'shell getprop sys.boot_completed') echo 1 ;;
  'shell getprop ro.boot.slot_suffix') echo _a ;;
  'shell getprop ro.boot.verifiedbootstate') echo orange ;;
  'shell getprop ro.build.fingerprint') echo 'Xiaomi/pearl/pearl:16/TEST/123:user/release-keys' ;;
  'shell getprop ro.build.version.incremental') echo OS3.0.310.0.WAACNXM ;;
  'shell cmd package list packages --show-versioncode com.miui.voiceassist') echo 'package:com.miui.voiceassist versionCode:507012002' ;;
  'shell pm path com.miui.voiceassist') echo 'package:/product/app/MIUIVoiceAssist/MIUIVoiceAssist.apk' ;;
  'shell sha256sum /product/app/MIUIVoiceAssist/MIUIVoiceAssist.apk') echo "$xiaoai_hash  /product/app/MIUIVoiceAssist/MIUIVoiceAssist.apk" ;;
  'shell su -c id') echo 'uid=0(root) gid=0(root) groups=0(root)' ;;
  'shell su -c magisk -V') echo 30700 ;;
  'shell cmd package list packages --show-versioncode com.niki914.nexus.agentic') echo 'package:com.niki914.nexus.agentic versionCode:8' ;;
  'shell pm path com.niki914.nexus.agentic') echo 'package:/data/app/~~abc==/com.niki914.nexus.agentic-def==/base.apk' ;;
  *"sha256sum '/data/app/~~abc==/com.niki914.nexus.agentic-def==/base.apk'"*) echo '99778de7820e8b3c19712a44ef921cddb778ba26156c7abd6593b7fd97b9988a  /data/app/base.apk' ;;
  *'cat /data/adb/modules/pearl_agent/module.prop'*) printf 'id=pearl_agent\nversion=0.1.5\nversionCode=6\n' ;;
  *'cat /data/adb/modules/zygisk_vector/module.prop'*) printf 'id=zygisk_vector\nversion=v2.2\nversionCode=3080\n' ;;
  *'cat /data/adb/pearl-agent/rootfs/opt/pearl-agent/BUILD.json'*) printf '{"architecture": "arm64", "hermes_commit": "a2e19d484cb5591df8dafe667c93345b62d9bf06"}\n' ;;
  *'secret-metadata=valid'*) echo 'secret-metadata=valid' ;;
  *'supervisor=running'*) echo 'supervisor=running' ;;
  *'bridge=running'*) echo 'bridge=running' ;;
  *'listener=127.0.0.1:51338'*) echo 'listener=127.0.0.1:51338' ;;
  *'nexus-settings=provisioned'*) echo 'nexus-settings=provisioned' ;;
  *) echo "Unexpected adb invocation: $joined" >&2; exit 97 ;;
esac
MOCK
chmod 0755 "$mock"
export MOCK_ADB_LOG="$log"

for phase in carrier magisk agent; do
  evidence="$work/$phase"
  ADB_BIN="$mock" bash "$COLLECTOR" --phase "$phase" --output-dir "$evidence"
  grep -Fx 'result=PASS' "$evidence/summary.txt" >/dev/null
  grep -Fx 'device_mutation_commands=none' "$evidence/summary.txt" >/dev/null
  grep -Fx 'secrets_collected=false' "$evidence/summary.txt" >/dev/null
  (cd "$evidence" && sha256sum -c manifest.sha256 >/dev/null)
done

grep -F $'nexus-settings\tPASS' "$work/agent/checks.tsv" >/dev/null
grep -F $'loopback-listener\tPASS' "$work/agent/checks.tsv" >/dev/null

if MOCK_BAD_XIAOAI=true ADB_BIN="$mock" bash "$COLLECTOR" \
    --phase carrier --output-dir "$work/rejected" >"$work/rejected.out" 2>&1; then
  echo 'Mismatched XiaoAi hash unexpectedly passed' >&2
  exit 1
fi
grep -Fx 'result=FAIL' "$work/rejected/summary.txt" >/dev/null
grep -F $'xiaoai-sha256\tFAIL' "$work/rejected/checks.tsv" >/dev/null

if MOCK_BAD_AGENT_CHECKS=true ADB_BIN="$mock" bash "$COLLECTOR" \
    --phase agent --output-dir "$work/rejected-agent" >"$work/rejected-agent.out" 2>&1; then
  echo 'Invalid Agent metadata unexpectedly passed' >&2
  exit 1
fi
grep -Fx 'result=FAIL' "$work/rejected-agent/summary.txt" >/dev/null
for check in secret-metadata supervisor bridge-process loopback-listener nexus-settings; do
  grep -F "$check"$'\tFAIL' "$work/rejected-agent/checks.tsv" >/dev/null
done

if grep -Eqi '(^|[[:space:]])(push|install|uninstall|reboot|remount|flash|erase|format|set_active|update)([[:space:]]|$)' "$log"; then
  echo 'Read-only collector issued a mutation command' >&2
  exit 1
fi

echo 'POST_INSTALL_ACCEPTANCE_COLLECTOR_TEST_OK'
