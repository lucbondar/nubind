package com.nubind.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Entrada escalonada de las tarjetas: aparecen una tras otra subiendo un poco con un
 * resorte suave. Solo mueve y desvanece (no cambia el tamaño), así el contenido no salta
 * mientras llegan. Lo usan Acerca de y Servidores; la cabecera con el logo no pasa por aquí:
 * el icono queda intacto.
 */
@Composable
fun Entrance(index: Int, content: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 80L)
        progress.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow))
    }
    val rise = with(LocalDensity.current) { 28.dp.toPx() }
    Box(
        Modifier.graphicsLayer {
            alpha = progress.value.coerceIn(0f, 1f)
            translationY = (1f - progress.value) * rise
        }
    ) { content() }
}
