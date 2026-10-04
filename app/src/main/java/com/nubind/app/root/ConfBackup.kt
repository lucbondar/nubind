package com.nubind.app.root

import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Respaldo cifrado de los servidores (el rclone.conf completo: contraseñas de FTP, claves S3 y
 * tokens de Drive). Las contraseñas de rclone solo están ofuscadas (reversibles), por eso el
 * archivo nunca lleva el conf en claro: se cifra con una contraseña que elige el usuario.
 *
 * Formato del archivo (.nubind):
 *   MAGIC (8 bytes "NUBINDB1") | salt (16) | iv (12) | AES-256-GCM(JSON) + tag (16)
 * La clave sale de PBKDF2-HMAC-SHA256 con [ITERATIONS] vueltas. MAGIC va como dato asociado (AAD),
 * así que cualquier cambio en la cabecera también invalida la etiqueta. El JSON es
 * {"v":1,"app":"<versión>","conf":"<rclone.conf>"}; el "v" permite evolucionar el formato.
 *
 * Solo usa javax.crypto y org.json (sin Context ni root), para poder probarlo aparte de la UI.
 */
object ConfBackup {
    private val MAGIC = "NUBINDB1".toByteArray(Charsets.US_ASCII)
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    const val ITERATIONS = 600_000
    const val MIN_PASSWORD = 8

    /** Por qué falló descifrar; la UI lo traduce a texto. */
    enum class Failure { NOT_A_BACKUP, WRONG_PASSWORD, UNSUPPORTED_VERSION, CORRUPT }

    class BackupException(val failure: Failure) : Exception(failure.name)

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(raw, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /** Cifra [conf] con [password]; devuelve los bytes del archivo. */
    fun encrypt(conf: String, password: CharArray, appVersion: String): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val payload = JSONObject()
            .put("v", 1)
            .put("app", appVersion)
            .put("conf", conf)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, ITERATIONS), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(MAGIC)
        return MAGIC + salt + iv + cipher.doFinal(payload)
    }

    /** Descifra un archivo .nubind y devuelve el rclone.conf. Lanza [BackupException] si no se puede. */
    fun decrypt(data: ByteArray, password: CharArray): String {
        val header = MAGIC.size + SALT_LEN + IV_LEN
        if (data.size < MAGIC.size || !data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw BackupException(Failure.NOT_A_BACKUP)
        }
        // Cabecera + al menos la etiqueta de 16 bytes: menos que eso es un archivo cortado.
        if (data.size < header + TAG_BITS / 8) throw BackupException(Failure.CORRUPT)
        val salt = data.copyOfRange(MAGIC.size, MAGIC.size + SALT_LEN)
        val iv = data.copyOfRange(MAGIC.size + SALT_LEN, header)
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(password, salt, ITERATIONS), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(MAGIC)
            cipher.doFinal(data, header, data.size - header)
        } catch (e: AEADBadTagException) {
            // Contraseña equivocada o archivo alterado: GCM no distingue entre ambos.
            throw BackupException(Failure.WRONG_PASSWORD)
        } catch (e: Exception) {
            throw BackupException(Failure.CORRUPT)
        }
        val json = try {
            JSONObject(String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupException(Failure.CORRUPT)
        }
        if (json.optInt("v", 0) != 1) throw BackupException(Failure.UNSUPPORTED_VERSION)
        return json.optString("conf").ifEmpty { throw BackupException(Failure.CORRUPT) }
    }
}
