#!/usr/bin/env bash
set -Eeuo pipefail

# Read-only staged acceptance collector for the experimental Pearl carrier.
# It records identities and non-secret health metadata. It has no adb push,
# install, reboot, remount, package mutation, Magisk mutation or fastboot path.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

PHASE=""
OUTPUT_DIR=""
SERIAL=""
ADB_BIN="${ADB_BIN:-adb}"
XIAOAI_PACKAGE=com.miui.voiceassist
XIAOAI_VERSION=507012002
XIAOAI_SHA256=326fe0601b11698e70f96aca5dc05d1c783cf675ebc872024e96b405aaf64406
NEXUS_PACKAGE=com.niki914.nexus.agentic
NEXUS_VERSION=8
NEXUS_SHA256=99778de7820e8b3c19712a44ef921cddb778ba26156c7abd6593b7fd97b9988a
HERMES_COMMIT=a2e19d484cb5591df8dafe667c93345b62d9bf06

usage() {
  cat <<'EOF'
Usage: scripts/collect-post-install-acceptance.sh \
  --phase carrier|magisk|agent --output-dir PATH [--serial SERIAL]

Creates a new hash-manifested evidence directory. All device operations are
read-only. `carrier` validates the clean Android/XiaoAi identity; `magisk` adds
root/Magisk checks; `agent` adds Nexus, Vector, Hermes, secret-metadata,
supervisor and loopback-listener checks without collecting any secret value.
EOF
}
while (($#)); do
  case "$1" in
    --phase) PHASE="${2:?missing phase}"; shift 2 ;;
    --output-dir) OUTPUT_DIR="${2:?missing output directory}"; shift 2 ;;
    --serial) SERIAL="${2:?missing serial}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done
case "$PHASE" in carrier|magisk|agent) ;; *) echo "--phase must be carrier, magisk or agent" >&2; exit 2 ;; esac
[[ -n "$OUTPUT_DIR" ]] || { echo "--output-dir is required" >&2; exit 2; }
[[ ! -e "$OUTPUT_DIR" ]] || { echo "Refusing to overwrite evidence: $OUTPUT_DIR" >&2; exit 1; }
command -v "$ADB_BIN" >/dev/null 2>&1 || { echo "Required host tool not found: $ADB_BIN" >&2; exit 1; }

parent="$(dirname -- "$OUTPUT_DIR")"
mkdir -p "$parent"
parent="$(cd -- "$parent" && pwd -P)"
OUTPUT_DIR="$parent/$(basename -- "$OUTPUT_DIR")"
stage="$OUTPUT_DIR.partial.$$"
rm -rf "$stage"
mkdir "$stage"
chmod 0700 "$stage" 2>/dev/null || true
trap 'rm -rf "$stage"' EXIT
commands_file="$stage/commands.tsv"
checks_file="$stage/checks.tsv"
printf 'name\texit_code\tcommand\n' > "$commands_file"
printf 'check\tresult\texpected\tactual\n' > "$checks_file"
failed=0
CAPTURED=""

adb=("$ADB_BIN")
[[ -z "$SERIAL" ]] || adb+=( -s "$SERIAL" )
state="$("${adb[@]}" get-state 2>&1)" || { echo "ADB target is unavailable: $state" >&2; exit 1; }
[[ "$state" == device ]] || { echo "ADB state is not device: $state" >&2; exit 1; }

