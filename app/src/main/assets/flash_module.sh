#!/system/bin/sh
# Flashea el zip del módulo Nubind, ya desacoplado de la app.
# Lo lanza la app (AppUpdater.flashModuleViaRoot) con root:
#   sh flash_module.sh <zip> <paquete> <actividad> <archivo-de-resultado>
#
# customize.sh del módulo reinstala la app, y reemplazar el paquete mata el
# proceso de la app (y con él al shell root que heredó su cgroup). Por eso
# primero se mueve este proceso a la raíz de los cgroups (igual que
# self_update.sh) y, al terminar, se reabre la app. El resultado queda en
# <archivo-de-resultado>: "OK" si salió bien, o las últimas líneas del error.
ZIP="$1"; PKG="$2"; ACT="$3"; RES="$4"

for _cg in /sys/fs/cgroup /acct; do
    [ -w "$_cg/cgroup.procs" ] && echo $$ > "$_cg/cgroup.procs" 2>/dev/null
done

rm -f "$RES"
sleep 1

# KernelSU, KSU Next y SukiSU usan ksud; Magisk, su propio comando.
if [ -x /data/adb/ksud ]; then
    OUT=$(/data/adb/ksud module install "$ZIP" 2>&1); RC=$?
elif command -v ksud >/dev/null 2>&1; then
    OUT=$(ksud module install "$ZIP" 2>&1); RC=$?
elif command -v magisk >/dev/null 2>&1; then
    OUT=$(magisk --install-module "$ZIP" 2>&1); RC=$?
else
    OUT="No se encontró ksud ni magisk"; RC=1
fi

rm -f "$ZIP" "$0"
if [ "$RC" = 0 ]; then
    echo OK > "$RES"
else
    echo "$OUT" | tail -n 3 > "$RES"
fi
# Se reabre con el MISMO intent que usa el launcher (MAIN + LAUNCHER). Con "am start -n"
# a secas el intent raíz de la tarea no coincide con el del launcher, y al volver a la app
# desde el launcher Android apila una instancia nueva encima (arranca en Inicio y pierde
# la pestaña). Con el mismo intent solo trae la tarea al frente.
am start --user 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n "$PKG/$ACT" >/dev/null 2>&1
