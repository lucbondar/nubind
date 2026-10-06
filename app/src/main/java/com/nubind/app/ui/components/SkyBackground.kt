package com.nubind.app.ui.components

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Cielo flat de la cabecera de Acerca de. Sin sol ni luna: solo un degradado vertical suave que
 * recorre el día (noche, madrugada, mañana, mediodía, tarde y de nuevo noche), nubes de formas
 * planas que van a la deriva y se despejan o se juntan con el tiempo, estrellas que titilan de
 * noche y, de vez en cuando, una estrella fugaz.
 *
 * Costo: un solo `Canvas` en su propia capa (`graphicsLayer`), sin bitmaps ni recomposición; el
 * reloj de animación se lee solo en la fase de dibujo y se limita a ~30 fps. Se pausa cuando la
 * app no está a la vista y se queda quieto (cuadro estático) si las animaciones del sistema están
 * apagadas. De día no dibuja estrellas.
 */

// ---------------------------------------------------------------------------
// Paleta según la hora
// ---------------------------------------------------------------------------

private val SkyInk = Color(0xFF0B1B33)
private val SkyWhite = Color(0xFFF4F7FF)
private val StarColor = Color(0xFFEAF0FF)

/**
 * Colores del cielo a una hora dada: [top] y [bottom] del degradado, [stars] (0 = de día, 1 = noche
 * cerrada: cuánto se ven las estrellas) y [content], el color de texto con contraste sobre ese cielo.
 */
@Immutable
class SkyPalette(val top: Color, val bottom: Color, val stars: Float, val content: Color)

private class SkyKey(val hour: Float, val top: Color, val bottom: Color, val stars: Float)

/**
 * Fotogramas clave por hora local (0-24; la última repite la primera para cerrar el día). Entre uno y
 * otro se interpola suavemente. Etapas: noche (21:30-04:00), madrugada (04:00-06:45: índigo que se
 * aclara con un horizonte rosado), mañana (6:45-11), mediodía (cielo más pálido, para que la nube
 * azul del logo siga destacando), tarde (15-19: dorada, luego coral) y crepúsculo.
 */
private val SkyKeys = listOf(
    SkyKey(0.0f, Color(0xFF080C24), Color(0xFF1A2350), 1.00f),
    SkyKey(4.0f, Color(0xFF0F1640), Color(0xFF2A2F6A), 0.95f),
    SkyKey(5.5f, Color(0xFF272D74), Color(0xFF9A6A9E), 0.50f),
    SkyKey(6.75f, Color(0xFF5F8FD6), Color(0xFFFFC9A3), 0.00f),
    SkyKey(9.0f, Color(0xFF57A6EA), Color(0xFFC4E4F8), 0.00f),
    SkyKey(12.0f, Color(0xFF6CBAF2), Color(0xFFD5EEFC), 0.00f),
    SkyKey(15.0f, Color(0xFF5AA5E4), Color(0xFFC9E3F5), 0.00f),
    SkyKey(17.5f, Color(0xFF5B8AD0), Color(0xFFF6D49B), 0.00f),
    SkyKey(19.0f, Color(0xFF3F4C9E), Color(0xFFF29A72), 0.00f),
    SkyKey(20.25f, Color(0xFF222A6B), Color(0xFF8A4E8C), 0.35f),
    SkyKey(21.5f, Color(0xFF0C1232), Color(0xFF1B2658), 1.00f),
    SkyKey(24.0f, Color(0xFF080C24), Color(0xFF1A2350), 1.00f)
)

