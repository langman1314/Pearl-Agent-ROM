#!/usr/bin/env bash
set -Eeuo pipefail

# Build the Debian ARM64 userspace used by the Pearl Magisk module.
# This script must run on a Linux host (native Linux or WSL2) as root.

readonly EXPECTED_HERMES_COMMIT="a2e19d484cb5591df8dafe667c93345b62d9bf06"
readonly DEBIAN_SUITE="bookworm"
readonly DEBIAN_SNAPSHOT="20250601T000000Z"
readonly SOURCE_DATE_EPOCH="1748736000"
readonly UV_VERSION="0.8.11"

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
HERMES_SOURCE="$REPO_ROOT/.tools/hermes-agent-upstream"
BRIDGE_SOURCE="$REPO_ROOT/hermes-bridge"
OUTPUT_DIR="$REPO_ROOT/artifacts/rootfs"
KEEP_ROOTFS=0

usage() {
  cat <<'EOF'
Usage: sudo scripts/build-hermes-rootfs.sh [options]

Options:
  --hermes-source PATH  Verified Hermes checkout (default: .tools/hermes-agent-upstream)
  --output-dir PATH     Artifact directory (default: artifacts/rootfs)
  --keep-rootfs         Preserve the unpacked build tree for inspection
  -h, --help            Show this help
EOF
}

while (($#)); do
  case "$1" in
    --hermes-source)
      HERMES_SOURCE="${2:?missing path after --hermes-source}"
      shift 2
      ;;
    --output-dir)
      OUTPUT_DIR="${2:?missing path after --output-dir}"
      shift 2
      ;;
    --keep-rootfs)
      KEEP_ROOTFS=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

for command_name in git mmdebstrap qemu-aarch64-static rsync tar zstd sha256sum; do
  require_command "$command_name"
done

if [[ "${EUID:-$(id -u)}" -ne 0 ]]; then
  echo "Run as root so mmdebstrap and chroot can preserve ownership." >&2
  exit 1
fi

HERMES_SOURCE="$(realpath "$HERMES_SOURCE")"
BRIDGE_SOURCE="$(realpath "$BRIDGE_SOURCE")"
mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR="$(realpath "$OUTPUT_DIR")"

actual_commit="$(git -C "$HERMES_SOURCE" rev-parse HEAD)"
if [[ "$actual_commit" != "$EXPECTED_HERMES_COMMIT" ]]; then
  echo "Hermes commit mismatch: expected $EXPECTED_HERMES_COMMIT, got $actual_commit" >&2
  exit 1
fi
if [[ -n "$(git -C "$HERMES_SOURCE" status --porcelain --untracked-files=no)" ]]; then
  echo "Hermes tracked files are dirty; refusing a non-reproducible rootfs build." >&2
  exit 1
fi
if [[ ! -f "$HERMES_SOURCE/uv.lock" || ! -f "$BRIDGE_SOURCE/pyproject.toml" ]]; then
  echo "Hermes uv.lock or bridge pyproject.toml is missing." >&2
  exit 1
fi

work_dir="$(mktemp -d -t pearl-rootfs.XXXXXXXX)"
rootfs="$work_dir/rootfs"
artifact_base="pearl-hermes-bookworm-arm64-${EXPECTED_HERMES_COMMIT:0:12}"
artifact="$OUTPUT_DIR/$artifact_base.tar.zst"

cleanup() {
  if mountpoint -q "$rootfs/proc" 2>/dev/null; then umount -l "$rootfs/proc" || true; fi
  if mountpoint -q "$rootfs/sys" 2>/dev/null; then umount -l "$rootfs/sys" || true; fi
  if mountpoint -q "$rootfs/dev/pts" 2>/dev/null; then umount -l "$rootfs/dev/pts" || true; fi
  if mountpoint -q "$rootfs/dev" 2>/dev/null; then umount -l "$rootfs/dev" || true; fi
  if [[ "$KEEP_ROOTFS" -eq 1 ]]; then
    echo "Preserved build tree: $work_dir"
  else
    rm -rf --one-file-system "$work_dir"
  fi
}
trap cleanup EXIT

