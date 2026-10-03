package com.nubind.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.nubind.app.root.PerfStep
import com.nubind.app.root.PerfStepState
import com.nubind.app.root.PerfTestState
import com.nubind.app.R
import com.nubind.app.Strings

/**
 * Pantalla de la prueba de rendimiento: una hoja apilable (como la de agregar
 * servidor) con el resultado paso a paso. La prueba arranca sola al abrirla;
 * [onRun] la repite.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PerfTestSheet(
    state: PerfTestState,
    onRun: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scheme = MaterialTheme.colorScheme
    val progress = state.progress

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(Strings.get(R.string.probar_rendimiento), style = MaterialTheme.typography.headlineMedium)
            Text(
                Strings.get(R.string.comprueba_que_el_perfil_elegido_se),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant
            )

            if (state.running) {
                LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            }

            progress.verdict?.let { VerdictCard(it, progress.summary) }

            state.error?.let { message ->
                Surface(
                    color = scheme.errorContainer,
                    contentColor = scheme.onErrorContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(message, modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                progress.steps.forEach { StepRow(it) }
            }

            Button(
                onClick = onRun,
                enabled = !state.running,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(if (state.running) Strings.get(R.string.probando) else Strings.get(R.string.repetir_prueba))
            }
        }
    }
}

@Composable
private fun VerdictCard(verdict: PerfStepState, summary: String) {
    val scheme = MaterialTheme.colorScheme
    val (container, content, title) = when (verdict) {
        PerfStepState.OK -> Triple(scheme.primaryContainer, scheme.onPrimaryContainer, Strings.get(R.string.todo_en_orden))
        PerfStepState.WARN -> Triple(scheme.tertiaryContainer, scheme.onTertiaryContainer, Strings.get(R.string.con_avisos))
        else -> Triple(scheme.errorContainer, scheme.onErrorContainer, Strings.get(R.string.con_problemas))
    }
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (summary.isNotEmpty()) {
                Text(summary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun StepRow(step: PerfStep) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        StepBadge(step.state)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                step.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (step.state == PerfStepState.PENDING) scheme.onSurfaceVariant else scheme.onSurface
            )
            if (step.detail.isNotEmpty()) {
                Text(
                    step.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Círculo con el estado del paso: giratorio, check, aviso, error o vacío. */
@Composable
private fun StepBadge(state: PerfStepState) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (state) {
        PerfStepState.OK -> scheme.primary to scheme.onPrimary
        PerfStepState.WARN -> scheme.tertiary to scheme.onTertiary
        PerfStepState.FAIL -> scheme.error to scheme.onError
        else -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier.size(36.dp).clip(CircleShape).background(container),
        contentAlignment = Alignment.Center
    ) {
        when (state) {
            PerfStepState.RUNNING ->
                CircularProgressIndicator(Modifier.size(20.dp), color = scheme.primary, strokeWidth = 2.5.dp)
            PerfStepState.OK ->
                Icon(Icons.Default.Check, contentDescription = Strings.get(R.string.correcto), tint = content, modifier = Modifier.size(20.dp))
            PerfStepState.WARN ->
                Icon(Icons.Default.Warning, contentDescription = Strings.get(R.string.aviso), tint = content, modifier = Modifier.size(20.dp))
            PerfStepState.FAIL ->
                Icon(Icons.Default.Close, contentDescription = Strings.get(R.string.error_2), tint = content, modifier = Modifier.size(20.dp))
            PerfStepState.SKIP -> Text("–", color = content, style = MaterialTheme.typography.titleMedium)
            PerfStepState.PENDING -> Unit
        }
    }
}
