#!/usr/bin/env bash
set -Eeuo pipefail

# Minimal official Android 15 recovery anchor. Dry-run is the default. It does
# not reuse Xiaomi's flash_all scripts and excludes every low-level partition.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"

STAGING=""
SERIAL=""
EXECUTE=false
WIPE=false
FASTBOOT_BIN="${FASTBOOT_BIN:-fastboot}"
ACK_VALUE=YES_RESTORE_OFFICIAL_OS3_0_3_0_AND_WIPE
EXPECTED_SUPER_BYTES=9126805504
EXPECTED_SUPER_RAW_SHA256=038483b5afccb91c843cd3143380b332750b26c0130bfbb3eb87aa02d088a1f0
EXPECTED_CUST_BYTES=708837376
EXPECTED_CUST_RAW_SHA256=d1af2f4310dee2df9fa0de602240dc23a0a36ce68d47211c9801349b74d3cfef

declare -A expected=(
 ["images/boot.img"]=8526d0ff63b6606f4ccda6381f61921e6a56c3bcdd7fa0ace05578863f9a6f82
 ["images/cust.img"]=ed712c0d3f2bc1a56bccd9583cba47037f09476027bdcf47178757eab2f790c1
 ["images/dtbo.img"]=dbc8f42ed1cd6704521d609c5bfc3db71f28bc07a0a54ca8dd0e9aa40ee944c9
 ["images/super.img"]=7ff1bb372c7ddf8c1debc6c0784a972426f16746425835dec967b266fe827026
 ["images/vbmeta.img"]=0dd9695425802eaf9b8bb35f2a94c41e7b0aeedff856ba9e70c860f206b82dfc
 ["images/vbmeta_system.img"]=4c3601c2666a43511142681dfb4eaa42de31fe3b32412cd9df39ea41a501ec39
 ["images/vbmeta_vendor.img"]=a7c26870259c98ba2c7e1473dd6839b03e8eb7cea759db4789a592972b5a9e56
 ["images/vendor_boot.img"]=5bbb608a856c6ec2023e0acb5b4d174bf4f81ee17893708883af7453a2590654
)
usage() {
 cat <<'EOF'
Usage: scripts/restore-official-minimal.sh \
  --staging OFFICIAL_FASTBOOT_ROOT [--serial SERIAL] [--wipe] [--execute]

Default is read-only dry-run. Execution requires all of:
  --wipe --execute
  PEARL_CONFIRM_OFFICIAL_RECOVERY=YES_RESTORE_OFFICIAL_OS3_0_3_0_AND_WIPE

Fixed writes: boot_a, vendor_boot_a, dtbo_a, shared super, unslotted cust,
vbmeta_system_a, vbmeta_vendor_a, vbmeta_a, erase metadata/userdata,
set active A, reboot. No preloader, efuse, GPT, slot B or low-level firmware.
EOF
}
while (($#)); do
 case "$1" in
  --staging) STAGING="${2:?missing staging path}"; shift 2;;
  --serial) SERIAL="${2:?missing serial}"; shift 2;;
  --wipe) WIPE=true; shift;;
  --execute) EXECUTE=true; shift;;
  -h|--help) usage; exit 0;;
  *) echo "Unknown argument: $1" >&2; usage >&2; exit 2;;
 esac
done
[[ -n "$STAGING" ]] || { echo "--staging is required" >&2; exit 2; }
STAGING="$(cd -- "$STAGING" && pwd -P)"
for rel in "${!expected[@]}"; do
 file="$STAGING/$rel"; [[ -f "$file" ]] || { echo "Missing official image: $rel" >&2; exit 1; }
 actual="$(sha256sum "$file" | awk '{print $1}')"
 [[ "$actual" == "${expected[$rel]}" ]] || { echo "Official image hash mismatch: $rel" >&2; exit 1; }
 printf '%s: OK\n' "$rel"
