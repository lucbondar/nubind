#!/system/bin/sh
# Login OAuth de Google Drive dentro del propio dispositivo.
#
# Uso: drive_auth.sh [client_id client_secret]
#
# Ejecuta `rclone authorize drive`: rclone abre un servidor temporal en
# 127.0.0.1:53682, imprime la URL de autorización y, cuando el usuario acepta
# en el navegador del teléfono (Google redirige a ese mismo puerto), imprime
# el token. La app lee la salida (auth.out) por polling; la última línea
# "__EXIT:<código>" indica que rclone terminó. auth.out contiene el token:
# la app lo borra en cuanto lo lee y service.sh lo limpia al arrancar.
SELF="$(readlink -f "$0")"
MODDIR=$(dirname "$(dirname "$SELF")")

# Mismo namespace global que mount.sh, para ver los mismos archivos de sistema.
if [ "$(readlink /proc/self/ns/mnt 2>/dev/null)" != "$(readlink /proc/1/ns/mnt 2>/dev/null)" ]; then
    exec nsenter -t 1 -m -- sh "$SELF" "$@"
fi

. "$MODDIR/scripts/env.sh"

OUT="$MODDIR/auth.out"
rm -f "$OUT"
umask 077
: > "$OUT"

# Tope de 5 minutos por si el usuario abandona el navegador.
LIMIT=""
command -v timeout >/dev/null 2>&1 && LIMIT="timeout 300"

# $LIMIT sin comillas a propósito (es un prefijo opcional).
$LIMIT "$MODDIR/bin/rclone" authorize drive "$@" --auth-no-open-browser >> "$OUT" 2>&1
echo "__EXIT:$?" >> "$OUT"
