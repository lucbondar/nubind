#!/system/bin/sh
# Precarga automática: tras un montaje correcto en perfil Máximo, baja a la
# caché los archivos del remoto montado. Así, cuando una app los abra por
# primera vez, ya están locales en vez de tener que esperar la descarga en
# ese momento.
#
# La lanza mount.sh en segundo plano justo después de "montado
# correctamente"; no bloquea eso ni el resto del arranque. No hace nada fuera
# de Máximo: en Equilibrado, Google Drive también monta con caché completa
# (para que un archivo se sirva entero en vez de a saltos), pero la tarjeta
# de precarga está oculta ahí y no tiene sentido bajar de antemano lo que el
# usuario no pidió.
#
# Descarga en paralelo (scripts/config/preload_workers, por defecto 4,
# tope 8): cada "cat" abre su propia conexión, así que leer varios archivos
# a la vez aprovecha mucho mejor el ancho de banda que uno por uno, sobre
# todo con carpetas que tienen miles de archivos pequeños (que no llegan al
# tamaño mínimo de --vfs-read-chunk-size / --multi-thread-cutoff como para
# paralelizarse por sí solos dentro de rclone). La selección de qué archivos
# entran en el presupuesto se decide antes, en un solo hilo, para que repartir
# el trabajo entre los workers no tenga condiciones de carrera.
#
# No repite trabajo ya hecho: si la caché sigue en disco (no en RAM, que se
# pierde al desmontar), conserva casi todo su tamaño y el remoto tiene la misma
# cantidad de archivos y el mismo tamaño total que la última precarga
# completa, se omite. clear_cache.sh
# borra esta marca al vaciar la caché, así que un remonte tras limpiarla
# vuelve a precargar todo.
#
# Respeta el tamaño de caché configurado (deja 512 MB de margen) y tiene
# topes de tiempo y de cantidad de archivos por seguridad, para no quedarse
# recorriendo para siempre un remoto enorme. Si el remoto tiene mucho más
# contenido del que se usa, conviene acotar la carpeta que se monta (carpeta
# raíz en Drive, bucket/subcarpeta en S3) y, con eso, lo que esto precarga.
#
# Orden: de menor a mayor tamaño (ver más abajo), y el presupuesto se cuenta
# en bytes exactos.
#
# $1 = "force" (opcional): ignora la marca de "ya estaba precargado" y
# vuelve a pasar por todos los archivos seleccionados. La llama así el botón
# "Precargar ahora" de la app; mount.sh la sigue lanzando SIN este argumento
# al montar, para no gastar red de más en cada montaje si no cambió nada. Un
# archivo ya en caché y sin cambios lo sirve rclone desde disco (casi
# instantáneo), así que forzar no vuelve a bajar de la red lo que ya estaba.
SELF="$(readlink -f "$0")"
MODDIR=$(dirname "$(dirname "$SELF")")
FORCE="$1"

if [ "$(readlink /proc/self/ns/mnt 2>/dev/null)" != "$(readlink /proc/1/ns/mnt 2>/dev/null)" ]; then
    exec nsenter -t 1 -m -- sh "$SELF" "$@"
fi

# Fuera del cgroup de la app (el botón "Precargar ahora" la lanza desde su
# shell root): si no, Android la congela al pasar la app a segundo plano.
. "$MODDIR/scripts/proc_detach.sh"
detach_from_app

LOG_FILE="$MODDIR/mount.log"
STATUS_FILE="$MODDIR/status.json"
RCLONE_CONF="$MODDIR/config/rclone.conf"
PRELOAD_STATUS="$MODDIR/preload_status.json"

# Nota: los tamaños se suman siempre con awk y no con $(( )): el mksh de
# Android hace la aritmética en 32 bits con signo, y un archivo de más de
# 2 GiB daba MB negativos (p. ej. -1581 para uno de 2515 MB).

# Escribe preload_status.json de forma atómica (tmp + mv) para que la app,
# que lo lee mientras corre esta precarga, nunca vea un JSON a medio
# escribir. $1 = true/false (sigue corriendo), $2 = archivos hechos, $3 = MB hechos.
write_status() {
    printf '{"running":%s,"remote":"%s","total_files":%s,"selected_files":%s,"selected_mb":%s,"done_files":%s,"done_mb":%s,"updated":%s}\n' \
        "$1" "$ACTIVE" "${TOTAL:-0}" "${N_SELECTED:-0}" "${DONE_MB:-0}" "$2" "$3" "$(date +%s)" \
        > "$PRELOAD_STATUS.tmp" 2>/dev/null && mv "$PRELOAD_STATUS.tmp" "$PRELOAD_STATUS"
}

