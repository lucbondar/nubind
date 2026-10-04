package com.nubind.app.root

import androidx.annotation.StringRes
import com.nubind.app.R
import com.nubind.app.Strings

/** Tipos de remoto que la app sabe crear (el valor de "type" en rclone.conf). */
enum class RemoteType(val rclone: String, val label: String) {
    FTP("ftp", "FTP"),
    DRIVE("drive", "Google Drive"),
    /** Almacenamiento de objetos compatible con S3 (Oracle Cloud Object Storage, etc.). */
    S3("s3", "S3")
}

/** Perfil de rendimiento del montaje (lo lee scripts/mount.sh desde config/perf). */
enum class PerfMode(val id: String, @StringRes private val labelRes: Int) {
    BALANCED("balanced", R.string.equilibrado),
    MAX("max", R.string.maximo);

    /** Nombre visible, en el idioma actual (se resuelve al leerlo). */
    val label: String get() = Strings.get(labelRes)
}

/**
 * Rango del tamaño de caché en GB que ofrece la app. El tope real de lo que
 * cabe en el teléfono no es este número: es el espacio libre real, que
 * rclone intenta conservar con 2 GB de margen (--vfs-cache-min-free-space);
 * no es un límite duro para archivos abiertos o subidas pendientes
 * y que "Probar rendimiento" avisa si no alcanza. 100 GB es el tope que
 * ofrece el slider.
 */
const val CACHE_GB_MIN = 1
const val CACHE_GB_MAX = 100

/** Tamaño de caché que usa mount.sh cuando el usuario no eligió uno (debe coincidir con el script). */
fun defaultCacheGb(mode: PerfMode): Int = if (mode == PerfMode.MAX) 10 else 1

/** KB a un texto legible ("340 MB", "2.3 GB"), para mostrar el tamaño de la caché en disco. */
fun formatCacheKb(kb: Long): String = when {
    kb >= 1_048_576 -> "%.1f GB".format(kb / 1_048_576.0)
    kb >= 1024 -> "%.0f MB".format(kb / 1024.0)
    else -> "$kb KB"
}

/** Ajustes propios de un remoto Google Drive. El token nunca sale del rclone.conf. */
data class DriveOptions(
    val clientId: String = "",
    val clientSecret: String = "",
    val readOnly: Boolean = false,
    val rootFolderId: String = "",
    val teamDrive: String = "",
    /** Equivale a --drive-acknowledge-abuse: permite bajar archivos que Google marca como malware/spam. */
    val acknowledgeAbuse: Boolean = false,
    val hasToken: Boolean = false
)

/**
 * Ajustes de un remoto S3. La clave secreta nunca sale del rclone.conf (solo
 * se sabe si existe, en [hasSecret]).
 *
 * [bucket] es el bucket que se monta, con subcarpeta opcional ("bucket" o
 * "bucket/carpeta"); vacío monta la lista de todos los buckets. Se guarda en
 * la clave propia "bind_path" de la sección: rclone la ignora y la leen
 * mount.sh y check_remote.sh.
 */
data class S3Options(
    val endpoint: String = "",
    val region: String = "",
    val accessKeyId: String = "",
    val bucket: String = "",
    val hasSecret: Boolean = false
)

/**
 * Un servidor guardado (FTP, Google Drive o S3). Los campos host/port/user/
 * hasPassword solo aplican a FTP; [drive] solo a Google Drive y [s3] solo a
 * S3. Las contraseñas, claves secretas y el token de sesión nunca salen del
 * rclone.conf.
 */
data class RemoteProfile(
    val name: String,
    val type: RemoteType,
    val host: String = "",
    val port: String = "21",
    val user: String = "",
    val hasPassword: Boolean = false,
    /** Carpeta del servidor FTP que se monta (clave propia "bind_path"); vacío = la raíz. Solo FTP. */
    val folder: String = "",
    val drive: DriveOptions? = null,
    val s3: S3Options? = null
)

