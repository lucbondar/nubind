package com.nubind.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nubind.app.root.RemoteProfile
import com.nubind.app.root.RemoteType
import com.nubind.app.root.S3Provider
import com.nubind.app.root.s3Provider
import com.nubind.app.root.formatCacheKb
import com.nubind.app.root.subtitle
import com.nubind.app.ui.theme.AppMotion
import com.nubind.app.R
import com.nubind.app.Strings
import kotlin.math.abs
import kotlinx.coroutines.delay

// Parte visible de una tarjeta cerrada, y cuánto se mete bajo la siguiente
// (para que no se vea el fondo entre las esquinas redondeadas).
private val PeekHeight = 88.dp
private val UnderlapHeight = 32.dp
private val OpenHeight = 280.dp
// Sin caché no se muestra su fila y la tarjeta abierta es más baja.
private val OpenHeightNoCache = 216.dp

// Resorte expressive de las tarjetas: con rebote visible, y más flojo cuanto más
// lejos está la tarjeta de la seleccionada, así el movimiento viaja por la pila
// como una ola en vez de moverse todo a la vez.
private fun <T> cardSpring(distance: Int, bounce: Float = 0.6f): FiniteAnimationSpec<T> =
    spring(dampingRatio = bounce, stiffness = 440f - 50f * distance.coerceAtMost(5))

// Escala del icono sin seleccionar respecto a seleccionado (22 dp / 26 dp).
private const val IconRestScale = 0.846f

/**
 * Pila de tarjetas tipo cartera: las cerradas asoman solo su franja superior y
 * la seleccionada se abre completa, empujando a las de abajo con un resorte.
 * Tocar una tarjeta cerrada la selecciona.
 */
