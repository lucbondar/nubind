#!/system/bin/sh
# Borra la caché en disco de rclone (VFS cache: bloques y metadatos de los
# archivos leídos/escritos). rclone la vuelve a llenar sola al acceder de
# nuevo, así que es segura de borrar — pero SOLO con el bind desmontado: si
# el perfil usa vfs-cache-mode "writes" o "full" y hay escrituras aún sin
# subir al remoto, borrarla ahora las perdería. Por eso se corta si el
# status dice que sigue montado, en vez de arriesgar datos del usuario.
SELF="$(readlink -f "$0")"
MODDIR=$(dirname "$(dirname "$SELF")")
CACHE_DIR="$MODDIR/cache"
STATUS_FILE="$MODDIR/status.json"

if grep -q '"mounted":true' "$STATUS_FILE" 2>/dev/null; then
    echo "ERROR: desmonta primero"
    exit 1
fi

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
