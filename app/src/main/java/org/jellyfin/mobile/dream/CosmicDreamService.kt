package org.jellyfin.mobile.dream

import android.animation.ObjectAnimator
import android.graphics.drawable.Drawable
import android.service.dreams.DreamService
import android.text.format.DateFormat
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextClock
import android.widget.TextView
import androidx.core.view.isVisible
import coil3.ImageLoader
import coil3.asDrawable
import coil3.request.ImageRequest
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import timber.log.Timber
import java.util.Locale
import kotlin.random.Random

/**
 * The screensaver of the TV (Settings > Screensaver > Jellyfin Cosmic): backdrops of the films and series of the
 * library, one every few seconds with a slow zoom, their logo (or title) and year, the time and date, over the animated
 * Cosmic background, which stays alone when there is no server or no network. Any key of the remote wakes the TV.
 * The text moves a little with each backdrop (and each minute without them), so nothing marks the screen.
 */
class CosmicDreamService : DreamService(), KoinComponent {
    private class Slide(val backdrop: String, val logo: String?, val title: String, val year: Int?)

    private val apiClient: ApiClient by inject()
    private val imageLoader: ImageLoader by inject()
    private val scope = MainScope()

    private lateinit var front: ImageView
    private lateinit var back: ImageView
    private lateinit var info: View
    private lateinit var logo: ImageView
    private lateinit var title: TextView
    private lateinit var year: TextView
    private lateinit var clockBox: View

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        setContentView(R.layout.dream_cosmic)
        front = findViewById(R.id.dream_front)
        back = findViewById(R.id.dream_back)
        info = findViewById(R.id.dream_info)
        logo = findViewById(R.id.dream_logo)
        title = findViewById(R.id.dream_title)
        year = findViewById(R.id.dream_year)
        clockBox = findViewById(R.id.dream_clock_box)
        // The date in the words and order of the language of the TV ("jueves, 27 de noviembre")
        val datePattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), DATE_SKELETON)
        findViewById<TextClock>(R.id.dream_date).apply {
            format12Hour = datePattern
            format24Hour = datePattern
        }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        scope.launch {
            // The backdrops while they load; without them (or when none loads) the background alone for a few
            // minutes, and the library is asked again (a busy server may answer later)
            while (isActive) {
                val slides = loadSlides()
                if (slides.isNotEmpty()) show(slides)
                drift()
            }
        }
    }

    override fun onDreamingStopped() {
        scope.coroutineContext.cancelChildren()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        super.onDetachedFromWindow()
    }

    /** Films and series of the library with a backdrop, at random (the session of the app; none without one). */
    private suspend fun loadSlides(): List<Slide> = try {
        val user = get<ApiClientController>().loadSavedServerUser()?.user
        if (user?.accessToken == null) {
            emptyList()
        } else {
            apiClient.itemsApi.getItems(
                userId = user.userId,
                includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
                recursive = true,
                sortBy = listOf(ItemSortBy.RANDOM),
                imageTypes = listOf(ImageType.BACKDROP),
                enableImageTypes = listOf(ImageType.BACKDROP, ImageType.LOGO),
                imageTypeLimit = 1,
                limit = SLIDE_COUNT,
            ).content.items.mapNotNull { item ->
                val backdropTag = item.backdropImageTags?.firstOrNull() ?: return@mapNotNull null
                Slide(
                    backdrop = apiClient.imageApi.getItemImageUrl(
                        itemId = item.id,
                        imageType = ImageType.BACKDROP,
                        tag = backdropTag,
                        fillWidth = BACKDROP_WIDTH,
                        fillHeight = BACKDROP_HEIGHT,
                        quality = IMAGE_QUALITY,
                    ),
                    logo = item.imageTags?.get(ImageType.LOGO)?.let { tag ->
                        apiClient.imageApi.getItemImageUrl(itemId = item.id, imageType = ImageType.LOGO, tag = tag, maxWidth = LOGO_WIDTH)
                    },
                    title = item.name.orEmpty(),
                    year = item.productionYear,
                )
            }
        }
    } catch (e: Exception) {
        Timber.w(e, "Screensaver: no backdrops, the Cosmic background alone")
        emptyList()
    }

    private suspend fun show(slides: List<Slide>) {
        var index = 0
        var shown = 0
        while (scope.isActive) {
            val slide = slides[index++ % slides.size]
            val backdrop = load(slide.backdrop)
            if (backdrop == null) {
                // A backdrop that does not load: the next one, unless none does (then the background alone)
                if (index >= slides.size && shown == 0) return
                continue
            }
            val logoImage = slide.logo?.let { load(it) }
            crossfade(backdrop)
            setInfo(slide, logoImage)
            shown++
            delay(SLIDE_MS)
        }
    }

    /** Without backdrops, for a few minutes: the clock moves each minute over the background. */
    private suspend fun drift() {
        repeat(RETRY_MINUTES) {
            nudge(clockBox)
            delay(DRIFT_MS)
        }
    }

    private suspend fun load(url: String): Drawable? = try {
        imageLoader.execute(ImageRequest.Builder(this).data(url).build()).image?.asDrawable(resources)
    } catch (e: Exception) {
        Timber.w(e, "Screensaver: an image did not load")
        null
    }

    /** The new backdrop fades in over the old one, with a slow zoom. */
    private fun crossfade(image: Drawable) {
        val incoming = back
        val outgoing = front
        incoming.setImageDrawable(image)
        incoming.alpha = 0f
        incoming.bringToFront()
        // The scrim and the text stay over the backdrops
        listOf<View>(findViewById(R.id.dream_scrim), clockBox, info, findViewById(R.id.dream_mark)).forEach { it.bringToFront() }
        incoming.animate().alpha(1f).setDuration(FADE_MS).withEndAction { outgoing.setImageDrawable(null) }
        val zoomFrom = 1f
        val zoomTo = ZOOM
        incoming.pivotX = incoming.width * Random.nextFloat()
        incoming.pivotY = incoming.height * Random.nextFloat()
        ObjectAnimator.ofFloat(incoming, View.SCALE_X, zoomFrom, zoomTo).apply {
            duration = SLIDE_MS + FADE_MS
            interpolator = LinearInterpolator()
            start()
        }
        ObjectAnimator.ofFloat(incoming, View.SCALE_Y, zoomFrom, zoomTo).apply {
            duration = SLIDE_MS + FADE_MS
            interpolator = LinearInterpolator()
            start()
        }
        front = incoming
        back = outgoing
    }

    private fun setInfo(slide: Slide, logoImage: Drawable?) {
        info.animate().alpha(0f).setDuration(INFO_FADE_MS).withEndAction {
            logo.setImageDrawable(logoImage)
            logo.isVisible = logoImage != null
            title.text = slide.title
            title.isVisible = logoImage == null
            year.text = slide.year?.toString().orEmpty()
            year.isVisible = slide.year != null
            nudge(info)
            nudge(clockBox)
            info.animate().alpha(1f).setDuration(INFO_FADE_MS)
        }
    }

    /** A few pixels somewhere else, so the text does not stay in one place. */
    private fun nudge(view: View) {
        val range = resources.displayMetrics.density * NUDGE_DP
        view.translationX = (Random.nextFloat() * 2 - 1) * range
        view.translationY = (Random.nextFloat() * 2 - 1) * range
    }

    private companion object {
        const val DATE_SKELETON = "EEEEdMMMM"
        const val SLIDE_COUNT = 40
        const val SLIDE_MS = 12_000L
        const val FADE_MS = 1_500L
        const val INFO_FADE_MS = 400L
        const val DRIFT_MS = 60_000L
        const val RETRY_MINUTES = 3
        const val ZOOM = 1.08f
        const val NUDGE_DP = 14f
        const val BACKDROP_WIDTH = 1920
        const val BACKDROP_HEIGHT = 1080
        const val LOGO_WIDTH = 720
        const val IMAGE_QUALITY = 80
    }
}
