package com.protvirto.redline.stream

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class StreamStats {
    val framesReceived = AtomicInteger()
    val framesRendered = AtomicInteger()
    val framesDropped = AtomicInteger()
    val decodeNanosTotal = AtomicLong()
    val decodeSamples = AtomicInteger()
}

/**
 * Hardware decoder fed straight from the network. Two input/output threads keep
 * the codec busy; frames are rendered the moment they come out ("latency"
 * pacing) or handed to the compositor with a vsync timestamp ("smooth").
 */
class VideoDecoder(
    private val surface: Surface,
    private val codecType: Int,
    private val width: Int,
    private val height: Int,
    private val smooth: Boolean,
    private val latencyGuard: Boolean,
    private val stats: StreamStats,
) {
    private class Frame(val data: ByteArray, val offset: Int, val length: Int, val ptsUs: Long, val flags: Int)

    private val queue = LinkedBlockingDeque<Frame>()
    private val inFlight = ConcurrentSkipListMap<Long, Long>()
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var running = false
    @Volatile private var waitingForKey = true
    private var threads = emptyList<Thread>()

    private val mime = if (codecType == Wire.CODEC_HEVC) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

    private fun format(tuned: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(mime, if (width > 0) width else 1920, if (height > 0) height else 1080).apply {
            if (!tuned) return@apply
            setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            // Qualcomm (Snapdragon 8 Gen 2 in the Thor) vendor low-latency switches
            setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
            setInteger("vendor.qti-ext-dec-picture-order.enable", 1)
            setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
        }

    fun start(csd: ByteArray) {
        val c = MediaCodec.createDecoderByType(mime)
        try {
            c.configure(format(tuned = true), surface, null, 0)
        } catch (e: Exception) {
            Log.w(TAG, "tuned configure failed, falling back", e)
            c.reset()
            c.configure(format(tuned = false), surface, null, 0)
        }
        c.start()
        codec = c
        running = true
        queue.addFirst(Frame(csd, 0, csd.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG))
        threads = listOf(
            thread(name = "redline-dec-in", isDaemon = true, priority = Thread.MAX_PRIORITY) { inputLoop() },
            thread(name = "redline-dec-out", isDaemon = true, priority = Thread.MAX_PRIORITY) { outputLoop() },
        )
    }

    fun submit(keyframe: Boolean, ptsMs: Long, data: ByteArray, offset: Int) {
        stats.framesReceived.incrementAndGet()
        if (!running) return
        if (waitingForKey) {
            if (!keyframe) {
                stats.framesDropped.incrementAndGet(); return
            }
            waitingForKey = false
        }
        if (latencyGuard && queue.size >= 4) {
            // The decoder is falling behind: throw away the backlog and resync on a keyframe.
            stats.framesDropped.addAndGet(queue.size)
            queue.removeIf { it.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 }
            if (!keyframe) {
                waitingForKey = true
                stats.framesDropped.incrementAndGet()
                return
            }
        }
        queue.add(Frame(data, offset, data.size - offset, ptsMs * 1000, if (keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0))
    }

    private fun inputLoop() {
        while (running) {
            val f = try {
                queue.poll(50, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                break
            } ?: continue
            val c = codec ?: break
            try {
                val idx = c.dequeueInputBuffer(20_000)
                if (idx < 0) {
                    queue.addFirst(f); continue
                }
                val buf = c.getInputBuffer(idx) ?: continue
                buf.clear()
                if (f.length > buf.capacity()) {
                    c.queueInputBuffer(idx, 0, 0, f.ptsUs, 0)
                    waitingForKey = true
                    continue
                }
                buf.put(f.data, f.offset, f.length)
                c.queueInputBuffer(idx, 0, f.length, f.ptsUs, f.flags)
                if (f.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) inFlight[f.ptsUs] = System.nanoTime()
            } catch (e: IllegalStateException) {
                if (running) Log.w(TAG, "input", e)
                break
            }
        }
    }

    private fun outputLoop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val c = codec ?: break
            try {
                var idx = c.dequeueOutputBuffer(info, 20_000)
                if (idx < 0) continue
                if (!smooth) {
                    // Lowest latency: if newer frames are already decoded, skip straight to the newest.
                    while (true) {
                        val next = c.dequeueOutputBuffer(info, 0)
                        if (next < 0) break
                        c.releaseOutputBuffer(idx, false)
                        stats.framesDropped.incrementAndGet()
                        idx = next
                    }
                    c.releaseOutputBuffer(idx, true)
                } else {
                    c.releaseOutputBuffer(idx, System.nanoTime())
                }
                stats.framesRendered.incrementAndGet()
                val pts = info.presentationTimeUs
                inFlight.remove(pts)?.let { t0 ->
                    stats.decodeNanosTotal.addAndGet(System.nanoTime() - t0)
                    stats.decodeSamples.incrementAndGet()
                }
                inFlight.headMap(pts).clear()
            } catch (e: IllegalStateException) {
                if (running) Log.w(TAG, "output", e)
                break
            }
        }
    }

    fun stop() {
        running = false
        threads.forEach { runCatching { it.join(300) } }
        codec?.let { c ->
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        codec = null
        queue.clear()
    }

    private companion object {
        const val TAG = "RedlineDecoder"
    }
}
