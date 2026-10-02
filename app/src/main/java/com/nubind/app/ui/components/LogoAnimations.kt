package com.nubind.app.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// Logo animado de "Acerca de". A los 1,5 s de abrir la pantalla la nube da el
// saltito clásico; después, cada 6 s, se elige al azar una de las 10 animaciones
// nuevas (sin repetir la anterior). Tocar el logo lanza una al azar al momento.
// Si el usuario desactivó las animaciones del sistema (escala 0), se queda quieta.
private const val LOGO_FIRST_DELAY_MS = 1500L
private const val LOGO_INTERVAL_MS = 6000L

private val Navy = Color(0xFF0F3D6E)
private val LockBlue = Color(0xFF1E5F99)
private val OkGreen = Color(0xFF2E9E5B)
private val BoltYellow = Color(0xFFFFC107)

private enum class LogoScene {
    HOP,       // saltito con rebote (el original)
    CHAIN,     // una cadena (bind) se enrolla alrededor de la nube y la aprieta
    DROP,      // cae el logo de un proveedor S3 / Drive y la nube lo traga
    UPLOAD,    // flechas de subida salen de la nube
    DOWNLOAD,  // aparece una carpeta y la nube le manda archivos (el montaje)
    FLIP,      // giro 3D completo
    ZAP,       // rayo: la nube destella y tiembla
    ORBIT,     // los logos de Amazon S3, Cloudflare, Oracle y Drive orbitan
    LOCK,      // candado + check: conexión segura
    SLEEP,     // la nube se duerme (zzz)
    JELLY      // gelatina
}

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

private class LogoState(val density: Float) {
    // Transformación de la nube (px / grados).
    val x = Animatable(0f)
    val y = Animatable(0f)
    val sx = Animatable(1f)
    val sy = Animatable(1f)
    val tilt = Animatable(0f)
    val flipY = Animatable(0f)
    val flash = Animatable(0f)
    // Progresos genéricos (0..1) de los objetos de cada escena.
    val p = Animatable(0f)
    val q = Animatable(0f)

    var scene by mutableStateOf<LogoScene?>(null)
    var brand by mutableIntStateOf(0)

    fun px(dp: Float) = dp * density

    suspend fun reset() {
        scene = null
        x.snapTo(0f); y.snapTo(0f); tilt.snapTo(0f); flipY.snapTo(0f)
        flash.snapTo(0f); p.snapTo(0f); q.snapTo(0f)
        sx.snapTo(1f); sy.snapTo(1f)
    }

    suspend fun play(s: LogoScene) {
        scene = s
        when (s) {
            LogoScene.HOP -> hop()
            LogoScene.CHAIN -> chain()
            LogoScene.DROP -> drop()
            LogoScene.UPLOAD -> upload()
            LogoScene.DOWNLOAD -> download()
            LogoScene.FLIP -> flip()
            LogoScene.ZAP -> zap()
            LogoScene.ORBIT -> orbit()
            LogoScene.LOCK -> lock()
            LogoScene.SLEEP -> sleep()
            LogoScene.JELLY -> jelly()
        }
    }

