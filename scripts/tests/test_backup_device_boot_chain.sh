#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd -P)"
BACKUP="$REPO_ROOT/scripts/backup-device-boot-chain.sh"
work="$(mktemp -d -t pearl-boot-backup-test.XXXXXXXX)"
trap 'rm -rf "$work"' EXIT

cat > "$work/fastboot" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
if [[ "${1:-}" == "devices" ]]; then
  printf 'PEARL123\tfastboot\n'
  exit 0
fi
[[ "${1:-}" == "-s" ]] && shift 2
case "${1:-}" in
  getvar)
    case "${2:-}" in
      product) value=pearl ;;
      current-slot) value=a ;;
      slot-count) value=2 ;;
      unlocked) value=yes ;;
      has-slot:boot|has-slot:vbmeta) value=yes ;;
      partition-size:boot_a|partition-size:boot_b) value=100000 ;;
      partition-size:vbmeta_a|partition-size:vbmeta_b) value=10000 ;;
      *) exit 1 ;;
    esac
    printf '%s: %s\nFinished. Total time: 0.001s\n' "$2" "$value" >&2
    ;;
  fetch)
    partition="${2:?}"
    output="${3:?}"
    case "$partition" in
      boot_*) printf 'ANDROID!' > "$output"; truncate -s 1048576 "$output" ;;
      vbmeta_*) printf 'AVB0' > "$output"; truncate -s 65536 "$output" ;;
      *) exit 1 ;;
    esac
    ;;
  *) exit 9 ;;
esac
EOF
chmod 0755 "$work/fastboot"

FASTBOOT_BIN="$work/fastboot" bash "$BACKUP" \
  --serial PEARL123 --output-dir "$work/evidence"
(
  cd "$work/evidence"
  sha256sum -c manifest.sha256 >/dev/null
)
grep -Fx 'device_mutation_commands=none' "$work/evidence/summary.txt" >/dev/null
[[ "$(wc -c < "$work/evidence/boot_a.img" | tr -d ' ')" == 1048576 ]]
[[ "$(wc -c < "$work/evidence/vbmeta_b.img" | tr -d ' ')" == 65536 ]]
if cut -f3- "$work/evidence/commands.tsv" \
    | grep -Eqi '(^|[[:space:]])(flash|erase|format|boot|reboot|set_active|update)([[:space:]]|$)'; then
  echo "Backup emitted a mutating command" >&2
  exit 1
fi
if FASTBOOT_BIN="$work/fastboot" bash "$BACKUP" \
    --serial PEARL123 --output-dir "$work/evidence" >/dev/null 2>&1; then
  echo "Backup overwrote existing evidence" >&2
  exit 1
fi
printf 'DEVICE_BOOT_CHAIN_BACKUP_TEST_OK\n'
