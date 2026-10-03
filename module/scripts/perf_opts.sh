# Opciones de montaje de rclone según el perfil de rendimiento y el tipo de
# remoto. Lo cargan mount.sh (para montar) y perf_test.sh (para comprobar que
# el montaje activo usa lo que dice la configuración actual): así el cálculo
# vive en un solo lugar y no pueden desincronizarse.
#
# Requiere MODDIR, RCLONE_CONF y ACTIVE ya definidos. Uso:
#   . "$MODDIR/scripts/perf_opts.sh"
#   compute_mount_opts      # deja el resultado en $MOUNT_OPTS (y $PERF)

# Valor de una clave de la sección de un remoto en rclone.conf (o nada si no
# está). El nombre de sección se compara literalmente, no como regexp. Uso: remote_key <remoto> <clave>
remote_key() {
    # Soporta espacios, tabs, CRLF y una última línea sin salto de línea.
    awk -v section="$1" -v key="$2" '
        { sub(/\r$/, ""); sub(/^[ \t]+/, ""); sub(/[ \t]+$/, "") }
        /^\[/ { active = ($0 == "[" section "]"); next }
        active {
            eq = index($0, "=")
            if (!eq) next
            k = substr($0, 1, eq - 1); sub(/[ \t]+$/, "", k)
            if (k == key) {
                value = substr($0, eq + 1); sub(/^[ \t]+/, "", value)
                print value; exit
            }
        }
    ' "$RCLONE_CONF" 2>/dev/null
}

# Tipo del remoto (ftp, drive, s3...): la clave "type" de su sección.
remote_type() {
    v="$(remote_key "$1" type)"
    echo "${v%% *}"
}

# Carpeta dentro del remoto que se monta (clave "bind_path" que escribe la
# app; en S3 es el bucket, con subcarpeta opcional). Vacía = la raíz del
# remoto. rclone ignora las claves que no conoce, así que no le afecta.
remote_root() {
    remote_key "$1" bind_path
}

# Versión del binario incluido ("rclone v1.75.1" -> 1 75). Vacío si no se puede leer.
rclone_version_parts() {
    [ -x "$MODDIR/bin/rclone" ] || return 1
    _v="$("$MODDIR/bin/rclone" version 2>/dev/null | head -n 1)"
    _v="${_v#*v}"
    _maj="${_v%%.*}"
    _rest="${_v#*.}"
    _min="${_rest%%.*}"
    case "$_maj$_min" in ''|*[!0-9]*) return 1 ;; esac
    [ -n "$_maj" ] && [ -n "$_min" ] || return 1
    echo "$_maj $_min"
}

rclone_version_known() {
    [ -n "$(rclone_version_parts)" ]
}

# rclone_at_least <mayor> <menor>: el binario es esa versión o una más nueva.
rclone_at_least() {
    set -- "$1" "$2" $(rclone_version_parts)
    [ -n "$3" ] || return 1
    [ "$3" -gt "$1" ] || { [ "$3" -eq "$1" ] && [ "$4" -ge "$2" ]; }
}

# ---- Rendimiento dedicado de S3 (Oracle Cloud y compatibles) ----
# Ajustes opcionales que escribe la app en config/ (vacío o ausente = el valor
# automático de cada proveedor y perfil; los mismos valores están en
# S3Perf, Conf.kt, y deben coincidir):
#   s3_streams        lectura paralela por archivo (1-12)       [Máximo]
#   s3_upload_conc    partes de subida a la vez (1-16)          [Máximo]
#   s3_chunk_mb       tamaño de parte de subida: 8, 16, 32 o 64 [Máximo]
#   s3_fewer_req      1 = menos peticiones, 0 = no
#   s3_dir_cache_min  minutos que se cachea cada listado (1-1440)

# Oracle Cloud si el endpoint es de *.oraclecloud.com, Cloudflare R2 si es de
# *.r2.cloudflarestorage.com; si no, "other".
s3_provider() {
    _ep="$(remote_key "$ACTIVE" endpoint | tr '[:upper:]' '[:lower:]')"
    _ep="${_ep#*://}"
    _ep="${_ep%%/*}"
    _ep="${_ep%%:*}"
    case "$_ep" in
        *.oraclecloud.com) echo oracle ;;
        *.r2.cloudflarestorage.com) echo cloudflare ;;
        *) echo other ;;
    esac
}

