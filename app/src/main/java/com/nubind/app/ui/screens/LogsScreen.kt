package com.nubind.app.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import com.nubind.app.BindViewModel
import com.nubind.app.ui.components.ScreenContainer
import kotlinx.coroutines.launch

@Composable
fun LogsScreen(vm: BindViewModel) {
    LaunchedEffect(Unit) { vm.refreshLogs() }

    // 0 = texto normal, 1 = texto ya "tirado a la papelera" (invisible).
    val trash = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

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

    ScreenContainer(
        title = "Logs",
        scroll = false,
        refreshing = vm.refreshing,
        onRefresh = { vm.pullRefresh() },
        actions = {
            IconButton(onClick = { discardLogs() }, enabled = vm.logs.isNotBlank()) {
                Icon(Icons.Default.Delete, contentDescription = "Borrar registro")
            }
            IconButton(onClick = { vm.refreshLogs() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Actualizar")
            }
        }
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            Text(
                text = vm.logs.ifBlank { "Sin logs todavía." },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxSize()
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
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            )
        }
    }
}
