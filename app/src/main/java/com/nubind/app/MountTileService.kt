package com.nubind.app

import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.nubind.app.root.RootShell
import com.nubind.app.root.readMountState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Quick toggle para montar/desmontar el servidor seleccionado.
 *
 * Tile expandido (Android 14+: ícono + texto): título "Nubind" y, debajo, el
 * servidor conectado (o "Desmontado"). Tile compacto: solo ícono, encendido
 * cuando hay algo montado.
 */
class MountTileService : TileService() {

    override fun onStartListening() {
        instance = this
        render()
        MountTileOps.refresh(applicationContext)
    }

    override fun onStopListening() {
        if (instance === this) instance = null
    }

    override fun onClick() {
        MountTileOps.toggle(applicationContext)
    }

    fun render() {
        val tile = qsTile ?: return
        val ui = MountTileOps.ui
        val busy = ui.busyLabel != null
        val subtitle: String? = when {
            busy -> ui.busyLabel
            ui.mounted -> ui.remote ?: getString(R.string.montado)
            else -> getString(R.string.desmontado)
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.state = when {
            busy -> Tile.STATE_UNAVAILABLE
            ui.mounted -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= 29) {
            tile.label = getString(R.string.tile_label)
            tile.subtitle = subtitle
        } else {
            // Sin subtítulo (Android 9 e inferior): el servidor va en la etiqueta.
            tile.label = if (ui.mounted && !busy) ui.remote ?: getString(R.string.tile_label) else getString(R.string.tile_label)
        }
        if (Build.VERSION.SDK_INT >= 30) tile.stateDescription = subtitle
        tile.updateTile()
    }

    companion object {
        @Volatile
        internal var instance: MountTileService? = null
    }
}

internal data class TileUi(val mounted: Boolean, val remote: String?, val busyLabel: String?)

/**
 * La lógica vive fuera del servicio y en un scope de proceso: el panel de
 * ajustes rápidos puede cerrarse (y el TileService destruirse) mientras
 * mount.sh sigue trabajando, y el montaje no debe cancelarse a medias.
 */
internal object MountTileOps {
    @Volatile
    var ui = TileUi(mounted = false, remote = null, busyLabel = null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    private fun publish(next: TileUi) {
        ui = next
        main.post { MountTileService.instance?.render() }
    }

    /** Relee el estado real (status.json); lo llama el tile al hacerse visible. */
    fun refresh(ctx: Context) {
        if (ui.busyLabel != null) return
        scope.launch {
            val s = RootShell.readMountState()
            if (ui.busyLabel == null) publish(TileUi(s.mounted, s.remote, null))
        }
    }

    fun toggle(ctx: Context) {
        if (ui.busyLabel != null) return
        val app = ctx.applicationContext
        scope.launch {
            val before = RootShell.readMountState()
            if (!before.mounted && RootShell.readActive() == null) {
                publish(TileUi(false, null, null))
                toast(app, Strings.get(R.string.agrega_un_servidor_primero))
                return@launch
            }
            publish(
                TileUi(
                    before.mounted, before.remote,
                    Strings.get(if (before.mounted) R.string.tile_unmounting else R.string.tile_mounting)
                )
            )
            val result = if (before.mounted) RootShell.unmount() else RootShell.mount()
            val after = RootShell.readMountState()
            publish(TileUi(after.mounted, after.remote, null))
            if (!result.success) toast(app, Strings.get(R.string.error, result.output.takeLast(200)))
            // mount.sh lanza la precarga sola al montar: se enseña su progreso en la notificación.
            if (result.success && after.mounted && !before.mounted) PreloadService.start(app)
        }
    }

    private fun toast(ctx: Context, text: String) =
        main.post { Toast.makeText(ctx, text, Toast.LENGTH_LONG).show() }
}
