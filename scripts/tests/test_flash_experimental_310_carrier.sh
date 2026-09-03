#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd -P)"
FLASHER="$REPO_ROOT/scripts/flash-experimental-310-carrier.sh"
work="$(mktemp -d -t pearl-310-flasher-test.XXXXXXXX)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/staging/images"
for name in boot-stock.img vendor_boot.img dtbo.img vbmeta.img vbmeta_system.img vbmeta_vendor.img; do
  printf '%s' "$name" > "$work/staging/images/$name"
done
super_windows="$(cygpath -w "$work/staging/images/super.img")"
python - "$super_windows" <<'PY'
import struct,sys
raw_bytes=9126805504
block=4096
header=struct.pack('<I4H4I',0xED26FF3A,1,0,28,12,block,raw_bytes//block,1,0)
chunk=struct.pack('<2H2I',0xCAC3,0,raw_bytes//block,12)
open(sys.argv[1],'wb').write(header+chunk)
PY
(
  cd "$work/staging"
  sha256sum images/* > manifest.sha256
)
cat > "$work/fastboot" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%q ' "$@" >> "${MOCK_LOG:?}"
printf '\n' >> "$MOCK_LOG"
if [[ "${1:-}" == devices ]]; then printf 'PEARL123\tfastboot\n'; exit 0; fi
[[ "${1:-}" == -s ]] && shift 2
if [[ "${1:-}" == getvar ]]; then
  case "${2:-}" in
    product) value=pearl ;;
    unlocked) value=yes ;;
    anti) value=1 ;;
    slot-count) value=2 ;;
    has-slot:boot|has-slot:vbmeta) value=yes ;;
    partition-size:boot_a) value=0x4000000 ;;
    partition-size:vbmeta_a) value=0x800000 ;;
    partition-size:super) value=0x240000000 ;;
    *) exit 1 ;;
  esac
  printf '%s: %s\n' "$2" "$value" >&2
  exit 0
fi
exit 0
EOF
chmod 0755 "$work/fastboot"
export MOCK_LOG="$work/commands.log"
: > "$MOCK_LOG"
FASTBOOT_BIN="$work/fastboot" bash "$FLASHER" \
  --staging "$work/staging" --serial PEARL123 > "$work/dry-run.txt"
grep -Fx 'DRY_RUN_ONLY' "$work/dry-run.txt" >/dev/null
if grep -Eq ' flash | erase | set_active | reboot' "$MOCK_LOG"; then
  echo "dry-run emitted a mutation" >&2
  exit 1
fi
if FASTBOOT_BIN="$work/fastboot" bash "$FLASHER" \
  --staging "$work/staging" --serial PEARL123 --wipe --execute >/dev/null 2>&1; then
  echo "execute succeeded without acknowledgement" >&2
  exit 1
fi
: > "$MOCK_LOG"
PEARL_ACCEPT_UNVERIFIED_310=YES_I_ACCEPT_UNVERIFIED_310_AND_DATA_LOSS \
FASTBOOT_BIN="$work/fastboot" bash "$FLASHER" \
  --staging "$work/staging" --serial PEARL123 --wipe --execute >/dev/null
for expected in \
  'flash boot_a' 'flash vendor_boot_a' 'flash dtbo_a' 'flash super' \
  'flash vbmeta_system_a' 'flash vbmeta_vendor_a' 'flash vbmeta_a' \
  'erase metadata' 'erase userdata' 'set_active a' 'reboot'; do
  grep -F "$expected" "$MOCK_LOG" >/dev/null || {
    echo "missing expected command: $expected" >&2; exit 1;
  }
done
if grep -Eqi 'preloader|efuse|gpt|boot_b|vbmeta_b|flash_all|flash (lk|tee|md1img|logo|cust|rescue)' "$MOCK_LOG"; then
  echo "prohibited partition appeared in execution" >&2
  exit 1
fi
printf 'EXPERIMENTAL_310_FLASHER_TEST_OK\n'
