package com.nekoplayer.app.extensions

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import com.nekoplayer.app.data.AppDatabase
import com.nekoplayer.app.extensions.loader.ExtensionLoader
import com.nekoplayer.app.extensions.model.Extension
import com.nekoplayer.app.extensions.model.LoadResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray

class ExtensionManager(context: Context, private val database: AppDatabase) {
    private val appContext = context.applicationContext
    private val network by lazy { installExtensionInjekt(appContext as Application) }
    private val repoApi by lazy { ExtensionRepoApi(network.client) }
    private val installer by lazy { ExtensionInstaller(appContext, network.client) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private var started = false

    private val _installed = MutableStateFlow<List<Extension.Installed>>(emptyList())
    val installed = _installed.asStateFlow()
    private val _available = MutableStateFlow<List<Extension.Available>>(emptyList())
    val available = _available.asStateFlow()
    private val _repoUrls = MutableStateFlow<List<String>>(emptyList())
    val repoUrls = _repoUrls.asStateFlow()
    private val _loadErrors = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val loadErrors = _loadErrors.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading = _loading.asStateFlow()

    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            val stored = withContext(Dispatchers.IO) {
                (database.readSetting(REPOS_KEY) as? JSONArray)?.let { array ->
                    buildList {
                        for (i in 0 until array.length()) {
                            array.optString(i).takeIf(String::isNotBlank)?.let(::add)
                        }
                    }
                }.orEmpty()
            }
            _repoUrls.value = stored.distinct()
            refresh()
        }
    }

    suspend fun refresh() = refreshMutex.withLock {
        _loading.value = true
        _error.value = null
        try {
            // Extension constructors resolve NetworkHelper, Application, and Json from Injekt.
            // Register the host graph before any extension class is initialized.
            withContext(Dispatchers.IO) { network }

            val (loadResults, availableResults) = coroutineScope {
                val loadResultsDeferred = async(Dispatchers.IO) {
                    ExtensionLoader.loadExtensions(appContext)
                }
                val availableResultsDeferred = async {
                    _repoUrls.value.map { url ->
                        async { url to repoApi.fetchIndex(url) }
                    }.awaitAll()
                }

                val loadResults = loadResultsDeferred.await()
                val loaded = loadResults.filterIsInstance<LoadResult.Success>().map { it.extension }
                _loadErrors.value = loadResults.filterIsInstance<LoadResult.Error>()
                    .map { it.pkgName to it.reason }

                // Publish local sources as soon as they are ready instead of waiting for remote indexes.
                val cachedAvailableByPackage = _available.value.associateBy { it.pkgName }
                _installed.value = loaded.map { installed ->
                    val remote = cachedAvailableByPackage[installed.pkgName]
                    installed.copy(
                        hasUpdate = remote != null &&
                            (remote.versionCode > installed.versionCode || remote.libVersion > installed.libVersion),
                    )
                }.sortedBy { it.name.lowercase() }

                loadResults to availableResultsDeferred.await()
            }
            val loaded = loadResults.filterIsInstance<LoadResult.Success>().map { it.extension }

            val successes = availableResults.mapNotNull { (url, result) ->
                result.getOrNull()?.map { it.copy(repoUrl = url) }
            }
            val failures = availableResults.mapNotNull { (url, result) ->
                result.exceptionOrNull()?.let { url to (it.message ?: "Could not load this repository.") }
            }

            val availableByPackage = successes.flatten()
                .groupBy { it.pkgName }
                .mapValues { (_, entries) -> entries.maxBy { it.versionCode } }

            _installed.value = loaded.map { installed ->
                val remote = availableByPackage[installed.pkgName]
                installed.copy(
                    hasUpdate = remote != null &&
                        (remote.versionCode > installed.versionCode || remote.libVersion > installed.libVersion),
                )
            }.sortedBy { it.name.lowercase() }
            _available.value = availableByPackage.values.sortedBy { it.name.lowercase() }
            if (failures.isNotEmpty()) {
                _error.value = failures.joinToString(" ") { (url, message) ->
                    "${url.substringAfter("://").substringBefore('/')} — $message"
                }
            }
        } catch (_: Exception) {
            _error.value = "Couldn’t refresh video extensions. Check your connection and try again."
        } finally {
            _loading.value = false
        }
    }

    suspend fun addRepository(input: String): Result<Unit> {
        val url = runCatching { repoApi.normaliseBase(input) }.getOrElse { return Result.failure(it) }
        if (url in _repoUrls.value) return Result.failure(IllegalArgumentException("That repository is already added."))
        val index = repoApi.fetchIndex(url)
        if (index.isFailure) return Result.failure(index.exceptionOrNull()!!)
        _repoUrls.value = _repoUrls.value + url
        persistRepositories()
        refresh()
        return Result.success(Unit)
    }

    suspend fun removeRepository(url: String) {
        _repoUrls.value = _repoUrls.value - url
        persistRepositories()
        refresh()
    }

    suspend fun install(extension: Extension.Available): Result<Unit> {
        _error.value = null
        val result = withContext(Dispatchers.IO) { installer.install(extension) }
        result.onSuccess { refresh() }
        result.onFailure { _error.value = it.message ?: "Couldn’t install this extension." }
        return result
    }

    suspend fun updateAll(): Result<Unit> {
        val updates = _installed.value.mapNotNull { installed ->
            _available.value.firstOrNull { it.pkgName == installed.pkgName }
                ?.takeIf { installed.hasUpdate && it.isSupported }
        }
        for (extension in updates) {
            val result = withContext(Dispatchers.IO) { installer.install(extension) }
            if (result.isFailure) {
                _error.value = result.exceptionOrNull()?.message ?: "Couldn’t update ${extension.name}."
                refresh()
                return result
            }
        }
        refresh()
        return Result.success(Unit)
    }

    suspend fun remove(pkgName: String): Boolean {
        val removed = withContext(Dispatchers.IO) { installer.remove(pkgName) }
        if (removed) refresh()
        else _error.value = "Couldn’t remove this extension."
        return removed
    }

    fun catalogueSources(): List<InstalledVideoSource> = _installed.value
        .flatMap { extension ->
            extension.sources.filterIsInstance<AnimeCatalogueSource>().map { source ->
                InstalledVideoSource(extension, source)
            }
        }
        .sortedBy { it.source.name.lowercase() }

    fun sourceById(id: Long): AnimeCatalogueSource? =
        catalogueSources().firstOrNull { it.source.id == id }?.source

    private suspend fun persistRepositories() = withContext(Dispatchers.IO) {
        database.writeSetting(REPOS_KEY, JSONArray(_repoUrls.value))
    }

    data class InstalledVideoSource(
        val extension: Extension.Installed,
        val source: AnimeCatalogueSource,
    )

    companion object {
        private const val REPOS_KEY = "extensions.repositories"
    }
}
