package com.nubind.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Colores de estado del actualizador (verde = hay actualización / listo, ámbar =
 * módulo desfasado). Son fijos y no salen del color dinámico: así el verde sigue
 * siendo verde y el ámbar ámbar con cualquier fondo de pantalla. Cada paleta
 * trae pares con contraste garantizado (tonos M3: contenedor 90 / texto 10 en
 * claro; contenedor 30 / texto 90 en oscuro).
 *
 *  - [container] / [onContainer]: fondo de la tarjeta y su texto.
 *  - [accent] / [onAccent]: color pleno (insignia, botón principal, progreso) y lo que va encima.
 */
@Immutable
class StatusPalette(
    val container: Color,
    val onContainer: Color,
    val accent: Color,
    val onAccent: Color
)

private val GreenLight = StatusPalette(Color(0xFFBDEFC0), Color(0xFF00210A), Color(0xFF1B6D2F), Color(0xFFFFFFFF))
private val GreenDark = StatusPalette(Color(0xFF0F5223), Color(0xFFB7F4B8), Color(0xFF8DDB90), Color(0xFF00390F))
private val AmberLight = StatusPalette(Color(0xFFFFDEA3), Color(0xFF261900), Color(0xFF7A5900), Color(0xFFFFFFFF))
private val AmberDark = StatusPalette(Color(0xFF5C4300), Color(0xFFFFDEA3), Color(0xFFF5BE48), Color(0xFF402D00))

/** Claro u oscuro según la superficie del tema en uso (no según el ajuste del sistema). */
@Composable
private fun isDarkSurface(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** Hay una actualización por instalar / en curso, o el módulo quedó flasheado. */
@Composable
fun updateGreenPalette(): StatusPalette = if (isDarkSurface()) GreenDark else GreenLight

/** El módulo instalado va por detrás de la app. */
@Composable
fun syncAmberPalette(): StatusPalette = if (isDarkSurface()) AmberDark else AmberLight
