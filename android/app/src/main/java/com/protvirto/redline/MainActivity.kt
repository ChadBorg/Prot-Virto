package com.protvirto.redline

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.protvirto.redline.ui.AppViewModel
import com.protvirto.redline.ui.Tab
import com.protvirto.redline.ui.screens.RootScreen
import com.protvirto.redline.ui.theme.RedlineTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        immersive()
        setContent { RedlineTheme { RootScreen(vm) } }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshNow()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    private fun immersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * Makes the whole UI drivable with the Thor's controls:
     * D-pad moves focus, A selects, B goes back, L1/R1 switch tabs.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1 -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && vm.hosts.value.isNotEmpty()) {
                    val tabs = Tab.entries
                    val step = if (event.keyCode == KeyEvent.KEYCODE_BUTTON_R1) 1 else -1
                    vm.tab.value = tabs[(vm.tab.value.ordinal + step + tabs.size) % tabs.size]
                }
                return true
            }
        }
        val mapped = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_DPAD_CENTER
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BACK
            else -> null
        }
        if (mapped != null) {
            return super.dispatchKeyEvent(
                KeyEvent(event.downTime, event.eventTime, event.action, mapped, event.repeatCount, event.metaState,
                    event.deviceId, event.scanCode, event.flags, event.source)
            )
        }
        return super.dispatchKeyEvent(event)
    }
}
