#!/system/bin/sh
# Prueba de rendimiento del montaje activo. La lanza la app en segundo plano
# (RootShell.perfTestStart) y lee el progreso de perf_test.out, una línea por
# evento:
#   STEP|<id>|<RUN|OK|WARN|FAIL|SKIP>|<texto>
#   DONE|<OK|WARN|FAIL>|<texto>|
# No usar "|" dentro de los textos.
#
# Qué comprueba:
#   mount  el montaje existe y rclone está corriendo
#   opts   las opciones con las que corre rclone son las de la configuración
#          actual (si cambiaste el perfil o la caché sin volver a montar, avisa)
#   space  hay espacio para la caché
#   list   listar la carpeta montada funciona y el segundo listado es rápido
#   write  escribir y releer un archivo temporal (se borra al terminar)
#   read   leer un trozo NO cacheado de un archivo grande dos veces y ver si la
#          caché en disco de rclone crece
SELF="$(readlink -f "$0")"
MODDIR=$(dirname "$(dirname "$SELF")")

# Debe correr en el namespace global (PID 1), igual que mount.sh: así ve el bind
# tal como lo ve el resto del sistema.
if [ "$(readlink /proc/self/ns/mnt 2>/dev/null)" != "$(readlink /proc/1/ns/mnt 2>/dev/null)" ]; then
    exec nsenter -t 1 -m -- sh "$SELF" "$@"
fi

OUT="$MODDIR/perf_test.out"
STATUS_FILE="$MODDIR/status.json"
RCLONE_CONF="$MODDIR/config/rclone.conf"
CACHE_DIR="$MODDIR/cache"
RCLONE_MOUNTPOINT="/data/local/tmp/nubind_mnt"
# Si la caché en RAM está activa (mount.sh la monta como tmpfs), es la que
# realmente usa rclone: se miden espacio y crecimiento ahí, no en disco.
if grep -q " $MODDIR/cache_ram tmpfs" /proc/mounts 2>/dev/null; then
    CACHE_DIR="$MODDIR/cache_ram"
    CACHE_IS_RAM=1
else
    CACHE_IS_RAM=0
fi
TEST_MB=32
WORST=OK
TDIR=""

: > "$OUT"

# Si la app corta la prueba a la mitad no debe quedar el archivo temporal.
cleanup() { [ -n "$TDIR" ] && rm -rf "$TDIR" 2>/dev/null; }
trap cleanup EXIT
trap 'exit 1' INT TERM HUP

emit() { echo "$1|$2|$3|$4" >> "$OUT"; }

# step <id> <estado> <texto>; recuerda el peor estado para el veredicto final.
step() {
    emit STEP "$1" "$2" "$3"
    case "$2" in
        FAIL) WORST=FAIL ;;
        WARN) [ "$WORST" = OK ] && WORST=WARN ;;
    esac
}

finish() {
    case "$WORST" in
        OK) msg="Todo funciona como se espera." ;;
        WARN) msg="Funciona, pero hay avisos." ;;
        *) msg="Se encontraron problemas." ;;
    esac
    [ -n "$1" ] && msg="$1"
    emit DONE "$WORST" "$msg" ""
    exit 0
}

# Milisegundos. Si el date del sistema no soporta %N se cae a segundos.
now_ms() {
    n="$(date +%s%N 2>/dev/null)"
    case "$n" in
        ''|*[!0-9]*) echo $(( $(date +%s) * 1000 )) ;;
        *) echo "${n%??????}" ;;
    esac
}

# Velocidad en décimas de MB/s: speed_x10 <MB> <ms>. Piso de 20 ms para no
# reportar velocidades absurdas cuando la medición es casi instantánea.
speed_x10() {
    ms=$2
    [ "$ms" -lt 20 ] && ms=20
    echo $(( $1 * 10000 / ms ))
}
fmt10() { echo "$(( $1 / 10 )).$(( $1 % 10 ))"; }

# Ejecuta con límite de tiempo si el sistema trae "timeout".
tmo() {
    s="$1"
    shift
    if command -v timeout >/dev/null 2>&1; then timeout "$s" "$@"; else "$@"; fi
}

