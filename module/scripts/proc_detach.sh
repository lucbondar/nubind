# Desacopla del proceso de la app los procesos de larga duración del módulo
# (rclone mount, watcher, precarga). Se carga con:
#   . "$MODDIR/scripts/proc_detach.sh"
#   detach_from_app [oom_score_adj]
#
# Por qué: cuando el montaje se lanza desde la app (libsu), el shell root
# hereda el cgroup de la app (uid_X/pid_Y). Android congela ese cgroup
# entero cuando la app pasa a segundo plano (cached app freezer, Android 12+)
# y lo mata entero cuando cierra la app (killProcessGroup). Como rclone y la
# precarga son hijos de ese shell, quedaban congelados o muertos justo
# cuando otra app en primer plano estaba leyendo del montaje: lecturas que
# se cuelgan, precarga detenida y binds que "desaparecen".
#
# Se mueve el proceso actual a la raíz de las jerarquías de cgroups (v2 en
# /sys/fs/cgroup, v1 en /acct) y, si existe, al cpuset "foreground" para no
# quedar limitado a los núcleos pequeños. Todo lo que este shell lance
# después lo hereda. Si algo no existe o no se puede escribir, se ignora: en
# el peor caso queda como antes.
detach_from_app() {
    for _cg in /sys/fs/cgroup /acct; do
        [ -w "$_cg/cgroup.procs" ] && echo $$ > "$_cg/cgroup.procs" 2>/dev/null
    done
    for _cs in /dev/cpuset/foreground /dev/cpuset; do
        if [ -w "$_cs/cgroup.procs" ]; then
            echo $$ > "$_cs/cgroup.procs" 2>/dev/null && break
        fi
    done
    # Prioridad frente al low memory killer. rclone sirve los archivos que
    # está usando la app en primer plano: si lmkd lo mata para liberar RAM,
    # esa app pierde sus archivos a mitad de uso.
    if [ -n "$1" ]; then
        echo "$1" > /proc/$$/oom_score_adj 2>/dev/null
    fi
    unset _cg _cs
}
