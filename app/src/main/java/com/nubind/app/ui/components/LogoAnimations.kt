package com.nubind.app.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

// Logo animado de "Acerca de".
//
// Cada cierto tiempo (al azar, entre 4,5 y 9 s) la nube protagoniza una escena
// elegida al azar de una "baraja" que se baraja de nuevo cuando se agota, así
// que se ven todas antes de repetir y nunca sale la misma dos veces seguidas.
// Las escenas representan lo que hace la app: montar una carpeta, el mazo de
// servidores (FTP / Drive / S3), la prueba de rendimiento, la precarga, el
// acceso root, los proveedores S3, la sincronización y el montaje automático.
//
// Diseño:
//  - Un solo reloj por escena (milisegundos). La pose de la nube y todos los
//    objetos son funciones puras de ese reloj, así que no hay corrutinas por
//    objeto ni recomposiciones: todo se lee en las fases de layer/dibujo.
//  - Todo el dibujo es vectorial (Canvas) en un lienzo de 108 x 108, el mismo
//    del icono. No hay bitmaps, assets ni dependencias nuevas.
//  - Squash & stretch con volumen constante, anticipación, rebote amortiguado
//    y sombra de contacto, para que se sienta física y no mecánica.
//  - Tocar el logo lanza una escena al instante. Si el usuario apagó las
//    animaciones del sistema (escala 0), la nube se queda quieta.

private val CloudBlue = Color(0xFF2A8DE0)
private val Ice = Color(0xFFBCD1E6)
private val Navy = Color(0xFF0F3D6E)
private val Ink = Color(0xFF134F87)
private val Gold = Color(0xFFFFCE74)
private val GoldDark = Color(0xFFE9B24C)
private val Mint = Color(0xFF91E4CD)
private val CardFtp = Color(0xFFB4CAD6)
private val CardDrive = Color(0xFFCBC1E9)
private val Terminal = Color(0xFF0B2A4A)

private val PIF = PI.toFloat()

private enum class LogoScene(val ms: Int, val shadow: Boolean) {
    Hop(1500, true),        // salto con anticipación, aterrizaje y polvo
    Mount(3200, false),     // sube una carpeta, caen archivos y queda "montada"
    Servers(3400, false),   // entra el mazo FTP / Drive / S3 y se elige uno
    Speed(2800, true),      // prueba de rendimiento: velocímetro y estelas
    Preload(3000, false),   // paquetes absorbidos y barra de precarga
    Root(3000, false),      // terminal con "#" y escudo de acceso root
    Orbit(3400, false),     // logos de Amazon S3, Cloudflare, Oracle y Drive orbitan
    Sync(3200, false),      // flechas de sincronización alrededor de la nube
    Wake(3600, true)        // dormida con zzz; el interruptor de autostart la despierta
}

// Baraja: recorre todas las escenas en orden aleatorio antes de repetir y
// evita que la última de una vuelta sea la primera de la siguiente.
private class SceneDeck {
    private val bag = ArrayList<LogoScene>()
    private var last: LogoScene? = null

    fun next(): LogoScene {
        if (bag.isEmpty()) {
            bag.addAll(LogoScene.values().toList().shuffled())
            if (bag.size > 1 && bag[bag.lastIndex] == last) {
                val i = bag.lastIndex
                val t = bag[0]
                bag[0] = bag[i]
                bag[i] = t
            }
        }
        val s = bag.removeAt(bag.lastIndex)
        last = s
        return s
    }
}

// ---------------------------------------------------------------- matemática

private fun lgClamp(x: Float) = x.coerceIn(0f, 1f)
private fun lgMix(a: Float, b: Float, f: Float) = a + (b - a) * f

/** Progreso 0..1 de [ms] dentro de la ventana [a, b] (en milisegundos). */
private fun lgPh(ms: Float, a: Int, b: Int) = lgClamp((ms - a) / (b - a).toFloat())

private fun lgEio(f: Float) = f * f * (3f - 2f * f)
private fun lgEout(f: Float): Float {
    val k = 1f - f
    return 1f - k * k * k
}
private fun lgEin(f: Float) = f * f

/** Entrada con sobrepaso (pop): 0 -> ~1,1 -> 1. */
private fun lgBack(f: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val k = f - 1f
    return 1f + c3 * k * k * k + c1 * k * k
}

/** Oscilación amortiguada que arranca en [amp] y llega a 0 exacto en f = 1. */
private fun lgOsc(f: Float, amp: Float, k: Float, cycles: Float) =
    amp * (1f - f) * exp(-k * f) * cos(2f * PIF * cycles * f)