echo "[1/7] Creating Debian $DEBIAN_SUITE ARM64 minbase from snapshot $DEBIAN_SNAPSHOT"
mmdebstrap \
  --mode=root \
  --variant=minbase \
  --architectures=arm64 \
  --components=main \
  --aptopt='Acquire::Check-Valid-Until "false"' \
  --aptopt='Acquire::Languages "none"' \
  --include='bash,ca-certificates,curl,git,python3,python3-venv,python3-dev,python3-pip,build-essential,pkg-config,libffi-dev,libssl-dev,libsqlite3-dev,libyaml-dev,libmagic1,libxml2-dev,libxslt1-dev,zlib1g-dev,libjpeg62-turbo-dev,procps,iproute2,iputils-ping,dnsutils,openssh-client,rsync,jq,tzdata,locales' \
  "$DEBIAN_SUITE" \
  "$rootfs" \
  "deb [check-valid-until=no] https://snapshot.debian.org/archive/debian/$DEBIAN_SNAPSHOT $DEBIAN_SUITE main" \
  "deb [check-valid-until=no] https://snapshot.debian.org/archive/debian/$DEBIAN_SNAPSHOT ${DEBIAN_SUITE}-updates main" \
  "deb [check-valid-until=no] https://snapshot.debian.org/archive/debian-security/$DEBIAN_SNAPSHOT ${DEBIAN_SUITE}-security main"

install -Dm755 "$(command -v qemu-aarch64-static)" "$rootfs/usr/bin/qemu-aarch64-static"
install -d -m755 "$rootfs/opt/pearl-agent/src/hermes-agent" "$rootfs/opt/pearl-agent/src/hermes-bridge"

echo "[2/7] Copying pinned source trees"
rsync -a --delete \
  --exclude='.git/' --exclude='.venv/' --exclude='venv/' --exclude='__pycache__/' \
  --exclude='tests/' --exclude='website/' --exclude='apps/' --exclude='ui-tui/' \
  --exclude='.github/' --exclude='node_modules/' \
  "$HERMES_SOURCE/" "$rootfs/opt/pearl-agent/src/hermes-agent/"
rsync -a --delete --exclude='__pycache__/' \
  "$BRIDGE_SOURCE/" "$rootfs/opt/pearl-agent/src/hermes-bridge/"

cat >"$rootfs/usr/local/sbin/pearl-hermes-bridge-wrapper" <<'EOF'
#!/bin/sh
set -eu
export HERMES_HOME="/data/pearl-agent/hermes-home"
export HOME="$HERMES_HOME/home"
export PATH="/opt/pearl-agent/venv/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
mkdir -p "$HOME" /data/pearl-agent/workspace /data/pearl-agent/state /data/pearl-agent/logs
exec /opt/pearl-agent/venv/bin/pearl-hermes-bridge \
  --config /data/pearl-agent/config/hermes-bridge.json
EOF
chmod 0755 "$rootfs/usr/local/sbin/pearl-hermes-bridge-wrapper"

# Prevent package postinst scripts from trying to start daemons under qemu/chroot.
cat >"$rootfs/usr/sbin/policy-rc.d" <<'EOF'
#!/bin/sh
exit 101
EOF
chmod 0755 "$rootfs/usr/sbin/policy-rc.d"

run_chroot() {
  chroot "$rootfs" /usr/bin/qemu-aarch64-static /bin/bash -Eeuo pipefail -c "$1"
}

echo "[3/7] Installing uv $UV_VERSION and the frozen Hermes environment"
run_chroot "export DEBIAN_FRONTEND=noninteractive PIP_DISABLE_PIP_VERSION_CHECK=1; \
  python3 -m pip install --no-cache-dir 'uv==$UV_VERSION'; \
  python3 -m venv /opt/pearl-agent/venv; \
  cd /opt/pearl-agent/src/hermes-agent; \
  UV_PROJECT_ENVIRONMENT=/opt/pearl-agent/venv uv sync --frozen --extra mcp --no-dev --no-editable; \
  uv pip install --python /opt/pearl-agent/venv/bin/python --no-deps /opt/pearl-agent/src/hermes-bridge"

