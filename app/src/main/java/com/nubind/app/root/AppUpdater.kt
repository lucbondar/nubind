package com.nubind.app.root

import android.content.Context
import com.topjohnwu.superuser.Shell
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Lo que publica update.json sobre la última build de la app. */
data class UpdateInfo(
    val appVersion: String,
    val appVersionCode: Int,
    val apkUrl: String,
    val apkSha256: String?
)

/**
 * Datos del módulo KSU instalado (su module.prop). [appVersion] y
 * [appVersionCode] son la versión del APK que ese módulo trae adentro (los
 * estampa el CI); en módulos viejos no existen y quedan en null.
 */
data class ModuleInfo(
    val version: String,
    val versionCode: Int,
    val appVersion: String?,
    val appVersionCode: Int?
)

/** Estado del actualizador de la app (lo muestra el banner de Acerca de). */
sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data object UpToDate : AppUpdateState
    data class Available(val info: UpdateInfo) : AppUpdateState
    /** [progress] en 0f..1f; negativo = tamaño desconocido (indeterminado). */
    data class Downloading(val progress: Float) : AppUpdateState
    data object Installing : AppUpdateState
    /** [info] permite reintentar sin volver a consultar. */
    data class Failed(val message: String, val info: UpdateInfo?) : AppUpdateState
}

/**
 * Actualizador de la app. El canal es el mismo update.json que lee KernelSU
 * (el release fijo "updates"): el CI le agrega la versión y la URL del APK de
 * la última build. La instalación va por root (cmd package install) para que
 * no haga falta el permiso de "instalar apps desconocidas".
 */
object AppUpdater {
    const val UPDATE_JSON_URL = "https://github.com/lucbondar/nubind/releases/download/updates/update.json"

    private const val APP_PACKAGE = "com.nubind.app"
    private const val APP_ACTIVITY = ".MainActivity"
    private const val TMP = "/data/local/tmp"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val INSTALL_WAIT_S = 90

    // ---- Última versión publicada ----

    /** Consulta update.json. Devuelve null sin red, con JSON inválido o sin datos de la app. */
    fun fetchLatest(): UpdateInfo? {
        val conn = open(UPDATE_JSON_URL)
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) null
            else parseUpdateJson(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    fun parseUpdateJson(text: String): UpdateInfo? = try {
        val o = JSONObject(text)
        val code = o.optInt("appVersionCode", 0)
        val url = o.optString("apkUrl", "")
        if (code <= 0 || !url.startsWith("https://")) null
        else UpdateInfo(
            appVersion = o.optString("appVersion", ""),
            appVersionCode = code,
            apkUrl = url,
            apkSha256 = o.optString("apkSha256", "").trim().ifEmpty { null }
        )
    } catch (_: Exception) {
        null
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Nubind-Updater")
        }

    // ---- Descarga ----

    /**
     * Baja el APK a la caché de la app y comprueba su SHA-256 si update.json
     * lo trae. Bloqueante: llamar fuera del hilo principal. [onProgress]
     * recibe 0f..1f (solo si el servidor informa el tamaño).
     */
    @Throws(IOException::class)
    fun download(cacheDir: File, info: UpdateInfo, onProgress: (Float) -> Unit): File {
        val dir = File(cacheDir, "update").apply { mkdirs() }
        val out = File(dir, "nubind-update.apk")
        out.delete()
        val conn = open(info.apkUrl)
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { input ->
                out.outputStream().use { sink ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = (read * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct / 100f)
                            }
                        }
                    }
                }
            }
            val expected = info.apkSha256
            if (expected != null) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expected, ignoreCase = true)) throw IOException("SHA-256")
            }
            return out
        } catch (e: IOException) {
            out.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    // ---- Instalación (root) ----

    private fun sq(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Instala [apk] encima de la app con root. Lanza assets/self_update.sh
     * desacoplado y espera su veredicto: si todo sale bien, Android mata
     * este proceso al reemplazar el paquete y el script reabre la app (por
     * eso, en ese caso, esta función nunca llega a devolver). Devuelve el
     * motivo si falló, o null si por algún motivo siguió viva sin error.
     * Bloqueante: llamar fuera del hilo principal.
     */
    fun installViaRoot(context: Context, apk: File): String? {
        val script = File(apk.parentFile, "self_update.sh")
        try {
            context.assets.open("self_update.sh").use { input ->
                script.outputStream().use { input.copyTo(it) }
            }
        } catch (e: IOException) {
            return e.message ?: "self_update.sh"
        }

        val result = "$TMP/nubind-update.result"
        val prep = Shell.cmd(
            "rm -f $result",
            "cp ${sq(apk.path)} $TMP/nubind-update.apk",
            "chmod 644 $TMP/nubind-update.apk",
            "cp ${sq(script.path)} $TMP/nubind-selfupdate.sh",
            "chmod 755 $TMP/nubind-selfupdate.sh"
        ).exec()
        if (!prep.isSuccess) return prep.out.joinToString("\n").ifBlank { "cp" }

        Shell.cmd(
            "nohup sh $TMP/nubind-selfupdate.sh $TMP/nubind-update.apk $APP_PACKAGE $APP_ACTIVITY $result " +
                ">/dev/null 2>&1 &"
        ).exec()

        repeat(INSTALL_WAIT_S) {
            Thread.sleep(1000)
            val out = Shell.cmd("cat $result 2>/dev/null").exec().out.joinToString("\n").trim()
            if (out.isNotEmpty()) return out
        }
        return "timeout"
    }

    // ---- Módulo KSU instalado ----

    /** module.prop del módulo instalado (con root), o null si no hay root o el módulo no está. */
    fun readModuleInfo(): ModuleInfo? {
        val res = Shell.cmd("cat ${ModulePaths.BASE}/module.prop 2>/dev/null").exec()
        if (!res.isSuccess) return null
        val props = HashMap<String, String>()
        for (line in res.out) {
            val i = line.indexOf('=')
            if (i > 0) props[line.substring(0, i).trim()] = line.substring(i + 1).trim()
        }
        val version = props["version"] ?: return null
        return ModuleInfo(
            version = version,
            versionCode = props["versionCode"]?.toIntOrNull() ?: 0,
            appVersion = props["appVersion"]?.ifEmpty { null },
            appVersionCode = props["appVersionCode"]?.toIntOrNull()
        )
    }

    /**
     * true si el APK que trae el módulo instalado es más viejo que la app que
     * corre ahora (típico tras actualizar la app desde aquí sin actualizar el
     * módulo). Con appVersionCode se compara directo; los módulos anteriores
     * a este campo se comparan por el número de versión de su module.prop.
     */
    fun isModuleBehind(module: ModuleInfo, appVersionCode: Int, appVersionName: String): Boolean {
        module.appVersionCode?.let { return it < appVersionCode }
        val moduleBase = numericVersion(module.appVersion ?: module.version) ?: return false
        val appBase = numericVersion(appVersionName) ?: return false
        return compareVersions(moduleBase, appBase) < 0
    }

    /** "v2.5.2 (build 163) [PRE-RELEASE]" -> "2.5.2" */
    fun numericVersion(text: String): String? = Regex("""\d+(?:\.\d+)*""").find(text)?.value

    fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}