@Composable
fun ServerCardStack(
    profiles: List<RemoteProfile>,
    selected: String?,
    onSelect: (String) -> Unit,
    onEdit: (RemoteProfile) -> Unit,
    onDelete: (RemoteProfile) -> Unit,
    cacheKbOf: (RemoteProfile) -> Long,
    canClearCache: (RemoteProfile) -> Boolean,
    onClearCache: (RemoteProfile) -> Unit,
    modifier: Modifier = Modifier
) {
    // Alto real de cada tarjeta abierta, medido de su contenido (en apaisado las
    // columnas son angostas y los textos se parten en más líneas que en vertical).
    // Los 280/216 dp son el mínimo; si el contenido pide más, la tarjeta crece.
    val measuredOpen = remember { mutableStateMapOf<String, Dp>() }
    val tops = ArrayList<Dp>()
    val heights = ArrayList<Dp>()
    var y = 0.dp
    profiles.forEach { p ->
        val open = p.name == selected
        val minOpen = if (cacheKbOf(p) > 0) OpenHeight else OpenHeightNoCache
        val openHeight = maxOf(minOpen, measuredOpen[p.name] ?: 0.dp)
        tops.add(y)
        heights.add(if (open) openHeight else PeekHeight + UnderlapHeight)
        y += if (open) openHeight else PeekHeight
    }
    val totalHeight = if (profiles.isEmpty()) 0.dp else tops.last() + heights.last()
    // Se lee solo en la fase de layout (nunca con `by` aquí): si se leyera en la
    // composición, cada fotograma del resorte recompondría la pila entera.
    val animatedTotal = animateDpAsState(totalHeight, spring(dampingRatio = 0.7f, stiffness = 380f), label = "stackHeight")

    val selectedIndex = profiles.indexOfFirst { it.name == selected }

    Box(
        modifier.fillMaxWidth().layout { measurable, constraints ->
            val h = animatedTotal.value.roundToPx().coerceIn(constraints.minHeight, constraints.maxHeight)
            val placeable = measurable.measure(constraints.copy(minHeight = 0))
            layout(placeable.width, h) { placeable.place(0, 0) }
        }
    ) {
        profiles.forEachIndexed { index, profile ->
            key(profile.name) {
                StackCard(
                    profile = profile,
                    index = index,
                    distance = if (selectedIndex < 0) 0 else abs(index - selectedIndex),
                    isSelected = profile.name == selected,
                    top = tops[index],
                    height = heights[index],
                    onSelect = { onSelect(profile.name) },
                    onEdit = { onEdit(profile) },
                    onDelete = { onDelete(profile) },
                    cacheKb = cacheKbOf(profile),
                    canClearCache = canClearCache(profile),
                    onClearCache = { onClearCache(profile) },
                    onOpenHeight = { h -> if (measuredOpen[profile.name] != h) measuredOpen[profile.name] = h }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StackCard(
    profile: RemoteProfile,
    index: Int,
    distance: Int,
    isSelected: Boolean,
    top: Dp,
    height: Dp,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    cacheKb: Long,
    canClearCache: Boolean,
    onClearCache: () -> Unit,
    onOpenHeight: (Dp) -> Unit
) {
    val density = LocalDensity.current
    // En varias columnas (apaisado) la tarjeta es angosta: Editar/Eliminar van solo con icono.
    val iconOnly = rememberIsDualPane()
    // Posición y alto: State sin delegar, leídos solo en layout (ver ServerCardStack).
    val animatedTop = animateDpAsState(top, cardSpring(distance), label = "cardTop")
    val animatedHeight = animateDpAsState(height, cardSpring(distance, 0.64f), label = "cardHeight")

    // Respuesta al toque: se hunde al pulsar y, al quedar seleccionada, "salta" con
    // rebote. Todo va por graphicsLayer (fase de dibujo), sin recomponer.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = remember { Animatable(1f) }
    val wasSelected = remember { mutableStateOf(isSelected) }
    LaunchedEffect(pressed) { scale.animateTo(if (pressed) 0.95f else 1f, spring(0.5f, 700f)) }
    LaunchedEffect(isSelected) {
        if (isSelected && !wasSelected.value) {
            scale.snapTo(0.94f)
            scale.animateTo(1f, spring(0.36f, 320f))
        }
        wasSelected.value = isSelected
    }
    // El icono crece con pop al seleccionarse (tamaño fijo de 26 dp, solo cambia la escala).
    val iconScale = remember { Animatable(if (isSelected) 1f else IconRestScale) }
    LaunchedEffect(isSelected) {
        iconScale.animateTo(if (isSelected) 1f else IconRestScale, spring(0.32f, 520f))
    }

    val scheme = MaterialTheme.colorScheme
    val isDrive = profile.type == RemoteType.DRIVE
    val isOracle = profile.s3Provider == S3Provider.ORACLE
    val isAws = profile.s3Provider == S3Provider.AWS
    val isCloudflare = profile.s3Provider == S3Provider.CLOUDFLARE
    val container = when {
        // Perfiles de Drive: color de marca propio y fijo, no la paleta
        // rotativa por posición que usan los demás perfiles.
        isDrive && isSelected -> DriveBrandBlue
        isDrive -> DriveBrandBlue.copy(alpha = 0.16f).compositeOver(scheme.surfaceContainerHighest)
        // Oracle: igual que Drive, con el color de su marca. Seleccionada es el
        // naranja pleno; sin seleccionar, una versión tenue del mismo tono.
        isOracle && isSelected -> OracleBrandRed
        isOracle -> OracleBrandRed.copy(alpha = 0.16f).compositeOver(scheme.surfaceContainerHighest)
        // AWS: igual, con su naranja. Seleccionada es el naranja pleno (con
        // texto y logo en azul oscuro); sin seleccionar, una versión tenue.
        isAws && isSelected -> AwsBrandOrange
        isAws -> AwsBrandOrange.copy(alpha = 0.16f).compositeOver(scheme.surfaceContainerHighest)
        // Cloudflare: igual que AWS, con su naranja (texto y logo en gris
        // oscuro sobre la tarjeta seleccionada; el blanco no da contraste).
        isCloudflare && isSelected -> CloudflareBrandOrange
        isCloudflare -> CloudflareBrandOrange.copy(alpha = 0.16f).compositeOver(scheme.surfaceContainerHighest)
        isSelected -> scheme.primary
        else -> when (index % 3) {
            0 -> scheme.secondaryContainer
            1 -> scheme.tertiaryContainer
            else -> scheme.surfaceContainerHighest
        }
    }
    val content = when {
        isDrive && isSelected -> Color.White
        isDrive -> scheme.onSurface
        isOracle && isSelected -> Color.White
        isOracle -> scheme.onSurface
        isAws && isSelected -> AwsBrandInk
        isAws -> scheme.onSurface
        isCloudflare && isSelected -> CloudflareBrandInk
        isCloudflare -> scheme.onSurface
        isSelected -> scheme.onPrimary
        else -> when (index % 3) {
            0 -> scheme.onSecondaryContainer
            1 -> scheme.onTertiaryContainer
            else -> scheme.onSurface
        }
    }
    // El fondo se pinta en la fase de dibujo (drawBehind) y el color del contenido
    // se lee dentro del contenido: ninguno recompone la tarjeta entera.
    val bg = animateColorAsState(container, AppMotion.effects(), label = "cardBg")
    val fgState = animateColorAsState(content, AppMotion.effects(), label = "cardFg")

    Surface(
        onClick = onSelect,
        enabled = !isSelected,
        interactionSource = interaction,
        shape = MaterialTheme.shapes.extraLarge,
        color = Color.Transparent,
        shadowElevation = if (isSelected) 8.dp else 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(0, animatedTop.value.roundToPx()) }
            .layout { measurable, constraints ->
                val h = animatedHeight.value.roundToPx().coerceIn(constraints.minHeight, constraints.maxHeight)
                val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .semantics { this.selected = isSelected }
    ) {
        val fg = fgState.value
        CompositionLocalProvider(LocalContentColor provides fg) {
        Box(Modifier.fillMaxSize().drawBehind { drawRect(bg.value) }) {
        Column(
            Modifier
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .onSizeChanged { if (isSelected) onOpenHeight(with(density) { it.height.toDp() }) }
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val icon = serverIconFor(profile)
                val iconModifier = Modifier.size(26.dp).graphicsLayer {
                    scaleX = iconScale.value
                    scaleY = iconScale.value
                }
                if (icon.branded) {
                    // El logo de Oracle es del mismo naranja que la tarjeta
                    // seleccionada: sobre ella se dibuja en blanco (tinte que
                    // conserva el hueco del óvalo) para que no desaparezca. El
                    // de AWS, igual: su sonrisa es del naranja de la tarjeta,
                    // así que sobre ella todo el logo va en azul oscuro. Sin
                    // seleccionar, AWS usa la variante de letras blancas si el
                    // fondo es oscuro (las letras azules no se verían).
                    val tint: Color? = when {
                        isOracle && isSelected -> Color.White
                        isAws && isSelected -> AwsBrandInk
                        // Cloudflare: sobre su naranja pleno, silueta en gris oscuro
                        // (el tinte conserva la ranura transparente del logo).
                        isCloudflare && isSelected -> CloudflareBrandInk
                        else -> null
                    }
                    Image(
                        icon.forBackground(container.luminance() < 0.5f),
                        contentDescription = null,
                        colorFilter = tint?.let { ColorFilter.tint(it) },
                        modifier = iconModifier
                    )
                } else if (profile.type == RemoteType.S3) {
                    // S3 genérico: nube de una tinta (FTP no lleva icono en la tarjeta).
                    Icon(icon.vector, contentDescription = null, modifier = iconModifier)
                }
                Text(
                    text = profile.name,
                    style = if (isSelected) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = profile.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            AnimatedVisibility(visible = isSelected, enter = fadeIn(), exit = fadeOut()) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    Reveal(shown = isSelected, order = 0) { Column {
                    if (profile.type == RemoteType.DRIVE) {
                        val drive = profile.drive
                        Text(
                            if (drive?.hasToken == true) Strings.get(R.string.sesion_de_google_guardada) else Strings.get(R.string.sin_sesion),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            if (drive?.readOnly == true) Strings.get(R.string.solo_lectura) else Strings.get(R.string.lectura_y_escritura),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else if (profile.type == RemoteType.S3) {
                        val s3 = profile.s3
                        Text(
                            if (s3?.bucket.isNullOrEmpty()) Strings.get(R.string.todos_los_buckets) else Strings.get(R.string.bucket, s3?.bucket),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            if (s3?.hasSecret == true) Strings.get(R.string.clave_secreta_guardada) else Strings.get(R.string.sin_clave_secreta),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text(Strings.get(R.string.puerto, profile.port), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (profile.hasPassword) Strings.get(R.string.contrasena_guardada) else Strings.get(R.string.sin_contrasena),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    } }
                    Spacer(Modifier.height(4.dp))
                    val colors = ButtonDefaults.textButtonColors(
                        contentColor = LocalContentColor.current,
                        disabledContentColor = LocalContentColor.current.copy(alpha = 0.38f)
                    )
                    // Caché de este servidor y su botón de borrar. Solo aparece si hay
                    // algo en caché; se desactiva si el servidor está montado (no se
                    // puede borrar en uso).
                    if (cacheKb > 0) {
                        Spacer(Modifier.height(4.dp))
                        Reveal(shown = isSelected, order = 1) {
                        Surface(
                            shape = RoundedCornerShape(28.dp),
                            color = fg.copy(alpha = 0.14f),
                            contentColor = fg,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // En columnas angostas (apaisado) el botón pasa a una segunda fila
                            // en vez de aplastar el texto.
                            FlowRow(
                                modifier = Modifier.padding(start = 20.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Column(Modifier.align(Alignment.CenterVertically)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.Storage,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            Strings.get(R.string.cache),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = fg.copy(alpha = 0.8f),
                                            modifier = Modifier.padding(start = 6.dp)
                                        )
                                    }
                                    Text(
                                        formatCacheKb(cacheKb),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                                Button(
                                    modifier = Modifier.align(Alignment.CenterVertically),
                                    onClick = onClearCache,
                                    enabled = canClearCache,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = fg,
                                        contentColor = bg.value,
                                        disabledContainerColor = fg.copy(alpha = 0.18f),
                                        disabledContentColor = fg.copy(alpha = 0.45f)
                                    ),
                                    contentPadding = PaddingValues(start = 14.dp, end = 18.dp)
                                ) {
                                    Icon(
                                        Icons.Default.DeleteSweep,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        Strings.get(R.string.borrar_cache),
                                        modifier = Modifier.padding(start = 8.dp),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        }
                    }
                    Reveal(shown = isSelected, order = 2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (iconOnly) {
                            val iconColors = IconButtonDefaults.iconButtonColors(contentColor = LocalContentColor.current)
                            IconButton(onClick = onEdit, colors = iconColors) {
                                Icon(Icons.Default.Edit, contentDescription = Strings.get(R.string.editar))
                            }
                            IconButton(onClick = onDelete, colors = iconColors) {
                                Icon(Icons.Default.Delete, contentDescription = Strings.get(R.string.eliminar))
                            }
                        } else {
                            TextButton(onClick = onEdit, colors = colors) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                                Text(Strings.get(R.string.editar))
                            }
                            TextButton(onClick = onDelete, colors = colors) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                                Text(Strings.get(R.string.eliminar))
                            }
                        }
                    }
                    }
                }
            }
        }
        }
        }
    }
}

/**
 * Entrada escalonada de cada bloque de detalles de la tarjeta abierta: sube y se
 * desvanece hacia dentro con un resorte con rebote, uno tras otro (`order`). Se
 * mueve con graphicsLayer, así que no remide ni recompone mientras anima.
 */
@Composable
private fun Reveal(shown: Boolean, order: Int, content: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(shown) {
        if (shown) {
            delay(70L + order * 60L)
            progress.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 380f))
        } else {
            progress.animateTo(0f, tween(110))
        }
    }
    Box(
        Modifier.graphicsLayer {
            val v = progress.value
            alpha = v.coerceIn(0f, 1f)
            translationY = (1f - v) * 18.dp.toPx()
        }
    ) { content() }
}
