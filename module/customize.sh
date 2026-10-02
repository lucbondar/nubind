SKIPUNZIP=0

ui_print "- Instalando Nubind"

mkdir -p "$MODPATH/config"
mkdir -p "$MODPATH/scripts"

chmod 755 "$MODPATH/bin/rclone"
chmod 755 "$MODPATH/bin/fusermount3" 2>/dev/null
chmod 755 "$MODPATH/scripts/"*.sh
chmod 755 "$MODPATH/service.sh"
chmod 755 "$MODPATH/post-fs-data.sh"

# Al actualizar, KernelSU arma el módulo nuevo en otra carpeta: sin esto se
# pierden los servidores, el servidor activo y el ajuste de autostart.
OLD_CONFIG="/data/adb/modules/nubind/config"
# Si venías de la versión anterior (id "rclone_ftp_bind"), se importan sus servidores.
[ -d "$OLD_CONFIG" ] || OLD_CONFIG="/data/adb/modules/rclone_ftp_bind/config"
if [ -d "$OLD_CONFIG" ]; then
    cp -a "$OLD_CONFIG/." "$MODPATH/config/"
    ui_print "- Configuración anterior conservada"
fi

# rclone (binario Go estático) resuelve DNS leyendo /etc/resolv.conf, que
# Android no trae: sin él, Google Drive falla con "lookup ... on [::1]:53".
# El módulo lo agrega de forma sistémica (system/etc/resolv.conf) y además
# env.sh monta un overlay de respaldo en runtime. Si el dispositivo ya tiene
# uno con nameservers, no se pisa.
if grep -qs '^nameserver' /system/etc/resolv.conf; then
    rm -f "$MODPATH/system/etc/resolv.conf"
else
    ui_print "- DNS para rclone (Google Drive): requiere reiniciar"
fi

# Config por defecto: sin autostart hasta que el usuario lo active desde la app
[ -f "$MODPATH/config/autostart" ] || echo "0" > "$MODPATH/config/autostart"

# --- Instalación de la app en tiempo de flasheo ---
# Si falla en silencio (p. ej. el binder a system_server no está listo o
# SELinux lo bloquea), se deja una marca para que service.sh reintente ya con
# el sistema arrancado y, como último recurso, abra el instalador del sistema.
APK_SRC="$MODPATH/app.apk"
APK_TMP="/data/local/tmp/nubind.apk"
INSTALL_LOG="$MODPATH/install.log"

if [ -f "$APK_SRC" ]; then
    . "$MODPATH/scripts/install_app.sh"
    cp "$APK_SRC" "$APK_TMP"
    chmod 644 "$APK_TMP"

    if install_app "$APK_TMP" "$INSTALL_LOG"; then
        ui_print "- App instalada correctamente"
    else
        ui_print "- No se pudo instalar la app al flashear:"
        ui_print "  $(echo "$LAST_ERR" | tail -n 1)"
        ui_print "- Se reintentará al reiniciar (log: $INSTALL_LOG)"
        touch "$MODPATH/.needs_manual_install"
    fi
    rm -f "$APK_TMP"
else
    ui_print "- AVISO: el zip no trae app.apk"
fi

ui_print "- Configura el servidor (FTP, Google Drive o S3) desde la app antes de montar"
