package com.protvirto.redline.stream

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Touch screen -> PC mouse.
 *  touchpad: drag = move, tap = left click, two-finger tap = right click,
 *            two-finger drag = scroll, long-press then drag = click & drag
 *  direct:   the cursor jumps to your finger and clicks there
 */
class TouchInput(private val client: () -> StreamClient?, var mode: String, private val sensitivity: Float = 1.6f) {
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var maxPointers = 0
    private var moved = false
    private var dragging = false
    private var accX = 0f
    private var accY = 0f
    private var accScroll = 0f
    private val longPress = Runnable {
        if (!moved && maxPointers == 1) {
            dragging = true
            client()?.mouseButton(1, true)
        }
    }

    fun onTouch(view: View, e: MotionEvent): Boolean {
        if (mode == "direct") return direct(view, e)
        val c = client() ?: return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y; downX = e.x; downY = e.y
                downTime = SystemClock.uptimeMillis()
                maxPointers = 1; moved = false; dragging = false
                view.postDelayed(longPress, 450)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, e.pointerCount)
                view.removeCallbacks(longPress)
                lastY = avgY(e)
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    val y = avgY(e)
                    accScroll += (lastY - y)
                    lastY = y
                    val ticks = (accScroll / 30f).toInt()
                    if (ticks != 0) {
                        c.mouseWheel(ticks * 120); accScroll -= ticks * 30f; moved = true
                    }
                } else {
                    val dx = (e.x - lastX) * sensitivity
                    val dy = (e.y - lastY) * sensitivity
                    lastX = e.x; lastY = e.y
                    if (hypot(e.x - downX, e.y - downY) > 12f) {
                        moved = true
                        if (!dragging) view.removeCallbacks(longPress)
                    }
                    accX += dx; accY += dy
                    val ix = accX.toInt(); val iy = accY.toInt()
                    if (ix != 0 || iy != 0) {
                        c.mouseMove(ix, iy); accX -= ix; accY -= iy
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.removeCallbacks(longPress)
                val quick = SystemClock.uptimeMillis() - downTime < 220
                when {
                    dragging -> c.mouseButton(1, false)
                    quick && !moved && maxPointers >= 2 -> { c.mouseButton(2, true); c.mouseButton(2, false) }
                    quick && !moved && e.actionMasked == MotionEvent.ACTION_UP -> { c.mouseButton(1, true); c.mouseButton(1, false) }
                }
                dragging = false
            }
        }
        return true
    }

    private fun avgY(e: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until e.pointerCount) sum += e.getY(i)
        return sum / e.pointerCount
    }

    private fun direct(view: View, e: MotionEvent): Boolean {
        val c = client() ?: return true
        if (view.width == 0 || view.height == 0) return true
        val nx = (e.x / view.width * 65535).toInt()
        val ny = (e.y / view.height * 65535).toInt()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { c.mouseAbs(nx, ny); c.mouseButton(1, true); lastX = e.x; lastY = e.y }
            MotionEvent.ACTION_MOVE -> if (abs(e.x - lastX) + abs(e.y - lastY) > 2f) {
                c.mouseAbs(nx, ny); lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { c.mouseAbs(nx, ny); c.mouseButton(1, false) }
        }
        return true
    }
}
