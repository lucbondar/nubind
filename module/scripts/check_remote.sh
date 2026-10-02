#!/system/bin/sh
# Comprueba que un remoto responde (sesión, red, certificados): lista su raíz.
# En S3 con bucket configurado lista ese bucket (y así también comprueba que
# existe y que la clave tiene acceso); sin bucket, lista los buckets.
# Uso: check_remote.sh <nombre-del-remoto>
# Sale con 0 si funciona; si no, imprime el error de rclone.
SELF="$(readlink -f "$0")"
MODDIR=$(dirname "$(dirname "$SELF")")
. "$MODDIR/scripts/env.sh"

[ -z "$1" ] && { echo "Falta el nombre del remoto"; exit 2; }

RCLONE_CONF="$MODDIR/config/rclone.conf"
. "$MODDIR/scripts/perf_opts.sh"
ROOT="$(remote_root "$1")"

LIMIT=""
command -v timeout >/dev/null 2>&1 && LIMIT="timeout 60"

$LIMIT "$MODDIR/bin/rclone" lsd "$1:$ROOT" --max-depth 1 \
    --config "$RCLONE_CONF" \
    --contimeout 15s --timeout 30s --retries 1 --low-level-retries 1 \
    --log-level ERROR >/dev/null
