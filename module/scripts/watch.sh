#!/system/bin/sh
SELF="$(readlink -f "$0")"
MODDIR=$(dirname "$(dirname "$SELF")")

# Debe correr en el namespace global (PID 1), igual que mount.sh.
if [ "$(readlink /proc/self/ns/mnt 2>/dev/null)" != "$(readlink /proc/1/ns/mnt 2>/dev/null)" ]; then
    exec nsenter -t 1 -m -- sh "$SELF" "$@"
fi

LOG_FILE="$MODDIR/mount.log"
STATUS_FILE="$MODDIR/status.json"
PIDFILE="$MODDIR/watch.pid"
RCLONE_MOUNTPOINT="/data/local/tmp/nubind_mnt"

# Una sola instancia: si quedó un watcher anterior, se reemplaza.
if [ -f "$PIDFILE" ]; then
    OLD="$(cat "$PIDFILE" 2>/dev/null)"
    [ -n "$OLD" ] && [ "$OLD" != "$$" ] && kill "$OLD" 2>/dev/null
fi
echo $$ > "$PIDFILE"
trap 'rm -f "$PIDFILE"' EXIT

echo "$(date): watcher iniciado (pid $$)" >> "$LOG_FILE"

while :; do
    # Si el usuario desmontó desde la app (status mounted:false), se termina.
    grep -q '"mounted":true' "$STATUS_FILE" 2>/dev/null || exit 0
    T="$(sed -n 's/.*"target":"\([^"]*\)".*/\1/p' "$STATUS_FILE" 2>/dev/null)"
    [ -z "$T" ] && exit 0

    # Si el FUSE de rclone murió no hay nada que volver a bindear.
    if ! grep -q " $RCLONE_MOUNTPOINT " /proc/mounts; then
        echo "$(date): watcher: el montaje de rclone ya no existe, se detiene" >> "$LOG_FILE"
        exit 0
    fi

    a="$(stat -c %d "$T" 2>/dev/null)"
    b="$(stat -c %d "$RCLONE_MOUNTPOINT" 2>/dev/null)"
    if [ -n "$b" ] && [ "$a" != "$b" ]; then
        # Registra qué app estaba en primer plano: sirve para identificar
        # quién quita el bind.
        FOCUS="$(dumpsys window 2>/dev/null | grep -m1 mCurrentFocus)"
        echo "$(date): watcher: el bind en $T desapareció; foco: $FOCUS" >> "$LOG_FILE"
        err="$(mount --bind "$RCLONE_MOUNTPOINT" "$T" 2>&1)" || \
            echo "$(date): watcher: rebind falló: $err" >> "$LOG_FILE"
    fi
    sleep 1
done
