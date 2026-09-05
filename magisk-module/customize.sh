#!/system/bin/sh
# Magisk sources customize.sh into its installer process. Do not change global
# errexit/nounset/pipefail state; every safety-critical command is guarded here.

SKIPMOUNT=false
PROPFILE=true
POSTFSDATA=true
LATESTARTSERVICE=true

STATE_ROOT=/data/adb/pearl-agent
ROOTFS="$STATE_ROOT/rootfs"
PREVIOUS_ROOTFS="$STATE_ROOT/rootfs.previous"
DATA_ROOT="$STATE_ROOT/data"
ARCHIVE="$MODPATH/payload/rootfs.tar.zst"
ZSTD="$MODPATH/payload/zstd"
MANIFEST="$MODPATH/payload/manifest.sha256"
UNPACKED_BYTES_FILE="$MODPATH/payload/rootfs.unpacked-bytes"
HERMES_CONFIG_TEMPLATE="$MODPATH/payload/hermes-config.yaml"

ui_print "- Pearl Nexus + Hermes chroot installer"
ui_print "- This module never writes boot, vbmeta, super, preloader, or efuse"

devices="$(getprop ro.product.device) $(getprop ro.product.vendor.device) $(getprop ro.product.system.device)"
case "$devices" in
  *pearl*) ;;
  *) abort "! Unsupported device: $devices (expected pearl)" ;;
esac

sdk="$(getprop ro.build.version.sdk)"
case "$sdk" in
  ''|*[!0-9]*) abort "! Could not read Android SDK level" ;;
esac
[ "$sdk" -ge 35 ] || abort "! Android SDK $sdk is too old; expected Android 15/16 base"

if [ -n "${MAGISK_VER_CODE:-}" ] && [ "$MAGISK_VER_CODE" -lt 27000 ]; then
  abort "! Magisk 27.0 or newer is required"
fi

[ -f "$ARCHIVE" ] || abort "! Missing payload/rootfs.tar.zst"
[ -f "$ZSTD" ] || abort "! Missing payload/zstd"
chmod 0755 "$ZSTD"
[ -x "$ZSTD" ] || abort "! payload/zstd is not executable"
[ -f "$UNPACKED_BYTES_FILE" ] || abort "! Missing payload/rootfs.unpacked-bytes"
[ -f "$HERMES_CONFIG_TEMPLATE" ] || abort "! Missing payload/hermes-config.yaml"
[ -f "$MANIFEST" ] || abort "! Missing payload/manifest.sha256"

ui_print "- Verifying payload SHA-256 manifest"
(
  cd "$MODPATH/payload"
  sha256sum -c manifest.sha256
) || abort "! Payload SHA-256 verification failed"

"$ZSTD" -t "$ARCHIVE" >/dev/null 2>&1 || abort "! Rootfs zstd integrity test failed"

unpacked_bytes="$(tr -d ' \r\n' < "$UNPACKED_BYTES_FILE")"
case "$unpacked_bytes" in
  ''|*[!0-9]*) abort "! Invalid rootfs uncompressed-size metadata" ;;
esac
[ "$unpacked_bytes" -ge 1048576 ] || abort "! Rootfs uncompressed size is implausibly small"
mkdir -p "$STATE_ROOT"
chmod 0700 "$STATE_ROOT"
available_kb="$(df -Pk "$STATE_ROOT" | awk 'NR > 1 { value=$4 } END { print value }')"
case "$available_kb" in
  ''|*[!0-9]*) abort "! Could not determine free space under $STATE_ROOT" ;;
esac
unpacked_kb=$(((unpacked_bytes + 1023) / 1024))
required_kb=$((unpacked_kb + unpacked_kb / 4 + 262144))
[ "$available_kb" -ge "$required_kb" ] || {
  abort "! Insufficient /data space: need ${required_kb} KiB free, have ${available_kb} KiB"
}
ui_print "- Free-space gate passed (${available_kb} KiB available)"

# Module upgrades can run while the old chroot is live. Stop and detach every
# bind mount before renaming the active rootfs; moving a mounted tree would
# leave the old process and new payload attached to ambiguous paths.
# shellcheck source=lib/common.sh
. "$MODPATH/lib/common.sh"
reinstall_after_uninstall=false
[ -f "$UNINSTALLED_FILE" ] && reinstall_after_uninstall=true
touch "$MAINTENANCE_FILE"
chmod 0600 "$MAINTENANCE_FILE"
stop_bridge
unmount_chroot

