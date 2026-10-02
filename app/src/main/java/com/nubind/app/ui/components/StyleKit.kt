package com.nubind.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.Role
import com.nubind.app.root.RemoteProfile
import com.nubind.app.root.RemoteType
import com.nubind.app.root.S3Provider
import com.nubind.app.root.s3Provider
import androidx.compose.ui.unit.dp

/**
 * Tarjeta de sección: superficie muy redondeada con encabezado
 * (icono + título), subtítulo opcional y el contenido debajo.
 * Todos los colores salen del esquema del sistema.
 *
 * Con [expandable] el encabezado se puede tocar para plegar/desplegar el
 * contenido (útil para tarjetas largas); el subtítulo, si hay, siempre
 * queda visible como resumen aunque esté plegada. [initiallyExpanded]
 * solo importa si [expandable] es true.
 */
@Composable
fun SectionCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    expandable: Boolean = false,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    var expanded by rememberSaveable { mutableStateOf(!expandable || initiallyExpanded) }
    val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevronRotation")

    Surface(
        color = scheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = if (expandable) {
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { expanded = !expanded }
                } else {
                    Modifier.fillMaxWidth()
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Icon(icon, contentDescription = null, tint = scheme.primary)
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (expandable) {
                    Icon(
                        Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Contraer" else "Expandir",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.rotate(chevronRotation)
                    )
                }
            }
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    content()
                }
            }
        }
    }
}

/**
 * Opción seleccionable tipo "Curve / Sliders": icono en un círculo más la
 * etiqueta. Seleccionada: relleno primaryContainer, contorno primary y el
 * círculo en primary. Sin seleccionar: relleno tenue y círculo neutro.
 *
 * [brandIcon] es para íconos con colores propios (p. ej. el triángulo de
 * Drive): el círculo queda blanco fijo en vez de teñido con el tema, y el
 * ícono se dibuja con sus propios colores (Image) en vez de un solo tinte
 * (Icon).
 */