# KB que ocupa la caché de rclone en disco.
cache_kb() {
    set -- $(du -sk "$CACHE_DIR" 2>/dev/null)
    echo "${1:-0}"
}

find_rclone_pid() {
    if command -v pgrep >/dev/null 2>&1; then
        pgrep -f "$MODDIR/bin/rclone mount" 2>/dev/null | head -n 1
        return
    fi
    for d in /proc/[0-9]*; do
        c="$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)"
        case "$c" in
            "$MODDIR/bin/rclone mount "*) echo "${d#/proc/}"; return ;;
        esac
    done
}

# ¿Hay de verdad un proceso de precarga corriendo? preload.lock es una carpeta
# (mkdir), y una carpeta no sabe si quien la creó sigue vivo: si el sistema
# mató preload.sh de un golpe (poca batería, poca memoria) sin dejarlo pasar
# por su propio trap de limpieza, el candado queda ahí pero nadie está
# compitiendo por la red. Mismo patrón y mismo respaldo que find_rclone_pid.
preload_running() {
    if command -v pgrep >/dev/null 2>&1; then
        pgrep -f "$MODDIR/scripts/preload.sh" >/dev/null 2>&1
        return
    fi
    for d in /proc/[0-9]*; do
        c="$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)"
        case "$c" in
            *"$MODDIR/scripts/preload.sh"*) return 0 ;;
        esac
    done
    return 1
}

# ---------------------------------------------------------------- mount
step mount RUN "Comprobando el montaje"
if ! grep -q '"mounted":true' "$STATUS_FILE" 2>/dev/null; then
    step mount FAIL "No hay nada montado."
    finish "Monta un servidor y vuelve a probar."
fi
T="$(sed -n 's/.*"target":"\([^"]*\)".*/\1/p' "$STATUS_FILE" 2>/dev/null)"
REMOTE="$(sed -n 's/.*"remote":"\([^"]*\)".*/\1/p' "$STATUS_FILE" 2>/dev/null)"
if [ -z "$T" ] || [ ! -d "$T" ]; then
    step mount FAIL "La carpeta de destino no existe: $T"
    finish
fi
if ! grep -q " $RCLONE_MOUNTPOINT " /proc/mounts; then
    step mount FAIL "El montaje de rclone ya no existe. Desmonta y vuelve a montar."
    finish
fi
PID="$(find_rclone_pid)"
if [ -z "$PID" ]; then
    step mount FAIL "El proceso de rclone no está corriendo. Desmonta y vuelve a montar."
    finish
fi
step mount OK "$REMOTE montado en $T"

# ----------------------------------------------------------------- opts
step opts RUN "Comparando la configuración con el montaje activo"
ACTIVE="$REMOTE"
. "$MODDIR/scripts/perf_opts.sh"
compute_mount_opts
RTYPE="$(remote_type "$ACTIVE")"
[ -z "$RTYPE" ] && RTYPE=remote

# Valor de una opción dentro de $MOUNT_OPTS: optval --vfs-cache-mode -> full
optval() {
    f="$1"
    prev=""
    for w in $MOUNT_OPTS; do
        if [ "$prev" = "$f" ]; then
            echo "$w"
            return
        fi
        prev="$w"
    done
}

CUR=" $(tr '\0' ' ' < "/proc/$PID/cmdline" 2>/dev/null) "
case "$CUR" in
    *rclone*) ;;
    *) CUR="" ;;
esac
NMISS=0
SHORT=""
miss() {
    NMISS=$(( NMISS + 1 ))
    # Solo se nombran las tres primeras para que el texto quepa en pantalla.
    if [ "$NMISS" -le 3 ]; then
        if [ -z "$SHORT" ]; then SHORT="$1"; else SHORT="$SHORT, $1"; fi
    fi
}

# set -- + shift en vez de "for w in $MOUNT_OPTS": hace falta mirar el
# token SIGUIENTE a cada "--flag" para saber si es su valor o si en
# realidad es la flag booleana siguiente (--vfs-fast-fingerprint, en el
# perfil Máximo, no lleva valor). Con el "for" de antes esa flag booleana
# nunca se llegaba a comprobar: el "--flag" de al lado la pisaba como si
# fuera su valor antes de que el chequeo la mirara.
set -- $MOUNT_OPTS
while [ "$#" -gt 0 ]; do
    flag="$1"
    case "$2" in
        ''|--*)
            # Booleana: sin valor, se busca la flag sola.
            case "$CUR" in
                *" $flag "*) ;;
                *) miss "$flag" ;;
            esac
            shift
            ;;
        *)
            case "$CUR" in
                *" $flag $2 "*) ;;
                *) miss "$flag" ;;
            esac
            shift 2
            ;;
    esac
