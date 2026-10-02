package com.nubind.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nubind.app.root.RemoteProfile
import com.nubind.app.root.RemoteType
import com.nubind.app.root.S3Provider
import com.nubind.app.root.s3Provider
import com.nubind.app.root.subtitle
import com.nubind.app.ui.theme.AppMotion

// Parte visible de una tarjeta cerrada, y cuánto se mete bajo la siguiente
// (para que no se vea el fondo entre las esquinas redondeadas).
private val PeekHeight = 88.dp
private val UnderlapHeight = 32.dp
private val OpenHeight = 216.dp

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
    modifier: Modifier = Modifier
) {
    val tops = ArrayList<Dp>()
    val heights = ArrayList<Dp>()
    var y = 0.dp
    profiles.forEach { p ->
        val open = p.name == selected
        tops.add(y)
        heights.add(if (open) OpenHeight else PeekHeight + UnderlapHeight)
        y += if (open) OpenHeight else PeekHeight
    }
    val totalHeight = if (profiles.isEmpty()) 0.dp else tops.last() + heights.last()
    val animatedTotal by animateDpAsState(totalHeight, AppMotion.spatial(), label = "stackHeight")

    Box(modifier.fillMaxWidth().height(animatedTotal)) {
        profiles.forEachIndexed { index, profile ->
            key(profile.name) {
                StackCard(
                    profile = profile,
                    index = index,
                    isSelected = profile.name == selected,
                    top = tops[index],
                    height = heights[index],
                    onSelect = { onSelect(profile.name) },
                    onEdit = { onEdit(profile) },
                    onDelete = { onDelete(profile) }
                )
            }
        }
    }
}

@Composable
private fun StackCard(
    profile: RemoteProfile,
    index: Int,
    isSelected: Boolean,
    top: Dp,
    height: Dp,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val animatedTop by animateDpAsState(top, AppMotion.spatial(), label = "cardTop")
    val animatedHeight by animateDpAsState(height, AppMotion.spatial(), label = "cardHeight")

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
    val bg by animateColorAsState(container, AppMotion.effects(), label = "cardBg")
    val fg by animateColorAsState(content, AppMotion.effects(), label = "cardFg")

    Surface(
        onClick = onSelect,
        enabled = !isSelected,
        shape = MaterialTheme.shapes.extraLarge,
        color = bg,
        contentColor = fg,
        shadowElevation = if (isSelected) 8.dp else 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(0, animatedTop.roundToPx()) }
            .height(animatedHeight)
            .semantics { this.selected = isSelected }
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val icon = serverIconFor(profile)
                val iconSize = if (isSelected) 26.dp else 22.dp
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
                        modifier = Modifier.size(iconSize)
                    )
                } else if (profile.type == RemoteType.S3) {
                    // S3 genérico: nube de una tinta (FTP no lleva icono en la tarjeta).
                    Icon(icon.vector, contentDescription = null, modifier = Modifier.size(iconSize))
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
            AnimatedVisibility(visible = isSelected) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    if (profile.type == RemoteType.DRIVE) {
                        val drive = profile.drive
                        Text(
                            if (drive?.hasToken == true) "Sesión de Google guardada" else "Sin sesión",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            if (drive?.readOnly == true) "Solo lectura" else "Lectura y escritura",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else if (profile.type == RemoteType.S3) {
                        val s3 = profile.s3
                        Text(
                            if (s3?.bucket.isNullOrEmpty()) "Todos los buckets" else "Bucket ${s3?.bucket}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            if (s3?.hasSecret == true) "Clave secreta guardada" else "Sin clave secreta",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text("Puerto ${profile.port}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (profile.hasPassword) "Contraseña guardada" else "Sin contraseña",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)
                        TextButton(onClick = onEdit, colors = colors) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Text("Editar")
                        }
                        TextButton(onClick = onDelete, colors = colors) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Text("Eliminar")
                        }
                    }
                }
            }
        }
    }
}
