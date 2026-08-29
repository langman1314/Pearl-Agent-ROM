#!/system/bin/sh
MODDIR=${0%/*}
# shellcheck source=lib/common.sh
. "$MODDIR/lib/common.sh"

action="${1:-toggle}"
case "$action" in
  enable)
    rm -f "$DISABLED_FILE"
    # A Magisk app action may run in an app-private mount namespace. Do not
    # create chroot mounts here and pretend they are visible to init services.
    pearl_log "Runtime enabled; reboot required to mount and start in init namespace"
    ;;
  disable)
    touch "$DISABLED_FILE"
    stop_bridge
    unmount_chroot
    pearl_log "Runtime disabled"
    ;;
  rollback)
    touch "$DISABLED_FILE"
    stop_bridge
    unmount_chroot
    if [ ! -d "$PREVIOUS_ROOTFS" ]; then
      pearl_log "Rollback requested but no previous rootfs exists"
      exit 1
    fi
    failed="$STATE_ROOT/rootfs.failed.$(date +%s)"
    [ -d "$ROOTFS" ] && mv "$ROOTFS" "$failed"
    mv "$PREVIOUS_ROOTFS" "$ROOTFS"
    pearl_log "Previous rootfs restored; failed rootfs retained at $failed; runtime remains disabled until explicit enable and reboot"
    ;;
  toggle)
    if [ -f "$DISABLED_FILE" ]; then
      exec "$0" enable
    else
      exec "$0" disable
    fi
    ;;
  *)
    echo "Usage: $0 [enable|disable|rollback|toggle]" >&2
    exit 2
    ;;
esac
