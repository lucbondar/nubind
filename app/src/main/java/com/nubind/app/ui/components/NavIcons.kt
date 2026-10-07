package com.nubind.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Iconos propios de las cuatro secciones (Inicio, Servidores, Logs y Acerca de), diseñados a mano
 * en estilo expressive: formas geométricas gruesas y redondeadas en un viewport de 24, con un
 * trazo de [STROKE_WIDTH]. Hay UN solo diseño por icono ([IconGeo]) y de él salen las dos formas
 * de usarlo, para que se vean igual en toda la app:
 * - [NavIcons]: `ImageVector` sólido (estado lleno) para `Icon(tint = ...)`, donde la app nombra
 *   la sección (Qué hace, avisos, selector de carpetas, Mostrar Logs, estado vacío de Logs);
 * - [NavIconArts] + [NavFillIcon]: la píldora de navegación, donde además hay estado vacío
 *   (solo contorno) y el icono se rellena y se vacía con una animación.
 */
private const val STROKE_WIDTH = 1.9f

/** Lápiz mínimo que implementan tanto `Path` (píldora) como `PathBuilder` (`ImageVector`). */
internal interface Pen {
    fun moveTo(x: Float, y: Float)
    fun lineTo(x: Float, y: Float)
    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float)
    fun close()
}

private class PathPen(val p: Path) : Pen {
    override fun moveTo(x: Float, y: Float) = p.moveTo(x, y)
    override fun lineTo(x: Float, y: Float) = p.lineTo(x, y)
    override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) =
        p.cubicTo(x1, y1, x2, y2, x3, y3)
    override fun close() = p.close()
}

private class VectorPen(val b: PathBuilder) : Pen {
    override fun moveTo(x: Float, y: Float) = b.moveTo(x, y)
    override fun lineTo(x: Float, y: Float) = b.lineTo(x, y)
    override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) =
        b.curveTo(x1, y1, x2, y2, x3, y3)
    override fun close() = b.close()
}

private const val K = 0.5523f // control de Bézier de un cuarto de círculo

private fun Pen.circle(cx: Float, cy: Float, r: Float) {
    val k = K * r
    moveTo(cx + r, cy)
    cubicTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
    cubicTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
    cubicTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
    cubicTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
    close()
}

/** Rectángulo con esquinas de radio [rad] (con `rad` = mitad del lado menor es una píldora). */
private fun Pen.roundRect(l: Float, t: Float, r: Float, b: Float, rad: Float) {
    val k = K * rad
    moveTo(l + rad, t)
    lineTo(r - rad, t)
    cubicTo(r - rad + k, t, r, t + rad - k, r, t + rad)
    lineTo(r, b - rad)
    cubicTo(r, b - rad + k, r - rad + k, b, r - rad, b)
    lineTo(l + rad, b)
    cubicTo(l + rad - k, b, l, b - rad + k, l, b - rad)
    lineTo(l, t + rad)
    cubicTo(l, t + rad - k, l + rad - k, t, l + rad, t)
    close()
}

/** Puerta de la casa: arco de 4 de ancho que arranca y termina en el suelo (y = 19,4). */
private fun Pen.door(closed: Boolean) {
    moveTo(10f, 19.4f)
    lineTo(10f, 15.8f)
    cubicTo(10f, 14.6954f, 10.8954f, 13.8f, 12f, 13.8f)
    cubicTo(13.1046f, 13.8f, 14f, 14.6954f, 14f, 15.8f)
    lineTo(14f, 19.4f)
    if (closed) close()
}

private fun Pen.houseBody() {
    moveTo(12f, 4.6f); lineTo(20f, 11.8f); lineTo(20f, 19.4f); lineTo(4f, 19.4f); lineTo(4f, 11.8f); close()
}

private fun Pen.people() {
    // Persona de delante: cabeza y hombros.
    circle(9f, 7.9f, 3.3f)
    moveTo(3f, 19.4f); lineTo(3f, 17.8f)
    cubicTo(3f, 15.6f, 5.6f, 14f, 9f, 14f)
    cubicTo(12.4f, 14f, 15f, 15.6f, 15f, 17.8f)
    lineTo(15f, 19.4f); close()
    // Persona de atrás, a la derecha.
    circle(17.1f, 8.4f, 2.4f)
    moveTo(18f, 19.4f); lineTo(18f, 14.4f)
    cubicTo(19.8f, 14.4f, 21f, 15.6f, 21f, 17.8f)
    lineTo(21f, 19.4f); close()
}

/** Tres filas de punto + barra; cada forma mide 5 de alto por fuera para que el vacío tenga hueco. */
private fun Pen.list() {
    for (y in floatArrayOf(5f, 12f, 19f)) {
        circle(4.7f, y, 1.55f)
        roundRect(10.15f, y - 1.55f, 21.05f, y + 1.55f, 1.55f)
    }
}