private fun smooth(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Paleta del cielo a la hora [hour] (0-24, con decimales). */
fun skyAt(hour: Float): SkyPalette {
    val h = ((hour % 24f) + 24f) % 24f
    val i = SkyKeys.indexOfLast { it.hour <= h }.coerceIn(0, SkyKeys.size - 2)
    val a = SkyKeys[i]
    val b = SkyKeys[i + 1]
    val f = smooth((h - a.hour) / (b.hour - a.hour))
    val top = lerp(a.top, b.top, f)
    val bottom = lerp(a.bottom, b.bottom, f)
    val stars = a.stars + (b.stars - a.stars) * f
    // El texto vive en la mitad baja de la tarjeta: se mide el brillo ahí, no en el centro exacto.
    val content = if (lerp(top, bottom, 0.6f).luminance() > 0.22f) SkyInk else SkyWhite
    return SkyPalette(top, bottom, stars, content)
}

// ---------------------------------------------------------------------------
// Relojes: hora del día y "a la vista"
// ---------------------------------------------------------------------------

private fun currentHour(): Float {
    val c = java.util.Calendar.getInstance()
    return c.get(java.util.Calendar.HOUR_OF_DAY) +
        c.get(java.util.Calendar.MINUTE) / 60f +
        c.get(java.util.Calendar.SECOND) / 3600f
}

@Composable
private fun rememberResumed(): State<Boolean> {
    val owner = LocalLifecycleOwner.current
    val resumed = remember(owner) {
        mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed.value = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return resumed
}

/** Hora local (0-24) que se refresca cada 20 s mientras la app está a la vista. */
@Composable
fun rememberSkyHour(): FloatState {
    val resumed = rememberResumed()
    val hour = remember { mutableFloatStateOf(currentHour()) }
    LaunchedEffect(resumed.value) {
        if (!resumed.value) return@LaunchedEffect
        while (isActive) {
            hour.floatValue = currentHour()
            delay(20_000L)
        }
    }
    return hour
}

private fun animationsOn(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

// ---------------------------------------------------------------------------
// Formas
// ---------------------------------------------------------------------------

/** Nube plana: píldora base con tres o cuatro "bultos" circulares. Ancho 100, alto ~46 (unidades propias). */
private fun cloudPath(variant: Int): Path = Path().apply {
    addRoundRect(RoundRect(0f, 26f, 100f, 46f, 10f, 10f))
    fun bump(cx: Float, cy: Float, r: Float) = addOval(Rect(Offset(cx, cy), r))
    if (variant == 0) {
        bump(28f, 28f, 13f); bump(52f, 22f, 20f); bump(76f, 30f, 12f)
    } else {
        bump(16f, 34f, 9f); bump(36f, 27f, 15f); bump(62f, 20f, 18f); bump(82f, 32f, 10f)
    }
}

private val CloudPaths = arrayOf(cloudPath(0), cloudPath(1))
private const val CLOUD_HALF_HEIGHT = 23f

/**
 * [y] = altura (fracción de la altura de referencia), [widthDp], [speed] en dp/s (las lejanas, más
 * chicas y lentas), [x0] posición inicial (fracción del recorrido), [alpha] opacidad propia y
 * [threshold] = nivel de nubosidad a partir del cual aparece (así el cielo a veces queda despejado).
 */
private class CloudSpec(
    val y: Float, val widthDp: Float, val speed: Float,
    val x0: Float, val alpha: Float, val threshold: Float, val variant: Int
)

private val Clouds = listOf(
    CloudSpec(0.10f, 120f, 5.5f, 0.15f, 0.55f, 0.00f, 0),
    CloudSpec(0.22f, 78f, 3.5f, 0.62f, 0.45f, 0.15f, 1),
    CloudSpec(0.33f, 150f, 8.0f, 0.85f, 0.90f, 0.35f, 0),
    CloudSpec(0.55f, 96f, 4.5f, 0.35f, 0.60f, 0.50f, 1),
    CloudSpec(0.70f, 170f, 9.0f, 0.05f, 0.85f, 0.62f, 1),
    CloudSpec(0.84f, 110f, 6.0f, 0.70f, 0.70f, 0.80f, 0)
)

private class Star(
    val x: Float, val y: Float, val rDp: Float,
    val phase: Float, val speed: Float, val base: Float, val sparkle: Boolean
)

private val Stars: List<Star> = run {
    val rnd = Random(20261006)
    List(40) {
        val big = rnd.nextFloat()
        Star(
            x = 0.03f + rnd.nextFloat() * 0.94f,
            y = 0.03f + rnd.nextFloat() * 0.75f,
            rDp = 0.55f + big * big * 1.2f,
            phase = rnd.nextFloat() * (2f * PI.toFloat()),
            speed = 0.6f + rnd.nextFloat() * 1.8f,
            base = 0.55f + rnd.nextFloat() * 0.45f,
            sparkle = big > 0.86f
        )
    }
}

/** Trayectoria de la estrella fugaz en curso (fracciones del ancho / de la altura de referencia). */
private class Shoot {
    var x = 0.8f
    var y = 0.1f
    var dx = -1f
    var dy = 0.4f
    var dist = 0.5f
    var tail = 0.2f

    fun randomize() {
        x = 0.40f + Random.nextFloat() * 0.55f
        y = 0.04f + Random.nextFloat() * 0.30f
        val a = Math.toRadians(Random.nextDouble(18.0, 34.0))
        dx = -cos(a).toFloat()
        dy = sin(a).toFloat()
        dist = 0.45f + Random.nextFloat() * 0.25f
        tail = 0.14f + Random.nextFloat() * 0.08f
    }
}

private const val FRAME_PAUSE_MS = 24L
private const val TWO_PI = 2.0 * PI

// ---------------------------------------------------------------------------
// Fondo
// ---------------------------------------------------------------------------

/**
 * Dibuja el cielo ocupando todo el padre. Va como primer hijo de un `Box`; el contenido va encima.
 * [hour] es la hora local de [rememberSkyHour].
 */
@Composable
fun BoxScope.SkyBackground(hour: FloatState) {
    val context = LocalContext.current
    val resumed = rememberResumed()
    val animate = remember(resumed.value) { animationsOn(context) }
    val running = resumed.value && animate

    // Reloj de animación (ms). Se lee solo al dibujar; ~30 fps aunque la pantalla sea de 90/120 Hz.
    val tick = remember { mutableLongStateOf(System.nanoTime() / 1_000_000L) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (isActive) {
            androidx.compose.runtime.withFrameNanos { tick.longValue = it / 1_000_000L }
            delay(FRAME_PAUSE_MS)
        }
    }

    // Estrella fugaz esporádica: solo de noche/madrugada, cada 8-22 s, ~0,75 s de recorrido.
    val shoot = remember { Shoot() }
    val shootProgress = remember { Animatable(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (isActive) {
            delay(Random.nextLong(8_000L, 22_000L))
            if (skyAt(hour.floatValue).stars < 0.55f) continue
            shoot.randomize()
            shootProgress.snapTo(0f)
            shootProgress.animateTo(1f, tween(750, easing = LinearEasing))
            shootProgress.snapTo(0f)
        }
    }

    Spacer(
        Modifier
            .matchParentSize()
            // Capa propia: las invalidaciones del reloj no re-graban el resto de la pantalla.
            .graphicsLayer()
            .drawWithCache {
                // Esta parte se rehace solo al cambiar el tamaño o la hora (cada ~20 s).
                val p = skyAt(hour.floatValue)
                val sky = Brush.verticalGradient(listOf(p.top, p.bottom))
                val day = 1f - p.stars
                val cloudColor = lerp(
                    lerp(p.top, Color.White, 0.16f),
                    lerp(p.bottom, Color.White, 0.72f),
                    day
                )
                val cloudAlpha = 0.45f + 0.45f * day
                val w = size.width
                val ref = minOf(size.height, 420.dp.toPx())
                val dp = density

                onDrawBehind {
                    drawRect(sky)
                    val t = tick.longValue / 1000.0

                    // Estrellas (solo si hay noche: de día no cuestan nada).
                    if (p.stars > 0.02f) {
                        for (s in Stars) {
                            val tw = 0.6f + 0.4f * sin(t * s.speed + s.phase).toFloat()
                            val a = (s.base * tw * p.stars).coerceIn(0f, 1f)
                            val c = Offset(s.x * w, s.y * ref)
                            drawCircle(StarColor, s.rDp * dp, c, alpha = a)
                            if (s.sparkle) {
                                val arm = s.rDp * dp * 3.2f
                                val stroke = 0.7f * dp
                                drawLine(StarColor, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), stroke, alpha = a * 0.8f)
                                drawLine(StarColor, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), stroke, alpha = a * 0.8f)
                            }
                        }
                    }

                    // Nubosidad que va y viene (dos ondas lentas de ~7 y ~13 min).
                    val cover = 0.62f * (0.5f + 0.5f * sin(t * TWO_PI / 420.0 + 1.3).toFloat()) +
                        0.38f * (0.5f + 0.5f * sin(t * TWO_PI / 777.0 + 4.1).toFloat())
                    for (c in Clouds) {
                        val vis = smooth((cover - c.threshold) / 0.24f + 0.5f)
                        val a = cloudAlpha * c.alpha * vis
                        if (a < 0.02f) continue
                        val wPx = c.widthDp * dp
                        val span = (w + wPx).toDouble()
                        val x = ((c.x0 * span + c.speed * dp * t) % span).toFloat() - wPx
                        val s = wPx / 100f
                        translate(x, c.y * ref - CLOUD_HALF_HEIGHT * s) {
                            scale(s, Offset.Zero) {
                                drawPath(CloudPaths[c.variant], cloudColor, alpha = a)
                            }
                        }
                    }

                    // Estrella fugaz.
                    val sp = shootProgress.value
                    if (sp > 0f && sp < 1f) {
                        val e = 1f - (1f - sp) * (1f - sp)
                        val travel = shoot.dist * w * e
                        val head = Offset(shoot.x * w + shoot.dx * travel, shoot.y * ref + shoot.dy * travel)
                        val tailLen = minOf(travel, shoot.tail * w)
                        val tail = Offset(head.x - shoot.dx * tailLen, head.y - shoot.dy * tailLen)
                        val env = sin(sp * PI.toFloat())
                        drawLine(
                            brush = Brush.linearGradient(
                                listOf(Color.White.copy(alpha = 0f), Color.White),
                                start = tail, end = head
                            ),
                            start = tail, end = head,
                            strokeWidth = 1.6f * dp, cap = StrokeCap.Round, alpha = env
                        )
                        drawCircle(Color.White, 1.4f * dp, head, alpha = env)
                    }
                }
            }
    )
}
