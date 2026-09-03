#!/usr/bin/env bash
set -Eeuo pipefail

# Installs only the minimum OS3.0.310.0 experimental carrier set. This is not
# an AVB-verified official ROM. Dry-run is the default and never touches device
# partitions. Execution requires both --execute and an explicit environment
# acknowledgement.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

STAGING=""
SERIAL=""
EXECUTE=false
WIPE=false
FASTBOOT_BIN="${FASTBOOT_BIN:-fastboot}"
EXPECTED_RAW_SUPER_BYTES=9126805504
ACK_VALUE=YES_I_ACCEPT_UNVERIFIED_310_AND_DATA_LOSS

usage() {
  cat <<'EOF'
Usage: scripts/flash-experimental-310-carrier.sh \
  --staging PATH [--serial SERIAL] [--wipe] [--execute]

Dry-run is the default. Actual execution additionally requires:
  PEARL_ACCEPT_UNVERIFIED_310=YES_I_ACCEPT_UNVERIFIED_310_AND_DATA_LOSS

The fixed write set is stock boot_a, vendor_boot_a, dtbo_a, vbmeta_a,
vbmeta_system_a, vbmeta_vendor_a, shared super, metadata/userdata erase,
set_active a, and reboot. No preloader, efuse, GPT, bootloader, modem, tee,
firmware, logo, rescue, cust, slot-B or generic flash-all command is allowed.
EOF
}

while (($#)); do
  case "$1" in
    --staging) STAGING="${2:?missing staging path}"; shift 2 ;;
    --serial) SERIAL="${2:?missing serial}"; shift 2 ;;
    --wipe) WIPE=true; shift ;;
    --execute) EXECUTE=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

[[ -n "$STAGING" ]] || { echo "--staging is required" >&2; exit 2; }
STAGING="$(cd -- "$STAGING" && pwd -P)"
command -v "$FASTBOOT_BIN" >/dev/null 2>&1 || { echo "fastboot not found" >&2; exit 1; }
command -v sha256sum >/dev/null 2>&1 || { echo "sha256sum not found" >&2; exit 1; }
command -v python >/dev/null 2>&1 || { echo "python not found" >&2; exit 1; }

required=(
  images/boot-stock.img
  images/vendor_boot.img
  images/dtbo.img
  images/vbmeta.img
  images/vbmeta_system.img
  images/vbmeta_vendor.img
  images/super.img
  manifest.sha256
)
for relative in "${required[@]}"; do
  [[ -f "$STAGING/$relative" ]] || { echo "Missing staged file: $relative" >&2; exit 1; }
done
(
  cd "$STAGING"
  sha256sum -c manifest.sha256
)

super_path_for_python="$STAGING/images/super.img"
if command -v cygpath >/dev/null 2>&1; then
  super_path_for_python="$(cygpath -w "$super_path_for_python")"
fi
actual_raw_super_bytes="$(python - "$super_path_for_python" <<'PY'
import struct,sys
with open(sys.argv[1], 'rb') as stream:
    header=stream.read(28)
if len(header) != 28:
    raise SystemExit('truncated sparse header')
magic,major,minor,file_hdr,chunk_hdr,block_size,total_blocks,total_chunks,checksum=struct.unpack('<I4H4I',header)
if (magic,major,file_hdr,chunk_hdr) != (0xED26FF3A,1,28,12):
    raise SystemExit('invalid Android sparse v1 header')
print(block_size*total_blocks)
PY
)"
[[ "$actual_raw_super_bytes" == "$EXPECTED_RAW_SUPER_BYTES" ]] || {
  echo "Unexpected expanded super size: $actual_raw_super_bytes" >&2
  exit 1
}

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
  local key="$1" output value
  output="$("${fastboot[@]}" getvar "$key" 2>&1)" || {
    echo "getvar $key failed" >&2
    return 1
  }
  value="$(awk -v key="$key" '{line=$0;sub(/^\(bootloader\)[[:space:]]*/,"",line);if(line ~ ("^" key ":[[:space:]]*")){sub("^" key ":[[:space:]]*","",line);v=line}}END{sub(/[[:space:]]+$/,"",v);if(v=="")exit 1;print v}' <<<"$output")"
  printf '%s\n' "$value"
}