private fun Color.al(a: Float) = copy(alpha = a.coerceIn(0f, 1f))

// ---------------------------------------------------------------------- pose

private class LogoPose(val x: Float, val y: Float, val sy: Float, val sx: Float, val tilt: Float)

private val RestPose = LogoPose(0f, 0f, 1f, 1f, 0f)

// x, y en unidades del lienzo de 108; sy es el estiramiento vertical y sx se
// deriva de él para conservar el volumen (salvo que la escena lo fije).
private fun logoPose(s: LogoScene, ms: Float): LogoPose {
    var x = 0f
    var y = 0f
    var sy = 1f
    var tilt = 0f
    var sx = -1f
    when (s) {
        LogoScene.Hop -> {
            if (ms < 170f) {
                sy = lgMix(1f, .86f, lgEio(lgPh(ms, 0, 170)))
            } else if (ms < 500f) {
                val b = lgPh(ms, 170, 500)
                y = -15f * lgEout(b)
                sy = lgMix(.86f, 1.1f, lgEout(b))
                tilt = -6f * lgEout(b)
            } else if (ms < 690f) {
                val c = lgPh(ms, 500, 690)
                y = -15f * (1f - lgEin(c))
                sy = lgMix(1.1f, 1.06f, c)
                tilt = lgMix(-6f, 3f, c)
            } else {
                val d = lgPh(ms, 690, 1500)
                sy = 1f + lgOsc(d, -.17f, 3.2f, 2.5f)
                tilt = lgOsc(d, 3.5f, 3.5f, 2f)
                y = -4f * sin(PIF * lgPh(ms, 700, 980))
            }
        }
        LogoScene.Mount -> {
            val rise = lgEio(lgPh(ms, 0, 450)) - lgEio(lgPh(ms, 2500, 3000))
            y = -8f * rise
            for (i in 0..2) sy -= .05f * sin(PIF * lgPh(ms, 500 + i * 330, 700 + i * 330))
            y += -7f * sin(PIF * lgPh(ms, 1950, 2450))
            if (ms > 2300f) sy += lgOsc(lgPh(ms, 2300, 2800), -.1f, 3.5f, 2f)
        }
        LogoScene.Servers -> {
            val up = lgEio(lgPh(ms, 100, 600)) - lgEio(lgPh(ms, 2900, 3350))
            y = -9f * up
            val nod = sin(PIF * lgPh(ms, 1500, 2000))
            tilt = 5f * nod
            y += -2f * nod
            if (ms > 2000f && ms < 2900f) sy += lgOsc(lgPh(ms, 2000, 2600), -.07f, 3f, 1.5f)
        }
        LogoScene.Speed -> {
            if (ms < 260f) {
                val a = lgEio(lgPh(ms, 0, 260))
                x = -3f * a
                tilt = -7f * a
                sy = 1f - .04f * a
            } else if (ms < 2000f) {
                val b = lgEout(lgPh(ms, 260, 700))
                x = lgMix(-3f, 2f, b)
                tilt = lgMix(-7f, 7f, b)
                sy = lgMix(.96f, 1f, b)
                y = .7f * sin(ms * .09f)
                sx = 1.05f
            } else {
                val u = lgPh(ms, 2000, 2800)
                x = 2f * (1f - lgEout(u)) + lgOsc(u, -2.5f, 3f, 2f)
                tilt = 7f * (1f - lgEout(u)) + lgOsc(u, 4f, 3f, 2f)
            }
        }
        LogoScene.Preload -> {
            y = -3f * (lgEio(lgPh(ms, 0, 400)) - lgEio(lgPh(ms, 2700, 3000)))
            for (i in 0..5) {
                val t = 700 + i * 210
                if (ms >= t) sy += lgOsc(lgPh(ms, t, t + 450), -.06f, 3f, 1.5f)
            }
            y += -6f * sin(PIF * lgPh(ms, 2100, 2500))
        }
        LogoScene.Root -> {
            val rise = lgEio(lgPh(ms, 100, 600)) - lgEio(lgPh(ms, 2600, 3000))
            y = -8f * rise
            tilt = 5f * sin(PIF * lgPh(ms, 700, 1500))
            y += -5f * sin(PIF * lgPh(ms, 1550, 2050))
            if (ms > 1950f && ms < 2600f) sy += lgOsc(lgPh(ms, 1950, 2500), -.09f, 3.2f, 2f)
        }
        LogoScene.Orbit -> {
            val env = sin(PIF * lgPh(ms, 0, 3400))
            tilt = 3.5f * sin(2f * PIF * lgPh(ms, 0, 3400) * 1.5f) * env
            y = -2f * env
            if (ms > 3050f) sy += lgOsc(lgPh(ms, 3050, 3400), -.08f, 3f, 1.5f)
        }
        LogoScene.Sync -> {
            val env = sin(PIF * lgPh(ms, 0, 3200))
            y = -2f * env
            sy += .02f * sin(2f * PIF * ms / 700f) * env
            y += -5f * sin(PIF * lgPh(ms, 2400, 2800))
        }
        LogoScene.Wake -> {
            if (ms < 400f) {
                tilt = -9f * lgEio(ms / 400f)
            } else if (ms < 2350f) {
                tilt = -9f
                sy = 1f + .03f * sin(2f * PIF * ms / 1400f)
            } else if (ms < 2500f) {
                val a = lgPh(ms, 2350, 2500)
                sy = lgMix(.98f, .9f, lgEio(a))
                tilt = lgMix(-9f, -3f, lgEio(a))
            } else if (ms < 2800f) {
                val b = lgPh(ms, 2500, 2800)
                y = -14f * lgEout(b)
                sy = lgMix(.9f, 1.1f, lgEout(b))
                tilt = lgMix(-3f, 0f, b)
            } else if (ms < 2980f) {
                val c = lgPh(ms, 2800, 2980)
                y = -14f * (1f - lgEin(c))
                sy = lgMix(1.1f, 1.05f, c)
            } else {
                val d = lgPh(ms, 2980, 3600)
                sy = 1f + lgOsc(d, -.15f, 3.2f, 2.5f)
                tilt = lgOsc(d, 3f, 3.5f, 2f)
                y = -3f * sin(PIF * lgPh(ms, 2990, 3250))
            }
        }
    }
    return LogoPose(x, y, sy, if (sx < 0f) 1f + (1f - sy) * .8f else sx, tilt)
}

