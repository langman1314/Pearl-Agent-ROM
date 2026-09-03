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
if FASTBOOT_BIN="$work/fastboot" bash "$FLASHER" \
  --staging "$work/staging" --serial PEARL123 > "$work/rejected.txt" 2>&1; then
  echo "tampered/incomplete staging was accepted" >&2
  exit 1
fi
grep -Eqi 'canonical|manifest|mismatch|hash|sparse|expanded' "$work/rejected.txt" || {
  echo "rejection did not identify the artifact gate" >&2
  exit 1
}
if [[ -s "$MOCK_LOG" ]]; then
  echo "rejected staging contacted fastboot" >&2
  exit 1
fi
printf 'EXPERIMENTAL_310_FLASHER_TEST_OK\n'
