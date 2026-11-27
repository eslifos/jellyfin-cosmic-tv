package org.jellyfin.mobile.player.tv

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.jellyfin.mobile.player.tv.OutputCapabilities.ENCODING_AC3
import org.jellyfin.mobile.player.tv.OutputCapabilities.ENCODING_DOLBY_TRUEHD
import org.jellyfin.mobile.player.tv.OutputCapabilities.ENCODING_DTS_HD
import org.jellyfin.mobile.player.tv.OutputCapabilities.ENCODING_E_AC3_JOC
import org.jellyfin.mobile.player.tv.OutputCapabilities.HDR_TYPE_DOLBY_VISION
import org.jellyfin.mobile.player.tv.OutputCapabilities.HDR_TYPE_HDR10
import org.jellyfin.mobile.player.tv.OutputCapabilities.HDR_TYPE_HLG
import org.jellyfin.mobile.player.tv.OutputCapabilities.Mode
import org.jellyfin.sdk.model.api.VideoRangeType

class OutputCapabilitiesTest : FunSpec({
    test("an SDR screen only takes SDR (and Dolby Vision with an SDR base)") {
        OutputCapabilities.videoRangeTypes(emptySet(), hasDolbyVisionDecoder = true) shouldContainExactlyInAnyOrder
            listOf(VideoRangeType.SDR, VideoRangeType.DOVI_WITH_SDR)
    }

    test("an HDR10 and HLG screen takes HDR10+, and Dolby Vision through its HDR10 or HLG base") {
        OutputCapabilities.videoRangeTypes(setOf(HDR_TYPE_HDR10, HDR_TYPE_HLG), hasDolbyVisionDecoder = false) shouldContainExactlyInAnyOrder listOf(
            VideoRangeType.SDR,
            VideoRangeType.DOVI_WITH_SDR,
            VideoRangeType.HDR10,
            VideoRangeType.HDR10_PLUS,
            VideoRangeType.HLG,
            VideoRangeType.DOVI_WITH_HDR10,
            VideoRangeType.DOVI_WITH_HLG,
            VideoRangeType.DOVI_WITH_HDR10_PLUS,
        )
    }

    test("Dolby Vision alone needs the screen and the decoder") {
        OutputCapabilities.videoRangeTypes(setOf(HDR_TYPE_DOLBY_VISION), hasDolbyVisionDecoder = false)
            .contains(VideoRangeType.DOVI) shouldBe false
        OutputCapabilities.videoRangeTypes(setOf(HDR_TYPE_DOLBY_VISION), hasDolbyVisionDecoder = true)
            .contains(VideoRangeType.DOVI) shouldBe true
    }

    test("passthrough codecs follow the encodings of the output") {
        OutputCapabilities.passthroughCodecs(emptyList()) shouldBe emptyList()
        OutputCapabilities.passthroughCodecs(listOf(ENCODING_AC3, ENCODING_E_AC3_JOC, ENCODING_DTS_HD, ENCODING_DOLBY_TRUEHD)) shouldBe
            listOf("ac3", "eac3", "dts", "truehd")
    }

    val current = Mode(1, 3840, 2160, 60f)
    val modes = listOf(
        current,
        Mode(2, 3840, 2160, 23.976f),
        Mode(3, 3840, 2160, 50f),
        Mode(4, 1920, 1080, 24f),
        Mode(5, 3840, 2160, 47.952f),
    )

    test("a 23.976 fps film picks 23.976 Hz at the same resolution") {
        OutputCapabilities.chooseMode(modes, current, 23.976f)?.id shouldBe 2
    }

    test("25 fps picks 50 Hz (a whole multiple), 30 fps picks 60 Hz") {
        OutputCapabilities.chooseMode(modes, current, 25f)?.id shouldBe 3
        OutputCapabilities.chooseMode(modes, current, 30f)?.id shouldBe 1
    }

    test("no mode for an unknown frame rate, nor for one with no multiple") {
        OutputCapabilities.chooseMode(modes, current, 0f) shouldBe null
        OutputCapabilities.chooseMode(listOf(current), current, 24f) shouldBe null
    }
})