// ----------------------------------------------------------------- primitivas

private fun DrawScope.fillRound(c: Color, x: Float, y: Float, w: Float, h: Float, r: Float, a: Float = 1f) {
    if (a <= 0f || w <= 0f) return
    drawRoundRect(c.al(a), Offset(x, y), Size(w, h), CornerRadius(r))
}

private fun DrawScope.fillDot(c: Color, cx: Float, cy: Float, r: Float, a: Float = 1f) {
    if (a <= 0f || r <= 0f) return
    drawCircle(c.al(a), r, Offset(cx, cy))
}

private fun DrawScope.strokeDot(c: Color, cx: Float, cy: Float, r: Float, w: Float, a: Float = 1f) {
    if (a <= 0f || r <= 0f) return
    drawCircle(c.al(a), r, Offset(cx, cy), style = Stroke(w))
}

private fun DrawScope.strokeLine(c: Color, x1: Float, y1: Float, x2: Float, y2: Float, w: Float, a: Float = 1f) {
    if (a <= 0f) return
    drawLine(c.al(a), Offset(x1, y1), Offset(x2, y2), strokeWidth = w, cap = StrokeCap.Round)
}

private fun DrawScope.strokeArc(
    c: Color, cx: Float, cy: Float, rx: Float, ry: Float,
    start: Float, sweep: Float, w: Float, a: Float = 1f
) {
    if (a <= 0f || sweep == 0f) return
    drawArc(
        c.al(a), start, sweep, false,
        Offset(cx - rx, cy - ry), Size(rx * 2f, ry * 2f),
        style = Stroke(w, cap = StrokeCap.Round)
    )
}

private fun DrawScope.drawCheck(cx: Float, cy: Float, r: Float, s: Float, a: Float = 1f) {
    if (s <= 0f || a <= 0f) return
    translate(cx, cy) {
        scale(s, pivot = Offset.Zero) {
            fillDot(Mint, 0f, 0f, r, a)
            val k = r / 6f
            strokeLine(Navy, -3f * k, 0f, -1f * k, 2f * k, 1.5f, a)
            strokeLine(Navy, -1f * k, 2f * k, 3f * k, -2f * k, 1.5f, a)
        }
    }
}

private fun DrawScope.drawShield(cx: Float, cy: Float, s: Float, a: Float = 1f) {
    if (s <= 0f || a <= 0f) return
    translate(cx, cy) {
        scale(s, pivot = Offset.Zero) {
            val path = Path().apply {
                moveTo(0f, -9f)
                lineTo(7.5f, -6f)
                lineTo(7.5f, 1f)
                cubicTo(7.5f, 5f, 5f, 8f, 0f, 10f)
                cubicTo(-5f, 8f, -7.5f, 5f, -7.5f, 1f)
                lineTo(-7.5f, -6f)
                close()
            }
            drawPath(path, Mint.al(a))
            strokeLine(Navy, -3f, .5f, -.8f, 3f, 1.7f, a)
            strokeLine(Navy, -.8f, 3f, 3.4f, -2.2f, 1.7f, a)
        }
    }
}