    private suspend fun hop() {
        val hopPx = px(8f)
        coroutineScope {
            launch { sy.animateTo(0.9f, tween(110)) }
            launch { sx.animateTo(1.07f, tween(110)) }
        }
        coroutineScope {
            launch { y.animateTo(-hopPx, tween(200, easing = FastOutSlowInEasing)) }
            launch { sy.animateTo(1.1f, tween(200)) }
            launch { sx.animateTo(0.94f, tween(200)) }
            launch { tilt.animateTo(-6f, tween(200)) }
        }
        coroutineScope {
            launch { y.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 450f)) }
            launch { sy.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 450f)) }
            launch { sx.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 450f)) }
            launch { tilt.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 400f)) }
        }
    }

    /** Una cadena se enrolla por la nube, la aprieta (bind) y cae. */
    private suspend fun chain() {
        p.animateTo(1f, tween(500, easing = LinearEasing))
        coroutineScope {
            launch { sx.animateTo(0.9f, tween(120)) }
            launch { sy.animateTo(1.06f, tween(120)) }
        }
        coroutineScope {
            launch { sx.animateTo(1f, spring(dampingRatio = 0.3f, stiffness = 350f)) }
            launch { sy.animateTo(1f, spring(dampingRatio = 0.3f, stiffness = 350f)) }
        }
        delay(250)
        coroutineScope {
            launch { q.animateTo(1f, tween(550, easing = FastOutLinearInEasing)) }
            launch {
                delay(150)
                y.animateTo(-px(4f), tween(100))
                y.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 450f))
            }
        }
    }

    /** Cae el logo de un proveedor al azar y la nube lo traga. */
    private suspend fun drop() {
        brand = (0..3).random()
        p.animateTo(1f, tween(450, easing = FastOutLinearInEasing))
        coroutineScope {
            launch { sy.animateTo(0.82f, tween(80)) }
            launch { sx.animateTo(1.1f, tween(80)) }
            launch { q.animateTo(1f, tween(260)) }
        }
        coroutineScope {
            launch { sy.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 500f)) }
            launch { sx.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 500f)) }
            launch {
                y.animateTo(-px(5f), tween(100))
                y.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 450f))
            }
        }
    }

    /** Dos flechas de subida salen de la nube. */
    private suspend fun upload() {
        coroutineScope {
            launch {
                sy.animateTo(0.92f, tween(100))
                sy.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 500f))
            }
            launch { p.animateTo(1f, tween(800, easing = FastOutSlowInEasing)) }
            launch {
                delay(250)
                q.animateTo(1f, tween(800, easing = FastOutSlowInEasing))
            }
            launch {
                delay(100)
                y.animateTo(-px(3f), tween(250))
                y.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 400f))
            }
        }
    }

    /** Aparece una carpeta abajo y la nube le manda flechas (montaje). */
    private suspend fun download() {
        p.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 400f))
        repeat(2) {
            q.snapTo(0f)
            q.animateTo(1f, tween(450, easing = FastOutLinearInEasing))
            sy.animateTo(0.95f, tween(60))
            sy.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 500f))
        }
        delay(150)
        p.animateTo(0f, tween(250))
    }

    private suspend fun flip() {
        coroutineScope {
            launch { flipY.animateTo(360f, tween(800, easing = FastOutSlowInEasing)) }
            launch {
                y.animateTo(-px(6f), tween(400, easing = FastOutSlowInEasing))
                y.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 400f))
            }
        }
    }

    /** Rayo: la nube destella y tiembla. */
    private suspend fun zap() {
        coroutineScope {
            launch {
                repeat(3) {
                    coroutineScope {
                        launch { flash.animateTo(1f, tween(50)) }
                        launch { p.animateTo(1f, tween(50)) }
                    }
                    coroutineScope {
                        launch { flash.animateTo(0f, tween(90)) }
                        launch { p.animateTo(0f, tween(90)) }
                    }
                    delay(60)
                }
            }
            launch {
                repeat(12) { i ->
                    x.animateTo(if (i % 2 == 0) px(3f) else -px(3f), tween(45))
                }
                x.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 500f))
            }
        }
    }

    /** Los logos de Amazon S3, Cloudflare, Oracle y Drive orbitan la nube. */
    private suspend fun orbit() {
        coroutineScope {
            launch { p.animateTo(1f, tween(2000, easing = LinearEasing)) }
            launch {
                q.animateTo(1f, tween(300))
                delay(1400)
                q.animateTo(0f, tween(300))
            }
            launch {
                tilt.animateTo(-4f, tween(500))
                tilt.animateTo(4f, tween(1000))
                tilt.animateTo(0f, tween(500))
            }
        }
    }

    /** Aparece un candado y luego un check: conexión segura. */
    private suspend fun lock() {
        coroutineScope {
            launch {
                sy.animateTo(0.92f, tween(90))
                sy.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 500f))
            }
            launch { p.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 500f)) }
        }
        delay(250)
        q.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 600f))
        delay(800)
        coroutineScope {
            launch { p.animateTo(0f, tween(200)) }
            launch { q.animateTo(0f, tween(200)) }
        }
    }

    /** La nube se duerme: se ladea, respira y salen "z". */
    private suspend fun sleep() {
        coroutineScope {
            launch { tilt.animateTo(-8f, tween(600)) }
            launch { p.animateTo(1f, tween(2600, easing = LinearEasing)) }
            launch {
                repeat(2) {
                    sy.animateTo(0.95f, tween(650))
                    sy.animateTo(1f, tween(650))
                }
            }
        }
        tilt.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 400f))
    }

    private suspend fun jelly() {
        coroutineScope {
            launch { sx.animateTo(1.28f, tween(90)) }
            launch { sy.animateTo(0.78f, tween(90)) }
            launch { tilt.animateTo(4f, tween(90)) }
        }
        coroutineScope {
            launch { sx.animateTo(1f, spring(dampingRatio = 0.22f, stiffness = 260f)) }
            launch { sy.animateTo(1f, spring(dampingRatio = 0.22f, stiffness = 260f)) }
            launch { tilt.animateTo(0f, spring(dampingRatio = 0.25f, stiffness = 200f)) }
        }
    }
}

