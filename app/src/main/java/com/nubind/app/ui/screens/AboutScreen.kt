package com.nubind.app.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nubind.app.BindViewModel
import com.nubind.app.BuildConfig
import com.nubind.app.root.AppUpdateState
import com.nubind.app.root.RootShell
import com.nubind.app.ui.components.AnimatedLogo
import com.nubind.app.ui.theme.AppMotion
import com.nubind.app.ui.components.AppIcons
import com.nubind.app.ui.components.DualPaneContentWidth
import com.nubind.app.ui.components.ScreenContainer
import com.nubind.app.ui.components.SectionCard
import com.nubind.app.ui.components.UpdateReminderChip
import com.nubind.app.ui.components.UpdateNotices
import com.nubind.app.ui.components.updateHeaderColors
import com.nubind.app.ui.components.rememberIsDualPane
import com.nubind.app.ui.theme.updateGreenPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.nubind.app.R
import com.nubind.app.Strings

private const val REPO_URL = "https://github.com/lucbondar/nubind"
private const val RCLONE_URL = "https://rclone.org"
private const val X_URL = "https://x.com/cruzmartinlbdt"
private const val TELEGRAM_URL = "https://t.me/lcruz_23"

@Composable
fun AboutScreen(vm: BindViewModel) {
    val scheme = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current

    // La versión de rclone se pide con root; si no hay root queda "—".
    var rcloneVersion by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(vm.rootGranted) {
        if (vm.rootGranted == true) {
            rcloneVersion = withContext(Dispatchers.IO) { RootShell.rcloneVersion() }
        }
    }

    // Al entrar a Acerca de se busca de nuevo, en silencio: así una actualización recién
    // publicada aparece sin esperar a la búsqueda periódica.
    LaunchedEffect(Unit) { vm.refreshUpdates(force = true) }

    val dualPane = rememberIsDualPane()

    ScreenContainer(
        title = Strings.get(R.string.acerca_de),
        maxContentWidth = if (dualPane) DualPaneContentWidth else null,
        // Deslizar hacia abajo busca actualizaciones (con aviso si falla la conexión).
        refreshing = vm.appUpdate is AppUpdateState.Checking,
        onRefresh = { vm.checkForUpdates(manual = true) }
    ) {
        // Cabecera: logo + nombre + versión. En vertical ocupa todo el ancho;
        // en apaisado ocupa la mitad y "Qué hace" va a su derecha.
        if (dualPane) {
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                HeaderCard(vm, modifier = Modifier.weight(1f).fillMaxHeight())
                Column(modifier = Modifier.weight(1f)) { Entrance(1) { WhatItDoesCard() } }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) { Entrance(2) { SystemCard(vm, rcloneVersion) } }
                Column(modifier = Modifier.weight(1f)) { Entrance(3) { LinksCard(uriHandler) } }
            }
        } else {
            HeaderCard(vm, modifier = Modifier.fillMaxWidth())
            Entrance(1) { WhatItDoesCard() }
            Entrance(2) { SystemCard(vm, rcloneVersion) }
            Entrance(3) { LinksCard(uriHandler) }
        }

        Entrance(4) {
            Text(
                Strings.get(R.string.usa_rclone_licencia_mit_libsu_apache),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            )
        }
    }
}

/**
 * Entrada escalonada de las tarjetas: aparecen una tras otra subiendo un poco con un
 * resorte suave. Solo mueve y desvanece (no cambia el tamaño), así el contenido no salta
 * mientras llegan. La cabecera con el logo no pasa por aquí: el icono queda intacto.
 */
