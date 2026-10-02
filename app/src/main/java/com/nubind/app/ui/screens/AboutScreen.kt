package com.nubind.app.ui.screens

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nubind.app.BindViewModel
import com.nubind.app.BuildConfig
import com.nubind.app.root.RootShell
import com.nubind.app.ui.components.AppIcons
import com.nubind.app.ui.components.DualPaneContentWidth
import com.nubind.app.ui.components.ScreenContainer
import com.nubind.app.ui.components.SectionCard
import com.nubind.app.ui.components.rememberIsDualPane
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
        // Cabecera: logo + nombre + versión. Ocupa todo el ancho siempre,
        // arriba de las columnas (igual que la tarjeta Montado/Desmontado
        // en Inicio).
        Surface(
            color = scheme.primaryContainer,
            contentColor = scheme.onPrimaryContainer,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(28.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AnimatedLogo()
                Text("Nubind", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Text(
                    "Versión ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        // En apaisado (o tablet), "Qué hace" y "Enlaces" a la izquierda,
        // "Sistema" a la derecha — mismo criterio que Inicio: lo
        // informativo/estático de un lado, el estado en vivo del otro.
        if (dualPane) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    WhatItDoesCard()
                    LinksCard(uriHandler)
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    SystemCard(vm, rcloneVersion)
                }
            }
        } else {
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

// Logo animado: cada pocos segundos la nube da un saltito con squash & stretch
// (se agacha, salta y cae con rebote). Tocarlo lo repite al momento. Si el
// usuario desactivó las animaciones del sistema (escala 0), se queda quieto.
private const val LOGO_FIRST_DELAY_MS = 1500L
private const val LOGO_INTERVAL_MS = 6000L

@Composable
private fun AnimatedLogo() {
    val context = LocalContext.current
    val hopPx = with(LocalDensity.current) { 8.dp.toPx() }
    val offsetY = remember { Animatable(0f) }
    val squashX = remember { Animatable(1f) }
    val squashY = remember { Animatable(1f) }
    val tilt = remember { Animatable(0f) }
    var replay by remember { mutableIntStateOf(0) }

    LaunchedEffect(replay) {
        val animationsOn = Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) > 0f
        if (!animationsOn) return@LaunchedEffect
        // Un toque reinicia el salto desde la pose de reposo.
        offsetY.snapTo(0f); squashX.snapTo(1f); squashY.snapTo(1f); tilt.snapTo(0f)
        delay(if (replay == 0) LOGO_FIRST_DELAY_MS else 0L)
        while (isActive) {
            // 1) Se agacha.
            coroutineScope {
                launch { squashY.animateTo(0.9f, tween(110)) }
                launch { squashX.animateTo(1.07f, tween(110)) }
            }
            // 2) Salta, estirándose y ladeándose un poco.
            coroutineScope {
                launch { offsetY.animateTo(-hopPx, tween(200, easing = FastOutSlowInEasing)) }
                launch { squashY.animateTo(1.1f, tween(200)) }
                launch { squashX.animateTo(0.94f, tween(200)) }
                launch { tilt.animateTo(-6f, tween(200)) }
            }
            // 3) Cae y rebota hasta asentarse.
            coroutineScope {
                launch { offsetY.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = 450f)) }
                launch { squashY.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 450f)) }
                launch { squashX.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 450f)) }
                launch { tilt.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 400f)) }
            }
            delay(LOGO_INTERVAL_MS)
        }
    }

    Box(
        Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF2A8DE0))
            .pointerInput(Unit) { detectTapGestures { replay++ } },
        contentAlignment = Alignment.Center
    ) {
        Image(
            AppIcons.Logo,
            contentDescription = null,
            modifier = Modifier
                .size(96.dp)
                .graphicsLayer {
                    translationY = offsetY.value
                    scaleX = squashX.value
                    scaleY = squashY.value
                    rotationZ = tilt.value
                    // Pivote en la base de la nube, para que el squash apoye en el "suelo".
                    transformOrigin = TransformOrigin(0.5f, 0.72f)
                }
        )
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
