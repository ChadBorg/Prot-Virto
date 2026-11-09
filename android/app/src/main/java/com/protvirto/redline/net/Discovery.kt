package com.protvirto.redline.net

import android.os.SystemClock
import com.protvirto.redline.data.DiscoveredHost
import com.protvirto.redline.data.HostInfo
import com.protvirto.redline.data.RedlineJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** Finds REDLINE agents on the local network with a UDP broadcast. */
object Discovery {
    private const val PORT = 48102
    private val MAGIC = "REDLINE_DISCOVER".toByteArray()

    private fun broadcastAddresses(): List<InetAddress> {
        val out = mutableListOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                nif.interfaceAddresses.mapNotNullTo(out) { it.broadcast }
            }
        }
        return out.distinct()
    }

    suspend fun scan(timeoutMs: Long = 1800): List<DiscoveredHost> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, DiscoveredHost>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 250
            for (addr in broadcastAddresses()) {
                runCatching { socket.send(DatagramPacket(MAGIC, MAGIC.size, addr, PORT)) }
            }
            val end = SystemClock.elapsedRealtime() + timeoutMs
            val buf = ByteArray(4096)
            while (SystemClock.elapsedRealtime() < end) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val info = runCatching {
                    RedlineJson.decodeFromString<HostInfo>(String(packet.data, 0, packet.length))
                }.getOrNull() ?: continue
                if (info.agent != "redline") continue
                val address = packet.address.hostAddress ?: continue
                found[address] = DiscoveredHost(address, info)
            }
        }
        found.values.toList()
    }
}