private fun DrawScope.drawFolderBack(top: Float) {
    fillRound(GoldDark, 31f, top - 4.5f, 17f, 8f, 2.4f)
    fillRound(GoldDark, 31f, top, 46f, 24f, 4.5f)
}

private fun DrawScope.drawFolderFront(top: Float) {
    fillRound(Gold, 31f, top + 7f, 46f, 28f, 4.5f)
    fillRound(Color.White, 34.5f, top + 9.7f, 39f, 1.5f, .75f, .45f)
}

private fun DrawScope.drawFile(cx: Float, cy: Float, rot: Float, s: Float, a: Float) {
    translate(cx, cy) {
        rotate(rot, Offset.Zero) {
            scale(s, pivot = Offset.Zero) {
                fillRound(Color.White, -4.5f, -6f, 9f, 12f, 1.8f, a)
                strokeLine(Ink, -2.2f, -2f, 2.2f, -2f, 1f, .5f * a)
                strokeLine(Ink, -2.2f, 1f, 1f, 1f, 1f, .5f * a)
            }
        }
    }
}

private fun DrawScope.drawZ(x: Float, y: Float, s: Float, a: Float) {
    if (a <= 0f) return
    strokeLine(Color.White, x, y, x + s, y, 1.7f, a)
    strokeLine(Color.White, x + s, y, x, y + s, 1.7f, a)
    strokeLine(Color.White, x, y + s, x + s, y + s, 1.7f, a)
}

private fun DrawScope.drawBrand(p: Painter, cx: Float, cy: Float, size: Float, a: Float) {
    if (a <= 0f || size <= 0f) return
    translate(cx - size / 2f, cy - size / 2f) {
        with(p) { draw(Size(size, size), alpha = a.coerceIn(0f, 1f)) }
    }
}

// ---------------------------------------------------------------------- orbit

// Los 4 logos orbitan en una elipse con profundidad: los de atrás se dibujan
// antes de la nube (quedan tapados) y los de delante después.
private fun DrawScope.drawOrbit(ms: Float, front: Boolean, brands: List<Painter>) {
    val rs = lgBack(lgPh(ms, 0, 550)) * (1f - lgEin(lgPh(ms, 2500, 3100)))
    if (rs <= 0f) return
    val a0 = 2f * PIF * lgEio(lgPh(ms, 300, 2500))
    if (!front) {
        drawOval(
            Ice.al(.25f * lgClamp(rs)),
            Offset(54f - 40f * rs, 50f - 13f * rs), Size(80f * rs, 26f * rs),
            style = Stroke(1f)
        )
    }
    for (i in 0..3) {
        val a = a0 + i * PIF / 2f
        val sn = sin(a)
        if ((sn > 0f) != front) continue
        val depth = (sn + 1f) / 2f
        val sz = 22f * (.72f + .28f * depth) * minOf(rs, 1.05f)
        val cx = 54f + 40f * cos(a) * rs
        val cy = 50f + 13f * sn * rs
        val al = lgClamp(rs * 1.5f) * (.8f + .2f * depth)
        fillDot(Navy, cx, cy + 1.2f, sz / 2f + .6f, .18f * al)
        fillDot(Color.White, cx, cy, sz / 2f, al)
        drawBrand(brands[i], cx, cy, sz * .72f, al)
    }
}

// ------------------------------------------------------- capa de atrás (fondo)

private fun stageAlpha(s: LogoScene, ms: Float) =
    lgEio(lgClamp(minOf(ms / 300f, (s.ms - ms) / 400f)))

