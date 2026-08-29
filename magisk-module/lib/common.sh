#!/system/bin/sh

STATE_ROOT=/data/adb/pearl-agent
ROOTFS="$STATE_ROOT/rootfs"
PREVIOUS_ROOTFS="$STATE_ROOT/rootfs.previous"
DATA_ROOT="$STATE_ROOT/data"
LOG_DIR="$DATA_ROOT/logs"
RUN_DIR="$STATE_ROOT/run"
PID_FILE="$RUN_DIR/hermes-bridge.pid"
DISABLED_FILE="$STATE_ROOT/disabled"
SUPERVISOR_LOG="$LOG_DIR/supervisor.log"

mkdir_safe() {
  mkdir -p "$1"
  chmod "${2:-0700}" "$1"
}

pearl_log() {
  mkdir_safe "$LOG_DIR" 0700
  printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*" >> "$SUPERVISOR_LOG"
}

pid_is_running() {
  pid="$1"
  [ -n "$pid" ] && [ -d "/proc/$pid" ] && kill -0 "$pid" 2>/dev/null
}

read_bridge_pid() {
  [ -f "$PID_FILE" ] || return 1
  pid="$(cat "$PID_FILE" 2>/dev/null)"
  pid_is_running "$pid" || return 1
  printf '%s\n' "$pid"
}

stop_bridge() {
  pid="$(read_bridge_pid 2>/dev/null)" || {
    rm -f "$PID_FILE"
    return 0
  }
  pearl_log "Stopping Hermes bridge pid=$pid"
  kill -TERM "$pid" 2>/dev/null || true
  count=0
  while pid_is_running "$pid" && [ "$count" -lt 20 ]; do
    sleep 1
    count=$((count + 1))
  done
  if pid_is_running "$pid"; then
    pearl_log "Hermes bridge ignored TERM; sending KILL pid=$pid"
    kill -KILL "$pid" 2>/dev/null || true
  fi
  rm -f "$PID_FILE"
}

is_mounted_at() {
  grep -q " $1 " /proc/mounts 2>/dev/null
}

mount_bind_once() {
  source_path="$1"
  target_path="$2"
  mkdir -p "$target_path"
  is_mounted_at "$target_path" || mount --bind "$source_path" "$target_path"
}

mount_fs_once() {
  fs_type="$1"
  source_name="$2"
  target_path="$3"
  shift 3
  mkdir -p "$target_path"
  is_mounted_at "$target_path" || mount -t "$fs_type" "$@" "$source_name" "$target_path"
}

write_resolv_conf() {
  target="$ROOTFS/etc/resolv.conf"
  rm -f "$target"
  : > "$target"
  for property in net.dns1 net.dns2 net.dns3 net.dns4; do
    value="$(getprop "$property" 2>/dev/null)"
    [ -n "$value" ] && printf 'nameserver %s\n' "$value" >> "$target"
  done
  if [ ! -s "$target" ]; then
    printf '%s\n' 'nameserver 223.5.5.5' 'nameserver 119.29.29.29' 'nameserver 1.1.1.1' > "$target"
  fi
  chmod 0644 "$target"
}

mount_chroot() {
  [ -x "$ROOTFS/usr/local/sbin/pearl-hermes-bridge-wrapper" ] || {
    pearl_log "Rootfs missing or invalid: $ROOTFS"
    return 1
  }

  mkdir_safe "$DATA_ROOT" 0700
  mkdir_safe "$DATA_ROOT/config" 0700
  mkdir_safe "$DATA_ROOT/hermes-home" 0700
  mkdir_safe "$DATA_ROOT/hermes-home/home" 0700
  mkdir_safe "$DATA_ROOT/state" 0700
  mkdir_safe "$DATA_ROOT/workspace" 0700
  mkdir_safe "$LOG_DIR" 0700
  mkdir_safe "$RUN_DIR" 0700

  mount_bind_once "$DATA_ROOT" "$ROOTFS/data/pearl-agent" || return 1
  mount_bind_once /dev "$ROOTFS/dev" || return 1
  mount_fs_once proc proc "$ROOTFS/proc" || return 1
  mount_fs_once sysfs sysfs "$ROOTFS/sys" || return 1
  mount_fs_once devpts devpts "$ROOTFS/dev/pts" -o mode=0620,ptmxmode=0666 || return 1
  mount_fs_once tmpfs tmpfs "$ROOTFS/run" -o mode=0755,nosuid,nodev || return 1
  write_resolv_conf
}

unmount_one() {
  target="$1"
  is_mounted_at "$target" || return 0
  umount "$target" 2>/dev/null || umount -l "$target" 2>/dev/null || true
}

unmount_chroot() {
  unmount_one "$ROOTFS/dev/pts"
  unmount_one "$ROOTFS/run"
  unmount_one "$ROOTFS/proc"
  unmount_one "$ROOTFS/sys"
  unmount_one "$ROOTFS/dev"
  unmount_one "$ROOTFS/data/pearl-agent"
}