/**
 * Proveedores S3 que la app distingue (cada uno con su propio icono, ver
 * serverIconFor en StyleKit.kt). Se detecta por el dominio del endpoint, sin
 * guardar nada extra en rclone.conf: para agregar un proveedor nuevo basta
 * con una entrada aquí (con los sufijos de su dominio) y su icono.
 */
enum class S3Provider(private val brand: String, private val hostSuffixes: List<String>) {
    ORACLE("Oracle Cloud", listOf(".oraclecloud.com")),
    AWS("Amazon S3", listOf(".amazonaws.com", ".amazonaws.com.cn")),
    CLOUDFLARE("Cloudflare R2", listOf(".r2.cloudflarestorage.com")),
    /** Cualquier otro servicio compatible con S3. */
    OTHER("", emptyList());

    /** Nombre visible: la marca, o "Otro proveedor" traducido para [OTHER]. */
    val label: String get() = if (this == OTHER) Strings.get(R.string.otro_proveedor) else brand

    companion object {
        fun fromEndpoint(endpoint: String): S3Provider {
            val host = endpoint.trim().lowercase()
                .substringAfter("://")
                .substringBefore("/")
                .substringBefore(":")
            return entries.firstOrNull { p -> p.hostSuffixes.any { host.endsWith(it) } } ?: OTHER
        }
    }
}

/** Proveedor del servidor S3, o null si no es de tipo S3. */
val RemoteProfile.s3Provider: S3Provider?
    get() = s3?.let { S3Provider.fromEndpoint(it.endpoint) }

/**
 * Ajustes de rendimiento propios de S3 (Oracle Cloud y compatibles), cada uno
 * en su archivo de config/ (los lee scripts/perf_opts.sh al montar). Un valor
 * null significa "automático": el que corresponde al proveedor y al perfil
 * (ver [S3Perf]).
 */
data class S3PerfSettings(
    /** Trozos del mismo archivo que se leen a la vez (--vfs-read-chunk-streams). Solo en Máximo. */
    val streams: Int? = null,
    /** Partes de una subida multiparte a la vez (--s3-upload-concurrency). Solo en Máximo. */
    val uploadConcurrency: Int? = null,
    /** Tamaño de cada parte de subida en MB (--s3-chunk-size). Solo en Máximo. */
    val chunkMb: Int? = null,
    /** Evita peticiones HEAD y copias en el servidor (ver perf_opts.sh). */
    val fewerRequests: Boolean? = null,
    /** Minutos que se cachea cada listado de carpeta (--dir-cache-time). */
    val dirCacheMin: Int? = null
) {
    val isCustom: Boolean
        get() = streams != null || uploadConcurrency != null || chunkMb != null ||
            fewerRequests != null || dirCacheMin != null
}

/**
 * Rangos y valores automáticos del rendimiento de S3. Los automáticos deben
 * coincidir con los de scripts/perf_opts.sh (s3_mount_opts).
 */
/** Límites de las descargas en paralelo de la precarga (los mismos que aplica preload.sh). */
object PreloadPerf {
    const val WORKERS_MIN = 1
    const val WORKERS_MAX = 8
    const val WORKERS_DEFAULT = 4

    /** Límite de velocidad total de la precarga en MB/s (0 = sin límite; lo aplica preload.sh). */
    const val LIMIT_MAX_MBPS = 50
}

object S3Perf {
    const val STREAMS_MIN = 1
    const val STREAMS_MAX = 12
    const val STREAMS_DEFAULT = 4

    const val UPLOAD_CONC_MIN = 1
    const val UPLOAD_CONC_MAX = 16
    const val UPLOAD_CONC_DEFAULT = 4

    val CHUNK_CHOICES_MB = listOf(8, 16, 32, 64)
    const val CHUNK_DEFAULT_MB = 16

    val DIR_CACHE_CHOICES_MIN = listOf(5, 10, 30, 60, 360)

