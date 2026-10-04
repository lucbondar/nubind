package com.nubind.app.ui.screens

import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import com.nubind.app.ui.components.CookieBadge
import com.nubind.app.ui.theme.syncAmberPalette
import com.nubind.app.ui.theme.updateGreenPalette
import kotlinx.coroutines.delay
import com.nubind.app.BindViewModel
import com.nubind.app.ui.components.ScreenContainer
import kotlinx.coroutines.launch
import com.nubind.app.R
import com.nubind.app.Strings

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    var pressing by remember { mutableStateOf(false) }
    var showJump by remember { mutableStateOf(false) }
    var bump by remember { mutableStateOf(0) }
    // Sostener el toque ~0,3 s sobre el log muestra los botones; al soltar se quedan un
    // momento para poder tocarlos y se esconden solos (tocar uno reinicia la cuenta).
    LaunchedEffect(pressing, bump) {
        if (pressing) {
            delay(300)
            if (!showJump) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            showJump = true
        } else if (showJump) {
            delay(2500)
            showJump = false
        }
    }
    fun jump(toEnd: Boolean) {
        bump++
        scope.launch {
            if (entries.isEmpty()) return@launch
            if (toEnd) listState.animateScrollToItem(entries.lastIndex) else listState.animateScrollToItem(0)
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
                .pointerInput(Unit) {
                    // Solo observa (pasada Initial, sin consumir): el desplazamiento sigue igual.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        pressing = true
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                        } while (event.changes.any { it.pressed })
                        pressing = false
                    }
                }
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
                            LogEmptyState(Modifier.align(Alignment.Center))
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                itemsIndexed(entries) { _, e -> LogRow(e) }
                            }
                        }
                    }
                }
            }

            // Botones de desplazamiento rápido: aparecen con resorte al sostener el toque.
            Column(
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.End
            ) {
                JumpButton(
                    visible = showJump && entries.isNotEmpty(),
                    enabled = listState.canScrollBackward,
                    icon = Icons.Default.KeyboardArrowUp,
                    description = Strings.get(R.string.log_ir_al_inicio),
                    onClick = { jump(false) }
                )
                JumpButton(
                    visible = showJump && entries.isNotEmpty(),
                    enabled = listState.canScrollForward,
                    icon = Icons.Default.KeyboardArrowDown,
                    description = Strings.get(R.string.log_ir_al_final),
                    onClick = { jump(true) }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Modelo y piezas del log
// ---------------------------------------------------------------------------

private enum class LogLevel { INFO, OK, WARN, ERROR }

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

@Composable
private fun levelColor(level: LogLevel): Color = when (level) {
    LogLevel.ERROR -> MaterialTheme.colorScheme.error
    LogLevel.WARN -> syncAmberPalette().accent
    LogLevel.OK -> updateGreenPalette().accent
    LogLevel.INFO -> MaterialTheme.colorScheme.outline
}

/** Una línea: barra de color por severidad, hora tenue y mensaje en monoespaciada. */
@Composable
private fun LogRow(e: LogEntry) {
    val color = levelColor(e.level)
    val tinted = e.level == LogLevel.ERROR || e.level == LogLevel.WARN
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (tinted) color.copy(alpha = 0.10f) else Color.Transparent)
            .height(IntrinsicSize.Min)
            .padding(end = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .padding(vertical = 4.dp, horizontal = 6.dp)
                .width(4.dp)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(color.copy(alpha = if (e.level == LogLevel.INFO) 0.35f else 0.9f))
        )
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
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

/** Botón flotante pequeño que entra y sale con resorte; atenuado si ya no hay hacia dónde ir. */
@Composable
private fun JumpButton(
    visible: Boolean,
    enabled: Boolean,
    icon: ImageVector,
    description: String,
    onClick: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium)) + fadeIn(),
        exit = scaleOut(spring(stiffness = Spring.StiffnessMedium)) + fadeOut()
    ) {
        SmallFloatingActionButton(
            onClick = { if (enabled) onClick() },
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (enabled) 1f else 0.5f),
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = if (enabled) 1f else 0.5f)
        ) {
            Icon(icon, contentDescription = description)
        }
    }
}
