#!/system/bin/sh

STATE_ROOT=/data/adb/pearl-agent
ROOTFS="$STATE_ROOT/rootfs"
PREVIOUS_ROOTFS="$STATE_ROOT/rootfs.previous"
DATA_ROOT="$STATE_ROOT/data"
LOG_DIR="$DATA_ROOT/logs"
RUN_DIR="$STATE_ROOT/run"
PID_FILE="$RUN_DIR/hermes-bridge.pid"
SUPERVISOR_PID_FILE="$RUN_DIR/supervisor.pid"
DISABLED_FILE="$STATE_ROOT/disabled"
MCP_TOKEN_FILE="$DATA_ROOT/config/mcp-token"
SUPERVISOR_LOG="$LOG_DIR/supervisor.log"

mkdir_safe() {
  mkdir -p "$1"
  chmod "${2:-0700}" "$1"
}

rotate_log_file() {
  log_file="$1"
  max_bytes="${2:-10485760}"
  [ -f "$log_file" ] || return 0
  bytes="$(wc -c < "$log_file" | tr -d ' ')"
  case "$bytes" in ''|*[!0-9]*) return 0 ;; esac
  [ "$bytes" -lt "$max_bytes" ] && return 0
  rm -f "$log_file.3"
  [ -f "$log_file.2" ] && mv "$log_file.2" "$log_file.3"
  [ -f "$log_file.1" ] && mv "$log_file.1" "$log_file.2"
  mv "$log_file" "$log_file.1"
}

pearl_log() {
  mkdir_safe "$LOG_DIR" 0700
  rotate_log_file "$SUPERVISOR_LOG" 10485760
  printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*" >> "$SUPERVISOR_LOG"
}

pid_is_running() {
  pid="$1"
  [ -n "$pid" ] && [ -d "/proc/$pid" ] && kill -0 "$pid" 2>/dev/null
}

process_start_time() {
  pid="$1"
  [ -r "/proc/$pid/stat" ] || return 1
  awk '{print $22}' "/proc/$pid/stat"
}

read_bridge_pid() {
  [ -f "$PID_FILE" ] || return 1
  read -r pid expected_start < "$PID_FILE" || return 1
  case "$pid:$expected_start" in
    *[!0-9:]*) return 1 ;;
  esac
  pid_is_running "$pid" || return 1
  [ "$(process_start_time "$pid" 2>/dev/null)" = "$expected_start" ] || return 1
  cmdline="$(tr '\000' ' ' < "/proc/$pid/cmdline" 2>/dev/null)"
  case "$cmdline" in
    *pearl-hermes-bridge*) ;;
    *) return 1 ;;
  esac
  printf '%s\n' "$pid"
}

stop_bridge() {
  pid="$(read_bridge_pid 2>/dev/null)" || {
    rm -f "$PID_FILE"
    return 0
  }
  pearl_log "Stopping Hermes bridge process group=$pid"
  kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
  count=0
  while pid_is_running "$pid" && [ "$count" -lt 20 ]; do
    sleep 1
    count=$((count + 1))
  done
  if pid_is_running "$pid"; then
    pearl_log "Hermes bridge group ignored TERM; sending KILL group=$pid"
    kill -KILL -- "-$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null || true
  fi
  rm -f "$PID_FILE"
}

is_mounted_at() {
  awk -v target="$1" '$2 == target { found=1 } END { exit !found }' /proc/mounts 2>/dev/null
}

mount_bind_once() {
  source_path="$1"
  target_path="$2"
  mkdir -p "$target_path"
  is_mounted_at "$target_path" || mount --bind "$source_path" "$target_path"
}

mount_bind_file_once() {
  source_path="$1"
  target_path="$2"
  mkdir -p "${target_path%/*}"
  [ -e "$target_path" ] || : > "$target_path"
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
  mount_fs_once proc proc "$ROOTFS/proc" -o nosuid,nodev,noexec || return 1
  mount_fs_once tmpfs tmpfs "$ROOTFS/dev" -o mode=0755,nosuid || return 1
  mkdir -p "$ROOTFS/dev/pts" "$ROOTFS/dev/shm"
  chmod 1777 "$ROOTFS/dev/shm"
  mount_fs_once devpts devpts "$ROOTFS/dev/pts" -o mode=0620,ptmxmode=0666,nosuid,noexec || return 1
  rm -f "$ROOTFS/dev/ptmx" "$ROOTFS/dev/fd" "$ROOTFS/dev/stdin" "$ROOTFS/dev/stdout" "$ROOTFS/dev/stderr"
  ln -s pts/ptmx "$ROOTFS/dev/ptmx"
  ln -s /proc/self/fd "$ROOTFS/dev/fd"
  ln -s /proc/self/fd/0 "$ROOTFS/dev/stdin"
  ln -s /proc/self/fd/1 "$ROOTFS/dev/stdout"
  ln -s /proc/self/fd/2 "$ROOTFS/dev/stderr"
  for node in null zero full random urandom tty; do
    mount_bind_file_once "/dev/$node" "$ROOTFS/dev/$node" || return 1
  done
  mount_fs_once tmpfs tmpfs "$ROOTFS/run" -o mode=0755,nosuid,nodev || return 1
  write_resolv_conf
}

unmount_one() {
  target="$1"
  is_mounted_at "$target" || return 0
  umount "$target" 2>/dev/null || umount -l "$target" 2>/dev/null || true
}

unmount_chroot() {
  for node in tty urandom random full zero null; do
    unmount_one "$ROOTFS/dev/$node"
  done
  unmount_one "$ROOTFS/dev/pts"
  unmount_one "$ROOTFS/run"
  unmount_one "$ROOTFS/proc"
  unmount_one "$ROOTFS/dev"
  unmount_one "$ROOTFS/data/pearl-agent"
}
