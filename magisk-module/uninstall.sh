#!/system/bin/sh
MODDIR=${0%/*}
# shellcheck source=lib/common.sh
. "$MODDIR/lib/common.sh"

touch "$DISABLED_FILE" "$UNINSTALLED_FILE"
chmod 0600 "$DISABLED_FILE" "$UNINSTALLED_FILE"
stop_bridge
unmount_chroot
rm -rf "$ROOTFS" "$PREVIOUS_ROOTFS" "$RUN_DIR" "$STATE_ROOT"/rootfs.new.* \
  "$STATE_ROOT"/rootfs.failed.mount.* "$STATE_ROOT"/rootfs.failed.action.*
rm -f "$MAINTENANCE_FILE" "$STATE_ROOT/installed-build.json" \
  "$STATE_ROOT/mount-failed" "$STATE_ROOT/mount-failure-count"
# Keep DATA_ROOT intentionally: it may contain API keys, Hermes memory, sessions,
# and user work. Keep disabled+uninstalled markers to prevent a departing old
# supervisor from restarting; a complete verified reinstall clears both.
pearl_log "Module uninstalled; persistent data retained at $DATA_ROOT"
