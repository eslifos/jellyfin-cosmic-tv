@file:Suppress("unused", "NOTHING_TO_INLINE")

package org.jellyfin.mobile.utils

import android.content.Context
import android.content.res.Resources
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.view.Window
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

inline fun Context.toast(@StringRes text: Int, duration: Int = Toast.LENGTH_SHORT) =
    Toast.makeText(this, text, duration).show()

inline fun Context.toast(text: CharSequence, duration: Int = Toast.LENGTH_SHORT) =
    Toast.makeText(this, text, duration).show()

inline fun LifecycleOwner.runOnUiThread(noinline block: suspend CoroutineScope.() -> Unit) {
    lifecycleScope.launch(Dispatchers.Main, block = block)
}

fun LayoutInflater.withThemedContext(context: Context, @StyleRes style: Int): LayoutInflater {
    return cloneInContext(ContextThemeWrapper(context, style))
}

/**
 * Keeps the view off the system bars. With [aboveKeyboard], also above the on-screen keyboard while it shows: the view
 * gets shorter, so a field being typed in is not hidden under the keyboard (the window is drawn edge to edge and does
 * not resize for it by itself).
 */
fun View.applyWindowInsetsAsMargins(aboveKeyboard: Boolean = false) {
    val types = WindowInsetsCompat.Type.systemBars() or
        (if (aboveKeyboard) WindowInsetsCompat.Type.ime() else 0)
    ViewCompat.setOnApplyWindowInsetsListener(this) { _, windowInsets ->
        val insets = windowInsets.getInsets(types)
        updateLayoutParams<MarginLayoutParams> {
            setMargins(insets.left, insets.top, insets.right, insets.bottom)
        }
        windowInsets
    }
}

fun View.fadeIn() {
    alpha = 0f
    isVisible = true
    animate().apply {
        alpha(1f)
        @Suppress("MagicNumber")
        duration = 300L
        interpolator = LinearOutSlowInInterpolator()
        withLayer()
    }
}

inline fun Resources.dip(px: Int) = (px * displayMetrics.density).toInt()

inline var Window.brightness: Float
    get() = attributes.screenBrightness
    set(value) {
        attributes = attributes.apply {
            screenBrightness = value
        }
    }
