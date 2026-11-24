package com.askfirst.core

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地崩溃日志（方案 §七.3）：不上架、不接崩溃平台，但亲友闪退时你得能拿到日志。
 *
 * - 捕获未处理异常，写到 `filesDir/crash/crash-<时间>.log`
 * - 只保留最近 [MAX_FILES] 份，避免无限增长
 * - 设置页有「把日志发给他」按钮（FileProvider + 系统分享）
 *
 * 注意：**不记录任何密钥**。这里只写异常栈与设备信息。
 */
object CrashLogger {

    private const val DIR = "crash"
    private const val MAX_FILES = 5

    fun install(context: Context, versionName: String) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(appContext, thread, throwable, versionName) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun write(context: Context, thread: Thread, throwable: Throwable, versionName: String): File? = runCatching {
        val dir = context.filesDir.resolve(DIR).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash-$stamp.log")
        val stack = StringWriter().also { writer ->
            PrintWriter(writer).use { throwable.printStackTrace(it) }
        }.toString()
        file.writeText(
            buildString {
                appendLine("时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                appendLine("版本：$versionName")
                appendLine("线程：${thread.name} (id=${thread.id})")
                appendLine("机型：Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT}) / ${android.os.Build.MODEL}")
                appendLine()
                append(stack)
            },
        )
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_FILES)?.forEach { it.delete() }
        file
    }.getOrNull()

    fun latest(context: Context): File? =
        context.filesDir.resolve(DIR).listFiles()?.maxByOrNull { it.lastModified() }

    fun all(context: Context): List<File> =
        context.filesDir.resolve(DIR).listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()

    fun clear(context: Context): Int {
        val files = all(context)
        files.forEach { it.delete() }
        return files.size
    }
}