// La primera animación (a los 1,5 s) es siempre el saltito; el resto se sortea
// entre las 10 nuevas, sin repetir la anterior.
private fun pickScene(previous: LogoScene?): LogoScene =
    LogoScene.values().filter { it != LogoScene.HOP && it != previous }.random()

@Composable
fun AnimatedLogo() {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val st = remember { LogoState(density) }
    var replay by remember { mutableIntStateOf(0) }

    LaunchedEffect(replay) {
        val animationsOn = Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) > 0f
        if (!animationsOn) return@LaunchedEffect
        // Un toque reinicia desde la pose de reposo con una animación al azar.
        st.reset()
        delay(if (replay == 0) LOGO_FIRST_DELAY_MS else 0L)
        var next = if (replay == 0) LogoScene.HOP else pickScene(null)
        while (isActive) {
            st.play(next)
            st.reset()
            delay(LOGO_INTERVAL_MS)
            next = pickScene(next)
        }
    }

    Box(
        Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF2A8DE0))
            .pointerInput(Unit) { detectTapGestures { replay++ } },
        contentAlignment = Alignment.Center
    ) {
        val cloudTransform: GraphicsLayerScope.() -> Unit = {
            translationX = st.x.value
            translationY = st.y.value
            scaleX = st.sx.value
            scaleY = st.sy.value
            rotationZ = st.tilt.value
            rotationY = st.flipY.value
            cameraDistance = 12f * density
            // Pivote en la base de la nube, para que el squash apoye en el "suelo".
            transformOrigin = TransformOrigin(0.5f, 0.72f)
        }
        Image(
            AppIcons.Logo,
            contentDescription = null,
            modifier = Modifier.size(96.dp).graphicsLayer(cloudTransform)
        )
        LogoProps(st, cloudTransform)
    }
}

private fun brandIcon(index: Int): ImageVector = when (index % 4) {
    0 -> AppIcons.AwsLogo
    1 -> AppIcons.CloudflareLogo
    2 -> AppIcons.OracleLogo
    else -> AppIcons.DriveLogo
}

@Composable
private fun BrandBadge(index: Int, size: Dp, modifier: Modifier) {
    Box(
        modifier.size(size).clip(CircleShape).background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Image(brandIcon(index), contentDescription = null, modifier = Modifier.size(size * 0.72f))
    }
}

/** Posiciona (en dp, desde arriba a la izquierda); los valores se leen en la fase de layout. */
private fun Modifier.placeAt(st: LogoState, x: () -> Float, y: () -> Float): Modifier =
    this.offset { IntOffset(st.px(x()).roundToInt(), st.px(y()).roundToInt()) }