# Evita dos precargas a la vez. El candado guarda el PID de quien lo tiene: si
# ese proceso ya no existe (kill -9, reinicio a mitad de corrida) el candado
# es huérfano y se recupera, en vez de salir en silencio para siempre.
# PID de esta corrida: el latido y el monitor de progreso lo vigilan para no
# sobrevivirla nunca (ver cleanup).
MAIN_PID=$$
LOCK="$MODDIR/preload.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
    OLD_PID="$(cat "$LOCK/pid" 2>/dev/null)"
    if [ -n "$OLD_PID" ] && [ "$OLD_PID" != "$$" ] && kill -0 "$OLD_PID" 2>/dev/null; then
        exit 0
    fi
    rm -rf "$LOCK"
    mkdir "$LOCK" 2>/dev/null || exit 0
fi
echo $$ > "$LOCK/pid"

# Restos de una corrida anterior que no terminó bien. Va DESPUÉS del candado:
# antes se borraba también cuando otra instancia salía por el candado ocupado,
# pisando los archivos de la corrida que sí estaba en marcha.
rm -f "$MODDIR"/.preload_list "$MODDIR"/.preload_list.raw "$MODDIR"/.preload_selected \
      "$MODDIR"/.preload_part_* "$MODDIR"/.preload_result_* "$MODDIR"/.preload_progress_* \
      "$MODDIR"/.preload_all_done 2>/dev/null

cleanup() {
    # Los hijos propios (latido, monitor, workers) se detienen SIEMPRE. Antes
    # esto estaba detrás de la comprobación del candado: unmount.sh y mount.sh
    # borran preload.lock justo después del pkill, así que al llegar acá el
    # candado ya no existía, la función salía sin matar nada y el monitor de
    # progreso quedaba huérfano para siempre, republicando "running":true
    # cada 2 s (la app se quedaba en "Revisando el remoto…" y "Precargar
    # ahora" apagado). Matar a los propios hijos nunca pisa a una corrida
    # nueva: los PIDs son de esta.
    [ -n "$HB_PID" ] && kill "$HB_PID" 2>/dev/null
    [ -n "$MONITOR_PID" ] && kill "$MONITOR_PID" 2>/dev/null
    [ -n "$WPIDS" ] && kill $WPIDS 2>/dev/null
    # Candado y temporales: solo si siguen siendo de esta corrida (una nueva
    # puede haberlos tomado ya).
    [ "$(cat "$LOCK/pid" 2>/dev/null)" = "$$" ] || return 0
    rm -rf "$LOCK"
    rm -f "$MODDIR"/.preload_list "$MODDIR"/.preload_list.raw "$MODDIR"/.preload_selected \
          "$MODDIR"/.preload_part_* "$MODDIR"/.preload_result_* "$MODDIR"/.preload_progress_* \
          "$MODDIR"/.preload_all_done
}
# TERM/INT/HUP: antes el trap limpiaba pero NO terminaba el script, que seguía
# su curso, escribía "terminada" y borraba los archivos de la corrida nueva.
trap cleanup EXIT
trap 'exit 143' INT TERM HUP

ACTIVE="$(sed -n 's/.*"remote":"\([^"]*\)".*/\1/p' "$STATUS_FILE" 2>/dev/null)"
T="$(sed -n 's/.*"target":"\([^"]*\)".*/\1/p' "$STATUS_FILE" 2>/dev/null)"
# Sin remoto o carpeta montada: no hay nada que precargar todavía. Se borra
# cualquier estado de una precarga anterior para que la app no muestre un
# progreso que ya no corresponde a este montaje.
[ -z "$ACTIVE" ] && { rm -f "$PRELOAD_STATUS"; exit 0; }
if [ -z "$T" ] || [ ! -d "$T" ]; then rm -f "$PRELOAD_STATUS"; exit 0; fi

. "$MODDIR/scripts/perf_opts.sh"
compute_mount_opts

# Solo en Máximo: en Equilibrado, Drive monta con --vfs-cache-mode full igual
# (ver perf_opts.sh), pero la tarjeta de precarga está oculta para ese
# perfil y bajar archivos por adelantado ahí sería red que el usuario no pidió.
if [ "$PERF" != "max" ]; then
    rm -f "$PRELOAD_STATUS"
    exit 0
fi

case "$MOUNT_OPTS" in
    *"--vfs-cache-mode full"*) ;;
    *) rm -f "$PRELOAD_STATUS"; exit 0 ;;
esac

# La caché en RAM (tmpfs) se pierde al desmontar o reiniciar: nunca se puede
# saltar la precarga ahí, siempre está fría al empezar.
CACHE_IS_RAM=0
grep -q " $MODDIR/cache_ram tmpfs" /proc/mounts 2>/dev/null && CACHE_IS_RAM=1

case "$PERF:$(remote_type "$ACTIVE")" in
    max:*) DEFAULT_GB=10 ;;
    *) DEFAULT_GB=1 ;;
