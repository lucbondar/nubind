package com.nubind.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import android.content.Intent
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.magnifier
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.DpSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import com.nubind.app.ui.components.CookieBadge
import com.nubind.app.ui.theme.syncAmberPalette
import com.nubind.app.ui.theme.updateGreenPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import com.nubind.app.BindViewModel
import com.nubind.app.ui.components.ScreenContainer
import kotlinx.coroutines.launch
import com.nubind.app.R
import com.nubind.app.Strings

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun LogsScreen(vm: BindViewModel) {
    LaunchedEffect(Unit) { vm.refreshLogs() }

    // 0 = texto normal, 1 = texto ya "tirado a la papelera" (invisible).
    val trash = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    val spin = remember { Animatable(0f) }
    val context = LocalContext.current

    fun shareLogs() {
        val text = vm.logs
        if (text.isBlank()) return
        scope.launch {
            val uri = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "logs").apply { mkdirs() }
                val f = File(dir, "nubind-log.txt")
                f.writeText(text)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, Strings.get(R.string.log_de_nubind))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, Strings.get(R.string.compartir_log)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun discardLogs() {
        if (vm.logs.isBlank() || trash.isRunning) return
        scope.launch {
            // Sale rápido hacia la papelera (arriba a la derecha): se encoge y se desvanece.
            trash.animateTo(1f, tween(durationMillis = 260, easing = FastOutLinearInEasing))
            vm.clearLogs()
            // Vuelve a aparecer ya vacío ("Sin logs todavía.") con un fundido corto.
            trash.animateTo(0f, tween(durationMillis = 160, easing = LinearOutSlowInEasing))
        }
    }

    // ---- Estado de la lista y de los botones de desplazamiento rápido ----
    val entries = remember(vm.logs) { parseLogEntries(vm.logs) }
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    // 2 cm físicos (según los dpi reales de la pantalla) entre el dedo y la lupa.
    val lensGapPx = remember { context.resources.displayMetrics.ydpi / 2.54f * 2f }
    // Modo desplazamiento rápido: se activa al sostener el dedo quieto ~0,3 s sobre el log.
    // Después el log sigue el deslizamiento del dedo: arriba = sube, abajo = baja. El recorrido se
    // escala para que deslizar por toda el área táctil recorra TODO el log (no solo lo visible); la
    // velocidad del log es la del dedo por ese factor, así que la marca el ritmo del dedo.
    var fast by remember { mutableStateOf(false) }
    var finger by remember { mutableStateOf(Offset.Unspecified) }
    // Dirección del último movimiento (-1 sube, 1 baja, 0 quieto) solo para los indicadores.
    var dir by remember { mutableStateOf(0) }
    var moveTick by remember { mutableStateOf(0) }
    // Alto del área táctil (px): recorrer todo ese alto con el dedo equivale a recorrer todo el log.
    val areaHeight = remember { floatArrayOf(0f) }
    val hasEntries = entries.isNotEmpty()
    val logColors = rememberLogColors()
    LaunchedEffect(moveTick) {
        delay(160)
        dir = 0
    }
    // Barra de desplazamiento: aparece al desplazar (o al entrar a la pantalla) y se va 1,3 s después
    // de parar; se puede arrastrar. `barDragging` = el dedo está sobre ella.
    var barDragging by remember { mutableStateOf(false) }
    var barShown by remember { mutableStateOf(true) }
    val scrolling = listState.isScrollInProgress
    LaunchedEffect(scrolling, fast, barDragging) {
        if (scrolling || fast || barDragging) {
            barShown = true
        } else {
            delay(1300)
            barShown = false
        }
    }

    ScreenContainer(
        title = "Logs",
        scroll = false,
        refreshing = vm.refreshing,
        onRefresh = { vm.pullRefresh() },
        actions = {
            FilledTonalIconButton(onClick = { shareLogs() }, enabled = vm.logs.isNotBlank()) {
                Icon(Icons.Default.Share, contentDescription = Strings.get(R.string.compartir_registro))
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton(onClick = { discardLogs() }, enabled = vm.logs.isNotBlank()) {
                Icon(Icons.Default.Delete, contentDescription = Strings.get(R.string.borrar_registro))
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton(onClick = {
                vm.refreshLogs()
                scope.launch {
                    spin.snapTo(spin.value % 360f)
                    spin.animateTo(spin.value + 360f, tween(600, easing = FastOutSlowInEasing))
                }
            }) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = Strings.get(R.string.actualizar),
                    modifier = Modifier.graphicsLayer { rotationZ = spin.value }
                )
            }
        }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged { areaHeight[0] = it.height.toFloat() }
                .pointerInput(hasEntries) {
                    // Pasada Initial. Los primeros 0,3 s solo observa (el desplazamiento normal sigue
                    // igual; si el dedo se mueve más que el umbral, se suelta). Si el dedo sigue quieto,
                    // entra el modo rápido y consume el gesto para que la lista no se mueva con el dedo.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        // Sobre la barra de desplazamiento visible manda ella (arrastre propio).
                        if (barShown && down.position.x >= size.width - ScrollbarTouchWidth.toPx()) return@awaitEachGesture
                        // Sin logs no hay nada que recorrer: no se consume nada y el deslizar-para-actualizar sigue igual.
                        if (!hasEntries) return@awaitEachGesture
                        val held = withTimeoutOrNull(FAST_HOLD_MS) {
                            while (true) {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                val c = ev.changes.firstOrNull { it.id == down.id }
                                if (c == null || !c.pressed ||
                                    (c.position - down.position).getDistance() > viewConfiguration.touchSlop
                                ) break
                            }
                        } == null
                        if (!held) return@awaitEachGesture
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        finger = down.position
                        fast = true
                        try {
                            while (true) {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                c.consume()
                                if (!c.pressed) break
                                finger = c.position
                                val dy = c.position.y - c.previousPosition.y
                                if (dy != 0f) {
                                    // Largo total estimado del log = tamaño medio de lo visible * nº de líneas.
                                    val info = listState.layoutInfo
                                    val vis = info.visibleItemsInfo
                                    val reach = if (vis.isEmpty() || areaHeight[0] <= 0f) 1f else {
                                        val avg = vis.sumOf { it.size }.toFloat() / vis.size + 4.dp.toPx()
                                        val total = avg * info.totalItemsCount
                                        (total / areaHeight[0] * FAST_REACH).coerceAtLeast(1f)
                                    }
                                    listState.dispatchRawDelta(dy * reach)
                                    dir = if (dy < 0f) -1 else 1
                                    moveTick++
                                }
                            }
                        } finally {
                            fast = false
                            finger = Offset.Unspecified
                            dir = 0
                        }
                    }
                }
                // Lupa real del sistema, 2 cm por encima del dedo. Amplía lo que hay donde está la
                // propia lupa (fuente = su centro), no lo que tapa el dedo. Sin dedo en modo rápido
                // (Unspecified) no se muestra.
                .magnifier(
                    sourceCenter = {
                        if (finger == Offset.Unspecified) Offset.Unspecified
                        else Offset(finger.x, (finger.y - lensGapPx).coerceAtLeast(0f))
                    },
                    magnifierCenter = {
                        if (finger == Offset.Unspecified) Offset.Unspecified
                        else Offset(finger.x, (finger.y - lensGapPx).coerceAtLeast(0f))
                    },
                    zoom = 1.8f,
                    size = DpSize(96.dp, 96.dp),
                    cornerRadius = 48.dp,
                    elevation = 8.dp
                )
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxSize()
            ) {
                Column(Modifier.fillMaxSize()) {
                    if (entries.isNotEmpty()) {
                        LogSummary(entries, Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp))
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .graphicsLayer {
                                // Se lee dentro del bloque: anima sin recomponer la pantalla.
                                val t = trash.value
                                alpha = 1f - t
                                val scale = 1f - 0.2f * t
                                scaleX = scale
                                scaleY = scale
                                translationY = -32.dp.toPx() * t
                                transformOrigin = TransformOrigin(1f, 0f)
                            }
                    ) {
                        if (entries.isEmpty()) {
                            // Desplazable (aunque no haya nada que mover) para que el gesto de
                            // deslizar hacia abajo llegue a ScreenContainer y actualice sin logs.
                            Column(
                                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                LogEmptyState()
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 12.dp, end = 20.dp, top = 6.dp, bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                itemsIndexed(entries) { _, e -> LogRow(e, logColors) }
                            }
                            LogScrollbar(
                                state = listState,
                                shown = barShown,
                                emphasized = barDragging || fast,
                                onDragging = { barDragging = it }
                            )
                        }
                    }
                }
            }

            // Indicadores (no se tocan): aparecen con resorte en modo rápido. El de la dirección
            // activa deja su hueco (aro) porque es la lupa la que sigue al dedo.
            Column(
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.End
            ) {
                JumpIndicator(
                    visible = fast && entries.isNotEmpty(),
                    active = dir == -1,
                    icon = Icons.Default.KeyboardArrowUp,
                    description = Strings.get(R.string.log_ir_al_inicio)
                )
                JumpIndicator(
                    visible = fast && entries.isNotEmpty(),
                    active = dir == 1,
                    icon = Icons.Default.KeyboardArrowDown,
                    description = Strings.get(R.string.log_ir_al_final)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Barra de desplazamiento expressive
// ---------------------------------------------------------------------------

private val ScrollbarTouchWidth = 28.dp
private val ScrollbarMinThumb = 48.dp
private val ScrollbarGap = 4.dp // separación entre líneas (la misma de LazyColumn)

/** Pulgar: posición y alto en px dentro de la pista, y los datos para convertir arrastre en desplazamiento. */
private class ThumbGeom(val top: Float, val height: Float, val travel: Float, val maxScroll: Float)

/**
 * Estima el pulgar a partir de lo visible: el largo total es el tamaño medio de las líneas visibles por el
 * número de líneas (como el modo rápido). Devuelve null si todo cabe en pantalla.
 */
private fun LazyListState.thumbGeom(trackH: Float, minThumbPx: Float, gapPx: Float): ThumbGeom? {
    val info = layoutInfo
    val vis = info.visibleItemsInfo
    val total = info.totalItemsCount
    if (vis.isEmpty() || total == 0 || trackH <= 0f) return null
    val avg = vis.sumOf { it.size }.toFloat() / vis.size + gapPx
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    val content = avg * total
    if (viewport <= 0f || content <= viewport) return null
    val h = (viewport / content * trackH).coerceIn(minThumbPx.coerceAtMost(trackH), trackH)
    val travel = trackH - h
    val maxScroll = content - viewport
    val firstSize = vis.first().size + gapPx
    val pos = (firstVisibleItemIndex + firstVisibleItemScrollOffset / firstSize) * avg
    var progress = (pos / maxScroll).coerceIn(0f, 1f)
    // En los extremos el pulgar llega siempre al borde, aunque la estimación se quede corta.
    if (!canScrollForward) progress = 1f else if (!canScrollBackward) progress = 0f
    return ThumbGeom(progress * travel, h, travel, maxScroll)
}

/**
 * Barra de desplazamiento del log: pulgar en píldora que aparece con resorte al desplazar, se ensancha
 * (con rebote) y se tiñe de color primario al sujetarlo, y muestra una burbuja "línea / total" a su lado.
 * Arrastrar el pulgar recorre el log entero. Solo recibe toques mientras está visible.
 */
@Composable
private fun LogScrollbar(
    state: LazyListState,
    shown: Boolean,
    emphasized: Boolean,
    onDragging: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val minThumbPx = with(density) { ScrollbarMinThumb.toPx() }
    val gapPx = with(density) { ScrollbarGap.toPx() }

    val appear by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "sbAppear"
    )
    val emph by animateFloatAsState(
        targetValue = if (emphasized) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "sbEmph"
    )
    val thumbWidth by animateDpAsState(
        targetValue = if (emphasized) 14.dp else if (shown) 6.dp else 3.dp,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "sbWidth"
    )
    val thumbColor = scheme.primary
    val trackColor = scheme.onSurface
    // La burbuja solo lee la geometría de la lista mientras está visible (si no, cada fotograma de
    // desplazamiento volvería a colocarla); al ocultarse conserva su última posición.
    val bubbleActive by rememberUpdatedState(emphasized)
    val bubbleY = remember { floatArrayOf(0f) }

    Box(modifier.fillMaxSize()) {
        // Burbuja con la posición, a la altura del pulgar y a su izquierda.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(end = 34.dp)
                .fillMaxHeight()
                .layout { measurable, constraints ->
                    val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    val maxH = constraints.maxHeight
                    layout(p.width, maxH) {
                        if (bubbleActive) {
                            val g = state.thumbGeom(maxH.toFloat(), minThumbPx, gapPx)
                            val cy = if (g == null) 0f else g.top + g.height / 2f
                            bubbleY[0] = (cy - p.height / 2f).coerceIn(0f, (maxH - p.height).toFloat().coerceAtLeast(0f))
                        }
                        p.place(0, bubbleY[0].roundToInt())
                    }
                }
        ) {
            AnimatedVisibility(
                visible = emphasized,
                enter = scaleIn(
                    animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
                    transformOrigin = TransformOrigin(1f, 0.5f)
                ) + fadeIn(),
                exit = scaleOut(
                    animationSpec = spring(stiffness = Spring.StiffnessMedium),
                    transformOrigin = TransformOrigin(1f, 0.5f)
                ) + fadeOut()
            ) {
                Surface(
                    color = scheme.primaryContainer,
                    shape = RoundedCornerShape(50),
                    shadowElevation = 4.dp
                ) {
                    Text(
                        text = "${state.firstVisibleItemIndex + 1} / ${state.layoutInfo.totalItemsCount}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }
        }

        // Franja táctil + dibujo de pista y pulgar (se leen en la fase de dibujo: sin recomponer).
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(ScrollbarTouchWidth)
                .drawBehind {
                    if (appear <= 0.01f) return@drawBehind
                    val g = state.thumbGeom(size.height, minThumbPx, gapPx) ?: return@drawBehind
                    val cx = size.width - 10.dp.toPx()
                    val w = thumbWidth.toPx().coerceAtLeast(1f)
                    if (emph > 0.01f) {
                        val tw = w + 6.dp.toPx()
                        drawRoundRect(
                            color = trackColor.copy(alpha = (0.10f * emph * appear).coerceIn(0f, 1f)),
                            topLeft = Offset(cx - tw / 2f, 0f),
                            size = Size(tw, size.height),
                            cornerRadius = CornerRadius(tw / 2f)
                        )
                    }
                    drawRoundRect(
                        color = thumbColor.copy(alpha = (appear * (0.55f + 0.45f * emph)).coerceIn(0f, 1f)),
                        topLeft = Offset(cx - w / 2f, g.top),
                        size = Size(w, g.height),
                        cornerRadius = CornerRadius(w / 2f)
                    )
                }
                .then(
                    if (shown) {
                        Modifier.pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragStart = {
                                    onDragging(true)
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                },
                                onDragEnd = { onDragging(false) },
                                onDragCancel = { onDragging(false) },
                                onVerticalDrag = { change, dy ->
                                    change.consume()
                                    val g = state.thumbGeom(size.height.toFloat(), minThumbPx, gapPx)
                                    if (g != null && g.travel > 0f) {
                                        // Dedo hacia abajo = hacia el final, como en el modo rápido.
                                        state.dispatchRawDelta(dy * g.maxScroll / g.travel)
                                    }
                                }
                            )
                        }
                    } else Modifier
                )
        )
    }
}

