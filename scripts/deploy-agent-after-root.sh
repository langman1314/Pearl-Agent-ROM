#!/usr/bin/env bash
set -Eeuo pipefail

# Post-root data-only deployment. This does not flash any partition and does
# not enable Vector scope. Dry-run is the default.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

RELEASE_DIR=""
SERIAL=""
EXECUTE=false
ADB_BIN="${ADB_BIN:-adb}"
ACK=YES_INSTALL_VECTOR_NEXUS_HERMES

usage() {
  cat <<'EOF'
Usage: scripts/deploy-agent-after-root.sh \
  --release-dir PATH [--serial SERIAL] [--execute]

Execution requires:
  PEARL_ACCEPT_AGENT_DEPLOY=YES_INSTALL_VECTOR_NEXUS_HERMES

Installs the approved Nexus APK plus Vector and pearl-agent Magisk modules.
It never flashes a partition and never enables an Xposed scope automatically.
EOF
}
while (($#)); do
  case "$1" in
    --release-dir) RELEASE_DIR="${2:?missing release directory}"; shift 2 ;;
    --serial) SERIAL="${2:?missing serial}"; shift 2 ;;
    --execute) EXECUTE=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done
[[ -n "$RELEASE_DIR" ]] || { echo "--release-dir is required" >&2; exit 2; }
RELEASE_DIR="$(cd -- "$RELEASE_DIR" && pwd -P)"
adb=("$ADB_BIN")
[[ -z "$SERIAL" ]] || adb+=( -s "$SERIAL" )

nexus="$RELEASE_DIR/nexus-1.0.1-pearl.2-release.apk"
vector="$RELEASE_DIR/Vector-v2.2-3080-Release.zip"
agent="$RELEASE_DIR/pearl-agent-magisk-0.1.2-3.zip"
declare -A expected=(
  ["$nexus"]=99778de7820e8b3c19712a44ef921cddb778ba26156c7abd6593b7fd97b9988a
  ["$vector"]=9ee8323575d615f7b3f1076ff60b2a63a49390ef11881b52632311a37f6f79cc
  ["$agent"]=386bcc1b4a383389343d65c8323250a2b03eba50a4c6d7e035ba5f33be10a7d8
)
for file in "$nexus" "$vector" "$agent"; do
  [[ -f "$file" ]] || { echo "Missing approved artifact: $file" >&2; exit 1; }
  actual="$(sha256sum "$file" | awk '{print $1}')"
  [[ "$actual" == "${expected[$file]}" ]] || { echo "Artifact hash mismatch: $file" >&2; exit 1; }
done

state="$("${adb[@]}" get-state 2>/dev/null)" || { echo "Authorized ADB device unavailable" >&2; exit 1; }
[[ "$state" == device ]] || { echo "ADB state is not device: $state" >&2; exit 1; }
product="$("${adb[@]}" shell getprop ro.product.device | tr -d '\r')"
sdk="$("${adb[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$product" == pearl ]] || { echo "Refusing non-pearl Android device" >&2; exit 1; }
[[ "$sdk" == 36 ]] || { echo "Experimental carrier requires Android SDK 36, got $sdk" >&2; exit 1; }

adb_host_path() {
  local path="$1"
  if [[ "${ADB_BIN,,}" == *.exe ]] && command -v cygpath >/dev/null 2>&1; then
    cygpath -w -- "$path"
  else
    printf '%s\n' "$path"
  fi
}

nexus_adb="$(adb_host_path "$nexus")"
vector_adb="$(adb_host_path "$vector")"
agent_adb="$(adb_host_path "$agent")"

printf 'Post-root deployment plan:\n'
printf '  adb install -r nexus-1.0.1-pearl.2-release.apk\n'
printf '  magisk --install-module Vector-v2.2-3080-Release.zip\n'
printf '  magisk --install-module pearl-agent-magisk-0.1.2-3.zip\n'
printf '  root sync\n'
printf '  adb reboot\n'
$EXECUTE || { echo "DRY_RUN_ONLY"; exit 0; }
[[ "${PEARL_ACCEPT_AGENT_DEPLOY:-}" == "$ACK" ]] || { echo "Missing exact deployment acknowledgement" >&2; exit 1; }
root_id="$("${adb[@]}" shell su -c id | tr -d '\r')" || { echo "Magisk root unavailable or not granted to ADB shell" >&2; exit 1; }
[[ "$root_id" == uid=0* ]] || { echo "Root identity rejected: $root_id" >&2; exit 1; }
remote=/data/local/tmp/pearl-agent-deploy
cleanup() { "${adb[@]}" shell su -c "rm -rf $remote" >/dev/null 2>&1 || true; }
trap cleanup EXIT
"${adb[@]}" shell su -c "rm -rf $remote && mkdir -p $remote && chmod 0777 $remote"
"${adb[@]}" push "$vector_adb" "$remote/vector.zip"
"${adb[@]}" push "$agent_adb" "$remote/agent.zip"
"${adb[@]}" install -r "$nexus_adb"
"${adb[@]}" shell su -c "magisk --install-module $remote/vector.zip"
"${adb[@]}" shell su -c "magisk --install-module $remote/agent.zip"
"${adb[@]}" shell su -c sync
cleanup
trap - EXIT
"${adb[@]}" reboot