    /** --transfers que fija el perfil Máximo para S3. */
    const val TRANSFERS = 4

    /** Presupuesto estimado de buffers multiparte, no límite de RAM total. */
    const val UPLOAD_RAM_CAP_MB = 256

    /** Oracle y Cloudflare R2 facturan por número de peticiones (R2 tras un cupo gratis): recortan por defecto. */
    fun defaultFewerRequests(provider: S3Provider): Boolean =
        provider == S3Provider.ORACLE || provider == S3Provider.CLOUDFLARE

    fun defaultDirCacheMin(provider: S3Provider, mode: PerfMode): Int = when {
        provider == S3Provider.ORACLE || provider == S3Provider.CLOUDFLARE -> 30
        mode == PerfMode.MAX -> 10
        else -> 5
    }

    /**
     * R2 da errores de firma en archivos grandes con mucha subida multiparte
     * simultánea (reportado en rclone con 4 o más partes), así que arranca en 3.
     */
    fun defaultUploadConcurrency(provider: S3Provider): Int =
        if (provider == S3Provider.CLOUDFLARE) 3 else UPLOAD_CONC_DEFAULT

    /** Partes simultáneas que realmente se usan: las pedidas, bajadas hasta entrar en el tope de RAM. */
    fun effectiveUploadConcurrency(requested: Int, chunkMb: Int): Int {
        var c = requested
        while (c > 1 && TRANSFERS * c * chunkMb > UPLOAD_RAM_CAP_MB) c--
        return c
    }

    /** Estimación de buffers multiparte (MB), sin incluir lecturas ni el resto del proceso. */
    fun uploadRamMb(concurrency: Int, chunkMb: Int): Int = TRANSFERS * concurrency * chunkMb
}

/** "5 min", "1 h", "6 h". */
fun formatMinutes(min: Int): String = when {
    min < 60 -> "$min min"
    min % 60 == 0 -> "${min / 60} h"
    else -> "${min / 60} h ${min % 60} min"
}

/** Línea corta que identifica el servidor en tarjetas y en Inicio. */
val RemoteProfile.subtitle: String
    get() = when (type) {
        RemoteType.FTP -> if (user.isEmpty()) host else "$user@$host"
        RemoteType.DRIVE -> RemoteType.DRIVE.label
        RemoteType.S3 -> {
            val opts = s3
            when {
                opts == null -> RemoteType.S3.label
                opts.bucket.isNotEmpty() -> opts.bucket
                else -> opts.endpoint.removePrefix("https://").removePrefix("http://")
            }
        }
    }

/** rclone.conf en memoria: sección -> (clave -> valor), conservando el orden. */
typealias Conf = LinkedHashMap<String, LinkedHashMap<String, String>>

