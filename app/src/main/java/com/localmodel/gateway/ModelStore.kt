package com.localmodel.gateway

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ModelStore {

    val SUPPORTED_EXTENSIONS = listOf(".litertlm", ".task", ".gguf")

    fun modelsDir(context: Context): File =
        File(context.filesDir, "models").apply { mkdirs() }

    fun listInstalled(context: Context): List<File> =
        modelsDir(context).listFiles()
            ?.filter { it.isFile && SUPPORTED_EXTENSIONS.any { ext -> it.name.endsWith(ext, true) } }
            ?.sortedBy { it.name }
            ?: emptyList()

    suspend fun import(context: Context, uri: Uri, onProgress: (copied: Long, total: Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val name = displayName(context, uri) ?: "model.litertlm"
            val target = File(modelsDir(context), name)
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法读取所选文件")
            input.use { source ->
                target.outputStream().use { sink ->
                    val buffer = ByteArray(1 shl 20)
                    var copied = 0L
                    val total = sizeOf(context, uri)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        copied += read
                        onProgress(copied, total)
                    }
                }
            }
            target
        }

    private fun sizeOf(context: Context, uri: Uri): Long =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else 0L
            } ?: 0L
        }.getOrDefault(0L)

    private fun displayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
}
