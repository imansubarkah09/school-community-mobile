package space.schoolcommunity.app.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import space.schoolcommunity.app.BuildConfig
import space.schoolcommunity.app.R
import java.io.File

/**
 * Blocking (mandatory) or dismissible (optional) "please update" screen.
 *
 * Flow: download APK from the release's apkUrl via DownloadManager -> validate the file
 * (exists, non-empty, package id matches) -> hand to the system package installer.
 * Every failure path lands on [showRetry]; a mandatory update can never be dismissed
 * past this screen, but it always offers "Coba Lagi".
 */
class UpdateActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_MANDATORY = "mandatory"
        private const val EXTRA_APK_URL = "apkUrl"
        private const val EXTRA_VERSION_NAME = "versionName"
        private const val EXTRA_FILE_NAME = "fileName"
        private const val EXTRA_NOTES = "notes"
        private const val DIR = "updates"

        fun intent(ctx: Context, decision: UpdateDecision): Intent {
            val r = requireNotNull(decision.release)
            return Intent(ctx, UpdateActivity::class.java).apply {
                putExtra(EXTRA_MANDATORY, decision.type == UpdateType.MANDATORY)
                putExtra(EXTRA_APK_URL, r.apkUrl)
                putExtra(EXTRA_VERSION_NAME, r.versionName)
                putExtra(EXTRA_FILE_NAME, r.fileName)
                putStringArrayListExtra(EXTRA_NOTES, ArrayList(r.releaseNotes))
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var dm: DownloadManager
    private var mandatory = false
    private var downloadId = -1L

    private lateinit var progressBar: ProgressBar
    private lateinit var statusView: TextView
    private lateinit var retryButton: Button
    private lateinit var updateButton: Button

    private val onComplete = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) == downloadId) onDownloadComplete()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update)
        dm = getSystemService(DownloadManager::class.java)
        mandatory = intent.getBooleanExtra(EXTRA_MANDATORY, false)

        progressBar = findViewById(R.id.update_progress)
        statusView = findViewById(R.id.update_status)
        retryButton = findViewById(R.id.update_retry)
        updateButton = findViewById(R.id.update_now)
        val laterButton = findViewById<Button>(R.id.update_later)
        val notesView = findViewById<TextView>(R.id.update_notes)

        findViewById<TextView>(R.id.update_title).text =
            getString(if (mandatory) R.string.update_mandatory_title else R.string.update_available_title)
        findViewById<TextView>(R.id.update_message).text =
            if (mandatory) getString(R.string.update_mandatory_body)
            else getString(R.string.update_optional_body, intent.getStringExtra(EXTRA_VERSION_NAME).orEmpty())

        val notes = intent.getStringArrayListExtra(EXTRA_NOTES).orEmpty()
        if (notes.isNotEmpty()) {
            notesView.visibility = View.VISIBLE
            notesView.text = notes.joinToString("\n") { "• $it" }
        }

        updateButton.setOnClickListener { startDownload() }
        retryButton.setOnClickListener { startDownload() }
        laterButton.visibility = if (mandatory) View.GONE else View.VISIBLE
        laterButton.setOnClickListener { finish() }

        // Mandatory: swallow Back so the user cannot slip into the app behind this screen.
        onBackPressedDispatcher.addCallback(this) { if (!mandatory) finish() }

        ContextCompat.registerReceiver(
            this, onComplete,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED, // system broadcast
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(onComplete) }
    }

    private fun startDownload() {
        // Android needs per-app "install unknown apps" consent before the installer will run.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            showStatus(getString(R.string.update_need_install_permission))
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }

        val url = intent.getStringExtra(EXTRA_APK_URL).orEmpty()
        if (!url.startsWith("https://")) return showRetry(getString(R.string.update_corrupt))

        val fileName = (intent.getStringExtra(EXTRA_FILE_NAME) ?: "school-community-update.apk")
            .substringAfterLast('/')
            .ifBlank { "school-community-update.apk" }
        File(getExternalFilesDir(DIR), fileName).delete()

        downloadId = dm.enqueue(
            DownloadManager.Request(Uri.parse(url))
                .setTitle(getString(R.string.app_name))
                .setDescription(getString(R.string.update_downloading))
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(this, DIR, fileName),
        )
        retryButton.visibility = View.GONE
        updateButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        showStatus(getString(R.string.update_downloading))
        pollProgress()
    }

    private fun pollProgress() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                dm.query(DownloadManager.Query().setFilterById(downloadId)).use { c ->
                    if (!c.moveToFirst()) return
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val soFar = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    if (total > 0) progressBar.progress = (soFar * 100 / total).toInt()
                    when (status) {
                        DownloadManager.STATUS_FAILED ->
                            return showRetry(getString(R.string.update_download_failed))
                        DownloadManager.STATUS_SUCCESSFUL -> return // onComplete drives install
                        else -> {}
                    }
                }
                handler.postDelayed(this, 500)
            }
        }, 500)
    }

    private fun onDownloadComplete() {
        handler.removeCallbacksAndMessages(null)
        val fileName = (intent.getStringExtra(EXTRA_FILE_NAME) ?: "school-community-update.apk")
            .substringAfterLast('/').ifBlank { "school-community-update.apk" }
        val file = File(getExternalFilesDir(DIR), fileName)

        if (!file.exists() || file.length() == 0L) return showRetry(getString(R.string.update_corrupt))
        val info = packageManager.getPackageArchiveInfo(file.path, 0)
        if (info == null || info.packageName != BuildConfig.APPLICATION_ID) {
            file.delete()
            return showRetry(getString(R.string.update_corrupt))
        }

        val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        showStatus(getString(R.string.update_installing))
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        } catch (e: Exception) {
            return showRetry(getString(R.string.update_corrupt))
        }
        // Installer is now in front. If the user cancels, they return here with retry available.
        showRetry(null)
    }

    private fun showStatus(text: String) {
        statusView.visibility = View.VISIBLE
        statusView.text = text
    }

    private fun showRetry(message: String?) {
        progressBar.visibility = View.GONE
        updateButton.isEnabled = true
        retryButton.visibility = View.VISIBLE
        if (message != null) showStatus(message)
    }
}
