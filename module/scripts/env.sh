# Entorno común de los scripts que ejecutan rclone (mount.sh, drive_auth.sh,
# check_remote.sh). Se carga con:  . "$MODDIR/scripts/env.sh"
# Requiere que $MODDIR ya esté definido por quien lo carga.

# Al correr como root vía su/servicio, $HOME suele venir vacío o en "/", y
# rclone intenta entonces crear su config/cache en "/.cache" — que cae en la
# partición de sistema, de solo lectura. Se fija HOME a un directorio propio
# y escribible del módulo.
export HOME="$MODDIR"

# Android no trae fusermount3 (rclone lo necesita para montar FUSE incluso
# corriendo como root). Se agrega $MODDIR/bin al PATH para que lo encuentre.
export PATH="$MODDIR/bin:$PATH"

# rclone es un binario Go estático "linux": no conoce el almacén de
# certificados de Android y sin esto toda conexión HTTPS (Google Drive) falla
# con "x509: certificate signed by unknown authority". Go acepta una lista de
# directorios separados por ":" en SSL_CERT_DIR. Se usa el almacén del APEX
# conscrypt (Android 14+, se actualiza por Play) junto al clásico del sistema.
CERT_DIRS=""
for d in /apex/com.android.conscrypt/cacerts /system/etc/security/cacerts; do
    [ -d "$d" ] && CERT_DIRS="${CERT_DIRS:+$CERT_DIRS:}$d"
done
[ -n "$CERT_DIRS" ] && export SSL_CERT_DIR="$CERT_DIRS"
unset CERT_DIRS

# Fallback DNS: rclone (Go estático) lee /etc/resolv.conf, que Android no trae
# (-> "lookup ... on [::1]:53: connection refused"). Si no hay nameservers,
# se superpone uno propio sobre /system/etc con overlayfs. No depende del
# overlay de módulos de KernelSU (que exige metamódulo en versiones recientes).
# Idempotente: tras montarse, el grep encuentra los nameservers y no repite.
if ! grep -qs '^nameserver' /system/etc/resolv.conf 2>/dev/null; then
    _E="$MODDIR/etc_overlay"
    mkdir -p "$_E/upper" "$_E/work"
    printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "$_E/upper/resolv.conf"
    chmod 644 "$_E/upper/resolv.conf"
    mount -t overlay overlay \
        -o "lowerdir=/system/etc,upperdir=$_E/upper,workdir=$_E/work" \
        /system/etc 2>/dev/null
    unset _E
fi
