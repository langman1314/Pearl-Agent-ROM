#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
TEMPLATE="$REPO_ROOT/magisk-module"
OUTPUT_DIR="$REPO_ROOT/artifacts/magisk"
ROOTFS=""
ZSTD_BINARY=""
ZSTD_SHA256=""
SOURCE_DATE_EPOCH="1748736000"

usage() {
  cat <<'EOF'
Usage: scripts/build-magisk-module.sh --rootfs FILE --zstd FILE --zstd-sha256 HASH [options]

Required:
  --rootfs FILE         Rootfs .tar.zst from build-hermes-rootfs.sh
  --zstd FILE           Static ARM64 zstd executable
  --zstd-sha256 HASH    Independently verified SHA-256 of that zstd binary

Optional:
  --output-dir PATH     Output directory (default: artifacts/magisk)
  -h, --help            Show this help
EOF
}

while (($#)); do
  case "$1" in
    --rootfs) ROOTFS="${2:?missing rootfs path}"; shift 2 ;;
    --zstd) ZSTD_BINARY="${2:?missing zstd path}"; shift 2 ;;
    --zstd-sha256) ZSTD_SHA256="${2:?missing zstd SHA-256}"; shift 2 ;;
    --output-dir) OUTPUT_DIR="${2:?missing output directory}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

for command_name in file readelf rsync sha256sum tar unzip wc zip zstd; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "Missing required command: $command_name" >&2
    exit 1
  }
done

[[ -n "$ROOTFS" && -f "$ROOTFS" ]] || { echo "A rootfs archive is required" >&2; exit 1; }
[[ -f "$ROOTFS.sha256" ]] || { echo "Missing rootfs sidecar: $ROOTFS.sha256" >&2; exit 1; }
[[ -n "$ZSTD_BINARY" && -f "$ZSTD_BINARY" ]] || { echo "A static ARM64 zstd binary is required" >&2; exit 1; }
[[ "$ZSTD_SHA256" =~ ^[0-9a-fA-F]{64}$ ]] || { echo "Invalid --zstd-sha256 value" >&2; exit 1; }

ROOTFS="$(realpath "$ROOTFS")"
ZSTD_BINARY="$(realpath "$ZSTD_BINARY")"
mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR="$(realpath "$OUTPUT_DIR")"
work_dir="$(mktemp -d -t pearl-magisk.XXXXXXXX)"
trap 'rm -rf "$work_dir"' EXIT

expected_rootfs_hash="$(awk 'NR==1 {print tolower($1)}' "$ROOTFS.sha256")"
actual_rootfs_hash="$(sha256sum "$ROOTFS" | awk '{print $1}')"
[[ "$actual_rootfs_hash" == "$expected_rootfs_hash" ]] || {
  echo "Rootfs SHA-256 mismatch" >&2
  exit 1
}
zstd -t "$ROOTFS" >/dev/null
rootfs_unpacked_bytes="$(zstd -dc "$ROOTFS" | wc -c | tr -d ' ')"
[[ "$rootfs_unpacked_bytes" =~ ^[1-9][0-9]*$ ]] || {
  echo "Could not determine rootfs uncompressed size" >&2
  exit 1
}
rootfs_entries="$work_dir/rootfs.entries"
zstd -dc "$ROOTFS" | tar -tf - > "$rootfs_entries"
if grep -Eqi '(^|/)(boot|init_boot|vendor_boot|recovery|dtbo|vbmeta(_system|_vendor)?|super|system|system_ext|vendor|odm|product|mi_ext|preloader|efuse|gpt|lk|abl|xbl[^/]*)(_raw)?(_[ab])?\.(img|bin|elf)$' "$rootfs_entries"; then
  echo "Unsafe partition payload detected inside rootfs archive" >&2
  exit 1