// Modo rápido: espera para activarlo y margen extra sobre el largo estimado del log (las líneas
// no miden todas lo mismo) para que un deslizamiento completo llegue de verdad al otro extremo.
private const val FAST_HOLD_MS = 300L
private const val FAST_REACH = 1.15f

// ---------------------------------------------------------------------------
// Modelo y piezas del log
// ---------------------------------------------------------------------------

private enum class LogLevel { INFO, OK, WARN, ERROR }

@Immutable
private class LogEntry(val time: String?, val tag: String?, val message: String, val level: LogLevel)

private val ModuleLine = Regex("""^\w{3} \w{3}\s+\d+ (\d{2}:\d{2}:\d{2}) [-+]?\w+ \d{4}: (.*)$""")
private val RcloneLine = Regex("""^\d{4}/\d{2}/\d{2} (\d{2}:\d{2}:\d{2}) (\w+)\s*: (.*)$""")

private fun classify(text: String, rcloneLevel: String?): LogLevel {
    val l = text.lowercase()
    return when {
        rcloneLevel == "ERROR" || rcloneLevel == "CRITICAL" -> LogLevel.ERROR
        "falló" in l || "fallo" in l || "error" in l || "failed" in l -> LogLevel.ERROR
        rcloneLevel == "NOTICE" || rcloneLevel == "WARNING" || "advertencia" in l || "timeout" in l -> LogLevel.WARN
        "correctamente" in l || "terminada" in l || "iniciado" in l || "listo" in l -> LogLevel.OK
        else -> LogLevel.INFO
    }
}

