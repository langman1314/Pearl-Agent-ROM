#!/usr/bin/env bash
set -Eeuo pipefail

# Installs only the minimum OS3.0.310.0 experimental carrier set. This is not
# an AVB-verified official ROM. Dry-run is the default and never touches device
# partitions. Execution requires both --execute and an explicit environment
# acknowledgement.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"

STAGING=""
SERIAL=""
EXECUTE=false
WIPE=false
FASTBOOT_BIN="${FASTBOOT_BIN:-fastboot}"
EXPECTED_RAW_SUPER_BYTES=9126805504
EXPECTED_RAW_SUPER_SHA256=f059a5802773e794823f620c46194f4d6a8fc0d8fbc5e591c8ebd2df85a9cc43
EXPECTED_SPARSE_SUPER_SHA256=89eb4b4d0a97119c0564a72e138cd1fd2f33d58aff5bf57b6179e708b1d9db6f
ACK_VALUE=YES_I_ACCEPT_UNVERIFIED_310_AND_DATA_LOSS

declare -A EXPECTED_IMAGE_SHA256=(
  [images/boot-stock.img]=8526d0ff63b6606f4ccda6381f61921e6a56c3bcdd7fa0ace05578863f9a6f82
  [images/vendor_boot.img]=5bbb608a856c6ec2023e0acb5b4d174bf4f81ee17893708883af7453a2590654
  [images/dtbo.img]=dbc8f42ed1cd6704521d609c5bfc3db71f28bc07a0a54ca8dd0e9aa40ee944c9
  [images/vbmeta.img]=e6d3cc2daf15266bc324a5986daa475ec119c1e7672e0a187439d8b5ce644e05
  [images/vbmeta_system.img]=c739d1a67ebfd24f45dd916963de768f5378afb355350348aafd879e0f442b32
  [images/vbmeta_vendor.img]=5bafec47682ff49cd2cb3cdcb0daf811820822c23986429ffb2102d0e25b3735
  [images/super.img]=$EXPECTED_SPARSE_SUPER_SHA256
)

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
command -v cmp >/dev/null 2>&1 || { echo "cmp not found" >&2; exit 1; }
command -v python >/dev/null 2>&1 || { echo "python not found" >&2; exit 1; }

if $EXECUTE; then
  $WIPE || { echo "Execution requires --wipe" >&2; exit 1; }
  [[ "${PEARL_ACCEPT_UNVERIFIED_310:-}" == "$ACK_VALUE" ]] || {
    echo "Missing exact risk acknowledgement" >&2; exit 1;
  }
fi

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
canonical_manifest="$REPO_ROOT/manifests/experimental-310-carrier.sha256"
[[ -f "$canonical_manifest" ]] || { echo "Missing canonical carrier manifest" >&2; exit 1; }
if ! cmp -s "$STAGING/manifest.sha256" "$canonical_manifest"; then
  echo "Staging manifest does not exactly match the tracked canonical manifest" >&2
  exit 1
fi
for relative in "${!EXPECTED_IMAGE_SHA256[@]}"; do
  file="$STAGING/$relative"
  actual="$(sha256sum "$file" | awk '{print $1}')"
  [[ "$actual" == "${EXPECTED_IMAGE_SHA256[$relative]}" ]] || {
    echo "Staged image hash mismatch: $relative" >&2
    exit 1
  }
  printf '%s: OK\n' "$relative"
done

verify_sparse_super() {
  local input="$1" output="$STAGING/.super.raw.verify.$$"
  local input_for_python="$input" output_for_python="$output" script_for_python="$REPO_ROOT/scripts/unsparse-android-image.py"
  if command -v cygpath >/dev/null 2>&1; then
    input_for_python="$(cygpath -w "$input")"
    output_for_python="$(cygpath -w "$output")"
    script_for_python="$(cygpath -w "$script_for_python")"
  fi
  rm -f "$output"
  python "$script_for_python" \
    "$input_for_python" "$output_for_python" --force >/dev/null
  local bytes actual
  bytes="$(wc -c < "$output" | awk '{print $1}')"
  actual="$(sha256sum "$output" | awk '{print $1}')"
  rm -f "$output"
  [[ "$bytes" == "$EXPECTED_RAW_SUPER_BYTES" ]] || {
    echo "Expanded super size mismatch: $bytes" >&2
    return 1
  }
  [[ "$actual" == "$EXPECTED_RAW_SUPER_SHA256" ]] || {
    echo "Expanded super hash mismatch: $actual" >&2
    return 1
  }
  printf 'images/super.img: sparse and raw payload verified\n'
}
verify_sparse_super "$STAGING/images/super.img"

