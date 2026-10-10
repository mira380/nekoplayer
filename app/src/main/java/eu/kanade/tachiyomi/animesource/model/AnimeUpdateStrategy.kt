// Adapted from the Aniyomi extensions API.
// Modified for nekoplayer; see the repository LICENSE.

package eu.kanade.tachiyomi.animesource.model

/** Update behavior for an anime entry. */
enum class AnimeUpdateStrategy {
    ALWAYS_UPDATE,
    ONLY_FETCH_ONCE,
}