@Composable
private fun Entrance(index: Int, content: @Composable () -> Unit) {
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

@Composable
private fun HeaderCard(vm: BindViewModel, modifier: Modifier = Modifier) {
    // Degradado Monet; verde con actualización/reinicio pendiente, ámbar con el aviso de desfase (ver updateHeaderColors).
    val colors = updateHeaderColors(vm.appUpdate, vm.showRebootCard, vm.moduleNotice != null)
    Surface(
        color = Color.Transparent,
        contentColor = colors.content,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier
    ) {
        Box(Modifier.background(Brush.linearGradient(listOf(colors.start, colors.end)))) {
            Column(
                modifier = Modifier.padding(28.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
            ) {
                AnimatedLogo()
                Text("Nubind", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                // Versión en píldora translúcida del color del texto de la tarjeta: la de la app y,
                // con root y módulo instalado, debajo la del módulo (llega después, al confirmar root).
                Surface(
                    color = LocalContentColor.current.copy(alpha = 0.14f),
                    contentColor = LocalContentColor.current,
                    shape = CircleShape,
                    modifier = Modifier.animateContentSize(animationSpec = AppMotion.spatial())
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            Strings.get(R.string.version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                            style = MaterialTheme.typography.titleSmall
                        )
                        vm.moduleVersion?.let {
                            Text(
                                Strings.get(R.string.version_modulo, it),
                                style = MaterialTheme.typography.labelMedium,
                                color = LocalContentColor.current.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
                // Actualizador de la app y aviso de desfase con el módulo KSU.
                UpdateNotices(vm, Modifier.fillMaxWidth().padding(top = 6.dp))
            }
            // Recordatorio del aviso pospuesto (reinicio o desfase), fijo arriba a la derecha de la tarjeta.
            UpdateReminderChip(vm, Modifier.align(Alignment.TopEnd).padding(12.dp))
        }
    }
}

@Composable
private fun WhatItDoesCard() {
    SectionCard(
        title = Strings.get(R.string.que_hace),
        icon = Icons.Default.Info,
        subtitle = Strings.get(R.string.monta_un_servidor_ftp_google_drive)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // El texto trae los pasos como "1. ...\n2. ...": se quita la numeración y se
            // dibuja cada paso con su insignia.
            Strings.get(R.string.s_1_agrega_un_servidor_en_la)
                .lines()
                .filter { it.isNotBlank() }
                .forEachIndexed { i, line ->
                    StepRow(i + 1, line.replace(Regex("^\\s*\\d+[.)]\\s*"), ""))
                }
        }
    }
}

@Composable
private fun StepRow(number: Int, text: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier.size(32.dp).background(scheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(number.toString(), style = MaterialTheme.typography.labelLarge, color = scheme.onPrimaryContainer)
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SystemCard(vm: BindViewModel, rcloneVersion: String?) {
    SectionCard(title = Strings.get(R.string.sistema), icon = Icons.Default.Build) {
        val scheme = MaterialTheme.colorScheme
        InfoRow(
            Strings.get(R.string.acceso_root),
            when (vm.rootGranted) {
                true -> Strings.get(R.string.concedido)
                false -> Strings.get(R.string.no_disponible)
                null -> Strings.get(R.string.comprobando)
            },
            // Punto de estado: verde con root, rojo sin él, gris mientras se comprueba.
            dot = when (vm.rootGranted) {
                true -> updateGreenPalette().accent
                false -> scheme.error
                null -> scheme.outline
            }
        )
        InfoRow("rclone", rcloneVersion ?: "—")
        InfoRow(Strings.get(R.string.interfaz), "Jetpack Compose · Material 3 Expressive")
    }
}

@Composable
private fun LinksCard(uriHandler: UriHandler) {
    SectionCard(title = Strings.get(R.string.enlaces), icon = Icons.Default.Share) {
        FilledTonalButton(onClick = { uriHandler.openUri(REPO_URL) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(Strings.get(R.string.codigo_fuente_en_github))
        }
        OutlinedButton(onClick = { uriHandler.openUri(RCLONE_URL) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(Strings.get(R.string.sitio_de_rclone))
        }
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally)
        ) {
            SocialButton(AppIcons.X, "X (@cruzmartinlbdt)") { uriHandler.openUri(X_URL) }
            SocialButton(AppIcons.Telegram, "Telegram (@lcruz_23)") { uriHandler.openUri(TELEGRAM_URL) }
        }
    }
}

@Composable
private fun SocialButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = scheme.surfaceContainerHighest,
        contentColor = scheme.onSurfaceVariant,
        modifier = Modifier.size(40.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, dot: Color? = null) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        // Valor en píldora; con [dot] lleva un punto de estado delante.
        Surface(
            color = scheme.surfaceContainerHighest,
            shape = CircleShape,
            modifier = Modifier.padding(start = 16.dp).weight(1f, fill = false)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (dot != null) Box(Modifier.size(8.dp).background(dot, CircleShape))
                Text(value, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.End, modifier = Modifier.weight(1f, fill = false))
            }
        }
    }
}