done
sparse_expanded_bytes() {
 local path="$1" native="$1"
 if command -v cygpath >/dev/null 2>&1; then native="$(cygpath -w "$path")"; fi
 python - "$native" <<'PY'
import pathlib,struct,sys
p=pathlib.Path(sys.argv[1])
with p.open('rb') as f: h=f.read(28)
if len(h)!=28: raise SystemExit('short sparse header')
magic,major,minor,file_h,chunk_h,block,total_blocks,total_chunks,checksum=struct.unpack('<I4H4I',h)
if (magic,major,minor,file_h,chunk_h)!=(0xED26FF3A,1,0,28,12): raise SystemExit('invalid sparse header')
print(block*total_blocks)
PY
}
verify_sparse_payload() {
 local input="$1" expected_bytes="$2" expected_hash="$3" label="$4"
 local output="$STAGING/.$(basename "$input").raw.verify.$$"
 local input_for_python="$input" output_for_python="$output" script_for_python="$REPO_ROOT/scripts/unsparse-android-image.py"
 if command -v cygpath >/dev/null 2>&1; then
  input_for_python="$(cygpath -w "$input")"
  output_for_python="$(cygpath -w "$output")"
  script_for_python="$(cygpath -w "$script_for_python")"
 fi
 rm -f "$output"
 python "$script_for_python" "$input_for_python" "$output_for_python" --force >/dev/null
 local bytes actual
 bytes="$(wc -c < "$output" | awk '{print $1}')"
 actual="$(sha256sum "$output" | awk '{print $1}')"
 rm -f "$output"
 [[ "$bytes" == "$expected_bytes" ]] || { echo "$label expanded size mismatch: $bytes" >&2; return 1; }
 [[ "$actual" == "$expected_hash" ]] || { echo "$label raw hash mismatch: $actual" >&2; return 1; }
 printf '%s: sparse and raw payload verified\n' "$label"
}
verify_sparse_payload "$STAGING/images/super.img" "$EXPECTED_SUPER_BYTES" "$EXPECTED_SUPER_RAW_SHA256" images/super.img
verify_sparse_payload "$STAGING/images/cust.img" "$EXPECTED_CUST_BYTES" "$EXPECTED_CUST_RAW_SHA256" images/cust.img
fastboot=("$FASTBOOT_BIN"); [[ -z "$SERIAL" ]] || fastboot+=( -s "$SERIAL" )
devices="$("$FASTBOOT_BIN" devices 2>&1)"
if [[ -n "$SERIAL" ]]; then
 awk -v s="$SERIAL" '$1==s && $2=="fastboot"{ok=1}END{exit !ok}' <<<"$devices" || { echo 'Requested fastboot device not present' >&2; exit 1; }
else
 [[ "$(awk '$2=="fastboot"{n++}END{print n+0}' <<<"$devices")" == 1 ]] || { echo 'Expected exactly one fastboot device' >&2; exit 1; }
