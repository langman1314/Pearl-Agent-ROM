#!/usr/bin/env bash
set -Eeuo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd -P)"
SCRIPT="$REPO_ROOT/scripts/restore-official-minimal.sh"
work="$(mktemp -d -t pearl-official-restore-test.XXXXXXXX)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/staging/images"
for n in boot cust dtbo super vbmeta vbmeta_system vbmeta_vendor vendor_boot; do printf x > "$work/staging/images/$n.img"; done
native_super="$(cygpath -w "$work/staging/images/super.img")"; native_cust="$(cygpath -w "$work/staging/images/cust.img")"
python - "$native_super" "$native_cust" <<'PY'
import struct,sys
for p,blocks in ((sys.argv[1],2228224),(sys.argv[2],173056)):
 open(p,'wb').write(struct.pack('<I4H4I',0xED26FF3A,1,0,28,12,4096,blocks,0,0))
PY
cat > "$work/sha256sum" <<'EOF'
#!/usr/bin/env bash
case "$1" in
 *vendor_boot.img) h=5bbb608a856c6ec2023e0acb5b4d174bf4f81ee17893708883af7453a2590654;;
 *boot.img) h=8526d0ff63b6606f4ccda6381f61921e6a56c3bcdd7fa0ace05578863f9a6f82;;
 *cust.img) h=ed712c0d3f2bc1a56bccd9583cba47037f09476027bdcf47178757eab2f790c1;;
 *dtbo.img) h=dbc8f42ed1cd6704521d609c5bfc3db71f28bc07a0a54ca8dd0e9aa40ee944c9;;
 *super.img) h=7ff1bb372c7ddf8c1debc6c0784a972426f16746425835dec967b266fe827026;;
 *vbmeta_system.img) h=4c3601c2666a43511142681dfb4eaa42de31fe3b32412cd9df39ea41a501ec39;;
 *vbmeta_vendor.img) h=a7c26870259c98ba2c7e1473dd6839b03e8eb7cea759db4789a592972b5a9e56;;
 *vbmeta.img) h=0dd9695425802eaf9b8bb35f2a94c41e7b0aeedff856ba9e70c860f206b82dfc;;
 *) exit 1;; esac
printf '%s  %s\n' "$h" "$1"
EOF
cat > "$work/fastboot" <<'EOF'
#!/usr/bin/env bash
printf '%s ' "$@" >> "${MOCK_LOG:?}"; printf '\n' >> "$MOCK_LOG"
if [[ "${1:-}" == devices ]]; then printf 'PEARL123\tfastboot\n'; exit 0; fi
[[ "${1:-}" == -s ]] && shift 2
if [[ "${1:-}" == getvar ]]; then
 case "${2:-}" in
 product) v=pearl;; unlocked) v=yes;; anti) v=1;; current-slot) v=a;; slot-count) v=2;;
 has-slot:cust) v=no;; has-slot:*) v=yes;;
 partition-size:boot_a) v=0x4000000;; partition-size:vbmeta_a) v=0x800000;;
 partition-size:super) v=0x220000000;; partition-size:cust) v=0x2a400000;; *) exit 1;; esac
 printf '%s: %s\n' "$2" "$v" >&2
fi
exit 0
EOF
chmod 0755 "$work/sha256sum" "$work/fastboot"
export PATH="$work:$PATH" MOCK_LOG="$work/log"; : > "$MOCK_LOG"
FASTBOOT_BIN="$work/fastboot" bash "$SCRIPT" --staging "$work/staging" --serial PEARL123 > "$work/dry.txt"
grep -Fx DRY_RUN_ONLY "$work/dry.txt" >/dev/null
if grep -Eq ' flash | erase | set_active | reboot' "$MOCK_LOG"; then echo 'dry-run mutated device' >&2; exit 1; fi
if FASTBOOT_BIN="$work/fastboot" bash "$SCRIPT" --staging "$work/staging" --serial PEARL123 --wipe --execute >/dev/null 2>&1; then echo 'missing recovery acknowledgement accepted' >&2; exit 1; fi
: > "$MOCK_LOG"
PEARL_CONFIRM_OFFICIAL_RECOVERY=YES_RESTORE_OFFICIAL_OS3_0_3_0_AND_WIPE FASTBOOT_BIN="$work/fastboot" \
 bash "$SCRIPT" --staging "$work/staging" --serial PEARL123 --wipe --execute >/dev/null
for expected in 'flash boot_a' 'flash vendor_boot_a' 'flash dtbo_a' '-S 256M flash super' '-S 256M flash cust' 'flash vbmeta_system_a' 'flash vbmeta_vendor_a' 'flash vbmeta_a' 'erase metadata' 'erase userdata' 'set_active a' 'reboot'; do
 grep -F -- "$expected" "$MOCK_LOG" >/dev/null || { echo "missing expected recovery command: $expected" >&2; exit 1; }
done
if grep -Eqi 'preloader|efuse|gpt|boot_b|vbmeta_b|flash_all|flash (lk|tee|md1img|logo|rescue)' "$MOCK_LOG"; then echo 'official recovery escaped allowlist' >&2; exit 1; fi
printf 'OFFICIAL_MINIMAL_RECOVERY_TEST_OK\n'
