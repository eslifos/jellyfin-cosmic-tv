package org.jellyfin.mobile.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import de.Maxr1998.modernpreferences.Preference
import de.Maxr1998.modernpreferences.PreferenceScreen
import de.Maxr1998.modernpreferences.PreferencesAdapter
import de.Maxr1998.modernpreferences.helpers.categoryHeader
import de.Maxr1998.modernpreferences.helpers.checkBox
import de.Maxr1998.modernpreferences.helpers.defaultOnCheckedChange
import de.Maxr1998.modernpreferences.helpers.defaultOnClick
import de.Maxr1998.modernpreferences.helpers.defaultOnSelectionChange
import de.Maxr1998.modernpreferences.helpers.onClick
import de.Maxr1998.modernpreferences.helpers.pref
import de.Maxr1998.modernpreferences.helpers.screen
import de.Maxr1998.modernpreferences.helpers.singleChoice
import de.Maxr1998.modernpreferences.preferences.CheckBoxPreference
import de.Maxr1998.modernpreferences.preferences.choice.SelectionItem
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.app.StorageManager
import org.jellyfin.mobile.bridge.TvSettings
import org.jellyfin.mobile.databinding.FragmentSettingsBinding
import org.jellyfin.mobile.downloads.DownloadMethod
import org.jellyfin.mobile.player.tv.SubtitleSize
import org.jellyfin.mobile.utils.BackPressInterceptor
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.applyWindowInsetsAsMargins
import org.jellyfin.mobile.utils.extensions.requireMainActivity
import org.jellyfin.mobile.utils.isPackageInstalled
import org.jellyfin.mobile.utils.isTelevision
import org.jellyfin.mobile.webapp.WebappFunctionChannel
import org.jellyfin.mobile.utils.withThemedContext
import org.koin.android.ext.android.inject

class SettingsFragment : Fragment(), BackPressInterceptor {

    private val appPreferences: AppPreferences by inject()
    private val storageManager: StorageManager by inject()

    private val storageLocationPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val changed = storageManager.changeStorageLocation(uri)

