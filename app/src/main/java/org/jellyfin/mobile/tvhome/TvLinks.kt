package org.jellyfin.mobile.tvhome

import android.content.Intent
import android.net.Uri
import java.util.UUID

/**
 * The links of the programs on the TV home screen: jellyfincosmic://play/{id}, jellyfincosmic://details/{id} and
 * jellyfincosmic://home (the channel). MainActivity keeps the last one opened until the web takes it
 * (NativeInterface.takePendingLink), since at a cold start the web is not loaded yet.
 */
object TvLinks {
    const val SCHEME = "jellyfincosmic"
    private const val PLAY = "play"
    private const val DETAILS = "details"
    private const val HOME = "home"

    @Volatile
    private var pending: String? = null

    fun play(id: UUID): Uri = Uri.parse("$SCHEME://$PLAY/$id")

    fun details(id: UUID): Uri = Uri.parse("$SCHEME://$DETAILS/$id")

    fun home(): Uri = Uri.parse("$SCHEME://$HOME")

    /**
     * An intent with one of these links: kept for the web as "play/<id>" or "details/<id>". Returns whether it was
     * one of them.
     */
    fun receive(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (uri.scheme != SCHEME) return false
        val action = uri.host
        val id = uri.pathSegments.firstOrNull()?.takeIf { it.matches(ID_PATTERN) }
        pending = when {
            (action == PLAY || action == DETAILS) && id != null -> "$action/$id"
            else -> null
        }
        return true
    }

    /** The link waiting for the web (once). */
    fun take(): String? = pending.also { pending = null }

    private val ID_PATTERN = Regex("^[0-9a-fA-F-]{32,36}$")
}