fi
actual_zstd_hash="$(sha256sum "$ZSTD_BINARY" | awk '{print $1}')"
[[ "$actual_zstd_hash" == "${ZSTD_SHA256,,}" ]] || {
  echo "zstd SHA-256 mismatch: got $actual_zstd_hash" >&2
  exit 1
}
zstd_file_info="$(file "$ZSTD_BINARY")"
printf '%s\n' "$zstd_file_info" | grep -Eqi 'ELF 64-bit LSB.*(ARM aarch64|ARM64)' || {
  echo "zstd payload is not an ARM64 ELF executable" >&2
  exit 1
}
printf '%s\n' "$zstd_file_info" | grep -Eqi '(statically linked|static-pie linked)' || {
  echo "zstd payload is dynamically linked and cannot run in the Magisk installer" >&2
  exit 1
}
if readelf -l "$ZSTD_BINARY" | grep -q 'INTERP'; then
  echo "zstd payload has a program interpreter and is not self-contained" >&2
  exit 1
fi

version="$(awk -F= '$1=="version" {print $2}' "$TEMPLATE/module.prop")"
version_code="$(awk -F= '$1=="versionCode" {print $2}' "$TEMPLATE/module.prop")"
[[ -n "$version" && -n "$version_code" ]] || { echo "Invalid module.prop" >&2; exit 1; }

stage="$work_dir/module"
rsync -a --exclude='payload/rootfs.tar.zst' --exclude='payload/zstd' \
  --exclude='payload/manifest.sha256' "$TEMPLATE/" "$stage/"
install -Dm644 "$ROOTFS" "$stage/payload/rootfs.tar.zst"
install -Dm755 "$ZSTD_BINARY" "$stage/payload/zstd"
printf '%s\n' "$rootfs_unpacked_bytes" > "$stage/payload/rootfs.unpacked-bytes"
rootfs_size_hash="$(sha256sum "$stage/payload/rootfs.unpacked-bytes" | awk '{print $1}')"
hermes_config_hash="$(sha256sum "$stage/payload/hermes-config.yaml" | awk '{print $1}')"
printf '%s  %s\n%s  %s\n%s  %s\n%s  %s\n' \
  "$actual_rootfs_hash" rootfs.tar.zst \
  "$actual_zstd_hash" zstd \
  "$rootfs_size_hash" rootfs.unpacked-bytes \
  "$hermes_config_hash" hermes-config.yaml \
  > "$stage/payload/manifest.sha256"

chmod 0755 "$stage/customize.sh" "$stage/post-fs-data.sh" "$stage/service.sh" \
  "$stage/action.sh" "$stage/uninstall.sh" "$stage/lib/common.sh" "$stage/payload/zstd"
find "$stage" -exec touch -h -d "@$SOURCE_DATE_EPOCH" {} +

artifact="$OUTPUT_DIR/pearl-agent-magisk-${version}-${version_code}.zip"
rm -f "$artifact" "$artifact.sha256"
(
  cd "$stage"
  zip -X -9 -r "$artifact" . >/dev/null
)
sha256sum "$artifact" > "$artifact.sha256"

unzip -t "$artifact" >/dev/null
for required in module.prop customize.sh post-fs-data.sh service.sh action.sh uninstall.sh \
  lib/common.sh payload/hermes-config.yaml payload/rootfs.tar.zst \
  payload/rootfs.unpacked-bytes payload/zstd payload/manifest.sha256; do
  unzip -Z1 "$artifact" | grep -Fx "$required" >/dev/null || {
    echo "Built module is missing $required" >&2
    exit 1
  }
done

# A Magisk module must not carry Android partition images or flashing scripts.
if unzip -Z1 "$artifact" | grep -Eqi '(^|/)(boot|init_boot|vendor_boot|recovery|dtbo|vbmeta(_system|_vendor)?|super|system|system_ext|vendor|odm|product|mi_ext|preloader|efuse|gpt|lk|abl|xbl[^/]*)(_raw)?(_[ab])?\.(img|bin|elf)$'; then
  echo "Unsafe partition payload detected in Magisk module" >&2
  exit 1
fi

printf 'Artifact: %s\nSHA-256: %s\n' "$artifact" "$(awk '{print $1}' "$artifact.sha256")"
