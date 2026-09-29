package com.localmodel.gateway

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagLog {

    const val TAG = "LocalModel"

    private const val MAX_BYTES = 2L * 1024 * 1024

    private val lock = Any()
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var file: File? = null

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        file = File(dir, "localmodel.log")
    }

    fun path(): String? = file?.absolutePath

    fun d(message: String) {
        Log.d(TAG, message)
        append("D", message)
    }

    fun w(message: String, throwable: Throwable? = null) {
        Log.w(TAG, message, throwable)
        append("W", withCause(message, throwable))
    }

    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        append("E", withCause(message, throwable, detailed = true))
    }

    fun clear() = synchronized(lock) { runCatching { file?.writeText("") } }

    fun export(context: Context, uri: Uri): Boolean = runCatching {
        val source = file
        context.contentResolver.openOutputStream(uri)?.use { output ->
            if (source != null && source.exists()) {
                source.inputStream().use { it.copyTo(output) }
            } else {
                output.write("（暂无日志）\n".toByteArray())
            }
        } ?: return false
        true
    }.getOrDefault(false)

    private fun withCause(message: String, throwable: Throwable?, detailed: Boolean = false): String =
        when {
            throwable == null -> message
            detailed -> "$message :: ${throwable.stackTraceToString()}"
            else -> "$message :: ${throwable.message}"
        }

    private fun append(level: String, message: String) {
        val target = file ?: return
        synchronized(lock) {
            runCatching {
                if (target.length() > MAX_BYTES) target.writeText("")
                target.appendText("${stamp.format(Date())} [$level] $message\n")
            }
        }
    }
}
