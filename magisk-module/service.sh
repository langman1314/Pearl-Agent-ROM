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

start_once() {
  mount_chroot || return 1
  rm -f "$PID_FILE"
  pearl_log "Starting Hermes bridge in Debian chroot"
  chroot "$ROOTFS" /usr/bin/env -i \
    HOME=/data/pearl-agent/hermes-home/home \
    HERMES_HOME=/data/pearl-agent/hermes-home \
    PATH=/opt/pearl-agent/venv/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    TMPDIR=/tmp \
    /usr/local/sbin/pearl-hermes-bridge-wrapper \
    >> "$LOG_DIR/hermes-bridge.log" 2>&1 &
  child=$!
  printf '%s\n' "$child" > "$PID_FILE"
  chmod 0600 "$PID_FILE"
  wait "$child"
  status=$?
  rm -f "$PID_FILE"
  return "$status"
}

if ! wait_for_boot; then
  pearl_log "service: boot_completed timeout; refusing background startup"
  exit 1
fi

if [ -f "$DISABLED_FILE" ]; then
  pearl_log "service: runtime disabled"
  exit 0
fi

mkdir_safe "$LOG_DIR" 0700
mkdir_safe "$RUN_DIR" 0700

crash_count=0
window_start="$(date +%s)"
backoff=2
while [ ! -f "$DISABLED_FILE" ]; do
  started="$(date +%s)"
  start_once
  status=$?
  ended="$(date +%s)"
  runtime=$((ended - started))
  pearl_log "Hermes bridge exited status=$status runtime=${runtime}s"

  [ -f "$DISABLED_FILE" ] && break
  if [ "$runtime" -ge 300 ]; then
    crash_count=0
    window_start="$ended"
    backoff=2
  else
    crash_count=$((crash_count + 1))
    if [ $((ended - window_start)) -gt 600 ]; then
      crash_count=1
      window_start="$ended"
    fi
    if [ "$crash_count" -gt 5 ]; then
      pearl_log "Crash-loop fuse open: sleeping 1800s to protect battery"
      sleep 1800
      crash_count=0
      window_start="$(date +%s)"
      backoff=2
      continue
    fi
  fi

  pearl_log "Restarting Hermes bridge after ${backoff}s"
  sleep "$backoff"
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
