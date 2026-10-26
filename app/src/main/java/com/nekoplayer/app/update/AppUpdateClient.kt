package com.nekoplayer.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateRelease(
    val versionTag: String,
    val releaseNotes: String?,
    val apkUrl: String?,
)

class AppUpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal fun formatReleaseNotes(markdown: String, versionTag: String? = null): String {
    val versionNumber = versionTag?.trim()?.removePrefix("v")
    val lines = mutableListOf<String>()

    markdown.replace("\r\n", "\n").lines().forEach { rawLine ->
        var line = rawLine.trim()
        if (line.startsWith("```") || line.matches(Regex("(?:---+|\\*\\*\\*+|___+)"))) return@forEach
        if (line.isEmpty()) {
            if (lines.isNotEmpty() && lines.last().isNotEmpty()) lines += ""
            return@forEach
        }

        val isHeading = line.startsWith('#')
        if (isHeading) line = line.dropWhile { it == '#' }.trim()
        val normalizedHeading = line.removePrefix("v").lowercase()
        if (isHeading && (
                normalizedHeading == "changes" ||
                    (versionNumber != null && normalizedHeading == "${versionNumber.lowercase()} changes")
                )
        ) return@forEach

        line = when {
            line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") -> "• ${line.drop(2)}"
            Regex("^\\d+[.)]\\s+").containsMatchIn(line) -> "• " + line.replaceFirst(Regex("^\\d+[.)]\\s+"), "")
            line.startsWith("> ") -> line.drop(2)
            else -> line
        }
        line = Regex("!?\\[([^]]+)]\\([^)]*\\)").replace(line, "$1")
        line = Regex("(\\*\\*|__)(.+?)\\1").replace(line, "$2")
        line = Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)").replace(line, "$1")
        line = Regex("(?<!\\w)_(.+?)_(?!\\w)").replace(line, "$1")
        line = Regex("~~(.+?)~~").replace(line, "$1")
        line = Regex("`([^`]+)`").replace(line, "$1")
        lines += line
    }

    return lines.joinToString("\n").trim()
}

class AppUpdateClient(context: Context) {
    private val appContext = context.applicationContext

