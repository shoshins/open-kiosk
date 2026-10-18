package org.openkiosk

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.window.OnBackInvokedDispatcher
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Shows the configured dashboard full screen and keeps it alive: it reloads
 * when the network returns, retries failed loads with backoff, and rebuilds
 * the WebView if its renderer process dies.
 */
class KioskActivity : Activity() {

    private lateinit var settings: Settings
    private var webView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var retryDelayMs = INITIAL_RETRY_MS
    private var loadFailed = false

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
        createWebView()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBackInPage() }
        }
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (webView != null) {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
            destroyWebView()
        }
        super.onDestroy()
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

    @SuppressLint("SetJavaScriptEnabled")
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
        view.webViewClient = KioskClient(startUrl)
        setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        webView = view
        view.loadUrl(startUrl)
    }

    private fun destroyWebView() {
        webView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
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
        if (view.url.isNullOrEmpty() || !UrlPolicy.isAllowed(startUrl, view.url!!)) view.loadUrl(startUrl) else view.reload()
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

        override fun onPageFinished(view: WebView, url: String) {
            if (!loadFailed) retryDelayMs = INITIAL_RETRY_MS
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) scheduleRetry()
        }

        // The renderer died (crash or low-memory kill). Rebuild instead of letting the app crash.
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            destroyWebView()
            handler.postDelayed({ createWebView() }, INITIAL_RETRY_MS)
            return true
        }
    }

    private companion object {
        const val INITIAL_RETRY_MS = 2_000L
        const val MAX_RETRY_MS = 60_000L
    }
}
