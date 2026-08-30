#!/usr/bin/env bash
set -Eeuo pipefail

# Build the Windows-only, read-only EROFS extractor used to audit XiaoAi APKs.
# The executable is an audit artifact only; it is never bundled into the phone ROM.

readonly EROFS_REPOSITORY="https://github.com/RayMarmAung/erofs_extract.git"
readonly EROFS_COMMIT="09bf89957f349bae668dc0254470e7466ed2ef49"
readonly EROFS_RELEASE_URL="https://github.com/RayMarmAung/erofs_extract/releases/download/Initial/erofs_extract.7z"
readonly EROFS_RELEASE_SHA256="37cef87f332d11c16c0ce4970c4e48dc37dd75ac43c7e1353fed32931cc13b71"
readonly LIBICONV_VERSION="1.17"
readonly LIBICONV_URL="https://ftp.gnu.org/pub/gnu/libiconv/libiconv-${LIBICONV_VERSION}.tar.gz"
readonly LIBICONV_SHA256="8f74213b56238c85a50a5329f77e06198771e70dd9a739779f4c02f65d971313"
readonly SOURCE_DATE_EPOCH="1736770292"

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
OUTPUT_DIR="$REPO_ROOT/artifacts/tooling/erofs-audit"
COMPAT_PATCH="$REPO_ROOT/scripts/patches/erofs-extract-mingw-modern.patch"

usage() {
  cat <<'EOF'
Usage: scripts/build-erofs-audit-tool.sh [--output-dir PATH]

Builds a reproducible static PE32 extract.erofs.exe for read-only ROM audit.
The generated binary is never a phone payload.
EOF
}

while (($#)); do
  case "$1" in
    --output-dir) OUTPUT_DIR="${2:?missing output directory}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

for command_name in 7z curl diff file git i686-w64-mingw32-g++-posix \
  i686-w64-mingw32-objdump make patch sed sha256sum tar; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "Missing required command: $command_name" >&2
    exit 1
  }
done

mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR="$(realpath "$OUTPUT_DIR")"
work_dir="$(mktemp -d -t pearl-erofs-audit.XXXXXXXX)"
trap 'rm -rf "$work_dir"' EXIT
export SOURCE_DATE_EPOCH

source_checkout="$work_dir/upstream"
release_archive="$work_dir/erofs_extract.Initial.7z"
release_root="$work_dir/release"
libiconv_archive="$work_dir/libiconv-${LIBICONV_VERSION}.tar.gz"

git init -q "$source_checkout"
git -C "$source_checkout" remote add origin "$EROFS_REPOSITORY"
git -C "$source_checkout" fetch -q --depth 1 origin "$EROFS_COMMIT"
git -C "$source_checkout" checkout -q --detach FETCH_HEAD
test "$(git -C "$source_checkout" rev-parse HEAD)" = "$EROFS_COMMIT"
test -z "$(git -C "$source_checkout" status --porcelain --untracked-files=no)"

curl --fail --location --silent --show-error "$EROFS_RELEASE_URL" --output "$release_archive"
printf '%s  %s\n' "$EROFS_RELEASE_SHA256" "$release_archive" | sha256sum -c -
mkdir -p "$release_root"
7z x -y "$release_archive" "-o$release_root" >/dev/null
release_source="$release_root/erofs_extract"

