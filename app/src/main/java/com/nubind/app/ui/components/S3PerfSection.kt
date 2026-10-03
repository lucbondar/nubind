package com.nubind.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.nubind.app.BindViewModel
import com.nubind.app.root.PerfMode
import com.nubind.app.root.S3Perf
import com.nubind.app.root.S3Provider
import com.nubind.app.root.formatMinutes
import kotlin.math.roundToInt
import com.nubind.app.R
import com.nubind.app.Strings

/**
 * Opciones de rendimiento propias de un servidor S3 (Oracle Cloud u otro
 * compatible), dentro de la tarjeta Rendimiento. Cada ajuste vacío es
 * "automático": el valor del proveedor y del perfil (S3Perf). Los listados y
 * "menos peticiones" aplican en ambos perfiles; lectura y subida en paralelo
 * solo en Máximo. Se aplican al volver a montar.
 */
@Composable
fun S3PerfSection(vm: BindViewModel, provider: S3Provider) {
    val scheme = MaterialTheme.colorScheme
    val s3 = vm.s3Perf
    val isMax = vm.perfMode == PerfMode.MAX

    val fewer = s3.fewerRequests ?: S3Perf.defaultFewerRequests(provider)
    val dirCache = s3.dirCacheMin ?: S3Perf.defaultDirCacheMin(provider, vm.perfMode)

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                when (provider) {
                    S3Provider.ORACLE -> Strings.get(R.string.opciones_de_oracle_cloud)
                    S3Provider.AWS -> Strings.get(R.string.opciones_de_amazon_s3)
                    S3Provider.CLOUDFLARE -> Strings.get(R.string.opciones_de_cloudflare_r2)
                    S3Provider.OTHER -> Strings.get(R.string.opciones_de_s3)
                },
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                when (provider) {
                    S3Provider.ORACLE ->
                        Strings.get(R.string.oracle_factura_y_limita_por_numero)
                    S3Provider.AWS ->
                        Strings.get(R.string.amazon_s3_aguanta_mucho_paralelismo_y)
                    S3Provider.CLOUDFLARE ->
                        Strings.get(R.string.r2_no_cobra_por_la_salida)
                    S3Provider.OTHER ->
                        Strings.get(R.string.valores_para_cualquier_servicio_compatible_con)
                },
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    Strings.get(R.string.menos_peticiones) + if (s3.fewerRequests == null) Strings.get(R.string.auto) else "",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    Strings.get(R.string.no_pide_los_datos_de_cada),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
            Switch(checked = fewer, onCheckedChange = { vm.setS3FewerRequests(it) })
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                Strings.get(R.string.listados_en_cache) + if (s3.dirCacheMin == null) Strings.get(R.string.auto) else "",
                style = MaterialTheme.typography.titleSmall
            )
            ChipChoices(
                choices = (S3Perf.DIR_CACHE_CHOICES_MIN + dirCache).distinct().sorted(),
                selected = dirCache,
                label = { formatMinutes(it) },
                onSelect = { vm.setS3DirCacheMin(it) }
            )
            Text(
                Strings.get(R.string.ajustes_globales_de_s3_se_aplican),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
        }

        if (isMax) {
            S3StepSlider(
                title = Strings.get(R.string.lectura_paralela),
                value = s3.streams ?: S3Perf.STREAMS_DEFAULT,
                isAuto = s3.streams == null,
                range = S3Perf.STREAMS_MIN..S3Perf.STREAMS_MAX,
                unit = "trozos",
                onCommit = { vm.setS3Streams(it) }
            )
            S3StepSlider(
                title = Strings.get(R.string.subida_paralela),
                value = s3.uploadConcurrency ?: S3Perf.defaultUploadConcurrency(provider),
                isAuto = s3.uploadConcurrency == null,
                range = S3Perf.UPLOAD_CONC_MIN..S3Perf.UPLOAD_CONC_MAX,
                unit = "partes",
                onCommit = { vm.setS3UploadConcurrency(it) }
            )

            val chunk = s3.chunkMb ?: S3Perf.CHUNK_DEFAULT_MB
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    Strings.get(R.string.tamano_de_parte_de_subida) + if (s3.chunkMb == null) Strings.get(R.string.auto) else "",
                    style = MaterialTheme.typography.titleSmall
                )
                ChipChoices(
                    choices = S3Perf.CHUNK_CHOICES_MB,
                    selected = chunk,
                    label = { "$it MB" },
                    onSelect = { vm.setS3ChunkMb(it) }
                )
                val requested = s3.uploadConcurrency ?: S3Perf.defaultUploadConcurrency(provider)
                val used = S3Perf.effectiveUploadConcurrency(requested, chunk)
                val ram = S3Perf.uploadRamMb(used, chunk)
                Text(
                    if (used < requested) {
                        Strings.get(R.string.buffers_multiparte_aprox_mb_se_usaran, ram, used, requested, S3Perf.UPLOAD_RAM_CAP_MB)
                    } else {
                        Strings.get(R.string.buffers_multiparte_aprox_mb_no_incluye, ram)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (used < requested) scheme.error else scheme.onSurfaceVariant
                )
            }
        } else {
            Text(
                Strings.get(R.string.lectura_y_subida_en_paralelo_estan),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
        }

        if (s3.isCustom) {
            TextButton(onClick = { vm.resetS3Perf() }) { Text(Strings.get(R.string.restablecer_valores_automaticos)) }
        }
    }
}

@Composable
private fun S3StepSlider(
    title: String,
    value: Int,
    isAuto: Boolean,
    range: IntRange,
    unit: String,
    onCommit: (Int) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var draft by remember(value) { mutableStateOf(value.toFloat()) }
    var lastTick by remember(value) { mutableStateOf(value) }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                "${draft.roundToInt()} $unit" + if (isAuto) Strings.get(R.string.auto) else "",
                style = MaterialTheme.typography.titleSmall,
                color = scheme.primary
            )
        }
        Slider(
            value = draft,
            onValueChange = {
                draft = it
                val rounded = it.roundToInt()
                if (rounded != lastTick) {
                    lastTick = rounded
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                }
            },
            onValueChangeFinished = { onCommit(draft.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1
        )
    }
}

@Composable
private fun ChipChoices(
    choices: List<Int>,
    selected: Int,
    label: (Int) -> String,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        choices.forEach { choice ->
            FilterChip(
                selected = choice == selected,
                onClick = { onSelect(choice) },
                label = { Text(label(choice)) }
            )
        }
    }
}
