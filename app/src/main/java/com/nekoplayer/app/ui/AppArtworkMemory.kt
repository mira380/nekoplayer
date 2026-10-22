package com.nekoplayer.app.ui

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private val artworkLoadSlots = Semaphore(3)

internal suspend fun <T> withArtworkLoadSlot(block: suspend () -> T): T =
    artworkLoadSlots.withPermit { block() }

internal fun trimArtworkCaches(aggressive: Boolean) {
    trimLibraryArtworkCache(aggressive)
    trimSourceArtworkCache(aggressive)
    trimExtensionIconCache(aggressive)
}