esac
BUDGET_MB=$(( ${CACHE_GB:-$DEFAULT_GB} * 1024 - 512 ))
[ "$BUDGET_MB" -lt 256 ] && BUDGET_MB=256

# Workers de descarga en paralelo (config/preload_workers; 1-8, por defecto 4).
WORKERS="$(cat "$MODDIR/config/preload_workers" 2>/dev/null)"
case "$WORKERS" in ''|*[!0-9]*|0) WORKERS=4 ;; esac
[ "$WORKERS" -gt 8 ] && WORKERS=8

# Tope de tiempo por archivo: 300 s como mínimo, y 4 s por MB para los
# grandes (equivale a aguantar hasta ~0,25 MB/s). Un tope fijo cortaba a
# medias los archivos grandes con enlace lento.
# Timeout propio en shell puro, sin depender de que el sistema traiga el
# comando "timeout" (confirmado que este dispositivo no lo tiene: sin esto,
# un archivo con la conexión trabada dejaba el "cat" corriendo para siempre y
# con él el worker y el candado, sin que preload_running lo detecte, porque
# para pgrep ese proceso sigue "vivo" aunque no avance nada). TERM primero;
# si a los 2s sigue ahí (bloqueado en E/S, donde TERM no siempre alcanza),
# remata con KILL.
run_with_timeout() {
    tl="$1"; shift
    "$@" &
    cpid=$!
    (
        # El sleep va en segundo plano y se mata al terminar: si no, cada
        # archivo dejaba un "sleep 300" huérfano (con miles de archivos
        # chicos, miles de procesos hasta agotar los PID: "Cannot fork").
        sleep "$tl" &
        sp=$!
        trap 'kill "$sp" 2>/dev/null; exit 0' TERM
        wait "$sp" 2>/dev/null
        kill -TERM "$cpid" 2>/dev/null; sleep 2; kill -KILL "$cpid" 2>/dev/null
    ) &
    wpid=$!
    wait "$cpid" 2>/dev/null
    rc=$?
    kill "$wpid" 2>/dev/null
    wait "$wpid" 2>/dev/null
    return "$rc"
}
TAB="$(printf '\t')"
# Latido mientras se lista el remoto (en un FTP grande puede tardar minutos):
# la app lo toma como "sigue viva" y no como una precarga colgada.
( while kill -0 "$MAIN_PID" 2>/dev/null; do write_status true 0 0; sleep 5; done ) &
HB_PID=$!
FILELIST="$MODDIR/.preload_list"

