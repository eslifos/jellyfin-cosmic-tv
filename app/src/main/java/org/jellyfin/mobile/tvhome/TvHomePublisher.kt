package org.jellyfin.mobile.tvhome

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.tv.TvContract
import android.net.Uri
import androidx.core.content.ContextCompat
import org.jellyfin.mobile.R
import org.jellyfin.mobile.utils.AndroidVersion
import org.jellyfin.mobile.utils.isTelevision
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MediaType
import timber.log.Timber
import java.time.ZoneOffset
import java.util.UUID

/**
 * The home screen of Android TV and Google TV (TvContract, Android 8+):
 *  - "Watch next" / "Play next": what the current profile left half watched and its next episodes;
 *  - the "Jellyfin Cosmic" channel with what is new in the library.
 * Each program opens the app with a link (TvLinks): jellyfincosmic://play/{id} or jellyfincosmic://details/{id}.
 * Everything is published again each time (what was finished goes), and removed on signing out.
 */
class TvHomePublisher(
    private val context: Context,
    private val apiClient: ApiClient,
) {
    private val resolver get() = context.contentResolver

    /** Only on a television with the TV provider. */
    val isSupported: Boolean
        get() = AndroidVersion.isAtLeastO && context.isTelevision()

    suspend fun publish() {
        if (!isSupported) return
        if (apiClient.accessToken == null || apiClient.baseUrl == null) {
            clear()
            return
        }
        val userId = apiClient.userApi.getCurrentUser().content.id
        publishWatchNext(userId)
        publishChannel(userId)
    }

    /** Removes the "Watch next" programs, the channel and its programs (signed out). */
    fun clear() {
        if (!isSupported) return
        try {
            resolver.delete(TvContract.WatchNextPrograms.CONTENT_URI, null, null)
            resolver.delete(TvContract.PreviewPrograms.CONTENT_URI, null, null)
            findChannel()?.let { resolver.delete(TvContract.buildChannelUri(it), null, null) }
        } catch (e: SecurityException) {
            Timber.w(e, "TV home: could not clear")
        }
    }

    /* ---------- Watch next ---------- */

    private suspend fun publishWatchNext(userId: UUID) {
        val resume = apiClient.itemsApi.getResumeItems(
            userId = userId,
            limit = WATCH_NEXT_MAX,
            mediaTypes = listOf(MediaType.VIDEO),
            enableImageTypes = IMAGE_TYPES,
            imageTypeLimit = 1,
        ).content.items
        val nextUp = apiClient.tvShowsApi.getNextUp(
            userId = userId,
            limit = WATCH_NEXT_MAX,
            enableImageTypes = IMAGE_TYPES,
            imageTypeLimit = 1,
        ).content.items
        // A resumable episode is also the next one of its series: once
        val resumeIds = resume.map { it.id }.toSet()
        val programs = resume.map { it to true } + nextUp.filterNot { it.id in resumeIds }.map { it to false }

        resolver.delete(TvContract.WatchNextPrograms.CONTENT_URI, null, null)
        var published = 0
        programs.take(WATCH_NEXT_MAX).forEachIndexed { index, (item, resumable) ->
            val values = programValues(item, TvLinks.play(item.id)).apply {
                put(
                    TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
                    if (resumable) TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE else TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT,
                )
                val position = (item.userData?.playbackPositionTicks ?: 0L) / TICKS_PER_MILLISECOND
                if (resumable && position > 0) put(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, position.toInt())
                // The order of the row: most recently watched first
                val engaged = item.userData?.lastPlayedDate?.toInstant(ZoneOffset.UTC)?.toEpochMilli()
                put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, engaged ?: (System.currentTimeMillis() - index * 1000L))
            }
            if (resolver.insert(TvContract.WatchNextPrograms.CONTENT_URI, values) != null) published++
        }
        Timber.i("TV home: %d of %d programs in Watch next", published, programs.size.coerceAtMost(WATCH_NEXT_MAX))
    }

    /* ---------- The channel ---------- */

    @SuppressLint("RestrictedApi")
    private suspend fun publishChannel(userId: UUID) {
        val latest = apiClient.userLibraryApi.getLatestMedia(
            userId = userId,
            limit = CHANNEL_MAX,
            enableImageTypes = IMAGE_TYPES,
            imageTypeLimit = 1,
            groupItems = true,
        ).content.filter { it.type == BaseItemKind.MOVIE || it.type == BaseItemKind.SERIES || it.type == BaseItemKind.EPISODE }

        val channelId = findChannel() ?: createChannel() ?: return
        resolver.delete(TvContract.PreviewPrograms.CONTENT_URI, null, null)
        latest.forEachIndexed { index, item ->
            // A new episode shows its series (as the home screen of the app does)
            val target = if (item.type == BaseItemKind.EPISODE) item.seriesId ?: item.id else item.id
            val values = programValues(item, TvLinks.details(target)).apply {
                put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, channelId)
                put(TvContract.PreviewPrograms.COLUMN_WEIGHT, CHANNEL_MAX - index)
            }
            resolver.insert(TvContract.PreviewPrograms.CONTENT_URI, values)
        }
        Timber.i("TV home: channel %d with %d programs", channelId, latest.size)
    }

    private fun findChannel(): Long? = resolver.query(
        TvContract.Channels.CONTENT_URI,
        arrayOf(TvContract.Channels._ID, TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA),
        null,
        null,
        null,
    )?.use { cursor ->
        // The provider only gives this app its own channels
        if (cursor.moveToFirst()) cursor.getLong(0) else null
    }

    private fun createChannel(): Long? {
        val values = ContentValues().apply {
            put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW)
            put(TvContract.Channels.COLUMN_DISPLAY_NAME, context.getString(R.string.tv_home_channel_name))
            put(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI, TvLinks.home().toString())
        }
        val uri = resolver.insert(TvContract.Channels.CONTENT_URI, values) ?: return null
        val channelId = uri.lastPathSegment?.toLongOrNull() ?: return null
        writeChannelLogo(channelId)
        // The first channel of the app is shown at once; the others, if the user accepts them
        TvContract.requestChannelBrowsable(context, channelId)
        return channelId
    }

    private fun writeChannelLogo(channelId: Long) {
        val drawable = ContextCompat.getDrawable(context, R.mipmap.ic_launcher) ?: return
        val bitmap = Bitmap.createBitmap(LOGO_SIZE, LOGO_SIZE, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, LOGO_SIZE, LOGO_SIZE)
        drawable.draw(Canvas(bitmap))
        try {
            resolver.openOutputStream(TvContract.buildChannelLogoUri(channelId))?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: Exception) {
            Timber.w(e, "TV home: could not write the channel logo")
        }
    }

    /* ---------- A program ---------- */

    private fun programValues(item: BaseItemDto, link: Uri) = ContentValues().apply {
        val isEpisode = item.type == BaseItemKind.EPISODE
        put(
            TvContract.PreviewPrograms.COLUMN_TYPE,
            when (item.type) {
                BaseItemKind.EPISODE -> TvContract.PreviewPrograms.TYPE_TV_EPISODE
                BaseItemKind.SERIES -> TvContract.PreviewPrograms.TYPE_TV_SERIES
                else -> TvContract.PreviewPrograms.TYPE_MOVIE
            },
        )
        put(TvContract.PreviewPrograms.COLUMN_TITLE, if (isEpisode) item.seriesName ?: item.name else item.name)
        if (isEpisode) {
            put(TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE, item.name)
            item.parentIndexNumber?.let { put(TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER, it.toString()) }
            item.indexNumber?.let { put(TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, it.toString()) }
        }
        item.overview?.let { put(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION, it.take(DESCRIPTION_MAX)) }
        item.runTimeTicks?.let { put(TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS, (it / TICKS_PER_MILLISECOND).toInt()) }
        item.productionYear?.let { put(TvContract.PreviewPrograms.COLUMN_RELEASE_DATE, it.toString()) }
        landscapeImage(item)?.let { put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, it) }
        put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, TvContract.PreviewPrograms.ASPECT_RATIO_16_9)
        put(TvContract.PreviewPrograms.COLUMN_INTENT_URI, link.toString())
        put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID, item.id.toString())
    }

    /** A 16:9 picture: the episode's own, else the backdrop or thumb of the item or of its series. */
    private fun landscapeImage(item: BaseItemDto): String? {
        val image = { id: UUID, type: ImageType, tag: String? ->
            apiClient.imageApi.getItemImageUrl(itemId = id, imageType = type, tag = tag, fillWidth = IMAGE_WIDTH, fillHeight = IMAGE_HEIGHT, quality = IMAGE_QUALITY)
        }
        return when {
            item.type == BaseItemKind.EPISODE && item.imageTags?.get(ImageType.PRIMARY) != null ->
                image(item.id, ImageType.PRIMARY, item.imageTags?.get(ImageType.PRIMARY))
            !item.backdropImageTags.isNullOrEmpty() -> image(item.id, ImageType.BACKDROP, item.backdropImageTags?.first())
            item.imageTags?.get(ImageType.THUMB) != null -> image(item.id, ImageType.THUMB, item.imageTags?.get(ImageType.THUMB))
            item.parentThumbItemId != null && item.parentThumbImageTag != null ->
                image(item.parentThumbItemId!!, ImageType.THUMB, item.parentThumbImageTag)
            item.parentBackdropItemId != null && !item.parentBackdropImageTags.isNullOrEmpty() ->
                image(item.parentBackdropItemId!!, ImageType.BACKDROP, item.parentBackdropImageTags?.first())
            item.imageTags?.get(ImageType.PRIMARY) != null -> image(item.id, ImageType.PRIMARY, item.imageTags?.get(ImageType.PRIMARY))
            else -> null
        }
    }

    companion object {
        private const val WATCH_NEXT_MAX = 20
        private const val CHANNEL_MAX = 20
        private const val TICKS_PER_MILLISECOND = 10_000L
        private const val DESCRIPTION_MAX = 300
        private const val IMAGE_WIDTH = 640
        private const val IMAGE_HEIGHT = 360
        private const val IMAGE_QUALITY = 85
        private const val LOGO_SIZE = 256
        private val IMAGE_TYPES = listOf(ImageType.PRIMARY, ImageType.BACKDROP, ImageType.THUMB)
    }
}
