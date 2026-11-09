package com.protvirto.redline.stream

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max

/**
 * Turns Android gamepad events (the Thor's built-in controls or any BT pad)
 * into XInput reports for the virtual Xbox 360 controller on the PC.
 */
class GamepadInput(private val send: (ByteArray) -> Unit) {
    companion object {
        const val DPAD_UP = 0x0001
        const val DPAD_DOWN = 0x0002
        const val DPAD_LEFT = 0x0004
        const val DPAD_RIGHT = 0x0008
        const val START = 0x0010
        const val BACK = 0x0020
        const val LEFT_THUMB = 0x0040
        const val RIGHT_THUMB = 0x0080
        const val LEFT_SHOULDER = 0x0100
        const val RIGHT_SHOULDER = 0x0200
        const val GUIDE = 0x0400
        const val A = 0x1000
        const val B = 0x2000
        const val X = 0x4000
        const val Y = 0x8000

        fun isGamepad(source: Int): Boolean =
            source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
                source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD
    }

    private var buttons = 0
    private var hat = 0
    private var digitalLt = false
    private var digitalRt = false
    private var analogLt = 0
    private var analogRt = 0
    private var lx = 0
    private var ly = 0
    private var rx = 0
    private var ry = 0
    private var last: ByteArray? = null

    /** True while SELECT + START are held: opens the REDLINE menu. */
    val menuCombo: Boolean get() = buttons and (START or BACK) == (START or BACK)

    private fun bitFor(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> A
        KeyEvent.KEYCODE_BUTTON_B -> B
        KeyEvent.KEYCODE_BUTTON_X -> X
        KeyEvent.KEYCODE_BUTTON_Y -> Y
        KeyEvent.KEYCODE_BUTTON_L1 -> LEFT_SHOULDER
        KeyEvent.KEYCODE_BUTTON_R1 -> RIGHT_SHOULDER
        KeyEvent.KEYCODE_BUTTON_THUMBL -> LEFT_THUMB
        KeyEvent.KEYCODE_BUTTON_THUMBR -> RIGHT_THUMB
        KeyEvent.KEYCODE_BUTTON_START -> START
        KeyEvent.KEYCODE_BUTTON_SELECT -> BACK
        KeyEvent.KEYCODE_BUTTON_MODE -> GUIDE
        KeyEvent.KEYCODE_DPAD_UP -> DPAD_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> DPAD_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> DPAD_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> DPAD_RIGHT
        else -> null
    }

    fun onKey(event: KeyEvent): Boolean {
        if (!isGamepad(event.source)) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> digitalLt = down
            KeyEvent.KEYCODE_BUTTON_R2 -> digitalRt = down
            else -> {
                val bit = bitFor(event.keyCode) ?: return false
                buttons = if (down) buttons or bit else buttons and bit.inv()
            }
        }
        if (event.repeatCount == 0) push()
        return true
    }

    private fun axis(e: MotionEvent, axis: Int): Float {
        val v = e.getAxisValue(axis)
        val flat = e.device?.getMotionRange(axis, e.source)?.flat ?: 0f
        return if (abs(v) <= max(flat, 0.04f)) 0f else v
    }

    private fun stick(v: Float): Int = (v * 32767f).toInt().coerceIn(-32768, 32767)

    fun onMotion(e: MotionEvent): Boolean {
        if (e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK || e.action != MotionEvent.ACTION_MOVE) {
            return false
        }
        val device = e.device
        val hasZ = device?.getMotionRange(MotionEvent.AXIS_Z, e.source) != null
        lx = stick(axis(e, MotionEvent.AXIS_X))
        ly = stick(-axis(e, MotionEvent.AXIS_Y))
        rx = stick(axis(e, if (hasZ) MotionEvent.AXIS_Z else MotionEvent.AXIS_RX))
        ry = stick(-axis(e, if (hasZ) MotionEvent.AXIS_RZ else MotionEvent.AXIS_RY))
        val l = max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
        val r = max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))
        analogLt = (l * 255).toInt().coerceIn(0, 255)
        analogRt = (r * 255).toInt().coerceIn(0, 255)
        val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        hat = (if (hx < -0.5f) DPAD_LEFT else 0) or (if (hx > 0.5f) DPAD_RIGHT else 0) or
            (if (hy < -0.5f) DPAD_UP else 0) or (if (hy > 0.5f) DPAD_DOWN else 0)
        push()
        return true
    }

    private fun push() {
        val lt = if (digitalLt) 255 else analogLt
        val rt = if (digitalRt) 255 else analogRt
        val report = ByteBuffer.allocate(12)
            .putShort((buttons or hat).toShort())
            .put(lt.toByte()).put(rt.toByte())
            .putShort(lx.toShort()).putShort(ly.toShort())
            .putShort(rx.toShort()).putShort(ry.toShort())
            .array()
        if (last?.contentEquals(report) == true) return
        last = report
        send(report)
    }

    fun releaseAll() {
        buttons = 0; hat = 0; digitalLt = false; digitalRt = false
        analogLt = 0; analogRt = 0; lx = 0; ly = 0; rx = 0; ry = 0
        push()
    }
}
