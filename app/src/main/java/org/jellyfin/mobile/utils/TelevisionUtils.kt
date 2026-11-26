package org.jellyfin.mobile.utils

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.core.content.getSystemService

/**
 * Whether the app runs on a television: Android TV and Google TV say so through the UI mode, and the devices that
 * do not (some Fire TV and older boxes) still have the TV launcher feature.
 */
fun Context.isTelevision(): Boolean =
    getSystemService<UiModeManager>()?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
