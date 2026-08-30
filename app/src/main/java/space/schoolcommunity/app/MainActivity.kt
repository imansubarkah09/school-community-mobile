package space.schoolcommunity.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.appcompat.app.AlertDialog
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import space.schoolcommunity.app.update.Release
import space.schoolcommunity.app.update.ReleaseApi
import space.schoolcommunity.app.update.UpdateDecision
import space.schoolcommunity.app.update.UpdateType
import java.io.File
import java.io.FileOutputStream

/**
 * Single-screen shell around https://school-community.space.
 * All business logic lives in the web app; this class only handles Android platform glue:
 * back navigation, external links, file upload/download, and an offline fallback screen.
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val BASE_URL = "https://schoolcommunity.space"
        const val APP_HOST = "schoolcommunity.space"
        // better-auth session cookie; present ~= user is (or was) logged in.
        const val SESSION_COOKIE = "better-auth.session_token"

        // Flip to true to surface which code path handles a download attempt, as toasts.
        const val DEBUG_DOWNLOADS = false
        const val DL_CHANNEL = "downloads"

        // Catches downloads the native DownloadListener never sees: client-generated
        // (jsPDF / pdfmake / SheetJS / react-pdf / FileSaver) files delivered via a
        // blob:/data: URL. FileSaver.js clicks a *detached* anchor via dispatchEvent,
        // so we have to hook createObjectURL + .click() + dispatchEvent + window.open.
        const val BLOB_DOWNLOAD_JS = """
            (function(){
              if(window.__scDL)return;window.__scDL=true;
              function log(m){try{AndroidDownloader.log(''+m)}catch(e){}}
              var U=window.URL||window.webkitURL,orig=U.createObjectURL,blobs={};
              U.createObjectURL=function(o){var u=orig.call(U,o);
                try{if(o instanceof Blob){blobs[u]=o;log('createObjectURL '+(o.type||'?')+' '+o.size+'B')}}catch(e){}
                return u};
              function toBridge(blob,name){var r=new FileReader();
                r.onloadend=function(){AndroidDownloader.save(r.result,name||'')};
                r.onerror=function(){log('FileReader error')};
                r.readAsDataURL(blob)}
              function handle(href,name){
                if(!href)return false;
                if(href.lastIndexOf('blob:',0)===0){
                  log('intercept blob '+(name||''));
                  var b=blobs[href];
                  if(b){toBridge(b,name)}
                  else{var x=new XMLHttpRequest();x.open('GET',href);x.responseType='blob';
                    x.onload=function(){toBridge(x.response,name)};
                    x.onerror=function(){log('blob XHR error')};x.send()}
                  return true}
                if(href.lastIndexOf('data:',0)===0){
                  log('intercept data '+(name||''));AndroidDownloader.save(href,name||'');return true}
                return false}
              function fromAnchor(el){
                return el&&el.tagName==='A'&&handle(el.getAttribute('href')||el.href,el.getAttribute('download'))}
              var click=HTMLElement.prototype.click;
              HTMLElement.prototype.click=function(){
                try{if(fromAnchor(this))return}catch(e){log('click hook '+e)}
                return click.apply(this,arguments)};
              var dispatch=EventTarget.prototype.dispatchEvent;
              EventTarget.prototype.dispatchEvent=function(ev){
                try{if(ev&&ev.type==='click'&&fromAnchor(this))return true}catch(e){log('dispatch hook '+e)}
                return dispatch.apply(this,arguments)};
              document.addEventListener('click',function(e){
                try{var a=e.target.closest&&e.target.closest('a[href]');
                  if(a&&fromAnchor(a)){e.preventDefault();e.stopPropagation()}}catch(err){log('doc click '+err)}
              },true);
              var open=window.open;
              window.open=function(u){try{if(typeof u==='string'&&handle(u,''))return null}catch(e){}
                return open.apply(window,arguments)};
              log('download shim installed');
            })();
        """
    }

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var errorView: View
    private lateinit var progressBar: View
    private lateinit var updateBanner: View
    private lateinit var updateBannerText: android.widget.TextView
    private lateinit var updateBannerAction: View
    private lateinit var updateBannerClose: View

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingDownload: (() -> Unit)? = null
    private var loadFailed = false

    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            fileCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                    ?: emptyArray()
            )
            fileCallback = null
        }

    private val storagePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) pendingDownload?.invoke()
            pendingDownload = null
        }

    // So the "download complete" notification for client-generated files can post on Android 13+.
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.web_view)
        swipeRefresh = findViewById(R.id.swipe_refresh)
        swipeRefresh.setOnRefreshListener { webView.reload() }
        errorView = findViewById(R.id.error_view)
        progressBar = findViewById(R.id.progress_bar)
        updateBanner = findViewById(R.id.update_banner)
        updateBannerText = findViewById(R.id.update_banner_text)
        updateBannerAction = findViewById(R.id.update_banner_action)
        updateBannerClose = findViewById(R.id.update_banner_close)
        findViewById<View>(R.id.retry_button).setOnClickListener {
            errorView.isVisible = false
            webView.reload()
        }

        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            // Some report/export buttons call window.open(); route those through onCreateWindow.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            // Google rejects OAuth from user agents containing "; wv"; present as plain Chrome.
            userAgentString = userAgentString.replace("; wv", "")
        }

        // ponytail: single-method bridge, required for client-generated file downloads.
        webView.addJavascriptInterface(DownloadBridge(), "AndroidDownloader")

        // Inject the download shim before the page's own scripts, on our origin only.
        // This is the reliable path; onPageFinished re-injection below is the fallback.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                webView, BLOB_DOWNLOAD_JS, setOf("https://$APP_HOST")
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                handleUrl(request)

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                loadFailed = false
                progressBar.isVisible = !swipeRefresh.isRefreshing // swipe spinner already visible
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.isVisible = false
                swipeRefresh.isRefreshing = false
                if (!loadFailed) errorView.isVisible = false
                if (Uri.parse(url).host?.let { it == APP_HOST || it.endsWith(".$APP_HOST") } == true) {
                    view?.evaluateJavascript(BLOB_DOWNLOAD_JS, null)
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    loadFailed = true
                    progressBar.isVisible = false
                    swipeRefresh.isRefreshing = false
                    errorView.isVisible = true
                } else {
                    debug("subframe error ${error.errorCode} ${request.url.toString().take(60)}")
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // window.open() / target=_blank: run the target through the main WebView so its
            // cookies + DownloadListener apply (report/export links often open this way).
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                debug("popup opened")
                val relay = WebView(this@MainActivity).apply {
                    settings.userAgentString = webView.settings.userAgentString
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                            debug("popup url ${r.url.toString().take(70)}")
                            webView.loadUrl(r.url.toString())
                            v.post { v.destroy() }
                            return true
                        }
                    }
                    setDownloadListener { url, ua, cd, mt, _ ->
                        startDownload(url, ua, cd, mt)
                        post { destroy() }
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = relay
                resultMsg.sendToTarget()
                return true
            }

            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                if (DEBUG_DOWNLOADS && m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                    debug("console: ${m.message().take(80)}")
                }
                return true
            }

            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    fileChooser.launch(params.createIntent())
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            debug("DownloadListener $mimeType ${url.take(60)}")
            startDownload(url, userAgent, contentDisposition, mimeType)
        }

        // Back: walk web history first, exit the app only when there is nothing to go back to.
        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }

        if (savedInstanceState == null) webView.loadUrl(startUrl())
        // checkForUpdate() runs in onResume — covers both cold launch and return-from-background.
    }

    override fun onResume() {
        super.onResume()
        checkForUpdate()
    }

    /** Cold-launch target: straight to the dashboard when a session cookie exists. */
    private fun startUrl(): String {
        val cookies = CookieManager.getInstance().getCookie(BASE_URL)
        return if (cookies?.contains(SESSION_COOKIE) == true) "$BASE_URL/dashboard" else "$BASE_URL/"
    }

    private var mandatoryDialog: AlertDialog? = null
    private var optionalBannerDismissed = false

    /** Off-thread read of the release registry; any failure just does nothing. Not cached. */
    private fun checkForUpdate() {
        Thread {
            val decision = UpdateDecision.of(
                BuildConfig.VERSION_CODE.toLong(),
                ReleaseApi.fetchLatest(),
            )
            runOnUiThread { if (!isFinishing) handleUpdateDecision(decision) }
        }.start()
    }

    private fun handleUpdateDecision(decision: UpdateDecision) {
        val release = decision.release ?: return
        when (decision.type) {
            UpdateType.NONE -> Unit
            UpdateType.MANDATORY -> showMandatoryUpdate(release)
            UpdateType.OPTIONAL -> showUpdateBanner(release)
        }
    }

    private fun showMandatoryUpdate(release: Release) {
        if (mandatoryDialog?.isShowing == true) return
        val body = release.releaseNotes.joinToString("\n") { "• $it" }
            .ifBlank { getString(R.string.update_mandatory_body) }
        mandatoryDialog = AlertDialog.Builder(this)
            .setCancelable(false)
            .setTitle(R.string.update_mandatory_title)
            .setMessage(body)
            .setPositiveButton(R.string.update_now) { _, _ -> openApk(release.apkUrl) }
            .show()
    }

    private fun showUpdateBanner(release: Release) {
        if (optionalBannerDismissed) return
        updateBannerText.text = getString(R.string.update_banner_text, release.versionName)
        updateBannerAction.setOnClickListener { openApk(release.apkUrl) }
        updateBannerClose.setOnClickListener {
            optionalBannerDismissed = true
            updateBanner.isVisible = false
        }
        updateBanner.isVisible = true
    }

    /**
     * Download the APK via Android's DownloadManager, not the browser: Chrome's download
     * UI notoriously stalls at 100% on GitHub release-asset redirects. DownloadManager
     * follows the redirect, finalizes reliably, and its "download complete" notification
     * opens the package installer on tap. User still approves the install manually.
     */
    private fun openApk(apkUrl: String) {
        if (!apkUrl.startsWith("https://")) return
        try {
            startDownload(apkUrl, webView.settings.userAgentString, "", "application/vnd.android.package-archive")
        } catch (e: Exception) {
            openExternally(Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl))) // last resort
        }
    }

    /** @return true when the URL was handed off elsewhere and the WebView should not load it. */
    private fun handleUrl(request: WebResourceRequest): Boolean {
        val uri = request.url
        when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                if (isInternalHost(uri.host)) return false
                // Only leave the app on a deliberate tap. JS-driven hops and redirects
                // through other hosts (e.g. Google OAuth / 2-Step) must stay in the WebView
                // so their session cookies survive.
                if (!request.hasGesture() || request.isRedirect) return false
                openExternally(Intent(Intent.ACTION_VIEW, uri))
            }
            "intent" -> {
                // Continue an intent:// hop as plain web navigation (keeps cookies);
                // only fall out to another app when there is no web URL to follow.
                val parsed = runCatching {
                    Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                }.getOrNull()
                val webUrl = parsed?.dataString?.takeIf { it.startsWith("http") }
                    ?: parsed?.getStringExtra("browser_fallback_url")
                when {
                    webUrl != null -> webView.loadUrl(webUrl)
                    parsed != null -> openExternally(parsed)
                }
            }
            // tel:, mailto:, sms:, geo:, whatsapp:, ...
            else -> openExternally(Intent(Intent.ACTION_VIEW, uri))
        }
        return true
    }

    private fun isInternalHost(host: String?): Boolean {
        if (host == null) return false
        return host == APP_HOST || host.endsWith(".$APP_HOST") ||
            host == "google.com" || host.endsWith(".google.com") ||
            host.endsWith(".googleusercontent.com") ||
            host.endsWith(".gstatic.com")
    }

    private fun openExternally(intent: Intent?) {
        if (intent == null) return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
        }
    }

    /** True when the pre-Android-10 storage permission is still needed and was requested. */
    private fun requestedStoragePermission(retry: () -> Unit): Boolean {
        val needed = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needed) {
            pendingDownload = retry
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return needed
    }

    private fun startDownload(url: String, ua: String, disposition: String, mimeType: String) {
        if (!url.startsWith("https://")) return
        if (requestedStoragePermission { startDownload(url, ua, disposition, mimeType) }) return

        val name = URLUtil.guessFileName(url, disposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(mimeType)
            addRequestHeader("User-Agent", ua)
            // Carry the session so downloads behind Google login (PDF/Excel exports) succeed.
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            setTitle(name)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        }
        (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        Toast.makeText(this, getString(R.string.downloading, name), Toast.LENGTH_SHORT).show()
    }

    private fun debug(msg: String) {
        if (DEBUG_DOWNLOADS) runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    /** JS bridge for downloads the WebView never surfaces to DownloadListener (blob:/data:). */
    private inner class DownloadBridge {
        @JavascriptInterface
        fun save(dataUrl: String, suggestedName: String) {
            runOnUiThread { saveDataUrl(dataUrl, suggestedName) }
        }

        @JavascriptInterface
        fun log(msg: String) = debug("js: $msg")
    }

    private fun saveDataUrl(dataUrl: String, suggestedName: String) {
        if (requestedStoragePermission { saveDataUrl(dataUrl, suggestedName) }) return
        try {
            val comma = dataUrl.indexOf(',')
            val header = dataUrl.substring("data:".length, comma)
            val mime = header.substringBefore(';').ifEmpty { "application/octet-stream" }
            val bytes = if (header.contains("base64")) {
                Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
            } else {
                Uri.decode(dataUrl.substring(comma + 1)).toByteArray()
            }
            val name = suggestedName.substringAfterLast('/').ifBlank {
                "download-${System.currentTimeMillis()}" + extForMime(mime)
            }
            val uri = writeToDownloads(name, mime, bytes)
            if (uri != null) {
                notifyDownloadComplete(name, uri, mime)
            } else {
                Toast.makeText(this, getString(R.string.saved_to_downloads, name), Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            debug("save failed: ${e.message}")
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Native "download complete" notification; tap opens the file (app picker appears there). */
    private fun notifyDownloadComplete(name: String, uri: Uri, mime: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(DL_CHANNEL, getString(R.string.dl_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = Intent.createChooser(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            getString(R.string.open_with),
        )
        val pi = PendingIntent.getActivity(
            this, uri.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            uri.hashCode(),
            NotificationCompat.Builder(this, DL_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(getString(R.string.dl_complete))
                .setContentText(name)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build(),
        )
    }

    private fun extForMime(mime: String) = when (mime) {
        "application/pdf" -> ".pdf"
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx"
        "application/vnd.ms-excel" -> ".xls"
        "text/csv" -> ".csv"
        else -> ""
    }

    /** @return a viewable content:// Uri for the saved file (null on pre-Q). */
    private fun writeToDownloads(name: String, mime: String, bytes: ByteArray): Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pending = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, pending)
                ?: error("insert failed")
            contentResolver.openOutputStream(uri).use { it!!.write(bytes) }
            contentResolver.update(
                uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null
            )
            return uri
        }
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        FileOutputStream(File(dir, name)).use { it.write(bytes) }
        return null
    }
}
