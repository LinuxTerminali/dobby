package app.dobby.util

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes uncaught exceptions to a file the user can read without logcat:
 * <app files>/crash.txt, and Download/Dobby/crash.txt when all-files access is granted.
 */
object CrashLog {
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { write(context, t, e) }
            previous?.uncaughtException(t, e)
        }
    }

    private fun write(context: Context, t: Thread, e: Throwable) {
        val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
        val text = "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}  thread=${t.name}\n$sw"
        File(context.getExternalFilesDir(null), "crash.txt").writeText(text)
        if (Environment.isExternalStorageManager()) {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Dobby").apply { mkdirs() }
            File(dir, "crash.txt").writeText(text)
        }
    }

    fun last(context: Context): String? = File(context.getExternalFilesDir(null), "crash.txt").takeIf { it.exists() }?.readText()
    fun clear(context: Context) { File(context.getExternalFilesDir(null), "crash.txt").delete() }
}
