package com.nekoplayer.app.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.PopupWindow
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import com.nekoplayer.app.data.DictionaryEntry
import com.nekoplayer.app.data.DictionaryRepository
import com.nekoplayer.app.data.DictionaryTermMeta
import com.nekoplayer.app.media.SubtitleScanResult
import com.nekoplayer.app.media.SubtitleScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal fun scanYomitanSubtitle(
    text: String,
    offset: Int,
    dictionaries: DictionaryRepository,
): SubtitleScanResult? = SubtitleScanner().scanContaining(text, offset) { terms ->
    dictionaries.lookupExactTerms(terms)
}

internal fun loadYomitanMetadata(
    result: SubtitleScanResult,
    dictionaries: DictionaryRepository,
): List<DictionaryTermMeta> = dictionaries.termMeta(
    buildSet {
        result.entries.forEach { entry ->
            add(entry.expression)
            add(entry.reading)
        }
    },
)

private data class YomitanLookupPage(
    val result: SubtitleScanResult,
    val metadata: List<DictionaryTermMeta>,
    val subtitleContext: String,
)

@Composable
internal fun YomitanLookupPopup(
    result: SubtitleScanResult?,
    message: String?,
    metadata: List<DictionaryTermMeta>,
    alreadyMined: Boolean,
    expansion: DictionaryPopupExpansion,
    popupSize: Float,
    anchorInPlayer: androidx.compose.ui.geometry.Offset,
    subtitleContext: String,
    dictionaries: DictionaryRepository,
    onDismiss: () -> Unit,
    onPlayAudio: (DictionaryEntry) -> Unit,
    onMine: (DictionaryEntry, SubtitleScanResult, List<DictionaryTermMeta>) -> Unit,
) {
    var linkedPages by remember(result) { mutableStateOf(emptyList<YomitanLookupPage>()) }
    var showFullPage by remember(result) { mutableStateOf(false) }
    var lookupRequestVersion by remember(result) { mutableIntStateOf(0) }
    val lookupScope = rememberCoroutineScope()
    val currentPage = linkedPages.lastOrNull() ?: result?.let {
        YomitanLookupPage(it, metadata, subtitleContext)
    }
    val entries = remember(currentPage) {
        (currentPage?.result?.entries.orEmpty() + currentPage?.result?.relatedEntries.orEmpty()).take(36)
    }
    val scheme = MaterialTheme.colorScheme
    val language = LocalNekoLanguage.current
    val pageColors = remember(
        scheme.surface, scheme.onSurface, scheme.onSurfaceVariant, scheme.surfaceVariant,
        scheme.outlineVariant, scheme.primary, scheme.onPrimary, scheme.background,
    ) {
        YomitanPopupColors(
            background = scheme.surface.toArgb(),
            foreground = scheme.onSurface.toArgb(),
            secondary = scheme.onSurfaceVariant.toArgb(),
            surface = scheme.surfaceVariant.toArgb(),
            divider = scheme.outlineVariant.toArgb(),
            accent = scheme.primary.toArgb(),
            accentForeground = scheme.onPrimary.toArgb(),
            isDark = scheme.background.luminance() < 0.5f,
        )
    }
    val activeAlreadyMined = linkedPages.isEmpty() && alreadyMined
    val popupHtml = remember(currentPage, activeAlreadyMined, expansion, language, pageColors) {
        currentPage?.let { page ->
            YomitanPopupHtml.render(
                page.result, page.metadata, page.subtitleContext, pageColors, language,
                alreadyMined = activeAlreadyMined, expansion = expansion,
                showExpandAction = true, showBackAction = linkedPages.isNotEmpty(),
            )
        }
    }
    val fullPageHtml = remember(currentPage, activeAlreadyMined, expansion, language, pageColors) {
        currentPage?.let { page ->
            YomitanPopupHtml.render(
                page.result, page.metadata, page.subtitleContext, pageColors, language,
                alreadyMined = activeAlreadyMined, expansion = expansion, showExpandAction = false,
            )
        }
    }
    val onLookupWord: (String, Int) -> Unit = { text, offset ->
        val requestVersion = ++lookupRequestVersion
        lookupScope.launch {
            val nextPage = withContext(Dispatchers.IO) {
                scanYomitanSubtitle(text, offset, dictionaries)?.let { match ->
                    YomitanLookupPage(match, loadYomitanMetadata(match, dictionaries), text)
                }
            }
            if (requestVersion == lookupRequestVersion && nextPage != null) {
                linkedPages = linkedPages + nextPage
            }
        }
    }
    val goBack: () -> Unit = {
        lookupRequestVersion++
        if (linkedPages.isNotEmpty()) linkedPages = linkedPages.dropLast(1)
        else showFullPage = false
    }
    BackHandler(enabled = !showFullPage && (result != null || message != null)) {
        if (linkedPages.isNotEmpty()) goBack() else onDismiss()
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
                val anchorX = if (anchorInPlayer == androidx.compose.ui.geometry.Offset.Zero) {
                    anchorBounds.left + anchorBounds.width / 2
                } else {
                    anchorBounds.left + anchorInPlayer.x.roundToInt()
                }
                val anchorY = if (anchorInPlayer == androidx.compose.ui.geometry.Offset.Zero) {
                    (anchorBounds.top + anchorBounds.height / 2)
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

        if (popupHtml != null) {
            AnchoredYomitanPopup(
                pageHtml = popupHtml,
                popupWidth = popupWidth,
                popupHeight = popupHeight,
                anchorInPlayer = anchorInPlayer,
                isFullScreen = showFullPage,
                entries = entries,
                lookupResult = currentPage?.result,
                metadata = currentPage?.metadata.orEmpty(),
                onDismiss = onDismiss,
                onExpand = { showFullPage = true },
                onBack = goBack,
                onLookupWord = onLookupWord,
                onPlayAudio = onPlayAudio,
                onMine = onMine,
            )
        } else {
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = onDismiss,
                properties = PopupProperties(
                    focusable = false,
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                    clippingEnabled = true,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .background(scheme.surface.copy(alpha = 0.96f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(message ?: "No match.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }

    if (showFullPage && fullPageHtml != null && currentPage != null) {
        FullScreenYomitanLookup(
            pageHtml = fullPageHtml,
            title = currentPage.result.entries.firstOrNull()?.expression
                ?.takeIf(String::isNotBlank)
                ?: currentPage.result.surfaceText,
            entries = entries,
            lookupResult = currentPage.result,
            metadata = currentPage.metadata,
            onBack = goBack,
            onDismiss = onDismiss,
            onExpand = {},
            onLookupWord = onLookupWord,
            onPlayAudio = onPlayAudio,
            onMine = onMine,
        )
    }
}

@Composable
private fun FullScreenYomitanLookup(
    pageHtml: String,
    title: String,
    entries: List<DictionaryEntry>,
    lookupResult: SubtitleScanResult?,
    metadata: List<DictionaryTermMeta>,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    onExpand: () -> Unit,
    onLookupWord: (String, Int) -> Unit,
    onPlayAudio: (DictionaryEntry) -> Unit,
    onMine: (DictionaryEntry, SubtitleScanResult, List<DictionaryTermMeta>) -> Unit,
) {
    val webView = rememberYomitanWebView(
        entries = entries,
        lookupResult = lookupResult,
        metadata = metadata,
        onDismiss = onDismiss,
        onExpand = onExpand,
        onBack = onBack,
        onLookupWord = onLookupWord,
        onPlayAudio = onPlayAudio,
        onMine = onMine,
    )

    LaunchedEffect(webView, pageHtml) { webView.loadYomitanPage(pageHtml) }
    DisposableEffect(webView) {
        onDispose { destroyYomitanWebView(webView) }
    }

    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = localizeUiText("Back"),
                        )
                    }
                    Text(
                        title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AndroidView(
                    factory = { webView },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}

@Composable
private fun AnchoredYomitanPopup(
    pageHtml: String,
    popupWidth: androidx.compose.ui.unit.Dp,
    popupHeight: androidx.compose.ui.unit.Dp,
    anchorInPlayer: androidx.compose.ui.geometry.Offset,
    isFullScreen: Boolean,
    entries: List<DictionaryEntry>,
    lookupResult: SubtitleScanResult?,
    metadata: List<DictionaryTermMeta>,
    onDismiss: () -> Unit,
    onExpand: () -> Unit,
    onBack: () -> Unit,
    onLookupWord: (String, Int) -> Unit,
    onPlayAudio: (DictionaryEntry) -> Unit,
    onMine: (DictionaryEntry, SubtitleScanResult, List<DictionaryTermMeta>) -> Unit,
) {
    val hostView = LocalView.current
    val density = LocalDensity.current
    val popupWidthPx = with(density) { popupWidth.roundToPx() }
    val popupHeightPx = with(density) { popupHeight.roundToPx() }
    val webView = rememberYomitanWebView(
        entries = entries,
        lookupResult = lookupResult,
        metadata = metadata,
        onDismiss = onDismiss,
        onExpand = onExpand,
        onBack = onBack,
        onLookupWord = onLookupWord,
        onPlayAudio = onPlayAudio,
        onMine = onMine,
    )
    val popupWindow = remember(webView) {
        PopupWindow(webView, popupWidthPx.coerceAtLeast(1), popupHeightPx.coerceAtLeast(1), false).apply {
            isClippingEnabled = true
            isOutsideTouchable = false
            setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())
            animationStyle = 0
        }
    }
    val latestDismiss = rememberUpdatedState(onDismiss)
    val latestIsFullScreen = rememberUpdatedState(isFullScreen)

    DisposableEffect(popupWindow, webView) {
        popupWindow.setOnDismissListener {
            if (!latestIsFullScreen.value) latestDismiss.value()
        }
        onDispose {
            popupWindow.setOnDismissListener(null)
            if (popupWindow.isShowing) popupWindow.dismiss()
            destroyYomitanWebView(webView)
        }
    }

    LaunchedEffect(isFullScreen, pageHtml, popupWidthPx, popupHeightPx, anchorInPlayer, hostView) {
        if (isFullScreen) {
            if (popupWindow.isShowing) popupWindow.dismiss()
            return@LaunchedEffect
        }
        webView.loadYomitanPage(pageHtml)

        val rootView = hostView.rootView
        if (popupWidthPx <= 0 || popupHeightPx <= 0 || rootView.width <= 0 || rootView.height <= 0) {
            return@LaunchedEffect
        }

        val hostLocation = IntArray(2)
        hostView.getLocationInWindow(hostLocation)
        val margin = with(density) { 12.dp.roundToPx() }
        val windowWidth = rootView.width
        val windowHeight = rootView.height
        val anchorX = if (anchorInPlayer == androidx.compose.ui.geometry.Offset.Zero) {
            hostLocation[0] + hostView.width / 2
        } else {
            hostLocation[0] + anchorInPlayer.x.roundToInt()
        }
        val anchorY = if (anchorInPlayer == androidx.compose.ui.geometry.Offset.Zero) {
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun rememberYomitanWebView(
    entries: List<DictionaryEntry>,
    lookupResult: SubtitleScanResult?,
    metadata: List<DictionaryTermMeta>,
    onDismiss: () -> Unit,
    onExpand: () -> Unit,
    onBack: () -> Unit,
    onLookupWord: (String, Int) -> Unit,
    onPlayAudio: (DictionaryEntry) -> Unit,
    onMine: (DictionaryEntry, SubtitleScanResult, List<DictionaryTermMeta>) -> Unit,
): WebView {
    val context = LocalContext.current
    val latestEntries = rememberUpdatedState(entries)
    val latestLookupResult = rememberUpdatedState(lookupResult)
    val latestMetadata = rememberUpdatedState(metadata)
    val latestDismiss = rememberUpdatedState(onDismiss)
    val latestExpand = rememberUpdatedState(onExpand)
    val latestBack = rememberUpdatedState(onBack)
    val latestLookupWord = rememberUpdatedState(onLookupWord)
    val latestPlayAudio = rememberUpdatedState(onPlayAudio)
    val latestMine = rememberUpdatedState(onMine)

    return remember(context) {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = false
            // This view loads only app-generated HTML; network and local file access stay disabled.
            settings.apply {
                javaScriptEnabled = true
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
            addJavascriptInterface(object {
                @JavascriptInterface
                @Suppress("unused")
                fun lookupWord(text: String?, offset: Int) {
                    val line = text?.takeIf { it.isNotBlank() && it.length <= MAX_LOOKUP_LINE_LENGTH } ?: return
                    if (offset !in line.indices) return
                    Handler(Looper.getMainLooper()).post {
                        latestLookupWord.value(line, offset)
                    }
                }

                @JavascriptInterface
                @Suppress("unused")
                fun mine(index: Int) {
                    Handler(Looper.getMainLooper()).post {
                        val entry = latestEntries.value.getOrNull(index) ?: return@post
                        val lookupResult = latestLookupResult.value ?: return@post
                        latestMine.value(entry, lookupResult, latestMetadata.value)
                    }
                }
            }, "NekoLookup")
            webViewClient = object : WebViewClient() {
                private fun handleLookupLink(rawUrl: String?) {
                    val action = runCatching { rawUrl?.toUri() }.getOrNull() ?: return
                    if (action.scheme != "nekoplayer") return
                    when (action.host) {
                        "close" -> latestDismiss.value()
                        "expand" -> latestExpand.value()
                        "back" -> latestBack.value()
                        "audio", "mine" -> {
                            val index = action.lastPathSegment?.toIntOrNull() ?: return
                            val entry = latestEntries.value.getOrNull(index) ?: return
                            if (action.host == "audio") latestPlayAudio.value(entry)
                            else latestLookupResult.value?.let { lookupResult ->
                                latestMine.value(entry, lookupResult, latestMetadata.value)
                            }
                        }
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest,
                ): Boolean {
                    handleLookupLink(request.url.toString())
                    return true
                }

                @Deprecated("Required for compatibility with older Android WebView versions")
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    url: String?,
                ): Boolean {
                    handleLookupLink(url)
                    return true
                }
            }
        }
    }
}

private const val MAX_LOOKUP_LINE_LENGTH = 8_000

private fun WebView.loadYomitanPage(pageHtml: String) {
    if (tag == pageHtml) return
    tag = pageHtml
    loadDataWithBaseURL(
        "https://nekoplayer.invalid/",
        pageHtml,
        "text/html",
        "UTF-8",
        null,
    )
}

private fun destroyYomitanWebView(webView: WebView) {
    webView.stopLoading()
    webView.webViewClient = WebViewClient()
    webView.loadUrl("about:blank")
    webView.removeAllViews()
    webView.destroy()
}