@Composable
fun OptionTile(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    brandIcon: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(28.dp)
    val container by animateColorAsState(
        if (selected) scheme.primaryContainer else scheme.surfaceContainerLow,
        label = "tileContainer"
    )
    val outline by animateColorAsState(
        if (selected) scheme.primary else Color.Transparent, label = "tileOutline"
    )
    val badge by animateColorAsState(
        if (selected) scheme.primary else scheme.surfaceContainerHighest,
        label = "tileBadge"
    )
    val badgeContent by animateColorAsState(
        if (selected) scheme.onPrimary else scheme.onSurfaceVariant, label = "tileBadgeContent"
    )

    Row(
        modifier = modifier
            .height(72.dp)
            .clip(shape)
            .background(container)
            .border(2.dp, outline, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(if (brandIcon) Color.White else badge),
            contentAlignment = Alignment.Center
        ) {
            if (brandIcon) {
                Image(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            } else {
                Icon(icon, contentDescription = null, tint = badgeContent)
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
            maxLines = 1
        )
    }
}

/** Iconos que no están en material-icons-core, dibujados con los paths estándar. */
object AppIcons {
    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).addPath(
            pathData = addPathNodes(path),
            fill = SolidColor(Color.Black)
        ).build()

    /**
     * Logo de la app (mismo dibujo que el icono del launcher): nube plana azul
     * claro. Lleva color propio: usar con Image, no con Icon. Va sobre un
     * fondo azul (0xFF2A8DE0), como el icono.
     */
    val Logo: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        ImageVector.Builder(
            name = "Logo",
            defaultWidth = 108.dp,
            defaultHeight = 108.dp,
            viewportWidth = 108f,
            viewportHeight = 108f
        ).addPath(pathData = addPathNodes("M23.59,47.07C23.87,45.88 23.96,44.58 24.39,43.41C27.31,35.48 34.7,33.38 42.32,35.36C44.64,31.91 46.25,28.94 49.85,26.49C52.44,24.74 55.41,23.79 58.46,23.25C72.12,20.81 83.23,31.63 83.46,44.92C85.03,45.4 86.8,45.7 88.39,46.39C93.25,48.48 96.27,52.84 97.49,57.84C99.14,64.58 95.79,72.96 89.25,75.92C87.79,76.59 86.13,77.3 84.52,77.48C81.38,77.84 78.2,77.66 75.04,77.7C68.15,77.78 61.26,77.68 54.37,77.7C48.06,77.72 41.74,77.71 35.43,77.72C30.12,77.72 24.82,78.18 19.71,76.58C14.73,75.03 11.38,69.8 10.51,64.94C8.86,55.68 14.89,48.82 23.59,47.07Z"), fill = SolidColor(Color(0xFFBCD1E6)))
            .build()
    }

    val Folder: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        icon(
            "Folder",
            "M10,4H4C2.9,4 2.01,4.9 2.01,6L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z"
        )
    }

    val Cloud: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        icon(
            "Cloud",
            "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96z"
        )
    }

    val Bolt: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        icon(
            "Bolt",
            "M11,21h-1l1,-7H7.5c-0.58,0 -0.57,-0.32 -0.38,-0.66 0.19,-0.34 0.05,-0.08 0.07,-0.12C8.48,10.94 10.42,7.54 13,3h1l-1,7h3.5c0.49,0 0.56,0.33 0.47,0.51l-0.07,0.15C12.96,17.55 11,21 11,21z"
        )
    }

    val Dns: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        icon(
            "Dns",
            "M20,13H4c-0.55,0 -1,0.45 -1,1v6c0,0.55 0.45,1 1,1h16c0.55,0 1,-0.45 1,-1v-6c0,-0.55 -0.45,-1 -1,-1zM7,19c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zM20,3H4c-0.55,0 -1,0.45 -1,1v6c0,0.55 0.45,1 1,1h16c0.55,0 1,-0.45 1,-1V4c0,-0.55 -0.45,-1 -1,-1zM7,9c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2z"
        )
    }

    val Download: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        icon(
            "Download",
            "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z"
        )
    }

    val X: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        icon(
            "X",
            "M18.901,1.153h3.68l-8.04,9.19L24,22.846h-7.406l-5.8,-7.584l-6.638,7.584H0.474l8.6,-9.83L0,1.154h7.594l5.243,6.932ZM17.61,20.644h2.039L6.486,3.24H4.298Z"
        )
    }

    val Telegram: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        icon(
            "Telegram",
            "M9.78,18.65l0.28,-4.23l7.68,-6.92c0.34,-0.31 -0.07,-0.46 -0.52,-0.19L7.74,13.3L3.64,12c-0.88,-0.25 -0.89,-0.86 0.2,-1.3l15.97,-6.16c0.73,-0.33 1.43,0.18 1.15,1.3l-2.72,12.81c-0.19,0.91 -0.74,1.13 -1.5,0.71L12.6,16.3l-1.99,1.93c-0.23,0.23 -0.42,0.42 -0.83,0.42Z"
        )
    }

    /**
     * Logotipo de Google Drive con sus seis facetas (verde, verde oscuro,
     * amarillo, naranja, azul y azul oscuro), trazado sobre un viewport de
     * 512x512 a partir del diseño vectorial del logo. Va con Image, no con
     * Icon: Icon fuerza un solo tinte y perdería los colores.
     *
     * Cada faceta lleva un contorno fino de su mismo color: tapa las
     * líneas claras que el antialiasing deja entre formas contiguas.
     */
    val DriveLogo: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        fun ImageVector.Builder.facet(color: Long, path: String) = addPath(
            pathData = addPathNodes(path),
            fill = SolidColor(Color(color)),
            stroke = SolidColor(Color(color)),
            strokeLineWidth = 6f,
            strokeLineJoin = StrokeJoin.Round
        )
        ImageVector.Builder(
            name = "DriveLogo",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 512f,
            viewportHeight = 512f
        )
            .facet(0xFF528EF9, "M83,478 L228,333 L512,333 L430,478 Z")          // azul
            .facet(0xFF3A5ABD, "M83,478 L166,333 L228,333 Z")                    // azul oscuro
            .facet(0xFF28B545, "M173,33 L0,332 L83,478 L166,333 L255,178 Z")     // verde
            .facet(0xFF209B39, "M173,33 L255,178 L221,237 Z")                    // verde oscuro
            .facet(0xFFFFD836, "M173,33 L340,33 L512,333 L345,333 Z")            // amarillo
            .facet(0xFFF9BD00, "M324,295 L345,333 L512,333 Z")                   // naranja
            .build()
    }

    /**
     * Logotipo de Oracle: el óvalo rojo (aro con extremos semicirculares),
     * medido sobre el logo original de 819x512 y centrado en un viewport
     * cuadrado para que ocupe el mismo recuadro que los demás iconos. Va con
     * Image, no con Icon, para conservar el color de marca.
     */
    val OracleLogo: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        // Viewport 819x819; el logo mide 512 de alto, así que se desplaza
        // (819 - 512) / 2 = 153.5 hacia abajo para quedar centrado.
        ImageVector.Builder(
            name = "OracleLogo",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 819f,
            viewportHeight = 819f
        ).addPath(
            pathData = addPathNodes(
                // Contorno exterior y hueco interior; EvenOdd deja el hueco vacío.
                "M256,153.5 H563 A256,256 0 0 1 563,665.5 H256 A256,256 0 0 1 256,153.5 Z " +
                    "M256,256.5 H563 A153,153 0 0 1 563,562.5 H256 A153,153 0 0 1 256,256.5 Z"
            ),
            pathFillType = PathFillType.EvenOdd,
            fill = SolidColor(OracleBrandRed)
        ).build()
    }

    /** Logo de AWS para fondos claros (letras azul oscuro, sonrisa naranja). */
    val AwsLogo: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildAwsLogo("AwsLogo", AwsBrandInk)
    }

    /** Logo de AWS para fondos oscuros (letras blancas, sonrisa naranja). */
    val AwsLogoOnDark: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildAwsLogo("AwsLogoOnDark", Color.White)
    }

    /** Logo de Cloudflare: naranja, se lee igual sobre fondo claro y oscuro. */
    val CloudflareLogo: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        buildCloudflareLogo("CloudflareLogo")
    }
}

