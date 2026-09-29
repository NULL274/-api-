package com.localmodel.gateway

import android.content.Context
import android.os.Build
import java.io.File

object EnvProbe {

    @Volatile
    private var logged = false

    fun logEnvironment(context: Context) {
        if (logged) return
        logged = true
        runCatching {
            DiagLog.d(
                "设备: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} " +
                    "(SDK ${Build.VERSION.SDK_INT}, targetSdk ${context.applicationInfo.targetSdkVersion})"
            )
            val nativeDir = context.applicationInfo.nativeLibraryDir
            DiagLog.d("nativeLibraryDir=$nativeDir")
            DiagLog.d("nativeLibraryDir 内容=${File(nativeDir).list()?.joinToString()}")

            listOf(
                "/vendor/lib64/libOpenCL.so",
                "/vendor/lib/libOpenCL.so",
                "/system/lib64/libOpenCL.so",
                "/system/lib/libOpenCL.so",
                "/vendor/lib64/libOpenCL-pixel.so",
                "/vendor/etc/public.libraries.txt",
            ).forEach { path ->
                val file = File(path)
                DiagLog.d("路径检查 exists=${file.exists()} canRead=${file.canRead()}  $path")
            }

            runCatching { File("/vendor/etc/public.libraries.txt").readLines() }
                .getOrNull()
                ?.filter { it.contains("OpenCL", ignoreCase = true) }
                ?.forEach { DiagLog.d("public.libraries 含 OpenCL 条目: $it") }

            listOf("OpenCL", "OpenCL-pixel", "OpenCL-car").forEach { name ->
                val result = runCatching { System.loadLibrary(name) }
                DiagLog.d(
                    "System.loadLibrary(\"$name\") -> " +
                        (result.exceptionOrNull()?.let { "失败: ${it.message}" } ?: "成功")
                )
            }
        }
    }
}