echo "[4/7] Validating ARM64 runtime imports and bridge entry point"
run_chroot "/opt/pearl-agent/venv/bin/python -c 'from run_agent import AIAgent; from mcp.server import MCPServer; import pearl_hermes_bridge; print(pearl_hermes_bridge.__version__)'; \
  /opt/pearl-agent/venv/bin/pearl-hermes-bridge --help >/dev/null"

install -d -m755 "$rootfs/opt/pearl-agent/manifests" "$rootfs/data/pearl-agent" \
  "$rootfs/data/pearl-agent/config" "$rootfs/data/pearl-agent/hermes-home/home" \
  "$rootfs/data/pearl-agent/workspace" "$rootfs/data/pearl-agent/state" \
  "$rootfs/data/pearl-agent/logs"
install -m600 "$BRIDGE_SOURCE/config.example.json" \
  "$rootfs/data/pearl-agent/config/hermes-bridge.json"
: >"$rootfs/data/pearl-agent/hermes-home/.env"
chmod 0600 "$rootfs/data/pearl-agent/hermes-home/.env"

run_chroot "dpkg-query -W -f='\${Package}\t\${Version}\t\${Architecture}\n' | sort > /opt/pearl-agent/manifests/dpkg.tsv; \
  /opt/pearl-agent/venv/bin/python -m pip freeze --all | sort > /opt/pearl-agent/manifests/python-freeze.txt; \
  apt-get clean; rm -rf /var/lib/apt/lists/* /var/cache/apt/* /tmp/* /var/tmp/*"

cat >"$rootfs/opt/pearl-agent/BUILD.json" <<EOF
{
  "schema": 1,
  "architecture": "arm64",
  "debian_suite": "$DEBIAN_SUITE",
  "debian_snapshot": "$DEBIAN_SNAPSHOT",
  "hermes_commit": "$EXPECTED_HERMES_COMMIT",
  "bridge_version": "0.1.0",
  "python": "3.11",
  "uv": "$UV_VERSION",
  "source_date_epoch": $SOURCE_DATE_EPOCH
}
EOF

rm -f "$rootfs/usr/bin/qemu-aarch64-static" "$rootfs/usr/sbin/policy-rc.d"
rm -rf "$rootfs/opt/pearl-agent/src"
: >"$rootfs/etc/machine-id"
rm -f "$rootfs/var/lib/dbus/machine-id"
find "$rootfs/var/log" -type f -exec truncate -s 0 {} +

echo "[5/7] Packing deterministic rootfs archive"
rm -f "$artifact" "$artifact.sha256"
tar \
  --sort=name \
  --mtime="@$SOURCE_DATE_EPOCH" \
  --owner=0 --group=0 --numeric-owner \
  --acls --xattrs --selinux \
  --pax-option=delete=atime,delete=ctime \
  -C "$rootfs" -cf - . | zstd -19 -T0 --no-progress -o "$artifact"
sha256sum "$artifact" >"$artifact.sha256"
cp "$rootfs/opt/pearl-agent/BUILD.json" "$OUTPUT_DIR/$artifact_base.build.json"
cp "$rootfs/opt/pearl-agent/manifests/dpkg.tsv" "$OUTPUT_DIR/$artifact_base.dpkg.tsv"
cp "$rootfs/opt/pearl-agent/manifests/python-freeze.txt" "$OUTPUT_DIR/$artifact_base.python-freeze.txt"

# The payload must actually be ARM64 and include the bridge executable.
echo "[6/7] Offline archive validation"
tar --use-compress-program=unzstd -tf "$artifact" | grep -Fx './opt/pearl-agent/venv/bin/pearl-hermes-bridge' >/dev/null
tar --use-compress-program=unzstd -tf "$artifact" | grep -Fx './usr/bin/python3.11' >/dev/null

actual_hash="$(cut -d' ' -f1 "$artifact.sha256")"
echo "[7/7] Complete"
echo "Artifact: $artifact"
echo "SHA-256: $actual_hash"