/**
 * Icono de un servidor. [branded] = true: lleva colores de marca propios y se
 * dibuja con Image; false: es un icono de una sola tinta y se dibuja con Icon
 * (tomando el color del tema).
 */
data class ServerIcon(
    val vector: ImageVector,
    val branded: Boolean,
    /** Variante para fondos oscuros, si el logo de marca no se lee sobre ellos (AWS). */
    val onDark: ImageVector? = null
) {
    /** El vector que se lee bien sobre un fondo claro u oscuro. */
    fun forBackground(dark: Boolean): ImageVector = if (dark) onDark ?: vector else vector
}

/** El icono que identifica a [profile]: uno distinto por tipo de servidor y, en S3, por proveedor. */
fun serverIconFor(profile: RemoteProfile): ServerIcon = when (profile.type) {
    RemoteType.FTP -> ServerIcon(AppIcons.Dns, branded = false)
    RemoteType.DRIVE -> ServerIcon(AppIcons.DriveLogo, branded = true)
    RemoteType.S3 -> when (profile.s3Provider) {
        S3Provider.ORACLE -> ServerIcon(AppIcons.OracleLogo, branded = true)
        S3Provider.AWS -> ServerIcon(AppIcons.AwsLogo, branded = true, onDark = AppIcons.AwsLogoOnDark)
        S3Provider.CLOUDFLARE -> ServerIcon(AppIcons.CloudflareLogo, branded = true)
        else -> ServerIcon(AppIcons.Cloud, branded = false)
    }
}

/** Azul de marca de Drive, para acentos y fondos de tarjeta (no solo el logo). */
val DriveBrandBlue = Color(0xFF2684FC)

/**
 * Naranja/rojo de marca de Oracle (el color de su logo), para el fondo de su
 * tarjeta. Como la tarjeta seleccionada usa este mismo color, el logo se
 * dibuja en blanco sobre ella (ver StackCard) para no confundirse con el fondo.
 */
val OracleBrandRed = Color(0xFFC84735)
