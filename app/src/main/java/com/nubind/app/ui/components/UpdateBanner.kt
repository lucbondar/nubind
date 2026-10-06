package com.nubind.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nubind.app.BindViewModel
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.nubind.app.R
import com.nubind.app.Strings
import com.nubind.app.root.AppUpdateState
import com.nubind.app.root.AppUpdater
import com.nubind.app.root.ModuleFlashState
import com.nubind.app.root.ModuleInfo
import com.nubind.app.root.UpdateInfo
import com.nubind.app.ui.theme.AppMotion
import com.nubind.app.ui.theme.StatusPalette
import com.nubind.app.ui.theme.syncAmberPalette
import com.nubind.app.ui.theme.updateGreenPalette

private enum class UpdateKind { CHECKING, AVAILABLE, DOWNLOADING, INSTALLING, FAILED }

private fun AppUpdateState.kind(): UpdateKind? = when (this) {
    AppUpdateState.Idle -> null
    AppUpdateState.Checking -> UpdateKind.CHECKING
    // Al día: no se muestra nada (sin tarjeta "Estás al día" ni botón de buscar de nuevo).
    AppUpdateState.UpToDate -> null
    is AppUpdateState.Available -> UpdateKind.AVAILABLE
    is AppUpdateState.Downloading -> UpdateKind.DOWNLOADING
    AppUpdateState.Installing -> UpdateKind.INSTALLING
    is AppUpdateState.Failed -> UpdateKind.FAILED
}

/** Verde mientras hay una actualización por instalar, en curso de bajar o instalándose. */
private fun AppUpdateState.isGreen(): Boolean =
    this is AppUpdateState.Available || this is AppUpdateState.Downloading || this is AppUpdateState.Installing

/**
 * Paleta de estado con la que se tiñe la cabecera ahora mismo: verde (actualización por instalar o
 * reinicio pendiente), ámbar (aviso de desfase a la vista; el verde manda si coinciden) o null
 * (degradado Monet). La usan la cabecera y las tarjetas de dentro, para que todo comparta color.
 */
@Composable
private fun headerStatusPalette(
    state: AppUpdateState,
    rebootPending: Boolean,
    moduleBehind: Boolean
): StatusPalette? = when {
    state.isGreen() || rebootPending -> updateGreenPalette()
    moduleBehind -> syncAmberPalette()
    else -> null
}

/**
 * Colores de la tarjeta de cabecera: [start] -> [end] es el degradado de estado (verde/ámbar), que
 * se pinta como velo con opacidad [tint] (0 = se ve el cielo, [HEADER_TINT] = estado a la vista);
 * [content] es el color del texto.
 */
@Immutable
class HeaderColors(val start: Color, val end: Color, val content: Color, val tint: Float)

/** Opacidad del velo de estado sobre el cielo: casi pleno, deja asomar las nubes. */
private const val HEADER_TINT = 0.9f

/**
 * Colores de la tarjeta de cabecera de Acerca de. Normalmente se ve el cielo dinámico
 * ([SkyBackground]) con el texto en [skyContent] (claro u oscuro según la hora). Cuando se
 * encuentra una actualización o el módulo quedó flasheado a la espera de reiniciar
 * ([rebootPending]), un velo de degradado verde de estado (paleta fija, para que el verde siga
 * siendo verde a cualquier hora) cubre el cielo con transición animada. Si el aviso de desfase del
 * módulo está a la vista ([moduleBehind]), el velo es ámbar; el verde manda si coinciden. Al
 * terminar el estado el velo se desvanece con sus últimos colores. Solo cambia el aspecto: no toca
 * el flujo del actualizador.
 */