private fun DrawScope.drawBack(s: LogoScene, ms: Float, p: LogoPose, brands: List<Painter>) {
    val sa = stageAlpha(s, ms)

    // Brillo suave arriba a la izquierda: da volumen al fondo plano mientras
    // dura la escena y se funde, así que en reposo el logo es idéntico al icono.
    drawRect(
        Brush.radialGradient(
            listOf(Color.White.al(.2f * sa), Color.White.al(0f)),
            center = Offset(30f, 18f), radius = 85f
        ),
        Offset.Zero, Size(108f, 108f)
    )

    // Sombra de contacto: se encoge y se desvanece cuanto más alto está.
    if (s.shadow) {
        val h = maxOf(0f, -p.y)
        val k = maxOf(.5f, 1f - h / 50f)
        drawOval(
            Navy.al(.22f * k * sa),
            Offset(54f + p.x * .5f - 26f * k, 85f - 3.6f * k), Size(52f * k, 7.2f * k)
        )
    }

    when (s) {
        LogoScene.Servers -> {
            val tops = floatArrayOf(72f, 82f, 92f)
            val cols = arrayOf(CardFtp, CardDrive, Color.White)
            val lift = 5f * sin(PIF * lgPh(ms, 1500, 2200)) * (if (ms < 2300f) 1f else 0f)
            for (k in 0..2) {
                val f = lgPh(ms, 100 + k * 140, 620 + k * 140)
                val fo = lgPh(ms, 2700 + (2 - k) * 110, 3250 + (2 - k) * 110)
                var off = (1f - lgBack(f)) * 45f + lgEio(fo) * 45f
                if (k == 2) off -= lift
                val top = tops[k] + off
                fillRound(cols[k], 19f, top, 70f, 44f, 8f)
                fillRound(if (k == 2) CloudBlue else Ink, 26.5f, top + 4.6f, 5f, 5f, 2.5f,
                    if (k == 2) (if (ms > 1800f) 0f else .9f) else .55f)
                fillRound(Ink, 35f, top + 5.6f, 24f, 3f, 1.5f, .28f)
            }
            // Selección: la tarjeta S3 sube, suena un anillo y aparece el check.
            if (ms >= 1600f && ms < 2900f) {
                val off2 = if (ms > 2700f) lgEio(lgPh(ms, 2700, 3250)) * 45f else 0f
                val top = 92f - lift + off2
                val r = lgPh(ms, 1600, 2300)
                strokeDot(Mint, 28.5f, top + 7.1f, 3f + 14f * lgEout(r), 1.4f, .7f * (1f - r))
                drawCheck(28.5f, top + 7.1f, 3.8f, lgBack(lgPh(ms, 1600, 2000)),
                    if (ms > 2700f) 1f - lgEio(lgPh(ms, 2700, 3000)) else 1f)
            }
        }
        LogoScene.Speed -> {
            // Estelas de velocidad que corren hacia atrás.
            val env = lgEio(lgPh(ms, 260, 520)) * (1f - lgEio(lgPh(ms, 1800, 2100)))
            val ys = floatArrayOf(34f, 45f, 56f, 66f, 73f)
            val ls = floatArrayOf(18f, 12f, 22f, 14f, 18f)
            for (i in 0..4) {
                val q = ((ms / 520f) + i * .37f) % 1f
                val x = lgMix(112f, -24f, q)
                strokeLine(Color.White, x, ys[i], x + ls[i], ys[i], 2.4f, .6f * env * sin(PIF * q))
            }
        }
        LogoScene.Preload -> {
            // Paquetes de datos que la nube absorbe: se ven hasta que entran en su silueta.
            for (i in 0..5) {
                val t = 700 + i * 210
                val f = lgPh(ms, t - 520, t)
                if (f <= 0f || f >= 1f) continue
                val sx0 = if (i % 2 == 0) -8f else 116f
                val sy0 = 26f + ((i * 17) % 38)
                val e = lgEin(f)
                translate(lgMix(sx0, 54f, e), lgMix(sy0, 56f, e)) {
                    rotate(i * 37f * e, Offset.Zero) {
                        scale(lgMix(1f, .35f, e), pivot = Offset.Zero) {
                            fillRound(Color.White, -4.5f, -4.5f, 9f, 9f, 2.2f, .95f)
                            strokeLine(CloudBlue, -2f, 0f, 2f, 0f, 1.4f, .8f)
                        }
                    }
                }
            }
        }
        LogoScene.Root -> {
            // Halo menta cuando se concede el acceso.
            val glow = sin(PIF * lgPh(ms, 1550, 2300))
            if (glow > 0f) {
                drawRect(
                    Brush.radialGradient(
                        listOf(Mint.al(.55f * glow), Mint.al(0f)),
                        center = Offset(54f, 52f), radius = 56f
                    ),
                    Offset.Zero, Size(108f, 108f)
                )
            }
        }
        LogoScene.Orbit -> drawOrbit(ms, false, brands)
        LogoScene.Sync -> {
            val open = lgEout(lgPh(ms, 200, 650))
            val close = 1f - lgEio(lgPh(ms, 2300, 2700))
            val sweep = 100f * open * close
            if (sweep > 1f) {
                val rot = 360f * lgEio(lgPh(ms, 250, 2500))
                for (i in 0..1) {
                    val st = rot + 30f + i * 180f
                    strokeArc(Mint, 54f, 52f, 44f, 40f, st, sweep, 2.4f, .95f)
                    val th = (st + sweep) * PIF / 180f
                    val tx = 54f + 44f * cos(th)
                    val ty = 52f + 40f * sin(th)
                    val tgx = -sin(th)
                    val tgy = cos(th)
                    val rdx = cos(th)
                    val rdy = sin(th)
                    strokeLine(Mint, tx, ty, tx - tgx * 5.5f + rdx * 3.4f, ty - tgy * 5.5f + rdy * 3.4f, 2.4f, .95f)
                    strokeLine(Mint, tx, ty, tx - tgx * 5.5f - rdx * 3.4f, ty - tgy * 5.5f - rdy * 3.4f, 2.4f, .95f)
                }
            }
        }
        LogoScene.Wake -> {
            if (ms > 2350f && ms < 3000f) {
                val f = lgPh(ms, 2400, 3000)
                strokeDot(Color.White, 54f, 52f, lgMix(30f, 58f, lgEout(f)), 1.6f, .5f * (1f - f))
            }
        }
        else -> Unit
    }
}

