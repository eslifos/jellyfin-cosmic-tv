package org.jellyfin.mobile.player.tv

/**
 * The size of the subtitles, with the names of the web player (its subtitle appearance "textSize", where "medium"
 * is its default, stored empty), so one setting sizes both players.
 */
object SubtitleSize {
    const val SMALLER = "smaller"
    const val SMALL = "small"
    const val MEDIUM = "medium"
    const val LARGE = "large"
    const val LARGER = "larger"
    const val EXTRA_LARGE = "extralarge"

    val ALL = listOf(SMALLER, SMALL, MEDIUM, LARGE, LARGER, EXTRA_LARGE)

    /** The font size of the web player for each one, in em (subtitleappearancehelper.js); medium is 1.36em. */
    private val WEB_EM = mapOf(
        SMALLER to 0.8f,
        SMALL to 1f,
        MEDIUM to 1.36f,
        LARGE to 1.72f,
        LARGER to 2f,
        EXTRA_LARGE to 2.2f,
    )

    /** A known size, else medium (the web stores medium as empty). */
    fun normalize(value: String?): String = if (value in ALL) value!! else MEDIUM

    /** How much larger than the player's default (medium) the subtitles are, as in the web player. */
    fun scale(value: String?): Float = WEB_EM.getValue(normalize(value)) / WEB_EM.getValue(MEDIUM)
}
