package org.jellyfin.mobile.webapp

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader.AssetsPathHandler
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.launch
import org.jellyfin.mobile.MainViewModel
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.bridge.ExternalPlayer
import org.jellyfin.mobile.bridge.MediaSegments
import org.jellyfin.mobile.bridge.NativeInterface
import org.jellyfin.mobile.bridge.NativePlayer
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.databinding.FragmentWebviewBinding
import org.jellyfin.mobile.setup.ConnectFragment
import org.jellyfin.mobile.utils.AndroidVersion
import org.jellyfin.mobile.utils.BackPressInterceptor
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.isTelevision
import org.jellyfin.mobile.utils.Constants.FRAGMENT_WEB_VIEW_EXTRA_SERVER
import org.jellyfin.mobile.utils.applyDefault
import org.jellyfin.mobile.utils.applyWindowInsetsAsMargins
import org.jellyfin.mobile.utils.dip
import org.jellyfin.mobile.utils.extensions.getParcelableCompat
import org.jellyfin.mobile.utils.extensions.replaceFragment
import org.jellyfin.mobile.utils.fadeIn
import org.jellyfin.mobile.utils.isOutdated
import org.jellyfin.mobile.utils.requestNoBatteryOptimizations
import org.jellyfin.mobile.utils.runOnUiThread
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class WebViewFragment : Fragment(), BackPressInterceptor, JellyfinWebChromeClient.FileChooserListener {
    val appPreferences: AppPreferences by inject()
    private val mainViewModel: MainViewModel by activityViewModel()
    private val webappFunctionChannel: WebappFunctionChannel by inject()
    private lateinit var assetsPathHandler: AssetsPathHandler
    private lateinit var jellyfinWebViewClient: JellyfinWebViewClient
    private val nativePlayer: NativePlayer by inject()
    private lateinit var externalPlayer: ExternalPlayer
    private val mediaSegments: MediaSegments by inject()

    lateinit var server: ServerEntity
        private set
    private var connected = false
    private val timeoutRunnable = Runnable {
        handleError()
    }
    private val showLoadingContainerRunnable = Runnable {
        webViewBinding?.loadingContainer?.isVisible = true
    }

    /** TV: the loading screen stays until the web shows its first screen (see [onWebReady]). */
    private var tvBoot = false
    private var bootPulse: Animator? = null

    /** TV: the web never said it was ready (an older web): it is shown anyway. */
    private val revealRunnable = Runnable { onWebReady() }

    // UI
    private var webViewBinding: FragmentWebviewBinding? = null

    // External file access
    private var fileChooserActivityLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        fileChooserCallback?.onReceiveValue(FileChooserParams.parseResult(result.resultCode, result.data))
    }
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        server = requireNotNull(requireArguments().getParcelableCompat(FRAGMENT_WEB_VIEW_EXTRA_SERVER)) {
            "Server entity has not been supplied!"
        }

        assetsPathHandler = AssetsPathHandler(requireContext())
        jellyfinWebViewClient = object : JellyfinWebViewClient(
            lifecycleScope,
            server,
            assetsPathHandler,
            mainViewModel,
        ) {
            override fun onConnectedToWebapp() {
                val webViewBinding = webViewBinding ?: return
                val webView = webViewBinding.webView
                webView.removeCallbacks(timeoutRunnable)
                webView.removeCallbacks(showLoadingContainerRunnable)
                connected = true
                runOnUiThread {
                    webViewBinding.loadingContainer.isVisible = false
                    webView.fadeIn()
                    if (tvBoot) webView.postDelayed(revealRunnable, Constants.TV_WEB_READY_TIMEOUT)
                }
                requestNoBatteryOptimizations(webViewBinding.root)
            }

            override fun onErrorReceived() {
                handleError()
            }
        }
        externalPlayer = ExternalPlayer(requireContext(), this, requireActivity().activityResultRegistry)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FragmentWebviewBinding.inflate(inflater, container, false).also { binding ->
            webViewBinding = binding
        }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val webView = webViewBinding!!.webView

        // Apply window insets; the web page also shrinks above the keyboard, so the field being typed in stays in view
        webView.applyWindowInsetsAsMargins(aboveKeyboard = true)

        // Setup exclusion rects for gestures
        if (AndroidVersion.isAtLeastQ) {
            @Suppress("MagicNumber")
            webView.doOnNextLayout {
                // Maximum allowed exclusion rect height is 200dp,
                // offsetting 100dp from the center in both directions
                // uses the maximum available space
                val verticalCenter = webView.measuredHeight / 2
                val offset = webView.resources.dip(100)

                // Arbitrary, currently 2x minimum touch target size
                val exclusionWidth = webView.resources.dip(96)

                webView.systemGestureExclusionRects = listOf(
                    Rect(
                        0,
                        verticalCenter - offset,
                        exclusionWidth,
                        verticalCenter + offset,
                    ),
                )
            }
        }

        // TV: the loading screen of the app, not the one of the web
        tvBoot = requireContext().isTelevision()
        if (tvBoot) {
            webViewBinding!!.bootContainer.isVisible = true
            startBootPulse()
            webViewBinding!!.bootRetry.setOnClickListener { retryBoot() }
            webViewBinding!!.bootChangeServer.setOnClickListener {
                webView.removeCallbacks(timeoutRunnable)
                webView.stopLoading()
                onSelectServer(error = false)
            }
        }

        // Setup WebView
        webView.initialize()

        webViewBinding!!.useDifferentServerButton.setOnClickListener {
            webView.removeCallbacks(timeoutRunnable)
            webView.stopLoading()
            webViewBinding!!.loadingContainer.isVisible = false
            onSelectServer(error = false)
        }

        // Process JS functions called from other components (e.g. the PlayerActivity)
        lifecycleScope.launch {
            for (function in webappFunctionChannel) {
                webView.evaluateJavascript(function, null)
            }
        }
    }

    override fun onInterceptBackPressed(): Boolean {
        return connected && webappFunctionChannel.goBack()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        bootPulse?.cancel()
        bootPulse = null
        webViewBinding = null
    }

    /** TV: the web shows its first screen (NativeInterface.webReady): the loading screen fades out over it. */
    fun onWebReady() {
        val binding = webViewBinding ?: return
        binding.webView.removeCallbacks(revealRunnable)
        if (!tvBoot || !binding.bootContainer.isVisible || binding.bootActions.isVisible) return
        bootPulse?.cancel()
        binding.bootContainer.animate()
            .alpha(0f)
            .setDuration(BOOT_FADE_MS)
            .withEndAction {
                binding.bootContainer.isVisible = false
                binding.bootContainer.alpha = 1f
            }
        binding.webView.requestFocus()
    }

    /** The mark breathing while the server answers. */
    private fun startBootPulse() {
        val logo = webViewBinding?.bootLogo ?: return
        bootPulse?.cancel()
        bootPulse = AnimatorSet().apply {
            val scale = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, BOOT_PULSE_SCALE)
            val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, BOOT_PULSE_SCALE)
            val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 1f, BOOT_PULSE_ALPHA)
            play(
                ObjectAnimator.ofPropertyValuesHolder(logo, scale, scaleY, alpha).apply {
                    duration = BOOT_PULSE_MS
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    interpolator = AccelerateDecelerateInterpolator()
                },
            )
            start()
        }
    }

    /** TV: the server did not answer: its name, Retry and Change server, the focus on Retry. */
    private fun showBootError() {
        val binding = webViewBinding ?: return
        bootPulse?.cancel()
        binding.bootLogo.apply {
            scaleX = 1f
            scaleY = 1f
            alpha = 1f
            // Smaller, to leave the message and the buttons the middle of the screen
            layoutParams = layoutParams.apply {
                width = resources.getDimensionPixelSize(R.dimen.tv_boot_logo_small)
                height = width
            }
        }
        val host = Uri.parse(server.hostname).host ?: server.hostname
        binding.bootTitle.text = getString(R.string.tv_boot_not_found, host)
        binding.bootTitle.isVisible = true
        binding.bootMessage.isVisible = true
        binding.bootActions.isVisible = true
        binding.bootContainer.isVisible = true
        binding.bootRetry.requestFocus()
    }

    private fun retryBoot() {
        val binding = webViewBinding ?: return
        binding.bootTitle.isVisible = false
        binding.bootMessage.isVisible = false
        binding.bootActions.isVisible = false
        binding.bootLogo.layoutParams = binding.bootLogo.layoutParams.apply {
            width = resources.getDimensionPixelSize(R.dimen.tv_boot_logo)
            height = width
        }
        startBootPulse()
        connected = false
        binding.webView.apply {
            stopLoading()
            removeCallbacks(timeoutRunnable)
            loadUrl("${server.hostname.trimEnd('/')}/")
            postDelayed(timeoutRunnable, Constants.TV_CONNECTION_TIMEOUT)
        }
    }

    private fun WebView.initialize() {
        if (!appPreferences.ignoreWebViewChecks && isOutdated()) { // Check WebView version
            showOutdatedWebViewDialog(this)
            return
        }
        webViewClient = jellyfinWebViewClient
        webChromeClient = JellyfinWebChromeClient(this@WebViewFragment)
        settings.applyDefault()
        if (requireContext().isTelevision()) {
            // Televisions report 2x density: without this the page is laid out for a 960 px wide screen and looks
            // twice its size. The page asks for 1920 px instead (injectionScript.js) and is fitted to the screen.
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
        }
        addJavascriptInterface(NativeInterface(requireContext()), "NativeInterface")
        addJavascriptInterface(nativePlayer, "NativePlayer")
        addJavascriptInterface(externalPlayer, "ExternalPlayer")
        addJavascriptInterface(mediaSegments, "MediaSegments")

        loadUrl("${server.hostname.trimEnd('/')}/")
        if (tvBoot) {
            postDelayed(timeoutRunnable, Constants.TV_CONNECTION_TIMEOUT)
        } else {
            postDelayed(timeoutRunnable, Constants.INITIAL_CONNECTION_TIMEOUT)
            postDelayed(showLoadingContainerRunnable, Constants.SHOW_PROGRESS_BAR_DELAY)
        }
    }

    private fun showOutdatedWebViewDialog(webView: WebView) {
        AlertDialog.Builder(requireContext()).apply {
            setTitle(R.string.dialog_web_view_outdated)
            setMessage(R.string.dialog_web_view_outdated_message)
            setCancelable(false)

            val webViewPackage = WebViewCompat.getCurrentWebViewPackage(context)
            if (webViewPackage != null) {
                val marketUri = Uri.Builder().apply {
                    scheme("market")
                    authority("details")
                    appendQueryParameter("id", webViewPackage.packageName)
                }.build()
                val referrerUri = Uri.Builder().apply {
                    scheme("android-app")
                    authority(context.packageName)
                }.build()

                val marketIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = marketUri
                    putExtra(Intent.EXTRA_REFERRER, referrerUri)
                }

                // Only show button if the intent can be resolved
                if (marketIntent.resolveActivity(context.packageManager) != null) {
                    setNegativeButton(R.string.dialog_button_check_for_updates) { _, _ ->
                        startActivity(marketIntent)
                        requireActivity().finishAfterTransition()
                    }
                }
            }
            if (AndroidVersion.isAtLeastN) {
                setPositiveButton(R.string.dialog_button_open_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_WEBVIEW_SETTINGS))
                    Toast.makeText(context, R.string.toast_reopen_after_change, Toast.LENGTH_LONG).show()
                    requireActivity().finishAfterTransition()
                }
            }
            setNeutralButton(R.string.dialog_button_ignore) { _, _ ->
                appPreferences.ignoreWebViewChecks = true
                // Re-initialize
                webView.initialize()
            }
        }.show()
    }

    private fun onSelectServer(error: Boolean = false) = runOnUiThread {
        val activity = activity
        if (activity != null && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            val extras = when {
                error -> Bundle().apply {
                    putBoolean(Constants.FRAGMENT_CONNECT_EXTRA_ERROR, true)
                }
                else -> null
            }
            parentFragmentManager.replaceFragment<ConnectFragment>(extras)
        }
    }

    private fun handleError() {
        runOnUiThread { showErrorOnUiThread() }
    }

    private fun showErrorOnUiThread() {
        // TV, still loading: the server is not found, said on the loading screen
        if (tvBoot && !connected && webViewBinding != null) {
            webViewBinding?.webView?.removeCallbacks(timeoutRunnable)
            showBootError()
            return
        }
        connected = false
        onSelectServer(error = true)
    }

    private companion object {
        const val BOOT_FADE_MS = 300L
        const val BOOT_PULSE_MS = 1200L
        const val BOOT_PULSE_SCALE = 1.04f
        const val BOOT_PULSE_ALPHA = 0.85f
    }

    override fun onShowFileChooser(intent: Intent, filePathCallback: ValueCallback<Array<Uri>>) {
        fileChooserCallback = filePathCallback
        fileChooserActivityLauncher.launch(intent)
    }
}
