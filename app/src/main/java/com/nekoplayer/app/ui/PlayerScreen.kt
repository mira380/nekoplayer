package com.nekoplayer.app.ui

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import androidx.core.net.toUri
import android.view.View
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.graphics.drawable.toDrawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text as Material3Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.text.CueGroup
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nekoplayer.app.MainActivity
import com.nekoplayer.app.data.AppDatabase
import com.nekoplayer.app.data.DictionaryEntry
import com.nekoplayer.app.data.DictionaryRepository
import com.nekoplayer.app.library.AniListClient
import com.nekoplayer.app.library.JimakuSubtitleClient
import com.nekoplayer.app.media.ExactTermLookup
import com.nekoplayer.app.media.SubtitleCue
import com.nekoplayer.app.media.SubtitleCueIndex
import com.nekoplayer.app.media.SubtitleParser
import com.nekoplayer.app.media.SubtitleScanner
import com.nekoplayer.app.media.SubtitleScanResult
import com.nekoplayer.app.media.SubtitleTrack
import com.nekoplayer.app.media.SubtitleTrackSource
import com.nekoplayer.app.mining.AnkiDroidClient
import com.nekoplayer.app.mining.AnkiDroidMiningWorkflow
import com.nekoplayer.app.mining.PronunciationAudioClient
import com.nekoplayer.app.player.JimakuSubtitleDialog
import com.nekoplayer.app.player.findAutomaticJimakuSubtitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.hypot
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val PlayerOverlaySurface = Color(0xE6111415)
private val PlayerOverlayText = Color(0xFFDEE7EC)
private val PlayerOverlayAccent = Color(0xFFA0CDE0)

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
fun PlayerScreen(
    activity: MainActivity,
    database: AppDatabase,
    dictionaries: DictionaryRepository,
    anki: AnkiDroidClient,
    uri: Uri,
    displayName: String,
    seriesTitle: String?,
    modifier: Modifier = Modifier,
    requestHeaders: Map<String, String> = emptyMap(),
    subtitleEpisodeName: String? = null,
    subtitleEpisodeNumber: Int? = null,
    sourceProgressIdentity: AppDatabase.SourceProgressIdentity? = null,
    onClose: () -> Unit,
    onPickSubtitle: (((Uri, String) -> Unit) -> Unit),
    onNotify: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember(uri, requestHeaders) {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(requestHeaders)
        val dataSourceFactory = DefaultDataSource.Factory(activity, httpDataSourceFactory)
        ExoPlayer.Builder(activity)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
            }
    }
    val playerView = remember(player) { mutableStateOf<PlayerView?>(null) }
    var positionMs by remember(uri) { mutableLongStateOf(0L) }
    var durationMs by remember(uri) { mutableLongStateOf(0L) }
    var playing by remember(uri) { mutableStateOf(false) }
    var isBuffering by remember(uri) { mutableStateOf(true) }
    var videoAspect by remember(uri) { mutableFloatStateOf(16f / 9f) }
    var embeddedSubtitle by remember(uri) { mutableStateOf("") }
    var externalSubtitleTracks by remember(uri) { mutableStateOf(emptyList<SubtitleTrack>()) }
    var selectedExternalSubtitleId by remember(uri) { mutableStateOf<String?>(null) }
    var preferExternalSubtitles by remember(uri) { mutableStateOf(false) }
    var userSelectedSubtitle by remember(uri) { mutableStateOf(false) }
    var useExternalSubtitle by remember(uri) { mutableStateOf(false) }
    var embeddedSubtitlesEnabled by remember(uri) { mutableStateOf(true) }
    var subtitleOffsetsMs by remember(uri) { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var showControls by remember(uri) { mutableStateOf(true) }
    var showTracks by remember(uri) { mutableStateOf<String?>(null) }
    var showPlayerMenu by remember(uri) { mutableStateOf(false) }
    var showSubtitleMenu by remember(uri) { mutableStateOf(false) }
    var showSubtitleTimingDialog by remember(uri) { mutableStateOf(false) }
    var subtitleOffsetDraft by remember(uri) { mutableLongStateOf(0L) }
    var subtitleOffsetInput by remember(uri) { mutableStateOf("0") }
    var selectedSpeed by remember(uri) { mutableFloatStateOf(1f) }
    var volume by remember(uri) { mutableFloatStateOf(database.readNumber("player.volume", 1.0).toFloat().coerceIn(0f, 1f)) }
    var lastNonMutedVolume by remember(uri) { mutableFloatStateOf(database.readNumber("player.lastNonMutedVolume", 1.0).toFloat().coerceIn(0.01f, 1f)) }
    var seekDrag by remember(uri) { mutableFloatStateOf(-1f) }
    var subtitleLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var subtitleCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var lookupAnchor by remember { mutableStateOf(Offset.Zero) }
    var lookup by remember { mutableStateOf<SubtitleScanResult?>(null) }
    var lookupSourceText by remember { mutableStateOf<String?>(null) }
    var lookupRequestId by remember { mutableIntStateOf(0) }
    var lookupWordAlreadyMined by remember { mutableStateOf(false) }
    var lookupMessage by remember { mutableStateOf<String?>(null) }
    var lookupMetadata by remember { mutableStateOf(emptyList<com.nekoplayer.app.data.DictionaryTermMeta>()) }
    var lastSavedAt by remember(uri) { mutableLongStateOf(0L) }
    var errorMessage by remember(uri) { mutableStateOf<String?>(null) }
    var showJimakuDialog by remember(uri) { mutableStateOf(false) }
    var jimakuApiKey by remember { mutableStateOf("") }
    var dictionaryPopupExpansion by remember { mutableStateOf(DictionaryPopupExpansion()) }
    var dictionaryPopupSize by remember(database) {
        mutableFloatStateOf(DictionaryPopupSize.load(database))
    }
    var pronunciationPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var pronunciationFile by remember { mutableStateOf<File?>(null) }
    val pronunciationClient = remember(database) { PronunciationAudioClient(database, context.cacheDir) }
    val aniListClient = remember(context) { AniListClient(context) }
    val jimakuClient = remember(context) { JimakuSubtitleClient(context.cacheDir) }
    val latestMediaUri = rememberUpdatedState(uri.toString())
    val selectedExternalTrack = externalSubtitleTracks.firstOrNull { it.id == selectedExternalSubtitleId }
    val externalSubtitle = selectedExternalTrack.takeIf { useExternalSubtitle }
    val subtitleOffsetMs = selectedExternalSubtitleId
        ?.takeIf { useExternalSubtitle }
        ?.let { subtitleOffsetsMs[it] }
        ?.coerceIn(-10_000L, 10_000L)
        ?: 0L
    val latestExternalSubtitle = rememberUpdatedState(externalSubtitle)
    val externalCueIndex = externalSubtitle?.let { SubtitleCueIndex.activeCueIndex(it, positionMs - subtitleOffsetMs) }
    val activeCue: SubtitleCue? = externalSubtitle?.takeIf { useExternalSubtitle }
        ?.let { track -> externalCueIndex?.let(track.cues::getOrNull) }
    val subtitleText = activeCue?.plainText ?: embeddedSubtitle
    val subtitleFont = remember(database) { database.readNumber("subtitles.fontSize", 32.0).toFloat().coerceIn(14f, 48f) }
    val subtitleAlpha = remember(database) { database.readNumber("subtitles.backgroundOpacity", 0.72).toFloat().coerceIn(0f, 1f) }
    val subtitleShadowStyle = remember(database) {
        database.readString("subtitles.shadowStyle", "").takeIf { it in setOf("Off", "Soft", "Strong", "Hard") }
            ?: if (database.readBoolean("subtitles.shadowEnabled", true)) "Soft" else "Off"
    }
    val subtitleVignette = remember(database) { database.readNumber("subtitles.vignette", 0.0).toFloat().coerceIn(0f, 1f) }
    val subtitleBottom = remember(database) { database.readNumber("subtitles.bottomInsetPx", 112.0).toFloat().coerceIn(0f, 240f) }
    val pauseOnDictionaryLookup = remember(database) { database.readBoolean("playback.autoPause", false) }
    val autoFetchJimaku = remember(database) { database.readBoolean("jimaku.autoFetch", true) }

    LaunchedEffect(subtitleText, activeCue?.id) {
        lookupRequestId++
        lookup = null
        lookupSourceText = null
        lookupMessage = null
        lookupMetadata = emptyList()
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE
                if (playbackState == Player.STATE_READY) durationMs = player.duration.coerceAtLeast(0L)
            }
            override fun onCues(cueGroup: CueGroup) {
                val nextText = cueGroup.cues.mapNotNull { cue -> cue.text?.toString()?.takeIf(String::isNotBlank) }
                    .joinToString("\n")
                embeddedSubtitle = nextText
            }
            override fun onPlayerError(error: PlaybackException) {
                errorMessage = "This video could not be played. Try another file or check its format."
            }
        }
        player.addListener(listener)
        player.volume = volume
        onDispose {
            player.removeListener(listener)
            playerView.value?.player = null
            val currentPosition = player.currentPosition.coerceAtLeast(0L)
            val currentDuration = player.duration.takeIf { it > 0L } ?: 0L
            scope.launch(Dispatchers.IO) {
                database.saveProgress(uri.toString(), currentPosition, currentDuration)
                sourceProgressIdentity?.let {
                    database.saveSourceEpisodeProgress(it, uri.toString(), currentPosition, currentDuration)
                }
            }
            player.release()
        }
    }

    LaunchedEffect(player, uri, sourceProgressIdentity) {
        val (recent, sourceProgress) = withContext(Dispatchers.IO) {
            val recent = database.listRecent().firstOrNull { it.uri == uri.toString() }
            val sourceProgress = sourceProgressIdentity?.let(database::sourceEpisodeProgress)
            database.recordOpened(uri, displayName)
            recent to sourceProgress
        }
        val knownDuration = player.duration.takeIf { it > 0L }
            ?: sourceProgress?.durationMs
            ?: recent?.durationMs
            ?: 0L
        val saved = sourceProgress?.positionMs ?: recent?.positionMs ?: 0L
        val resumePosition = if (knownDuration > 0 && saved >= knownDuration - 10_000L) 0L else saved
        if (resumePosition > 0L) player.seekTo(resumePosition)
        player.playWhenReady = true
        selectedSpeed = 1f
        player.playbackParameters = PlaybackParameters(1f)
        activity.setPlayerImmersive(true)
    }

    LaunchedEffect(database) {
        jimakuApiKey = withContext(Dispatchers.IO) { database.readString("jimaku.apiKey") }
    }

    LaunchedEffect(database) {
        dictionaryPopupExpansion = withContext(Dispatchers.IO) {
            DictionaryPopupExpansion.load(database)
        }
    }

    LaunchedEffect(lookup, anki, database) {
        lookupWordAlreadyMined = false
        val activeLookup = lookup ?: return@LaunchedEffect
        val activeEntry = activeLookup.entries.firstOrNull() ?: return@LaunchedEffect
        val terms = listOf(activeEntry.expression, activeEntry.reading, activeLookup.surfaceText)
            .filter(String::isNotBlank)
            .distinct()
        val deckName = withContext(Dispatchers.IO) {
            database.readString("anki.deck", "Japanese::Mining").trim()
        }
        val status = withContext(Dispatchers.IO) { anki.status() }
        if (!status.installed || !status.apiEnabled || !status.permissionGranted) return@LaunchedEffect
        lookupWordAlreadyMined = try {
            withContext(Dispatchers.IO) { anki.hasWordInDeck(deckName, terms) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.takeIf { it > 0L } ?: durationMs
            if (positionMs - lastSavedAt >= 5_000L) {
                lastSavedAt = positionMs
                val savedPosition = positionMs
                val savedDuration = durationMs
                withContext(Dispatchers.IO) {
                    database.saveProgress(uri.toString(), savedPosition, savedDuration)
                    sourceProgressIdentity?.let {
                        database.saveSourceEpisodeProgress(it, uri.toString(), savedPosition, savedDuration)
                    }
                }
            }
            delay(250L)
        }
    }

    LaunchedEffect(showControls, playing) {
        if (showControls && playing) {
            delay(4_000L)
            showControls = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            pronunciationPlayer?.release()
            pronunciationPlayer = null
            pronunciationFile?.delete()
            pronunciationFile = null
        }
    }

    fun playPronunciation(entry: DictionaryEntry) {
        scope.launch {
            val asset = pronunciationClient.resolve(entry, destinationField = "Pronunciation Audio")
            if (asset == null) {
                onNotify("No pronunciation audio is available for this word.")
                return@launch
            }
            pronunciationPlayer?.release()
            pronunciationFile?.delete()
            val nextPlayer = MediaPlayer()
            pronunciationPlayer = nextPlayer
            pronunciationFile = asset.attachment.file
            try {
                nextPlayer.setDataSource(asset.attachment.file.absolutePath)
                nextPlayer.setOnPreparedListener { prepared -> prepared.start() }
                nextPlayer.setOnCompletionListener { completed ->
                    completed.release()
                    if (pronunciationPlayer === completed) {
                        pronunciationPlayer = null
                        pronunciationFile?.delete()
                        pronunciationFile = null
                    }
                }
                nextPlayer.setOnErrorListener { failed, _, _ ->
                    failed.release()
                    if (pronunciationPlayer === failed) {
                        pronunciationPlayer = null
                        pronunciationFile?.delete()
                        pronunciationFile = null
                    }
                    onNotify("Pronunciation audio could not be played.")
                    true
                }
                nextPlayer.prepareAsync()
            } catch (_: Exception) {
                nextPlayer.release()
                pronunciationFile?.delete()
                pronunciationPlayer = null
                pronunciationFile = null
                onNotify("Pronunciation audio could not be played.")
            }
        }
    }

    fun selectTrack(type: Int, group: Tracks.Group, index: Int) {
        if (type == C.TRACK_TYPE_TEXT) {
            userSelectedSubtitle = true
            useExternalSubtitle = false
            preferExternalSubtitles = false
            embeddedSubtitlesEnabled = true
        }
        val current = player.trackSelectionParameters
        player.trackSelectionParameters = current.buildUpon()
            .setTrackTypeDisabled(type, false)
            .clearOverridesOfType(type)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(index)))
            .build()
        showTracks = null
    }

    fun toggleEmbeddedSubtitles(enabled: Boolean, manualChoice: Boolean = true) {
        if (manualChoice) userSelectedSubtitle = true
        embeddedSubtitlesEnabled = enabled
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .build()
        if (enabled) {
            useExternalSubtitle = false
            preferExternalSubtitles = false
        }
    }

    fun tapSubtitle(position: Offset) {
        val layout = subtitleLayout ?: return
        val text = subtitleText
        if (text.isBlank()) return
        val offset = layout.getOffsetForPosition(position)
        lookupAnchor = subtitleCoordinates?.localToRoot(position) ?: Offset.Zero
        val requestId = lookupRequestId + 1
        lookupRequestId = requestId
        lookupSourceText = text
        lookup = null
        lookupMessage = null
        lookupMetadata = emptyList()
        if (pauseOnDictionaryLookup) player.pause()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                SubtitleScanner().scanContaining(text, offset) { terms -> dictionaries.lookupExactTerms(terms) }
            }
            if (requestId != lookupRequestId) return@launch
            lookup = result
            lookupMessage = if (result == null) "No dictionary match at that subtitle position." else null
            if (result != null) {
                val coordinates = subtitleCoordinates
                if (result.startUtf16Offset in 0 until text.length && result.endUtf16Offset in 1..text.length) {
                    val first = layout.getBoundingBox(result.startUtf16Offset)
                    val last = layout.getBoundingBox(result.endUtf16Offset - 1)
                    val wordCenter = Offset((first.left + last.right) / 2f, (first.top + last.bottom) / 2f)
                    lookupAnchor = coordinates?.localToRoot(wordCenter) ?: lookupAnchor
                }
                lookupMetadata = withContext(Dispatchers.IO) {
                    dictionaries.termMeta(result.entries.flatMap { listOf(it.expression, it.reading) }.toSet())
                }
                if (requestId != lookupRequestId) return@launch
            }
        }
    }

    val latestSubtitleTap = rememberUpdatedState<(Offset) -> Unit> { position -> tapSubtitle(position) }

    suspend fun parseExternalSubtitle(
        subtitleUri: Uri,
        name: String,
        source: SubtitleTrackSource = SubtitleTrackSource.MANUAL,
    ): SubtitleTrack = withContext(Dispatchers.IO) {
        val output = ByteArrayOutputStream()
        val input = context.contentResolver.openInputStream(subtitleUri)
            ?: throw IllegalArgumentException("Could not open this subtitle file.")
        input.use { stream ->
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > SubtitleParser.MAX_FILE_BYTES) throw IllegalArgumentException("Subtitle files must be 20 MiB or smaller.")
                output.write(buffer, 0, count)
            }
        }
        val parsed = SubtitleParser.parse(output.toByteArray(), name)
        if (source == SubtitleTrackSource.MANUAL) parsed else {
            val sourcePrefix = "${source.name.lowercase(Locale.ROOT)}-"
            parsed.copy(
                id = sourcePrefix + parsed.id,
                cues = parsed.cues.map { cue ->
                    cue.copy(id = sourcePrefix + cue.id, trackId = sourcePrefix + cue.trackId)
                },
                source = source,
            )
        }
    }

    fun applyExternalSubtitle(track: SubtitleTrack, manualChoice: Boolean = true) {
        externalSubtitleTracks = externalSubtitleTracks.filterNot { it.id == track.id } + track
        selectedExternalSubtitleId = track.id
        useExternalSubtitle = true
        preferExternalSubtitles = true
        embeddedSubtitle = ""
        toggleEmbeddedSubtitles(false, manualChoice = manualChoice)
    }

    fun removeExternalSubtitle(track: SubtitleTrack) {
        userSelectedSubtitle = true
        externalSubtitleTracks = externalSubtitleTracks.filterNot { it.id == track.id }
        subtitleOffsetsMs = subtitleOffsetsMs - track.id
        if (selectedExternalSubtitleId == track.id) {
            selectedExternalSubtitleId = null
            preferExternalSubtitles = false
            if (useExternalSubtitle) {
                useExternalSubtitle = false
                toggleEmbeddedSubtitles(false)
            }
        }
    }

    fun openExternalSubtitle(
        subtitleUri: Uri,
        name: String,
        source: SubtitleTrackSource = SubtitleTrackSource.MANUAL,
    ) {
        userSelectedSubtitle = true
        scope.launch {
            runCatching { parseExternalSubtitle(subtitleUri, name, source) }.fold(
                onSuccess = { applyExternalSubtitle(it) },
                onFailure = { onNotify(it.message ?: "Could not load subtitles.") },
            )
        }
    }

    LaunchedEffect(uri, displayName, seriesTitle, subtitleEpisodeName, subtitleEpisodeNumber, sourceProgressIdentity, jimakuApiKey, autoFetchJimaku, userSelectedSubtitle) {
        if (!autoFetchJimaku || userSelectedSubtitle || latestExternalSubtitle.value != null) return@LaunchedEffect
        val requestedMediaUri = uri.toString()
        val episodeName = subtitleEpisodeName?.takeIf(String::isNotBlank) ?: displayName
        val subtitleStorageKey = sourceProgressIdentity?.key ?: if (!seriesTitle.isNullOrBlank()) {
            listOf(seriesTitle.trim(), episodeName, subtitleEpisodeNumber?.toString().orEmpty()).joinToString("\\u0000")
        } else {
            requestedMediaUri
        }

        val savedFile = withContext(Dispatchers.IO) {
            findPersistedAutoJimakuSubtitle(context, subtitleStorageKey)
        }
        if (savedFile != null) {
            val savedTrack = try {
                parseExternalSubtitle(Uri.fromFile(savedFile), savedFile.name, SubtitleTrackSource.JIMAKU)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                withContext(Dispatchers.IO) { savedFile.delete() }
                null
            }
            if (savedTrack != null) {
                if (requestedMediaUri != latestMediaUri.value || userSelectedSubtitle || latestExternalSubtitle.value != null) return@LaunchedEffect
                applyExternalSubtitle(savedTrack, manualChoice = false)
                return@LaunchedEffect
            }
        }

        if (jimakuApiKey.isBlank()) return@LaunchedEffect
        val showName = seriesTitle?.takeIf(String::isNotBlank) ?: displayName
        val file = try {
            findAutomaticJimakuSubtitle(
                showName = showName,
                episodeFileName = episodeName,
                apiKey = jimakuApiKey,
                aniListClient = aniListClient,
                jimakuClient = jimakuClient,
                episodeNumber = subtitleEpisodeNumber,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return@LaunchedEffect
        if (requestedMediaUri != latestMediaUri.value || userSelectedSubtitle || latestExternalSubtitle.value != null) return@LaunchedEffect
        val track = try {
            parseExternalSubtitle(Uri.fromFile(file), file.name, SubtitleTrackSource.JIMAKU)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@LaunchedEffect
        }
        if (requestedMediaUri != latestMediaUri.value || userSelectedSubtitle || latestExternalSubtitle.value != null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            persistAutoJimakuSubtitle(context, subtitleStorageKey, file)
        }
        if (requestedMediaUri != latestMediaUri.value || userSelectedSubtitle || latestExternalSubtitle.value != null) return@LaunchedEffect
        applyExternalSubtitle(track, manualChoice = false)
    }

    fun setSubtitleOffset(offsetMs: Long) {
        val trackId = selectedExternalSubtitleId ?: return
        subtitleOffsetsMs = subtitleOffsetsMs + (trackId to offsetMs.coerceIn(-10_000L, 10_000L))
    }

    fun setSpeed(speed: Float) {
        selectedSpeed = speed
        player.playbackParameters = PlaybackParameters(speed)
        showTracks = null
    }

    fun setVolume(value: Float, persist: Boolean = false) {
        volume = value.coerceIn(0f, 1f)
        player.volume = volume
        if (volume > 0f) lastNonMutedVolume = volume
        if (persist) {
            scope.launch(Dispatchers.IO) {
                database.writeSetting("player.volume", volume.toDouble())
                database.writeSetting("player.lastNonMutedVolume", lastNonMutedVolume.toDouble())
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { showControls = !showControls },
    ) {
        val pictureHeight = minOf(maxHeight, maxWidth / videoAspect.coerceAtLeast(0.1f))
        val pictureBottomInset = (maxHeight - pictureHeight) / 2
        val configuredSubtitleInset = with(LocalDensity.current) { subtitleBottom.toDp() }
        val captionBottomInset = maxOf(
            pictureBottomInset + configuredSubtitleInset.coerceAtMost(pictureHeight / 4),
            if (showControls) 160.dp else 0.dp,
        )
        val selectionHighlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        val highlightedSubtitle = remember(subtitleText, lookup, lookupSourceText, selectionHighlight) {
            buildAnnotatedString {
                val match = lookup?.takeIf { lookupSourceText == subtitleText }
                val start = match?.startUtf16Offset ?: -1
                val end = match?.endUtf16Offset ?: -1
                if (start in 0 until subtitleText.length && end in (start + 1)..subtitleText.length) {
                    append(subtitleText.substring(0, start))
                    pushStyle(SpanStyle(background = selectionHighlight))
                    append(subtitleText.substring(start, end))
                    pop()
                    append(subtitleText.substring(end))
                } else {
                    append(subtitleText)
                }
            }
        }
        val density = LocalDensity.current
        val subtitleShadow = remember(subtitleShadowStyle, density) {
            with(density) {
                when (subtitleShadowStyle) {
                    "Strong" -> Shadow(
                        color = Color.Black.copy(alpha = 0.95f),
                        offset = Offset(0f, 3.dp.toPx()),
                        blurRadius = 6.dp.toPx(),
                    )
                    "Hard" -> Shadow(
                        color = Color.Black.copy(alpha = 0.9f),
                        offset = Offset(0f, 2.dp.toPx()),
                        blurRadius = 0f,
                    )
                    "Soft" -> Shadow(
                        color = Color.Black.copy(alpha = 0.7f),
                        offset = Offset(0f, 1.dp.toPx()),
                        blurRadius = 3.dp.toPx(),
                    )
                    else -> null
                }
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                (LayoutInflater.from(viewContext).inflate(
                    com.nekoplayer.app.R.layout.player_view,
                    FrameLayout(viewContext),
                    false,
                ) as PlayerView).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                    subtitleView?.visibility = View.GONE
                }
            },
            update = { view ->
                view.player = player
                view.subtitleView?.visibility = View.GONE
                playerView.value = view
            },
        )
        if (subtitleVignette > 0f) {
            Canvas(
                Modifier.fillMaxSize().drawWithCache {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val vignetteBrush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = subtitleVignette),
                        ),
                        center = center,
                        radius = hypot(center.x, center.y).coerceAtLeast(1f),
                    )
                    onDrawBehind { drawRect(brush = vignetteBrush) }
                },
            ) {}
        }
        if (subtitleText.isNotBlank()) {
            Material3Text(
                text = highlightedSubtitle,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 20.dp)
                    .padding(bottom = captionBottomInset)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = subtitleAlpha.coerceIn(0f, 1f)))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .onGloballyPositioned { subtitleCoordinates = it }
                    .pointerInput(subtitleText) { detectTapGestures { latestSubtitleTap.value(it) } },
                color = Color.White,
                fontSize = subtitleFont.sp,
                style = MaterialTheme.typography.bodyMedium.copy(shadow = subtitleShadow),
                textAlign = TextAlign.Center,
                lineHeight = (subtitleFont * 1.25f).sp,
                onTextLayout = { subtitleLayout = it },
            )
        }
        if (isBuffering && errorMessage == null) {
            Text("Loading video…", Modifier.align(Alignment.Center), color = PlayerOverlayText.copy(alpha = 0.8f))
        }
        errorMessage?.let { message ->
            Surface(Modifier.align(Alignment.Center).padding(24.dp), color = Color(0xF2171A1B), shape = RoundedCornerShape(16.dp)) {
                Text(message, Modifier.padding(18.dp), color = PlayerOverlayText)
            }
        }
        if (showControls) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(
                    Modifier.fillMaxWidth().background(PlayerOverlaySurface).padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = localizeUiText("Close player"), tint = PlayerOverlayText) }
                    Text(displayName, Modifier.weight(1f), color = PlayerOverlayText, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Box {
                        IconButton(onClick = { showPlayerMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = localizeUiText("Player options"), tint = PlayerOverlayText)
                        }
                        DropdownMenu(expanded = showPlayerMenu, onDismissRequest = { showPlayerMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Open subtitle file", style = MaterialTheme.typography.bodyMedium) },
                                onClick = {
                                    showPlayerMenu = false
                                    onPickSubtitle { subtitleUri, name ->
                                        openExternalSubtitle(subtitleUri, name)
                                    }
                                },
                                leadingIcon = { Icon(Icons.Default.Subtitles, contentDescription = null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Find subtitles with Jimaku", style = MaterialTheme.typography.bodyMedium) },
                                onClick = {
                                    showPlayerMenu = false
                                    userSelectedSubtitle = true
                                    showJimakuDialog = true
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Audio tracks", style = MaterialTheme.typography.bodyMedium) },
                                onClick = { showPlayerMenu = false; showTracks = "audio" },
                            )
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().background(PlayerOverlaySurface).padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(formatTime(if (seekDrag >= 0f) seekDrag.roundToLong() else positionMs), modifier = Modifier.width(44.dp), textAlign = TextAlign.Center, color = PlayerOverlayText, style = MaterialTheme.typography.labelSmall)
                        Slider(
                            value = (if (seekDrag >= 0f) seekDrag else positionMs.toFloat()).coerceIn(0f, durationMs.coerceAtLeast(1L).toFloat()),
                            onValueChange = { seekDrag = it },
                            onValueChangeFinished = {
                                if (seekDrag >= 0f) player.seekTo(seekDrag.roundToLong())
                                seekDrag = -1f
                            },
                            valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                        )
                        Text(formatTime(durationMs), modifier = Modifier.width(44.dp), textAlign = TextAlign.Center, color = PlayerOverlayText, style = MaterialTheme.typography.labelSmall)
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val volumeControls: @Composable () -> Unit = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = {
                                    setVolume(if (volume > 0f) 0f else lastNonMutedVolume, persist = true)
                                }) {
                                    Icon(
                                        if (volume > 0f) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                                        contentDescription = localizeUiText(if (volume > 0f) "Mute" else "Unmute"),
                                        tint = PlayerOverlayText,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                Slider(
                                    value = volume,
                                    onValueChange = { setVolume(it) },
                                    onValueChangeFinished = { setVolume(volume, persist = true) },
                                    valueRange = 0f..1f,
                                    modifier = Modifier.width(88.dp).height(48.dp).padding(horizontal = 10.dp),
                                )
                            }
                        }
                        val transportControls: @Composable () -> Unit = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L)) }) {
                                    Icon(Icons.Default.Replay10, contentDescription = localizeUiText("Back 10 seconds"), tint = PlayerOverlayText)
                                }
                                IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = localizeUiText(if (playing) "Pause" else "Play"), tint = PlayerOverlayText, modifier = Modifier.size(28.dp))
                                }
                                IconButton(onClick = { player.seekTo((player.currentPosition + 10_000L).coerceAtMost(durationMs)) }) {
                                    Icon(Icons.Default.Forward10, contentDescription = localizeUiText("Forward 10 seconds"), tint = PlayerOverlayText)
                                }
                            }
                        }
                        val playerOptions: @Composable () -> Unit = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = { showTracks = "speed" },
                                    modifier = Modifier.width(58.dp).heightIn(min = 48.dp),
                                    contentPadding = PaddingValues(horizontal = 2.dp),
                                ) {
                                    Text("${selectedSpeed}×", color = PlayerOverlayText, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                                }
                                TextButton(
                                    onClick = {
                                        subtitleOffsetDraft = subtitleOffsetMs
                                        subtitleOffsetInput = subtitleOffsetMs.toString()
                                        showSubtitleTimingDialog = true
                                    },
                                    enabled = externalSubtitle != null,
                                    modifier = Modifier.width(80.dp).heightIn(min = 48.dp),
                                    contentPadding = PaddingValues(horizontal = 2.dp),
                                ) {
                                    Text(
                                        "Sub ${formatSubtitleOffset(subtitleOffsetMs)}",
                                        color = if (externalSubtitle != null) PlayerOverlayAccent else PlayerOverlayText.copy(alpha = 0.58f),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                                Box {
                                    IconButton(onClick = { showSubtitleMenu = true }) {
                                        Icon(Icons.Default.Subtitles, contentDescription = localizeUiText("Subtitle options"), tint = PlayerOverlayText, modifier = Modifier.size(20.dp))
                                    }
                                    DropdownMenu(expanded = showSubtitleMenu, onDismissRequest = { showSubtitleMenu = false }) {
                                        DropdownMenuItem(
                                            text = { Text("Subtitle tracks", style = MaterialTheme.typography.bodyMedium) },
                                            onClick = { showSubtitleMenu = false; showTracks = "subtitle" },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(if (useExternalSubtitle || embeddedSubtitlesEnabled) "Turn subtitles off" else "Turn subtitles on") },
                                            onClick = {
                                                if (useExternalSubtitle || embeddedSubtitlesEnabled) {
                                                    useExternalSubtitle = false
                                                    toggleEmbeddedSubtitles(false)
                                                } else {
                                                    val track = selectedExternalTrack
                                                    if (preferExternalSubtitles && track != null) applyExternalSubtitle(track)
                                                    else toggleEmbeddedSubtitles(true)
                                                }
                                                showSubtitleMenu = false
                                            },
                                        )
                                        selectedExternalTrack?.takeIf { it.source == SubtitleTrackSource.MANUAL }?.let { track ->
                                            DropdownMenuItem(
                                                text = { Text("Remove ${track.name}") },
                                                onClick = { removeExternalSubtitle(track); showSubtitleMenu = false },
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (maxWidth >= 600.dp) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { volumeControls() }
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { transportControls() }
                                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { playerOptions() }
                            }
                        } else {
                            Column(Modifier.fillMaxWidth()) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { transportControls() }
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    volumeControls()
                                    playerOptions()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSubtitleTimingDialog) {
        val validOffset = subtitleOffsetInput.toLongOrNull()?.takeIf { it in -10_000L..10_000L }
        AlertDialog(
            onDismissRequest = { showSubtitleTimingDialog = false },
            title = { Text("Subtitle timing") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(formatSubtitleOffset(subtitleOffsetDraft), style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = subtitleOffsetDraft.toFloat(),
                        onValueChange = { value ->
                            val snapped = ((value / 100f).roundToInt() * 100L).coerceIn(-10_000L, 10_000L)
                            subtitleOffsetDraft = snapped
                            subtitleOffsetInput = snapped.toString()
                        },
                        valueRange = -10_000f..10_000f,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("−10 s", style = MaterialTheme.typography.labelSmall)
                        Text("+10 s", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedTextField(
                        value = subtitleOffsetInput,
                        onValueChange = { candidate ->
                            if (candidate.matches(Regex("[+-]?\\d{0,5}"))) {
                                val parsed = candidate.toLongOrNull()
                                if (parsed == null || parsed in -10_000L..10_000L) {
                                    subtitleOffsetInput = candidate
                                    parsed?.let { subtitleOffsetDraft = it }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Offset in milliseconds") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        singleLine = true,
                    )
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        subtitleOffsetDraft = 0L
                        subtitleOffsetInput = "0"
                    }) { Text("Reset") }
                    TextButton(onClick = { showSubtitleTimingDialog = false }) { Text("Cancel") }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = validOffset != null,
                    onClick = {
                        validOffset?.let(::setSubtitleOffset)
                        showSubtitleTimingDialog = false
                    },
                ) { Text("Apply") }
            },
        )
    }

    showTracks?.let { kind ->
        when (kind) {
            "speed" -> AlertDialog(
                onDismissRequest = { showTracks = null },
                title = { Text("Playback speed") },
                text = {
                    Column {
                        listOf(0.5f, 0.75f, 1f, 1.5f, 2f).forEach { speed ->
                            TextButton(onClick = { setSpeed(speed) }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (speed == selectedSpeed) "✓  ${speed}×" else "${speed}×", modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showTracks = null }) { Text("Done") } },
            )
            else -> TrackDialog(
                title = if (kind == "audio") "Audio tracks" else "Subtitle tracks",
                tracks = player.currentTracks,
                kind = kind,
                externalTracks = externalSubtitleTracks,
                selectedExternalTrackId = selectedExternalTrack?.id.takeIf { useExternalSubtitle },
                useExternal = useExternalSubtitle,
                embeddedEnabled = embeddedSubtitlesEnabled,
                onExternal = { track ->
                    applyExternalSubtitle(track)
                    showTracks = null
                },
                onSelect = { type, group, index -> selectTrack(type, group, index) },
                onOff = { useExternalSubtitle = false; toggleEmbeddedSubtitles(false); showTracks = null },
                onDismiss = { showTracks = null },
            )
        }
    }

    if (lookup != null || lookupMessage != null) {
        LookupPopup(
            result = lookup,
            message = lookupMessage,
            metadata = lookupMetadata,
            alreadyMined = lookupWordAlreadyMined,
            expansion = dictionaryPopupExpansion,
            popupSize = dictionaryPopupSize,
            anchorInPlayer = lookupAnchor,
            subtitleContext = activeCue?.plainText ?: embeddedSubtitle,
            onDismiss = {
                lookupRequestId++
                lookup = null
                lookupSourceText = null
                lookupMessage = null
                lookupMetadata = emptyList()
            },
            onPlayAudio = ::playPronunciation,
            onMine = { entry ->
                scope.launch {
                    val sentence = activeCue?.plainText ?: embeddedSubtitle
                    if (sentence.isBlank()) {
                        onNotify("Select a subtitle cue before mining a card.")
                        return@launch
                    }
                    val permission = try { awaitAnkiWritePermission(anki) } catch (_: Exception) {
                        onNotify("AnkiDroid access could not be requested.")
                        return@launch
                    }
                    if (!permission) {
                        onNotify("AnkiDroid access was denied.")
                        return@launch
                    }
                    val cueStart = activeCue?.let {
                        (it.startMs + externalSubtitle.offsetMs + subtitleOffsetMs).coerceAtLeast(0L)
                    } ?: positionMs
                    val cueEnd = activeCue?.let {
                        (it.endMs + externalSubtitle.offsetMs + subtitleOffsetMs).coerceAtLeast(cueStart)
                    } ?: (cueStart + 1_500L)
                    val related = lookup?.entries.orEmpty() + lookup?.relatedEntries.orEmpty()
                    val allEntries = (listOf(entry) + related).distinctBy { it.dictionaryId to it.id }
                    val originalVolume = player.volume
                    val playAudio = withContext(Dispatchers.IO) {
                        database.readBoolean("capture.playAudioWhileCapture", false)
                    }
                    try {
                        if (!playAudio) player.volume = 0f
                        val pronunciation = pronunciationClient.resolve(entry, destinationField = "Pronunciation Audio")
                        AnkiDroidMiningWorkflow(context, database, anki).mine(
                            uri = uri,
                            sentence = sentence,
                            cueStartMs = cueStart,
                            cueEndMs = cueEnd,
                            mainEntry = entry,
                            allEntries = allEntries,
                            metadata = lookupMetadata,
                            surfaceText = lookup?.surfaceText.orEmpty(),
                            pronunciationAttachment = pronunciation?.attachment,
                        )
                        lookup = null
                        lookupMessage = null
                    } catch (_: Exception) {
                        onNotify("Card could not be added to AnkiDroid.")
                    } finally {
                        player.volume = originalVolume
                    }
                }
            }
        )
    }

    if (showJimakuDialog) {
        JimakuSubtitleDialog(
            showName = seriesTitle?.takeIf(String::isNotBlank) ?: displayName,
            episodeFileName = displayName,
            apiKey = jimakuApiKey,
            aniListClient = aniListClient,
            jimakuSubtitleClient = jimakuClient,
            onSubtitleFile = { file ->
                showJimakuDialog = false
                openExternalSubtitle(Uri.fromFile(file), file.name, SubtitleTrackSource.JIMAKU)
            },
            onDismiss = { showJimakuDialog = false },
        )
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
private fun TrackDialog(
    title: String,
    tracks: Tracks,
    kind: String,
    externalTracks: List<SubtitleTrack>,
    selectedExternalTrackId: String?,
    useExternal: Boolean,
    embeddedEnabled: Boolean,
    onExternal: (SubtitleTrack) -> Unit,
    onSelect: (Int, Tracks.Group, Int) -> Unit,
    onOff: () -> Unit,
    onDismiss: () -> Unit,
) {
    val trackType = if (kind == "audio") C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
    val groups = tracks.groups.filter { it.type == trackType }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn {
                if (kind == "subtitle") {
                    item {
                        TextButton(onClick = { onOff() }, modifier = Modifier.fillMaxWidth()) {
                            Text("${if (!useExternal && !embeddedEnabled) "✓  " else ""}${localizeUiText("Subtitles off")}", modifier = Modifier.fillMaxWidth())
                        }
                    }
                    items(externalTracks, key = SubtitleTrack::id) { track ->
                        TextButton(onClick = { onExternal(track) }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "${if (track.id == selectedExternalTrackId) "✓  " else ""}${track.name} (${localizeUiText("external")})",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                items(groups.flatMap { group -> (0 until group.length).map { group to it } }) { (group, index) ->
                    val format = group.mediaTrackGroup.getFormat(index)
                    val label = listOfNotNull(format.label?.takeIf(String::isNotBlank), format.language?.takeIf(String::isNotBlank))
                        .distinct().joinToString(" · ").ifBlank { "Track ${index + 1}" }
                    val selected = group.isTrackSelected(index)
                    TextButton(onClick = { onSelect(trackType, group, index) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${if (selected) "✓  " else ""}${localizeUiText(label)}", modifier = Modifier.fillMaxWidth())
                    }
                }
                if (groups.isEmpty() && (kind == "audio" || externalTracks.isEmpty())) {
                    item { Text("No ${if (kind == "audio") "audio" else "subtitle"} tracks found.") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun LookupPopup(
    result: SubtitleScanResult?,
    message: String?,
    metadata: List<com.nekoplayer.app.data.DictionaryTermMeta>,
    alreadyMined: Boolean,
    expansion: DictionaryPopupExpansion,
    popupSize: Float,
    anchorInPlayer: Offset,
    subtitleContext: String,
    onDismiss: () -> Unit,
    onPlayAudio: (DictionaryEntry) -> Unit,
    onMine: (DictionaryEntry) -> Unit,
) {
    val entries = remember(result) {
        (result?.entries.orEmpty() + result?.relatedEntries.orEmpty()).take(36)
    }
    val scheme = MaterialTheme.colorScheme
    val language = LocalNekoLanguage.current
    val pageHtml = remember(
        result, metadata, subtitleContext, alreadyMined, expansion, language,
        scheme.surface, scheme.onSurface, scheme.onSurfaceVariant,
        scheme.surfaceVariant, scheme.outlineVariant, scheme.primary,
        scheme.onPrimary, scheme.background,
    ) {
        result?.let {
            YomitanPopupHtml.render(
                it,
                metadata,
                subtitleContext,
                YomitanPopupColors(
                    background = scheme.surface.toArgb(),
                    foreground = scheme.onSurface.toArgb(),
                    secondary = scheme.onSurfaceVariant.toArgb(),
                    surface = scheme.surfaceVariant.toArgb(),
                    divider = scheme.outlineVariant.toArgb(),
                    accent = scheme.primary.toArgb(),
                    accentForeground = scheme.onPrimary.toArgb(),
                    isDark = scheme.background.luminance() < 0.5f,
                ),
                language = language,
                alreadyMined = alreadyMined,
                expansion = expansion,
            )
        }
    }
    val density = LocalDensity.current.density
    val positionProvider = remember(anchorInPlayer, density) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val margin = (12f * density).roundToInt()
                val anchorX = if (anchorInPlayer == Offset.Zero) {
                    anchorBounds.left + anchorBounds.width / 2
                } else {
                    anchorBounds.left + anchorInPlayer.x.roundToInt()
                }
                val anchorY = if (anchorInPlayer == Offset.Zero) {
                    anchorBounds.top + anchorBounds.height / 2
                } else {
                    anchorBounds.top + anchorInPlayer.y.roundToInt()
                }
                val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
                val maxY = (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)
                val x = (anchorX - popupContentSize.width / 2).coerceIn(margin, maxX)
                val below = anchorY + margin
                val preferredY = if (below + popupContentSize.height <= windowSize.height - margin) {
                    below
                } else {
                    anchorY - popupContentSize.height - margin
                }
                return IntOffset(x, preferredY.coerceIn(margin, maxY))
            }
        }
    }

    BoxWithConstraints {
        val portrait = maxHeight > maxWidth
        val popupWidth = (maxWidth * popupSize)
            .coerceAtMost(if (portrait) 760.dp else 1000.dp)
        val popupHeight = (maxHeight * popupSize)
            .coerceAtMost(760.dp)

        if (pageHtml != null) {
            AnchoredYomitanPopup(
                pageHtml = pageHtml,
                popupWidth = popupWidth,
                popupHeight = popupHeight,
                anchorInPlayer = anchorInPlayer,
                entries = entries,
                onDismiss = onDismiss,
                onPlayAudio = onPlayAudio,
                onMine = onMine,
            )
        } else {
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = onDismiss,
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                    clippingEnabled = true,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .background(scheme.surface.copy(alpha = 0.96f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(message ?: "No match.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun AnchoredYomitanPopup(
    pageHtml: String,
    popupWidth: androidx.compose.ui.unit.Dp,
    popupHeight: androidx.compose.ui.unit.Dp,
    anchorInPlayer: Offset,
    entries: List<DictionaryEntry>,
    onDismiss: () -> Unit,
    onPlayAudio: (DictionaryEntry) -> Unit,
    onMine: (DictionaryEntry) -> Unit,
) {
    val context = LocalContext.current
    val hostView = LocalView.current
    val density = LocalDensity.current
    val popupWidthPx = with(density) { popupWidth.roundToPx() }
    val popupHeightPx = with(density) { popupHeight.roundToPx() }
    val latestEntries = rememberUpdatedState(entries)
    val latestPlayAudio = rememberUpdatedState(onPlayAudio)
    val latestMine = rememberUpdatedState(onMine)
    val latestDismiss = rememberUpdatedState(onDismiss)

    val webView = remember(context) {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = false
            settings.apply {
                javaScriptEnabled = false
                domStorageEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                blockNetworkLoads = true
                builtInZoomControls = false
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = false
                textZoom = 100
                defaultTextEncodingName = "UTF-8"
            }
            webViewClient = object : WebViewClient() {
                private fun handleLookupLink(rawUrl: String?): Boolean {
                    val action = runCatching { rawUrl?.toUri() }.getOrNull() ?: return true
                    if (action.scheme != "nekoplayer") return true
                    when (action.host) {
                        "close" -> latestDismiss.value()
                        "audio", "mine" -> {
                            val index = action.lastPathSegment?.toIntOrNull() ?: return true
                            val entry = latestEntries.value.getOrNull(index) ?: return true
                            if (action.host == "audio") latestPlayAudio.value(entry)
                            else latestMine.value(entry)
                        }
                    }
                    return true
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest,
                ): Boolean = handleLookupLink(request.url.toString())

                @Deprecated("Required for compatibility with older Android WebView versions")
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    url: String?,
                ): Boolean = handleLookupLink(url)
            }
        }
    }
    val popupWindow = remember(webView) {
        PopupWindow(webView, popupWidthPx.coerceAtLeast(1), popupHeightPx.coerceAtLeast(1), true).apply {
            isClippingEnabled = true
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())
            animationStyle = 0
        }
    }

    DisposableEffect(popupWindow, webView) {
        popupWindow.setOnDismissListener { latestDismiss.value() }
        onDispose {
            popupWindow.setOnDismissListener(null)
            if (popupWindow.isShowing) popupWindow.dismiss()
            webView.stopLoading()
            webView.webViewClient = WebViewClient()
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()
        }
    }

    LaunchedEffect(pageHtml, popupWidthPx, popupHeightPx, anchorInPlayer, hostView) {
        if (webView.tag != pageHtml) {
            webView.tag = pageHtml
            webView.loadDataWithBaseURL(
                "https://nekoplayer.invalid/",
                pageHtml,
                "text/html",
                "UTF-8",
                null,
            )
        }

        val rootView = hostView.rootView
        if (popupWidthPx <= 0 || popupHeightPx <= 0 || rootView.width <= 0 || rootView.height <= 0) {
            return@LaunchedEffect
        }

        val hostLocation = IntArray(2)
        hostView.getLocationInWindow(hostLocation)
        val margin = with(density) { 12.dp.roundToPx() }
        val windowWidth = rootView.width
        val windowHeight = rootView.height
        val anchorX = if (anchorInPlayer == Offset.Zero) {
            hostLocation[0] + hostView.width / 2
        } else {
            hostLocation[0] + anchorInPlayer.x.roundToInt()
        }
        val anchorY = if (anchorInPlayer == Offset.Zero) {
            hostLocation[1] + hostView.height / 2
        } else {
            hostLocation[1] + anchorInPlayer.y.roundToInt()
        }
        val maxX = (windowWidth - popupWidthPx - margin).coerceAtLeast(margin)
        val maxY = (windowHeight - popupHeightPx - margin).coerceAtLeast(margin)
        val x = (anchorX - popupWidthPx / 2).coerceIn(margin, maxX)
        val below = anchorY + margin
        val preferredY = if (below + popupHeightPx <= windowHeight - margin) {
            below
        } else {
            anchorY - popupHeightPx - margin
        }
        val y = preferredY.coerceIn(margin, maxY)
        val rightToLeft = hostView.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val horizontalOffset = if (rightToLeft) windowWidth - x - popupWidthPx else x

        if (popupWindow.isShowing) {
            popupWindow.update(horizontalOffset, y, popupWidthPx, popupHeightPx)
        } else {
            popupWindow.width = popupWidthPx
            popupWindow.height = popupHeightPx
            popupWindow.showAtLocation(rootView, Gravity.TOP or Gravity.START, horizontalOffset, y)
        }
    }
}

private fun autoJimakuSubtitleDirectory(context: Context, identity: String): File {
    val folderName = UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8)).toString()
    return File(File(context.filesDir, "auto-jimaku-subtitles"), folderName)
}

private fun findPersistedAutoJimakuSubtitle(context: Context, identity: String): File? =
    autoJimakuSubtitleDirectory(context, identity)
        .listFiles()
        ?.filter { file ->
            file.isFile &&
                file.length() in 1L..SubtitleParser.MAX_FILE_BYTES.toLong() &&
                file.extension.lowercase(Locale.ROOT) in SubtitleParser.SUPPORTED_EXTENSIONS
        }
        ?.maxByOrNull { it.lastModified() }

private fun persistAutoJimakuSubtitle(context: Context, identity: String, source: File): File? {
    val root = File(context.filesDir, "auto-jimaku-subtitles")
    val directory = autoJimakuSubtitleDirectory(context, identity)
    return try {
        if (!directory.exists() && !directory.mkdirs()) return null
        val destination = File(directory, source.name)
        source.copyTo(destination, overwrite = true)
        directory.listFiles()
            ?.filter { it.isFile && it != destination }
            ?.forEach { it.delete() }
        trimAutoJimakuSubtitleStorage(root, directory)
        destination
    } catch (_: Exception) {
        File(directory, source.name).delete()
        null
    }
}

private fun trimAutoJimakuSubtitleStorage(root: File, newestDirectory: File) {
    val olderDirectories = root.listFiles()
        ?.filter { it.isDirectory && it != newestDirectory }
        ?.sortedByDescending { it.lastModified() }
        .orEmpty()
    val directories = listOf(newestDirectory) + olderDirectories
    var keptEpisodes = 0
    var keptBytes = 0L
    for (directory in directories) {
        val bytes = directory.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        val keep = directory == newestDirectory ||
            (keptEpisodes < MAX_SAVED_JIMAKU_EPISODES && keptBytes + bytes <= MAX_SAVED_JIMAKU_BYTES)
        if (keep) {
            keptEpisodes++
            keptBytes += bytes
        } else {
            directory.deleteRecursively()
        }
    }
}

private const val MAX_SAVED_JIMAKU_EPISODES = 200
private const val MAX_SAVED_JIMAKU_BYTES = 128L * 1024L * 1024L

private fun formatTime(timeMs: Long): String {
    val totalSeconds = (timeMs.coerceAtLeast(0L) / 1000L)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
}

private fun formatSubtitleOffset(offsetMs: Long): String = when {
    offsetMs > 0L -> "+${offsetMs} ms"
    offsetMs < 0L -> "−${-offsetMs} ms"
    else -> "0 ms"
}


private suspend fun awaitAnkiWritePermission(anki: AnkiDroidClient): Boolean =
    suspendCancellableCoroutine { continuation ->
        anki.requestWritePermission { result ->
            if (!continuation.isActive) return@requestWritePermission
            result.fold(
                onSuccess = continuation::resume,
                onFailure = continuation::resumeWithException,
            )
        }
    }