// -------------------------------------------------------- capa de delante

private fun DrawScope.drawFront(s: LogoScene, ms: Float, brands: List<Painter>) {
    when (s) {
        LogoScene.Hop -> {
            // Polvo al aterrizar.
            val f = lgPh(ms, 700, 1150)
            if (f > 0f && f < 1f) {
                for (sd in intArrayOf(-1, 1)) {
                    fillDot(Color.White, 54f + sd * (18f + 16f * lgEout(f)), 83f - 4f * lgEout(f), 3f + 4f * f, .5f * (1f - f))
                }
            }
        }
        LogoScene.Mount -> {
            val appear = lgBack(lgPh(ms, 100, 620))
            val leave = lgEin(lgPh(ms, 2600, 3150))
            var top = lgMix(122f, 80f, appear) + 42f * leave
            // La carpeta se hunde un poco con cada archivo que recibe.
            for (i in 0..2) top += 1.6f * sin(PIF * lgPh(ms, 500 + i * 330 + 380, 500 + i * 330 + 700))
            drawFolderBack(top)
            // Los archivos salen de la nube y se pierden detrás del frente de la carpeta.
            for (i in 0..2) {
                val st = 500 + i * 330
                val f = lgPh(ms, st, st + 520)
                if (f <= 0f || f >= 1f) continue
                val xx = 54f + (i - 1) * 10f + sin(f * PIF) * (i - 1) * 3f
                val yy = lgMix(62f, 88f, lgEin(f))
                drawFile(xx, yy + (top - 80f), (i - 1) * 14f * (1f - f), lgMix(1.25f, .95f, f), lgClamp(f / .15f))
            }
            drawFolderFront(top)
            val r = lgPh(ms, 1950, 2550)
            if (r > 0f) strokeDot(Mint, 54f, top + 14f, lgMix(14f, 36f, lgEout(r)), 1.6f, .6f * (1f - r))
            drawCheck(74f, top + 3f, 7f, lgBack(lgPh(ms, 1950, 2350)), 1f - lgEin(lgPh(ms, 2600, 3000)))
        }
        LogoScene.Speed -> {
            // Velocímetro de la prueba de rendimiento.
            val pin = lgBack(lgPh(ms, 250, 650))
            val pout = 1f - lgEin(lgPh(ms, 2300, 2700))
            val sc = maxOf(0f, minOf(pin, pout))
            if (sc > 0f) {
                translate(88f, 26f) {
                    scale(sc, pivot = Offset.Zero) {
                        fillDot(Navy, 0f, 1.4f, 15.5f, .25f)
                        fillDot(Terminal, 0f, 0f, 15f)
                        strokeDot(Ice, 0f, 0f, 14.4f, .8f, .35f)
                        var v = lgEout(lgPh(ms, 300, 1500)) * .95f
                        if (ms > 1500f && ms < 2000f) v += sin(ms * .03f) * .018f
                        if (ms >= 2000f) v = .95f
                        strokeArc(Ice, 0f, 0f, 10f, 10f, 150f, 240f, 3f, .3f)
                        if (v > 0f) strokeArc(Mint, 0f, 0f, 10f, 10f, 150f, 240f * v, 3f, 1f)
                        val na = (150f + 240f * v) * PIF / 180f
                        strokeLine(Color.White, 0f, 0f, 8f * cos(na), 8f * sin(na), 1.7f)
                        fillDot(Color.White, 0f, 0f, 2f)
                        val rf = lgPh(ms, 1450, 1900)
                        if (rf > 0f && rf < 1f) strokeDot(Mint, 0f, 0f, lgMix(15f, 27f, lgEout(rf)), 1.4f, .7f * (1f - rf))
                    }
                }
            }
        }
        LogoScene.Preload -> {
            // Barra de precarga que se completa y se convierte en check.
            val al = minOf(lgEout(lgPh(ms, 350, 800)), 1f - lgEio(lgPh(ms, 2000, 2250)))
            if (al > 0f) {
                val prog = lgEio(lgPh(ms, 700, 2000))
                fillRound(Navy, 27f, 87f, 54f, 6.5f, 3.25f, .38f * al)
                if (prog > 0f) fillRound(Mint, 27f, 87f, maxOf(6.5f, 54f * prog), 6.5f, 3.25f, al)
                for (k in 1..5) strokeLine(Navy, 27f + 9f * k, 87.5f, 27f + 9f * k, 92.5f, 1f, .35f * al)
            }
            drawCheck(54f, 90f, 7.5f, lgBack(lgPh(ms, 2050, 2400)), 1f - lgEin(lgPh(ms, 2700, 3000)))
            val r = lgPh(ms, 2050, 2650)
            if (r > 0f) strokeDot(Mint, 54f, 90f, lgMix(8f, 34f, lgEout(r)), 1.6f, .6f * (1f - r))
        }
        LogoScene.Root -> {
            // Terminal con prompt "#" y cursor; al terminar aparece el escudo.
            val slide = if (ms < 2600f) lgBack(lgPh(ms, 100, 650)) else 1f
            val out = lgEin(lgPh(ms, 2600, 3000))
            val cx = lgMix(122f, 28f, slide) + (122f - 28f) * out
            if (cx < 120f) {
                translate(cx, 80f) {
                    fillRound(Navy, 0f, 1.5f, 52f, 28f, 6.5f, .25f)
                    fillRound(Terminal, 0f, 0f, 52f, 28f, 6.5f)
                    fillDot(Mint, 6f, 5f, 1.4f)
                    fillDot(Gold, 10.5f, 5f, 1.4f)
                    fillDot(Ice, 15f, 5f, 1.4f, .7f)
                    strokeLine(Ice, 0f, 9.5f, 52f, 9.5f, .7f, .15f)
                    val t = lgPh(ms, 700, 1000)
                    val hx = 7f
                    val hy = 19f
                    strokeLine(Mint, hx + 1f, hy - 4f, hx, hy + 4f, 1.3f, t)
                    strokeLine(Mint, hx + 4f, hy - 4f, hx + 3f, hy + 4f, 1.3f, t)
                    strokeLine(Mint, hx - 1f, hy - 1.3f, hx + 6f, hy - 1.3f, 1.3f, t)
                    strokeLine(Mint, hx - 1.6f, hy + 1.7f, hx + 5.4f, hy + 1.7f, 1.3f, t)
                    val w1 = 22f * lgEout(lgPh(ms, 1000, 1500))
                    val w2 = 14f * lgEout(lgPh(ms, 1350, 1700))
                    if (w1 > 0f) fillRound(Ice, 17f, hy - 3.2f, w1, 2.6f, 1.3f, .8f)
                    if (w2 > 0f) fillRound(Ice, 17f, hy + 1.4f, w2, 2.6f, 1.3f, .5f)
                    if (ms > 650f && ((ms / 350f).toInt() % 2 == 0)) {
                        fillRound(Mint, 17f + (if (ms < 1350f) w1 else w2) + 2.5f, hy - 4f, 4.5f, 9f, 1f, .95f)
                    }
                }
                drawShield(cx + 43f, 79f, lgBack(lgPh(ms, 1500, 1900)), 1f - lgEin(lgPh(ms, 2600, 3000)))
            }
        }
        LogoScene.Orbit -> drawOrbit(ms, true, brands)
        LogoScene.Sync -> {
            drawCheck(88f, 86f, 8f, lgBack(lgPh(ms, 2400, 2800)), 1f - lgEio(lgPh(ms, 2900, 3150)))
        }
        LogoScene.Wake -> {
            // zzz mientras duerme.
            if (ms < 2350f) {
                for (i in 0..2) {
                    val f = lgPh(ms, 200 + i * 450, 1300 + i * 450)
                    if (f <= 0f || f >= 1f) continue
                    drawZ(64f + i * 6f + 8f * f, 34f - i * 5f - 14f * f, 5f + i * 2.3f, sin(PIF * f))
                }
            }
            // Interruptor de autostart: se activa y la nube despierta.
            val sc = maxOf(0f, minOf(lgBack(lgPh(ms, 1500, 1900)), 1f - lgEio(lgPh(ms, 3000, 3500))))
            if (sc > 0f) {
                translate(54f, 94f) {
                    scale(sc, pivot = Offset.Zero) {
                        val k = lgBack(lgPh(ms, 2000, 2400))
                        fillRound(Navy, -15f, -8f, 30f, 16f, 8f, .4f * (1f - k))
                        fillRound(Mint, -15f, -8f, 30f, 16f, 8f, minOf(k, 1f))
                        fillDot(Color.White, lgMix(-7f, 7f, k), 0f, 6f)
                    }
                }
            }
            // Destello de rayos al despertar.
            if (ms > 2400f && ms < 3100f) {
                val f = lgPh(ms, 2450, 3100)
                for (i in 0..7) {
                    val a = (-90f + (i - 3.5f) * 22f) * PIF / 180f
                    val r0 = lgMix(40f, 46f, lgEout(f))
                    val r1 = lgMix(44f, 56f, lgEout(f))
                    strokeLine(
                        Color.White,
                        54f + r0 * cos(a), 50f + r0 * sin(a),
                        54f + r1 * cos(a), 50f + r1 * sin(a),
                        1.8f, .9f * sin(PIF * f)
                    )
                }
            }
        }
        else -> Unit
    }
}