# The release supplies static liberofs/codec archives omitted from Git. Before
# using them, prove that every released C/C++ source/header equals the pinned
# Git source after normalizing CRLF to LF.
mapfile -t source_files < <(
  git -C "$source_checkout" ls-files '*.cpp' '*.h' | sort
)
((${#source_files[@]} > 0)) || { echo "No extractor sources found" >&2; exit 1; }
for relative in "${source_files[@]}"; do
  test -f "$release_source/$relative"
  diff -u \
    <(sed 's/\r$//' "$source_checkout/$relative") \
    <(sed 's/\r$//' "$release_source/$relative") >/dev/null || {
      echo "Release source mismatch: $relative" >&2
      exit 1
    }
done
# Compile the pinned Git checkout (LF), using only the release's omitted static
# libraries. This avoids patching release CRLF files after identity was proven.
patch --batch --forward -d "$source_checkout" -p1 < "$COMPAT_PATCH"

for archive in liberofs_lib_static.a libdeflate.a libzstd_static.lib liblz4_static.lib; do
  test -s "$release_source/lib/$archive" || {
    echo "Release is missing static library: $archive" >&2
    exit 1
  }
done

curl --fail --location --silent --show-error "$LIBICONV_URL" --output "$libiconv_archive"
printf '%s  %s\n' "$LIBICONV_SHA256" "$libiconv_archive" | sha256sum -c -

build_once() {
  label="$1"
  tree="$work_dir/$label"
  mkdir -p "$tree"
  tar -xzf "$libiconv_archive" -C "$tree"
  iconv_source="$tree/libiconv-${LIBICONV_VERSION}"
  iconv_prefix="$tree/iconv-install"
  (
    cd "$iconv_source"
    ./configure --host=i686-w64-mingw32 --prefix="$iconv_prefix" \
      --enable-static --disable-shared >/dev/null
    make -j2 >/dev/null
    make install >/dev/null
  )

  output="$tree/extract.erofs.exe"
  i686-w64-mingw32-g++-posix \
    -std=gnu++14 -O2 -DNDEBUG -static -static-libgcc -static-libstdc++ \
    -D_WIN32_WINNT=0x0600 -DWINVER=0x0600 \
    -ffile-prefix-map="$work_dir"=/usr/src/pearl-erofs-audit \
    -fdebug-prefix-map="$work_dir"=/usr/src/pearl-erofs-audit \
    -I"$source_checkout" -I"$source_checkout/erofs" -I"$iconv_prefix/include" \
    "$source_checkout/main.cpp" \
    "$source_checkout/ErofsNode.cpp" \
    "$source_checkout/ExtractHelper.cpp" \
    "$source_checkout/ExtractOperation.cpp" \
    "$source_checkout/ErofsHardlinkHandle.cpp" \
    -Wl,--no-insert-timestamp,--build-id=none,--start-group \
    "$release_source/lib/liberofs_lib_static.a" \
    "$release_source/lib/libdeflate.a" \
    "$release_source/lib/libzstd_static.lib" \
    "$release_source/lib/liblz4_static.lib" \
    "$iconv_prefix/lib/libiconv.a" \
    -Wl,--end-group \
    -lkernel32 -ladvapi32 -lws2_32 -lssp -lpthread \
    -s -o "$output"
  printf '%s\n' "$output"
}

binary_a="$(build_once build-a)"
binary_b="$(build_once build-b)"
hash_a="$(sha256sum "$binary_a" | awk '{print $1}')"
hash_b="$(sha256sum "$binary_b" | awk '{print $1}')"
test "$hash_a" = "$hash_b" || {
  echo "EROFS audit-tool reproducibility mismatch: $hash_a != $hash_b" >&2
  exit 1
}
file "$binary_a" | grep -Eqi 'PE32 executable.*Intel 80386.*Windows' || {
  echo "Audit tool is not a 32-bit Windows PE console executable" >&2
  exit 1
}

unexpected_dlls="$(
  i686-w64-mingw32-objdump -p "$binary_a" \
    | sed -n 's/^[[:space:]]*DLL Name: //p' \
    | grep -Eiv '^(KERNEL32|ADVAPI32|WS2_32|msvcrt)\.dll$' || true
)"
test -z "$unexpected_dlls" || {
  printf 'Unexpected runtime DLL dependencies:\n%s\n' "$unexpected_dlls" >&2
  exit 1
}

install -m755 "$binary_a" "$OUTPUT_DIR/extract.erofs.exe"
printf '%s  %s\n' "$hash_a" extract.erofs.exe > "$OUTPUT_DIR/extract.erofs.exe.sha256"
cat > "$OUTPUT_DIR/extract.erofs.provenance.txt" <<EOF
purpose=read-only XiaoAi APK audit; never bundled into ROM
erofs_repository=$EROFS_REPOSITORY
erofs_commit=$EROFS_COMMIT
erofs_release_url=$EROFS_RELEASE_URL
erofs_release_sha256=$EROFS_RELEASE_SHA256
libiconv_version=$LIBICONV_VERSION
libiconv_url=$LIBICONV_URL
libiconv_sha256=$LIBICONV_SHA256
source_date_epoch=$SOURCE_DATE_EPOCH
compiler=$(i686-w64-mingw32-g++-posix --version | head -n 1)
sha256=$hash_a
EOF
cp "$source_checkout/LICENSE" "$OUTPUT_DIR/erofs-extract-LICENSE"
printf 'Artifact: %s\nSHA-256: %s\n' "$OUTPUT_DIR/extract.erofs.exe" "$hash_a"
