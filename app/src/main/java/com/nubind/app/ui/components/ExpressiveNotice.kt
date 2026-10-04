package com.nubind.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nubind.app.ui.theme.AppMotion
import com.nubind.app.ui.theme.StatusPalette
import com.nubind.app.ui.theme.syncAmberPalette
import com.nubind.app.ui.theme.updateGreenPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Tipo de aviso: decide icono, forma, colores y cuánto tiempo se queda en pantalla. */
enum class NoticeKind(val durationMs: Long) {
    /** Algo salió bien (guardado, conectado, borrado). */
    Success(3500),

    /** Información neutra. */
    Info(4000),

    /** Algo que el usuario debe corregir o hacer antes (no es un fallo). */
    Warning(5500),

    /** Algo falló; lleva detalle técnico, así que dura más. */
    Error(7000)
}

/**
 * Un aviso de una sola vez. [id] es único por aviso: así el mismo texto mostrado dos
 * veces seguidas vuelve a animarse y reinicia el temporizador.
 */
@Stable
class AppNotice(val text: String, val kind: NoticeKind, val id: Long)

/**
 * Anfitrión del aviso expressive (reemplaza al snackbar y a los Toast de la app).
 *
 * Una tarjeta flotante muy redondeada con una insignia de forma Material Expressive
 * (cada tipo tiene la suya) que entra girando con resorte. Se cierra sola según el
 * tipo, al tocarla o al deslizarla hacia un lado. Solo hay un aviso a la vez: uno
 * nuevo reemplaza al anterior.
 *
 * [onDismiss] recibe el id del aviso que se cierra, para no cerrar uno más nuevo.
 */
@Composable
fun ExpressiveNoticeHost(
    notice: AppNotice?,
    onDismiss: (id: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(notice?.id) {
        val n = notice ?: return@LaunchedEffect
        delay(n.kind.durationMs)
        onDismiss(n.id)
    }

    // Al cerrarse, notice pasa a null antes de terminar la salida: se conserva el último.
    var shown by remember { mutableStateOf<AppNotice?>(null) }
    if (notice != null) shown = notice

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = notice != null,
            enter = slideInVertically(AppMotion.spatial()) { it / 2 } +
                scaleIn(AppMotion.spatial(), initialScale = 0.85f) +
                fadeIn(AppMotion.effects()),
            exit = slideOutVertically(AppMotion.spatial()) { it / 2 } +
                scaleOut(AppMotion.effects(), targetScale = 0.9f) +
                fadeOut(AppMotion.effects())
        ) {
            shown?.let { n ->
                // Una clave por aviso: reinicia el desplazamiento y la animación de la insignia.
                key(n.id) { NoticeCard(n, onDismiss = { onDismiss(n.id) }) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NoticeCard(notice: AppNotice, onDismiss: () -> Unit) {
    val look = noticeLook(notice.kind)
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }

    // La insignia entra girando y creciendo con resorte.
    val spin = remember { Animatable(-110f) }
    val grow = remember { Animatable(0.4f) }
    LaunchedEffect(Unit) {
        launch { spin.animateTo(0f, AppMotion.spatial()) }
        launch { grow.animateTo(1f, AppMotion.spatial()) }
    }

    Surface(
        onClick = onDismiss,
        shape = RoundedCornerShape(32.dp),
        color = look.palette.container,
        contentColor = look.palette.onContainer,
        shadowElevation = 6.dp,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .widthIn(max = 560.dp)
            .offset { IntOffset(offsetX.value.roundToInt(), 0) }
            .graphicsLayer { alpha = (1f - abs(offsetX.value) / 700f).coerceIn(0f, 1f) }
            .pointerInput(Unit) {
                val threshold = 120.dp.toPx()
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (abs(offsetX.value) > threshold) onDismiss()
                        else scope.launch { offsetX.animateTo(0f, AppMotion.spatial()) }
                    },
                    onDragCancel = { scope.launch { offsetX.animateTo(0f, AppMotion.spatial()) } }
                ) { change, drag ->
                    change.consume()
                    scope.launch { offsetX.snapTo(offsetX.value + drag) }
                }
            }
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .graphicsLayer {
                        rotationZ = spin.value
                        scaleX = grow.value
                        scaleY = grow.value
                    }
                    .background(look.palette.accent, look.shapeOf()),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = look.icon,
                    contentDescription = null,
                    tint = look.palette.onAccent,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                text = notice.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
    }
}

private class NoticeLook(
    val palette: StatusPalette,
    val icon: ImageVector,
    val shapeOf: @Composable () -> androidx.compose.ui.graphics.Shape
)

/** Colores, icono y forma de cada tipo. Verde y ámbar son los fijos del actualizador. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun noticeLook(kind: NoticeKind): NoticeLook {
    val scheme = MaterialTheme.colorScheme
    return when (kind) {
        NoticeKind.Success -> NoticeLook(
            updateGreenPalette(), Icons.Filled.Check
        ) { MaterialShapes.Cookie9Sided.toShape() }

        NoticeKind.Warning -> NoticeLook(
            syncAmberPalette(), Icons.Filled.Warning
        ) { MaterialShapes.Sunny.toShape() }

        NoticeKind.Error -> NoticeLook(
            StatusPalette(scheme.errorContainer, scheme.onErrorContainer, scheme.error, scheme.onError),
            Icons.Filled.Error
        ) { MaterialShapes.Cookie9Sided.toShape() }

        NoticeKind.Info -> NoticeLook(
            StatusPalette(scheme.secondaryContainer, scheme.onSecondaryContainer, scheme.secondary, scheme.onSecondary),
            Icons.Filled.Info
        ) { MaterialShapes.Sunny.toShape() }
    }
}