done
[ "$NMISS" -gt 3 ] && SHORT="$SHORT y $(( NMISS - 3 )) más"

if [ "$PERF" = max ]; then PL="Máximo"; else PL="Equilibrado"; fi
CACHE_MODE="$(optval --vfs-cache-mode)"
CACHE_MAX="$(optval --vfs-cache-max-size)"
DESC="perfil $PL, caché $CACHE_MODE"
[ -n "$CACHE_MAX" ] && DESC="$DESC de hasta $CACHE_MAX"

if [ -z "$CUR" ]; then
    step opts WARN "No se pudo leer las opciones del proceso de rclone."
elif [ "$NMISS" -gt 0 ]; then
    step opts WARN "El montaje activo no usa la configuración actual (difiere en $SHORT). Desmonta y vuelve a montar para aplicarla."
else
    step opts OK "Aplicado: $DESC."
fi

# ---------------------------------------------------------------- space
step space RUN "Midiendo el espacio libre"
set -- $(df -k "$CACHE_DIR" 2>/dev/null | tail -n 1)
FREE_KB="$4"
# La caché existente ya ocupa parte del objetivo: no exigir N GB nuevos
# si una parte de N ya está descargada. du es aproximado y los límites VFS
# siguen siendo blandos.
EXISTING_KB="$(cache_kb)"
case "$EXISTING_KB" in ''|*[!0-9]*) EXISTING_KB=0 ;; esac
# En RAM el tmpfs mide caché + reserva (mount.sh), así que "libre" siempre es
# menor que eso una vez que se llena. Se compara el tamaño del tmpfs, no lo libre.
[ "$CACHE_IS_RAM" = 1 ] && FREE_KB="$2"
case "$FREE_KB" in
    ''|*[!0-9]*)
        step space WARN "No se pudo leer el espacio libre."
        ;;
    *)
        FREE_GB=$(( FREE_KB / 1048576 ))
        CAPACITY_KB=$FREE_KB
        [ "$CACHE_IS_RAM" = 1 ] || CAPACITY_KB=$(( FREE_KB + EXISTING_KB ))
        NEED="${CACHE_MAX%G}"
        case "$NEED" in
            ''|*[!0-9]*)
                step space OK "$FREE_GB GB libres en el almacenamiento."
                ;;
            *)
                RESERVE=0
                case "$MOUNT_OPTS" in *min-free-space*) RESERVE=2 ;; esac
                WHERE="en el almacenamiento"
                [ "$CACHE_IS_RAM" = 1 ] && WHERE="en RAM"
                if [ "$CAPACITY_KB" -lt $(( (NEED + RESERVE) * 1048576 )) ]; then
                    if [ "$CACHE_IS_RAM" = 1 ]; then
                        step space WARN "El tmpfs en RAM mide solo $FREE_GB GB: no caben la caché de $NEED GB y la reserva de $RESERVE GB. Desmonta y vuelve a montar para reajustarlo."
                    else
                        step space WARN "Solo quedan $FREE_GB GB libres $WHERE: no alcanza para completar la caché de $NEED GB y conservar $RESERVE GB. rclone intentará recortarla; archivos abiertos o subidas pendientes pueden superar el límite."
                    fi
                else
                    step space OK "$FREE_GB GB disponibles $WHERE; capacidad suficiente para completar $NEED GB de caché con la reserva. No es un límite duro de uso."
                fi
                ;;
        esac
        ;;
esac

