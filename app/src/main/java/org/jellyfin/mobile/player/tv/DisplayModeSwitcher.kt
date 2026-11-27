package org.jellyfin.mobile.player.tv

import android.view.Window
import timber.log.Timber

/**
 * "Match the screen to the video": while a video plays, the screen takes the mode of the same resolution whose
 * refresh rate is a whole multiple of the video's frame rate (a 24 fps film at 24 Hz, without judder), and goes back
 * to its own mode afterwards. Through the window's preferred mode, which works from Android 6 and keeps the
 * resolution (Android 11+ could also use Surface.setFrameRate, only for a surface the app draws video on).
 */
class DisplayModeSwitcher(private val window: Window) {
    /** The mode the window asked for before (0: the system's choice). */
    private var originalModeId: Int? = null

    /** Switches for a video of [fps] frames per second; does nothing when the screen has no fitting mode. */
    fun match(fps: Float) {
        if (fps <= 0f) return
        val display = window.decorView.display ?: return
        val current = display.mode
        val modes = display.supportedModes.map { OutputCapabilities.Mode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val chosen = OutputCapabilities.chooseMode(
            modes,
            OutputCapabilities.Mode(current.modeId, current.physicalWidth, current.physicalHeight, current.refreshRate),
            fps,
        )
        if (chosen == null) {
            Timber.i("Frame rate %.3f: no mode of the screen fits (%d modes)", fps, modes.size)
            return
        }
        if (originalModeId == null) originalModeId = window.attributes.preferredDisplayModeId
        if (chosen.id == current.modeId) return
        Timber.i("Frame rate %.3f: screen to %.3f Hz", fps, chosen.refreshRate)
        window.attributes = window.attributes.apply { preferredDisplayModeId = chosen.id }
    }

    /** Back to the mode the screen had. */
    fun restore() {
        val original = originalModeId ?: return
        originalModeId = null
        window.attributes = window.attributes.apply { preferredDisplayModeId = original }
    }
}
