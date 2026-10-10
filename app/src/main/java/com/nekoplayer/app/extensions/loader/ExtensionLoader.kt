// Private APK loader adapted from Watchbox-Android (GPL-3.0).
// Modified for nekoplayer; it only loads APKs the user installed through this app.

package com.nekoplayer.app.extensions.loader

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.nekoplayer.app.extensions.model.Extension
import com.nekoplayer.app.extensions.model.ExtensionApiVersion
import com.nekoplayer.app.extensions.model.LoadResult
import java.io.File

object ExtensionLoader {
    private const val EXTENSION_FEATURE = "tachiyomi.animeextension"
    private const val METADATA_SOURCE_CLASS = "tachiyomi.animeextension.class"
    private const val METADATA_NSFW = "tachiyomi.animeextension.nsfw"
    private const val PRIVATE_DIR = "extensions"
    private const val APK_SUFFIX = ".apk"

    private val packageFlags: Int
        @Suppress("DEPRECATION")
        get() = PackageManager.GET_CONFIGURATIONS or PackageManager.GET_META_DATA or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }

    fun privateDir(context: Context): File =
        File(context.filesDir, PRIVATE_DIR).apply { mkdirs() }

    fun privateFile(context: Context, pkgName: String): File {
        require(pkgName.matches(Regex("[A-Za-z0-9_.]+"))) { "Invalid extension package name" }
        return File(privateDir(context), "$pkgName$APK_SUFFIX")
    }

    /** Loads only verified APKs stored in the app-private extension directory. */
    suspend fun loadExtensions(context: Context): List<LoadResult> = withContext(Dispatchers.IO) {
        privateDir(context).listFiles { file -> file.isFile && file.name.endsWith(APK_SUFFIX) }
            .orEmpty()
            .map { file -> loadExtension(context, file) }
    }

    private fun loadExtension(context: Context, file: File): LoadResult {
        val info = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.absolutePath, packageFlags)
        }.getOrNull() ?: return LoadResult.Error(file.nameWithoutExtension, "The extension APK is invalid.")

        if (!isAnimeExtension(info)) {
            return LoadResult.Error(info.packageName.orEmpty(), "This APK is not a video-source extension.")
        }
        val pkgName = info.packageName ?: return LoadResult.Error(file.nameWithoutExtension, "Missing package name.")
        if (!pkgName.startsWith("eu.kanade.tachiyomi.animeextension.")) {
            return LoadResult.Error(pkgName, "Only Aniyomi video-source extensions can be loaded.")
        }

        val appInfo = info.applicationInfo
            ?: return LoadResult.Error(pkgName, "Missing extension metadata.")
        appInfo.sourceDir = file.absolutePath
        appInfo.publicSourceDir = file.absolutePath

        val versionName = info.versionName.orEmpty()
        val libVersion = ExtensionApiVersion.parse(versionName)
            ?: return LoadResult.Error(pkgName, "The extension API version is invalid.")
        if (!ExtensionApiVersion.isSupported(libVersion)) {
            return LoadResult.Error(
                pkgName,
                "Extension API ${libVersion.toInt()} is not supported (12–16 are supported).",
            )
        }

        val declaredClasses = appInfo.metaData?.getString(METADATA_SOURCE_CLASS)
            ?.split(';')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.map { if (it.startsWith('.')) pkgName + it else it }
            .orEmpty()
        if (declaredClasses.isEmpty()) {
            return LoadResult.Error(pkgName, "No source class is declared.")
        }

        val classLoader = runCatching {
            ChildFirstClassLoader(file.absolutePath, appInfo.nativeLibraryDir, context.classLoader)
        }.getOrElse {
            return LoadResult.Error(pkgName, "Could not open the extension.")
        }

        val sources = mutableListOf<AnimeSource>()
        for (className in declaredClasses) {
            val instance = runCatching {
                Class.forName(className, true, classLoader).getDeclaredConstructor().newInstance()
            }.getOrElse { error ->
                return LoadResult.Error(pkgName, "Could not load the source: ${error.rootMessage()}")
            }

            when (instance) {
                is AnimeSource -> sources += instance
                is AnimeSourceFactory -> {
                    val created = runCatching { instance.createSources() }.getOrElse { error ->
                        return LoadResult.Error(pkgName, "Could not create video sources: ${error.rootMessage()}")
                    }
                    sources += created
                }
                else -> return LoadResult.Error(pkgName, "The extension declares an unsupported source type.")
            }
        }

        val videoSources = sources.filterIsInstance<AnimeCatalogueSource>()
        if (videoSources.isEmpty()) {
            return LoadResult.Error(pkgName, "This extension does not provide browsable video sources.")
        }

        val langs = videoSources.map { it.lang }.distinct()
        val label = runCatching { context.packageManager.getApplicationLabel(appInfo).toString() }
            .getOrDefault(pkgName).removePrefix("Aniyomi: ")

        return LoadResult.Success(
            Extension.Installed(
                name = label,
                pkgName = pkgName,
                versionName = versionName,
                versionCode = info.versionCodeCompat(),
                libVersion = libVersion,
                lang = langs.singleOrNull().orEmpty().ifBlank { if (langs.size > 1) "all" else "" },
                isNsfw = appInfo.metaData?.getInt(METADATA_NSFW) == 1,
                sources = videoSources,
                icon = runCatching { context.packageManager.getApplicationIcon(appInfo) }.getOrNull(),
            ),
        )
    }

    private fun isAnimeExtension(info: PackageInfo): Boolean =
        info.reqFeatures.orEmpty().any { it.name == EXTENSION_FEATURE }

    @Suppress("DEPRECATION")
    private fun PackageInfo.versionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

    private fun Throwable.rootMessage(): String {
        var current = this
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current.message ?: current.javaClass.simpleName
    }
}
