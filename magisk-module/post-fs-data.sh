#!/system/bin/sh
MODDIR=${0%/*}
# shellcheck source=lib/common.sh
. "$MODDIR/lib/common.sh"

pearl_log "post-fs-data: preparing Pearl Hermes chroot mounts"
if [ -f "$DISABLED_FILE" ]; then
  pearl_log "post-fs-data: module runtime is disabled"
  exit 0
fi

if ! mount_chroot; then
  pearl_log "post-fs-data: mount preparation failed; bridge will not start"
  touch "$STATE_ROOT/mount-failed"
  exit 1
fi
rm -f "$STATE_ROOT/mount-failed"
pearl_log "post-fs-data: chroot mounts ready"
