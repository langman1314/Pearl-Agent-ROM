#!/system/bin/sh
MODDIR=${0%/*}
# shellcheck source=lib/common.sh
. "$MODDIR/lib/common.sh"

action="${1:-toggle}"
case "$action" in
  enable)
    if [ -f "$MAINTENANCE_FILE" ]; then
      pearl_log "Enable refused: interrupted install/upgrade marker present; reinstall the verified module"
      exit 1
    fi
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
    failed=""
    if [ -d "$ROOTFS" ]; then
      for old_failed in "$STATE_ROOT"/rootfs.failed.action.*; do
        [ -d "$old_failed" ] && rm -rf "$old_failed"
      done
      failed="$STATE_ROOT/rootfs.failed.action.$(date +%s).$$"
      if ! mv "$ROOTFS" "$failed"; then
        pearl_log "Rollback aborted: could not quarantine active rootfs"
        exit 1
      fi
    fi
    if ! mv "$PREVIOUS_ROOTFS" "$ROOTFS"; then
      [ -n "$failed" ] && [ -d "$failed" ] && mv "$failed" "$ROOTFS" 2>/dev/null
      pearl_log "Rollback aborted: could not activate previous rootfs; active rootfs restoration attempted"
      exit 1
    fi
    if [ -n "$failed" ]; then
      pearl_log "Previous rootfs restored; failed rootfs retained at $failed; runtime remains disabled until explicit enable and reboot"
    else
      pearl_log "Previous rootfs restored; no active rootfs required quarantine; runtime remains disabled until explicit enable and reboot"
    fi
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