mkdir -p "$STATE_ROOT" "$DATA_ROOT" "$STATE_ROOT/run"
chmod 0700 "$STATE_ROOT" "$DATA_ROOT" "$STATE_ROOT/run"
# A killed installer may leave an extraction tree. No live process can own it
# after stop_bridge + unmount_chroot, so clean all stale stages before reuse.
rm -rf "$STATE_ROOT"/rootfs.new.*
stage="$STATE_ROOT/rootfs.new.$$"
mkdir -p "$stage"

ui_print "- Extracting verified Debian ARM64 rootfs"
"$ZSTD" -dc "$ARCHIVE" | tar -xpf - -C "$stage" || {
  rm -rf "$stage"
  abort "! Rootfs extraction failed; current rootfs was not changed"
}
if find "$stage" -type f | grep -Eqi '(^|/)(boot|init_boot|vendor_boot|recovery|dtbo|vbmeta(_system|_vendor)?|super|system|system_ext|vendor|odm|product|mi_ext|preloader|efuse|gpt|lk|abl|xbl[^/]*)(_raw)?(_[ab])?\.(img|bin|elf)$'; then
  rm -rf "$stage"
  abort "! Extracted rootfs contains a forbidden Android partition payload"
fi
if find "$stage" -type f | grep -Eqi '(^|/)(flash_all[^/]*|flash[^/]*preloader[^/]*|fastboot[^/]*)\.(bat|cmd|sh|py)$'; then
  rm -rf "$stage"
  abort "! Extracted rootfs contains a forbidden firmware flashing script"
fi

[ -x "$stage/usr/local/sbin/pearl-hermes-bridge-wrapper" ] || {
  rm -rf "$stage"
  abort "! Extracted rootfs is missing the Hermes bridge wrapper"
}
if [ ! -L "$stage/opt/pearl-agent/venv/bin/python" ] ||
   [ "$(readlink "$stage/opt/pearl-agent/venv/bin/python")" != python3 ] ||
   [ ! -L "$stage/opt/pearl-agent/venv/bin/python3" ] ||
   [ "$(readlink "$stage/opt/pearl-agent/venv/bin/python3")" != /usr/bin/python3 ] ||
   [ ! -L "$stage/usr/bin/python3" ] ||
   [ "$(readlink "$stage/usr/bin/python3")" != python3.11 ] ||
   [ ! -x "$stage/usr/bin/python3.11" ]; then
  rm -rf "$stage"
  abort "! Extracted rootfs is missing the Python environment"
fi
grep -q "$EXPECTED_HERMES_COMMIT" "$stage/opt/pearl-agent/BUILD.json" || {
  rm -rf "$stage"
  abort "! Rootfs Hermes commit does not match the approved source"
}
build_json_hash="$(sha256sum "$stage/opt/pearl-agent/BUILD.json" | awk '{print $1}')"
[ "$build_json_hash" = "$EXPECTED_BUILD_JSON_SHA256" ] || {
  rm -rf "$stage"
  abort "! Extracted rootfs build metadata is corrupt"
}
re_init_hash="$(sha256sum "$stage/usr/lib/python3.11/re/__init__.py" | awk '{print $1}')"
[ "$re_init_hash" = "$EXPECTED_RE_INIT_SHA256" ] || {
  rm -rf "$stage"
  abort "! Extracted rootfs Python standard library is corrupt"
}
rootfs_runtime_is_valid "$stage" true || {
  rm -rf "$stage"
  abort "! Extracted rootfs runtime smoke test failed"
}
# The host deployer reboots immediately after Magisk returns. Force the multi-GB
# extraction to stable storage before publishing it, otherwise F2FS delayed
# writeback can leave correct file sizes backed by zero-filled data after reboot.
ui_print "- Synchronizing verified rootfs to persistent storage"
sync || {
  rm -rf "$stage"
  abort "! Could not synchronize extracted rootfs"
}

mkdir -p "$DATA_ROOT/config" "$DATA_ROOT/hermes-home/home" "$DATA_ROOT/state" \
  "$DATA_ROOT/workspace" "$DATA_ROOT/logs"
chmod 0700 "$DATA_ROOT" "$DATA_ROOT/config" "$DATA_ROOT/hermes-home" \
  "$DATA_ROOT/hermes-home/home" "$DATA_ROOT/state" "$DATA_ROOT/workspace" "$DATA_ROOT/logs"

if [ ! -f "$DATA_ROOT/config/hermes-bridge.json" ]; then
  cp "$stage/data/pearl-agent/config/hermes-bridge.json" "$DATA_ROOT/config/hermes-bridge.json"
  chmod 0600 "$DATA_ROOT/config/hermes-bridge.json"
fi
if [ ! -f "$DATA_ROOT/hermes-home/config.yaml" ]; then
  cp "$HERMES_CONFIG_TEMPLATE" "$DATA_ROOT/hermes-home/config.yaml"