# Lista "bytes<TAB>ruta" en UNA pasada: find agrupa las rutas y las pasa a
# stat de a cientos (-exec ... {} +). Antes se recorría el remoto con find,
# después otra vez entera con "du" para la huella, y luego se lanzaban
# stat + awk por cada archivo (dos procesos por archivo: con 20000 archivos,
# minutos de CPU solo en preparar la lista). Si el find del sistema no
# soporta "{} +", se cae al modo lento archivo por archivo.
#
# Orden: de menor a mayor tamaño. Las apps suelen abrir primero muchos
# archivos chicos (índices, configuraciones, shaders, manifiestos) y recién
# después los paquetes grandes; así lo que más se pide al arrancar queda en
# caché primero, y el presupuesto cubre la mayor cantidad de archivos posible.
# S3 (Oracle, AWS, Cloudflare R2...) y Google Drive: el listado NO se hace recorriendo el
# montaje con find. find baja carpeta por carpeta y cada carpeta es una
# petición de listado al servidor, una detrás de otra; en un bucket con cientos
# de carpetas (un juego de Unity) eso tarda minutos, y si el usuario cambiaba
# de servidor antes de que terminara, la precarga quedaba en "0 archivos" sin
# haber descargado nada. "rclone lsf -R --fast-list" pide el árbol entero
# en pocas peticiones (hasta 1000 objetos por petición) y tarda segundos. Solo
# se usa para saber QUÉ precargar; los archivos se siguen leyendo por el
# montaje, así que lo que entra a la caché es lo mismo. En Drive se descartan
# los archivos de tamaño desconocido (Docs/Sheets, que lsf da como -1). Si lsf falla, se cae al
# find de siempre.
LIST_DONE=0
RTYPE="$(remote_type "$ACTIVE")"
case "$RTYPE" in s3|drive) LSF_OK=1 ;; *) LSF_OK=0 ;; esac
if [ "$LSF_OK" = 1 ] && [ -x "$MODDIR/bin/rclone" ]; then
    . "$MODDIR/scripts/env.sh"
    LS_T0="$(date +%s)"
    "$MODDIR/bin/rclone" lsf "$ACTIVE:$(remote_root "$ACTIVE")" -R --files-only \
        --format sp --separator "$TAB" --fast-list \
        --exclude '.nubind-test/**' ${DRIVE_PACER_OPTS:-} \
        --config "$RCLONE_CONF" --cache-dir "$MODDIR/cache" \
        --log-level ERROR --log-file "$LOG_FILE" 2>/dev/null |
        awk -F "$TAB" -v t="$T" -v OFS="$TAB" \
            '{ i = index($0, "\t"); if (i && $1 + 0 >= 0) print $1, t "/" substr($0, i + 1) }' \
        > "$FILELIST.raw"
    if [ -s "$FILELIST.raw" ]; then
        LIST_DONE=1
        echo "$(date): Precarga: listado de '$ACTIVE' con rclone lsf en $(( $(date +%s) - LS_T0 ))s" >> "$LOG_FILE"
    else
        echo "$(date): Precarga: rclone lsf no devolvió nada en $(( $(date +%s) - LS_T0 ))s, se usa find sobre el montaje" >> "$LOG_FILE"
    fi
fi

if [ "$LIST_DONE" = 0 ]; then
    find "$T" -type f -not -path '*/.nubind-test/*' \
        -exec stat -c "%s${TAB}%n" {} + 2>/dev/null > "$FILELIST.raw"
    if [ ! -s "$FILELIST.raw" ]; then
        find "$T" -type f -not -path '*/.nubind-test/*' 2>/dev/null |
            while IFS= read -r f; do
                stat -c "%s${TAB}%n" "$f" 2>/dev/null
            done > "$FILELIST.raw"
    fi
fi

# Si mientras se listaba se desmontó o se cambió de servidor, no hay nada que
# precargar: se sale diciéndolo, en vez de registrar "0 archivos" como si el
# remoto estuviera vacío.
NOW_ACTIVE="$(sed -n 's/.*"remote":"\([^"]*\)".*/\1/p' "$STATUS_FILE" 2>/dev/null)"
if [ "$NOW_ACTIVE" != "$ACTIVE" ]; then
    echo "$(date): Precarga: '$ACTIVE' ya no está montado, se cancela (el listado no terminó de usarse)" >> "$LOG_FILE"
    kill "$HB_PID" 2>/dev/null; wait "$HB_PID" 2>/dev/null; HB_PID=""
    rm -f "$PRELOAD_STATUS" "$FILELIST.raw"
    exit 0
fi
sort -n "$FILELIST.raw" > "$FILELIST" 2>/dev/null || mv -f "$FILELIST.raw" "$FILELIST"
rm -f "$FILELIST.raw"
TOTAL="$(wc -l < "$FILELIST" 2>/dev/null | tr -d ' ')"
[ -z "$TOTAL" ] && TOTAL=0

# Huella barata del remoto (cantidad de archivos + KB totales) para saber si
# ya se precargó por completo la vez pasada. Los KB salen de la misma lista
# (awk usa coma flotante: no desborda con remotos de más de 2 GiB).
MARKER="$MODDIR/config/preload_done_$ACTIVE"
FP_NOW="$TOTAL $(awk -F "$TAB" '{s += $1} END {printf "%d", s / 1024}' "$FILELIST" 2>/dev/null)"

# El marcador guarda "<archivos> <KB del remoto> <KB de cache/vfs>". Además de
# que el remoto no haya cambiado, la caché en disco debe seguir ahí (al menos
# el 90 % de lo que había al terminar): rclone la purga por antigüedad
# (--vfs-cache-max-age) o por espacio, y sin esta comprobación se diría "ya
# estaba precargado" con la caché vacía.
CACHE_VFS="$MODDIR/cache/vfs"
MARKER_OK=0
if [ "$FORCE" != "force" ] && [ "$CACHE_IS_RAM" = 0 ] && [ -f "$MARKER" ]; then
    set -- $(cat "$MARKER" 2>/dev/null)
    if [ "$1 $2" = "$FP_NOW" ]; then
        case "$3" in
            ''|*[!0-9]*) ;;
            *)
                CUR_KB="$(du -sk "$CACHE_VFS" 2>/dev/null | awk '{print $1}')"
                case "$CUR_KB" in ''|*[!0-9]*) CUR_KB=0 ;; esac
                [ "$3" -gt 0 ] && [ "$CUR_KB" -ge $(( $3 * 9 / 10 )) ] && MARKER_OK=1
                ;;
        esac
    fi
