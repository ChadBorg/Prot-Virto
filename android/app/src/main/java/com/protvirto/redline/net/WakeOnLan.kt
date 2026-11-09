package com.protvirto.redline.net

import com.protvirto.redline.data.SavedHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Wake-on-LAN. On the same network a broadcast magic packet is enough.
 * From outside (hotel Wi-Fi, a friend's place...) the packet can't cross the
 * internet, so we ask an always-on relay agent at home to send it for us.
 */
object WakeOnLan {
    fun parseMac(mac: String): ByteArray? {
        val hex = mac.filter { it.isLetterOrDigit() }
        if (hex.length != 12) return null
        return runCatching { ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
    }

    fun magicPacket(mac: ByteArray): ByteArray =
        ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { mac[it % 6] }

    /** Returns a human readable summary of what was attempted. */
    suspend fun wake(host: SavedHost, api: AgentApi): String = withContext(Dispatchers.IO) {
        val mac = parseMac(host.mac) ?: throw ApiException("No MAC address saved for this PC")
        val packet = magicPacket(mac)
        val routes = mutableListOf<String>()
        runCatching {
            DatagramSocket().use { s ->
                s.broadcast = true
                val targets = (listOf(host.broadcast, "255.255.255.255") + host.addresses()).distinct()
                for (t in targets) for (port in intArrayOf(9, 7)) {
                    runCatching { s.send(DatagramPacket(packet, packet.size, InetAddress.getByName(t), port)) }
                }
            }
            routes += "LAN broadcast"
        }
        if (host.relayAddress.isNotBlank()) {
            try {
                api.relayWake(host.relayAddress.trim(), host.mac)
                routes += "relay ${host.relayAddress}"
            } catch (e: Exception) {
                routes += "relay failed (${e.message})"
            }
        }
        "Wake signal sent via ${routes.joinToString(" + ")}"
    }
}
