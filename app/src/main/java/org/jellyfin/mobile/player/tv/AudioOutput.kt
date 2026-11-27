package org.jellyfin.mobile.player.tv

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import androidx.core.content.getSystemService
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import org.jellyfin.mobile.utils.Constants
import timber.log.Timber

/**
 * The audio output setting of the integrated player, as the capabilities its audio sink works with:
 *  - automatic: what the system reports (passthrough when the TV or receiver takes it, honoring its surround setting);
 *  - stereo: never passthrough, everything decoded on the device;
 *  - direct over HDMI: the compressed formats the HDMI device itself reports, even with the system's surround off.
 */
@UnstableApi
object AudioOutput {
    private const val HDMI_EARC = 29 // AudioDeviceInfo.TYPE_HDMI_EARC (API 31)
    private val HDMI_TYPES = setOf(AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, HDMI_EARC)
    private const val MAX_CHANNELS = 8

    /** Null for the automatic choice of the player. */
    fun capabilities(context: Context, setting: String): AudioCapabilities? = when (setting) {
        Constants.AUDIO_OUTPUT_STEREO -> AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES
        Constants.AUDIO_OUTPUT_PASSTHROUGH -> hdmiCapabilities(context)
        else -> null
    }

    private fun hdmiCapabilities(context: Context): AudioCapabilities? {
        val devices = context.getSystemService<AudioManager>()?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
        val hdmi = devices.filter { it.type in HDMI_TYPES }
        val encodings = hdmi.flatMap { it.encodings.toList() }.toSet()
        if (OutputCapabilities.passthroughCodecs(encodings).isEmpty()) {
            Timber.i("Audio: no HDMI device with surround formats, automatic output")
            return null
        }
        val channels = hdmi.flatMap { it.channelCounts.toList() }.maxOrNull()?.coerceAtMost(MAX_CHANNELS) ?: MAX_CHANNELS
        Timber.i("Audio: direct over HDMI, %s", OutputCapabilities.passthroughCodecs(encodings))
        return AudioCapabilities((encodings + AudioFormat.ENCODING_PCM_16BIT).toIntArray(), channels)
    }
}
