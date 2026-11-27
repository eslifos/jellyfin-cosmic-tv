package org.jellyfin.mobile.player.tv

import org.jellyfin.sdk.model.api.VideoRangeType

/**
 * What the screen and the audio output of the TV can take, decided from what the system reports. Pure functions,
 * so they can be tested without a device (OutputCapabilitiesTest).
 */
object OutputCapabilities {
    // Display.HdrCapabilities (API 24)
    const val HDR_TYPE_DOLBY_VISION = 1
    const val HDR_TYPE_HDR10 = 2
    const val HDR_TYPE_HLG = 3
    const val HDR_TYPE_HDR10_PLUS = 4

    // AudioFormat encodings of compressed (passthrough) audio
    const val ENCODING_AC3 = 5
    const val ENCODING_E_AC3 = 6
    const val ENCODING_DTS = 7
    const val ENCODING_DTS_HD = 8
    const val ENCODING_DOLBY_TRUEHD = 14
    const val ENCODING_E_AC3_JOC = 18

    /**
     * The video ranges the device plays as they are: SDR always; each HDR format when the screen reports it; and Dolby
     * Vision when the screen reports it and there is a Dolby Vision decoder. A Dolby Vision file with an HDR10, HLG or
     * SDR base layer also plays through its base layer. Anything else is tone mapped by the server.
     */
    fun videoRangeTypes(hdrTypes: Set<Int>, hasDolbyVisionDecoder: Boolean): List<VideoRangeType> {
        val hdr10 = HDR_TYPE_HDR10 in hdrTypes
        // HDR10+ files carry HDR10 static metadata: an HDR10 screen shows them
        val hdr10Plus = HDR_TYPE_HDR10_PLUS in hdrTypes || hdr10
        val hlg = HDR_TYPE_HLG in hdrTypes
        val dolbyVision = HDR_TYPE_DOLBY_VISION in hdrTypes && hasDolbyVisionDecoder
        return buildList {
            add(VideoRangeType.SDR)
            add(VideoRangeType.DOVI_WITH_SDR)
            if (hdr10) add(VideoRangeType.HDR10)
            if (hdr10Plus) add(VideoRangeType.HDR10_PLUS)
            if (hlg) add(VideoRangeType.HLG)
            if (dolbyVision) add(VideoRangeType.DOVI)
            if (dolbyVision || hdr10) add(VideoRangeType.DOVI_WITH_HDR10)
            if (dolbyVision || hlg) add(VideoRangeType.DOVI_WITH_HLG)
            if (dolbyVision || hdr10Plus) add(VideoRangeType.DOVI_WITH_HDR10_PLUS)
        }
    }

    /** The audio codecs (Jellyfin names) the output takes compressed, to pass them through as they are. */
    fun passthroughCodecs(encodings: Collection<Int>): List<String> = buildList {
        if (ENCODING_AC3 in encodings) add("ac3")
        if (ENCODING_E_AC3 in encodings || ENCODING_E_AC3_JOC in encodings) add("eac3")
        if (ENCODING_DTS in encodings || ENCODING_DTS_HD in encodings) add("dts")
        if (ENCODING_DOLBY_TRUEHD in encodings) add("truehd")
    }

    /** A mode of the screen (Display.Mode). */
    data class Mode(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

    /** How far (relative) a refresh rate may be from an exact multiple of the frame rate: 23.976 fits 47.952, not 48. */
    private const val MULTIPLE_TOLERANCE = 0.002f

    /**
     * The mode of the same resolution as [current] whose refresh rate is the smallest whole multiple of [fps] (24 fps:
     * 24 Hz, else 48, else 72…); null when there is none, or [fps] is not known.
     */
    fun chooseMode(modes: List<Mode>, current: Mode, fps: Float): Mode? {
        if (fps <= 0f) return null
        return modes
            .filter { it.width == current.width && it.height == current.height }
            .filter { mode ->
                val ratio = mode.refreshRate / fps
                val multiple = Math.round(ratio)
                multiple >= 1 && kotlin.math.abs(ratio - multiple) / multiple <= MULTIPLE_TOLERANCE
            }
            .minByOrNull { it.refreshRate }
    }
}