fun parseConf(text: String): Conf {
    val conf = Conf()
    var current: LinkedHashMap<String, String>? = null
    for (raw in text.lines()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
        if (line.startsWith("[") && line.endsWith("]")) {
            val section = LinkedHashMap<String, String>()
            conf[line.substring(1, line.length - 1)] = section
            current = section
        } else {
            val eq = line.indexOf('=')
            val target = current
            if (eq > 0 && target != null) {
                target[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
            }
        }
    }
    return conf
}

fun serializeConf(conf: Conf): String =
    conf.entries.joinToString("\n\n") { entry ->
        "[${entry.key}]\n" + entry.value.entries.joinToString("\n") { "${it.key} = ${it.value}" }
    } + "\n"

fun Conf.toProfiles(): List<RemoteProfile> =
    entries.mapNotNull { entry ->
        val v = entry.value
        when (v["type"]) {
            RemoteType.FTP.rclone -> RemoteProfile(
                name = entry.key,
                type = RemoteType.FTP,
                host = v["host"].orEmpty(),
                port = v["port"] ?: "21",
                user = v["user"].orEmpty(),
                hasPassword = !v["pass"].isNullOrEmpty(),
                folder = v["bind_path"].orEmpty()
            )
            RemoteType.DRIVE.rclone -> RemoteProfile(
                name = entry.key,
                type = RemoteType.DRIVE,
                drive = DriveOptions(
                    clientId = v["client_id"].orEmpty(),
                    clientSecret = v["client_secret"].orEmpty(),
                    readOnly = v["scope"] == DRIVE_SCOPE_READONLY,
                    rootFolderId = v["root_folder_id"].orEmpty(),
                    teamDrive = v["team_drive"].orEmpty(),
                    acknowledgeAbuse = v["acknowledge_abuse"] == "true",
                    hasToken = !v["token"].isNullOrEmpty()
                )
            )
            RemoteType.S3.rclone -> RemoteProfile(
                name = entry.key,
                type = RemoteType.S3,
                s3 = S3Options(
                    endpoint = v["endpoint"].orEmpty(),
                    region = v["region"].orEmpty(),
                    accessKeyId = v["access_key_id"].orEmpty(),
                    bucket = v["bind_path"].orEmpty(),
                    hasSecret = !v["secret_access_key"].isNullOrEmpty()
                )
            )
            // Otros tipos (sftp...) que el usuario haya puesto a mano se
            // conservan en el archivo pero no se muestran en la app.
            else -> null
        }
    }

const val DRIVE_SCOPE_FULL = "drive"
const val DRIVE_SCOPE_READONLY = "drive.readonly"

/**
 * Es muy fácil pegar la dirección completa ("ftp://192.168.1.75") en el campo
 * Host, pero rclone espera solo el host/IP: con esquema o puerto de más falla
 * con "too many colons in address". Se limpia antes de guardar.
 */
fun cleanHost(raw: String): String =
    raw.trim()
        .removePrefix("ftp://")
        .removePrefix("ftps://")
        .removePrefix("http://")
        .removePrefix("https://")
        .substringBefore("/")
        .substringBefore(":")

// ---- S3 / Oracle Cloud Object Storage ----

// Endpoint de la API de compatibilidad S3 de Oracle:
//   https://<namespace>.compat.objectstorage.<región>.oraclecloud.com
private val ORACLE_ENDPOINT =
    Regex("""^https://([A-Za-z0-9_-]+)\.compat\.objectstorage\.([a-z0-9-]+)\.oraclecloud\.com/?$""")

// Región de Oracle: us-ashburn-1, eu-frankfurt-1, sa-saopaulo-1...
private val ORACLE_REGION = Regex("^[a-z]{2}-[a-z0-9]+-[0-9]+$")
private val ORACLE_NAMESPACE = Regex("^[A-Za-z0-9_-]+$")
private val S3_BUCKET = Regex("^[A-Za-z0-9._-]+(/\\S.*)?$")

fun oracleEndpoint(namespace: String, region: String): String =
    "https://${namespace.trim()}.compat.objectstorage.${region.trim()}.oraclecloud.com"

/** Namespace y región si [endpoint] es de Oracle Cloud; null si es de otro proveedor. */
fun parseOracleEndpoint(endpoint: String): Pair<String, String>? =
    ORACLE_ENDPOINT.find(endpoint.trim())?.let { it.groupValues[1] to it.groupValues[2] }

/** Errores de los campos de Oracle (namespace, región), o null si están bien. */
fun validateOracleNamespace(namespace: String): String? = when {
    namespace.isEmpty() -> Strings.get(R.string.escribe_el_namespace)
    !ORACLE_NAMESPACE.matches(namespace) -> Strings.get(R.string.solo_letras_numeros_y)
    else -> null
}

fun validateOracleRegion(region: String): String? = when {
    region.isEmpty() -> Strings.get(R.string.escribe_la_region)
    !ORACLE_REGION.matches(region) -> Strings.get(R.string.formato_de_region_por_ejemplo_us)
    else -> null
}

// ---- Amazon S3 ----

// Región de AWS: us-east-1, eu-west-3, ap-southeast-2, us-gov-west-1, cn-north-1...
private val AWS_REGION = Regex("^[a-z]{2}(-[a-z]+)+-[0-9]+$")

/**
 * Endpoint regional de Amazon S3. Con provider=AWS rclone usa direccionamiento
 * virtual-hosted (bucket.s3.región.amazonaws.com) por su cuenta; las regiones de
 * China tienen otro dominio (.amazonaws.com.cn).
 */
fun awsEndpoint(region: String): String {
    val r = region.trim()
    val domain = if (r.startsWith("cn-")) "amazonaws.com.cn" else "amazonaws.com"
    return "https://s3.$r.$domain"
}

fun validateAwsRegion(region: String): String? = when {
    region.isEmpty() -> Strings.get(R.string.escribe_la_region_del_bucket)
    !AWS_REGION.matches(region) -> Strings.get(R.string.formato_de_region_por_ejemplo_us_2)
    else -> null
}

// ---- Cloudflare R2 ----

// Account ID: 32 caracteres hexadecimales (panel de Cloudflare > R2 > Resumen).
private val CF_ACCOUNT_ID = Regex("^[0-9a-fA-F]{32}$")

// Host de la API S3 de R2. La jurisdicción (eu, fedramp) va entre el ID y "r2":
//   <id>.r2.cloudflarestorage.com, <id>.eu.r2.cloudflarestorage.com...
private val CF_HOST =
    Regex("^([0-9a-f]{32})(\\.(?:eu|fedramp))?\\.r2\\.cloudflarestorage\\.com$")

/** Solo el host de lo pegado: sin esquema, ruta, consulta ni puerto, en minúsculas. */
private fun hostOnly(raw: String): String =
    raw.trim().lowercase().substringAfter("://").substringBefore("/").substringBefore("?").substringBefore(":")

/**
 * Endpoint de R2 a partir de lo escrito en el campo: el Account ID (se arma
 * https://<id>.r2.cloudflarestorage.com) o el endpoint/host completo, que es la
 * forma de conservar una jurisdicción (UE, FedRAMP). Null si no es ninguna.
 */
fun cloudflareEndpoint(input: String): String? {
    val t = input.trim()
    if (CF_ACCOUNT_ID.matches(t)) return "https://${t.lowercase()}.r2.cloudflarestorage.com"
    val host = hostOnly(t)
    return if (CF_HOST.matches(host)) "https://$host" else null
}

/** Lo que muestra el campo al editar: el Account ID, o el host entero si lleva jurisdicción. */
fun cloudflareFieldValue(endpoint: String): String? {
    val host = hostOnly(endpoint)
    val m = CF_HOST.matchEntire(host) ?: return null
    return if (m.groupValues[2].isEmpty()) m.groupValues[1] else host
}

fun validateCloudflareAccount(input: String): String? = when {
    input.isBlank() -> Strings.get(R.string.escribe_el_account_id)
    cloudflareEndpoint(input) == null -> Strings.get(R.string.debe_ser_el_account_id_32)
    else -> null
}

/** Endpoint escrito a mano: se completa el https:// y se quita la barra final. */
fun cleanS3Endpoint(raw: String): String {
    val t = raw.trim().trimEnd('/')
    return if (t.isEmpty() || t.contains("://")) t else "https://$t"
}

fun validateS3Endpoint(endpoint: String): String? = when {
    endpoint.isEmpty() -> Strings.get(R.string.escribe_el_endpoint)
    !(endpoint.startsWith("https://") || endpoint.startsWith("http://")) -> Strings.get(R.string.debe_empezar_con_https)
    endpoint.length <= "https://".length -> Strings.get(R.string.endpoint_incompleto)
    endpoint.any { it.isWhitespace() } -> Strings.get(R.string.no_puede_llevar_espacios)
    else -> null
}

/** Carpeta FTP escrita o pegada: se quitan espacios y las "/" del final ("/Juegos/" -> "/Juegos"). */
fun cleanFtpFolder(raw: String): String =
    // "/" solo (o vacío) queda en "": es la raíz y no se guarda nada.
    raw.trim().trimEnd('/')

/** Bucket pegado como "s3://bucket/carpeta" o "/bucket/": se deja "bucket/carpeta". */
fun cleanS3Bucket(raw: String): String =
    raw.trim().removePrefix("s3://").trim('/')

/** El bucket es opcional: vacío es válido (se listan todos los buckets). */
fun validateS3Bucket(bucket: String): String? =
    if (bucket.isEmpty() || S3_BUCKET.matches(bucket)) null
    else Strings.get(R.string.bucket_invalido_letras_numeros_y_carpeta)

// Patrones de link para compartir una carpeta de Drive:
//   https://drive.google.com/drive/folders/<id>?usp=sharing
//   https://drive.google.com/drive/u/0/folders/<id>
//   https://drive.google.com/open?id=<id>                 (link viejo, cualquier archivo/carpeta)
private val DRIVE_FOLDER_URL = Regex("""/folders/([A-Za-z0-9_-]+)""")
private val DRIVE_ID_PARAM = Regex("""[?&]id=([A-Za-z0-9_-]+)""")

/**
 * Es más fácil pegar el link para compartir la carpeta que buscar su ID a
 * mano. Si [raw] matchea alguno de los links de arriba, devuelve solo el
 * ID; si no matchea nada (ya es un ID, o cualquier otro texto), se
 * devuelve tal cual recortado, para no interferir con lo que el usuario
 * esté escribiendo.
 */
fun extractDriveFolderId(raw: String): String {
    val trimmed = raw.trim()
    DRIVE_FOLDER_URL.find(trimmed)?.let { return it.groupValues[1] }
    DRIVE_ID_PARAM.find(trimmed)?.let { return it.groupValues[1] }
    return trimmed
}

// Reglas de rclone para el nombre de un remoto: solo estos caracteres ASCII,
// sin empezar con "-" ni con espacio.
private val NAME_REGEX = Regex("^[A-Za-z0-9_.+@][A-Za-z0-9_.+@ -]*$")

fun validateProfileName(name: String, original: String?, existing: List<String>): String? = when {
    name.isEmpty() -> Strings.get(R.string.escribe_un_nombre)
    !NAME_REGEX.matches(name) -> Strings.get(R.string.usa_letras_sin_tilde_numeros_espacios)
    name != original && existing.contains(name) -> Strings.get(R.string.ya_existe_un_servidor_con_ese)
    else -> null
}

/** Ruta de destino del bind cuando el usuario todavía no configuró una propia. */
const val DEFAULT_TARGET_PATH = "/sdcard/Nubind"

/** Raíz del almacenamiento interno desde donde se elige la carpeta de destino. */
const val STORAGE_ROOT = "/sdcard"

/** Quita espacios y la barra final (salvo que la ruta sea solo "/"). */
fun cleanTargetPath(raw: String): String {
    val trimmed = raw.trim()
    return if (trimmed.length > 1) trimmed.trimEnd('/') else trimmed
}

/**
 * Reglas mínimas para la ruta de destino: absoluta, sin ".." (evita salirse
 * de donde debería quedar el bind) y distinta de la raíz del sistema.
 */
fun validateTargetPath(path: String): String? = when {
    path.isEmpty() -> Strings.get(R.string.escribe_una_ruta)
    !path.startsWith("/") -> Strings.get(R.string.debe_ser_una_ruta_absoluta_empieza)
    path == "/" -> Strings.get(R.string.no_uses_la_raiz_del_sistema)
    path.contains("..") -> Strings.get(R.string.la_ruta_no_puede_contener)
    else -> null
}
