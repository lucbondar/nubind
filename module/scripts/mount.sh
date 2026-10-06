#!/system/bin/sh
SELF="$(readlink -f "$0")"
MODDIR=$(dirname "$(dirname "$SELF")")

# Si esto corre desde el "su" de la app (RootShell -> libsu) o desde
# ciertos service.sh, el proceso puede quedar en un mount namespace
# PRIVADO en vez del namespace global (el de init/PID 1). El mount y el
# bind se hacen igual y rclone loguea éxito ("Montado correctamente"),
# pero el bind solo existe en ese namespace aislado — ningún otro
# proceso del sistema (explorador de archivos incluido) lo ve. Por eso
# "dice que monta pero no aparecen los archivos". Forzamos re-ejecutar
# este script ya adentro del namespace de PID 1 para que el mount se
# propague a todo el sistema.
if [ "$(readlink /proc/self/ns/mnt 2>/dev/null)" != "$(readlink /proc/1/ns/mnt 2>/dev/null)" ]; then
    exec nsenter -t 1 -m -- sh "$SELF" "$@"
fi

RCLONE_BIN="$MODDIR/bin/rclone"
RCLONE_CONF="$MODDIR/config/rclone.conf"
LOG_FILE="$MODDIR/mount.log"
STATUS_FILE="$MODDIR/status.json"
CACHE_DIR="$MODDIR/cache"

# Punto donde rclone monta realmente el FTP
RCLONE_MOUNTPOINT="/data/local/tmp/nubind_mnt"
# Ruta final visible en el almacenamiento interno (bind). Configurable desde
# la app (config/target_path); sin ese archivo se usa la de siempre.
TARGET_PATH="$(cat "$MODDIR/config/target_path" 2>/dev/null)"
[ -z "$TARGET_PATH" ] && TARGET_PATH="/sdcard/Nubind"

# HOME, PATH (fusermount3) y certificados TLS para rclone: ver env.sh.
. "$MODDIR/scripts/env.sh"

# Fuera del cgroup de la app y protegido del low memory killer: rclone, el
# watcher y la precarga se lanzan desde este shell y lo heredan. Ver
# proc_detach.sh (sin esto, Android congela rclone al pasar la app a segundo
# plano).
. "$MODDIR/scripts/proc_detach.sh"
detach_from_app -800

# rclone escribe en este log con nivel INFO durante todo el montaje y no se
# rotaba nunca: crecía sin límite en /data/adb. Se rota al montar si pasa de
# 4 MB (queda una copia anterior en mount.log.1).
LOG_KB="$(du -k "$LOG_FILE" 2>/dev/null | awk '{print $1+0}')"
if [ "${LOG_KB:-0}" -gt 4096 ]; then
    mv -f "$LOG_FILE" "$LOG_FILE.1" 2>/dev/null
fi
unset LOG_KB

if [ ! -f "$RCLONE_CONF" ]; then
    echo "$(date): No hay rclone.conf, configura el FTP desde la app" >> "$LOG_FILE"
    echo "No hay rclone.conf: configura un servidor desde la app"
    exit 1
fi

# Servidor seleccionado en la app (config/active). Sin ese archivo se usa
# "remote", el nombre que usaban las versiones con un solo servidor.
ACTIVE="$(cat "$MODDIR/config/active" 2>/dev/null)"
[ -z "$ACTIVE" ] && ACTIVE="remote"
. "$MODDIR/scripts/perf_opts.sh"
if [ -z "$(remote_type "$ACTIVE")" ]; then
    echo "$(date): No existe el servidor '$ACTIVE' en rclone.conf" >> "$LOG_FILE"
    echo "No existe el servidor '$ACTIVE' en rclone.conf"
    exit 1
fi

# Opciones de montaje según el perfil de rendimiento y el tipo de remoto
# (scripts/perf_opts.sh; scripts/perf_test.sh usa el mismo cálculo para
# comprobar que el montaje activo las tiene aplicadas).
compute_mount_opts

