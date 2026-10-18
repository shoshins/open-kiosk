package org.openkiosk

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextClock
import android.window.OnBackInvokedDispatcher
import org.openkiosk.bridge.JsBridge
import org.openkiosk.command.Command
import org.openkiosk.command.CommandBus
import org.openkiosk.command.CommandExecutor
import org.openkiosk.command.ErrorCode
import org.openkiosk.command.Outcome
import java.util.Locale

/**
 * Shows the configured dashboard full screen and keeps it alive: it reloads
 * when the network returns, retries failed loads with backoff, and rebuilds
 * the WebView if its renderer process dies. It is also the executor for
 * every remote command.
 */
class KioskActivity : Activity(), CommandExecutor {

    private lateinit var settings: Settings
    private lateinit var root: FrameLayout
    private var webView: WebView? = null
    private var screensaver: View? = null
    private var screensaverWebView: WebView? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val handler = Handler(Looper.getMainLooper())
    private var retryDelayMs = INITIAL_RETRY_MS
    private var loadFailed = false

    // Read from REST and MQTT threads, so cached here rather than read from views.
    @Volatile private var currentUrl: String? = null
    @Volatile private var lastError: String? = null
    @Volatile private var brightness: Int = -1
    @Volatile private var screensaverActive = false

    private val settingsTaps = ArrayDeque<Long>()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post { if (loadFailed) reload() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        if (settings.startUrl.isNullOrEmpty()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        applyBrightness(settings.brightness)
        createWebView()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBackInPage() }
        }
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        CommandBus.attach(this)
        KioskRuntime.start(this) { KioskState(currentUrl, screensaverActive, brightness, lastError) }
        resetIdleTimer()
    }

    override fun onResume() {
        super.onResume()
        // Settings may have changed while the settings screen was in front.
        if (::settings.isInitialized && settingsChanged) {
            settingsChanged = false
            recreate()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::root.isInitialized) {
            CommandBus.detach(this)
            KioskRuntime.stop()
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
            hideScreensaver()
            destroyWebView()
            tts?.shutdown()
        }
        super.onDestroy()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            resetIdleTimer()
            if (screensaver != null) {
                hideScreensaver()
                return true // the waking touch does not reach the dashboard
            }
            if (isSettingsGesture(event)) {
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
        }
        return super.dispatchTouchEvent(event)
    }

    // A kiosk has nowhere to go back to: back only steps back within the page.
    // Android 13+ routes back through the callback registered in onCreate;
    // this override covers Android 8.1-12.
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        goBackInPage()
    }

    private fun goBackInPage() {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack()
    }

    // Commands

    override fun execute(command: Command): Outcome {
        val start = settings.startUrl ?: return Outcome.error(ErrorCode.UNAVAILABLE, "No start URL")
        val outcome = when (command) {
            is Command.Navigate ->
                if (!UrlPolicy.isAllowed(start, command.url)) Outcome.error(ErrorCode.NOT_ALLOWED, "The URL is outside the dashboard's origin")
                else { hideScreensaver(); webView?.loadUrl(command.url); Outcome.OK }
            Command.Reload -> { reload(); Outcome.OK }
            is Command.SetBrightness -> { settings.brightness = command.percent; applyBrightness(command.percent); Outcome.OK }
            Command.Wake -> { hideScreensaver(); resetIdleTimer(); Outcome.OK }
            Command.Sleep -> { showScreensaver(ScreensaverMode.BLACK, minimumBrightness = true); Outcome.OK }
            Command.ScreensaverOn -> { showScreensaver(effectiveScreensaverMode()); Outcome.OK }
            Command.ScreensaverOff -> { hideScreensaver(); resetIdleTimer(); Outcome.OK }
            is Command.Speak -> speak(command)
            Command.StopSpeaking -> { tts?.stop(); Outcome.OK }
            is Command.LaunchApp -> launch(command.packageName)
        }
        KioskRuntime.stateChanged()
        return outcome
    }

    private fun speak(command: Command.Speak): Outcome {
        val engine = tts ?: TextToSpeech(applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) pendingSpeech.forEach { it() }
            pendingSpeech.clear()
        }.also { tts = it }
        val utter = {
            command.language?.let { engine.setLanguage(Locale.forLanguageTag(it)) }
            engine.speak(command.text, TextToSpeech.QUEUE_ADD, null, "ok-${System.nanoTime()}")
            Unit
        }
        if (ttsReady) utter() else pendingSpeech += utter
        return Outcome.OK
    }

    private val pendingSpeech = mutableListOf<() -> Unit>()

    private fun launch(packageName: String): Outcome {
        if (packageName !in settings.launchAllowlist) {
            return Outcome.error(ErrorCode.NOT_ALLOWED, "The package is not on the launch allowlist")
        }
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return Outcome.error(ErrorCode.NOT_FOUND, "The package is not installed or has no launcher activity")
        return try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Outcome.OK
        } catch (_: ActivityNotFoundException) {
            Outcome.error(ErrorCode.NOT_FOUND, "The app could not be started")
        }
    }

    // Brightness

    private fun applyBrightness(percent: Int) {
        brightness = percent
        window.attributes = window.attributes.apply {
            screenBrightness = if (percent < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            else (percent.coerceIn(1, 100) / 100f)
        }
    }

    // Screensaver

    private val idleTimeout = Runnable {
        val mode = settings.screensaverMode
        if (mode != ScreensaverMode.OFF) showScreensaver(mode)
    }

    private fun resetIdleTimer() {
        handler.removeCallbacks(idleTimeout)
        val minutes = settings.screensaverTimeoutMinutes
        if (settings.screensaverMode != ScreensaverMode.OFF && minutes > 0) {
            handler.postDelayed(idleTimeout, minutes * 60_000L)
        }
    }

    private fun effectiveScreensaverMode() =
        settings.screensaverMode.takeIf { it != ScreensaverMode.OFF } ?: ScreensaverMode.BLACK

    @SuppressLint("SetJavaScriptEnabled")
    private fun showScreensaver(mode: ScreensaverMode, minimumBrightness: Boolean = false) {
        hideScreensaver()
        val overlay: View = when (mode) {
            ScreensaverMode.CLOCK -> FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(TextClock(this@KioskActivity).apply {
                    format24Hour = "HH:mm"
                    format12Hour = "h:mm"
                    textSize = 96f
                    setTextColor(Color.rgb(225, 225, 225))
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            }
            ScreensaverMode.URL -> {
                val url = settings.screensaverUrl
                val start = settings.startUrl
                if (url == null || start == null || !UrlPolicy.isAllowed(start, url)) {
                    View(this).apply { setBackgroundColor(Color.BLACK) }
                } else {
                    WebView(this).apply {
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                                !UrlPolicy.isAllowed(start, request.url.toString())
                        }
                        loadUrl(url)
                    }.also { screensaverWebView = it }
                }
            }
            ScreensaverMode.DIM -> View(this).apply { setBackgroundColor(Color.argb(200, 0, 0, 0)) }
            else -> View(this).apply { setBackgroundColor(Color.BLACK) }
        }
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        screensaver = overlay
        screensaverActive = true
        when {
            minimumBrightness -> applyWindowBrightnessOnly(0)
            mode == ScreensaverMode.DIM -> applyWindowBrightnessOnly(settings.screensaverDimBrightness)
        }
        KioskRuntime.stateChanged()
    }

    private fun hideScreensaver() {
        val overlay = screensaver ?: return
        root.removeView(overlay)
        screensaverWebView?.destroy()
        screensaverWebView = null
        screensaver = null
        screensaverActive = false
        applyBrightness(settings.brightness)
        KioskRuntime.stateChanged()
    }

    /** Changes the window only, keeping the configured brightness to restore. */
    private fun applyWindowBrightnessOnly(percent: Int) {
        window.attributes = window.attributes.apply { screenBrightness = (percent.coerceIn(0, 100) / 100f).coerceAtLeast(0.01f) }
    }

    // Settings entry: five taps in the top-left corner within three seconds.

    private fun isSettingsGesture(event: MotionEvent): Boolean {
        val corner = CORNER_DP * resources.displayMetrics.density
        if (event.x > corner || event.y > corner) {
            settingsTaps.clear()
            return false
        }
        val now = event.eventTime
        settingsTaps.addLast(now)
        while (settingsTaps.isNotEmpty() && now - settingsTaps.first() > GESTURE_WINDOW_MS) settingsTaps.removeFirst()
        if (settingsTaps.size >= GESTURE_TAPS) {
            settingsTaps.clear()
            return true
        }
        return false
    }

    // WebView

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createWebView() {
        val startUrl = settings.startUrl ?: return
        val view = WebView(this)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // No file or content access: a dashboard is a web page, not a file browser.
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        if (settings.bridgeEnabled) {
            view.addJavascriptInterface(JsBridge(applicationContext, settings) { currentUrl }, JsBridge.NATIVE_NAME)
        }
        view.webViewClient = KioskClient(startUrl)
        root.addView(view, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        webView = view
        view.loadUrl(startUrl)
    }

    private fun destroyWebView() {
        webView?.let {
            root.removeView(it)
            it.destroy()
        }
        webView = null
    }

    private fun reload() {
        handler.removeCallbacks(retry)
        loadFailed = false
        val view = webView
        val startUrl = settings.startUrl
        if (view == null || startUrl == null) return
        // After a failure the WebView shows its own error page; go back to the dashboard.
        val url = view.url
        if (url.isNullOrEmpty() || !UrlPolicy.isAllowed(startUrl, url)) view.loadUrl(startUrl) else view.reload()
    }

    private val retry = Runnable { reload() }

    private fun scheduleRetry() {
        loadFailed = true
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
    }

    @Suppress("DEPRECATION")
    private fun enterImmersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    private inner class KioskClient(private val startUrl: String) : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            !UrlPolicy.isAllowed(startUrl, request.url.toString())

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            currentUrl = url
        }

        override fun onPageFinished(view: WebView, url: String) {
            currentUrl = url
            if (!loadFailed) {
                retryDelayMs = INITIAL_RETRY_MS
                lastError = null
            }
            if (settings.bridgeEnabled && UrlPolicy.isAllowed(startUrl, url)) view.evaluateJavascript(JsBridge.SHIM, null)
            KioskRuntime.stateChanged()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                lastError = error.description?.toString()
                scheduleRetry()
            }
        }

        // The renderer died (crash or low-memory kill). Rebuild instead of letting the app crash.
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (view === screensaverWebView) {
                hideScreensaver()
                return true
            }
            lastError = if (detail.didCrash()) "renderer crashed" else "renderer killed for memory"
            destroyWebView()
            handler.postDelayed({ createWebView() }, INITIAL_RETRY_MS)
            return true
        }
    }

    companion object {
        /** Set by the settings screen so the kiosk restarts with the new configuration. */
        @Volatile var settingsChanged = false

        private const val INITIAL_RETRY_MS = 2_000L
        private const val MAX_RETRY_MS = 60_000L
        private const val CORNER_DP = 80
        private const val GESTURE_TAPS = 5
        private const val GESTURE_WINDOW_MS = 3_000L
    }
}