private fun parseLogEntries(text: String): List<LogEntry> =
    text.lineSequence().filter { it.isNotBlank() }.map { raw ->
        val line = raw.trimEnd()
        ModuleLine.find(line)?.let { m ->
            val msg = m.groupValues[2]
            LogEntry(m.groupValues[1], null, msg, classify(msg, null))
        } ?: RcloneLine.find(line)?.let { m ->
            val msg = m.groupValues[3]
            LogEntry(m.groupValues[1], m.groupValues[2], msg, classify(msg, m.groupValues[2]))
        } ?: LogEntry(null, null, line, classify(line, null))
    }.toList()

/** Colores por severidad, calculados una sola vez para toda la lista (no por fila). */
@Immutable
private class LogColors(val error: Color, val warn: Color, val ok: Color, val info: Color) {
    fun of(level: LogLevel): Color = when (level) {
        LogLevel.ERROR -> error
        LogLevel.WARN -> warn
        LogLevel.OK -> ok
        LogLevel.INFO -> info
    }
}

@Composable
private fun rememberLogColors(): LogColors {
    val scheme = MaterialTheme.colorScheme
    val amber = syncAmberPalette().accent
    val green = updateGreenPalette().accent
    return remember(scheme.error, scheme.outline, amber, green) {
        LogColors(error = scheme.error, warn = amber, ok = green, info = scheme.outline)
    }
}

