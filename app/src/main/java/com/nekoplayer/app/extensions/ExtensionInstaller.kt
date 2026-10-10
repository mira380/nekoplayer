package com.nekoplayer.app.extensions

import android.content.Context
import android.content.pm.PackageManager
import okhttp3.OkHttpClient
import okhttp3.Request
import com.nekoplayer.app.extensions.loader.ExtensionLoader
import com.nekoplayer.app.extensions.model.Extension
import java.io.File

class ExtensionInstaller(
    private val context: Context,
    private val client: OkHttpClient,
) {
    suspend fun install(extension: Extension.Available): Result<Unit> =
        runCatching {
            require(extension.isSupported) { "This extension uses an unsupported API version." }
            val staging = File(context.cacheDir, "extension-${extension.pkgName}.apk")
            val target = ExtensionLoader.privateFile(context, extension.pkgName)
            try {
                val request = Request.Builder()
                    .url(extension.apkUrl)
                    .header("User-Agent", "nekoplayer-android")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Download returned HTTP ${response.code}")
                    val body = response.body ?: error("The extension download was empty.")
                    staging.outputStream().use { output -> body.byteStream().use { it.copyTo(output) } }
                }
                verify(staging, extension.pkgName)
                if (target.exists() && !target.delete()) error("Could not replace the installed extension.")
                if (!staging.renameTo(target)) {
                    staging.copyTo(target, overwrite = true)
                    staging.delete()
                }
                if (!target.setReadOnly()) error("Could not protect the installed extension file.")
            } finally {
                staging.delete()
            }
        }

    fun remove(pkgName: String): Boolean {
        val target = ExtensionLoader.privateFile(context, pkgName)
        return !target.exists() || target.delete()
    }

    private fun verify(file: File, expectedPackage: String) {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.GET_CONFIGURATIONS or PackageManager.GET_META_DATA,
        ) ?: error("Downloaded file is not a valid APK.")
        require(info.packageName == expectedPackage) { "The APK package does not match the repository entry." }
        require(info.reqFeatures.orEmpty().any { it.name == "tachiyomi.animeextension" }) {
            "The APK is not an Aniyomi video-source extension."
        }
        require(expectedPackage.contains(".animeextension.")) {
            "Only anime/video extensions can be installed."
        }
    }
}