fi

if [ "$MARKER_OK" = 1 ]; then
    kill "$HB_PID" 2>/dev/null; wait "$HB_PID" 2>/dev/null; HB_PID=""
    echo "$(date): Precarga: '$ACTIVE' ya estaba precargado por completo (sin cambios), se omite" >> "$LOG_FILE"
    N_SELECTED="$TOTAL"
    DONE_MB="$(( $(printf '%s' "$FP_NOW" | awk '{print $2}') / 1024 ))"
    write_status false "$TOTAL" "$DONE_MB"
    exit 0
fi

# Tope de archivos por corrida (config/preload_max_files; por defecto 20000).
# Hay remotos con miles de archivos sueltos que no se acercan al presupuesto
# en MB; sigue habiendo un tope (y no "sin límite") para no recorrer para
# siempre un remoto enorme con contenido que nadie va a abrir.
MAX_FILES="$(cat "$MODDIR/config/preload_max_files" 2>/dev/null)"
case "$MAX_FILES" in ''|*[!0-9]*|0) MAX_FILES=20000 ;; esac
[ "$MAX_FILES" -gt 200000 ] && MAX_FILES=200000

echo "$(date): Precarga: '$ACTIVE', $TOTAL archivos, hasta ${BUDGET_MB} MB (tope $MAX_FILES archivos), $WORKERS en paralelo" >> "$LOG_FILE"

# ---- Selección y reparto en un solo awk (sin transferir datos ni lanzar un
# proceso por archivo). El presupuesto se cuenta en bytes: antes se sumaban
# MB enteros por archivo y todo lo menor a 1 MB contaba 0, así que miles de
# archivos chicos podían pasarse del presupuesto sin que se notara. Cada
# archivo va a una única partición (round-robin): sin condiciones de carrera
# entre workers. Línea de partición: "MB<TAB>bytes<TAB>ruta" (los MB para la
# aritmética de 32 bits del shell; los bytes para el progreso exacto).
set -- $(awk -F "$TAB" -v budget="$BUDGET_MB" -v maxf="$MAX_FILES" \
              -v workers="$WORKERS" -v dir="$MODDIR" -v OFS="$TAB" '
    NR > maxf { exit }
    {
        b = $1 + 0
        if (used + b > budget * 1048576) next
        used += b
        p = n % workers
        n++
        print int(b / 1048576), b, substr($0, index($0, "\t") + 1) > (dir "/.preload_part_" p)
    }
    END { printf "%d %d\n", n, used / 1048576 }
' "$FILELIST" 2>/dev/null)
N_SELECTED="${1:-0}"
DONE_MB="${2:-0}"
kill "$HB_PID" 2>/dev/null; wait "$HB_PID" 2>/dev/null; HB_PID=""
write_status true 0 0

preload_worker() {
    # $1 = archivo con las rutas de este worker; $2 = número del worker (solo para el log)
    ok=0
    while IFS="$TAB" read -r SZ_MB SZ_B f; do
        [ -n "$f" ] || continue
        case "$SZ_MB" in ''|*[!0-9]*) SZ_MB=0 ;; esac
        TL=$(( SZ_MB * 4 ))
        [ "$TL" -lt 300 ] && TL=300
        # Solo se registra cada archivo grande (>= 8 MB) con su tiempo: con
        # miles de archivos chicos, una línea y dos "date" por archivo
        # inflaban mount.log y costaban más CPU que la propia lectura.
        [ "$SZ_MB" -ge 8 ] && t0="$(date +%s)"
        if run_with_timeout "$TL" cat "$f" > /dev/null 2>>"$LOG_FILE"; then
            ok=$(( ok + 1 ))
            [ "$SZ_MB" -ge 8 ] && \
                echo "$(date): Precarga[$2]: ${f#$T/} (${SZ_MB} MB, $(( $(date +%s) - t0 ))s)" >> "$LOG_FILE"
            # Solo este worker escribe en su propio archivo: sin condiciones
            # de carrera entre workers. Lo lee el monitor de progreso.
            echo "$SZ_B" >> "$MODDIR/.preload_progress_$2"
        else
            echo "$(date): Precarga[$2]: falló ${f#$T/}" >> "$LOG_FILE"
        fi
    done < "$1"
    echo "$ok" > "$MODDIR/.preload_result_$2"
}