private fun Pen.infoRing() = circle(12f, 12f, 9f)

private fun Pen.infoMarks() {
    circle(12f, 7.9f, 1.2f)
    roundRect(10.9f, 10.7f, 13.1f, 16.9f, 1.1f)
}

/**
 * Diseño de un icono, en dos estados:
 * - **vacío**: [strokes] con trazo de [STROKE_WIDTH] (puntas y uniones redondas) y, si hay,
 *   [hollowFills] rellenos (la «i» de Acerca de);
 * - **lleno**: [solid] relleno EvenOdd (sus huecos son la puerta de la casa y la «i») más un
 *   trazo de [solidStroke] del mismo grosor, que redondea las esquinas y deja el mismo tamaño
 *   exterior que el estado vacío.
 */
internal class IconGeo(
    val strokes: Pen.() -> Unit,
    val solid: Pen.() -> Unit,
    val solidStroke: Pen.() -> Unit,
    val hollowFills: (Pen.() -> Unit)? = null
)

private val HomeGeo = IconGeo(
    strokes = { houseBody(); door(closed = false) },
    solid = { houseBody(); door(closed = true) },
    solidStroke = { houseBody() }
)
private val ServersGeo = IconGeo(strokes = { people() }, solid = { people() }, solidStroke = { people() })
private val LogsGeo = IconGeo(strokes = { list() }, solid = { list() }, solidStroke = { list() })
private val AboutGeo = IconGeo(
    strokes = { infoRing() },
    solid = { infoRing(); infoMarks() },
    solidStroke = { infoRing() },
    hollowFills = { infoMarks() }
)

private fun navVector(name: String, geo: IconGeo): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    )
        .path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) { geo.solid(VectorPen(this)) }
        .path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = STROKE_WIDTH,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) { geo.solidStroke(VectorPen(this)) }
        .build()

/** Iconos sólidos para `Icon(tint = ...)` fuera de la píldora. */
object NavIcons {
    /** Casa con puerta: Inicio. */
    val Home: ImageVector by lazy { navVector("NavHome", HomeGeo) }

    /** Dos personas: Servidores. */
    val Servers: ImageVector by lazy { navVector("NavServers", ServersGeo) }

    /** Lista de tres puntos y tres barras: Logs. */
    val Logs: ImageVector by lazy { navVector("NavLogs", LogsGeo) }

    /** Aro con «i»: Acerca de y avisos informativos. */
    val About: ImageVector by lazy { navVector("NavAbout", AboutGeo) }
}

/** Dibujo "rellenable" de un icono de la píldora (ver [NavFillIcon]). */
class NavIconArt internal constructor(geo: IconGeo) {
    internal val strokes: Path = geo.strokes.toPath(false)
    internal val solid: Path = geo.solid.toPath(true)
    internal val solidStroke: Path = geo.solidStroke.toPath(false)
    internal val hollowFills: Path? = geo.hollowFills?.toPath(true)
}

private fun (Pen.() -> Unit).toPath(evenOdd: Boolean): Path {
    val p = Path()
    if (evenOdd) p.fillType = PathFillType.EvenOdd
    this(PathPen(p))
    return p
}

object NavIconArts {
    val Home = NavIconArt(HomeGeo)
    val Servers = NavIconArt(ServersGeo)
    val Logs = NavIconArt(LogsGeo)
    val About = NavIconArt(AboutGeo)
}

private val ArtStroke = Stroke(width = STROKE_WIDTH, cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun DrawScope.drawHollow(art: NavIconArt, tint: Color) {
    drawPath(art.strokes, tint, style = ArtStroke)
    art.hollowFills?.let { drawPath(it, tint) }
}

private fun DrawScope.drawSolid(art: NavIconArt, tint: Color) {
    drawPath(art.solid, tint)
    drawPath(art.solidStroke, tint, style = ArtStroke)
}

/**
 * Icono de la píldora que se llena y se vacía. [fill] va de 0 (solo contorno) a 1 (sólido): el
 * relleno sube desde abajo y al vaciarse baja, con el borde recto entre las dos mitades (arriba
 * se dibuja el vacío, abajo el lleno, sin superponerse: superpuestos, la «i» de Acerca de se
 * perdería sobre el disco). Es un [State] y se lee solo al dibujar, así la animación no recompone.
 */
@Composable
fun NavFillIcon(
    art: NavIconArt,
    tint: Color,
    fill: State<Float>,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val semantic = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else Modifier
    Canvas(modifier.then(semantic)) {
        val s = size.minDimension / 24f
        val level = fill.value.coerceIn(0f, 1f)
        withTransform({ scale(s, s, pivot = Offset.Zero) }) {
            val edge = 24f * (1f - level)
            if (level < 1f) clipRect(0f, 0f, 24f, edge) { drawHollow(art, tint) }
            if (level > 0f) clipRect(0f, edge, 24f, 24f) { drawSolid(art, tint) }
        }
    }
}