record_command() {
  local name="$1" status="$2"; shift 2
  { printf '%s\t%s\t' "$name" "$status"; printf '%q ' "$@"; printf '\n'; } >> "$commands_file"
}
capture() {
  local name="$1"; shift
  local output="$stage/$name.txt" status
  set +e
  "$@" > "$output" 2>&1
  status=$?
  set -e
  record_command "$name" "$status" "$@"
  chmod 0600 "$output" 2>/dev/null || true
  CAPTURED="$(tr -d '\r' < "$output")"
  if [[ "$status" != 0 ]]; then
    printf '%s\tFAIL\texit=0\texit=%s\n' "$name" "$status" >> "$checks_file"
    failed=$((failed + 1))
    return 1
  fi
  return 0
}
capture_root_script() {
  local name="$1" script="$2"
  local output="$stage/$name.txt" status script_hash
  script_hash="$(printf '%s\n' "$script" | sha256sum | awk '{print $1}')"
  set +e
  printf '%s\n' "$script" | "${adb[@]}" shell su -c sh > "$output" 2>&1
  status=$?
  set -e
  record_command "$name" "$status" "${adb[@]}" shell su -c sh "<stdin-sha256:$script_hash>"
  chmod 0600 "$output" 2>/dev/null || true
  CAPTURED="$(tr -d '\r' < "$output")"
  if [[ "$status" != 0 ]]; then
    printf '%s\tFAIL\texit=0\texit=%s\n' "$name" "$status" >> "$checks_file"
    failed=$((failed + 1))
    return 1
  fi
  return 0
}
check_eq() {
  local name="$1" expected="$2" actual="$3"
  if [[ "$actual" == "$expected" ]]; then
    printf '%s\tPASS\t%s\t%s\n' "$name" "$expected" "$actual" >> "$checks_file"
  else
    printf '%s\tFAIL\t%s\t%s\n' "$name" "$expected" "$actual" >> "$checks_file"
    failed=$((failed + 1))
  fi
}
check_contains() {
  local name="$1" needle="$2" actual="$3"
  if grep -Fq -- "$needle" <<< "$actual"; then
    printf '%s\tPASS\tcontains:%s\tmatched\n' "$name" "$needle" >> "$checks_file"
  else
    printf '%s\tFAIL\tcontains:%s\tnot-matched\n' "$name" "$needle" >> "$checks_file"
    failed=$((failed + 1))
  fi
}
select_apk_path() {
  local raw="$1" package="$2" path
  mapfile -t paths < <(printf '%s\n' "$raw" | sed -n "s|^package:||p" | sed '/^$/d')
  if ((${#paths[@]} == 1)); then
    path="${paths[0]}"
  else
    path=""
    for candidate in "${paths[@]}"; do [[ "$candidate" == */base.apk ]] && path="$candidate"; done
  fi
  [[ -n "$path" && "$path" == /*.apk && "$path" != *[!A-Za-z0-9._/@+=,:~-]* ]] || return 1
  printf '%s\n' "$path"
}
verify_package() {
  local label="$1" package="$2" version="$3" expected_hash="$4" root_hash="$5"
  local package_info path_info path hash_output actual_hash
  if capture "$label-package" "${adb[@]}" shell cmd package list packages --show-versioncode "$package"; then
    package_info="$CAPTURED"
    check_contains "$label-version" "package:$package versionCode:$version" "$package_info"
  fi
  if capture "$label-path" "${adb[@]}" shell pm path "$package"; then
    path_info="$CAPTURED"
    if path="$(select_apk_path "$path_info" "$label")"; then
      if [[ "$root_hash" == true ]]; then
        capture "$label-sha256" "${adb[@]}" shell su -c "sha256sum '$path'" || return 0
      else
        capture "$label-sha256" "${adb[@]}" shell sha256sum "$path" || return 0
      fi
      hash_output="$CAPTURED"
      actual_hash="${hash_output%%[[:space:]]*}"
      check_eq "$label-sha256" "$expected_hash" "$actual_hash"
    else
      printf '%s\tFAIL\tunique-safe-apk-path\tinvalid\n' "$label-path" >> "$checks_file"
      failed=$((failed + 1))
    fi
  fi
}

capture adb-state "${adb[@]}" get-state || true
capture product "${adb[@]}" shell getprop ro.product.device && check_eq product pearl "$CAPTURED"
capture sdk "${adb[@]}" shell getprop ro.build.version.sdk && check_eq sdk 36 "$CAPTURED"
capture boot-completed "${adb[@]}" shell getprop sys.boot_completed && check_eq boot-completed 1 "$CAPTURED"
capture slot-suffix "${adb[@]}" shell getprop ro.boot.slot_suffix && check_eq slot-suffix _a "$CAPTURED"
capture verified-boot "${adb[@]}" shell getprop ro.boot.verifiedbootstate && check_eq verified-boot orange "$CAPTURED"
capture fingerprint "${adb[@]}" shell getprop ro.build.fingerprint || true
capture incremental "${adb[@]}" shell getprop ro.build.version.incremental || true
verify_package xiaoai "$XIAOAI_PACKAGE" "$XIAOAI_VERSION" "$XIAOAI_SHA256" false

if [[ "$PHASE" == magisk || "$PHASE" == agent ]]; then
  capture root-id "${adb[@]}" shell su -c id && check_contains root-id 'uid=0(root)' "$CAPTURED"
  if capture magisk-version "${adb[@]}" shell su -c 'magisk -V'; then
    check_eq magisk-version 30700 "$(tr -d '[:space:]' <<< "$CAPTURED")"
  fi
fi

if [[ "$PHASE" == agent ]]; then
  verify_package nexus "$NEXUS_PACKAGE" "$NEXUS_VERSION" "$NEXUS_SHA256" true
  if capture agent-module "${adb[@]}" shell su -c 'cat /data/adb/modules/pearl_agent/module.prop'; then
    check_contains agent-module-id 'id=pearl_agent' "$CAPTURED"
    check_contains agent-module-version 'version=0.1.4' "$CAPTURED"
  fi
  if capture vector-module "${adb[@]}" shell su -c 'cat /data/adb/modules/zygisk_vector/module.prop'; then
    check_contains vector-module-id 'id=zygisk_vector' "$CAPTURED"
    check_contains vector-module-version 'versionCode=3080' "$CAPTURED"
  fi
  if capture hermes-build "${adb[@]}" shell su -c 'cat /data/adb/pearl-agent/rootfs/opt/pearl-agent/BUILD.json'; then
    check_contains hermes-commit "$HERMES_COMMIT" "$CAPTURED"
    check_contains hermes-architecture '"architecture": "arm64"' "$CAPTURED"
  fi
  capture_root_script secret-metadata 'envf=/data/adb/pearl-agent/data/hermes-home/.env
token=/data/adb/pearl-agent/data/config/mcp-token
test "$(stat -c %a "$envf")" = 600 &&
test "$(stat -c %a "$token")" = 600 &&
test "$(wc -c < "$token" | tr -d "[:space:]")" = 64 &&
test "$(grep -c "^DEEPSEEK_API_KEY=[^[:space:]][^[:space:]]*$" "$envf" | tr -d "[:space:]")" = 1 &&
echo secret-metadata=valid || echo secret-metadata=invalid' && check_eq secret-metadata secret-metadata=valid "$CAPTURED"
  capture_root_script supervisor 'f=/data/adb/pearl-agent/run/supervisor.pid
read pid start < "$f" &&
test -d "/proc/$pid" &&
test "$(awk '\''{print $22}'\'' "/proc/$pid/stat")" = "$start" &&
echo supervisor=running || echo supervisor=invalid' && check_eq supervisor supervisor=running "$CAPTURED"
  capture_root_script bridge-process 'f=/data/adb/pearl-agent/run/hermes-bridge.pid
read pid start < "$f" &&
test -d "/proc/$pid" &&
test "$(awk '\''{print $22}'\'' "/proc/$pid/stat")" = "$start" &&
tr "\000" " " < "/proc/$pid/cmdline" | grep -q pearl-hermes-bridge &&
echo bridge=running || echo bridge=invalid' && check_eq bridge-process bridge=running "$CAPTURED"
  capture_root_script loopback-listener 'grep -Eqi "0100007F:C88A[[:space:]]" /proc/net/tcp /proc/net/tcp6 && echo listener=127.0.0.1:51338 || echo listener=missing' && check_eq loopback-listener listener=127.0.0.1:51338 "$CAPTURED"
  capture_root_script nexus-settings 'mcp=/data/user/0/com.niki914.nexus.agentic/files/settings/tools/mcp/servers.json
llm=/data/user/0/com.niki914.nexus.agentic/files/settings/agents/main/config.json
grep -Eq "\"Authorization\"[[:space:]]*:[[:space:]]*\"Bearer [0-9a-f]{64}\"" "$mcp" &&
grep -Eq "\"provider\"[[:space:]]*:[[:space:]]*\"[^\"]+\"" "$llm" &&
grep -Eq "\"endpoint\"[[:space:]]*:[[:space:]]*\"https://[^\"]+\"" "$llm" &&
grep -Eq "\"model\"[[:space:]]*:[[:space:]]*\"[^\"]+\"" "$llm" &&
grep -Eq "\"api_key\"[[:space:]]*:[[:space:]]*\"[^\"]{10,}\"" "$llm" &&
echo nexus-settings=provisioned || echo nexus-settings=invalid' && check_eq nexus-settings nexus-settings=provisioned "$CAPTURED"
fi

result=PASS
((failed == 0)) || result=FAIL
{
  printf 'phase=%s\n' "$PHASE"
  printf 'result=%s\n' "$result"
  printf 'failed_checks=%s\n' "$failed"
  printf 'collector=collect-post-install-acceptance.sh\n'
  printf 'device_mutation_commands=none\n'
  printf 'secrets_collected=false\n'
  printf 'xiaoai_expected_version=%s\n' "$XIAOAI_VERSION"
  printf 'xiaoai_expected_sha256=%s\n' "$XIAOAI_SHA256"
  [[ "$PHASE" != agent ]] || printf 'nexus_expected_sha256=%s\n' "$NEXUS_SHA256"
} > "$stage/summary.txt"
chmod 0600 "$commands_file" "$checks_file" "$stage/summary.txt" 2>/dev/null || true
(
  cd "$stage"
  find . -maxdepth 1 -type f ! -name manifest.sha256 -print0 | sort -z | xargs -0 sha256sum > manifest.sha256
)
chmod 0600 "$stage/manifest.sha256" 2>/dev/null || true
mv "$stage" "$OUTPUT_DIR"
trap - EXIT
printf 'Read-only %s acceptance evidence: %s result=%s\n' "$PHASE" "$OUTPUT_DIR" "$result"
[[ "$result" == PASS ]]
