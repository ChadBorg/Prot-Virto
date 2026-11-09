package com.protvirto.redline.stream

import android.app.Presentation
import android.os.Bundle
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.protvirto.redline.ui.theme.RedlineTheme

/**
 * Renders Compose content on the AYN Thor's second (bottom) screen while the
 * game streams on the main screen.
 */
class BottomScreenHud(
    private val activity: ComponentActivity,
    display: Display,
    private val content: @Composable () -> Unit,
) : Presentation(activity, display) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.decorView?.let {
            it.setViewTreeLifecycleOwner(activity)
            it.setViewTreeViewModelStoreOwner(activity)
            it.setViewTreeSavedStateRegistryOwner(activity)
        }
        setContentView(ComposeView(context).apply {
            setViewTreeLifecycleOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setContent { RedlineTheme { content() } }
        })
    }
}