@Composable
fun updateHeaderColors(
    state: AppUpdateState,
    rebootPending: Boolean = false,
    moduleBehind: Boolean = false,
    skyContent: Color
): HeaderColors {
    val status = headerStatusPalette(state, rebootPending, moduleBehind)
    val held = remember { arrayOfNulls<StatusPalette>(1) }
    if (status != null) held[0] = status
    val shown = status ?: held[0] ?: updateGreenPalette()
    val tint by animateFloatAsState(
        if (status != null) HEADER_TINT else 0f,
        animationSpec = tween(500), label = "headerTint"
    )
    val start by animateColorAsState(
        shown.container, animationSpec = tween(500), label = "headerStart"
    )
    // Verde y desfase a la vez: el degradado termina virando a ámbar, así el aviso ámbar de
    // dentro no se siente un color ajeno pegado sobre un fondo verde.
    val amber = syncAmberPalette()
    val both = moduleBehind && (state.isGreen() || rebootPending)
    val base = lerp(shown.container, shown.accent, 0.25f)
    val end by animateColorAsState(
        if (both) lerp(base, amber.container, 0.75f) else base,
        animationSpec = tween(500), label = "headerEnd"
    )
    val content by animateColorAsState(
        status?.onContainer ?: skyContent,
        animationSpec = tween(500), label = "headerContent"
    )
    return HeaderColors(start, end, content, tint)
}

/**
 * Avisos del banner de Acerca de, estilo Material 3 Expressive: insignias en
 * forma "cookie" que giran, indicadores ondulados, botones que se aplastan al
 * tocarlos y transiciones con resortes.
 *
 *  - Tarjeta del actualizador de la app (buscando, al día, disponible,
 *    descargando, instalando, error).
 *  - Aviso de desfase: si el módulo KSU instalado trae un APK más viejo que
 *    la app (por ejemplo tras actualizar la app desde aquí), la app puede
 *    funcionar, pero la actualización solo queda completa al descargar el
 *    módulo desde la app de KernelSU.
 *
 * Va dentro de la tarjeta de cabecera (cielo dinámico), por eso los fondos
 * son translúcidos o de color pleno sobre ella.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdateNotices(vm: BindViewModel, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val state = vm.appUpdate
    val notice = vm.moduleNotice
    // Misma paleta que la cabecera: la tarjeta del actualizador se adapta a su color.
    val headerStatus = headerStatusPalette(state, vm.showRebootCard, notice != null)
    // Con la cabecera verde, la tarjeta ámbar lleva su propio fondo pleno y borde (un velo
    // ámbar sobre verde se ve turbio y el texto pierde contraste).
    val noticeOverGreen = notice != null && (state.isGreen() || vm.showRebootCard)

    Column(
        modifier = modifier.animateContentSize(animationSpec = AppMotion.spatial()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // El contenido se anima por "tipo" de estado, no por cada tick de progreso.
        AnimatedContent(
            targetState = state.kind(),
            transitionSpec = {
                (fadeIn(animationSpec = AppMotion.effects()) +
                    scaleIn(animationSpec = AppMotion.spatial(), initialScale = 0.92f)) togetherWith
                    fadeOut(animationSpec = AppMotion.effects())
            },
            label = "updateCard"
        ) { kind ->
            if (kind != null) {
                UpdateCard(
                    kind = kind,
                    state = vm.appUpdate,
                    headerStatus = headerStatus,
                    onUpdate = { info ->
                        // Con root se instala sola; sin root se baja el APK con el navegador.
                        if (vm.rootGranted == true) vm.installUpdate() else uriHandler.openUri(info.apkUrl)
                    },
                    onCheck = { vm.checkForUpdates(manual = true) }
                )
            }
        }

        AnimatedVisibility(
            visible = notice != null,
            enter = expandVertically(animationSpec = AppMotion.spatial()) + fadeIn(animationSpec = AppMotion.effects()),
            exit = shrinkVertically(animationSpec = AppMotion.spatial()) + fadeOut(animationSpec = AppMotion.effects())
        ) {
            // Al descartarse, notice pasa a null antes de terminar la salida: se conserva el último.
            val shown = remember { arrayOfNulls<ModuleInfo>(1) }
            if (notice != null) shown[0] = notice
            shown[0]?.let {
                ModuleNoticeCard(
                    module = it,
                    flash = vm.moduleFlash,
                    solid = noticeOverGreen,
                    onFlash = vm::flashModule,
                    onLater = vm::dismissModuleNotice
                )
            }
        }

        // Módulo ya flasheado: falta reiniciar para que se active.
        AnimatedVisibility(
            visible = vm.showRebootCard,
            enter = expandVertically(animationSpec = AppMotion.spatial()) + fadeIn(animationSpec = AppMotion.effects()),
            exit = shrinkVertically(animationSpec = AppMotion.spatial()) + fadeOut(animationSpec = AppMotion.effects())
        ) {
            ModuleRebootCard(onReboot = vm::rebootDevice, onLater = vm::postponeReboot)
        }
    }
}

/**
 * Recordatorio fijo (arriba a la derecha de la cabecera) cuando se pospuso con "Lo haré luego"
 * un aviso que sigue pendiente: el reinicio del módulo ya flasheado (píldora verde) o el
 * desfase del módulo (píldora ámbar). Al tocarla vuelve la tarjeta correspondiente. Nunca
 * hay dos a la vez: con reinicio pendiente el desfase ya se resolvió.
 */
