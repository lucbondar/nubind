package com.nubind.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.nubind.app.R
import com.nubind.app.Strings
import kotlin.math.PI
import kotlin.math.cos

/**
 * Aviso de datos móviles antes de pasar a Máximo / montar / precargar: el
 * perfil Máximo descarga el contenido del servidor a la caché del teléfono y
 * puede gastar varios GB del plan.
 */
@Composable
fun MeteredDataDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { MobileDataIcon() },
        title = { Text(Strings.get(R.string.aviso_datos_moviles_titulo)) },
        text = { Text(Strings.get(R.string.aviso_datos_moviles_texto)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(Strings.get(R.string.continuar_igual)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(Strings.get(R.string.cancelar)) }
        }
    )
}

/**
 * Señal de datos móviles: las barras se encienden en onda, de menor a mayor,
 * y una flecha de descarga cae y se desvanece a su lado.
 */
@Composable
private fun MobileDataIcon() {
    val transition = rememberInfiniteTransition(label = "metered")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )
    val barColor = MaterialTheme.colorScheme.error
    val arrowColor = MaterialTheme.colorScheme.primary

    Canvas(Modifier.size(56.dp)) {
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
                color = barColor.copy(alpha = 0.25f + 0.75f * wave),
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
        val stroke = Stroke(width = w * 0.07f, cap = StrokeCap.Round)
        val color = arrowColor.copy(alpha = alpha.coerceIn(0f, 1f))
        drawLine(color, Offset(cx, top), Offset(cx, top + len), strokeWidth = stroke.width, cap = StrokeCap.Round)
        val head = Path().apply {
            moveTo(cx - w * 0.10f, top + len - w * 0.10f)
            lineTo(cx, top + len)
            lineTo(cx + w * 0.10f, top + len - w * 0.10f)
        }
        drawPath(head, color, style = stroke)
    }
}
