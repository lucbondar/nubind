package com.nubind.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.graphics.PathParser
import com.nubind.app.root.PerfMode
import com.nubind.app.root.RootShell
import com.nubind.app.root.readMountState
import com.nubind.app.ui.components.NoticeKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

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
        cancelAnimation()
        if (instance === this) instance = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var animation: Runnable? = null

    private fun cancelAnimation() {
        animation?.let { handler.removeCallbacks(it) }
        animation = null
    }

    /**
     * Mini animación del icono: la nube se llena de abajo arriba como agua (con una ola en la
     * superficie) al montar, y se vacía al desmontar. Un tile solo admite iconos estáticos, así
     * que se van cambiando por cuadros (updateTile cada [FRAME_MS] ms). Se anima solo cuando el
     * cambio lo provocó un toque en el tile, no al abrir el panel con algo ya montado.
     */
    private fun playFill(fill: Boolean) {
        cancelAnimation()
        var i = 0
        val runner = object : Runnable {
            override fun run() {
                val t = i / FRAMES.toFloat()
                val eased = 1f - (1f - t) * (1f - t)
                val level = if (fill) eased else 1f - eased
                qsTile?.let {
                    it.icon = CloudFrames.icon(level, i * 0.9f)
                    it.updateTile()
                }
                i++
                if (i <= FRAMES) handler.postDelayed(this, FRAME_MS) else animation = null
            }
        }
        animation = runner
        runner.run()
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
        val anim = if (busy) MountTileOps.ANIM_NONE else MountTileOps.takeAnim()
        // Montado = nube rellena; desmontado u ocupado = solo el contorno. Mientras corre la
        // animación el icono lo manejan sus cuadros.
        // Al llenar se parte del contorno y al vaciar de la nube llena (si no, se vería un
        // parpadeo con el estado final antes del primer cuadro).
        val iconFilled = when (anim) {
            MountTileOps.ANIM_FILL -> false
            MountTileOps.ANIM_DRAIN -> true
            else -> ui.mounted && !busy
        }
        if (animation == null || anim != MountTileOps.ANIM_NONE) {
            tile.icon = if (iconFilled) CloudFrames.icon(1f, 0f)
            else Icon.createWithResource(this, R.drawable.ic_tile)
        }
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
        when (anim) {
            MountTileOps.ANIM_FILL -> playFill(true)
            MountTileOps.ANIM_DRAIN -> playFill(false)
        }
    }

    companion object {
        @Volatile
        internal var instance: MountTileService? = null
        private const val FRAMES = 12
        private const val FRAME_MS = 45L
    }
}

/**
 * Cuadros del icono: la nube de Material (contorno + silueta) dibujada en un bitmap blanco con
 * alfa (el sistema lo tiñe, igual que el vector de siempre). El relleno es la silueta recortada
 * por una región cuyo borde superior es una onda que sube con [level] (0 = vacía, 1 = llena).
 */
private object CloudFrames {
    private const val SIZE_PX = 96
    private const val OUTLINE_DATA =
        "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13" +
            "c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96zM19,18H6c-2.21,0 -4,-1.79 -4,-4 0,-2.05 1.53,-3.76 3.56,-3.97" +
            "l1.07,-0.11 0.5,-0.95C8.08,7.14 9.94,6 12,6c2.62,0 4.88,1.86 5.39,4.43l0.3,1.5 1.53,0.11c1.56,0.1 2.78,1.41 2.78,2.96" +
            " 0,1.65 -1.35,3 -3,3z"
    private const val SOLID_DATA =
        "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13" +
            "c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96z"

    private val outline: Path by lazy { PathParser.createPathFromPathData(OUTLINE_DATA) }
    private val solid: Path by lazy { PathParser.createPathFromPathData(SOLID_DATA) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }

    fun icon(level: Float, phase: Float): Icon = Icon.createWithBitmap(frame(level, phase))

    private fun frame(level: Float, phase: Float): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.scale(SIZE_PX / 24f, SIZE_PX / 24f)
        canvas.drawPath(outline, paint)
        if (level > 0f) {
            // La nube ocupa de y = 4 a y = 20; el agua sube de 20,5 a 3 (un poco más arriba
            // del borde para que al final no quede una franja sin llenar). La onda se aplana
            // al llegar arriba.
            val top = 20.5f - level.coerceIn(0f, 1f) * 17.5f
            val amp = 0.9f * (1f - level)
            val wave = Path().apply {
                moveTo(-1f, 25f)
                lineTo(-1f, top)
                var x = -1f
                while (x <= 25f) {
                    lineTo(x, top + amp * sin(x / 24f * 2f * PI.toFloat() * 1.5f + phase))
                    x += 1f
                }
                lineTo(25f, 25f)
                close()
            }
            canvas.save()
            canvas.clipPath(wave)
            canvas.drawPath(solid, paint)
            canvas.restore()
        }
        return bmp
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

    const val ANIM_NONE = 0
    const val ANIM_FILL = 1
    const val ANIM_DRAIN = 2

    /** Animación pendiente del icono tras un toque en el tile; la consume el próximo `render()`. */
    @Volatile
    private var pendingAnim = ANIM_NONE

    fun takeAnim(): Int {
        val a = pendingAnim
        pendingAnim = ANIM_NONE
        return a
    }

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
                notify(app, Strings.get(R.string.agrega_un_servidor_primero), NoticeKind.Warning)
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
            // Solo si el estado cambió de verdad: llenar al montar, vaciar al desmontar. Si el
            // panel está cerrado nadie consume la marca, así que se limpia justo después del
            // render (los posts a main van en orden) y no se anima al volver a abrirlo.
            pendingAnim = when {
                after.mounted && !before.mounted -> ANIM_FILL
                !after.mounted && before.mounted -> ANIM_DRAIN
                else -> ANIM_NONE
            }
            publish(TileUi(after.mounted, after.remote, null))
            main.post { pendingAnim = ANIM_NONE }
            if (!result.success) notify(
                app,
                Strings.get(
                    R.string.error,
                    result.output.lines().lastOrNull { it.isNotBlank() }?.trim()?.take(200)
                        ?: Strings.get(R.string.sin_respuesta_sin_red_o_tiempo)
                ),
                NoticeKind.Error
            )
            // mount.sh lanza la precarga sola al montar, pero solo en el perfil Máximo
            // (preload.sh sale sin hacer nada en Equilibrado): solo ahí hay progreso que mostrar.
            if (result.success && after.mounted && !before.mounted &&
                RootShell.readPerfMode() == PerfMode.MAX
            ) PreloadService.start(app)
        }
    }

    /** Aviso expressive fuera de la app: notificación emergente breve (ver [SystemNotice]). */
    private fun notify(ctx: Context, text: String, kind: NoticeKind) =
        main.post { SystemNotice.show(ctx, text, kind) }
}
