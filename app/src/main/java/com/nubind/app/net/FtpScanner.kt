package com.nubind.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Un servidor FTP encontrado en la red local. */
data class FoundFtpServer(val ip: String, val port: Int, val banner: String?)

/** Puertos que se sondean: el estándar y los que usan las apps de servidor FTP de Android / Termux. */
private val FTP_PORTS = intArrayOf(21, 2121, 2221, 2222)

private const val CONNECT_TIMEOUT_MS = 900
private const val BANNER_TIMEOUT_MS = 1500
private const val MAX_CONCURRENCY = 96

/** Máximo de hosts a recorrer (una /22); redes más grandes se acotan alrededor de la IP local. */
private const val MAX_HOSTS = 1022

/** La red local: IP del teléfono, largo de prefijo y (si se conoce) la [Network] de Android. */
private class LocalNet(val address: Inet4Address, val prefix: Int, val network: Network?)

/**
 * Busca la red Wi-Fi/Ethernet real del teléfono.
 *
 * Antes se tomaba la primera interfaz IPv4 "up", que con datos móviles o VPN
 * activos suele ser rmnet/tun (10.x / 100.x) y no la Wi-Fi: se barría una
 * subred equivocada. Además, si la Wi-Fi no tiene internet (típico de una
 * red local sin salida), Android manda los sockets normales por datos
 * móviles y nunca llegan al FTP de la LAN. Por eso se usa ConnectivityManager
 * (solo requiere ACCESS_NETWORK_STATE) y luego se atan los sockets a esa red.
 */
private fun findLocalNet(context: Context): LocalNet? {
    try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val candidates = cm.allNetworks.mapNotNull { net ->
            val caps = cm.getNetworkCapabilities(net) ?: return@mapNotNull null
            val lan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!lan || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            val la = cm.getLinkProperties(net)?.linkAddresses
                ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
                ?: return@mapNotNull null
            LocalNet(la.address as Inet4Address, la.prefixLength, net)
        }
        candidates.firstOrNull()?.let { return it }
    } catch (e: Exception) {
        // se cae al método por interfaces de abajo
    }
    return localNetFromInterfaces()
}

/**
 * Plan B (por ejemplo si el teléfono es el punto de acceso Wi-Fi, que no
 * aparece como Network): interfaces de red, priorizando wlan/ap/eth sobre
 * rmnet/tun.
 */
private fun localNetFromInterfaces(): LocalNet? = try {
    NetworkInterface.getNetworkInterfaces()?.asSequence()
        ?.filter { it.isUp && !it.isLoopback }
        ?.flatMap { ni ->
            ni.interfaceAddresses.asSequence()
                .filter { it.address is Inet4Address && !it.address.isLoopbackAddress }
                .map { ni.name to it }
        }
        ?.sortedBy { (name, _) ->
            when {
                name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan") -> 0
                name.startsWith("eth") -> 1
                name.startsWith("rmnet") || name.startsWith("tun") || name.startsWith("ccmni") -> 3
                else -> 2
            }
        }
        ?.firstOrNull()
        ?.let { (_, ia) -> LocalNet(ia.address as Inet4Address, ia.networkPrefixLength.toInt(), null) }
} catch (e: Exception) {
    null
}

private fun ipToInt(b: ByteArray): Int =
    ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
        ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)

private fun intToIp(v: Int): String =
    "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"

/** Todas las IPs de host de la subred (sin red/broadcast ni la propia). */
private fun hostsOf(net: LocalNet): List<String> {
    val self = ipToInt(net.address.address)
    // Prefijo entre /22 y /30: nunca más de 1022 hosts, ni una subred absurda.
    val prefix = net.prefix.coerceIn(22, 30)
    val mask = -1 shl (32 - prefix)
    val network = self and mask
    val broadcast = network or mask.inv()
    return ((network + 1) until broadcast)
        .filter { it != self }
        .take(MAX_HOSTS)
        .map(::intToIp)
}

/**
 * Sondea ip:port. Un servidor FTP manda "220 ..." apenas se conecta; en
 * puertos distintos del 21 se exige ese saludo para no listar cualquier
 * cosa que tenga el puerto abierto. En el 21 basta con que conecte.
 */
private fun probeFtp(ip: String, port: Int, network: Network?): FoundFtpServer? {
    val socket = Socket()
    return try {
        network?.bindSocket(socket)
        socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
        val banner = try {
            socket.soTimeout = BANNER_TIMEOUT_MS
            socket.getInputStream().bufferedReader().readLine()?.trim()
        } catch (e: Exception) {
            null
        }
        if (banner?.startsWith("220") == true || (port == 21 && banner == null)) {
            FoundFtpServer(ip, port, banner)
        } else null
    } catch (e: Exception) {
        null
    } finally {
        try { socket.close() } catch (_: Exception) {}
    }
}

/**
 * Recorre la subred Wi-Fi en busca de servidores FTP. [onProgress] recibe
 * (revisados, total) y se llama desde varios hilos a la vez.
 * Devuelve lista vacía si no hay Wi-Fi/Ethernet conectada.
 */
suspend fun scanForFtpServers(
    context: Context,
    onProgress: (checked: Int, total: Int) -> Unit
): List<FoundFtpServer> {
    val net = findLocalNet(context.applicationContext) ?: return emptyList()
    val hosts = hostsOf(net)
    val total = hosts.size * FTP_PORTS.size
    val checked = AtomicInteger(0)

    val pool = Executors.newFixedThreadPool(MAX_CONCURRENCY)
    try {
        val dispatcher = pool.asCoroutineDispatcher()
        return withContext(dispatcher) {
            coroutineScope {
                hosts.flatMap { ip ->
                    FTP_PORTS.map { port ->
                        async {
                            val found = probeFtp(ip, port, net.network)
                            onProgress(checked.incrementAndGet(), total)
                            found
                        }
                    }
                }.awaitAll()
            }.filterNotNull()
                .sortedWith(compareBy({ ipToInt(java.net.InetAddress.getByName(it.ip).address).toLong() and 0xFFFFFFFFL }, { it.port }))
        }
    } finally {
        pool.shutdownNow()
    }
}