# ----------------------------------------------------------------- list
step list RUN "Listando la carpeta raíz"
t0=$(now_ms)
N1="$(ls -1 "$T" 2>/dev/null | wc -l)"
t1=$(now_ms)
ls -1 "$T" >/dev/null 2>&1
t2=$(now_ms)
N1=$(echo $N1)
L1=$(( t1 - t0 ))
L2=$(( t2 - t1 ))
EMPTY=0
if [ "$N1" = 0 ]; then
    EMPTY=1
    step list WARN "La carpeta montada aparece vacía. Si el servidor tiene archivos, el bind no está mostrando su contenido."
elif [ "$L2" -gt 1500 ]; then
    step list WARN "$N1 elementos. El segundo listado tardó $L2 ms: los listados no se están cacheando."
else
    step list OK "$N1 elementos. Primer listado $L1 ms, segundo $L2 ms."
fi

# ---------------------------------------------------------------- write
step write RUN "Escribiendo $TEST_MB MB de prueba"
TDIR="$T/.nubind-test"
WS=""
RS=""
if ! mkdir -p "$TDIR" 2>/dev/null; then
    step write WARN "No se pudo crear la carpeta de prueba (¿servidor de solo lectura?). Se omite la escritura."
else
    t0=$(now_ms)
    dd if=/dev/zero of="$TDIR/w.bin" bs=1048576 count=$TEST_MB 2>/dev/null
    rc=$?
    t1=$(now_ms)
    dd if="$TDIR/w.bin" of=/dev/null bs=1048576 2>/dev/null
    t2=$(now_ms)
    SZ="$(stat -c %s "$TDIR/w.bin" 2>/dev/null)"
    # Se borra enseguida: así el archivo no llega a subirse a la nube.
    rm -rf "$TDIR" 2>/dev/null
    TDIR=""
    if [ "$rc" != 0 ] || [ "$SZ" != $(( TEST_MB * 1048576 )) ]; then
        step write FAIL "La escritura falló o quedó incompleta (código $rc, tamaño ${SZ:-0} bytes)."
    else
        WS=$(speed_x10 "$TEST_MB" $(( t1 - t0 )))
        RS=$(speed_x10 "$TEST_MB" $(( t2 - t1 )))
        if [ "$WS" -lt 50 ]; then
            step write WARN "Escritura lenta: $(fmt10 "$WS") MB/s (relectura $(fmt10 "$RS") MB/s)."
        else
            step write OK "Escritura $(fmt10 "$WS") MB/s, relectura $(fmt10 "$RS") MB/s."
        fi
    fi
fi

# ----------------------------------------------------------------- read
step read RUN "Buscando un archivo grande para medir la lectura"
F=""
if [ "$EMPTY" = 0 ]; then
    # -size en bloques de 512 bytes: vale igual en toybox y en busybox.
    F="$(tmo 25 find "$T" -maxdepth 3 -type f -size +$(( TEST_MB * 2048 )) 2>/dev/null | head -n 1)"
fi

if [ -z "$F" ]; then
    if [ "$EMPTY" = 1 ]; then
        step read SKIP "Se omite: la carpeta montada está vacía."
    else
        step read SKIP "No hay archivos de más de $TEST_MB MB (o no se encontraron a tiempo) para medir la lectura."
    fi
    finish
fi

SIZE_MB="$(stat -c %s "$F" 2>/dev/null | awk '{printf "%d", $1 / 1048576}')"
# (awk y no $(( )): el mksh de Android usa 32 bits y desbordaba con archivos de más de 2 GiB)
[ -z "$SIZE_MB" ] && SIZE_MB=0
MAXSKIP=$(( SIZE_MB - TEST_MB ))
[ "$MAXSKIP" -lt 0 ] && MAXSKIP=0
# Tramo al azar del archivo: uno ya leído antes estaría en la caché de rclone y
# no mediría la red.
R1=${RANDOM:-$(date +%s)}
R2=${RANDOM:-$R1}
SKIP=$(( (R1 * 32768 + R2) % (MAXSKIP + 1) ))
NAME="${F##*/}"

step read RUN "Leyendo $TEST_MB MB de $NAME (primera vez)"
C0=$(cache_kb)
t0=$(now_ms)
tmo 60 dd if="$F" of=/dev/null bs=1048576 skip=$SKIP count=$TEST_MB 2>/dev/null
rc=$?
t1=$(now_ms)