    suspend fun latestRelease(): AppUpdateRelease = withContext(Dispatchers.IO) {
        val connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "nekoplayer-android")
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw AppUpdateException("Couldn’t check for updates. Check your connection and try again.")
            }
            val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val versionTag = release.optString("tag_name").takeIf(String::isNotBlank)
                ?: throw AppUpdateException("Couldn’t read the latest release information.")
            val assets = release.optJSONArray("assets")
            val apkUrl = (0 until (assets?.length() ?: 0))
                .asSequence()
                .mapNotNull { index ->
                    assets?.optJSONObject(index)?.let { asset ->
                        val name = asset.optString("name")
                        val url = asset.optString("browser_download_url")
                        if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) url else null
                    }
                }
                .firstOrNull()

            AppUpdateRelease(
                versionTag = versionTag,
                releaseNotes = release.optString("body").takeIf(String::isNotBlank),
                apkUrl = apkUrl,
            )
        } catch (error: AppUpdateException) {
            throw error
        } catch (error: Exception) {
            throw AppUpdateException(
                "Couldn’t check for updates. Check your connection and try again.",
                error,
            )
        } finally {
            connection.disconnect()
        }
    }

    suspend fun downloadApk(
        downloadUrl: String,
        onDownloadProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        if (!downloadUrl.startsWith("https://", ignoreCase = true)) {
            throw AppUpdateException("Couldn’t download the update. Check your connection and try again.")
        }

        val updateDirectory = File(appContext.filesDir, "updates")
        if (!updateDirectory.exists() && !updateDirectory.mkdirs()) {
            throw AppUpdateException("Couldn’t download the update. Check your connection and try again.")
        }
        val partialFile = File(updateDirectory, "nekoplayer-update.apk.part")
        val apkFile = File(updateDirectory, "nekoplayer-update.apk")
        val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "nekoplayer-android")
        }

        try {
            if (connection.responseCode !in 200..299) {
                throw AppUpdateException("Couldn’t download the update. Check your connection and try again.")
            }

            val expectedLength = connection.contentLength.toLong()
            if (expectedLength > MAX_APK_SIZE_BYTES) {
                throw AppUpdateException("Couldn’t download the update. Check your connection and try again.")
            }

            var downloadedBytes = 0L
            var lastProgressUpdate = 0L
            connection.inputStream.buffered().use { input ->
                partialFile.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        downloadedBytes += count
                        if (downloadedBytes > MAX_APK_SIZE_BYTES) {
                            throw AppUpdateException("Couldn’t download the update. Check your connection and try again.")
                        }
                        output.write(buffer, 0, count)

                        val now = SystemClock.elapsedRealtime()
                        if (expectedLength > 0 && now - lastProgressUpdate >= PROGRESS_UPDATE_INTERVAL_MS) {
                            lastProgressUpdate = now
                            withContext(Dispatchers.Main) {
                                onDownloadProgress((downloadedBytes.toFloat() / expectedLength).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            if (expectedLength > 0 && downloadedBytes != expectedLength) {
                throw AppUpdateException("Couldn’t download the update. Check your connection and try again.")
            }
            withContext(Dispatchers.Main) { onDownloadProgress(1f) }

            val downloadedInfo = appContext.packageManager.getPackageArchiveInfo(partialFile.absolutePath, 0)
                ?: throw AppUpdateException("The downloaded APK is not compatible with this app.")
            if (downloadedInfo.packageName != appContext.packageName) {
                throw AppUpdateException("The downloaded APK is not compatible with this app.")
            }

            val installedInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            if (downloadedInfo.compatibleLongVersionCode() <= installedInfo.compatibleLongVersionCode()) {
                throw AppUpdateException("The downloaded APK is not newer than the installed version.")
            }

            if (apkFile.exists() && !apkFile.delete()) {
                throw AppUpdateException("Couldn’t download the update. Check your connection and try again.")
            }
            if (!partialFile.renameTo(apkFile)) {
                partialFile.copyTo(apkFile, overwrite = true)
                partialFile.delete()
            }
            apkFile
        } catch (error: AppUpdateException) {
            partialFile.delete()
            throw error
        } catch (error: Exception) {
            partialFile.delete()
            throw AppUpdateException(
                "Couldn’t download the update. Check your connection and try again.",
                error,
            )
        } finally {
            connection.disconnect()
        }
    }

    fun canInstallUnknownApps(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || appContext.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent =
        Intent(
            "android.settings.MANAGE_UNKNOWN_APP_SOURCES",
            "package:${appContext.packageName}".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun install(apkFile: File) {
        if (!apkFile.isFile) {
            throw AppUpdateException("Couldn’t start the installer. Try again.")
        }
        try {
            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.nekoplayer_media",
                apkFile,
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(installIntent)
        } catch (error: Exception) {
            throw AppUpdateException("Couldn’t start the installer. Try again.", error)
        }
    }

    companion object {
        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/mira380/nekoplayer/releases/latest"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val MAX_APK_SIZE_BYTES = 512L * 1024L * 1024L
        private const val PROGRESS_UPDATE_INTERVAL_MS = 250L

        fun isNewerVersion(latestTag: String, installedVersion: String): Boolean {
            val latest = versionParts(latestTag) ?: return false
            val installed = versionParts(installedVersion) ?: return false
            for (index in 0 until maxOf(latest.size, installed.size)) {
                val latestPart = latest.getOrElse(index) { 0L }
                val installedPart = installed.getOrElse(index) { 0L }
                if (latestPart != installedPart) return latestPart > installedPart
            }
            return false
        }

        private fun versionParts(version: String): List<Long>? {
            val normalized = version.trim().removePrefix("v").substringBefore('-').substringBefore('+')
            val pieces = normalized.split('.')
            if (pieces.isEmpty() || pieces.any { it.isEmpty() || it.any { character -> !character.isDigit() } }) {
                return null
            }
            return pieces.map { it.toLongOrNull() ?: return null }
        }

        private fun PackageInfo.compatibleLongVersionCode(): Long =
            PackageInfoCompat.getLongVersionCode(this)
    }
}