# num_in_range <valor> <mín> <máx> <defecto>: el valor si es entero y está en
# el rango; si no, el defecto.
num_in_range() {
    # Normaliza ceros iniciales y evita desbordar la aritmética del shell.
    _num="$(printf '%s' "$1" | sed 's/^0*//')"
    case "$_num" in ''|*[!0-9]*) echo "$4"; return ;; esac
    if [ "${#_num}" -gt "${#3}" ]; then echo "$4"; return; fi
    if [ "$_num" -lt "$2" ] || [ "$_num" -gt "$3" ]; then echo "$4"; else echo "$_num"; fi
}

# Consulta las capacidades una vez por cálculo. Una consulta fallida NO
# autoriza flags opcionales: así un binario viejo no rompe el montaje.
S3_FLAGS_HELP=""
MOUNT_FLAGS_HELP=""
s3_flags_load() {
    S3_FLAGS_HELP=""
    [ -x "$MODDIR/bin/rclone" ] || return 0
    # Las opciones de los backends (--s3-*) solo salen en "help flags" con
    # --all en las versiones recientes; las viejas no conocen --all. Se prueba
    # primero con --all y, si no devuelve nada, sin él.
    S3_FLAGS_HELP="$("$MODDIR/bin/rclone" help flags --all 's3-|use-server-modtime' 2>/dev/null)" || S3_FLAGS_HELP=""
    if [ -z "$S3_FLAGS_HELP" ]; then
        S3_FLAGS_HELP="$("$MODDIR/bin/rclone" help flags 's3-|use-server-modtime' 2>/dev/null)" || S3_FLAGS_HELP=""
    fi
}
s3_has_flag() {
    printf '%s\n' "$S3_FLAGS_HELP" | grep -qE -- "--$1([[:space:]=]|$)"
}

# Deja en $MOUNT_OPTS las opciones de un remoto S3.
#  - Equilibrado: caché completa de 1G (como Drive) y listados/peticiones.
#  - Máximo: caché grande, lectura anticipada, varios trozos del mismo archivo
#    en paralelo y subida multiparte en paralelo.
#  - Menos peticiones (por defecto en Oracle, que factura y limita por número
#    de peticiones según el plan, y en Cloudflare R2, que cobra por operación
#    pasado su cupo gratis): --use-server-modtime evita un HEAD por
#    archivo para leer su fecha y el SetModTime (copia en el servidor) tras
#    cada subida, a costa de que la fecha de modificación sea la de subida;
#    --s3-no-head y --s3-no-head-object quitan los HEAD antes/después de
#    subir y bajar.
#  - S3 no avisa de cambios hechos fuera del montaje: el listado solo se
#    refresca al caducar --dir-cache-time.
s3_mount_opts() {
    _prov="$(s3_provider)"
    _cfg="$MODDIR/config"

    _fewer="$(cat "$_cfg/s3_fewer_req" 2>/dev/null)"
    case "$_fewer" in
        0|1) ;;
        *) case "$_prov" in oracle|cloudflare) _fewer=1 ;; *) _fewer=0 ;; esac ;;
    esac

    case "$_prov" in
        oracle|cloudflare) _dcd=30 ;;
        *) if [ "$PERF" = max ]; then _dcd=10; else _dcd=5; fi ;;
    esac
    _dc="$(num_in_range "$(cat "$_cfg/s3_dir_cache_min" 2>/dev/null)" 1 1440 "$_dcd")"

    s3_flags_load
    _o="--vfs-cache-mode full --vfs-cache-max-age 720h --dir-cache-time ${_dc}m"

    if [ "$PERF" = max ]; then
        _st="$(num_in_range "$(cat "$_cfg/s3_streams" 2>/dev/null)" 1 12 4)"
        # R2 falla con la firma en archivos grandes si suben muchas partes a la
        # vez (visto en rclone con 4 o más): arranca en 3. Los demás, en 4.
        if [ "$_prov" = cloudflare ]; then _ccd=3; else _ccd=4; fi
        _cc="$(num_in_range "$(cat "$_cfg/s3_upload_conc" 2>/dev/null)" 1 16 "$_ccd")"
        _ck="$(cat "$_cfg/s3_chunk_mb" 2>/dev/null)"
        case "$_ck" in 8|16|32|64) ;; *) _ck=16 ;; esac
        _tr=4
        # Estimación de buffers multiparte (no es la RAM total) = transfers x partes simultáneas x
        # tamaño de parte. Se acota a 256 MB bajando las partes simultáneas.
        while [ "$_cc" -gt 1 ] && [ $(( _tr * _cc * _ck )) -gt 256 ]; do
            _cc=$(( _cc - 1 ))
        done
        _o="$_o --vfs-cache-max-size ${CACHE_GB:-10}G --vfs-cache-min-free-space 2G --vfs-read-ahead 64M --vfs-read-chunk-size 16M --vfs-read-chunk-streams $_st --buffer-size 16M --transfers $_tr --checkers 8 --vfs-write-back 15s --attr-timeout 1m"
        s3_has_flag s3-upload-concurrency && _o="$_o --s3-upload-concurrency $_cc"
        s3_has_flag s3-chunk-size && _o="$_o --s3-chunk-size ${_ck}M"
        # Con menos peticiones los archivos medianos suben de una vez (un
        # solo PUT, el umbral por defecto de 200M); si no, desde una parte
        # en adelante se suben en paralelo.
        if [ "$_fewer" = 0 ]; then
            s3_has_flag s3-upload-cutoff && _o="$_o --s3-upload-cutoff ${_ck}M"
        fi
    else
        _o="$_o --vfs-cache-max-size ${CACHE_GB:-1}G --vfs-cache-min-free-space 2G --buffer-size 8M"
    fi

    if [ "$_fewer" = 1 ]; then
        s3_has_flag use-server-modtime && _o="$_o --use-server-modtime"
        s3_has_flag s3-no-head && _o="$_o --s3-no-head"
        s3_has_flag s3-no-head-object && _o="$_o --s3-no-head-object"
    fi

    MOUNT_OPTS="$_o"
}

