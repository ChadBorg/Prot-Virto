package com.protvirto.redline.stream

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Mirrors host-agent/redline/protocol.py */
object Wire {
    const val VIDEO_CONFIG = 0x01
    const val VIDEO_FRAME = 0x02
    const val AUDIO_PCM = 0x03
    const val PONG = 0x04
    const val INFO = 0x05
    const val CONGESTION = 0x06
    const val ERROR = 0x07

    const val GAMEPAD = 0x10
    const val MOUSE_MOVE = 0x11
    const val MOUSE_BUTTON = 0x12
    const val MOUSE_ABS = 0x13
    const val MOUSE_WHEEL = 0x14
    const val TEXT = 0x15
    const val KEY = 0x16
    const val PING = 0x17

    const val CODEC_HEVC = 1
    const val MAX_MESSAGE = 16 * 1024 * 1024
}

class StreamClient(
    private val address: String,
    private val port: Int,
    private val hello: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onInfo(json: String)
        fun onVideoConfig(codec: Int, data: ByteArray)
        /** [data] holds the Annex-B access unit starting at [offset]. */
        fun onVideoFrame(keyframe: Boolean, ptsMs: Long, data: ByteArray, offset: Int)
        fun onAudio(pcm: ByteArray)
        fun onPong(rttMs: Double)
        fun onCongestion(droppedFrames: Int)
        fun onHostError(message: String)
        fun onClosed(source: StreamClient, error: Throwable?)
    }

    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "redline-send").apply { isDaemon = true } }
    val bytesReceived = AtomicLong()

    fun start() {
        running = true
        thread(name = "redline-net", isDaemon = true, priority = Thread.MAX_PRIORITY) { loop() }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        sender.shutdownNow()
    }

    private fun loop() {
        var error: Throwable? = null
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.receiveBufferSize = 4 * 1024 * 1024
            s.setPerformancePreferences(0, 2, 1) // latency over bandwidth
            s.trafficClass = 0xB8 // DSCP EF: hint routers that this is real-time traffic
            s.connect(InetSocketAddress(address, port), 6000)
            socket = s
            val o = s.getOutputStream()
            out = o
            synchronized(o) {
                o.write((hello + "\n").toByteArray(Charsets.UTF_8))
                o.flush()
            }
            val input = DataInputStream(BufferedInputStream(s.getInputStream(), 512 * 1024))
            while (running) {
                val type = input.readUnsignedByte()
                val len = input.readInt()
                if (len < 0 || len > Wire.MAX_MESSAGE) throw IOException("corrupt stream (len=$len)")
                val payload = ByteArray(len)
                input.readFully(payload)
                bytesReceived.addAndGet(len + 5L)
                dispatch(type, payload)
            }
        } catch (t: Throwable) {
            if (running) error = t
        } finally {
            running = false
            runCatching { socket?.close() }
            listener.onClosed(this, error)
        }
    }

    private fun dispatch(type: Int, p: ByteArray) {
        when (type) {
            Wire.VIDEO_FRAME -> if (p.size > 5) {
                val pts = ((p[1].toLong() and 0xFF) shl 24) or ((p[2].toLong() and 0xFF) shl 16) or
                    ((p[3].toLong() and 0xFF) shl 8) or (p[4].toLong() and 0xFF)
                listener.onVideoFrame((p[0].toInt() and 1) == 1, pts, p, 5)
            }
            Wire.AUDIO_PCM -> listener.onAudio(p)
            Wire.VIDEO_CONFIG -> if (p.isNotEmpty()) listener.onVideoConfig(p[0].toInt(), p.copyOfRange(1, p.size))
            Wire.PONG -> if (p.size >= 8) {
                val sent = ByteBuffer.wrap(p, 0, 8).long
                listener.onPong((System.nanoTime() - sent) / 1e6)
            }
            Wire.INFO -> listener.onInfo(String(p, Charsets.UTF_8))
            Wire.CONGESTION -> listener.onCongestion(if (p.size >= 4) ByteBuffer.wrap(p).int else 1)
            Wire.ERROR -> listener.onHostError(String(p, Charsets.UTF_8))
        }
    }

    // ---- client -> host -----------------------------------------------------
    fun send(type: Int, payload: ByteArray = ByteArray(0)) {
        val o = out ?: return
        val msg = ByteBuffer.allocate(5 + payload.size).put(type.toByte()).putInt(payload.size).put(payload).array()
        try {
            sender.execute {
                try {
                    synchronized(o) { o.write(msg); o.flush() }
                } catch (_: IOException) {
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    fun ping() = send(Wire.PING, ByteBuffer.allocate(8).putLong(System.nanoTime()).array())

    fun mouseMove(dx: Int, dy: Int) = send(Wire.MOUSE_MOVE,
        ByteBuffer.allocate(4).putShort(dx.coerceIn(-32768, 32767).toShort()).putShort(dy.coerceIn(-32768, 32767).toShort()).array())

    fun mouseAbs(x: Int, y: Int) = send(Wire.MOUSE_ABS,
        ByteBuffer.allocate(4).putShort(x.coerceIn(0, 65535).toShort()).putShort(y.coerceIn(0, 65535).toShort()).array())

    fun mouseButton(button: Int, down: Boolean) = send(Wire.MOUSE_BUTTON, byteArrayOf(button.toByte(), if (down) 1 else 0))

    fun mouseWheel(delta: Int) = send(Wire.MOUSE_WHEEL, ByteBuffer.allocate(2).putShort(delta.coerceIn(-32768, 32767).toShort()).array())

    fun text(text: String) = send(Wire.TEXT, text.toByteArray(Charsets.UTF_8))

    fun key(vk: Int, down: Boolean) = send(Wire.KEY, ByteBuffer.allocate(3).putShort(vk.toShort()).put(if (down) 1 else 0).array())
}
