#!/system/bin/sh
# Borra la caché en disco de rclone (VFS cache: bloques y metadatos de los
# archivos leídos/escritos). rclone la vuelve a llenar sola al acceder de
# nuevo, así que es segura de borrar — pero SOLO con el bind desmontado: si
# el perfil usa vfs-cache-mode "writes" o "full" y hay escrituras aún sin
# subir al remoto, borrarla ahora las perdería. Por eso se corta si el
# status dice que sigue montado, en vez de arriesgar datos del usuario.
#
# Sin argumentos borra la caché de TODOS los servidores (con nada montado).
# Con un nombre de servidor ($1) borra solo la de ese servidor: rclone guarda
# cada remoto en cache/vfs/<servidor>{hash} y cache/vfsMeta/<servidor>{hash}
# (ver server_cache_dirs en perf_opts.sh). Eso se
# puede hacer con otro servidor montado; solo se rechaza si el montado es
# justo ese.
SELF="$(readlink -f "$0")"
MODDIR=$(dirname "$(dirname "$SELF")")
CACHE_DIR="$MODDIR/cache"
STATUS_FILE="$MODDIR/status.json"
NAME="$1"
. "$MODDIR/scripts/perf_opts.sh"

MOUNTED=0
grep -q '"mounted":true' "$STATUS_FILE" 2>/dev/null && MOUNTED=1

if [ -z "$NAME" ]; then
    [ "$MOUNTED" = 1 ] && { echo "ERROR: desmonta primero"; exit 1; }

    BEFORE_KB=$(du -sk "$CACHE_DIR" 2>/dev/null | awk '{print $1}')
    [ -z "$BEFORE_KB" ] && BEFORE_KB=0

    rm -rf "${CACHE_DIR:?}"/* 2>/dev/null
    mkdir -p "$CACHE_DIR"

    # La caché quedó vacía: ninguna marca de "precarga completa" (preload.sh)
    # sigue siendo válida. Si no se borran, el próximo montaje creería que ya
    # está todo precargado y no bajaría nada. También se borra el progreso
    # mostrado en la app, que ahora mismo mentiría diciendo "listo".
    rm -f "$MODDIR/config"/preload_done_* "$MODDIR/preload_status.json" 2>/dev/null

    echo "OK $BEFORE_KB"
    exit 0
fi

# Un solo servidor. El nombre se usa como carpeta: nunca una ruta.
case "$NAME" in
    */*|.|..) echo "ERROR: nombre no válido"; exit 1 ;;
esac

if [ "$MOUNTED" = 1 ]; then
    MOUNTED_NAME="$(sed -n 's/.*"remote":"\([^"]*\)".*/\1/p' "$STATUS_FILE" 2>/dev/null)"
    # Si no se sabe cuál está montado, se asume que es este.
    if [ -z "$MOUNTED_NAME" ] || [ "$MOUNTED_NAME" = "$NAME" ]; then
        echo "ERROR: desmonta primero"
        exit 1
    fi
fi

BEFORE_KB="$(server_cache_kb "$NAME")"
[ -z "$BEFORE_KB" ] && BEFORE_KB=0

server_cache_dirs "$NAME" | while IFS= read -r d; do
    rm -rf "$d" 2>/dev/null
done

# Misma razón que arriba, solo para este servidor: su marca de "precarga
# completa" ya no es válida. El progreso mostrado solo se borra si era de
# este servidor.
rm -f "$MODDIR/config/preload_done_$NAME" 2>/dev/null
if grep -qF "\"remote\":\"$NAME\"" "$MODDIR/preload_status.json" 2>/dev/null; then
    rm -f "$MODDIR/preload_status.json" 2>/dev/null
fi

echo "OK $BEFORE_KB"
