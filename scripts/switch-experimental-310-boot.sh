#!/usr/bin/env bash
set -Eeuo pipefail

# Switch only slot-A boot between the stock carrier image and the independently
# reproduced Magisk 30.7 image. Dry-run is the default.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

STAGING=""
SERIAL=""
MODE=""
EXECUTE=false
FASTBOOT_BIN="${FASTBOOT_BIN:-fastboot}"
STOCK_SHA256=8526d0ff63b6606f4ccda6381f61921e6a56c3bcdd7fa0ace05578863f9a6f82
MAGISK_SHA256=f3cb3ca2353315bc96694cd17d400b71108864f28ff4ef8a7260c50f69572005

usage() {
  cat <<'EOF'
Usage: scripts/switch-experimental-310-boot.sh \
  --staging PATH --mode stock|magisk [--serial SERIAL] [--execute]

Execution acknowledgement:
  mode magisk: PEARL_ACCEPT_MAGISK_BOOT=YES_FLASH_FRESH_MAGISK_TO_BOOT_A
  mode stock:  PEARL_CONFIRM_STOCK_RESTORE=YES_RESTORE_STOCK_BOOT_A

Only boot_a, active slot A and reboot are in the write allowlist.
EOF
}

while (($#)); do
  case "$1" in
    --staging) STAGING="${2:?missing staging path}"; shift 2 ;;
    --serial) SERIAL="${2:?missing serial}"; shift 2 ;;
    --mode) MODE="${2:?missing mode}"; shift 2 ;;
    --execute) EXECUTE=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done
[[ -n "$STAGING" ]] || { echo "--staging is required" >&2; exit 2; }
[[ "$MODE" == stock || "$MODE" == magisk ]] || { echo "--mode must be stock or magisk" >&2; exit 2; }
STAGING="$(cd -- "$STAGING" && pwd -P)"
if [[ "$MODE" == stock ]]; then
  image="$STAGING/images/boot-stock.img"
  expected="$STOCK_SHA256"
  acknowledgement="${PEARL_CONFIRM_STOCK_RESTORE:-}"
  required_ack=YES_RESTORE_STOCK_BOOT_A
else
  image="$STAGING/images/boot-magisk.img"
  expected="$MAGISK_SHA256"
  acknowledgement="${PEARL_ACCEPT_MAGISK_BOOT:-}"
  required_ack=YES_FLASH_FRESH_MAGISK_TO_BOOT_A
fi
[[ -f "$image" ]] || { echo "Missing boot image: $image" >&2; exit 1; }
actual="$(sha256sum "$image" | awk '{print $1}')"
[[ "$actual" == "$expected" ]] || { echo "Boot image hash mismatch" >&2; exit 1; }

fastboot=("$FASTBOOT_BIN")
[[ -z "$SERIAL" ]] || fastboot+=( -s "$SERIAL" )
devices="$("$FASTBOOT_BIN" devices 2>&1)"
if [[ -n "$SERIAL" ]]; then
  awk -v serial="$SERIAL" '$1 == serial && $2 == "fastboot" { ok=1 } END { exit !ok }' \
    <<<"$devices" || { echo "Requested fastboot device not present" >&2; exit 1; }
else
  count="$(awk '$2 == "fastboot" { n++ } END { print n+0 }' <<<"$devices")"
  [[ "$count" == 1 ]] || { echo "Expected exactly one fastboot device" >&2; exit 1; }
fi
getvar() {
  local key="$1" output
  output="$("${fastboot[@]}" getvar "$key" 2>&1)" || return 1
  awk -v key="$key" '{line=$0;sub(/^\(bootloader\)[[:space:]]*/,"",line);if(line ~ ("^" key ":[[:space:]]*")){sub("^" key ":[[:space:]]*","",line);v=line}}END{sub(/[[:space:]]+$/,"",v);if(v=="")exit 1;print v}' <<<"$output"
}
[[ "$(getvar product)" == pearl ]] || { echo "Refusing non-pearl device" >&2; exit 1; }
[[ "$(getvar unlocked)" == yes ]] || { echo "Bootloader is not unlocked" >&2; exit 1; }
[[ "$(getvar current-slot)" == a ]] || { echo "Carrier boot switch requires active slot A" >&2; exit 1; }
boot_size="$(getvar partition-size:boot_a)"
capacity_equals() {
  local raw="${1#0x}"
  raw="${raw#0X}"
  [[ "$raw" =~ ^[0-9a-fA-F]+$ ]] || return 1
  ((16#$raw == $2))
}
capacity_equals "$boot_size" 67108864 || { echo "Unexpected boot_a capacity" >&2; exit 1; }
printf 'mode=%s image_sha256=%s\n' "$MODE" "$actual"
printf '  fastboot flash boot_a images/%s\n' "$(basename "$image")"
printf '  fastboot set_active a\n'
printf '  fastboot reboot\n'
$EXECUTE || { echo "DRY_RUN_ONLY"; exit 0; }
[[ "$acknowledgement" == "$required_ack" ]] || { echo "Missing exact execution acknowledgement" >&2; exit 1; }
"${fastboot[@]}" flash boot_a "$image"
"${fastboot[@]}" set_active a
"${fastboot[@]}" reboot