@Composable
fun UpdateReminderChip(vm: BindViewModel, modifier: Modifier = Modifier) {
    val rebootNow = vm.rebootReminder
    val show = rebootNow || vm.moduleReminder
    // Al tocar la píldora el estado cambia antes de que acabe su salida (fade): sin esto, durante
    // ese instante se redibujaba con el texto y el color ámbar de "Módulo desfasado". Se conserva
    // el último tipo que estuvo visible.
    val lastKind = remember { booleanArrayOf(false) }
    if (show) lastKind[0] = rebootNow
    val reboot = lastKind[0]
    AnimatedVisibility(
        visible = show,
        modifier = modifier,
        enter = fadeIn(animationSpec = AppMotion.effects()) + scaleIn(animationSpec = AppMotion.spatial(), initialScale = 0.8f),
        exit = fadeOut(animationSpec = AppMotion.effects())
    ) {
        val p = if (reboot) updateGreenPalette() else syncAmberPalette()
        Surface(
            onClick = if (reboot) vm::showRebootCardAgain else vm::showModuleNoticeAgain,
            color = p.accent,
            contentColor = p.onAccent,
            shape = RoundedCornerShape(50)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    if (reboot) Icons.Default.RestartAlt else Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    Strings.get(if (reboot) R.string.upd_reinicio_pendiente else R.string.upd_desfase_pendiente),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tarjeta del actualizador
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UpdateCard(
    kind: UpdateKind,
    state: AppUpdateState,
    headerStatus: StatusPalette?,
    onUpdate: (UpdateInfo) -> Unit,
    onCheck: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    // Con la cabecera teñida (verde: actualización o reinicio pendiente; ámbar: desfase) todo el
    // bloque usa esa paleta, también "Buscando…"; sin tinte, el color dinámico del tema.
    val accent = headerStatus?.accent ?: scheme.primary
    val onAccent = headerStatus?.onAccent ?: scheme.onPrimary
    val base = headerStatus?.onContainer ?: LocalContentColor.current
    val (container, content) = when (kind) {
        // Disponible es lo importante: color pleno sobre la cabecera.
        UpdateKind.AVAILABLE -> accent to onAccent
        UpdateKind.FAILED -> scheme.errorContainer to scheme.onErrorContainer
        else -> base.copy(alpha = 0.10f) to base
    }

    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (kind) {
                    // Buscando: insignia con el icono real de refrescar girando.
                    UpdateKind.CHECKING -> CookieBadge(
                        icon = Icons.Default.Refresh,
                        shape = MaterialShapes.Cookie9Sided.toShape(),
                        background = accent,
                        glyph = onAccent,
                        spinning = true,
                        glyphSpinning = true
                    )
                    // Instalando: insignia con el icono real de actualización del sistema.
                    UpdateKind.INSTALLING -> CookieBadge(
                        icon = Icons.Default.SystemUpdate,
                        shape = MaterialShapes.Cookie9Sided.toShape(),
                        background = accent,
                        glyph = onAccent,
                        spinning = true
                    )
                    UpdateKind.AVAILABLE -> CookieBadge(
                        icon = AppIcons.Download,
                        shape = MaterialShapes.Cookie9Sided.toShape(),
                        background = onAccent,
                        glyph = accent,
                        spinning = true
                    )
                    UpdateKind.DOWNLOADING -> CookieBadge(
                        icon = AppIcons.Download,
                        shape = MaterialShapes.Cookie9Sided.toShape(),
                        background = accent,
                        glyph = onAccent,
                        spinning = true
                    )
                    UpdateKind.FAILED -> CookieBadge(
                        icon = Icons.Default.Warning,
                        shape = MaterialShapes.Sunny.toShape(),
                        background = scheme.error,
                        glyph = scheme.onError,
                        spinning = false
                    )
                }

                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val (title, detail) = when (kind) {
                        UpdateKind.CHECKING -> Strings.get(R.string.upd_buscando) to null
                        UpdateKind.AVAILABLE -> Strings.get(R.string.upd_disponible) to
                            ((state as? AppUpdateState.Available)?.info?.let {
                                Strings.get(R.string.upd_disponible_detalle, it.appVersion.ifEmpty { it.appVersionCode.toString() })
                            })
                        UpdateKind.DOWNLOADING -> Strings.get(R.string.upd_descargando) to null
                        UpdateKind.INSTALLING -> Strings.get(R.string.upd_instalando) to
                            Strings.get(R.string.upd_instalando_detalle)
                        UpdateKind.FAILED -> Strings.get(R.string.upd_fallo) to
                            (state as? AppUpdateState.Failed)?.message
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (detail != null) {
                        Text(detail, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
                    }
                }

            }

            when (kind) {
                UpdateKind.AVAILABLE -> (state as? AppUpdateState.Available)?.info?.let { info ->
                    var changesOpen by remember(info.appVersionCode) { mutableStateOf(false) }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SquishButton(
                            text = Strings.get(R.string.upd_actualizar_ahora),
                            colors = ButtonDefaults.buttonColors(containerColor = onAccent, contentColor = accent),
                            onClick = { onUpdate(info) },
                            modifier = Modifier.weight(1f)
                        )
                        if (info.changelogUrl != null) {
                            ChangelogPill(
                                open = changesOpen,
                                color = onAccent,
                                onClick = { changesOpen = !changesOpen }
                            )
                        }
                    }
                    if (info.changelogUrl != null) {
                        ChangelogList(
                            url = info.changelogUrl,
                            open = changesOpen,
                            color = onAccent
                        )
                    }
                }
                UpdateKind.DOWNLOADING -> {
                    val p = (state as? AppUpdateState.Downloading)?.progress ?: -1f
                    if (p < 0f) {
                        LinearWavyProgressIndicator(Modifier.fillMaxWidth(), color = accent, trackColor = accent.copy(alpha = 0.24f))
                    } else {
                        LinearWavyProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = accent, trackColor = accent.copy(alpha = 0.24f))
                        Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                    }
                }
                UpdateKind.FAILED -> {
                    val info = (state as? AppUpdateState.Failed)?.info
                    SquishButton(
                        text = Strings.get(R.string.upd_reintentar),
                        colors = ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError),
                        onClick = { if (info != null) onUpdate(info) else onCheck() }
                    )
                }
                else -> Unit
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Aviso de desfase módulo / app
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModuleNoticeCard(
    module: ModuleInfo,
    flash: ModuleFlashState,
    solid: Boolean,
    onFlash: () -> Unit,
    onLater: () -> Unit
) {
    val p = syncAmberPalette()
    val cardColor by animateColorAsState(
        if (solid) p.container else p.onContainer.copy(alpha = 0.10f),
        animationSpec = tween(400), label = "noticeCard"
    )
    val borderColor by animateColorAsState(
        if (solid) p.accent.copy(alpha = 0.45f) else Color.Transparent,
        animationSpec = tween(400), label = "noticeBorder"
    )
    val working = flash is ModuleFlashState.Downloading || flash is ModuleFlashState.Flashing

    // Va sobre la cabecera, que con el aviso a la vista se pone ámbar (ver updateHeaderColors):
    // velo translúcido del mismo ámbar, como la tarjeta de reinicio sobre el verde.
    Surface(
        color = cardColor,
        contentColor = p.onContainer,
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.5.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                CookieBadge(
                    icon = Icons.Default.Warning,
                    shape = MaterialShapes.Sunny.toShape(),
                    background = p.accent,
                    glyph = p.onAccent,
                    spinning = !working
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        Strings.get(
                            when (flash) {
                                is ModuleFlashState.Downloading -> R.string.upd_modulo_descargando
                                ModuleFlashState.Flashing -> R.string.upd_modulo_flasheando
                                else -> R.string.upd_modulo_titulo
                            }
                        ),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        if (flash is ModuleFlashState.Failed) Strings.get(R.string.upd_modulo_fallo_detalle, flash.message)
                        else Strings.get(R.string.upd_modulo_cuerpo),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 5
                    )
                    Text(
                        Strings.get(
                            R.string.upd_modulo_versiones,
                            AppUpdater.numericVersion(module.appVersion ?: module.version) ?: module.version,
                            com.nubind.app.BuildConfig.VERSION_NAME
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = p.onContainer.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            when (flash) {
                is ModuleFlashState.Downloading ->
                    if (flash.progress < 0f) {
                        LinearWavyProgressIndicator(Modifier.fillMaxWidth(), color = p.accent, trackColor = p.accent.copy(alpha = 0.24f))
                    } else {
                        LinearWavyProgressIndicator(
                            progress = { flash.progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = p.accent,
                            trackColor = p.accent.copy(alpha = 0.24f)
                        )
                        Text("${(flash.progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                    }
                ModuleFlashState.Flashing ->
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth(), color = p.accent, trackColor = p.accent.copy(alpha = 0.24f))
                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SquishButton(
                            text = Strings.get(if (flash is ModuleFlashState.Failed) R.string.upd_reintentar else R.string.upd_modulo_flashear),
                            colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.onAccent),
                            onClick = onFlash
                        )
                        SquishButton(
                            text = Strings.get(R.string.upd_luego),
                            colors = laterButtonColors(p),
                            onClick = onLater
                        )
                    }
                }
            }
        }
    }
}

/**
 * Módulo flasheado con éxito: se activa al reiniciar. Va sobre la cabecera, que en
 * ese caso se pone verde (ver [updateHeaderColors]), así que la tarjeta es solo un
 * velo translúcido del mismo verde y el único color pleno es el botón.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModuleRebootCard(onReboot: () -> Unit, onLater: () -> Unit) {
    val p = updateGreenPalette()
    Surface(
        color = p.onContainer.copy(alpha = 0.10f),
        contentColor = p.onContainer,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Icono de reinicio (no otro check: "Estás al día" ya usa uno).
                CookieBadge(
                    icon = Icons.Default.RestartAlt,
                    shape = MaterialShapes.Cookie9Sided.toShape(),
                    background = p.accent,
                    glyph = p.onAccent,
                    spinning = false
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(Strings.get(R.string.upd_modulo_listo_titulo), style = MaterialTheme.typography.titleMedium)
                    Text(Strings.get(R.string.upd_modulo_listo_cuerpo), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SquishButton(
                    text = Strings.get(R.string.upd_reiniciar),
                    colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.onAccent),
                    onClick = onReboot
                )
                SquishButton(
                    text = Strings.get(R.string.upd_luego),
                    colors = laterButtonColors(p),
                    onClick = onLater
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Piezas expressive
// ---------------------------------------------------------------------------

/**
 * Píldora "Cambios" a la derecha de "Actualizar ahora": se aprieta al pulsarla, el chevron gira
 * con resorte y, abierta, se llena un poco más. [color] es el del texto/fondo del botón principal.
 */
@Composable
private fun ChangelogPill(open: Boolean, color: Color, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium),
        label = "pillSquish"
    )
    val fill by animateColorAsState(
        color.copy(alpha = if (open) 0.34f else 0.18f),
        animationSpec = tween(250), label = "pillFill"
    )
    val rotation by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "pillChevron"
    )
    Surface(
        onClick = onClick,
        interactionSource = source,
        color = fill,
        contentColor = color,
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .height(52.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(Strings.get(R.string.upd_cambios), style = MaterialTheme.typography.labelLarge)
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = if (open) Strings.get(R.string.contraer) else Strings.get(R.string.expandir),
                modifier = Modifier.rotate(rotation)
            )
        }
    }
}