# En Equilibrado el tope de caché es 1G (FTP, S3, Drive). Si ese servidor ya
# tiene una caché más grande (la armó en Máximo), rclone la recorta al tope
# apenas monta: se pierde lo cacheado y hay que volver a precargar. Para que
# cambiar de perfil o de servidor no borre nada, el tope de Equilibrado no
# baja de lo que el servidor ya tiene en disco (redondeado hacia arriba a
# múltiplos de 5 GB, así no cambia con cada archivo nuevo) ni del tamaño que
# el usuario le había puesto en Máximo. Si la caché cabe en el tope normal,
# no cambia nada. Para reducirla de verdad: bajar el tamaño en Máximo y/o
# usar "Borrar caché".
keep_cache_size() {
    _cur="$(printf '%s' "$MOUNT_OPTS" | sed -n 's/.*--vfs-cache-max-size \([0-9][0-9]*\)G.*/\1/p')"
    case "$_cur" in ''|*[!0-9]*) return 0 ;; esac
    _used_kb="$(du -sk "$MODDIR/cache/vfs/$ACTIVE" 2>/dev/null | awk '{print $1+0}')"
    case "$_used_kb" in ''|*[!0-9]*) _used_kb=0 ;; esac
    _keep=0
    if [ "$_used_kb" -gt $(( _cur * 1048576 )) ]; then
        _keep=$(( (_used_kb + 5242879) / 5242880 * 5 ))
    fi
    _saved="${CACHE_GB_SAVED:-0}"
    [ "$_saved" -gt "$_keep" ] && _keep="$_saved"
    [ "$_keep" -gt "$_cur" ] || return 0
    MOUNT_OPTS="$(printf '%s' "$MOUNT_OPTS" | sed "s/--vfs-cache-max-size ${_cur}G/--vfs-cache-max-size ${_keep}G/")"
}

