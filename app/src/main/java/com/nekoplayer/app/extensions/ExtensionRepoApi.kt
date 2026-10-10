package com.nekoplayer.app.extensions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import com.nekoplayer.app.extensions.model.AvailableSource
import com.nekoplayer.app.extensions.model.Extension
import com.nekoplayer.app.extensions.model.ExtensionApiVersion

class ExtensionRepoApi(private val client: OkHttpClient) {
    suspend fun fetchIndex(repoUrl: String): Result<List<Extension.Available>> = withContext(Dispatchers.IO) {
        runCatching {
            val base = normaliseBase(repoUrl)
            val request = Request.Builder()
                .url("$base/index.min.json")
                .header("Accept", "application/json")
                .header("User-Agent", "nekoplayer-android")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Repository returned HTTP ${response.code}")
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) error("Repository index is empty.")
                val entries = JSONArray(body)
                buildList {
                    for (i in 0 until entries.length()) {
                        val item = entries.optJSONObject(i) ?: continue
                        val pkg = item.optString("pkg")
                        val apk = item.optString("apk")
                        val versionName = item.optString("version")
                        val api = ExtensionApiVersion.parse(versionName) ?: continue
                        if (!pkg.contains(".animeextension.") || apk.isBlank()) continue
                        val sourcesJson = item.optJSONArray("sources") ?: JSONArray()
                        val sources = buildList {
                            for (sourceIndex in 0 until sourcesJson.length()) {
                                val source = sourcesJson.optJSONObject(sourceIndex) ?: continue
                                add(
                                    AvailableSource(
                                        id = source.optLong("id"),
                                        name = source.optString("name"),
                                        lang = source.optString("lang"),
                                        baseUrl = source.optString("baseUrl"),
                                    ),
                                )
                            }
                        }
                        add(
                            Extension.Available(
                                name = item.optString("name").removePrefix("Aniyomi: "),
                                pkgName = pkg,
                                versionName = versionName,
                                versionCode = item.optLong("code"),
                                libVersion = api,
                                lang = item.optString("lang"),
                                isNsfw = item.optInt("nsfw") == 1,
                                apkName = apk,
                                iconUrl = "$base/icon/$pkg.png",
                                apkUrl = "$base/apk/$apk",
                                sources = sources,
                                repoUrl = base,
                                isSupported = ExtensionApiVersion.isSupported(api),
                            ),
                        )
                    }
                }.sortedBy { it.name.lowercase() }
            }
        }
    }

    fun normaliseBase(repoUrl: String): String {
        val trimmed = repoUrl.trim()
            .removeSuffix("/")
            .removeSuffix("/index.min.json")
            .removeSuffix("/index.json")
            .removeSuffix("/")
        require(trimmed.startsWith("https://")) { "Use an HTTPS repository URL." }
        require(trimmed.length > "https://".length) { "Enter a repository URL." }
        return trimmed
    }
}
