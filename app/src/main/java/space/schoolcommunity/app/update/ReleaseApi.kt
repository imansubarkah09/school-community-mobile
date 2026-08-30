package space.schoolcommunity.app.update

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One published Android release, as described by the Mobile Release Registry. */
data class Release(
    val versionName: String,
    val versionCode: Long,
    val minimumSupportedVersion: Long,
    val apkUrl: String,
    val fileName: String?,
    val releaseNotes: List<String>,
    val forceUpdate: Boolean,
)

/**
 * Read-only client for the authoritative release source:
 * GET https://schoolcommunity.space/api/mobile/android/latest
 *
 * Returns null for every "no usable info" case (no release published yet -> HTTP 404
 * {"error":"NO_RELEASE"}, offline, timeout, malformed body). Never throws, so a flaky
 * network can never block the app.
 */
object ReleaseApi {
    const val LATEST_URL = "https://schoolcommunity.space/api/mobile/android/latest"
    private const val TAG = "ReleaseApi"

    fun fetchLatest(url: String = LATEST_URL): Release? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            useCaches = false // always fresh; the endpoint's own max-age=60 is enough
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (c.responseCode !in 200..299) {
                Log.i(TAG, "latest -> HTTP ${c.responseCode}, treating as no update")
                null
            } else {
                parse(c.inputStream.bufferedReader().use { it.readText() })
            }
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        Log.w(TAG, "latest check failed: ${e.message}")
        null
    }

    /** Visible for tests. */
    fun parse(body: String): Release? = try {
        val o = JSONObject(body)
        val apkUrl = o.optString("apkUrl")
        val versionCode = o.optLong("versionCode", -1)
        when {
            versionCode < 0 -> null
            !apkUrl.startsWith("https://") -> null
            else -> Release(
                versionName = o.optString("versionName").ifBlank { versionCode.toString() },
                versionCode = versionCode,
                minimumSupportedVersion = o.optLong("minimumSupportedVersion", 0L),
                apkUrl = apkUrl,
                fileName = o.optString("fileName").ifBlank { null },
                releaseNotes = o.optJSONArray("releaseNotes")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
                } ?: emptyList(),
                forceUpdate = o.optBoolean("forceUpdate", false),
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "malformed release JSON: ${e.message}")
        null
    }
}