if [ "$rc" = 124 ]; then
    step read WARN "La primera lectura no terminó en 60 s: el enlace es muy lento (menos de $(fmt10 "$(speed_x10 "$TEST_MB" 60000)") MB/s)."
    finish
elif [ "$rc" != 0 ]; then
    step read FAIL "No se pudo leer $NAME desde la carpeta montada (código $rc)."
    finish
fi

# rclone escribe el tramo leído a la caché de forma asíncrona, y ext4 además
# demora la asignación real de bloques en disco hasta el writeback (delayed
# allocation): medir con du -sk justo después de leer casi siempre da 0
# aunque la caché SÍ esté funcionando. "sync" fuerza el writeback pendiente
# antes de medir (no afecta la velocidad reportada: se mide antes de esto).
sync
sleep 1
C1=$(cache_kb)

step read RUN "Leyendo el mismo tramo otra vez"
t2=$(now_ms)
tmo 60 dd if="$F" of=/dev/null bs=1048576 skip=$SKIP count=$TEST_MB 2>/dev/null
t3=$(now_ms)

CX=$(speed_x10 "$TEST_MB" $(( t1 - t0 )))
WX=$(speed_x10 "$TEST_MB" $(( t3 - t2 )))
GROW_KB=$(( C1 - C0 ))
GROW_MB=$(( GROW_KB / 1024 ))
DETAIL="Primera vez $(fmt10 "$CX") MB/s, segunda $(fmt10 "$WX") MB/s, la caché en disco creció $GROW_MB MB."

# Dos cosas que falsean la medición: una precarga todavía corriendo (compite
# por el ancho de banda) y un remoto ya precargado (la primera lectura sale de
# la caché y no mide la nube).
PRE_WARN=0
if [ -d "$MODDIR/preload.lock" ]; then
    if preload_running; then
        PRE_WARN=1
        DETAIL="$DETAIL La precarga sigue corriendo y compite por la red: espera a que termine para medir."
    else
        # Candado de una precarga que no terminó bien (el sistema la mató sin
        # avisar): no hay nada compitiendo por la red. Se limpia de una vez
        # para no depender del próximo montaje para soltarlo.
        rm -rf "$MODDIR/preload.lock" 2>/dev/null
    fi
fi
if [ "$PRE_WARN" = 0 ] && [ -f "$MODDIR/config/preload_done_$REMOTE" ]; then
    DETAIL="$DETAIL El servidor está precargado: la primera lectura sale de la caché y no mide la velocidad real de la nube."
fi

# Último resultado del otro perfil con el mismo tipo de servidor, para poder
# comparar Equilibrado contra Máximo probando una vez con cada uno.
if [ "$PERF" = max ]; then OTHER=balanced; OL="Equilibrado"; else OTHER=max; OL="Máximo"; fi
PREV="$(cat "$MODDIR/config/perf_last_${OTHER}_$RTYPE" 2>/dev/null)"
set -- $PREV
if [ -n "$1" ] && [ -n "$2" ]; then
    DETAIL="$DETAIL Última prueba en $OL: $(fmt10 "$1") MB/s la primera vez, $(fmt10 "$2") la segunda."
fi
mkdir -p "$MODDIR/config" 2>/dev/null
echo "$CX $WX" > "$MODDIR/config/perf_last_${PERF:-balanced}_$RTYPE"

case "$CACHE_MODE" in
    full)
        if [ "$PRE_WARN" = 1 ]; then
            step read WARN "$DETAIL"
        elif [ "$GROW_KB" -ge $(( TEST_MB * 512 )) ]; then
            step read OK "$DETAIL"
        elif [ "$CX" -ge 1000 ]; then
            step read OK "$DETAIL La primera lectura ya fue muy rápida: ese tramo probablemente ya estaba en caché."
        else
            step read WARN "$DETAIL La caché no creció: las lecturas no se están guardando en disco."
        fi
        ;;
    *)
        step read OK "$DETAIL Este perfil no cachea lecturas en disco, es lo esperado."
        ;;
esac

finish
