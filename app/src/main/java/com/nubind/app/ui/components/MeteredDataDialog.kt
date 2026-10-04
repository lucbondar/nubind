package com.nubind.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.nubind.app.R
import com.nubind.app.Strings
import kotlin.math.PI
import kotlin.math.cos

/**
 * Aviso de datos móviles antes de pasar a Máximo / montar / precargar: el
 * perfil Máximo descarga el contenido del servidor a la caché del teléfono y
 * puede gastar varios GB del plan. [onConfirm] recibe true si el usuario
 * marcó "No volver a mostrar" (solo se guarda al continuar; cancelar no lo
 * silencia).
 *
 * Expressive: la tarjeta entra con resorte, la insignia "salta" y el resto
 * entra escalonado; el interruptor se llena de color al marcarse y los
 * botones (apilados, a todo el ancho) se redondean más al pulsarlos.
 * Cancelar es el botón pleno (lo seguro); continuar es el tonal de error.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MeteredDataDialog(onConfirm: (dontShowAgain: Boolean) -> Unit, onDismiss: () -> Unit) {
    var dontShow by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    val card = remember { Animatable(0f) }
    val badgePop = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { card.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) }
        launch {
            delay(120)
            badgePop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow))
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = scheme.surfaceContainerHigh,
            shape = RoundedCornerShape(40.dp),
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    val t = card.value
                    alpha = t.coerceIn(0f, 1f)
                    val sc = 0.82f + 0.18f * t
                    scaleX = sc
                    scaleY = sc
                }
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.graphicsLayer {
                    val t = badgePop.value
                    scaleX = t
                    scaleY = t
                    rotationZ = (1f - t) * -40f
                }) { MobileDataBadge() }
                Spacer(Modifier.height(20.dp))
                Entrance(1) {
                    Text(
                        Strings.get(R.string.aviso_datos_moviles_titulo),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(12.dp))
                Entrance(2) {
                    Text(
                        Strings.get(R.string.aviso_datos_moviles_texto),
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(18.dp))
                Entrance(3) { DontShowToggle(checked = dontShow, onChange = { dontShow = it }) }
                Spacer(Modifier.height(22.dp))
                Entrance(4) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PressMorphButton(
                            label = Strings.get(R.string.cancelar),
                            container = scheme.primary,
                            content = scheme.onPrimary,
                            onClick = onDismiss
                        )
                        PressMorphButton(
                            label = Strings.get(R.string.continuar_igual),
                            container = scheme.errorContainer,
                            content = scheme.onErrorContainer,
                            onClick = { onConfirm(dontShow) }
                        )
                    }
                }
            }
        }
    }
}

/** "No volver a mostrar": píldora que se llena de color y muestra una marca al activarse. */
@Composable
private fun DontShowToggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val container by animateColorAsState(
        if (checked) scheme.secondaryContainer else scheme.surfaceContainerHighest,
        label = "dontShowContainer"
    )
    val content by animateColorAsState(
        if (checked) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
        label = "dontShowContent"
    )
    val corner by animateDpAsState(
        if (checked) 28.dp else 16.dp,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "dontShowCorner"
    )
    val markScale by animateFloatAsState(
        if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "dontShowMark"
    )
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(corner),
        // Recorte a la forma antes del toggleable: el resplandor del toque no debe salirse de la píldora.
        modifier = Modifier.clip(RoundedCornerShape(corner)).toggleable(
            value = checked,
            role = Role.Checkbox,
            onValueChange = {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onChange(it)
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (checked) scheme.secondary else scheme.outlineVariant.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = scheme.onSecondary,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer {
                            scaleX = markScale
                            scaleY = markScale
                        }
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(Strings.get(R.string.no_volver_a_mostrar), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Botón de píldora a todo el ancho que se "aprieta" (esquinas menos redondas) mientras se pulsa. */
@Composable
private fun PressMorphButton(label: String, container: Color, content: Color, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val corner by animateDpAsState(
        if (pressed) 16.dp else 28.dp,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 500f),
        label = "pressCorner"
    )
    Surface(
        onClick = onClick,
        interactionSource = source,
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(corner),
        modifier = Modifier.fillMaxWidth().height(56.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * Insignia expressive: una forma "cookie" de Material que gira despacio y
 * respira, con la señal de datos (barras en onda) y una flecha de descarga
 * que cae y se desvanece.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MobileDataBadge() {
    val transition = rememberInfiniteTransition(label = "metered")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Restart),
        label = "spin"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    val shape = MaterialShapes.Cookie9Sided.toShape()
    val container = MaterialTheme.colorScheme.errorContainer
    val glyph = MaterialTheme.colorScheme.onErrorContainer

    Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = spin
                    scaleX = pulse
                    scaleY = pulse
                }
                .background(container, shape)
        )
        Canvas(Modifier.size(52.dp)) {
            val w = size.width
            val h = size.height

            // Barras (4), ancho y alto crecientes, a la izquierda.
            val bars = 4
            val barW = w * 0.09f
            val gap = w * 0.055f
            val baseY = h * 0.88f
            for (i in 0 until bars) {
                val barH = h * (0.22f + 0.17f * i)
                val wave = 0.5f + 0.5f * cos(2f * PI.toFloat() * (phase - i / bars.toFloat()))
                drawRoundRect(
                    color = glyph.copy(alpha = 0.3f + 0.7f * wave),
                    topLeft = Offset(w * 0.06f + i * (barW + gap), baseY - barH),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(barW / 2f)
                )
            }

            // Flecha de descarga, a la derecha: baja y se desvanece en cada ciclo.
            val cx = w * 0.80f
            val travel = h * 0.22f
            val top = h * 0.18f + travel * phase
            val len = h * 0.34f
            val alpha = if (phase < 0.2f) phase / 0.2f else 1f - (phase - 0.2f) / 0.8f
            val strokeW = w * 0.07f
            val color = glyph.copy(alpha = alpha.coerceIn(0f, 1f))
            drawLine(color, Offset(cx, top), Offset(cx, top + len), strokeWidth = strokeW, cap = StrokeCap.Round)
            val head = Path().apply {
                moveTo(cx - w * 0.10f, top + len - w * 0.10f)
                lineTo(cx, top + len)
                lineTo(cx + w * 0.10f, top + len - w * 0.10f)
            }
            drawPath(head, color, style = Stroke(width = strokeW, cap = StrokeCap.Round))
        }
    }
}
