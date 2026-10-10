// Adapted from Watchbox-Android (GPL-3.0), https://github.com/Nicartjay/Watchbox-Android.
// Modified for nekoplayer; see the repository LICENSE.

package eu.kanade.tachiyomi.animesource.model

/**
 * One page of browse/search results.
 *
 * See the note in [SAnime] for why this package reproduces the Aniyomi ABI.
 */
data class AnimesPage(val animes: List<SAnime>, val hasNextPage: Boolean)
