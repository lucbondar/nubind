package com.nubind.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nubind.app.BindViewModel
import com.nubind.app.ui.components.AppIcons
import com.nubind.app.root.CACHE_GB_MAX
import com.nubind.app.root.CACHE_GB_MIN
import com.nubind.app.root.PerfMode
import com.nubind.app.root.RemoteType
import com.nubind.app.root.S3Provider
import com.nubind.app.root.s3Provider
import com.nubind.app.root.subtitle
import com.nubind.app.root.defaultCacheGb
import com.nubind.app.root.formatCacheKb
import com.nubind.app.ui.components.DualPaneContentWidth
import com.nubind.app.ui.components.FolderPickerDialog
import com.nubind.app.ui.components.MeteredDataDialog
import com.nubind.app.ui.components.OptionTile
import com.nubind.app.ui.components.PerfTestSheet
import com.nubind.app.ui.components.ScreenContainer
import com.nubind.app.ui.components.S3PerfSection
import com.nubind.app.ui.components.SectionCard
import com.nubind.app.ui.components.rememberIsDualPane
import com.nubind.app.ui.components.serverIconFor
import com.nubind.app.ui.theme.AppMotion
import kotlin.math.roundToInt
import com.nubind.app.R
import com.nubind.app.Strings

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(vm: BindViewModel, onOpenServers: () -> Unit) {
    LaunchedEffect(Unit) { vm.refreshAll() }

    val scheme = MaterialTheme.colorScheme
    val mounted = vm.isMounted
    val active = vm.activeName
    var showPathDialog by remember { mutableStateOf(false) }
    var showRamCacheConfirm by remember { mutableStateOf(false) }
    var showPerfTest by remember { mutableStateOf(false) }
    val heroColor by animateColorAsState(
        if (mounted) scheme.primaryContainer else scheme.surfaceContainerHigh,
        AppMotion.effects(), label = "heroColor"
    )
    val heroContent by animateColorAsState(
        if (mounted) scheme.onPrimaryContainer else scheme.onSurface,
        AppMotion.effects(), label = "heroContent"
    )
    val dualPane = rememberIsDualPane()

    val onRunPerfTest: () -> Unit = {
        showPerfTest = true
        vm.startPerfTest()
    }

    ScreenContainer(
        title = Strings.get(R.string.inicio),
        refreshing = vm.refreshing,
        onRefresh = { vm.pullRefresh() },
        maxContentWidth = if (dualPane) DualPaneContentWidth else null
    ) {
        Surface(
            color = heroColor,
            contentColor = heroContent,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (mounted) Strings.get(R.string.montado) else Strings.get(R.string.desmontado),
                    style = MaterialTheme.typography.headlineLarge
                )
                Text(
                    if (mounted) {
                        Strings.get(R.string.los_archivos_de_estan_en, vm.mountedRemote ?: Strings.get(R.string.tu_servidor), vm.targetPath)
                    } else {
                        Strings.get(R.string.elige_un_servidor_y_montalo_en, vm.targetPath)
                    },
                    style = MaterialTheme.typography.bodyLarge
                )
                if (vm.busy) {
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }

        if (vm.rootGranted == false) {
            Surface(
                color = scheme.errorContainer,
                contentColor = scheme.onErrorContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    Strings.get(R.string.no_se_detecto_acceso_root_concede),
                    modifier = Modifier.padding(20.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // En apaisado (o tablet) hay ancho de sobra para dos columnas: a la
        // izquierda lo que se usa para montar (servidor, carpeta, botón); a
        // la derecha los ajustes (autostart y rendimiento). En vertical
        // sigue todo apilado en una sola columna, como siempre.
        if (dualPane) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    MountCard(
                        vm = vm,
                        mounted = mounted,
                        active = active,
                        onOpenServers = onOpenServers,
                        onChangeFolder = { showPathDialog = true }
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    AutostartCard(vm)
                    PerfCard(
                        vm = vm,
                        mounted = mounted,
                        onRunPerfTest = onRunPerfTest,
                        onRequestRamCache = { showRamCacheConfirm = true }
                    )
                    if (vm.perfMode == PerfMode.MAX) {
                        PreloadCard(vm, mounted)
                    }
                }
            }
        } else {
            MountCard(
                vm = vm,
                mounted = mounted,
                active = active,
                onOpenServers = onOpenServers,
                onChangeFolder = { showPathDialog = true }
            )
            AutostartCard(vm)
            PerfCard(
                vm = vm,
                mounted = mounted,
                onRunPerfTest = onRunPerfTest,
                onRequestRamCache = { showRamCacheConfirm = true }
            )
            if (vm.perfMode == PerfMode.MAX) {
                PreloadCard(vm, mounted)
            }
        }
    }

    if (vm.pendingMeteredAction != null) {
        MeteredDataDialog(onConfirm = { vm.confirmMetered() }, onDismiss = { vm.dismissMetered() })
    }

    if (showRamCacheConfirm) {
        AlertDialog(
            onDismissRequest = { showRamCacheConfirm = false },
            title = { Text(Strings.get(R.string.activar_cache_en_ram)) },
            text = {
                Text(
                    Strings.get(R.string.la_cache_del_perfil_maximo_se)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setRamCache(true)
                    showRamCacheConfirm = false
                }) { Text(Strings.get(R.string.activar)) }
            },
            dismissButton = {
                TextButton(onClick = { showRamCacheConfirm = false }) { Text(Strings.get(R.string.cancelar)) }
            }
        )
    }

    if (showPerfTest) {
        PerfTestSheet(
            state = vm.perfTest,
            onRun = { vm.startPerfTest() },
            onDismiss = {
                // Cerrar la hoja a mitad de la prueba la corta y borra el archivo temporal.
                vm.cancelPerfTest()
                showPerfTest = false
            }
        )
    }

    if (showPathDialog) {
        FolderPickerDialog(
            initialPath = vm.targetPath,
            onPick = {
                vm.setTargetPath(it)
                showPathDialog = false
            },
            onDismiss = { showPathDialog = false }
        )
    }
}

