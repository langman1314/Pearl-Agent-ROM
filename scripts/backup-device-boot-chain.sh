#!/usr/bin/env bash
set -Eeuo pipefail

# Read-only fastboot backup of the A/B boot and vbmeta chain. The command list
# is fixed: devices, getvar and fetch only. It never reboots or mutates a slot.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

OUTPUT_DIR=""
SERIAL=""
FASTBOOT_BIN="${FASTBOOT_BIN:-fastboot}"

usage() {
  cat <<'EOF'
Usage: scripts/backup-device-boot-chain.sh --output-dir PATH [--serial SERIAL]

Requires a bootloader that implements read-only `fastboot fetch`. The output
path must not exist. No flash/erase/format/boot/reboot/set_active command is
present or accepted.
EOF
}

while (($#)); do
  case "$1" in
    --output-dir) OUTPUT_DIR="${2:?missing output directory}"; shift 2 ;;
    --serial) SERIAL="${2:?missing serial}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

[[ -n "$OUTPUT_DIR" ]] || { echo "--output-dir is required" >&2; exit 2; }
[[ ! -e "$OUTPUT_DIR" ]] || { echo "Refusing to overwrite backup: $OUTPUT_DIR" >&2; exit 1; }
command -v "$FASTBOOT_BIN" >/dev/null 2>&1 || {
  echo "Required host tool not found: $FASTBOOT_BIN" >&2
  exit 1
}

parent="$(dirname -- "$OUTPUT_DIR")"
mkdir -p "$parent"
parent="$(cd -- "$parent" && pwd -P)"
OUTPUT_DIR="$parent/$(basename -- "$OUTPUT_DIR")"
stage="$OUTPUT_DIR.partial.$$"
rm -rf "$stage"
mkdir "$stage"
chmod 0700 "$stage" 2>/dev/null || true
trap 'rm -rf "$stage"' EXIT

fastboot=("$FASTBOOT_BIN")
[[ -z "$SERIAL" ]] || fastboot+=( -s "$SERIAL" )

devices="$("$FASTBOOT_BIN" devices 2>&1)" || {
  echo "fastboot devices failed: $devices" >&2
  exit 1
}
if [[ -n "$SERIAL" ]]; then
  awk -v serial="$SERIAL" '$1 == serial && $2 == "fastboot" { found=1 } END { exit !found }' \
    <<<"$devices" || { echo "Requested fastboot serial is not present" >&2; exit 1; }
else
  count="$(awk '$2 == "fastboot" { count++ } END { print count+0 }' <<<"$devices")"
  [[ "$count" == "1" ]] || {
    echo "Expected exactly one fastboot device without --serial; found $count" >&2
    exit 1
  }
fi

commands_file="$stage/commands.tsv"
printf 'name\texit_code\tcommand\n' > "$commands_file"

record_command() {
  name="$1"
  status="$2"
  shift 2
  {
    printf '%s\t%s\t' "$name" "$status"
    printf '%q ' "$@"
    printf '\n'
  } >> "$commands_file"
}

getvar_value() {
  variable="$1"
  output_file="$stage/getvar-${variable//:/-}.txt"
  set +e
  "${fastboot[@]}" getvar "$variable" > "$output_file" 2>&1
  status=$?
  set -e
  record_command "getvar-$variable" "$status" "${fastboot[@]}" getvar "$variable"
  [[ "$status" == "0" ]] || {
    echo "fastboot getvar $variable failed" >&2
    return 1
  }
  awk -v key="$variable" '
    {
      line=$0
      sub(/^\(bootloader\)[[:space:]]*/, "", line)
      prefix=key ":[[:space:]]*"
      if (line ~ ("^" prefix)) {
        sub("^" prefix, "", line)
        value=line
      }
    }
    END {
      sub(/[[:space:]]+$/, "", value)
      if (value == "") exit 1
      print value
    }
  ' "$output_file"
}

parse_hex_size() {
  value="${1#0x}"
  value="${value#0X}"
  [[ "$value" =~ ^[0-9a-fA-F]+$ ]] || return 1
  printf '%s\n' "$((16#$value))"
}

