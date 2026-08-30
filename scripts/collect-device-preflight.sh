#!/usr/bin/env bash
set -Eeuo pipefail

# Prevent Git Bash/MSYS from rewriting Android absolute paths such as
# /dev/block/by-name/boot into host Windows paths before passing them to adb.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

# Read-only target-device evidence collector. This script has no flash, erase,
# format, reboot, boot, set_active, update, dd, push, or remount operation.

MODE=""
OUTPUT_DIR=""
SERIAL=""
ADB_BIN="${ADB_BIN:-adb}"
FASTBOOT_BIN="${FASTBOOT_BIN:-fastboot}"

usage() {
  cat <<'EOF'
Usage: scripts/collect-device-preflight.sh --mode adb|fastboot --output-dir PATH [--serial SERIAL]

Collects selected read-only Android or bootloader identity facts into a new,
atomically published evidence directory. The output directory must not exist.
ADB_BIN and FASTBOOT_BIN may override tool paths for controlled testing.
EOF
}

while (($#)); do
  case "$1" in
    --mode) MODE="${2:?missing mode}"; shift 2 ;;
    --output-dir) OUTPUT_DIR="${2:?missing output directory}"; shift 2 ;;
    --serial) SERIAL="${2:?missing serial}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

case "$MODE" in adb|fastboot) ;; *) echo "--mode must be adb or fastboot" >&2; exit 2 ;; esac
[[ -n "$OUTPUT_DIR" ]] || { echo "--output-dir is required" >&2; exit 2; }
[[ ! -e "$OUTPUT_DIR" ]] || { echo "Refusing to overwrite evidence: $OUTPUT_DIR" >&2; exit 1; }

parent="$(dirname -- "$OUTPUT_DIR")"
mkdir -p "$parent"
parent="$(cd -- "$parent" && pwd -P)"
OUTPUT_DIR="$parent/$(basename -- "$OUTPUT_DIR")"
stage="$OUTPUT_DIR.partial.$$"
rm -rf "$stage"
mkdir "$stage"
# Host evidence may live on Windows filesystems where POSIX chmod is unavailable.
# Apply private modes where supported; the evidence directory is never committed.
chmod 0700 "$stage" 2>/dev/null || true
trap 'rm -rf "$stage"' EXIT

chmod_private() {
  chmod "$@" 2>/dev/null || true
}

commands_file="$stage/commands.tsv"
printf 'name\texit_code\tcommand\n' > "$commands_file"

run_capture() {
  name="$1"
  shift
  output="$stage/$name.txt"
  set +e
  "$@" > "$output" 2>&1
  status=$?
  set -e
  chmod_private 0600 "$output"
  {
    printf '%s\t%s\t' "$name" "$status"
    printf '%q ' "$@"
    printf '\n'
  } >> "$commands_file"
  return 0
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Required host tool not found: $1" >&2
    exit 1
  }
}

if [[ "$MODE" == adb ]]; then
  require_command "$ADB_BIN"
  adb=("$ADB_BIN")
  [[ -z "$SERIAL" ]] || adb+=( -s "$SERIAL" )
  state="$("${adb[@]}" get-state 2>&1)" || {
    echo "ADB target is unavailable: $state" >&2
    exit 1
  }
  [[ "$state" == "device" ]] || { echo "ADB state is not device: $state" >&2; exit 1; }

  run_capture adb-state "${adb[@]}" get-state
  properties=(
    ro.product.device
    ro.product.vendor.device
    ro.product.system.device
    ro.build.fingerprint
    ro.build.version.incremental
    ro.build.version.sdk
    ro.build.version.security_patch
    ro.boot.slot_suffix
    ro.boot.verifiedbootstate
    ro.boot.flash.locked
    ro.boot.vbmeta.device_state
  )
  for property in "${properties[@]}"; do
    safe="${property//./-}"
    run_capture "adb-$safe" "${adb[@]}" shell getprop "$property"
  done
  run_capture adb-xiaoai-package "${adb[@]}" shell cmd package list packages --show-versioncode com.miui.voiceassist
  run_capture adb-magisk-version "${adb[@]}" shell magisk -V
  run_capture adb-by-name-boot "${adb[@]}" shell ls -l /dev/block/by-name/boot /dev/block/by-name/boot_a /dev/block/by-name/boot_b
  run_capture adb-by-name-vbmeta "${adb[@]}" shell ls -l /dev/block/by-name/vbmeta /dev/block/by-name/vbmeta_a /dev/block/by-name/vbmeta_b
else
  require_command "$FASTBOOT_BIN"
  fastboot=("$FASTBOOT_BIN")
  [[ -z "$SERIAL" ]] || fastboot+=( -s "$SERIAL" )
  devices="$("$FASTBOOT_BIN" devices 2>&1)" || {
    echo "fastboot devices failed: $devices" >&2
    exit 1
  }
  if [[ -n "$SERIAL" ]]; then
    grep -Eq "^${SERIAL}[[:space:]]+fastboot([[:space:]]|$)" <<<"$devices" || {
      echo "Requested fastboot serial is not present" >&2
      exit 1
    }
  else
    count="$(grep -Ec '^[^[:space:]]+[[:space:]]+fastboot([[:space:]]|$)' <<<"$devices" || true)"
    [[ "$count" == "1" ]] || {
      echo "Expected exactly one fastboot device without --serial; found $count" >&2
      exit 1
    }
  fi

  run_capture fastboot-devices "$FASTBOOT_BIN" devices
  variables=(
    product
    current-slot
    slot-count
    unlocked
    secure
    anti
    has-slot:boot
    has-slot:vbmeta
    partition-size:boot
    partition-size:boot_a
    partition-size:boot_b
    partition-size:vbmeta
    partition-size:vbmeta_a
    partition-size:vbmeta_b
  )
  for variable in "${variables[@]}"; do
    safe="${variable//:/-}"
    run_capture "fastboot-$safe" "${fastboot[@]}" getvar "$variable"
  done
fi

chmod_private 0600 "$commands_file"
{
  printf 'mode=%s\n' "$MODE"
  printf 'collector=collect-device-preflight.sh\n'
  printf 'mutation_commands=none\n'
  printf 'partition_contents_collected=false\n'
} > "$stage/summary.txt"
chmod_private 0600 "$stage/summary.txt"
(
  cd "$stage"
  find . -maxdepth 1 -type f ! -name manifest.sha256 -print0 \
    | sort -z \
    | xargs -0 sha256sum > manifest.sha256
)
chmod_private 0600 "$stage/manifest.sha256"
mv "$stage" "$OUTPUT_DIR"
trap - EXIT
printf 'Read-only %s evidence: %s\n' "$MODE" "$OUTPUT_DIR"