/**
 * Servidor seleccionado, carpeta de destino y el botón Montar en una sola
 * SectionCard, en ese orden, para que las tres acciones del flujo de
 * montaje queden juntas de un vistazo (v1.5.1). Es la columna izquierda del
 * doble panel en apaisado, o la primera tarjeta de la columna única en
 * vertical.
 */
@Composable
private fun MountCard(
    vm: BindViewModel,
    mounted: Boolean,
    active: String?,
    onOpenServers: () -> Unit,
    onChangeFolder: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val selected = vm.profiles.firstOrNull { it.name == active }
    val label = when {
        vm.busy -> Strings.get(R.string.trabajando)
        mounted && (vm.mountedRemote == null || vm.mountedRemote == active) -> Strings.get(R.string.desmontar)
        mounted -> Strings.get(R.string.cambiar_a, active)
        else -> Strings.get(R.string.montar)
    }
    SectionCard(title = Strings.get(R.string.servidor_seleccionado), icon = AppIcons.Cloud) {
        Surface(
            color = scheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mini icono del tipo de servidor: logo de Drive (con sus
                // colores, por eso Image) o el icono de servidor para FTP.
                if (selected != null) {
                    val icon = serverIconFor(selected)
                    if (icon.branded) {
                        // Logos con letras oscuras (AWS) tienen variante para fondo oscuro.
                        Image(
                            icon.forBackground(scheme.surface.luminance() < 0.5f),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Icon(
                            icon.vector,
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                }
                // Mismo estilo que la carpeta de destino, centrado
                // verticalmente. La línea secundaria (usuario@host) solo
                // se dibuja si tiene texto: en Drive el host va vacío y
                // un Text vacío igual ocupa una línea, lo que descentraba
                // el nombre.
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(
                        selected?.name ?: Strings.get(R.string.ninguno),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val detail = when {
                        selected == null || selected.type == RemoteType.DRIVE -> ""
                        else -> selected.subtitle
                    }
                    if (detail.isNotBlank()) {
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                TextButton(onClick = onOpenServers) {
                    Text(if (selected == null) Strings.get(R.string.agregar) else Strings.get(R.string.cambiar))
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(AppIcons.Folder, contentDescription = null, tint = scheme.primary)
            Text(
                Strings.get(R.string.carpeta_de_destino),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
        }
        Surface(
            color = scheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    vm.targetPath,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onChangeFolder) { Text(Strings.get(R.string.cambiar)) }
            }
        }

        Button(
            onClick = { vm.toggleMount() },
            enabled = !vm.busy && (active != null || mounted),
            colors = if (mounted) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.buttonColors(),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().height(64.dp)
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Interruptor de montaje automático al iniciar el teléfono. */
@Composable
private fun AutostartCard(vm: BindViewModel) {
    SectionCard(
        title = Strings.get(R.string.montar_al_iniciar),
        icon = Icons.Default.PlayArrow,
        subtitle = Strings.get(R.string.monta_el_servidor_seleccionado_cuando_arranca)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                if (vm.autostart) Strings.get(R.string.activado) else Strings.get(R.string.desactivado),
                style = MaterialTheme.typography.titleMedium
            )
            Switch(checked = vm.autostart, onCheckedChange = { vm.setAutostart(it) })
        }
    }
}

/**
 * Perfil de rendimiento (Equilibrado/Máximo), tamaño de caché, prueba de
 * velocidad, caché en RAM (solo en Máximo) y caché en disco.
 */
@Composable
private fun PerfCard(
    vm: BindViewModel,
    mounted: Boolean,
    onRunPerfTest: () -> Unit,
    onRequestRamCache: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    SectionCard(
        title = Strings.get(R.string.rendimiento),
        icon = AppIcons.Bolt,
        subtitle = Strings.get(R.string.maximo_guarda_mas_en_cache_para),
        expandable = true
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PerfMode.entries.forEach { mode ->
                OptionTile(
                    label = mode.label,
                    icon = if (mode == PerfMode.MAX) AppIcons.Bolt else Icons.Default.Settings,
                    selected = vm.perfMode == mode,
                    onClick = { vm.setPerfMode(mode) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // v1.5.2: igual que "Caché en RAM", el tamaño de caché solo tiene
        // sentido en modo Máximo (en Equilibrado no se usa un tamaño
        // configurable), así que se oculta por completo en Equilibrado.
        if (vm.perfMode == PerfMode.MAX) {
            val haptics = LocalHapticFeedback.current
            val custom = vm.cacheGb
            val effective = custom ?: defaultCacheGb(vm.perfMode)
            var draft by remember(effective) { mutableStateOf(effective.toFloat()) }
            // SegmentTick en cada GB que cruza el dedo (no en cada píxel):
            // el mismo háptico suave y discreto que ya usa la píldora de
            // navegación, en vez del tic más fuerte por defecto del Slider.
            var lastTick by remember(effective) { mutableStateOf(effective) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(Strings.get(R.string.tamano_de_cache), style = MaterialTheme.typography.titleMedium)
                Text(
                    "${draft.roundToInt()} GB" + if (custom == null) Strings.get(R.string.auto) else "",
                    style = MaterialTheme.typography.titleMedium,
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
                onValueChangeFinished = { vm.setCacheGb(draft.roundToInt()) },
                valueRange = CACHE_GB_MIN.toFloat()..CACHE_GB_MAX.toFloat(),
                steps = CACHE_GB_MAX - CACHE_GB_MIN - 1
            )
            Text(
                Strings.get(R.string.aplica_a_google_drive_s3_y),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
            if (custom != null) {
                TextButton(onClick = { vm.setCacheGb(null) }) { Text(Strings.get(R.string.restablecer_tamano_automatico)) }
            }
        }

        // Opciones dedicadas cuando el servidor elegido es S3 (Oracle u otro
        // compatible): ver S3PerfSection.
        vm.profiles.firstOrNull { it.name == vm.activeName && it.type == RemoteType.S3 }?.let { s3 ->
            HorizontalDivider()
            S3PerfSection(vm, s3.s3Provider ?: S3Provider.OTHER)
        }

        FilledTonalButton(
            onClick = onRunPerfTest,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Icon(AppIcons.Bolt, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(Strings.get(R.string.probar_rendimiento), style = MaterialTheme.typography.titleMedium)
        }

        if (vm.perfMode == PerfMode.MAX) {
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(Strings.get(R.string.cache_en_ram), style = MaterialTheme.typography.titleMedium)
                    Text(
                        Strings.get(R.string.lecturas_y_escrituras_a_velocidad_de),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = vm.ramCache,
                    onCheckedChange = { enabled ->
                        if (enabled) onRequestRamCache() else vm.setRamCache(false)
                    }
                )
            }
        }

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(Strings.get(R.string.cache_en_disco), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (mounted) {
                        Strings.get(R.string.usados_desmonta_para_borrarla, formatCacheKb(vm.cacheKb))
                    } else {
                        Strings.get(R.string.usados, formatCacheKb(vm.cacheKb))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { vm.clearCache() }, enabled = !mounted && !vm.busy) {
                Text(Strings.get(R.string.borrar_cache))
            }
        }
    }
}

/**
 * Progreso de scripts/preload.sh y botón para relanzarlo a mano. Solo se
 * muestra en perfil Máximo (es el único que cachea lecturas completas de
 * FTP; Drive las cachea en cualquier perfil, pero el botón manual solo
 * tiene sentido junto al resto de los controles de rendimiento). El
 * objetivo: que al llegar la barra a 100%, lo que ya se precargó se lea
 * desde el teléfono, sin esperar a la red.
 */
@Composable
private fun PreloadCard(vm: BindViewModel, mounted: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val status = vm.preloadStatus
    val hasData = status != null && status.selectedFiles > 0

    SectionCard(
        title = Strings.get(R.string.precarga_de_archivos),
        icon = AppIcons.Download,
        subtitle = Strings.get(R.string.baja_los_archivos_del_remoto_a)
    ) {
        if (!hasData) {
            Text(
                if (mounted) {
                    Strings.get(R.string.todavia_no_hay_nada_precargado_se)
                } else {
                    Strings.get(R.string.monta_un_servidor_para_poder_precargarlo)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant
            )
        } else {
            val s = status!!
            val pct = (s.fraction * 100).roundToInt()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    when {
                        s.running -> Strings.get(R.string.precargando)
                        s.finished -> Strings.get(R.string.listo)
                        else -> Strings.get(R.string.incompleta)
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                Text("$pct%", style = MaterialTheme.typography.titleMedium, color = scheme.primary)
            }
            LinearProgressIndicator(
                progress = { s.fraction },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
            )
            Text(
                Strings.get(R.string.mb_archivos, s.doneMb, s.selectedMb, s.doneFiles, s.selectedFiles) +
                    if (s.totalFiles > s.selectedFiles) Strings.get(R.string.de_en_el_remoto, s.totalFiles) else "",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
            if (s.finished) {
                Text(
                    Strings.get(R.string.todo_en_cache_local_se_comporta),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.primary
                )
            } else if (s.totalFiles > s.selectedFiles) {
                Text(
                    Strings.get(R.string.el_remoto_tiene_mas_archivos_de),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }

        FilledTonalButton(
            onClick = { vm.preloadNow() },
            enabled = mounted && status?.running != true,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Icon(AppIcons.Download, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(
                if (status?.finished == true) Strings.get(R.string.precargar_de_nuevo) else Strings.get(R.string.precargar_ahora),
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}
