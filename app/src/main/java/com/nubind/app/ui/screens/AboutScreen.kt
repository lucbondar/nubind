package com.nubind.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nubind.app.BindViewModel
import com.nubind.app.BuildConfig
import com.nubind.app.root.RootShell
import com.nubind.app.ui.components.AnimatedLogo
import com.nubind.app.ui.components.AppIcons
import com.nubind.app.ui.components.DualPaneContentWidth
import com.nubind.app.ui.components.ScreenContainer
import com.nubind.app.ui.components.SectionCard
import com.nubind.app.ui.components.rememberIsDualPane
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    val dualPane = rememberIsDualPane()

    ScreenContainer(
        title = "Acerca de",
        maxContentWidth = if (dualPane) DualPaneContentWidth else null
    ) {
        // Cabecera: logo + nombre + versión. En vertical ocupa todo el ancho;
        // en apaisado ocupa la mitad y "Qué hace" va a su derecha.
        if (dualPane) {
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                HeaderCard(modifier = Modifier.weight(1f).fillMaxHeight())
                Column(modifier = Modifier.weight(1f)) { WhatItDoesCard() }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) { LinksCard(uriHandler) }
                Column(modifier = Modifier.weight(1f)) { SystemCard(vm, rcloneVersion) }
            }
        } else {
            HeaderCard(modifier = Modifier.fillMaxWidth())
            WhatItDoesCard()
            SystemCard(vm, rcloneVersion)
            LinksCard(uriHandler)
        }

        Text(
            "Usa rclone (licencia MIT), libsu (Apache 2.0) y Haze (Apache 2.0).",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        )
    }
}

@Composable
private fun HeaderCard(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.primaryContainer,
        contentColor = scheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(28.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
        ) {
            AnimatedLogo()
            Text("Nubind", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Text(
                "Versión ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun WhatItDoesCard() {
    SectionCard(
        title = "Qué hace",
        icon = Icons.Default.Info,
        subtitle = "Monta un servidor FTP, Google Drive o un bucket S3 (Oracle Cloud, Amazon S3, " +
            "Cloudflare R2 y compatibles) con rclone y lo muestra como una carpeta más de tu " +
            "almacenamiento interno, para que cualquier app pueda usarlo."
    ) {
        Text(
            "1. Agrega un servidor en la pestaña Servidores.\n" +
                "2. Elige la carpeta de destino en Inicio.\n" +
                "3. Toca Montar: los archivos remotos aparecen ahí.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SystemCard(vm: BindViewModel, rcloneVersion: String?) {
    SectionCard(title = "Sistema", icon = Icons.Default.Build) {
        InfoRow("Acceso root", when (vm.rootGranted) {
            true -> "Concedido"
            false -> "No disponible"
            null -> "Comprobando…"
        })
        InfoRow("rclone", rcloneVersion ?: "—")
        InfoRow("Interfaz", "Jetpack Compose · Material 3 Expressive")
    }
}

@Composable
private fun LinksCard(uriHandler: UriHandler) {
    SectionCard(title = "Enlaces", icon = Icons.Default.Share) {
        FilledTonalButton(onClick = { uriHandler.openUri(REPO_URL) }, modifier = Modifier.fillMaxWidth()) {
            Text("Código fuente en GitHub")
        }
        OutlinedButton(onClick = { uriHandler.openUri(RCLONE_URL) }, modifier = Modifier.fillMaxWidth()) {
            Text("Sitio de rclone")
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
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.End, modifier = Modifier.padding(start = 16.dp))
    }
}
