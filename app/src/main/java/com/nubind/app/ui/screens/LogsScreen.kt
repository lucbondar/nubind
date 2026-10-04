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
import com.nubind.app.BindViewModel
import com.nubind.app.ui.components.ScreenContainer
import kotlinx.coroutines.launch
import com.nubind.app.R
import com.nubind.app.Strings

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
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            Text(
                text = vm.logs.ifBlank { Strings.get(R.string.sin_logs_todavia) },
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