@Composable
private fun BoxScope.LogoProps(st: LogoState, cloudTransform: GraphicsLayerScope.() -> Unit) {
    val tl = Alignment.TopStart
    when (st.scene) {
        LogoScene.CHAIN -> Canvas(Modifier.fillMaxSize()) {
            val n = 7
            val cx = size.width / 2f
            val fall = st.q.value
            for (i in 0 until n) {
                val a = (st.p.value * n - i).coerceIn(0f, 1f)
                if (a <= 0f) continue
                val bx = size.width * (0.17f + 0.66f * i / (n - 1))
                val bx2 = cx + (bx - cx) * st.sx.value
                val sag = sin(PI * i / (n - 1)).toFloat() * 3.dp.toPx()
                val by = 56.dp.toPx() + sag + fall * fall * 70.dp.toPx() - (1f - a) * 6.dp.toPx()
                val w = (if (i % 2 == 0) 13.dp else 7.dp).toPx()
                val h = 8.dp.toPx()
                drawRoundRect(
                    color = Navy.copy(alpha = a * (1f - fall * 0.7f)),
                    topLeft = Offset(bx2 - w / 2f, by - h / 2f),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(h / 2f),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }

        LogoScene.DROP -> BrandBadge(
            st.brand, 28.dp,
            Modifier.align(tl)
                .placeAt(st, { 34f }, { lerpF(-30f, 20f, st.p.value) })
                .graphicsLayer {
                    val s = 1f - st.q.value
                    scaleX = s; scaleY = s; alpha = s
                }
        )

        LogoScene.UPLOAD -> {
            for (idx in 0..1) {
                val t = { if (idx == 0) st.p.value else st.q.value }
                Icon(
                    AppIcons.Download, contentDescription = null, tint = Navy,
                    modifier = Modifier.align(tl).size(16.dp)
                        .placeAt(st, { if (idx == 0) 24f else 56f }, { lerpF(40f, -4f, t()) })
                        .graphicsLayer {
                            rotationZ = 180f
                            alpha = sin(PI * t()).toFloat().coerceIn(0f, 1f)
                        }
                )
            }
        }

        LogoScene.DOWNLOAD -> {
            Icon(
                AppIcons.Folder, contentDescription = null, tint = Color.White,
                modifier = Modifier.align(tl).size(24.dp)
                    .placeAt(st, { 36f }, { lerpF(100f, 68f, st.p.value) })
                    .graphicsLayer { alpha = st.p.value.coerceIn(0f, 1f) }
            )
            Icon(
                AppIcons.Download, contentDescription = null, tint = Navy,
                modifier = Modifier.align(tl).size(16.dp)
                    .placeAt(st, { 40f }, { lerpF(42f, 70f, st.q.value) })
                    .graphicsLayer { alpha = sin(PI * st.q.value).toFloat().coerceIn(0f, 1f) }
            )
        }

        LogoScene.ZAP -> {
            Image(
                AppIcons.Logo, contentDescription = null,
                colorFilter = ColorFilter.tint(Color.White),
                modifier = Modifier.size(96.dp).graphicsLayer {
                    cloudTransform()
                    alpha = st.flash.value.coerceIn(0f, 1f)
                }
            )
            Icon(
                AppIcons.Bolt, contentDescription = null, tint = BoltYellow,
                modifier = Modifier.align(Alignment.Center).size(46.dp).graphicsLayer {
                    val s = 0.8f + 0.4f * st.p.value
                    scaleX = s; scaleY = s
                    alpha = st.p.value.coerceIn(0f, 1f)
                }
            )
        }

        LogoScene.ORBIT -> {
            for (i in 0..3) {
                val ang = { 2.0 * PI * (st.p.value + i / 4f) }
                BrandBadge(
                    i, 20.dp,
                    Modifier.align(tl)
                        .placeAt(st, { 38f + 34f * cos(ang()).toFloat() }, { 40f + 16f * sin(ang()).toFloat() })
                        .graphicsLayer {
                            val depth = (sin(ang()).toFloat() + 1f) / 2f
                            val s = 0.65f + 0.35f * depth
                            scaleX = s; scaleY = s
                            alpha = st.q.value.coerceIn(0f, 1f)
                        }
                )
            }
        }

        LogoScene.LOCK -> {
            Icon(
                Icons.Filled.Lock, contentDescription = null, tint = LockBlue,
                modifier = Modifier.align(tl).size(26.dp)
                    .placeAt(st, { 35f }, { 37f })
                    .graphicsLayer {
                        val s = st.p.value.coerceAtLeast(0f)
                        scaleX = s; scaleY = s
                    }
            )
            Box(
                Modifier.align(tl).size(15.dp)
                    .placeAt(st, { 51f }, { 33f })
                    .graphicsLayer {
                        val s = st.q.value.coerceAtLeast(0f)
                        scaleX = s; scaleY = s
                    }
                    .clip(CircleShape).background(OkGreen),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
            }
        }

        LogoScene.SLEEP -> {
            for (i in 0..2) {
                val t = { ((st.p.value - i * 0.2f) / 0.6f).coerceIn(0f, 1f) }
                Text(
                    "z", color = Navy, fontSize = (11 + i * 3).sp,
                    modifier = Modifier.align(tl)
                        .placeAt(st, { 62f + i * 7f + t() * 6f }, { 28f - i * 6f - t() * 16f })
                        .graphicsLayer { alpha = sin(PI * t()).toFloat().coerceIn(0f, 1f) }
                )
            }
        }

        else -> Unit
    }
}
