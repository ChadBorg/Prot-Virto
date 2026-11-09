package com.protvirto.redline.stream

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** 48 kHz stereo PCM from the PC's speakers. Keeps at most ~60 ms queued. */
class AudioPlayer {
    private val queue = ArrayBlockingQueue<ByteArray>(6)
    @Volatile private var running = false
    private val track: AudioTrack

    init {
        val min = AudioTrack.getMinBufferSize(48_000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(48_000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min, 1920 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    fun start() {
        running = true
        track.play()
        thread(name = "redline-audio", isDaemon = true, priority = Thread.MAX_PRIORITY) {
            while (running) {
                val block = try {
                    queue.poll(100, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    break
                } ?: continue
                track.write(block, 0, block.size)
            }
        }
    }

    fun submit(pcm: ByteArray) {
        if (!running) return
        if (!queue.offer(pcm)) {
            queue.poll() // drop the oldest block rather than drift behind the picture
            queue.offer(pcm)
        }
    }

    fun stop() {
        running = false
        runCatching { track.pause(); track.flush(); track.release() }
    }
}
