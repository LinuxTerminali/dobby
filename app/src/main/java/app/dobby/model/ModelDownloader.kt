package app.dobby.model

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Downloads model files with Android's DownloadManager: survives the app being closed,
 * resumes on flaky networks, shows a system notification.
 */
class ModelDownloader(private val context: Context, private val registry: ModelRegistry) {
    private val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)

    data class Progress(val running: Boolean, val percent: Int, val status: String)

    fun start(e: ModelEntry): Long {
        val target = registry.fileFor(e)
        if (target.exists()) target.delete()
        val req = DownloadManager.Request(Uri.parse(e.url))
            .setTitle(e.name)
            .setDescription("Model download")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, null, "models/${e.file}")
            .setAllowedOverMetered(false)
        val token = registry.hfToken
        if (token.isNotBlank()) req.addRequestHeader("Authorization", "Bearer $token")
        val id = dm.enqueue(req)
        prefs.edit().putLong(e.id, id).apply()
        return id
    }

    fun cancel(e: ModelEntry) {
        val id = prefs.getLong(e.id, -1)
        if (id >= 0) { dm.remove(id); prefs.edit().remove(e.id).apply() }
        registry.fileFor(e).delete()
    }

    fun delete(e: ModelEntry) { cancel(e) }

    fun progress(e: ModelEntry): Progress? {
        val id = prefs.getLong(e.id, -1)
        if (id < 0) return null
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return null
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val pct = if (total > 0) (done * 100 / total).toInt() else 0
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> Progress(false, 100, "done")
                DownloadManager.STATUS_FAILED -> Progress(false, pct, "failed: reason ${c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))}")
                DownloadManager.STATUS_PAUSED -> Progress(true, pct, "paused")
                DownloadManager.STATUS_PENDING -> Progress(true, 0, "queued")
                else -> Progress(true, pct, "downloading")
            }
        }
    }

    fun sizeOnDisk(e: ModelEntry): Long = registry.fileFor(e).takeIf(File::exists)?.length() ?: 0
}