# Caché en RAM (tmpfs) del perfil Máximo: opcional, la activa el usuario
# desde la app con una confirmación explícita (usa RAM mientras esté montado
# y se pierde al desmontar o reiniciar). Se decide aquí, con root, porque hay
# que comprobar la RAM libre antes de montar el tmpfs; unmount.sh la libera.
# Fuente de verdad de si está activa: que el tmpfs esté realmente montado en
# RAM_CACHE_DIR (no el archivo de config, que solo expresa lo que se pidió) —
# así perf_test.sh puede saberlo con una simple lectura de /proc/mounts.
RAM_CACHE_DIR="$MODDIR/cache_ram"
if [ "$PERF" = max ] && [ "$(cat "$MODDIR/config/ram_cache" 2>/dev/null)" = "1" ]; then
    AVAIL_KB="$(awk '/MemAvailable/{print $2}' /proc/meminfo 2>/dev/null)"
    case "$AVAIL_KB" in ''|*[!0-9]*) AVAIL_KB=0 ;; esac
    AVAIL_MB=$(( AVAIL_KB / 1024 ))
    # tmpfs incluye el margen VFS de 2G. Dejar además al menos 1G o
    # el 25% de MemAvailable para las apps en uso, Android y buffers de rclone.
    RESERVE_MB=$(( AVAIL_MB / 4 ))
    [ "$RESERVE_MB" -ge 1024 ] || RESERVE_MB=1024
    NEED_MB=$(( (${CACHE_GB:-10} + 2) * 1024 + RESERVE_MB ))
    if [ "$AVAIL_MB" -ge "$NEED_MB" ]; then
        mkdir -p "$RAM_CACHE_DIR"
        if ! grep -q " $RAM_CACHE_DIR tmpfs" /proc/mounts; then
            # +2G sobre la caché: --vfs-cache-min-free-space 2G haría que rclone
            # dejara 2G libres dentro del propio tmpfs y la caché útil quedara
            # en N-2. Solo ocupa RAM lo que se escribe de verdad.
            mount -t tmpfs -o "size=$(( ${CACHE_GB:-10} + 2 ))G,mode=0700" tmpfs "$RAM_CACHE_DIR" 2>>"$LOG_FILE"
        fi
        if grep -q " $RAM_CACHE_DIR tmpfs" /proc/mounts; then
            CACHE_DIR="$RAM_CACHE_DIR"
            echo "$(date): Caché en RAM activa (${CACHE_GB:-10}G, ${AVAIL_MB}M libres)" >> "$LOG_FILE"
        else
            echo "$(date): No se pudo montar el tmpfs de caché en RAM, se usa disco" >> "$LOG_FILE"
        fi
    else
        echo "$(date): Caché en RAM pedida pero solo hay ${AVAIL_MB}M libres (hacen falta ${NEED_MB}M); se usa disco" >> "$LOG_FILE"
    fi
fi
mkdir -p "$CACHE_DIR"

mkdir -p "$RCLONE_MOUNTPOINT"
# Estado actual: el montaje FUSE de rclone y el bind sobre /sdcard/Nubind son
# dos cosas distintas. Si un intento anterior dejó el FUSE pero no el bind,
# solo hay que completar el bind.
is_fuse_mounted() { grep -q " $RCLONE_MOUNTPOINT " /proc/mounts; }

# OJO: /proc/mounts muestra la ruta REAL ya resuelta (/sdcard es un symlink
# a /storage/emulated/0 o similar), nunca "/sdcard/Nubind". Buscar ese texto
# ahí daba siempre "no está bound" aunque el bind sí se hubiera hecho, y el
# script reportaba "Falló el bind" en cada intento (apilando un bind nuevo
# encima del anterior cada vez). Se compara el id de dispositivo: un bind
# de la carpeta FUSE tiene exactamente el mismo st_dev que el original.
is_bound_at() {
    a="$(stat -c %d "$1" 2>/dev/null)"
    b="$(stat -c %d "$RCLONE_MOUNTPOINT" 2>/dev/null)"
    [ -n "$a" ] && [ "$a" = "$b" ]
}

