package com.puja.user

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JsResult
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.puja.user.download.DownloadService
import com.puja.user.lock.AppLockManager
import com.puja.user.lock.BiometricLockActivity
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var fullscreenContainer: FrameLayout

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private var pendingDownloadUrl: String? = null
    private var pendingDownloadTitle: String? = null
    private var pendingDownloadId: String? = null

    // ── App Update / APK Install ────────────────────────────────────────────
    private var pendingInstallPath: String? = null

    // ── Exit Dialog ──────────────────────────────────────────────────────────
    private var exitDialog: Dialog? = null

    companion object {
        private const val FILE_CHOOSER_REQUEST_CODE = 51426
        private const val PERM_STORAGE_REQUEST_CODE = 2001
        private const val PERM_NOTIF_REQUEST_CODE = 2002
        private const val INSTALL_PERMISSION_REQUEST_CODE = 2003
        private const val UPDATE_ID_PREFIX = "app_update_"
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getStringExtra(DownloadService.KEY_ID) ?: return
            val isUpdate = id.startsWith(UPDATE_ID_PREFIX)
            when (intent.action) {
                DownloadService.BROADCAST_PROGRESS -> {
                    val pct   = intent.getIntExtra(DownloadService.KEY_PCT, 0)
                    val bytes = intent.getLongExtra(DownloadService.KEY_BYTES, 0)
                    val total = intent.getLongExtra(DownloadService.KEY_TOTAL, 0)
                    if (isUpdate) {
                        pushToJs("window._appUpdateProgress && window._appUpdateProgress($pct,$bytes,$total)")
                    } else {
                        pushToJs("window._dlProgress && window._dlProgress('$id',$pct,$bytes,$total)")
                    }
                }
                DownloadService.BROADCAST_DONE -> {
                    val path = intent.getStringExtra(DownloadService.KEY_PATH) ?: ""
                    if (isUpdate) {
                        pushToJs("window._appUpdateDone && window._appUpdateDone('${escJs(path)}')")
                    } else {
                        pushToJs("window._dlDone && window._dlDone('$id','${escJs(path)}')")
                    }
                }
                DownloadService.BROADCAST_ERROR -> {
                    val err = intent.getStringExtra(DownloadService.KEY_ERROR) ?: "Failed"
                    if (isUpdate) {
                        pushToJs("window._appUpdateError && window._appUpdateError('${escJs(err)}')")
                    } else {
                        pushToJs("window._dlError && window._dlError('$id','${escJs(err)}')")
                    }
                }
                DownloadService.BROADCAST_CANCELLED -> {
                    if (isUpdate) {
                        pushToJs("window._appUpdateCancelled && window._appUpdateCancelled()")
                    } else {
                        pushToJs("window._dlCancelled && window._dlCancelled('$id')")
                    }
                }
            }
        }
    }

    private fun pushToJs(js: String) {
        runOnUiThread { webView.evaluateJavascript(js, null) }
    }

    private fun escJs(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        fullscreenContainer = findViewById(R.id.fullscreen_container)

        // Auto-detect device lock on first ever launch
        BiometricLockActivity.autoDetectAndEnable(this)

        // Set up notification channel for push notifications (no-op if already created)
        com.puja.user.push.FCMService.ensureChannel(this)
        requestNotificationPermissionIfNeeded()

        val html = HtmlVault.getHtml(this)
        if (html == null) {
            Toast.makeText(this, "Failed to load content", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupWebView()
        webView.loadDataWithBaseURL("https://app.local/", html, "text/html", "UTF-8", null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.addJavascriptInterface(AndroidBridge(), "AndroidApp")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val js = "(function(){" +
                        "document.addEventListener('contextmenu',function(e){e.preventDefault();},true);" +
                        "document.addEventListener('selectstart',function(e){e.preventDefault();},true);" +
                        "document.addEventListener('copy',function(e){e.preventDefault();},true);" +
                        "var s=document.createElement('style');" +
                        "s.textContent='*{-webkit-user-select:none!important;user-select:none!important;}';" +
                        "document.head.appendChild(s);" +
                        "console.log=console.warn=console.error=function(){};" +
                        "})();"
                view?.evaluateJavascript(js, null)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return when {
                    url.startsWith("view-source:") -> true
                    url.startsWith("file://") -> true
                    url.startsWith("content://") -> true
                    url.contains("youtube.com") || url.contains("youtu.be") -> {
                        tryExternalIntent(url)
                        true
                    }
                    url.startsWith("https://") || url.startsWith("http://") -> false
                    else -> {
                        tryExternalIntent(url)
                        true
                    }
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(view)
                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }

            override fun onHideCustomView() {
                fullscreenContainer.removeAllViews()
                fullscreenContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                return try {
                    val intent = fileChooserParams?.createIntent() ?: return false
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE)
                    true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    false
                }
            }

            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                Toast.makeText(this@MainActivity, message ?: "", Toast.LENGTH_SHORT).show()
                result?.confirm()
                return true
            }
        }
    }

    private fun tryExternalIntent(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            // No app available to handle this URL; ignore.
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            val results = if (resultCode == Activity.RESULT_OK && data?.data != null)
                arrayOf(data.data!!) else null
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        } else if (requestCode == INSTALL_PERMISSION_REQUEST_CODE) {
            val path = pendingInstallPath
            pendingInstallPath = null
            if (path != null &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls())
            ) {
                performInstallApk(path)
            }
        }
    }

    private fun showExitDialog() {
        if (exitDialog?.isShowing == true) return

        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_exit_premium)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        val card    = dialog.findViewById<View>(R.id.exit_card)
        val btnStay = dialog.findViewById<TextView>(R.id.btn_cancel_exit)
        val btnExit = dialog.findViewById<TextView>(R.id.btn_confirm_exit)

        // Entrance animation — card pops up from slightly below
        card.alpha = 0f
        card.scaleX = 0.85f
        card.scaleY = 0.85f
        card.translationY = 60f
        card.animate()
            .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(280)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        btnStay.setOnClickListener {
            card.animate()
                .alpha(0f).scaleX(0.88f).scaleY(0.88f).translationY(40f)
                .setDuration(200)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction { dialog.dismiss() }
                .start()
        }

        btnExit.setOnClickListener {
            card.animate()
                .alpha(0f).scaleX(0.88f).scaleY(0.88f).translationY(40f)
                .setDuration(180)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    dialog.dismiss()
                    finishAndRemoveTask()
                }
                .start()
        }

        dialog.setOnDismissListener { exitDialog = null }

        exitDialog = dialog
        dialog.show()
    }
    // ─────────────────────────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (customView != null) {
                webView.webChromeClient?.onHideCustomView()
                return true
            }
            if (webView.canGoBack()) {
                webView.goBack()
                return true
            }
            webView.evaluateJavascript("(function(){ try { return String(window._appBack && window._appBack()); } catch(e){ return 'false'; } })();") { result ->
                if (result != "true" && result != "\"true\"") {
                    runOnUiThread { showExitDialog() }
                }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        AppLockManager.onBackground()
        webView.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(downloadReceiver)
    }

    override fun onResume() {
        super.onResume()

        // App lock check — show lock screen if enabled and session expired
        if (BiometricLockActivity.isLockEnabled(this) && AppLockManager.needsRelock()) {
            startActivity(Intent(this, BiometricLockActivity::class.java))
        }

        webView.onResume()
        val filter = IntentFilter().apply {
            addAction(DownloadService.BROADCAST_PROGRESS)
            addAction(DownloadService.BROADCAST_DONE)
            addAction(DownloadService.BROADCAST_ERROR)
            addAction(DownloadService.BROADCAST_CANCELLED)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(downloadReceiver, filter)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_STORAGE_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                val url   = pendingDownloadUrl   ?: return
                val title = pendingDownloadTitle ?: "file"
                launchDownloadService(url, title, pendingDownloadId)
            } else {
                Toast.makeText(this, "Storage permission required to download", Toast.LENGTH_LONG).show()
                if (pendingDownloadId?.startsWith(UPDATE_ID_PREFIX) == true) {
                    pushToJs("window._appUpdateError && window._appUpdateError('Storage permission required')")
                }
            }
            pendingDownloadUrl = null; pendingDownloadTitle = null; pendingDownloadId = null
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                PERM_NOTIF_REQUEST_CODE
            )
        }
    }

    private fun launchDownloadService(url: String, title: String, id: String? = null) {
        val intent = Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START
            putExtra(DownloadService.EXTRA_URL, url)
            putExtra(DownloadService.EXTRA_TITLE, title)
            if (id != null) putExtra(DownloadService.EXTRA_ID, id)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun sendServiceAction(action: String, id: String) {
        startService(Intent(this, DownloadService::class.java).apply {
            this.action = action
            putExtra(DownloadService.EXTRA_ID, id)
        })
    }

    /**
     * Launches the system package installer for a downloaded update APK.
     * On Android 8.0+ this first checks (and if needed requests) the
     * "install unknown apps" permission for this app specifically.
     */
    private fun performInstallApk(path: String) {
        try {
            val file = java.io.File(path)
            if (!file.exists()) {
                Toast.makeText(this, "Update file not found", Toast.LENGTH_SHORT).show()
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                pendingInstallPath = path
                Toast.makeText(this, "Please allow installing unknown apps, then try again", Toast.LENGTH_LONG).show()
                val settingsIntent = Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
                try {
                    startActivityForResult(settingsIntent, INSTALL_PERMISSION_REQUEST_CODE)
                } catch (e: Exception) {
                    Toast.makeText(this, "Unable to open install permission settings", Toast.LENGTH_LONG).show()
                }
                return
            }
            val apkUri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Install error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    inner class AndroidBridge {
        @android.webkit.JavascriptInterface
        fun getPlatform(): String = "android"

        @android.webkit.JavascriptInterface
        fun isAndroid(): Boolean = true

        /**
         * Returns the device's current FCM registration token (or empty string if not
         * yet available). JS: AndroidApp.getFcmToken()
         * This is synchronous from JS's point of view but the underlying fetch can take
         * a brief moment on first install, so JS should also listen for
         * window._onFcmToken(token) which fires once the token is ready/refreshed.
         */
        @android.webkit.JavascriptInterface
        fun getFcmToken(): String {
            return com.puja.user.push.FCMService.getStoredToken(this@MainActivity) ?: ""
        }

        /**
         * Forces a fresh token fetch and pushes the result to JS via
         * window._onFcmToken(token). JS: AndroidApp.requestFcmToken()
         */
        @android.webkit.JavascriptInterface
        fun requestFcmToken() {
            com.puja.user.push.FCMService.fetchToken(this@MainActivity) { token ->
                if (!token.isNullOrBlank()) {
                    pushToJs("window._onFcmToken && window._onFcmToken('${escJs(token)}')")
                }
            }
        }

        /**
         * Get current lock status.
         * JS: AndroidApp.getLockStatus() → returns "enabled", "disabled", or "unavailable"
         * "unavailable" means no biometric/PIN is configured on the device.
         */
        @android.webkit.JavascriptInterface
        fun getLockStatus(): String {
            return when {
                !BiometricLockActivity.canAuthenticate(this@MainActivity) -> "unavailable"
                BiometricLockActivity.isLockEnabled(this@MainActivity)    -> "enabled"
                else                                                      -> "disabled"
            }
        }

        /**
         * Enable or disable app lock.
         * JS: AndroidApp.setLockEnabled(true) / AndroidApp.setLockEnabled(false)
         * Pushes _onLockStatusChanged(status) back to JS after saving.
         */
        @android.webkit.JavascriptInterface
        fun setLockEnabled(enabled: Boolean) {
            runOnUiThread {
                if (enabled && !BiometricLockActivity.canAuthenticate(this@MainActivity)) {
                    Toast.makeText(
                        this@MainActivity,
                        "No fingerprint or screen lock is set up on this device",
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }
                BiometricLockActivity.setLockEnabled(this@MainActivity, enabled)
                if (!enabled) AppLockManager.unlock()
                val msg = if (enabled) "App lock enabled" else "App lock disabled"
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                val status = if (enabled) "enabled" else "disabled"
                pushToJs("window._onLockStatusChanged && window._onLockStatusChanged('$status')")
            }
        }

        @android.webkit.JavascriptInterface
        fun showToast(message: String) {
            runOnUiThread { Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show() }
        }

        @android.webkit.JavascriptInterface
        fun openUrl(url: String) {
            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    // ignore
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun shareText(text: String) {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_SEND)
                intent.type = "text/plain"
                intent.putExtra(Intent.EXTRA_TEXT, text)
                startActivity(Intent.createChooser(intent, null))
            }
        }

        @android.webkit.JavascriptInterface
        fun playVideo(url: String, title: String) {
            runOnUiThread {
                try {
                    val intent = Intent(this@MainActivity, com.puja.user.player.PlayerActivity::class.java).apply {
                        putExtra(com.puja.user.player.PlayerActivity.EXTRA_VIDEO_URI, url)
                        putExtra(com.puja.user.player.PlayerActivity.EXTRA_VIDEO_TITLE, title)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Player error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun playVideoPreview(url: String, title: String, previewLimitMs: Long) {
            runOnUiThread {
                try {
                    val intent = Intent(this@MainActivity, com.puja.user.player.PlayerActivity::class.java).apply {
                        putExtra(com.puja.user.player.PlayerActivity.EXTRA_VIDEO_URI, url)
                        putExtra(com.puja.user.player.PlayerActivity.EXTRA_VIDEO_TITLE, title)
                        putExtra(com.puja.user.player.PlayerActivity.EXTRA_PREVIEW_LIMIT_MS, previewLimitMs)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Player error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @android.webkit.JavascriptInterface
        fun playPlaylist(urlsJson: String, titlesJson: String, startIndex: Int) {
            runOnUiThread {
                try {
                    val urls   = org.json.JSONArray(urlsJson)
                    val titles = org.json.JSONArray(titlesJson)
                    val urlArray   = Array(urls.length())   { urls.getString(it) }
                    val titleArray = Array(titles.length()) { titles.getString(it) }
                    val intent = Intent(this@MainActivity, com.puja.user.player.PlayerActivity::class.java).apply {
                        putExtra(com.puja.user.player.PlayerActivity.EXTRA_PLAYLIST,        urlArray)
                        putExtra(com.puja.user.player.PlayerActivity.EXTRA_PLAYLIST_TITLES, titleArray)
                        putExtra(com.puja.user.player.PlayerActivity.EXTRA_VIDEO_POSITION,  startIndex)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Player error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        /** Start a new download. JS: AndroidApp.startDownload(url, title) */
        @android.webkit.JavascriptInterface
        fun startDownload(url: String, title: String) {
            runOnUiThread {
                if (url.isBlank()) {
                    Toast.makeText(this@MainActivity, "No download link available", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(this@MainActivity,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingDownloadUrl = url; pendingDownloadTitle = title
                    ActivityCompat.requestPermissions(this@MainActivity,
                        arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        PERM_STORAGE_REQUEST_CODE)
                } else {
                    launchDownloadService(url, title)
                    Toast.makeText(this@MainActivity, "Download started: $title", Toast.LENGTH_SHORT).show()
                }
            }
        }

        /** Get all download records as JSON. JS: AndroidApp.getDownloads() */
        @android.webkit.JavascriptInterface
        fun getDownloads(): String {
            val prefs = getSharedPreferences(DownloadService.PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(DownloadService.PREFS_KEY_LIST, "[]") ?: "[]"
        }

        /** Cancel active download. JS: AndroidApp.cancelDownload(id) */
        @android.webkit.JavascriptInterface
        fun cancelDownload(id: String) {
            runOnUiThread { sendServiceAction(DownloadService.ACTION_CANCEL, id) }
        }

        /** Pause active download. JS: AndroidApp.pauseDownload(id) */
        @android.webkit.JavascriptInterface
        fun pauseDownload(id: String) {
            runOnUiThread { sendServiceAction(DownloadService.ACTION_PAUSE, id) }
        }

        /** Resume paused download. JS: AndroidApp.resumeDownload(id) */
        @android.webkit.JavascriptInterface
        fun resumeDownload(id: String) {
            runOnUiThread {
                val prefs = getSharedPreferences(DownloadService.PREFS_NAME, Context.MODE_PRIVATE)
                val list  = try { JSONArray(prefs.getString(DownloadService.PREFS_KEY_LIST, "[]") ?: "[]") }
                            catch (_: Exception) { JSONArray() }
                var rec: JSONObject? = null
                for (i in 0 until list.length()) {
                    val o = list.getJSONObject(i)
                    if (o.optString("id") == id) { rec = o; break }
                }
                if (rec == null) return@runOnUiThread
                val intent = Intent(this@MainActivity, DownloadService::class.java).apply {
                    action = DownloadService.ACTION_RESUME
                    putExtra(DownloadService.EXTRA_ID, id)
                    putExtra(DownloadService.EXTRA_URL, rec.optString("url"))
                    putExtra(DownloadService.EXTRA_TITLE, rec.optString("title"))
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                else startService(intent)
            }
        }

        /** Delete record and file. JS: AndroidApp.deleteDownload(id) */
        @android.webkit.JavascriptInterface
        fun deleteDownload(id: String) {
            runOnUiThread {
                val prefs   = getSharedPreferences(DownloadService.PREFS_NAME, Context.MODE_PRIVATE)
                val list    = try { JSONArray(prefs.getString(DownloadService.PREFS_KEY_LIST, "[]") ?: "[]") }
                              catch (_: Exception) { JSONArray() }
                val newList = JSONArray()
                for (i in 0 until list.length()) {
                    val obj = list.getJSONObject(i)
                    if (obj.optString("id") == id) {
                        val path = obj.optString("path", "")
                        if (path.isNotBlank()) {
                            val f = java.io.File(path)
                            if (f.exists()) f.delete()
                        }
                    } else {
                        newList.put(obj)
                    }
                }
                prefs.edit().putString(DownloadService.PREFS_KEY_LIST, newList.toString()).apply()
                pushToJs("window._dlDeleted && window._dlDeleted('$id')")
            }
        }

        // ── JS bridge: show exit dialog from WebView HTML ─────────────────────
        @android.webkit.JavascriptInterface
        fun showExitDialog() {
            runOnUiThread { this@MainActivity.showExitDialog() }
        }
        // ─────────────────────────────────────────────────────────────────────

        // ── App Update (direct-link installer, no Play Store / no Firebase Storage) ──

        /** Current installed app version code. JS: AndroidApp.getAppVersionCode() */
        @android.webkit.JavascriptInterface
        fun getAppVersionCode(): Int {
            return try {
                val info = packageManager.getPackageInfo(packageName, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt()
                else @Suppress("DEPRECATION") info.versionCode
            } catch (e: Exception) {
                0
            }
        }

        /** Current installed app version name. JS: AndroidApp.getAppVersionName() */
        @android.webkit.JavascriptInterface
        fun getAppVersionName(): String {
            return try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: ""
            } catch (e: Exception) {
                ""
            }
        }

        /**
         * Downloads an app-update APK from a direct link set by the admin panel.
         * Reuses DownloadService so progress/pause/resume/retry all work the
         * same way as normal downloads, but routes its broadcasts to the
         * dedicated window._appUpdate* JS callbacks instead of the Downloads tab.
         * JS: AndroidApp.downloadAppUpdate(url, versionCode)
         */
        @android.webkit.JavascriptInterface
        fun downloadAppUpdate(url: String, versionCode: String) {
            runOnUiThread {
                if (url.isBlank()) {
                    pushToJs("window._appUpdateError && window._appUpdateError('No download link available')")
                    return@runOnUiThread
                }
                val id    = UPDATE_ID_PREFIX + versionCode
                val title = "NHSeries_Update_v$versionCode"
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(this@MainActivity,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingDownloadUrl = url; pendingDownloadTitle = title; pendingDownloadId = id
                    ActivityCompat.requestPermissions(this@MainActivity,
                        arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        PERM_STORAGE_REQUEST_CODE)
                } else {
                    launchDownloadService(url, title, id)
                }
            }
        }

        /**
         * Installs a previously-downloaded update APK from its local file path.
         * JS: AndroidApp.installApk(path)
         */
        @android.webkit.JavascriptInterface
        fun installApk(path: String) {
            runOnUiThread { performInstallApk(path) }
        }
        // ─────────────────────────────────────────────────────────────────────
    }
}
