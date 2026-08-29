#!/system/bin/sh
MODDIR=${0%/*}
# shellcheck source=lib/common.sh
. "$MODDIR/lib/common.sh"

wait_for_boot() {
  count=0
  while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$count" -lt 300 ]; do
    sleep 2
    count=$((count + 1))
  done
  [ "$(getprop sys.boot_completed)" = "1" ]
}

sleep_while_enabled() {
  remaining="$1"
  while [ "$remaining" -gt 0 ]; do
    [ -f "$DISABLED_FILE" ] && return 1
    step=5
    [ "$remaining" -lt "$step" ] && step="$remaining"
    sleep "$step"
    remaining=$((remaining - step))
  done
  [ ! -f "$DISABLED_FILE" ]
}

claim_supervisor() {
  if [ -f "$SUPERVISOR_PID_FILE" ]; then
    read -r old_pid old_start < "$SUPERVISOR_PID_FILE" || true
    if pid_is_running "$old_pid" && [ "$(process_start_time "$old_pid" 2>/dev/null)" = "$old_start" ]; then
      old_cmdline="$(tr '\000' ' ' < "/proc/$old_pid/cmdline" 2>/dev/null)"
      case "$old_cmdline" in
        *pearl_agent/service.sh*|*magisk-module/service.sh*)
          pearl_log "service: supervisor already running pid=$old_pid"
          return 1
          ;;
      esac
    fi
  fi
  printf '%s %s\n' "$$" "$(process_start_time "$$")" > "$SUPERVISOR_PID_FILE"
  chmod 0600 "$SUPERVISOR_PID_FILE"
}

release_supervisor() {
  if [ -f "$SUPERVISOR_PID_FILE" ]; then
    read -r owner _ < "$SUPERVISOR_PID_FILE" || owner=""
    [ "$owner" = "$$" ] && rm -f "$SUPERVISOR_PID_FILE"
  fi
}

start_once() {
  mount_chroot || return 1
  rm -f "$PID_FILE"
  rotate_log_file "$LOG_DIR/hermes-bridge.log" 10485760
  pearl_log "Starting authenticated Hermes bridge in Debian chroot"
  setsid chroot "$ROOTFS" /usr/bin/env -i \
    HOME=/data/pearl-agent/hermes-home/home \
    HERMES_HOME=/data/pearl-agent/hermes-home \
    PATH=/opt/pearl-agent/venv/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    TMPDIR=/tmp \
    /usr/local/sbin/pearl-hermes-bridge-wrapper \
    >> "$LOG_DIR/hermes-bridge.log" 2>&1 &
  child=$!
  start_time="$(process_start_time "$child" 2>/dev/null)"
  if [ -n "$start_time" ]; then
    printf '%s %s\n' "$child" "$start_time" > "$PID_FILE"
    chmod 0600 "$PID_FILE"
    [ -w "/proc/$child/oom_score_adj" ] && echo 300 > "/proc/$child/oom_score_adj" 2>/dev/null || true
  fi
  wait "$child"
  status=$?
  # Clean any descendants that survived after the process-group leader exited.
  kill -TERM -- "-$child" 2>/dev/null || true
  sleep 1
  kill -KILL -- "-$child" 2>/dev/null || true
  rm -f "$PID_FILE"
  return "$status"
}

mkdir_safe "$LOG_DIR" 0700
mkdir_safe "$RUN_DIR" 0700
claim_supervisor || exit 0
trap 'release_supervisor' EXIT HUP INT TERM

if ! wait_for_boot; then
  pearl_log "service: boot_completed timeout; refusing background startup"
  exit 1
fi
if [ -f "$DISABLED_FILE" ]; then
  pearl_log "service: runtime disabled"
  exit 0
fi
if [ ! -s "$MCP_TOKEN_FILE" ]; then
  pearl_log "service: root-only MCP token missing; refusing unauthenticated startup"
  exit 1
fi
chmod 0600 "$MCP_TOKEN_FILE"
command -v setsid >/dev/null 2>&1 || {
  pearl_log "service: setsid applet missing; refusing unsafe single-PID supervision"
  exit 1
}

crash_count=0
backoff=2
while [ ! -f "$DISABLED_FILE" ]; do
  started="$(date +%s)"
  start_once
  status=$?
  ended="$(date +%s)"
  runtime=$((ended - started))
  pearl_log "Hermes bridge exited status=$status runtime=${runtime}s"

  [ -f "$DISABLED_FILE" ] && break
  if [ "$runtime" -ge 900 ]; then
    crash_count=0
    backoff=2
  else
    crash_count=$((crash_count + 1))
    if [ "$crash_count" -ge 3 ]; then
      pearl_log "Crash-loop fuse open after $crash_count short runs; sleeping up to 1800s"
      sleep_while_enabled 1800 || break
      crash_count=0
      backoff=2
      continue
    fi
  fi

  pearl_log "Restarting Hermes bridge after ${backoff}s"
  sleep_while_enabled "$backoff" || break
  case "$backoff" in
    2) backoff=5 ;;
    5) backoff=15 ;;
    15) backoff=30 ;;
    30) backoff=60 ;;
    *) backoff=120 ;;
  esac
done

stop_bridge
pearl_log "service: supervisor stopped"
