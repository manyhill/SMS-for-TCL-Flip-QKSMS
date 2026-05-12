package com.moez.QKSMS.common.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Writes uncaught crashes to internal storage so reports exist even without adb/logcat access.
 */
object LocalCrashLogger {

    private const val TAG = "LocalCrashLogger"
    private val installed = AtomicBoolean(false)

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return

        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                persistCrash(appContext, thread, throwable)
            }.onFailure { error ->
                Log.e(TAG, "Failed to persist uncaught crash", error)
            }

            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun persistCrash(context: Context, thread: Thread, throwable: Throwable) {
        val dir = File(context.noBackupFilesDir, "crash").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS", Locale.US).format(Date())
        val report = buildReport(context, thread, throwable, timestamp)

        // Keep a rolling "latest" file for quick sharing/retrieval + a timestamped archive.
        File(dir, "last_crash.txt").writeText(report)
        File(dir, "crash_$timestamp.txt").writeText(report)

        trimOldReports(dir, maxReports = 20)
    }

    private fun buildReport(
        context: Context,
        thread: Thread,
        throwable: Throwable,
        timestamp: String
    ): String {
        val stack = StringWriter().also { sw ->
            PrintWriter(sw).use { pw -> throwable.printStackTrace(pw) }
        }.toString()

        val version = runCatching {
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pkg.versionName} (${pkg.longVersionCode})"
        }.getOrElse { "unknown" }

        return buildString {
            appendLine("time=$timestamp")
            appendLine("package=${context.packageName}")
            appendLine("version=$version")
            appendLine("android=${Build.VERSION.RELEASE} (sdk=${Build.VERSION.SDK_INT})")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("thread=${thread.name}")
            appendLine()
            appendLine(stack)
        }
    }

    private fun trimOldReports(dir: File, maxReports: Int) {
        val reports = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("crash_") && it.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

        if (reports.size <= maxReports) return
        reports.drop(maxReports).forEach { file ->
            runCatching { file.delete() }
        }
    }
}

