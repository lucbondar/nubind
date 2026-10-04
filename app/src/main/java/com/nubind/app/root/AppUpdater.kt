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
    val apkSha256: String?,
    /** zip del módulo de esa misma build (para descargarlo y flashearlo desde la app); null si update.json no lo trae. */
    val zipUrl: String? = null,
    val zipSha256: String? = null
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

/** Descarga y flasheo del módulo desde el aviso de desfase. */
sealed interface ModuleFlashState {
    data object Idle : ModuleFlashState
    /** [progress] en 0f..1f; negativo = tamaño desconocido (indeterminado). */
    data class Downloading(val progress: Float) : ModuleFlashState
    data object Flashing : ModuleFlashState
    data class Failed(val message: String) : ModuleFlashState
}

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

    /**
     * Consulta update.json. Devuelve null sin red, con JSON inválido o sin datos de la app.
     * Primero con un parámetro único en la URL y cabeceras no-cache, para esquivar copias
     * viejas en la caché de GitHub justo después de publicar una build; si eso falla, con
     * la URL normal.
     */
    fun fetchLatest(): UpdateInfo? =
        fetchFrom("$UPDATE_JSON_URL?t=${System.currentTimeMillis()}") ?: fetchFrom(UPDATE_JSON_URL)

    private fun fetchFrom(url: String): UpdateInfo? {
        val conn = try {
            open(url).apply {
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Pragma", "no-cache")
            }
        } catch (_: Exception) {
            return null
        }
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
            apkSha256 = o.optString("apkSha256", "").trim().ifEmpty { null },
            zipUrl = o.optString("zipUrl", "").takeIf { it.startsWith("https://") },
            zipSha256 = o.optString("zipSha256", "").trim().ifEmpty { null }
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
    fun download(cacheDir: File, info: UpdateInfo, onProgress: (Float) -> Unit): File =
        downloadFile(cacheDir, info.apkUrl, info.apkSha256, "nubind-update.apk", onProgress)

    /** Igual que [download] para cualquier archivo (el APK o el zip del módulo). */
    @Throws(IOException::class)
    fun downloadFile(cacheDir: File, url: String, sha256: String?, fileName: String, onProgress: (Float) -> Unit): File {
        val dir = File(cacheDir, "update").apply { mkdirs() }
        val out = File(dir, fileName)
        out.delete()
        val conn = open(url)
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
            val expected = sha256
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

    // ---- Flasheo del módulo (root) ----

    private const val FLASH_WAIT_S = 240

    /**
     * Flashea [zip] (el módulo) con el gestor de root (ksud de KernelSU / KSU Next /
     * SukiSU, o magisk). Lanza assets/flash_module.sh desacoplado, porque
     * customize.sh reinstala la app y eso mata este proceso; el script reabre la
     * app al terminar. Devuelve null si salió bien (el módulo queda a la espera de
     * reiniciar) o el motivo del fallo. Bloqueante: llamar fuera del hilo principal.
     */
    fun flashModuleViaRoot(context: Context, zip: File): String? {
        val script = File(zip.parentFile, "flash_module.sh")
        try {
            context.assets.open("flash_module.sh").use { input ->
                script.outputStream().use { input.copyTo(it) }
            }
        } catch (e: IOException) {
            return e.message ?: "flash_module.sh"
        }

        val result = "$TMP/nubind-flash.result"
        val prep = Shell.cmd(
            "rm -f $result",
            "cp ${sq(zip.path)} $TMP/nubind-module.zip",
            "chmod 644 $TMP/nubind-module.zip",
            "cp ${sq(script.path)} $TMP/nubind-flash.sh",
            "chmod 755 $TMP/nubind-flash.sh"
        ).exec()
        if (!prep.isSuccess) return prep.out.joinToString("\n").ifBlank { "cp" }

        Shell.cmd(
            "nohup sh $TMP/nubind-flash.sh $TMP/nubind-module.zip $APP_PACKAGE $APP_ACTIVITY $result " +
                ">/dev/null 2>&1 &"
        ).exec()

        repeat(FLASH_WAIT_S) {
            Thread.sleep(1000)
            val out = Shell.cmd("cat $result 2>/dev/null").exec().out.joinToString("\n").trim()
            if (out == "OK") return null
            if (out.isNotEmpty()) return out
        }
        return "timeout"
    }

    /** Reinicia el teléfono (el módulo flasheado se activa al arrancar). */
    fun reboot() {
        Shell.cmd("svc power reboot || reboot").exec()
    }

    // ---- Módulo KSU instalado ----

    /**
     * Módulo ya flasheado que espera al reinicio (KernelSU lo deja en modules_update
     * hasta el próximo arranque), o null si no hay ninguno pendiente.
     */
    fun readPendingModuleInfo(): ModuleInfo? =
        readModuleProp("${ModulePaths.UPDATE_DIR}/module.prop")

    /** module.prop del módulo instalado (con root), o null si no hay root o el módulo no está. */
    fun readModuleInfo(): ModuleInfo? = readModuleProp("${ModulePaths.BASE}/module.prop")

    private fun readModuleProp(path: String): ModuleInfo? {
        val res = Shell.cmd("cat ${sq(path)} 2>/dev/null").exec()
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