compute_mount_opts() {
    # Rendimiento elegido en la app (config/perf: "balanced" o "max") y tamaño
    # de caché en GB (config/cache_gb, opcional; vacío = el de cada perfil).
    PERF="$(cat "$MODDIR/config/perf" 2>/dev/null)"
    case "$PERF" in max) ;; *) PERF=balanced ;; esac
    CACHE_GB="$(num_in_range "$(cat "$MODDIR/config/cache_gb" 2>/dev/null)" 1 100 '')"
    # El tamaño elegido se recuerda aunque el perfil sea Equilibrado: ahí no
    # manda sobre el tope del perfil, pero keep_cache_size lo usa como piso
    # para no encoger la caché grande del servidor.
    CACHE_GB_SAVED="$CACHE_GB"
    # El control de tamaño solo está disponible en Máximo.
    [ "$PERF" = max ] || CACHE_GB=""
    MOUNT_FLAGS_HELP=""
    if [ -x "$MODDIR/bin/rclone" ]; then
        MOUNT_FLAGS_HELP="$("$MODDIR/bin/rclone" mount --help 2>/dev/null)" || MOUNT_FLAGS_HELP=""
    fi

    # Limitar subidas y buffers importa más que multiplicar conexiones en
    # Android. No usar fast-fingerprint: puede ocultar cambios externos.
    MAX_EXTRA_OPTS="--transfers 4 --checkers 8 --vfs-write-back 15s --attr-timeout 1m"

    # El cliente compartido de Drive no debe recibir ráfagas agresivas.
    DRIVE_PACER_OPTS=""
    if [ -n "$(remote_key "$ACTIVE" client_id)" ]; then
        DRIVE_PACER_OPTS="--drive-pacer-min-sleep 20ms --drive-pacer-burst 100"
    fi

    # Antigüedad máxima de la caché: 720 h (30 días) en todos los perfiles con
    # caché completa. Con valores cortos (1 h en Equilibrado/Drive) rclone
    # purga lo precargado si no se abre a tiempo, y el marcador de
    # preload.sh diría "ya precargado" con la caché vacía. El espacio lo sigue
    # acotando --vfs-cache-max-size.

    # Opciones de montaje según el perfil y el tipo de remoto. Se dejan sin
    # comillas al invocar rclone para que se separen en palabras.
    #  - balanced: lo de siempre (Drive con caché completa de 1G; FTP solo escrituras).
    #  - max: caché completa en ambos, lectura anticipada y listados cacheados más
    #    tiempo. --vfs-cache-min-free-space evita llenar el almacenamiento con la
    #    caché. FTP no avisa de cambios, por eso su dir-cache-time es corto.
    case "$PERF:$(remote_type "$ACTIVE")" in
        max:drive)
            # Lectura paralela opcional, con buffers moderados por archivo.
            MOUNT_OPTS="--vfs-cache-mode full --vfs-cache-max-size ${CACHE_GB:-10}G --vfs-cache-max-age 720h --vfs-cache-min-free-space 2G --vfs-read-ahead 64M --vfs-read-chunk-size 16M --vfs-read-chunk-streams 4 --buffer-size 16M --dir-cache-time 12h --drive-chunk-size 16M $DRIVE_PACER_OPTS $MAX_EXTRA_OPTS"
            ;;
        *:s3)
            # Opciones dedicadas de S3 / Oracle: s3_mount_opts, más arriba.
            s3_mount_opts
            ;;
        max:*)
            MOUNT_OPTS="--vfs-cache-mode full --vfs-cache-max-size ${CACHE_GB:-10}G --vfs-cache-max-age 720h --vfs-cache-min-free-space 2G --vfs-read-ahead 64M --buffer-size 16M --dir-cache-time 10m $MAX_EXTRA_OPTS"
            ;;
        *:drive)
            # Drive no admite escritura parcial ni lecturas con salto sobre la
            # nube: la caché completa en disco (acotada) hace que los archivos
            # se comporten como locales para cualquier app.
            MOUNT_OPTS="--vfs-cache-mode full --vfs-cache-max-size ${CACHE_GB:-1}G --vfs-cache-max-age 720h --vfs-cache-min-free-space 2G --buffer-size 8M $DRIVE_PACER_OPTS"
            ;;
        *)
            MOUNT_OPTS="--vfs-cache-mode writes --vfs-cache-max-size 1G --vfs-cache-max-age 720h --vfs-cache-min-free-space 2G --buffer-size 8M"
            ;;
    esac

    # Carpetas en S3: S3 no tiene carpetas, solo objetos con "/" en el nombre.
    # Una carpeta vacía creada desde el explorador solo existe en la memoria de
    # rclone y desaparece al desmontar, salvo que se suba un objeto marcador
    # "carpeta/" (--s3-directory-markers, rclone 1.64+). Antes la opción solo
    # se añadía si "rclone help flags" la listaba, y esa lista no incluye las
    # opciones de los backends sin --all: la opción no se aplicaba nunca. Ahora
    # también se aplica si la versión del binario es 1.64 o más nueva.
    if [ "$(remote_type "$ACTIVE")" = s3 ]; then
        if s3_has_flag s3-directory-markers || rclone_at_least 1 64; then
            MOUNT_OPTS="$MOUNT_OPTS --s3-directory-markers"
        fi
    fi

    [ "$PERF" = max ] || keep_cache_size

    case "$MOUNT_OPTS" in
        *--vfs-read-chunk-streams*)
            if ! printf '%s\n' "$MOUNT_FLAGS_HELP" | grep -q -- '--vfs-read-chunk-streams'; then
                MOUNT_OPTS="$(printf '%s' "$MOUNT_OPTS" | sed 's/ --vfs-read-chunk-streams [0-9]*//')"
            fi
            ;;
    esac
}
