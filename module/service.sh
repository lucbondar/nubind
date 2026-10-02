#!/system/bin/sh
MODDIR=${0%/*}

while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 1
done

# Salida temporal del login de Google Drive: contiene un token y no debe
# quedar en disco si la app se cerró a medias.
rm -f "$MODDIR/auth.out"

# Si customize.sh no pudo instalar al flashear (dejó .needs_manual_install),
# se reintenta ahora que el sistema ya arrancó; si sigue fallando, se abre el
# instalador del sistema y basta un toque en "Instalar".
NEEDS_MANUAL="$MODDIR/.needs_manual_install"
APK_SRC="$MODDIR/app.apk"
APK_TMP="/data/local/tmp/nubind.apk"
INSTALL_LOG="$MODDIR/install.log"

if [ -f "$NEEDS_MANUAL" ] && [ -f "$APK_SRC" ]; then
    . "$MODDIR/scripts/install_app.sh"
    cp "$APK_SRC" "$APK_TMP"
    chmod 644 "$APK_TMP"
    if ! install_app "$APK_TMP" "$INSTALL_LOG"; then
        am start -a android.intent.action.VIEW \
            -d "file://$APK_TMP" \
            -t application/vnd.android.package-archive \
            -f 0x10000000 >> "$INSTALL_LOG" 2>&1
    fi
    rm -f "$NEEDS_MANUAL"
fi

AUTOSTART_FLAG="$MODDIR/config/autostart"

if [ -f "$AUTOSTART_FLAG" ] && [ "$(cat "$AUTOSTART_FLAG")" = "1" ]; then
    # En segundo plano y con reintentos: al arrancar, boot_completed llega
    # antes de que el almacenamiento esté desbloqueado (/sdcard todavía no
    # existe, y un bind hecho ahí queda tapado cuando se monta de verdad) y
    # antes de que haya red, así que el primer intento suele fallar.
    (
        i=0
        until [ -d /sdcard/Android ] || [ "$i" -ge 150 ]; do
            sleep 2
            i=$((i + 1))
        done

        n=0
        while [ "$n" -lt 30 ]; do
            sh "$MODDIR/scripts/mount.sh" && break
            n=$((n + 1))
            sleep 10
        done
    ) &
fi