fi
chmod 0600 "$DATA_ROOT/hermes-home/config.yaml"
if [ ! -f "$DATA_ROOT/hermes-home/.env" ]; then
  : > "$DATA_ROOT/hermes-home/.env"
fi
chmod 0600 "$DATA_ROOT/hermes-home/.env"
if [ ! -f "$MCP_TOKEN_FILE" ]; then
  old_umask="$(umask)"
  umask 077
  head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$MCP_TOKEN_FILE"
  umask "$old_umask"
fi
chmod 0600 "$MCP_TOKEN_FILE"
token_length="$(wc -c < "$MCP_TOKEN_FILE" | tr -d ' ')"
[ "$token_length" -ge 64 ] || abort "! Generated MCP token is unexpectedly short"

restore_after_activation_failure() {
  reason="$1"
  touch "$MAINTENANCE_FILE"
  chmod 0600 "$MAINTENANCE_FILE"
  rm -rf "$stage" "$ROOTFS"
  rm -f "$STATE_ROOT/installed-build.json.new.$$"
  if [ "$old_rootfs_preserved" = true ] && mv "$PREVIOUS_ROOTFS" "$ROOTFS"; then
    rollback_record="$STATE_ROOT/installed-build.json.rollback.$$"
    if cp "$ROOTFS/opt/pearl-agent/BUILD.json" "$rollback_record" &&
       chmod 0600 "$rollback_record" &&
       mv -f "$rollback_record" "$STATE_ROOT/installed-build.json"; then
      :
    else
      rm -f "$rollback_record" "$STATE_ROOT/installed-build.json"
      reason="$reason; previous build record restore failed"
    fi
  else
    rm -f "$STATE_ROOT/installed-build.json"
    [ "$old_rootfs_preserved" = false ] || reason="$reason; previous verified rootfs restore failed"
  fi
  sync
  abort "! $reason"
}

ui_print "- Atomically activating rootfs; preserving one verified rollback version"
rm -rf "$PREVIOUS_ROOTFS"
old_rootfs_preserved=false
if [ -d "$ROOTFS" ]; then
  if rootfs_runtime_is_valid "$ROOTFS"; then
    mv "$ROOTFS" "$PREVIOUS_ROOTFS" || {
      rm -rf "$stage"
      abort "! Could not preserve current verified rootfs"
    }
    old_rootfs_preserved=true
  else
    ui_print "- Existing rootfs is corrupt; excluding it from rollback"
    rm -rf "$ROOTFS"
  fi
fi
mv "$stage" "$ROOTFS" ||
  restore_after_activation_failure "Could not activate new rootfs"

rootfs_runtime_is_valid "$ROOTFS" ||
  restore_after_activation_failure "Activated rootfs failed final runtime verification"

build_record_tmp="$STATE_ROOT/installed-build.json.new.$$"
cp "$ROOTFS/opt/pearl-agent/BUILD.json" "$build_record_tmp" ||
  restore_after_activation_failure "Could not stage installed build record"
chmod 0600 "$build_record_tmp" ||
  restore_after_activation_failure "Could not protect installed build record"
mv -f "$build_record_tmp" "$STATE_ROOT/installed-build.json" ||
  restore_after_activation_failure "Could not publish installed build record"
[ "$(sha256sum "$STATE_ROOT/installed-build.json" | awk '{print $1}')" = "$EXPECTED_BUILD_JSON_SHA256" ] ||
  restore_after_activation_failure "Installed build record failed verification"
sync || restore_after_activation_failure "Could not synchronize activated rootfs"
rm -f "$STATE_ROOT/mount-failed"

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/customize.sh" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/lib/common.sh" 0 0 0755
set_perm "$ZSTD" 0 0 0755

# A prior uninstall deliberately leaves disabled+uninstalled markers to stop
# races with the old supervisor. Clear them only after a complete reinstall;
# ordinary upgrades preserve an intentional user-disabled state.
if [ "$reinstall_after_uninstall" = true ]; then
  rm -f "$DISABLED_FILE" "$UNINSTALLED_FILE"
fi
# Persist the active rename, build record, permissions and generated token while
# maintenance still keeps the runtime fail-closed. Publish completion last.
sync || restore_after_activation_failure "Could not synchronize activated Agent state"
rm -f "$MAINTENANCE_FILE"
if ! sync; then
  touch "$MAINTENANCE_FILE"
  chmod 0600 "$MAINTENANCE_FILE"
  sync
  abort "! Could not synchronize Agent completion state; maintenance retained"
fi

ui_print "- Installation staged safely and synchronized"
ui_print "- Reboot, then provision DEEPSEEK_API_KEY; native XiaoAi remains intact"
