#!/system/bin/sh
# Instala/actualiza la app. Se usa con "." (source) desde customize.sh y con
# "sh" desde service.sh, así la lógica vive en un solo lugar.
#
# Uso: install_app <apk> <log>
# Retorna 0 si quedó instalada, 1 si falló.

APP_PKG="com.nubind.app"

# Intenta instalar de dos formas: por ruta y por stdin. La segunda no exige
# que system_server pueda leer el archivo (útil si SELinux lo impide).
# -r reinstala conservando datos, -d permite bajar de versionCode.
_try_install() {
    _apk="$1"; _log="$2"
    _size=$(stat -c %s "$_apk" 2>/dev/null || wc -c < "$_apk")
    for _c in "cmd package" "pm"; do
        _out=$($_c install -r -d "$_apk" 2>&1)
        echo "[$_c por ruta] $_out" >> "$_log"
        case "$_out" in *Success*) return 0 ;; esac
        case "$_out" in *UPDATE_INCOMPATIBLE*) LAST_ERR="$_out"; return 2 ;; esac
        LAST_ERR="$_out"

        _out=$($_c install -r -d -S "$_size" < "$_apk" 2>&1)
        echo "[$_c por stdin] $_out" >> "$_log"
        case "$_out" in *Success*) return 0 ;; esac
        case "$_out" in *UPDATE_INCOMPATIBLE*) LAST_ERR="$_out"; return 2 ;; esac
        LAST_ERR="$_out"
    done
    return 1
}

install_app() {
    _apk="$1"; _log="$2"
    LAST_ERR=""
    _try_install "$_apk" "$_log"
    _rc=$?

    # Único caso en que se desinstala: la firma del APK instalado no
    # coincide con la nueva (builds viejos firmados con claves al azar).
    # Se pierden los datos de la app, pero no hay otra forma de reemplazarla.
    if [ "$_rc" = "2" ]; then
        echo "Firma distinta: desinstalando la versión anterior" >> "$_log"
        cmd package uninstall "$APP_PKG" >> "$_log" 2>&1 || pm uninstall "$APP_PKG" >> "$_log" 2>&1
        _try_install "$_apk" "$_log"
        _rc=$?
    fi

    [ "$_rc" = "0" ] && return 0
    return 1
}
