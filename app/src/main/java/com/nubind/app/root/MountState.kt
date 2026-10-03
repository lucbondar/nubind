package com.nubind.app.root

/** Estado del montaje según status.json (lo escriben mount.sh y unmount.sh). */
data class MountState(val mounted: Boolean, val remote: String?)

/** Lectura bloqueante (usa root): llamar fuera del hilo principal. */
fun RootShell.readMountState(): MountState {
    val json = status().output
    val mounted = json.contains("\"mounted\":true")
    val remote = if (mounted) {
        Regex("\"remote\":\"([^\"]*)\"").find(json)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    } else null
    return MountState(mounted, remote)
}
