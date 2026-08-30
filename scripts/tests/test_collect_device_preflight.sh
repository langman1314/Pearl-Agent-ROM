#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd -P)"
COLLECTOR="$REPO_ROOT/scripts/collect-device-preflight.sh"
work="$(mktemp -d -t pearl-preflight-test.XXXXXXXX)"
trap 'rm -rf "$work"' EXIT

cat > "$work/adb" <<'EOF'
#!/usr/bin/env bash
set -u
[[ "${1:-}" == "-s" ]] && shift 2
case "${1:-}" in
  get-state) printf 'device\n' ;;
  shell)
    shift
    if [[ "${1:-}" == "getprop" ]]; then
      case "${2:-}" in
        ro.product.device|ro.product.vendor.device|ro.product.system.device) printf 'pearl\n' ;;
        ro.build.version.sdk) printf '35\n' ;;
        *) printf 'mock-value\n' ;;
      esac
    elif [[ "${1:-}" == "ls" ]]; then
      printf '%s\n' "$@"
    else
      printf 'mock-shell-output\n'
    fi
    ;;
  *) exit 9 ;;
esac
EOF

cat > "$work/fastboot" <<'EOF'
#!/usr/bin/env bash
set -u
if [[ "${1:-}" == "devices" ]]; then
  printf 'PEARL123\tfastboot\n'
  exit 0
fi
[[ "${1:-}" == "-s" ]] && shift 2
if [[ "${1:-}" == "getvar" ]]; then
  printf '(bootloader) %s: mock-value\n' "${2:-}" >&2
  printf 'Finished. Total time: 0.001s\n' >&2
  exit 0
fi
exit 9
EOF
chmod 0755 "$work/adb" "$work/fastboot"

ADB_BIN="$work/adb" bash "$COLLECTOR" \
  --mode adb --serial PEARL123 --output-dir "$work/adb-evidence"
FASTBOOT_BIN="$work/fastboot" bash "$COLLECTOR" \
  --mode fastboot --serial PEARL123 --output-dir "$work/fastboot-evidence"

for evidence in "$work/adb-evidence" "$work/fastboot-evidence"; do
  (
    cd "$evidence"
    sha256sum -c manifest.sha256 >/dev/null
  )
  grep -Fx 'mutation_commands=none' "$evidence/summary.txt" >/dev/null
  grep -Fx 'partition_contents_collected=false' "$evidence/summary.txt" >/dev/null
  if tail -n +2 "$evidence/commands.tsv" | cut -f3- \
      | grep -Eqi '(^|[[:space:]])(flash|erase|format|reboot|set_active|update|dd|push|remount)([[:space:]]|$)'; then
    echo "Collector emitted a mutating command" >&2
    exit 1
  fi
done

grep -F 'shell getprop ro.product.device' "$work/adb-evidence/commands.tsv" >/dev/null
grep -Fx '/dev/block/by-name/boot' "$work/adb-evidence/adb-by-name-boot.txt" >/dev/null
if grep -Fq ':/' "$work/adb-evidence/adb-by-name-boot.txt"; then
  echo "MSYS rewrote an Android absolute path" >&2
  exit 1
fi
grep -F 'getvar current-slot' "$work/fastboot-evidence/commands.tsv" >/dev/null

if ADB_BIN="$work/adb" bash "$COLLECTOR" \
    --mode adb --serial PEARL123 --output-dir "$work/adb-evidence" >/dev/null 2>&1; then
  echo "Collector overwrote existing evidence" >&2
  exit 1
fi

printf 'DEVICE_PREFLIGHT_COLLECTOR_TEST_OK\n'