# Archivos hechos y MB hechos ("<archivos> <MB>") sumando lo que anotaron los
# workers, en un solo awk (bytes en coma flotante: sin desbordes).
progress_sum() {
    set -- "$MODDIR"/.preload_progress_*
    [ -f "$1" ] || { echo "0 0"; return; }
    awk '{ s += $1; n++ } END { printf "%d %d\n", n, s / 1048576 }' "$@" 2>/dev/null || echo "0 0"
}

# Progreso en vivo para la app (tarjeta de precarga en Inicio): cada 2s
# publica en preload_status.json lo que los workers llevan hecho. Corre en
# paralelo a los workers y se apaga solo al ver .preload_all_done (lo crea
# este script justo después de "wait").
(
    while [ ! -f "$MODDIR/.preload_all_done" ] && kill -0 "$MAIN_PID" 2>/dev/null; do
        set -- $(progress_sum)
        write_status true "${1:-0}" "${2:-0}"
        sleep 2
    done
) &
MONITOR_PID=$!

# Se guardan los PIDs de los workers: un "wait" sin argumentos esperaría
# también al monitor de progreso, que solo termina cuando existe
# .preload_all_done (que se crea después del wait) y el script se colgaría
# para siempre. Si no hay ningún worker no se espera a nada.
WPIDS=""
w=0
while [ "$w" -lt "$WORKERS" ]; do
    PART="$MODDIR/.preload_part_$w"
    if [ -s "$PART" ]; then
        ( trap '[ -n "$cpid" ] && kill "$cpid" 2>/dev/null; exit 143' TERM INT HUP; preload_worker "$PART" "$w" ) &
        WPIDS="$WPIDS $!"
    fi
    w=$(( w + 1 ))
done
[ -n "$WPIDS" ] && wait $WPIDS
touch "$MODDIR/.preload_all_done"
wait "$MONITOR_PID" 2>/dev/null

PN=0
for rf in "$MODDIR"/.preload_result_*; do
    [ -f "$rf" ] || continue
    v="$(cat "$rf" 2>/dev/null)"
    case "$v" in ''|*[!0-9]*) ;; *) PN=$(( PN + v )) ;; esac
done

# MB realmente bajados (suma final de lo que cada worker fue anotando), no el
# presupuesto ($DONE_MB de la selección): si algún archivo falló, difieren.
set -- $(progress_sum)
FINAL_MB="${2:-0}"
write_status false "$PN" "$FINAL_MB"

echo "$(date): Precarga terminada: ${FINAL_MB} de ${DONE_MB} MB, $PN de $N_SELECTED archivos ($TOTAL en total)" >> "$LOG_FILE"

# Marca de "precarga completa" solo si de verdad se cubrió todo el remoto
# (nada se salteó por presupuesto ni falló). Así el próximo montaje, si nada
# cambió, no vuelve a bajar lo que ya está en disco.
if [ "$CACHE_IS_RAM" = 0 ] && [ "$PN" = "$TOTAL" ] && [ "$TOTAL" -gt 0 ]; then
    mkdir -p "$MODDIR/config" 2>/dev/null
    sync
    echo "$FP_NOW $(du -sk "$CACHE_VFS" 2>/dev/null | awk '{print $1+0}')" > "$MARKER"
fi