            // Update preference
            if (changed && ::downloadLocationPreference.isInitialized) {
                downloadLocationPreference.summary = storageManager.getStorageLocation()?.name
                downloadLocationPreference.requestRebindAndHighlight()
            }
        }
    }

    private val settingsAdapter: PreferencesAdapter by lazy { PreferencesAdapter(buildSettingsScreen()) }
    private val webappFunctionChannel: WebappFunctionChannel by inject()

    /** A TV: the settings of a touch screen (gestures, brightness, orientation) and downloads are left out. */
    private val isTv by lazy { requireContext().isTelevision() }

    // Touch screen only (null on a TV)
    private var startLandscapeVideoInLandscapePreference: CheckBoxPreference? = null
    private var swipeGesturesPreference: CheckBoxPreference? = null
    private var pressSpeedUpPreference: CheckBoxPreference? = null
    private var rememberBrightnessPreference: Preference? = null
    private var horizontalGesturePreference: Preference? = null

    // TV only (null elsewhere)
    private var audioOutputPreference: Preference? = null

    private lateinit var backgroundAudioPreference: Preference
    private lateinit var directPlayAssPreference: Preference
    private lateinit var networkBufferPreference: Preference
    private lateinit var externalPlayerChoicePreference: Preference
    private lateinit var downloadLocationPreference: Preference

    /** TV: the views behind (the web) and how they took the focus, given back when the settings close. */
    private val blockedViews = mutableListOf<Pair<ViewGroup, Int>>()

    init {
        Preference.Config.titleMaxLines = 2
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val localInflater = inflater.withThemedContext(requireContext(), R.style.AppTheme_Settings)
        val binding = FragmentSettingsBinding.inflate(localInflater, container, false)
        binding.root.applyWindowInsetsAsMargins()
        binding.toolbar.setTitle(R.string.activity_name_settings)
        requireMainActivity().apply {
            setSupportActionBar(binding.toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
        }
        binding.recyclerView.adapter = settingsAdapter
        if (isTv) setUpForRemote(binding)
        return binding.root
    }

    /**
     * TV: the remote moves in the settings, not in the web left behind them (it kept the focus), with the row in
     * focus framed, and the list in the middle of the screen to be read from the couch.
     */
    private fun setUpForRemote(binding: FragmentSettingsBinding) {
        parentFragmentManager.fragments.filter { it !== this }.mapNotNull { it.view as? ViewGroup }.forEach { view ->
            blockedViews.add(view to view.descendantFocusability)
            view.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        binding.recyclerView.apply {
            val side = resources.displayMetrics.widthPixels / TV_SIDE_FRACTION
            setPadding(side, paddingTop, side, paddingBottom)
            clipToPadding = false
            addOnChildAttachStateChangeListener(
                object : RecyclerView.OnChildAttachStateChangeListener {
                    override fun onChildViewAttachedToWindow(view: View) {
                        view.foreground = ContextCompat.getDrawable(view.context, R.drawable.fg_tv_focus)
                    }

                    override fun onChildViewDetachedFromWindow(view: View) = Unit
                },
            )
            post { requestFocus() }
        }
    }

    override fun onInterceptBackPressed(): Boolean {
        return settingsAdapter.goBack()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        blockedViews.forEach { (view, focusability) -> view.descendantFocusability = focusability }
        blockedViews.clear()
        requireMainActivity().setSupportActionBar(null)
    }

    /** A TV setting that lives in the web: kept here and handed to the web (TvSettings). */
    private fun sendToWeb(name: String, value: Any) {
        webappFunctionChannel.call(TvSettings.changeScript(name, value))
    }

    @Suppress("LongMethod")
    private fun buildSettingsScreen() = screen(requireContext()) {
        collapseIcon = true
        // The notification of the music player: a TV has no notification shade
        if (!isTv) {
            categoryHeader(PREF_CATEGORY_MUSIC_PLAYER) {
                titleRes = R.string.pref_category_music_player
            }
            checkBox(Constants.PREF_MUSIC_NOTIFICATION_ALWAYS_DISMISSIBLE) {
                titleRes = R.string.pref_music_notification_always_dismissible_title
                summaryRes = R.string.pref_music_notification_always_dismissible_summary_off
                summaryOnRes = R.string.pref_music_notification_always_dismissible_summary_on
            }
        }
        categoryHeader(PREF_CATEGORY_VIDEO_PLAYER) {
            titleRes = R.string.pref_category_video_player
        }
        val videoPlayerOptions = listOf(
            SelectionItem(VideoPlayerType.WEB_PLAYER, R.string.video_player_web, R.string.video_player_web_description),
            SelectionItem(
                VideoPlayerType.WEB_PLAYER_NATIVE_FALLBACK,
                R.string.video_player_web_native_fallback,
                R.string.video_player_web_native_fallback_description,
            ),
            SelectionItem(
                VideoPlayerType.EXO_PLAYER,
                R.string.video_player_integrated,
                R.string.video_player_native_description,
            ),
            SelectionItem(
                VideoPlayerType.EXTERNAL_PLAYER,
                R.string.video_player_external,
                R.string.video_player_external_description,
            ),
        )
        singleChoice(Constants.PREF_VIDEO_PLAYER_TYPE, videoPlayerOptions) {
            titleRes = R.string.pref_video_player_type_title
            initialSelection = appPreferences.defaultVideoPlayerType
            defaultOnSelectionChange { selection ->
                // The settings of the integrated player count also when it is the fallback of the web one
                val integrated = selection == VideoPlayerType.EXO_PLAYER || selection == VideoPlayerType.WEB_PLAYER_NATIVE_FALLBACK
                startLandscapeVideoInLandscapePreference?.enabled = integrated
                swipeGesturesPreference?.enabled = integrated
                rememberBrightnessPreference?.enabled = integrated && swipeGesturesPreference?.checked == true
                pressSpeedUpPreference?.enabled = integrated
                backgroundAudioPreference.enabled = integrated
                horizontalGesturePreference?.enabled = integrated
                directPlayAssPreference.enabled = integrated
                networkBufferPreference.enabled = integrated
                audioOutputPreference?.enabled = integrated
                externalPlayerChoicePreference.enabled = selection == VideoPlayerType.EXTERNAL_PLAYER
            }
        }
        if (!isTv) addTouchScreenSettings()
        backgroundAudioPreference = checkBox(Constants.PREF_EXOPLAYER_ALLOW_BACKGROUND_AUDIO) {
            titleRes = R.string.pref_exoplayer_allow_background_audio
            summaryRes = R.string.pref_exoplayer_allow_background_audio_summary
            enabled = appPreferences.usesIntegratedPlayer
        }
        directPlayAssPreference = checkBox(Constants.PREF_EXOPLAYER_DIRECT_PLAY_ASS) {
            titleRes = R.string.pref_exoplayer_direct_play_ass
            summaryRes = R.string.pref_exoplayer_direct_play_ass_description
            enabled = appPreferences.usesIntegratedPlayer
        }
        val networkBufferOptions = listOf(
            SelectionItem(
                Constants.NETWORK_BUFFER_AUTO,
                R.string.network_buffer_auto,
                R.string.network_buffer_auto_description,
            ),
            SelectionItem(
                Constants.NETWORK_BUFFER_LARGE,
                R.string.network_buffer_large,
                R.string.network_buffer_large_description,
            ),
            SelectionItem(
                Constants.NETWORK_BUFFER_EXTRA_LARGE,
                R.string.network_buffer_extra_large,
                R.string.network_buffer_extra_large_description,
            ),
        )
        networkBufferPreference = singleChoice(Constants.PREF_EXOPLAYER_NETWORK_BUFFER, networkBufferOptions) {
            titleRes = R.string.pref_exoplayer_network_buffer
            initialSelection = Constants.NETWORK_BUFFER_AUTO
            enabled = appPreferences.usesIntegratedPlayer
        }

        if (isTv) addTvSettings()

        // Generate available external player options
        val packageManager = requireContext().packageManager
        val externalPlayerOptions = listOf(
            SelectionItem(
                ExternalPlayerPackage.SYSTEM_DEFAULT,
                R.string.external_player_system_default,
                R.string.external_player_system_default_description,
            ),
            SelectionItem(
                ExternalPlayerPackage.MPV_PLAYER,
                R.string.external_player_mpv,
                R.string.external_player_mpv_description,
            ),
            SelectionItem(
                ExternalPlayerPackage.MX_PLAYER_FREE,
                R.string.external_player_mx_player_free,
                R.string.external_player_mx_player_free_description,
            ),
            SelectionItem(
                ExternalPlayerPackage.MX_PLAYER_PRO,
                R.string.external_player_mx_player_pro,
                R.string.external_player_mx_player_pro_description,
            ),
            SelectionItem(
                ExternalPlayerPackage.VLC_PLAYER,
                R.string.external_player_vlc_player,
                R.string.external_player_vlc_player_description,
            ),
            SelectionItem(
                ExternalPlayerPackage.MPVKT_PLAYER,
                R.string.external_player_mpvkt,
                R.string.external_player_mpvkt_description,
            ),
        ).filter { item ->
            item.key == ExternalPlayerPackage.SYSTEM_DEFAULT || packageManager.isPackageInstalled(item.key)
        }

        // Revert if current selection isn't available
        if (!packageManager.isPackageInstalled(appPreferences.externalPlayerApp)) {
            appPreferences.externalPlayerApp = ExternalPlayerPackage.SYSTEM_DEFAULT
        }

        externalPlayerChoicePreference = singleChoice(Constants.PREF_EXTERNAL_PLAYER_APP, externalPlayerOptions) {
            titleRes = R.string.external_player_app
            enabled = appPreferences.videoPlayerType == VideoPlayerType.EXTERNAL_PLAYER
        }
        val subtitleSettingsIntent = Intent(Settings.ACTION_CAPTIONING_SETTINGS)
        if (subtitleSettingsIntent.resolveActivity(requireContext().packageManager) != null) {
            pref(Constants.PREF_SUBTITLE_STYLE) {
                titleRes = R.string.pref_subtitle_style
                summaryRes = R.string.pref_subtitle_style_summary
                defaultOnClick {
                    startActivity(subtitleSettingsIntent)
                }
            }
        }
        // Downloads: not on a TV
        if (!isTv) addDownloadSettings()
    }

    private fun PreferenceScreen.Builder.addDownloadSettings() {
        categoryHeader(PREF_CATEGORY_DOWNLOADS) {
            titleRes = R.string.pref_category_downloads
        }

        val downloadMethods = listOf(
            SelectionItem(
                DownloadMethod.WIFI_ONLY.intValue,
                R.string.wifi_only,
                R.string.wifi_only_summary,
            ),
            SelectionItem(
                DownloadMethod.MOBILE_DATA.intValue,
                R.string.mobile_data,
                R.string.mobile_data_summary,
            ),
            SelectionItem(
                DownloadMethod.MOBILE_AND_ROAMING.intValue,
                R.string.mobile_data_and_roaming,
                R.string.mobile_data_and_roaming_summary,
            ),
        )
        singleChoice(Constants.PREF_DOWNLOAD_METHOD, downloadMethods) {
            titleRes = R.string.network_title
            initialSelection = DownloadMethod.DEFAULT.intValue
        }

        downloadLocationPreference = pref(Constants.PREF_STORAGE_LOCATION) {
            val location = storageManager.getStorageLocation()

            titleRes = R.string.pref_download_location
            summary = location?.name ?: getString(R.string.menu_item_none)

            onClick {
                storageLocationPicker.launch(location?.uri ?: storageManager.defaultStorageLocation)
                false
            }
        }
    }

    /** The settings of the integrated player for a touch screen. */
    private fun PreferenceScreen.Builder.addTouchScreenSettings() {
        startLandscapeVideoInLandscapePreference = checkBox(Constants.PREF_EXOPLAYER_START_LANDSCAPE_VIDEO_IN_LANDSCAPE) {
            titleRes = R.string.pref_exoplayer_start_landscape_video_in_landscape
            enabled = appPreferences.usesIntegratedPlayer
        }
        swipeGesturesPreference = checkBox(Constants.PREF_EXOPLAYER_ALLOW_SWIPE_GESTURES) {
            titleRes = R.string.pref_exoplayer_allow_brightness_volume_gesture
            enabled = appPreferences.usesIntegratedPlayer
            defaultValue = true
            defaultOnCheckedChange { checked ->
                rememberBrightnessPreference?.enabled = checked
            }
        }
        rememberBrightnessPreference = checkBox(Constants.PREF_EXOPLAYER_REMEMBER_BRIGHTNESS) {
            titleRes = R.string.pref_exoplayer_remember_brightness
            enabled = appPreferences.usesIntegratedPlayer && appPreferences.exoPlayerAllowSwipeGestures
            defaultOnCheckedChange { checked ->
                if (!checked) appPreferences.exoPlayerBrightness = BRIGHTNESS_OVERRIDE_NONE
            }
        }
        pressSpeedUpPreference = checkBox(Constants.PREF_EXOPLAYER_ALLOW_PRESS_SPEED_UP) {
            titleRes = R.string.pref_exoplayer_allow_press_speed_up
            summaryRes = R.string.pref_exoplayer_allow_press_speed_up_summary
            enabled = appPreferences.usesIntegratedPlayer
            defaultValue = true
        }
        horizontalGesturePreference = checkBox(Constants.PREF_EXOPLAYER_ALLOW_HORIZONTAL_GESTURE) {
            titleRes = R.string.pref_exoplayer_allow_horizontal_gesture
            summaryRes = R.string.pref_exoplayer_allow_horizontal_gesture_summary
            enabled = appPreferences.usesIntegratedPlayer
            defaultValue = true
        }
    }

    /** TV: the screen and the sound of the TV or the receiver, and the subtitles and trailers of both players. */
    private fun PreferenceScreen.Builder.addTvSettings() {
        categoryHeader(PREF_CATEGORY_TV) {
            titleRes = R.string.pref_category_tv
        }
        checkBox(Constants.PREF_MATCH_FRAME_RATE) {
            titleRes = R.string.pref_match_frame_rate
            summaryRes = R.string.pref_match_frame_rate_description
            defaultValue = false
        }
        val audioOutputOptions = listOf(
            SelectionItem(Constants.AUDIO_OUTPUT_AUTO, R.string.audio_output_auto, R.string.audio_output_auto_description),
            SelectionItem(Constants.AUDIO_OUTPUT_STEREO, R.string.audio_output_stereo, R.string.audio_output_stereo_description),
            SelectionItem(Constants.AUDIO_OUTPUT_PASSTHROUGH, R.string.audio_output_passthrough, R.string.audio_output_passthrough_description),
        )
        audioOutputPreference = singleChoice(Constants.PREF_AUDIO_OUTPUT, audioOutputOptions) {
            titleRes = R.string.pref_audio_output
            initialSelection = Constants.AUDIO_OUTPUT_AUTO
            enabled = appPreferences.usesIntegratedPlayer
        }
        val subtitleSizes = listOf(
            SelectionItem(SubtitleSize.SMALLER, R.string.subtitle_size_smaller),
            SelectionItem(SubtitleSize.SMALL, R.string.subtitle_size_small),
            SelectionItem(SubtitleSize.MEDIUM, R.string.subtitle_size_medium),
            SelectionItem(SubtitleSize.LARGE, R.string.subtitle_size_large),
            SelectionItem(SubtitleSize.LARGER, R.string.subtitle_size_larger),
            SelectionItem(SubtitleSize.EXTRA_LARGE, R.string.subtitle_size_extra_large),
        )
        singleChoice(Constants.PREF_SUBTITLE_SIZE, subtitleSizes) {
            titleRes = R.string.pref_subtitle_size
            initialSelection = SubtitleSize.MEDIUM
            defaultOnSelectionChange { selection -> sendToWeb(TvSettings.SUBTITLE_SIZE, selection) }
        }
        checkBox(Constants.PREF_TV_TRAILERS) {
            titleRes = R.string.pref_tv_trailers
            summaryRes = R.string.pref_tv_trailers_summary
            defaultValue = true
            defaultOnCheckedChange { checked -> sendToWeb(TvSettings.TRAILERS, checked) }
        }
    }

    companion object {
        const val PREF_CATEGORY_TV = "pref_category_tv"

        /** TV: each side of the list leaves this fraction of the screen. */
        private const val TV_SIDE_FRACTION = 7
        const val PREF_CATEGORY_MUSIC_PLAYER = "pref_category_music"
        const val PREF_CATEGORY_VIDEO_PLAYER = "pref_category_video"
        const val PREF_CATEGORY_DOWNLOADS = "pref_category_downloads"
    }
}
