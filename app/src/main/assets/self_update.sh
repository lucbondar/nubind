#!/system/bin/sh
# Instala el APK de una actualización de la app, ya desacoplado de ella.
# Lo lanza la app (AppUpdater.installViaRoot) con root:
#   sh self_update.sh <apk> <paquete> <actividad> <archivo-de-resultado>
#
# Reemplazar el paquete mata el proceso de la app, y con él al shell root que
# heredó su cgroup. Por eso primero se mueve este proceso a la raíz de los
# cgroups (igual que scripts/proc_detach.sh del módulo, pero embebido aquí:
# el módulo instalado puede ser más viejo que la app y no traer ese script).
# Si la instalación sale bien, se vuelve a abrir la app sola; si falla, el
# motivo queda en <archivo-de-resultado> y la app (que sigue viva) lo muestra.
APK="$1"; PKG="$2"; ACT="$3"; RES="$4"

for _cg in /sys/fs/cgroup /acct; do
    [ -w "$_cg/cgroup.procs" ] && echo $$ > "$_cg/cgroup.procs" 2>/dev/null
done

rm -f "$RES"
sleep 1

# Igual que scripts/install_app.sh: por ruta y, si system_server no puede leer
# el archivo (SELinux), por stdin. -r conserva los datos, -d permite bajar de versionCode.
OUT=$(cmd package install -r -d "$APK" 2>&1)
case "$OUT" in *Success*) ;; *)
    OUT=$(pm install -r -d "$APK" 2>&1) ;;
esac
case "$OUT" in *Success*) ;; *)
    SIZE=$(stat -c %s "$APK" 2>/dev/null || wc -c < "$APK")
    OUT=$(cmd package install -r -d -S "$SIZE" < "$APK" 2>&1) ;;
esac

case "$OUT" in
    *Success*)
        rm -f "$APK" "$0"
        am start --user 0 -n "$PKG/$ACT" >/dev/null 2>&1
        ;;
    *)
        echo "$OUT" | tail -n 3 > "$RES"
        rm -f "$APK" "$0"
        ;;
esac