try_bind() {
    # $1 = ruta destino a probar
    mkdir -p "$1" 2>>"$LOG_FILE"
    is_bound_at "$1" && return 0
    err="$(mount --bind "$RCLONE_MOUNTPOINT" "$1" 2>&1)" || \
        echo "$(date): mount --bind hacia $1 falló: $err" >> "$LOG_FILE"
    is_bound_at "$1"
}

# Ruta de respaldo: el almacenamiento real debajo de /sdcard. Dentro del
# namespace de PID 1 a veces /sdcard no apunta a la vista de usuario.
fallback_path() {
    case "$TARGET_PATH" in
        /sdcard/*) echo "/data/media/0/${TARGET_PATH#/sdcard/}" ;;
        /storage/emulated/0/*) echo "/data/media/0/${TARGET_PATH#/storage/emulated/0/}" ;;
        /storage/self/primary/*) echo "/data/media/0/${TARGET_PATH#/storage/self/primary/}" ;;
    esac
}

do_bind() {
    USED="$TARGET_PATH"
    if ! try_bind "$TARGET_PATH"; then
        FB="$(fallback_path)"
        if [ -n "$FB" ] && try_bind "$FB"; then
            USED="$FB"
        else
            echo '{"mounted":false}' > "$STATUS_FILE"
            echo "$(date): Falló el bind hacia $TARGET_PATH" >> "$LOG_FILE"
            echo "Falló el bind hacia $TARGET_PATH"
            exit 1
        fi
    fi
    # Se guarda la ruta REAL usada: unmount.sh debe apuntar a esta aunque
    # el usuario cambie la ruta desde la app mientras sigue montado.
    echo "{\"mounted\":true,\"remote\":\"$ACTIVE\",\"target\":\"$USED\"}" > "$STATUS_FILE"
    echo "$(date): '$ACTIVE' montado correctamente en $USED" >> "$LOG_FILE"
    # Vigila el bind y lo rehace si algo (p. ej. el launcher de otra app) lo
    # quita. Stdio a /dev/null para no dejar colgada la shell root de la app.
    ( sh "$MODDIR/scripts/watch.sh" </dev/null >/dev/null 2>&1 & )

    # Precarga automática de la caché (ver preload.sh: no hace nada si el
    # perfil activo no cachea lecturas completas). Se corta cualquier
    # precarga anterior antes de lanzar esta, para no acumular procesos en
    # remontajes seguidos.
    #
    # pkill solo ENVÍA la señal, no espera a que el proceso viejo termine de
    # verdad. Si acá se borraba el candado sin esperar, y ese proceso viejo
    # todavía estaba a medio limpiar sus archivos temporales (.preload_list,
    # .preload_part_*...), la precarga nueva arrancaba sobre esos mismos
    # archivos a medio escribir/borrar y terminaba viendo 0 archivos para
    # precargar en vez de los que había (visto en mount.log: dos líneas
    # "Precarga: ..." con un total distinto en el mismo segundo). Se espera
    # hasta 5s a que el propio trap de salida de preload.sh suelte el
    # candado antes de forzar el borrado y lanzar la nueva; todo en segundo
    # plano para no demorar la confirmación de montaje.
    pkill -f "$MODDIR/scripts/preload.sh" 2>/dev/null
    (
        i=0
        while [ -d "$MODDIR/preload.lock" ] && [ "$i" -lt 5 ]; do
            sleep 1
            i=$((i + 1))
        done
        rm -rf "$MODDIR/preload.lock"
        sh "$MODDIR/scripts/preload.sh" </dev/null >/dev/null 2>&1
    ) &

    exit 0
}

if is_fuse_mounted; then
    do_bind
fi

echo "$(date): opciones de montaje: $MOUNT_OPTS" >> "$LOG_FILE"

# S3: respaldo por variable de entorno para que las carpetas vacías creadas
# desde el explorador se guarden como objeto marcador "carpeta/" y no se
# pierdan al desmontar. rclone ignora las variables RCLONE_* de opciones que su
# versión no conoce, así que en un binario viejo no hace nada.
if [ "$(remote_type "$ACTIVE")" = s3 ]; then
    export RCLONE_S3_DIRECTORY_MARKERS=true
    case "$MOUNT_OPTS" in
        *--s3-directory-markers*) ;;
        *) echo "$(date): aviso: rclone no reconoce --s3-directory-markers (se necesita 1.64+); las carpetas vacías se perderán al desmontar" >> "$LOG_FILE" ;;
    esac
fi

# S3: si el servidor tiene bucket (bind_path) se monta ese bucket y no la lista
# de todos; útil cuando la cuenta no puede listar buckets (p. ej. Oracle).
REMOTE_ROOT="$(remote_root "$ACTIVE")"
echo "$(date): montando '$ACTIVE:$REMOTE_ROOT' (tipo $(remote_type "$ACTIVE"))" >> "$LOG_FILE"

# Comprobación rápida de que el remoto responde ANTES de lanzar el montaje.
# Sin red (o con el servidor caído) `rclone mount --daemon` esperaba sus plazos
# largos de conexión (más de un minuto) y la app se quedaba en "Trabajando…"
# sin decir nada; así falla en unos segundos y con el motivo en la salida.
PRE_LIMIT=""
command -v timeout >/dev/null 2>&1 && PRE_LIMIT="timeout 25"
PRE_ERR="$($PRE_LIMIT "$RCLONE_BIN" lsd "$ACTIVE:$REMOTE_ROOT" --max-depth 1 \
    --config "$RCLONE_CONF" \
    --contimeout 8s --timeout 15s --retries 1 --low-level-retries 1 \
    --log-level ERROR 2>&1 >/dev/null)"
if [ $? -ne 0 ]; then
    PRE_MSG="$(printf '%s\n' "$PRE_ERR" | grep -v '^[[:space:]]*$' | tail -n 1 | cut -c1-200)"
    [ -z "$PRE_MSG" ] && PRE_MSG="sin respuesta (sin red o tiempo agotado)"
    echo '{"mounted":false}' > "$STATUS_FILE"
    echo "$(date): '$ACTIVE' no responde, no se monta: $PRE_MSG" >> "$LOG_FILE"
    echo "No se pudo conectar con '$ACTIVE': $PRE_MSG"
    exit 1
fi

"$RCLONE_BIN" mount "$ACTIVE:$REMOTE_ROOT" "$RCLONE_MOUNTPOINT" \
    --config "$RCLONE_CONF" \
    --cache-dir "$CACHE_DIR" \
    --allow-other \
    $MOUNT_OPTS \
    --daemon \
    --log-file "$LOG_FILE" \
    --log-level INFO

# Con --daemon, rclone ya espera a que el montaje esté listo antes de
# volver; el "sleep 2" fijo de antes solo agregaba latencia a cada montaje.
# Se sondea hasta 10 s por si el binario es viejo o el FUSE tarda en aparecer.
i=0
while ! is_fuse_mounted && [ "$i" -lt 20 ]; do
    sleep 0.5 2>/dev/null || sleep 1
    i=$((i + 1))
done

if is_fuse_mounted; then
    do_bind
else
    echo '{"mounted":false}' > "$STATUS_FILE"
    echo "$(date): Fallo al montar rclone" >> "$LOG_FILE"
    MNT_ERR="$(grep -iE 'error|failed|fatal' "$LOG_FILE" 2>/dev/null | tail -n 1 | cut -c1-200)"
    echo "Fallo al montar rclone${MNT_ERR:+: $MNT_ERR}"
    exit 1
fi