fastboot=("$FASTBOOT_BIN")
[[ -z "$SERIAL" ]] || fastboot+=( -s "$SERIAL" )
fastboot_image_path() {
  local path="$1"
  if [[ "${FASTBOOT_BIN,,}" == *.exe ]] && command -v cygpath >/dev/null 2>&1; then
    cygpath -w -- "$path"
  else
    printf '%s\n' "$path"
  fi
}

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
current_slot="$(getvar current-slot)"
slot_count="$(getvar slot-count)"
has_boot="$(getvar has-slot:boot)"
has_vbmeta="$(getvar has-slot:vbmeta)"
boot_size="$(getvar partition-size:boot_a)"
vbmeta_size="$(getvar partition-size:vbmeta_a)"
super_size="$(getvar partition-size:super)"
[[ "$product" == pearl ]] || { echo "Refusing product: $product" >&2; exit 1; }
[[ "$unlocked" == yes ]] || { echo "Bootloader is not unlocked" >&2; exit 1; }
[[ "$anti" =~ ^[0-9]+$ && "$anti" -le 1 ]] || { echo "Anti-rollback value rejected: $anti" >&2; exit 1; }
[[ "$slot_count" == 2 && "$current_slot" == a && "$has_boot" == yes && "$has_vbmeta" == yes ]] || {
  echo "Unexpected A/B geometry" >&2; exit 1;
}
capacity_equals() {
  local raw="${1#0x}"
  raw="${raw#0X}"
  [[ "$raw" =~ ^[0-9a-fA-F]+$ ]] || return 1
  ((16#$raw == $2))
}
capacity_equals "$boot_size" 67108864 && capacity_equals "$vbmeta_size" 8388608 || {
  echo "Unexpected boot/vbmeta capacity" >&2; exit 1;
}
super_hex="${super_size#0x}"; super_hex="${super_hex#0X}"
[[ "$super_hex" =~ ^[0-9a-fA-F]+$ ]] || { echo "Invalid super capacity" >&2; exit 1; }
(( 16#$super_hex >= EXPECTED_RAW_SUPER_BYTES )) || { echo "super partition is too small" >&2; exit 1; }

plan=(
  "flash|boot_a|images/boot-stock.img"
  "flash|vendor_boot_a|images/vendor_boot.img"
  "flash|dtbo_a|images/dtbo.img"
  "flash_sparse|super|images/super.img"
  "flash|vbmeta_system_a|images/vbmeta_system.img"
  "flash|vbmeta_vendor_a|images/vbmeta_vendor.img"
  "flash|vbmeta_a|images/vbmeta.img"
  "erase|metadata|"
  "erase|userdata|"
  "set_active|a|"
  "reboot||"
)

validate_plan_entry() {
  case "$1" in
    'flash|boot_a|images/boot-stock.img'|'flash|vendor_boot_a|images/vendor_boot.img'|\
    'flash|dtbo_a|images/dtbo.img'|'flash_sparse|super|images/super.img'|\
    'flash|vbmeta_system_a|images/vbmeta_system.img'|'flash|vbmeta_vendor_a|images/vbmeta_vendor.img'|\
    'flash|vbmeta_a|images/vbmeta.img'|'erase|metadata|'|'erase|userdata|'|\
    'set_active|a|'|'reboot||') ;;
    *) echo "Internal plan entry rejected: $1" >&2; return 1 ;;
  esac
}

printf 'Experimental 310 carrier plan (unverified AVB):\n'
for entry in "${plan[@]}"; do
  validate_plan_entry "$entry"
  IFS='|' read -r command arg file <<<"$entry"
  if [[ "$command" == flash ]]; then
    printf '  fastboot flash %s %s\n' "$arg" "$file"
  elif [[ "$command" == flash_sparse ]]; then
    printf '  fastboot -S 256M flash %s %s\n' "$arg" "$file"
  elif [[ -n "$arg" ]]; then
    printf '  fastboot %s %s\n' "$command" "$arg"
  else
    printf '  fastboot %s\n' "$command"
  fi
done

$EXECUTE || { echo "DRY_RUN_ONLY"; exit 0; }

run_entry() {
  local entry="$1" command arg file status
  validate_plan_entry "$entry"
  IFS='|' read -r command arg file <<<"$entry"
  if case "$command" in
    flash) "${fastboot[@]}" flash "$arg" "$(fastboot_image_path "$STAGING/$file")" ;;
    flash_sparse) "${fastboot[@]}" -S 256M flash "$arg" "$(fastboot_image_path "$STAGING/$file")" ;;
    erase) "${fastboot[@]}" erase "$arg" ;;
    set_active) "${fastboot[@]}" set_active "$arg" ;;
    reboot) "${fastboot[@]}" reboot ;;
    *) return 2 ;;
  esac
  then
    return 0
  else
    status=$?
    echo "STOP: $command $arg failed; device was not automatically rebooted" >&2
    return "$status"
  fi
}
for entry in "${plan[@]}"; do
  run_entry "$entry"
done
