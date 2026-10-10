// Adapted from Watchbox-Android (GPL-3.0), https://github.com/Nicartjay/Watchbox-Android.
// Modified for nekoplayer; see the repository LICENSE.

package eu.kanade.tachiyomi.network

import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.FormBody

/**
 * Request builders extensions call directly.
 *
 * These are top-level functions, which the Kotlin compiler exposes as static
 * members of `RequestsKt`. That class name is part of the ABI — the surveyed
 * extensions reference `RequestsKt.GET(...)` — so this file must stay named
 * `Requests.kt`.
 *
 * See the note in [eu.kanade.tachiyomi.animesource.model.SAnime] for why this
 * package reproduces the Aniyomi ABI.
 */

private val DEFAULT_CACHE_CONTROL = CacheControl.Builder().maxAge(10, java.util.concurrent.TimeUnit.MINUTES).build()
private val DEFAULT_HEADERS = Headers.Builder().build()
private val DEFAULT_BODY: RequestBody = FormBody.Builder().build()

fun GET(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Request = Request.Builder()
    .url(url)
    .headers(headers)
    .cacheControl(cache)
    .build()

fun GET(
    url: HttpUrl,
    headers: Headers = DEFAULT_HEADERS,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Request = Request.Builder()
    .url(url)
    .headers(headers)
    .cacheControl(cache)
    .build()

fun POST(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = DEFAULT_BODY,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Request = Request.Builder()
    .url(url)
    .post(body)
    .headers(headers)
    .cacheControl(cache)
    .build()

fun PUT(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = DEFAULT_BODY,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Request = Request.Builder()
    .url(url)
    .put(body)
    .headers(headers)
    .cacheControl(cache)
    .build()

fun DELETE(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = DEFAULT_BODY,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Request = Request.Builder()
    .url(url)
    .delete(body)
    .headers(headers)
    .cacheControl(cache)
    .build()

/**
 * Executes request builders through the host's shared source client.
 *
 * Extensions compiled against extensions-lib 16 call these suspend helpers
 * directly, so their JVM signatures are part of the runtime ABI.
 */
suspend fun OkHttpClient.get(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Response = newCall(GET(url, headers, cache)).awaitSuccess()

suspend fun OkHttpClient.get(
    url: HttpUrl,
    headers: Headers = DEFAULT_HEADERS,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Response = newCall(GET(url, headers, cache)).awaitSuccess()

suspend fun OkHttpClient.post(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = DEFAULT_BODY,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Response = newCall(POST(url, headers, body, cache)).awaitSuccess()

/** Convenience used by some extensions when posting JSON. */
val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()
