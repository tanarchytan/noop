package com.noop

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Captures the last uncaught exception to a file so a crash that only reproduces on a user's own
 * device — a deterministic crash on a specific data shape, like the Insights tab — lands
 * in the shareable strap log instead of being lost to a logcat no one can reach without adb. The
 * handler records the trace, then chains to the previous handler so the process still dies normally
 * (we never swallow the crash). [LogExport] appends [lastCrash] to the strap log header.
 */
object CrashCapture {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // The handler itself must never throw, or we replace one crash with another.
            runCatching {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val text = buildString {
                    appendLine("when:   ${java.util.Date()}")
                    appendLine("thread: ${thread.name}")
                    appendLine(sw.toString())
                }
                File(appContext.filesDir, FILE).writeText(text)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** The captured crash text, or null if there hasn't been one. Surfaced by [LogExport]. */
    fun lastCrash(context: Context): String? {
        val f = File(context.applicationContext.filesDir, FILE)
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull()?.ifBlank { null }
    }
}
