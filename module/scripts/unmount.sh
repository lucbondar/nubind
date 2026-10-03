#!/system/bin/sh
SELF="$(readlink -f "$0")"
MODDIR=$(dirname "$(dirname "$SELF")")

# Mismo motivo que en mount.sh: forzar el namespace global de PID 1 para
# que el umount le pegue al mount real, no a una vista privada del proceso.
if [ "$(readlink /proc/self/ns/mnt 2>/dev/null)" != "$(readlink /proc/1/ns/mnt 2>/dev/null)" ]; then
    exec nsenter -t 1 -m -- sh "$SELF" "$@"
fi

LOG_FILE="$MODDIR/mount.log"
STATUS_FILE="$MODDIR/status.json"

RCLONE_MOUNTPOINT="/data/local/tmp/nubind_mnt"

# Prioridad: la ruta que quedó realmente montada (guardada en status.json al
# montar) por si config/target_path cambió después sin volver a montar;
# si no hay status, se usa la de config, y si tampoco, la de siempre.
TARGET_PATH="$(sed -n 's/.*"target":"\([^"]*\)".*/\1/p' "$STATUS_FILE" 2>/dev/null)"
if [ -z "$TARGET_PATH" ]; then
    TARGET_PATH="$(cat "$MODDIR/config/target_path" 2>/dev/null)"
fi
[ -z "$TARGET_PATH" ] && TARGET_PATH="/sdcard/Nubind"

# Tiempos por fase (en mount.log) para ver dónde se va la espera al desmontar.
T0=$(date +%s)
tlog() { echo "$(date): unmount: $1 (+$(( $(date +%s) - T0 ))s)" >> "$LOG_FILE"; }

# Primero se marca como desmontado y se detiene el watcher; si no, volvería
# a crear el bind apenas se quite.
echo '{"mounted":false}' > "$STATUS_FILE"
[ -f "$MODDIR/watch.pid" ] && kill "$(cat "$MODDIR/watch.pid")" 2>/dev/null
rm -f "$MODDIR/watch.pid"

# Se desmonta en bucle: intentos anteriores pueden haber dejado varios binds
# apilados en la misma carpeta.
i=0
while [ "$i" -lt 10 ] && umount -l "$TARGET_PATH" 2>/dev/null; do
    i=$((i + 1))
done
tlog "binds quitados"
umount -l "$RCLONE_MOUNTPOINT" 2>>"$LOG_FILE" || "$MODDIR/bin/fusermount3" -u "$RCLONE_MOUNTPOINT" 2>>"$LOG_FILE"

tlog "FUSE desmontado"

# PIDs de los "rclone mount" vivos. Se buscan UNA sola vez: recorrer /proc
# con un fork de "tr" por proceso tardaba ~10 s cada vez en Android (se
# llamaba 2-3 veces por desmontaje). pgrep hace el barrido en un solo
# proceso; si el dispositivo no lo trae, se usa el recorrido lento, pero
# igual una sola vez. Después se espera con "kill -0", que es un builtin
# del shell (instantáneo).
rclone_pids() {
    if command -v pgrep >/dev/null 2>&1; then
        pgrep -f "$MODDIR/bin/rclone mount" 2>/dev/null
        return
    fi
    for d in /proc/[0-9]*; do
        c="$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)"
        case "$c" in
            "$MODDIR/bin/rclone mount "*) echo "${d#/proc/}" ;;
        esac
    done
}
rclone_alive() {
    for p in $RPIDS; do
        kill -0 "$p" 2>/dev/null && return 0
    done
    return 1
}
RPIDS="$(rclone_pids)"
tlog "búsqueda de rclone: ${RPIDS:-ninguno}"

# Por si el mount corre como proceso en background. Se pide el cierre normal
# (TERM) y se espera hasta 20 s a que rclone termine de subir lo pendiente
# (carpetas, archivos en --vfs-write-back); solo si no sale a tiempo se fuerza.
if rclone_alive; then
    kill $RPIDS 2>/dev/null
    i=0
    while [ "$i" -lt 40 ] && rclone_alive; do
        sleep 0.5 2>/dev/null || sleep 1
        i=$((i + 1))
    done
    if rclone_alive; then
        echo "$(date): rclone no terminó en 20 s, se fuerza el cierre" >> "$LOG_FILE"
        kill -9 $RPIDS 2>/dev/null
    fi
    tlog "rclone terminó"
else
    tlog "rclone ya había salido solo"
fi

# Corta la precarga automática si seguía corriendo: el mount ya no existe,
# seguir leyendo archivos ahí solo daría errores.
pkill -f "$MODDIR/scripts/preload.sh" 2>/dev/null
# pkill solo envía la señal: se espera (hasta 5 s) a que preload.sh termine su
# limpieza y suelte el candado antes de forzar el borrado. Borrarlo de
# inmediato dejaba a la precarga sin poder limpiar sus procesos hijos.
i=0
while [ -d "$MODDIR/preload.lock" ] && [ "$i" -lt 10 ]; do
    sleep 0.5 2>/dev/null || sleep 1
    i=$((i + 1))
done
rm -rf "$MODDIR/preload.lock" "$MODDIR/.preload_list"

# Libera la RAM de la caché en RAM (si estaba activa): el tmpfs es
# descartable, así que basta con desmontarlo.
RAM_CACHE_DIR="$MODDIR/cache_ram"
if grep -q " $RAM_CACHE_DIR tmpfs" /proc/mounts; then
    umount -l "$RAM_CACHE_DIR" 2>>"$LOG_FILE"
fi

echo '{"mounted":false}' > "$STATUS_FILE"
echo "$(date): Desmontado" >> "$LOG_FILE"