product="$(getvar_value product)"
current_slot="$(getvar_value current-slot)"
slot_count="$(getvar_value slot-count)"
unlocked="$(getvar_value unlocked)"
has_boot="$(getvar_value has-slot:boot)"
has_vbmeta="$(getvar_value has-slot:vbmeta)"
[[ "$product" == "pearl" ]] || { echo "Refusing non-pearl product: $product" >&2; exit 1; }
[[ "$current_slot" == "a" || "$current_slot" == "b" ]] || { echo "Invalid current slot" >&2; exit 1; }
[[ "$slot_count" == "2" ]] || { echo "Expected two slots, got $slot_count" >&2; exit 1; }
[[ "$unlocked" == "yes" ]] || { echo "Bootloader does not report unlocked=yes" >&2; exit 1; }
[[ "$has_boot" == "yes" && "$has_vbmeta" == "yes" ]] || {
  echo "boot/vbmeta are not both reported as slotted" >&2
  exit 1
}

boot_a_bytes="$(parse_hex_size "$(getvar_value partition-size:boot_a)")" || exit 1
boot_b_bytes="$(parse_hex_size "$(getvar_value partition-size:boot_b)")" || exit 1
vbmeta_a_bytes="$(parse_hex_size "$(getvar_value partition-size:vbmeta_a)")" || exit 1
vbmeta_b_bytes="$(parse_hex_size "$(getvar_value partition-size:vbmeta_b)")" || exit 1
[[ "$boot_a_bytes" == "$boot_b_bytes" && "$boot_a_bytes" -ge 1048576 ]] || {
  echo "Boot slot capacities are inconsistent or implausible" >&2
  exit 1
}
[[ "$vbmeta_a_bytes" == "$vbmeta_b_bytes" && "$vbmeta_a_bytes" -ge 65536 ]] || {
  echo "vbmeta slot capacities are inconsistent or implausible" >&2
  exit 1
}

fetch_partition() {
  partition="$1"
  expected_bytes="$2"
  partial="$stage/$partition.img.partial"
  final="$stage/$partition.img"
  set +e
  "${fastboot[@]}" fetch "$partition" "$partial" > "$stage/fetch-$partition.txt" 2>&1
  status=$?
  set -e
  record_command "fetch-$partition" "$status" "${fastboot[@]}" fetch "$partition" "$partial"
  if [[ "$status" != "0" ]]; then
    echo "Device does not support read-only fetch for $partition" >&2
    return 1
  fi
  actual_bytes="$(wc -c < "$partial" | tr -d ' ')"
  [[ "$actual_bytes" == "$expected_bytes" ]] || {
    echo "Fetched $partition size mismatch: expected=$expected_bytes actual=$actual_bytes" >&2
    return 1
  }
  case "$partition" in
    boot_*) [[ "$(head -c 8 "$partial")" == "ANDROID!" ]] || {
      echo "$partition lacks ANDROID! header" >&2; return 1;
    } ;;
    vbmeta_*) [[ "$(head -c 4 "$partial")" == "AVB0" ]] || {
      echo "$partition lacks AVB0 header" >&2; return 1;
    } ;;
  esac
  mv "$partial" "$final"
  chmod 0600 "$final" 2>/dev/null || true
}

fetch_partition boot_a "$boot_a_bytes"
fetch_partition boot_b "$boot_b_bytes"
fetch_partition vbmeta_a "$vbmeta_a_bytes"
fetch_partition vbmeta_b "$vbmeta_b_bytes"

{
  printf 'product=%s\n' "$product"
  printf 'current_slot=%s\n' "$current_slot"
  printf 'slot_count=%s\n' "$slot_count"
  printf 'boot_slot_bytes=%s\n' "$boot_a_bytes"
  printf 'vbmeta_slot_bytes=%s\n' "$vbmeta_a_bytes"
  printf 'device_mutation_commands=none\n'
} > "$stage/summary.txt"
chmod 0600 "$commands_file" "$stage/summary.txt" 2>/dev/null || true
(
  cd "$stage"
  find . -maxdepth 1 -type f ! -name manifest.sha256 -print0 \
    | sort -z \
    | xargs -0 sha256sum > manifest.sha256
)
chmod 0600 "$stage/manifest.sha256" 2>/dev/null || true
mv "$stage" "$OUTPUT_DIR"
trap - EXIT
printf 'Read-only boot-chain backup: %s\n' "$OUTPUT_DIR"
