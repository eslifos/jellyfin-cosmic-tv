package org.jellyfin.mobile.bridge

import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.player.tv.SubtitleSize
import org.json.JSONObject
import timber.log.Timber

/**
 * The settings of the TV that live in the web (per user) and also show in the native settings: the web tells their
 * values (NativeInterface.setTvSettings, on start and on each change) and the native settings send a change back
 * ([changeScript], the event "cosmictvsetting" of the web, TvMenu/tvSettings.ts).
 */
object TvSettings {
    const val TRAILERS = "trailers"
    const val SUBTITLE_SIZE = "subtitleSize"

    /** Keeps the values the web tells (a JSON object with any of the names above). */
    fun receive(appPreferences: AppPreferences, json: String) {
        try {
            val values = JSONObject(json)
            if (values.has(TRAILERS)) appPreferences.tvTrailers = values.getBoolean(TRAILERS)
            if (values.has(SUBTITLE_SIZE)) appPreferences.subtitleSize = SubtitleSize.normalize(values.optString(SUBTITLE_SIZE))
        } catch (e: org.json.JSONException) {
            Timber.w(e, "TV settings: not understood")
        }
    }

    /** The script that hands a change of the native settings to the web. */
    fun changeScript(name: String, value: Any): String {
        val detail = JSONObject().put("name", name).put("value", value)
        return "window.dispatchEvent(new CustomEvent('cosmictvsetting', { detail: $detail }));"
    }
}