/**
 * Lista desplegable con los últimos 10 cambios de changelog.md. Se baja la primera vez que se abre
 * y se conserva mientras la tarjeta siga a la vista. Las líneas empiezan por la versión
 * ("2.5.41: ..."), que se resalta en negrita.
 */
@Composable
private fun ChangelogList(url: String, open: Boolean, color: Color) {
    var lines by remember(url) { mutableStateOf<List<String>?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(open, url) {
        if (open && lines == null) {
            val got = withContext(Dispatchers.IO) { AppUpdater.fetchChangelog(url, 10) }
            if (got.isNullOrEmpty()) failed = true else { failed = false; lines = got }
        }
    }
    AnimatedVisibility(
        visible = open,
        enter = expandVertically(animationSpec = AppMotion.spatial()) + fadeIn(animationSpec = AppMotion.effects()),
        exit = shrinkVertically(animationSpec = AppMotion.spatial()) + fadeOut(animationSpec = AppMotion.effects())
    ) {
        Surface(
            color = color.copy(alpha = 0.14f),
            contentColor = color,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val shown = lines
                when {
                    shown != null -> shown.forEach { line -> ChangelogRow(line, color) }
                    failed -> Text(Strings.get(R.string.upd_cambios_error), style = MaterialTheme.typography.bodyMedium)
                    else -> LinearWavyProgressIndicator(
                        Modifier.fillMaxWidth(), color = color, trackColor = color.copy(alpha = 0.24f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChangelogRow(line: String, color: Color) {
    val m = Regex("""^(v?\d+(?:\.\d+)+)\s*:\s*(.*)$""").find(line)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .padding(top = 7.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.7f))
        )
        Text(
            text = if (m != null) buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
                append("  ")
                append(m.groupValues[2])
            } else AnnotatedString(line),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * Botón secundario "Lo haré luego": mismo tamaño y forma que el principal (queda alineado
 * justo debajo), pero tonal: velo del color del texto de la tarjeta, sin color pleno.
 */
@Composable
private fun laterButtonColors(p: StatusPalette) = ButtonDefaults.buttonColors(
    containerColor = p.onContainer.copy(alpha = 0.16f),
    contentColor = p.onContainer
)

/**
 * Insignia expressive: una forma de Material (cookie, sunny…) que gira despacio
 * y "respira" cuando [spinning], con el icono quieto encima (o girando con
 * [glyphSpinning], para acciones en curso como buscar).
 */
@Composable
internal fun CookieBadge(
    icon: ImageVector,
    shape: Shape,
    background: Color,
    glyph: Color,
    spinning: Boolean,
    modifier: Modifier = Modifier,
    glyphSpinning: Boolean = false,
    size: Dp = 48.dp
) {
    val transition = rememberInfiniteTransition(label = "updateBadge")
    val glyphSpin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "glyphSpin"
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(14000, easing = LinearEasing)),
        label = "spin"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            tween(1500, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "pulse"
    )
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (spinning) {
                        rotationZ = spin
                        scaleX = pulse
                        scaleY = pulse
                    }
                }
                .background(background, shape)
        )
        Icon(
            icon,
            contentDescription = null,
            tint = glyph,
            modifier = Modifier
                .size(size / 2)
                .graphicsLayer { if (glyphSpinning) rotationZ = glyphSpin }
        )
    }
}

/** Botón en píldora que se aplasta con rebote al tocarlo (el "tacto" de Expressive). */
@Composable
private fun SquishButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: androidx.compose.material3.ButtonColors = ButtonDefaults.buttonColors()
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium),
        label = "squish"
    )
    Button(
        onClick = onClick,
        colors = colors,
        interactionSource = source,
        shape = RoundedCornerShape(50),
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}
