#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd -P)"
SCRIPT="$REPO_ROOT/scripts/switch-experimental-310-boot.sh"
work="$(mktemp -d -t pearl-310-boot-switch-test.XXXXXXXX)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/staging/images"
printf stock > "$work/staging/images/boot-stock.img"
printf magisk > "$work/staging/images/boot-magisk.img"
cat > "$work/sha256sum" <<'EOF'
#!/usr/bin/env bash
case "$1" in
  *boot-stock.img) printf '8526d0ff63b6606f4ccda6381f61921e6a56c3bcdd7fa0ace05578863f9a6f82  %s\n' "$1" ;;
  *boot-magisk.img) printf 'f3cb3ca2353315bc96694cd17d400b71108864f28ff4ef8a7260c50f69572005  %s\n' "$1" ;;
  *) exit 1 ;;
esac
EOF
cat > "$work/fastboot" <<'EOF'
#!/usr/bin/env bash
printf '%q ' "$@" >> "${MOCK_LOG:?}"; printf '\n' >> "$MOCK_LOG"
if [[ "${1:-}" == devices ]]; then printf 'PEARL123\tfastboot\n'; exit 0; fi
[[ "${1:-}" == -s ]] && shift 2
if [[ "${1:-}" == getvar ]]; then
 case "${2:-}" in
  product) v=pearl;; unlocked) v=yes;; current-slot) v=a;; partition-size:boot_a) v=0x4000000;; *) exit 1;;
 esac
 printf '%s: %s\n' "$2" "$v" >&2
fi
exit 0
EOF
chmod 0755 "$work/sha256sum" "$work/fastboot"
export PATH="$work:$PATH" MOCK_LOG="$work/log"
: > "$MOCK_LOG"
FASTBOOT_BIN="$work/fastboot" bash "$SCRIPT" --staging "$work/staging" --serial PEARL123 --mode magisk > "$work/dry.txt"
grep -Fx DRY_RUN_ONLY "$work/dry.txt" >/dev/null
if grep -Eq ' flash | set_active | reboot' "$MOCK_LOG"; then echo 'dry-run mutated device' >&2; exit 1; fi
if FASTBOOT_BIN="$work/fastboot" bash "$SCRIPT" --staging "$work/staging" --serial PEARL123 --mode magisk --execute >/dev/null 2>&1; then
 echo 'missing Magisk acknowledgement was accepted' >&2; exit 1
fi
: > "$MOCK_LOG"
PEARL_ACCEPT_MAGISK_BOOT=YES_FLASH_FRESH_MAGISK_TO_BOOT_A FASTBOOT_BIN="$work/fastboot" \
 bash "$SCRIPT" --staging "$work/staging" --serial PEARL123 --mode magisk --execute >/dev/null
grep -F 'flash boot_a' "$MOCK_LOG" >/dev/null
grep -F 'set_active a' "$MOCK_LOG" >/dev/null
grep -F 'reboot' "$MOCK_LOG" >/dev/null
if grep -Eqi 'vbmeta|super|preloader|efuse|boot_b|erase' "$MOCK_LOG"; then echo 'boot switch escaped allowlist' >&2; exit 1; fi
: > "$MOCK_LOG"
PEARL_CONFIRM_STOCK_RESTORE=YES_RESTORE_STOCK_BOOT_A FASTBOOT_BIN="$work/fastboot" \
 bash "$SCRIPT" --staging "$work/staging" --serial PEARL123 --mode stock --execute >/dev/null
grep -F 'boot-stock.img' "$MOCK_LOG" >/dev/null
printf 'EXPERIMENTAL_310_BOOT_SWITCH_TEST_OK\n'
