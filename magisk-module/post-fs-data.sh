#!/system/bin/sh
MODDIR=${0%/*}
# shellcheck source=lib/common.sh
. "$MODDIR/lib/common.sh"

MOUNT_FAILURE_COUNT_FILE="$STATE_ROOT/mount-failure-count"

record_mount_failure() {
  count=0
  if [ -f "$MOUNT_FAILURE_COUNT_FILE" ]; then
    count="$(tr -d ' \r\n' < "$MOUNT_FAILURE_COUNT_FILE")"
    case "$count" in ''|*[!0-9]*) count=0 ;; esac
  fi
  count=$((count + 1))
  tmp="$MOUNT_FAILURE_COUNT_FILE.$$"
  printf '%s\n' "$count" > "$tmp"
  chmod 0600 "$tmp"
  mv "$tmp" "$MOUNT_FAILURE_COUNT_FILE"
  printf '%s\n' "$count"
}

try_automatic_rollback() {
  [ -d "$PREVIOUS_ROOTFS" ] || return 1
  pearl_log "post-fs-data: three mount-failed boots; attempting one-version automatic rollback"
  unmount_chroot
  for old_failed in "$STATE_ROOT"/rootfs.failed.mount.*; do
    [ -d "$old_failed" ] && rm -rf "$old_failed"
  done
  failed="$STATE_ROOT/rootfs.failed.mount.$(date +%s).$$"
  if [ -d "$ROOTFS" ] && ! mv "$ROOTFS" "$failed"; then
    pearl_log "post-fs-data: could not quarantine failed rootfs"
    return 1
  fi
  if ! mv "$PREVIOUS_ROOTFS" "$ROOTFS"; then
    [ -d "$failed" ] && mv "$failed" "$ROOTFS" 2>/dev/null || true
    pearl_log "post-fs-data: could not activate previous rootfs"
    return 1
  fi
  if mount_chroot; then
    rm -f "$MOUNT_FAILURE_COUNT_FILE" "$STATE_ROOT/mount-failed"
    if [ -f "$ROOTFS/opt/pearl-agent/BUILD.json" ]; then
      cp "$ROOTFS/opt/pearl-agent/BUILD.json" "$STATE_ROOT/installed-build.json"
      chmod 0600 "$STATE_ROOT/installed-build.json"
    fi
    pearl_log "post-fs-data: automatic rollback mounted successfully; quarantined=$failed"
    return 0
  fi
  pearl_log "post-fs-data: previous rootfs also failed to mount; disabling runtime"
  return 1
}

pearl_log "post-fs-data: preparing Pearl Hermes chroot mounts"
if [ -f "$DISABLED_FILE" ]; then
  pearl_log "post-fs-data: module runtime is disabled"
  exit 0
fi

if ! mount_chroot; then
  unmount_chroot
  failure_count="$(record_mount_failure)"
  pearl_log "post-fs-data: mount preparation failed boot_count=$failure_count"
  touch "$STATE_ROOT/mount-failed"
  if [ "$failure_count" -ge 3 ]; then
    if try_automatic_rollback; then
      exit 0
    fi
    touch "$DISABLED_FILE"
    pearl_log "post-fs-data: runtime disabled after repeated mount failures"
  fi
  exit 1
fi
rm -f "$STATE_ROOT/mount-failed" "$MOUNT_FAILURE_COUNT_FILE"
pearl_log "post-fs-data: chroot mounts ready"
