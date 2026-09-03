#!/usr/bin/env bash
set -Eeuo pipefail

# First-use secret provisioning for the data-only Hermes runtime. The API key is
# read without echo and transferred over adb stdin; it is never placed in an
# adb/su command argument, a host file, or console output.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

SERIAL=""
EXECUTE=false
ADB_BIN="${ADB_BIN:-adb}"
ACK=YES_PROVISION_HERMES_DEEPSEEK_SECRET
TARGET=/data/adb/pearl-agent/data/hermes-home/.env

usage() {
  cat <<'EOF'
Usage: scripts/provision-hermes-after-root.sh [--serial SERIAL] [--execute]

Run this only after the approved Pearl Agent Magisk module has been installed
and the phone has rebooted. Dry-run is the default. Execution requires:

  PEARL_ACCEPT_SECRET_PROVISION=YES_PROVISION_HERMES_DEEPSEEK_SECRET

Execution prompts for DEEPSEEK_API_KEY without echo, refuses to overwrite a
non-empty Hermes .env, writes it atomically through adb stdin with mode 0600,
and reboots so the supervised bridge starts with the new credential.
EOF
}

while (($#)); do
  case "$1" in
    --serial) SERIAL="${2:?missing serial}"; shift 2 ;;
    --execute) EXECUTE=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

adb=("$ADB_BIN")
[[ -z "$SERIAL" ]] || adb+=( -s "$SERIAL" )
state="$("${adb[@]}" get-state 2>/dev/null)" || {
  echo "Authorized ADB device unavailable" >&2
  exit 1
}
[[ "$state" == device ]] || { echo "ADB state is not device: $state" >&2; exit 1; }
product="$("${adb[@]}" shell getprop ro.product.device | tr -d '\r')"
sdk="$("${adb[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$product" == pearl ]] || { echo "Refusing non-pearl Android device" >&2; exit 1; }
[[ "$sdk" == 36 ]] || { echo "Experimental carrier requires Android SDK 36, got $sdk" >&2; exit 1; }
root_id="$("${adb[@]}" shell su -c id | tr -d '\r')" || {
  echo "Magisk root unavailable or not granted to ADB shell" >&2
  exit 1
}
[[ "$root_id" == uid=0* ]] || { echo "Root identity rejected: $root_id" >&2; exit 1; }
"${adb[@]}" shell su -c \
  'test -f /data/adb/modules/pearl_agent/module.prop && test -d /data/adb/pearl-agent/data/hermes-home' \
  >/dev/null || {
    echo "Verified Pearl Agent module/data layout is unavailable" >&2
    exit 1
  }

printf 'Hermes secret provisioning plan:\n'
printf '  target: %s (root-only mode 0600)\n' "$TARGET"
printf '  transport: hidden prompt -> adb stdin -> atomic on-device file\n'
printf '  overwrite policy: refuse an existing non-empty .env\n'
printf '  completion: verify mode/shape without printing secret, then reboot\n'
$EXECUTE || { echo "DRY_RUN_ONLY"; exit 0; }
[[ "${PEARL_ACCEPT_SECRET_PROVISION:-}" == "$ACK" ]] || {
  echo "Missing exact secret-provisioning acknowledgement" >&2
  exit 1
}

printf 'DEEPSEEK_API_KEY (input hidden): ' >&2
IFS= read -r -s deepseek_key
printf '\n' >&2
[[ ${#deepseek_key} -ge 20 && ${#deepseek_key} -le 512 ]] || {
  unset deepseek_key
  echo "DeepSeek key length is outside the accepted 20..512 byte range" >&2
  exit 1
}
case "$deepseek_key" in
  *[[:space:]]*)
    unset deepseek_key
    echo "DeepSeek key must not contain whitespace" >&2
    exit 1
    ;;
esac

remote_write='target=/data/adb/pearl-agent/data/hermes-home/.env; tmp=$target.new.$$; umask 077; if [ -s "$target" ]; then echo "Hermes .env is already non-empty; refusing overwrite" >&2; exit 42; fi; mkdir -p "${target%/*}"; trap '\''rm -f "$tmp"'\'' EXIT HUP INT TERM; cat > "$tmp"; chmod 0600 "$tmp"; mv -f "$tmp" "$target"; trap - EXIT HUP INT TERM'
if ! printf 'DEEPSEEK_API_KEY=%s\n' "$deepseek_key" | "${adb[@]}" shell su -c "$remote_write"; then
  unset deepseek_key
  echo "On-device secret write failed; no successful provisioning was recorded" >&2
  exit 1
fi
unset deepseek_key

verification="$("${adb[@]}" shell su -c 'target=/data/adb/pearl-agent/data/hermes-home/.env; mode=$(stat -c %a "$target") || exit 1; lines=$(grep -c "^DEEPSEEK_API_KEY=[^[:space:]][^[:space:]]*$" "$target") || exit 1; bytes=$(wc -c < "$target" | tr -d " "); printf "mode=%s key_lines=%s bytes=%s\\n" "$mode" "$lines" "$bytes"' | tr -d '\r')" || {
  echo "Secret was written but its non-secret metadata could not be verified; inspect before reboot" >&2
  exit 1
}
case "$verification" in
  mode=600\ key_lines=1\ bytes=*) ;;
  *) echo "Secret metadata verification rejected: $verification" >&2; exit 1 ;;
esac
printf 'Hermes credential provisioned: %s\n' "$verification"
"${adb[@]}" reboot
echo "REBOOT_REQUESTED"