private val LogRowShape = RoundedCornerShape(14.dp)

/**
 * Una línea: barra de color por severidad, hora tenue y mensaje en monoespaciada.
 * Pensada para desplazarse fluido: sin medición intrínseca (la barra se dibuja con `drawBehind` y
 * ocupa el alto de la fila), sin `clip` (no crea una capa por fila) y con los colores ya resueltos.
 */
@Composable
private fun LogRow(e: LogEntry, colors: LogColors) {
    val color = colors.of(e.level)
    val tinted = e.level == LogLevel.ERROR || e.level == LogLevel.WARN
    val barColor = color.copy(alpha = if (e.level == LogLevel.INFO) 0.35f else 0.9f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (tinted) Modifier.background(color.copy(alpha = 0.10f), LogRowShape) else Modifier)
            .drawBehind {
                val barW = 4.dp.toPx()
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(6.dp.toPx(), 4.dp.toPx()),
                    size = Size(barW, (size.height - 8.dp.toPx()).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius(barW / 2f)
                )
            }
            .padding(start = 16.dp, end = 10.dp, top = 6.dp, bottom = 6.dp)
    ) {
        if (e.time != null) {
            Text(
                text = if (e.tag != null) "${e.time} · ${e.tag}" else e.time,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = e.message,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** Píldoras de resumen sobre la lista: total de líneas y, si hay, errores y avisos. */
@Composable
private fun LogSummary(entries: List<LogEntry>, modifier: Modifier = Modifier) {
    val errors = entries.count { it.level == LogLevel.ERROR }
    val warns = entries.count { it.level == LogLevel.WARN }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SummaryPill(Strings.get(R.string.log_lineas, entries.size), MaterialTheme.colorScheme.outline)
        if (errors > 0) SummaryPill(Strings.get(R.string.log_errores, errors), MaterialTheme.colorScheme.error)
        if (warns > 0) SummaryPill(Strings.get(R.string.log_avisos, warns), syncAmberPalette().accent)
    }
}

@Composable
private fun SummaryPill(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(50)) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LogEmptyState(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        CookieBadge(
            icon = Icons.Default.Info,
            shape = MaterialShapes.Cookie9Sided.toShape(),
            background = scheme.secondaryContainer,
            glyph = scheme.onSecondaryContainer,
            spinning = true,
            size = 72.dp
        )
        Text(
            Strings.get(R.string.sin_logs_todavia),
            style = MaterialTheme.typography.titleMedium,
            color = scheme.onSurfaceVariant
        )
    }
}

/** Indicador flotante (no tocable) que entra y sale con resorte; activo = aro vacío, la lupa lo reemplaza. */
@Composable
private fun JumpIndicator(
    visible: Boolean,
    active: Boolean,
    icon: ImageVector,
    description: String
) {
    val scheme = MaterialTheme.colorScheme
    val fill by animateFloatAsState(
        targetValue = if (active) 0f else 0.6f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "jumpFill"
    )
    val ring by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "jumpRing"
    )
    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium)) + fadeIn(),
        exit = scaleOut(spring(stiffness = Spring.StiffnessMedium)) + fadeOut()
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = scheme.primaryContainer.copy(alpha = fill),
            border = BorderStroke(2.dp, scheme.primary.copy(alpha = ring))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = description,
                    tint = if (active) scheme.primary else scheme.onPrimaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
