package com.nekoplayer.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.filled.VideoLibrary
import okhttp3.Request
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text as Material3Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.core.net.toUri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nekoplayer.app.data.AppDatabase
import com.nekoplayer.app.extensions.ExtensionManager
import com.nekoplayer.app.library.AniListAiringEpisode
import com.nekoplayer.app.library.AniListClient
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.TimeStamp
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun VideoSourceBrowser(
    item: ExtensionManager.InstalledVideoSource,
    database: AppDatabase,
    aniList: AniListClient,
    initialAnime: SAnime?,
    onBack: () -> Unit,
    onPlayVideo: (
        android.net.Uri,
        String,
        String?,
        Map<String, String>,
        String?,
        Int?,
        AppDatabase.SourceProgressIdentity?,
        String?,
        NextEpisodeAction?,
        List<TimeStamp>,
    ) -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = item.source
    val scope = rememberCoroutineScope()
    val catalogueGridState = rememberLazyGridState()
    var query by remember(source.id) { mutableStateOf("") }
    var filterList by remember(source.id) { mutableStateOf(source.getFilterList()) }
    var showFilters by remember { mutableStateOf(value = false) }
    var mode by remember(source.id) { mutableIntStateOf(0) }
    var results by remember(source.id) { mutableStateOf<List<SAnime>>(emptyList()) }
    var page by remember(source.id) { mutableIntStateOf(0) }
    var hasNextPage by remember(source.id) { mutableStateOf(false) }
    var selectedAnime by remember(source.id) { mutableStateOf<SAnime?>(null) }
    var selectedAnimeArtworkUrl by remember(source.id) { mutableStateOf<String?>(null) }
    var selectedAnimeUrl by remember(source.id) { mutableStateOf<String?>(null) }
    var sourceProgressAnimeUrl by remember(source.id, initialAnime?.url) {
        mutableStateOf(initialAnime?.url)
    }
    var sourceProgressAnimeTitle by remember(source.id, initialAnime?.url) {
        mutableStateOf(initialAnime?.title)
    }
    var savedSourceProgress by remember(source.id, initialAnime?.url) {
        mutableStateOf<AppDatabase.SourceEpisodeProgress?>(null)
    }
    var pendingContinueProgress by remember(source.id, initialAnime?.url) {
        mutableStateOf<AppDatabase.SourceEpisodeProgress?>(null)
    }
    var details by remember(source.id) { mutableStateOf<SAnime?>(null) }
    var nextAiringEpisode by remember(source.id) {
        mutableStateOf<AniListAiringEpisode?>(null)
    }
    var seasons by remember(source.id) { mutableStateOf<List<SAnime>>(emptyList()) }
    var episodes by remember(source.id) { mutableStateOf<List<SEpisode>>(emptyList()) }
    var episodeQuery by remember(source.id) { mutableStateOf("") }
    var episodeSortOrder by remember(source.id) { mutableStateOf(EpisodeSortOrder.NumberAscending) }
    var showEpisodeSearch by remember(source.id) { mutableStateOf(false) }
    var showEpisodeSortMenu by remember(source.id) { mutableStateOf(false) }
    var selectedEpisode by remember(source.id) { mutableStateOf<SEpisode?>(null) }
    var streamChoices by remember(source.id) { mutableStateOf<List<StreamChoice>>(emptyList()) }
    var busy by remember(source.id) { mutableStateOf(false) }
    var error by remember(source.id) { mutableStateOf<String?>(null) }
    val visibleEpisodes = remember(episodes, episodeQuery, episodeSortOrder) {
        val normalizedQuery = episodeQuery.trim()
        val matchingEpisodes = episodes.filter { episode ->
            normalizedQuery.isEmpty() ||
                episode.name.contains(normalizedQuery, ignoreCase = true) ||
                episode.sortingNumber()?.toString()?.removeSuffix(".0")
                    ?.contains(normalizedQuery, ignoreCase = true) == true
        }
        when (episodeSortOrder) {
            EpisodeSortOrder.NumberAscending -> matchingEpisodes.sortedWith(
                compareBy<SEpisode> { it.sortingNumber() == null }
                    .thenBy { it.sortingNumber() ?: Float.MAX_VALUE },
            )
            EpisodeSortOrder.NumberDescending -> matchingEpisodes.sortedWith(
                compareBy<SEpisode> { it.sortingNumber() == null }
                    .thenByDescending { it.sortingNumber() ?: Float.MIN_VALUE },
            )
        }
    }

    LaunchedEffect(aniList, details?.url, details?.title) {
        val title = details?.title?.takeIf(String::isNotBlank)
        nextAiringEpisode = if (title == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                aniList.findNextAiringEpisode(title, episodes.take(2).map(SEpisode::name))
            }
        }
    }

    fun loadPage(reset: Boolean) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            try {
                val next = if (reset) 1 else page + 1
                val (animes, hasMore) = withContext(Dispatchers.IO) {
                    when {
                        query.isNotBlank() -> source.getSearchAnime(next, query, filterList)
                        mode == 1 && source.supportsLatest -> source.getLatestUpdates(next)
                        else -> source.getPopularAnime(next)
                    }
                }
                results = if (reset) animes else results + animes
                page = next
                hasNextPage = hasMore
            } catch (linkage: LinkageError) {
                error = linkage.toExtensionRuntimeMessage(item.extension.libVersion)
            } catch (_: Exception) {
                error = "Couldn’t load ${source.name}. Check your connection and try again."
            } finally {
                busy = false
            }
        }
    }

    fun openAnime(anime: SAnime, loadCatalogueAfter: Boolean = false) {
        if (selectedAnime == null) {
            sourceProgressAnimeUrl = anime.url
            sourceProgressAnimeTitle = anime.title
        }
        selectedAnime = anime
        selectedAnimeArtworkUrl = anime.thumbnail_url
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        selectedAnimeUrl = anime.url
        details = null
        seasons = emptyList()
        episodes = emptyList()
        episodeQuery = ""
        episodeSortOrder = EpisodeSortOrder.NumberAscending
        showEpisodeSearch = false
        showEpisodeSortMenu = false
        selectedEpisode = null
        streamChoices = emptyList()
        scope.launch {
            busy = true
            error = null
            try {
                val (detail, seasonList, episodeList) = withContext(Dispatchers.IO) {
                    val detail = if (anime.initialized) anime else source.getAnimeDetails(anime)
                    val seasonList = if (item.extension.libVersion >= 16.0) {
                        runCatching { source.getSeasonList(detail) }.getOrDefault(emptyList())
                    } else {
                        emptyList()
                    }
                    val episodeList = if (detail.fetch_type == FetchType.Seasons) {
                        emptyList()
                    } else {
                        source.getEpisodeList(detail)
                    }
                    if (episodeList.isNotEmpty()) {
                        runCatching {
                            database.listSourceLibraryEntries()
                                .firstOrNull { it.sourceId == source.id && it.animeUrl == anime.url }
                                ?.let { database.saveSourceLibraryEntry(it.copy(episodeCount = episodeList.size)) }
                        }
                    }
                    Triple(detail, seasonList, episodeList)
                }
                details = detail
                seasons = seasonList
                episodes = episodeList
            } catch (linkage: LinkageError) {
                error = linkage.toExtensionRuntimeMessage(item.extension.libVersion)
            } catch (_: Exception) {
                error = "Couldn’t load episodes for ${anime.title}."
            } finally {
                busy = false
                if (loadCatalogueAfter && results.isEmpty()) loadPage(reset = true)
            }
        }
    }

    fun playVideoChoice(
        choice: StreamChoice,
        episode: SEpisode,
        onNextEpisode: NextEpisodeAction? = null,
        onAutoPlayFinished: ((Boolean) -> Unit)? = null,
    ): Boolean {
        val url = choice.video.videoUrl
        if (url.isBlank()) {
            error = "This source did not resolve a direct video stream."
            onAutoPlayFinished?.invoke(false)
            return false
        }
        val sourceHeaders = choice.video.headers
        val headers = buildMap {
            sourceHeaders?.let { sourceHeaders ->
                for (headerIndex in 0 until sourceHeaders.size) {
                    put(sourceHeaders.name(headerIndex), sourceHeaders.value(headerIndex))
                }
            }
        }
        val episodeNumber = episode.episode_number
            .takeIf { it.isFinite() && it >= 1f && it % 1f == 0f }
            ?.toInt()
        val detailAnimeUrl = selectedAnimeUrl ?: selectedAnime?.url.orEmpty()
        val progressIdentity = sourceProgressAnimeUrl
            ?.takeIf(String::isNotBlank)
            ?.let { seriesAnimeUrl ->
                AppDatabase.SourceProgressIdentity(
                    sourceId = source.id,
                    seriesAnimeUrl = seriesAnimeUrl,
                    detailAnimeUrl = detailAnimeUrl.ifBlank { seriesAnimeUrl },
                    episodeUrl = episode.url,
                    episodeName = episode.name,
                    episodeNumber = episodeNumber,
                )
            }
        onPlayVideo(
            url.toUri(),
            choice.label.ifBlank { episode.name },
            selectedAnime?.title ?: sourceProgressAnimeTitle,
            headers,
            episode.name,
            episodeNumber,
            progressIdentity,
            sourceProgressAnimeTitle,
            onNextEpisode,
            choice.video.timestamps,
        )
        onAutoPlayFinished?.invoke(true)
        return true
    }

    fun openEpisode(
        episode: SEpisode,
        autoPlayFirst: Boolean = false,
        preferredStream: StreamIdentity? = null,
        onAutoPlayFinished: ((Boolean) -> Unit)? = null,
    ) {
        selectedEpisode = episode
        streamChoices = emptyList()
        scope.launch {
            busy = true
            error = null
            try {
                val choices = withContext(Dispatchers.IO) {
                    val httpSource = source as? AnimeHttpSource
                    suspend fun directVideoHoster() = listOf(
                        Hoster(
                            hosterName = Hoster.NO_HOSTER_LIST,
                            videoList = source.getVideoList(episode),
                        ),
                    )
                    val hosters = if (item.extension.libVersion >= 16.0) {
                        try {
                            source.getHosterList(episode)
                        } catch (_: UnsupportedOperationException) {
                            directVideoHoster()
                        }
                    } else {
                        directVideoHoster()
                    }
                    val sortedHosters = httpSource?.run { hosters.sortHosters() } ?: hosters
                    val resolvedChoices = mutableListOf<StreamChoice>()
                    hosterLoop@ for (hoster in sortedHosters) {
                        val hosterLabel = hoster.hosterName
                            .takeUnless { it == Hoster.NO_HOSTER_LIST }
                            .orEmpty()
                        val hosterIdentity = normalizedStreamLabel(hosterLabel)
                        if (preferredStream != null && hosterIdentity != preferredStream.hosterName) {
                            continue@hosterLoop
                        }
                        val videos = if (!hoster.lazy && hoster.videoList != null) {
                            hoster.videoList
                        } else {
                            source.getVideoList(hoster)
                        }
                        val sortedVideos = httpSource?.run { videos.sortVideos() } ?: videos
                        val matchingVideos = if (preferredStream == null) {
                            sortedVideos
                        } else {
                            sortedVideos.filter { video ->
                                video.videoTitle.isBlank() ||
                                    streamIdentity(hosterLabel, video.videoTitle) == preferredStream
                            }
                        }
                        if (
                            autoPlayFirst &&
                            preferredStream != null &&
                            preferredStream.videoTitle.isBlank() &&
                            matchingVideos.size > 1
                        ) {
                            continue@hosterLoop
                        }
                        for (video in matchingVideos) {
                            val resolved = if (httpSource != null) {
                                httpSource.resolveVideo(video) ?: continue
                            } else {
                                video
                            }
                            if (
                                preferredStream != null &&
                                streamIdentity(hosterLabel, resolved.videoTitle.ifBlank { video.videoTitle }) != preferredStream
                            ) {
                                continue
                            }
                            if (
                                httpSource != null &&
                                (resolved.videoUrl.isBlank() || resolved.videoUrl == "null")
                            ) {
                                runCatching { httpSource.getVideoUrl(resolved) }
                                    .getOrNull()
                                    ?.takeIf(String::isNotBlank)
                                    ?.let { resolved.videoUrl = it }
                            }
                            if (resolved.videoUrl.isBlank() || resolved.videoUrl == "null") {
                                continue
                            }
                            resolvedChoices += StreamChoice(
                                label = listOf(hosterLabel, resolved.videoTitle)
                                    .asSequence()
                                    .filter(String::isNotBlank)
                                    .joinToString(" · "),
                                video = resolved,
                                identity = streamIdentity(
                                    hosterLabel,
                                    resolved.videoTitle.ifBlank { video.videoTitle },
                                ),
                            )
                            if (autoPlayFirst) break@hosterLoop
                        }
                    }
                    resolvedChoices
                }
                streamChoices = choices
                if (autoPlayFirst) {
                    val choice = choices.firstOrNull()
                    if (choice == null) {
                        error = if (preferredStream == null) {
                            "This episode has no playable video streams."
                        } else {
                            "The selected video stream is unavailable for this episode."
                        }
                        onAutoPlayFinished?.invoke(false)
                    } else {
                        val currentIndex = visibleEpisodes.indexOfFirst { it.url == episode.url }
                        val nextEpisode = visibleEpisodes.getOrNull(currentIndex + 1)
                        val nextAction: NextEpisodeAction? = nextEpisode?.let { next ->
                            { onFinished ->
                                openEpisode(
                                    next,
                                    autoPlayFirst = true,
                                    preferredStream = choice.identity,
                                    onAutoPlayFinished = onFinished,
                                )
                            }
                        }
                        playVideoChoice(
                            choice,
                            episode,
                            nextAction,
                            onAutoPlayFinished,
                        )
                    }
                } else if (choices.isEmpty()) {
                    error = "This episode has no playable video streams."
                }
            } catch (linkage: LinkageError) {
                error = linkage.toExtensionRuntimeMessage(item.extension.libVersion)
                onAutoPlayFinished?.invoke(false)
            } catch (_: Exception) {
                error = "Couldn’t load video streams for ${episode.name}."
                onAutoPlayFinished?.invoke(false)
            } finally {
                busy = false
            }
        }
    }

    BackHandler(enabled = true) {
        when {
            selectedEpisode != null -> {
                selectedEpisode = null
                streamChoices = emptyList()
                error = null
            }
            selectedAnime != null && initialAnime != null -> onBack()
            selectedAnime != null -> {
                selectedAnime = null
                selectedAnimeArtworkUrl = null
                selectedAnimeUrl = null
                sourceProgressAnimeUrl = null
                sourceProgressAnimeTitle = null
                savedSourceProgress = null
                pendingContinueProgress = null
                details = null
                episodes = emptyList()
                error = null
            }
            else -> onBack()
        }
    }

    LaunchedEffect(source.id, sourceProgressAnimeUrl) {
        val seriesUrl = sourceProgressAnimeUrl
        savedSourceProgress = if (seriesUrl.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                database.latestUnfinishedSourceProgress(source.id, seriesUrl)
            }
        }
    }

    LaunchedEffect(pendingContinueProgress, selectedAnimeUrl, episodes, busy) {
        val progress = pendingContinueProgress ?: return@LaunchedEffect
        if (progress.identity.detailAnimeUrl != selectedAnimeUrl) return@LaunchedEffect
        val episode = episodes.firstOrNull { it.url == progress.identity.episodeUrl }
            ?: episodes.firstOrNull {
                progress.identity.episodeNumber != null &&
                    it.sortingNumber() == progress.identity.episodeNumber.toFloat()
            }
        if (episode != null) {
            pendingContinueProgress = null
            openEpisode(episode, autoPlayFirst = true)
        } else if (!busy && details != null) {
            pendingContinueProgress = null
            error = "The saved episode is no longer available from this source."
        }
    }

    fun continueSourceProgress(progress: AppDatabase.SourceEpisodeProgress) {
        if (busy) return
        val targetDetailUrl = progress.identity.detailAnimeUrl
        if (targetDetailUrl == selectedAnimeUrl) {
            val episode = episodes.firstOrNull { it.url == progress.identity.episodeUrl }
                ?: episodes.firstOrNull {
                    progress.identity.episodeNumber != null &&
                        it.sortingNumber() == progress.identity.episodeNumber.toFloat()
                }
            if (episode == null) {
                error = "The saved episode is no longer available from this source."
            } else {
                openEpisode(episode, autoPlayFirst = true)
            }
            return
        }

        val season = seasons.firstOrNull { it.url == targetDetailUrl }
        if (season == null) {
            error = "The saved season is no longer available from this source."
        } else {
            pendingContinueProgress = progress
            openAnime(season)
        }
    }

    LaunchedEffect(source.id, initialAnime?.url) {
        if (initialAnime != null) openAnime(initialAnime, loadCatalogueAfter = true)
        else loadPage(reset = true)
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    when {
                        selectedEpisode != null -> { selectedEpisode = null; streamChoices = emptyList() }
                        selectedAnime != null && initialAnime != null -> onBack()
                        selectedAnime != null -> {
                            selectedAnime = null
                            selectedAnimeArtworkUrl = null
                            selectedAnimeUrl = null
                            sourceProgressAnimeUrl = null
                            sourceProgressAnimeTitle = null
                            savedSourceProgress = null
                            pendingContinueProgress = null
                            details = null
                            episodes = emptyList()
                        }
                        else -> onBack()
                    }
                },
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = localizeUiText("Back"))
            }
            Column(Modifier.weight(1f)) {
                Material3Text(
                    selectedEpisode?.name ?: selectedAnime?.title ?: source.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Material3Text(
                    item.extension.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }

        if (selectedAnime == null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == 0, onClick = { mode = 0; loadPage(true) }, label = { Text("Popular") })
                if (source.supportsLatest) {
                    FilterChip(selected = mode == 1, onClick = { mode = 1; loadPage(true) }, label = { Text("Latest") })
                }
                IconButton(onClick = { showFilters = true }) {
                    Icon(Icons.Default.FilterList, contentDescription = localizeUiText("Search filters"))
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    label = { Text("Search ${source.name}") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { loadPage(true) }),
                )
                IconButton(onClick = { loadPage(true) }, enabled = !busy) {
                    Icon(Icons.Default.Search, contentDescription = localizeUiText("Search video catalogue"))
                }
            }
        }

        if (error != null) {
            Text(
                error.orEmpty(),
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (selectedAnime == null) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 108.dp),
                state = catalogueGridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (results.isEmpty() && busy) {
                    item(span = { GridItemSpan(maxLineSpan) }) { LoadingCard("Loading video catalogue…") }
                }
                if (results.isEmpty() && !busy && error == null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyCard("No videos found", "Try a different search or browse another installed source.")
                    }
                }
                items(results, key = { "${source.id}:${it.url}" }) { anime ->
                    SourceAnimeCard(
                        anime = anime,
                        source = source as? AnimeHttpSource,
                        onClick = { openAnime(anime) },
                    )
                }
                if (hasNextPage) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Button(
                            onClick = { loadPage(reset = false) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        ) { Text(if (busy) "Loading…" else "Load more") }
                    }
                }
                if (busy && results.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        } else {
            val anime = details ?: selectedAnime
            val libraryAnimeUrl = selectedAnimeUrl ?: anime?.url
            val animeArtworkUrl = selectedAnimeArtworkUrl
                ?: anime?.thumbnail_url?.takeIf {
                    it.isNotBlank() && !it.equals("null", ignoreCase = true)
                }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    val synopsisScrollState = remember(source.id, anime?.url) { ScrollState(0) }
                    var savedToLibrary by remember(source.id, libraryAnimeUrl) {
                        mutableStateOf(
                            libraryAnimeUrl?.let { database.containsSourceLibraryEntry(source.id, it) } == true,
                        )
                    }
                    var libraryActionBusy by remember(source.id, libraryAnimeUrl) { mutableStateOf(false) }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(
                                Modifier.width(104.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                SourceArtwork(
                                    imageUrl = animeArtworkUrl,
                                    contentDescription = anime?.title?.let { "$it cover art" },
                                    source = source as? AnimeHttpSource,
                                    modifier = Modifier.fillMaxWidth().height(154.dp),
                                )
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Material3Text(anime?.title.orEmpty(), style = MaterialTheme.typography.titleMedium)
                                val synopsis = anime?.description
                                if (!synopsis.isNullOrBlank()) {
                                    Text("Synopsis", style = MaterialTheme.typography.titleSmall)
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(136.dp)
                                            .background(
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                                                RoundedCornerShape(12.dp),
                                            )
                                            .verticalScroll(synopsisScrollState)
                                            .padding(10.dp),
                                    ) {
                                        Material3Text(
                                            synopsis,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                anime?.genre?.takeIf(String::isNotBlank)?.let { genre ->
                                    Material3Text(genre, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                FilledTonalButton(
                                    onClick = {
                                        if (anime != null && !libraryActionBusy) {
                                            val currentAnimeUrl = libraryAnimeUrl ?: anime.url
                                            val currentAnimeThumbnailUrl = selectedAnimeArtworkUrl
                                                ?: anime.thumbnail_url?.takeIf {
                                                    it.isNotBlank() && !it.equals("null", ignoreCase = true)
                                                }
                                            libraryActionBusy = true
                                            scope.launch {
                                                try {
                                                    withContext(Dispatchers.IO) {
                                                        if (savedToLibrary) {
                                                            database.removeSourceLibraryEntry(source.id, currentAnimeUrl)
                                                        } else {
                                                            database.saveSourceLibraryEntry(
                                                                AppDatabase.SourceLibraryEntry(
                                                                    sourceId = source.id,
                                                                    sourceName = source.name,
                                                                    extensionName = item.extension.name,
                                                                    animeUrl = currentAnimeUrl,
                                                                    title = anime.title,
                                                                    thumbnailUrl = resolveArtworkUrl(
                                                                        currentAnimeThumbnailUrl,
                                                                        (source as? AnimeHttpSource)?.baseUrl,
                                                                    ) ?: currentAnimeThumbnailUrl,
                                                                    description = anime.description,
                                                                    episodeCount = episodes.size.takeIf { it > 0 },
                                                                ),
                                                            )
                                                        }
                                                    }
                                                    savedToLibrary = !savedToLibrary
                                                } catch (_: Exception) {
                                                    error = "Couldn’t update your library."
                                                } finally {
                                                    libraryActionBusy = false
                                                }
                                            }
                                        }
                                    },
                                    enabled = !libraryActionBusy,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(
                                        if (savedToLibrary) Icons.Default.Delete else Icons.Default.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (savedToLibrary) "Remove from library" else "Add to library")
                                }
                            }
                        }
                    }
                }
                if (selectedEpisode == null) {
                    val seasonsOnly = anime?.fetch_type == FetchType.Seasons
                    if (seasonsOnly) {
                        item { Text("Seasons", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                        if (seasons.isEmpty() && busy) item { LoadingCard("Loading seasons…") }
                        if (seasons.isEmpty() && !busy && error == null) {
                            item { EmptyCard("No seasons found", "This source did not return any seasons for this video.") }
                        }
                    } else if (seasons.isNotEmpty()) {
                        item { Text("Seasons", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                    }
                    items(seasons, key = { "${source.id}:season:${it.url}" }) { season ->
                        val seasonProgress = savedSourceProgress?.takeIf {
                            it.identity.detailAnimeUrl == season.url
                        }
                        Card(
                            onClick = {
                                if (seasonProgress != null) continueSourceProgress(seasonProgress)
                                else openAnime(season)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Material3Text(season.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    if (seasonProgress != null) localizeUiText("Continue") else "Open",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                    if (!seasonsOnly) {
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        "Episodes",
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    nextAiringEpisode?.let { airing ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Icon(
                                                Icons.Default.Schedule,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Text(
                                                "Next ep. ${airing.episode} · ${formatAiringDate(airing.airingAtEpochSeconds)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                softWrap = false,
                                            )
                                        }
                                    }
                                }
                                IconButton(
                                    enabled = episodes.isNotEmpty(),
                                    onClick = {
                                        if (showEpisodeSearch) {
                                            episodeQuery = ""
                                            showEpisodeSearch = false
                                        } else {
                                            showEpisodeSearch = true
                                        }
                                    },
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = localizeUiText("Filter episodes"))
                                }
                                Box {
                                    IconButton(
                                        enabled = episodes.isNotEmpty(),
                                        onClick = { showEpisodeSortMenu = true },
                                    ) {
                                        Icon(Icons.Default.FilterList, contentDescription = localizeUiText("Sort episodes"))
                                    }
                                    DropdownMenu(
                                        expanded = showEpisodeSortMenu,
                                        onDismissRequest = { showEpisodeSortMenu = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Episode ascending") },
                                            onClick = {
                                                episodeSortOrder = EpisodeSortOrder.NumberAscending
                                                showEpisodeSortMenu = false
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Episode descending") },
                                            onClick = {
                                                episodeSortOrder = EpisodeSortOrder.NumberDescending
                                                showEpisodeSortMenu = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        if (showEpisodeSearch) {
                            item(key = "${source.id}:episode_filter") {
                                OutlinedTextField(
                                    value = episodeQuery,
                                    onValueChange = { episodeQuery = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                    singleLine = true,
                                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                    label = { Text("Filter episodes") },
                                )
                            }
                        }
                        if (episodes.isEmpty() && busy) item { LoadingCard("Loading episodes…") }
                        if (episodes.isEmpty() && !busy && error == null) {
                            item { EmptyCard("No episodes found", "This source did not return any episodes for this video.") }
                        }
                        if (episodes.isNotEmpty() && visibleEpisodes.isEmpty()) {
                            item { EmptyCard("No matching episodes", "Try a different episode title or number.") }
                        }
                        items(visibleEpisodes, key = { "${source.id}:${it.url}" }) { episode ->
                            val episodeProgress = savedSourceProgress?.takeIf { progress ->
                                progress.identity.detailAnimeUrl == selectedAnimeUrl &&
                                    (progress.identity.episodeUrl == episode.url ||
                                        (progress.identity.episodeNumber != null &&
                                            episode.sortingNumber() == progress.identity.episodeNumber.toFloat()))
                            }
                            Card(
                                onClick = {
                                    openEpisode(episode, autoPlayFirst = episodeProgress != null)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            ) {
                                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Material3Text(
                                            episode.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                        episodeProgress?.let { progress ->
                                            if (progress.durationMs > 0L) {
                                                val fraction = (progress.positionMs.toFloat() / progress.durationMs)
                                                    .coerceIn(0f, 1f)
                                                Box(
                                                    Modifier.fillMaxWidth()
                                                        .height(4.dp)
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                                ) {
                                                    Box(
                                                        Modifier.fillMaxWidth(fraction)
                                                            .fillMaxSize()
                                                            .background(MaterialTheme.colorScheme.primary),
                                                    )
                                                }
                                            }
                                            Text(
                                                if (progress.durationMs > 0L) {
                                                    "${formatProgressTime(progress.positionMs)} / ${formatProgressTime(progress.durationMs)}"
                                                } else {
                                                    formatProgressTime(progress.positionMs)
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                softWrap = false,
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        if (episodeProgress != null) localizeUiText("Continue") else "Play",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    item { Text("Available streams", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                    if (streamChoices.isEmpty() && busy) item { LoadingCard("Finding video streams…") }
                    items(streamChoices, key = { "${it.label}:${it.video.videoUrl}" }) { choice ->
                        Card(
                            onClick = {
                                selectedEpisode?.let { episode ->
                                    val currentIndex = visibleEpisodes.indexOfFirst { it.url == episode.url }
                                    val nextEpisode = visibleEpisodes.getOrNull(currentIndex + 1)
                                    val nextAction: NextEpisodeAction? = nextEpisode?.let { next ->
                                        { onFinished: (Boolean) -> Unit ->
                                            openEpisode(
                                                next,
                                                autoPlayFirst = true,
                                                preferredStream = choice.identity,
                                                onAutoPlayFinished = onFinished,
                                            )
                                        }
                                    }
                                    playVideoChoice(choice, episode, nextAction)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Material3Text(choice.label.ifBlank { localizeUiText("Video stream") }, style = MaterialTheme.typography.titleSmall)
                                    Text("Open in player", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("Play", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFilters) {
        FilterDialog(
            filterList = filterList,
            onDismiss = { showFilters = false },
            onApply = {
                showFilters = false
                loadPage(reset = true)
            },
        )
    }
}
