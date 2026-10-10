// Adapted from Watchbox-Android (GPL-3.0), https://github.com/Nicartjay/Watchbox-Android.
// Modified for nekoplayer; see the repository LICENSE.

package com.nekoplayer.app.extensions.model

import android.graphics.drawable.Drawable
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource

object ExtensionApiVersion {
    const val MIN_SUPPORTED = 12.0
    const val MAX_SUPPORTED = 16.0

    /** Extension versions use the API major as the first component, e.g. 16.15 targets API 16. */
    fun parse(versionName: String): Double? =
        versionName.trim().substringBefore('.').toDoubleOrNull()

    fun isSupported(version: Double): Boolean = version in MIN_SUPPORTED..MAX_SUPPORTED
}

sealed interface Extension {
    val name: String
    val pkgName: String
    val versionName: String
    val versionCode: Long
    val libVersion: Double
    val lang: String
    val isNsfw: Boolean

    data class Installed(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val libVersion: Double,
        override val lang: String,
        override val isNsfw: Boolean,
        val sources: List<AnimeSource>,
        val icon: Drawable? = null,
        val hasUpdate: Boolean = false,
    ) : Extension {
        fun hasConfigurableSources(): Boolean = sources.any { it is ConfigurableAnimeSource }
    }

    data class Available(
        override val name: String,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Long,
        override val libVersion: Double,
        override val lang: String,
        override val isNsfw: Boolean,
        val apkName: String,
        val iconUrl: String,
        val apkUrl: String,
        val sources: List<AvailableSource> = emptyList(),
        val repoUrl: String = "",
        val isSupported: Boolean = ExtensionApiVersion.isSupported(libVersion),
    ) : Extension
}

data class AvailableSource(
    val id: Long,
    val name: String,
    val lang: String,
    val baseUrl: String,
)

sealed interface LoadResult {
    data class Success(val extension: Extension.Installed) : LoadResult
    data class Error(val pkgName: String, val reason: String) : LoadResult
}