fi
getvar() {
 local key="$1" output
 output="$("${fastboot[@]}" getvar "$key" 2>&1)" || return 1
 awk -v key="$key" '{l=$0;sub(/^\(bootloader\)[[:space:]]*/,"",l);if(l~("^"key":[[:space:]]*")){sub("^"key":[[:space:]]*","",l);v=l}}END{sub(/[[:space:]]+$/,"",v);if(v=="")exit 1;print v}' <<<"$output"
}
[[ "$(getvar product)" == pearl ]] || { echo 'Refusing non-pearl device' >&2; exit 1; }
[[ "$(getvar unlocked)" == yes ]] || { echo 'Bootloader is not unlocked' >&2; exit 1; }
anti="$(getvar anti)"; [[ "$anti" =~ ^[0-9]+$ && "$anti" -le 1 ]] || { echo "Official recovery anti gate rejected: $anti" >&2; exit 1; }
[[ "$(getvar current-slot)" == a && "$(getvar slot-count)" == 2 ]] || { echo 'Unexpected active slot geometry' >&2; exit 1; }
for p in boot vendor_boot dtbo vbmeta vbmeta_system vbmeta_vendor; do [[ "$(getvar has-slot:$p)" == yes ]] || { echo "Missing A/B partition: $p" >&2; exit 1; }; done
[[ "$(getvar has-slot:cust)" == no ]] || { echo 'Expected unslotted cust partition' >&2; exit 1; }
boot_size="$(getvar partition-size:boot_a)"; vbmeta_size="$(getvar partition-size:vbmeta_a)"
[[ "${boot_size,,}" == 0x4000000 && "${vbmeta_size,,}" == 0x800000 ]] || { echo 'Unexpected boot/vbmeta capacity' >&2; exit 1; }
capacity_at_least() { local raw="${1#0x}"; raw="${raw#0X}"; [[ "$raw" =~ ^[0-9a-fA-F]+$ ]] && ((16#$raw >= $2)); }
capacity_at_least "$(getvar partition-size:super)" "$EXPECTED_SUPER_BYTES" || { echo 'super partition is too small' >&2; exit 1; }
capacity_at_least "$(getvar partition-size:cust)" "$EXPECTED_CUST_BYTES" || { echo 'cust partition is too small' >&2; exit 1; }
plan=(
 'flash|boot_a|images/boot.img' 'flash|vendor_boot_a|images/vendor_boot.img' 'flash|dtbo_a|images/dtbo.img'
 'flash_sparse|super|images/super.img' 'flash_sparse|cust|images/cust.img'
 'flash|vbmeta_system_a|images/vbmeta_system.img' 'flash|vbmeta_vendor_a|images/vbmeta_vendor.img' 'flash|vbmeta_a|images/vbmeta.img'
 'erase|metadata|' 'erase|userdata|' 'set_active|a|' 'reboot||'
)
validate_plan_entry() {
 case "$1" in
  'flash|boot_a|images/boot.img'|'flash|vendor_boot_a|images/vendor_boot.img'|'flash|dtbo_a|images/dtbo.img'|\
  'flash_sparse|super|images/super.img'|'flash_sparse|cust|images/cust.img'|\
  'flash|vbmeta_system_a|images/vbmeta_system.img'|'flash|vbmeta_vendor_a|images/vbmeta_vendor.img'|'flash|vbmeta_a|images/vbmeta.img'|\
  'erase|metadata|'|'erase|userdata|'|'set_active|a|'|'reboot||') ;;
  *) echo "Internal recovery plan entry rejected: $1" >&2; return 1;;
 esac
}

echo 'Official OS3.0.3.0 minimal recovery plan:'
for entry in "${plan[@]}"; do
 validate_plan_entry "$entry"
 IFS='|' read -r command arg file <<<"$entry"
 case "$command" in flash) echo "  fastboot flash $arg $file";; flash_sparse) echo "  fastboot -S 256M flash $arg $file";; reboot) echo '  fastboot reboot';; *) echo "  fastboot $command $arg";; esac
done
$EXECUTE || { echo DRY_RUN_ONLY; exit 0; }
$WIPE || { echo 'Execution requires --wipe' >&2; exit 1; }
[[ "${PEARL_CONFIRM_OFFICIAL_RECOVERY:-}" == "$ACK_VALUE" ]] || { echo 'Missing exact official recovery acknowledgement' >&2; exit 1; }
run_entry() {
 local entry="$1" command arg file status
 validate_plan_entry "$entry"
 IFS='|' read -r command arg file <<<"$entry"
 if case "$command" in
  flash) "${fastboot[@]}" flash "$arg" "$STAGING/$file";;
  flash_sparse) "${fastboot[@]}" -S 256M flash "$arg" "$STAGING/$file";;
  erase) "${fastboot[@]}" erase "$arg";;
  set_active) "${fastboot[@]}" set_active "$arg";;
  reboot) "${fastboot[@]}" reboot;;
  *) return 2;;
 esac
 then return 0
 else
  status=$?
  echo "STOP: $command $arg failed; device was not automatically rebooted" >&2
  return "$status"
 fi
}
for entry in "${plan[@]}"; do run_entry "$entry"; done
