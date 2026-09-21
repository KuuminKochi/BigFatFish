#!/system/bin/sh
set -eu
umask 077
JAR=${1:?Pass the staged helper JAR path}
AUTHORITY=io.github.kuuminkochi.bigfatfish.control
PROCESS=bigfatfish-helper
FINAL=/data/local/tmp/bigfatfish-helper.jar
LOG=/data/local/tmp/bigfatfish-helper.log
PIDFILE=/data/local/tmp/bigfatfish-helper.pid
LOCK=/data/local/tmp/bigfatfish-start.lock
TMP=

verified_helper() {
    for pid in $(/system/bin/pidof "$PROCESS" 2>/dev/null || true); do
        case "$pid" in ''|*[!0-9]*) continue ;; esac
        name=$(/system/bin/tr '\000' '\n' < "/proc/$pid/cmdline" 2>/dev/null | /system/bin/sed -n '1p' || true)
        [ "$name" = "$PROCESS" ] && return 0
    done
    return 1
}
status() {
    /system/bin/content call --user 0 --uri "content://$AUTHORITY" --method status 2>&1 || true
}

tries=0
until /system/bin/mkdir "$LOCK" 2>/dev/null; do
    tries=$((tries + 1))
    [ "$tries" -lt 30 ] || {
        echo "ERROR: startup lock remains at $LOCK. Check that no launcher is running before removing a stale lock manually." >&2
        exit 1
    }
    sleep 1
done
printf '%s\n' "$$" > "$LOCK/owner"
cleanup() {
    [ -z "$TMP" ] || /system/bin/rm -f "$TMP"
    /system/bin/rm -f "$LOCK/owner"
    /system/bin/rmdir "$LOCK" 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP

if ! verified_helper; then
    [ -f "$JAR" ] || { echo "ERROR: staged helper JAR not found." >&2; exit 1; }
    [ -x /system/bin/nohup ] && [ -x /system/bin/setsid ] || {
        echo "ERROR: this device lacks nohup or setsid." >&2; exit 1;
    }
    TMP="$FINAL.install.$$"
    /system/bin/cp "$JAR" "$TMP"
    /system/bin/mv "$TMP" "$FINAL"
    TMP=
    /system/bin/setsid /system/bin/nohup /system/bin/sh -c '
        pidfile=$1; jar=$2; process=$3
        echo $$ > "$pidfile"
        CLASSPATH=$jar; export CLASSPATH
        exec /system/bin/app_process / --nice-name="$process" ObserverHelper
    ' bigfatfish-start "$PIDFILE" "$FINAL" "$PROCESS" >>"$LOG" 2>&1 </dev/null &
fi

i=0
while [ "$i" -lt 30 ]; do
    current=$(status)
    case "$current" in
        *helperReady=true*)
            if verified_helper; then
                echo "READY: BigFatFish helper is registered on-device. Start it again after a full reboot."
                exit 0
            fi ;;
    esac
    sleep 1
    i=$((i + 1))
done
echo "ERROR: helper did not register. Open BigFatFish first and inspect $LOG." >&2
exit 1