product="$(getvar product)"
unlocked="$(getvar unlocked)"
anti="$(getvar anti)"
slot_count="$(getvar slot-count)"
has_boot="$(getvar has-slot:boot)"
has_vbmeta="$(getvar has-slot:vbmeta)"
boot_size="$(getvar partition-size:boot_a)"
vbmeta_size="$(getvar partition-size:vbmeta_a)"
super_size="$(getvar partition-size:super)"
[[ "$product" == pearl ]] || { echo "Refusing product: $product" >&2; exit 1; }
[[ "$unlocked" == yes ]] || { echo "Bootloader is not unlocked" >&2; exit 1; }
[[ "$anti" =~ ^[0-9]+$ && "$anti" -le 1 ]] || { echo "Anti-rollback value rejected: $anti" >&2; exit 1; }
[[ "$slot_count" == 2 && "$has_boot" == yes && "$has_vbmeta" == yes ]] || {
  echo "Unexpected A/B geometry" >&2; exit 1;
}
[[ "${boot_size,,}" == 0x4000000 && "${vbmeta_size,,}" == 0x800000 ]] || {
  echo "Unexpected boot/vbmeta capacity" >&2; exit 1;
}
super_hex="${super_size#0x}"; super_hex="${super_hex#0X}"
[[ "$super_hex" =~ ^[0-9a-fA-F]+$ ]] || { echo "Invalid super capacity" >&2; exit 1; }
(( 16#$super_hex >= EXPECTED_RAW_SUPER_BYTES )) || { echo "super partition is too small" >&2; exit 1; }

plan=(
  "flash|boot_a|images/boot-stock.img"
  "flash|vendor_boot_a|images/vendor_boot.img"
  "flash|dtbo_a|images/dtbo.img"
  "flash|super|images/super.img"
  "flash|vbmeta_system_a|images/vbmeta_system.img"
  "flash|vbmeta_vendor_a|images/vbmeta_vendor.img"
  "flash|vbmeta_a|images/vbmeta.img"
  "erase|metadata|"
  "erase|userdata|"
  "set_active|a|"
  "reboot||"
)

printf 'Experimental 310 carrier plan (unverified AVB):\n'
for entry in "${plan[@]}"; do
  IFS='|' read -r command arg file <<<"$entry"
  if [[ "$command" == flash ]]; then
    printf '  fastboot flash %s %s\n' "$arg" "$file"
  elif [[ -n "$arg" ]]; then
    printf '  fastboot %s %s\n' "$command" "$arg"
  else
    printf '  fastboot %s\n' "$command"
  fi
done

$EXECUTE || { echo "DRY_RUN_ONLY"; exit 0; }
$WIPE || { echo "Execution requires --wipe" >&2; exit 1; }
[[ "${PEARL_ACCEPT_UNVERIFIED_310:-}" == "$ACK_VALUE" ]] || {
  echo "Missing exact risk acknowledgement" >&2; exit 1;
}

for entry in "${plan[@]}"; do
  IFS='|' read -r command arg file <<<"$entry"
  case "$command" in
    flash) "${fastboot[@]}" flash "$arg" "$STAGING/$file" ;;
    erase) "${fastboot[@]}" erase "$arg" ;;
    set_active) "${fastboot[@]}" set_active "$arg" ;;
    reboot) "${fastboot[@]}" reboot ;;
    *) echo "Internal command rejected: $command" >&2; exit 1 ;;
  esac
  status=$?
  [[ "$status" == 0 ]] || {
    echo "STOP: $command $arg failed; device was not automatically rebooted" >&2
    exit "$status"
  }
done
