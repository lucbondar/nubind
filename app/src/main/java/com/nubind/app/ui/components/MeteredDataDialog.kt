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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MeteredDataDialog(onConfirm: (dontShowAgain: Boolean) -> Unit, onDismiss: () -> Unit) {
    var dontShow by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(36.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        icon = { MobileDataBadge() },
        title = {
            Text(
                Strings.get(R.string.aviso_datos_moviles_titulo),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    Strings.get(R.string.aviso_datos_moviles_texto),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .toggleable(
                            value = dontShow,
                            role = Role.Checkbox,
                            onValueChange = { dontShow = it }
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Checkbox(checked = dontShow, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                    Text(
                        Strings.get(R.string.no_volver_a_mostrar),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(dontShow) }) { Text(Strings.get(R.string.continuar_igual)) }
        },
        dismissButton = {
            FilledTonalButton(onClick = onDismiss) { Text(Strings.get(R.string.cancelar)) }
        }
    )
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
