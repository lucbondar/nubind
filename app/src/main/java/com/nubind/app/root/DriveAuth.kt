package com.nubind.app.root

import org.json.JSONException
import org.json.JSONObject

/** Estado del inicio de sesión con Google (lo observa el formulario de servidor). */
sealed interface DriveAuthState {
    data object Idle : DriveAuthState
    data object Starting : DriveAuthState

    /** rclone ya escucha en 127.0.0.1; falta que el usuario autorice en el navegador. */
    data class WaitingBrowser(val url: String) : DriveAuthState

    /** [token] es el JSON que rclone guarda en la clave "token" del remoto. */
    data class Success(val token: String) : DriveAuthState

    data class Failed(val message: String) : DriveAuthState
}

/** Lo que se pudo leer hasta ahora de la salida de `rclone authorize`. */
data class DriveAuthProgress(
    val url: String?,
    val token: String?,
    /** Código de salida de rclone; null mientras siga corriendo. */
    val exitCode: Int?,
    val error: String?
)

/**
 * `scripts/drive_auth.sh` deja la salida de `rclone authorize` en un archivo
 * y agrega al final una línea `__EXIT:<código>`. La app lo lee por polling:
 * primero aparece la URL de autorización, y al terminar el bloque del token.
 */
object DriveAuthParser {

    private val URL_REGEX = Regex("""https?://(?:127\.0\.0\.1|localhost)(?::\d+)?/auth\?state=\S+""")
    private val TOKEN_REGEX = Regex("""--->\s*(\{.*?\})\s*<---End paste""", RegexOption.DOT_MATCHES_ALL)
    private val EXIT_REGEX = Regex("""__EXIT:(\d+)""")
    private val ERROR_HINT = Regex("""(?i)failed|error|critical|fatal|denied|timeout|no such host|x509""")

    fun parse(output: String): DriveAuthProgress {
        val url = URL_REGEX.find(output)?.value
        val token = TOKEN_REGEX.find(output)?.groupValues?.get(1)?.let { normalizeToken(it) }
        val exit = EXIT_REGEX.find(output)?.groupValues?.get(1)?.toIntOrNull()

        var error: String? = null
        if (token == null && exit != null) {
            val lines = output.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("__EXIT") }
            val line = lines.lastOrNull { ERROR_HINT.containsMatchIn(it) } ?: lines.lastOrNull()
            error = line?.take(240) ?: "rclone terminó sin devolver un token (código $exit)"
        }
        return DriveAuthProgress(url = url, token = token, exitCode = exit, error = error)
    }
}

/**
 * Valida un token de rclone (JSON) y lo deja en una sola línea, como lo
 * espera el rclone.conf. Acepta también todo el bloque pegado desde
 * `rclone authorize`. Devuelve null si no es válido: sin refresh_token la
 * sesión caducaría a la hora.
 */
fun normalizeToken(raw: String): String? {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val candidate = raw.substring(start, end + 1)
    return try {
        val json = JSONObject(candidate)
        if (json.optString("access_token").isEmpty() || json.optString("refresh_token").isEmpty()) {
            null
        } else {
            candidate.lines().joinToString("") { it.trim() }
        }
    } catch (e: JSONException) {
        null
    }
}