// ------------------------------------------------------------------ composable

@Composable
fun AnimatedLogo() {
    val context = LocalContext.current
    val deck = remember { SceneDeck() }
    val clock = remember { Animatable(0f) } // milisegundos dentro de la escena
    var scene by remember { mutableStateOf<LogoScene?>(null) }
    var replay by remember { mutableIntStateOf(0) }
    val interaction = remember { MutableInteractionSource() }
    val brands: List<Painter> = listOf(
        rememberVectorPainter(AppIcons.AwsLogo),
        rememberVectorPainter(AppIcons.CloudflareLogo),
        rememberVectorPainter(AppIcons.OracleLogo),
        rememberVectorPainter(AppIcons.DriveLogo)
    )

    LaunchedEffect(replay) {
        // Un toque (o salir de la pantalla) cancela esto; el siguiente efecto
        // siempre arranca desde la pose de reposo, sin dejar objetos a medias.
        scene = null
        clock.snapTo(0f)
        delay(if (replay == 0) Random.nextLong(1200L, 2200L) else 0L)
        while (isActive) {
            // Respeta el interruptor de animaciones de Android en cada vuelta.
            val enabled = Settings.Global.getFloat(
                context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            ) > 0f
            if (enabled) {
                val next = deck.next()
                clock.snapTo(0f)
                scene = next
                clock.animateTo(next.ms.toFloat(), tween(next.ms, easing = LinearEasing))
                scene = null
                clock.snapTo(0f)
            }
            delay(Random.nextLong(4500L, 9000L))
        }
    }

    Box(
        Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(CloudBlue)
            .semantics { contentDescription = "Logo de Nubind" }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = "Animar la nube"
            ) { replay++ },
        contentAlignment = Alignment.Center
    ) {
        // Detrás de la nube: brillo, sombra, estelas, paquetes, mazo, órbita trasera...
        Canvas(Modifier.matchParentSize()) {
            val s = scene
            if (s != null) {
                val ms = clock.value
                scale(size.width / 108f, size.height / 108f, pivot = Offset.Zero) {
                    drawBack(s, ms, logoPose(s, ms), brands)
                }
            }
        }
        Image(
            AppIcons.Logo,
            contentDescription = null,
            modifier = Modifier.size(96.dp).graphicsLayer {
                val s = scene
                val p = if (s != null) logoPose(s, clock.value) else RestPose
                val unit = size.width / 108f
                translationX = p.x * unit
                translationY = p.y * unit
                scaleX = p.sx
                scaleY = p.sy
                rotationZ = p.tilt
                // Pivote en la base de la nube, para que el squash apoye en el "suelo".
                transformOrigin = TransformOrigin(.5f, .72f)
            }
        )
        // Delante de la nube: carpeta, velocímetro, terminal, órbita delantera...
        Canvas(Modifier.matchParentSize()) {
            val s = scene
            if (s != null) {
                val ms = clock.value
                scale(size.width / 108f, size.height / 108f, pivot = Offset.Zero) {
                    drawFront(s, ms, brands)
                }
            }
        }
    }
}
