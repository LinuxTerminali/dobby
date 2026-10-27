package app.dobby.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import app.dobby.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates from GitHub Releases. CI tags each main build `build-N` and attaches the APK;
 * N is also the app's versionCode, so "newer" is a plain integer compare.
 * Note: installing is still a sideload; Play Protect may block it. `adb install -r` always works.
 */
object UpdateChecker {
    const val REPO = "LinuxTerminali/dobby"

    data class Release(val build: Int, val name: String, val apkUrl: String, val notes: String) {
        val isNewer get() = build > BuildConfig.VERSION_CODE
    }

    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 15000; c.readTimeout = 15000
        if (c.responseCode != 200) return@withContext null
        val j = JSONObject(c.inputStream.bufferedReader().readText())
        val build = j.optString("tag_name").removePrefix("build-").toIntOrNull() ?: return@withContext null
        val assets = j.optJSONArray("assets")
        var apk: String? = null
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) apk = a.optString("browser_download_url")
        }
        apk?.let { Release(build, j.optString("name"), it, j.optString("body")) }
    }

    fun apkFile(context: Context, r: Release) = File(File(context.getExternalFilesDir(null), "updates"), "dobby-${r.build}.apk")

    fun download(context: Context, r: Release): Long {
        apkFile(context, r).delete()
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(r.apkUrl))
            .setTitle("Dobby build ${r.build}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, null, "updates/dobby-${r.build}.apk")
        return dm.enqueue(req)
    }

    fun isDownloaded(context: Context, r: Release) = apkFile(context, r).let { it.exists() && it.length() > 1_000_000 }

    fun install(context: Context, r: Release) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apkFile(context, r))
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }
}
