#!/system/bin/sh
MODDIR=${0%/*}
# shellcheck source=lib/common.sh
. "$MODDIR/lib/common.sh"

touch "$DISABLED_FILE"
stop_bridge
unmount_chroot
rm -rf "$ROOTFS" "$PREVIOUS_ROOTFS" "$RUN_DIR"
# Keep DATA_ROOT intentionally: it may contain API keys, Hermes memory, sessions,
# and user work. The stock rollback package can securely purge it when requested.
pearl_log "Module uninstalled; persistent data retained at $DATA_ROOT"
