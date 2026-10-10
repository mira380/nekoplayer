// Adapted from Watchbox-Android (GPL-3.0), https://github.com/Nicartjay/Watchbox-Android.
// Modified for nekoplayer; see the repository LICENSE.

package com.nekoplayer.app.extensions.loader

import dalvik.system.PathClassLoader

/**
 * Parent-last classloader for extension APKs with host-shared API namespaces.
 *
 * Source API, dependency-injection, and network types cross the host/extension
 * boundary. Those classes must have one defining classloader so source methods
 * receive the host's clients and Injekt registry. Extension implementation
 * classes and private libraries still resolve from the extension APK first.
 *
 * Platform classes are always resolved from the system loader.
 */
internal class ChildFirstClassLoader(
    dexPath: String,
    librarySearchPath: String?,
    private val host: ClassLoader,
) : PathClassLoader(dexPath, librarySearchPath, host) {

    private val system: ClassLoader? = getSystemClassLoader()

    private val hostSharedPrefixes = listOf(
        "uy.kohesive.injekt.",
        "kotlin.",
        "kotlinx.coroutines.",
        "kotlinx.serialization.",
        "okhttp3.",
        "okio.",
        "rx.",
        "org.jsoup.",
        "androidx.preference.",
    )

    @Throws(ClassNotFoundException::class)
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        // Locking on the loader itself rather than a per-name lock: extensions
        // are loaded once at startup, so contention is not a concern and this
        // avoids reaching for ClassLoader's protected lock helper.
        synchronized(this) {
            findLoadedClass(name)?.let { return it }

            // Platform classes must never be overridable.
            system?.let { sys ->
                runCatching { return sys.loadClass(name) }
            }

            // Source ABI classes live in the host. Keep extension implementation
            // classes child-first: their package shares the tachiyomi namespace.
            val isHostSourceApi =
                name.startsWith("eu.kanade.tachiyomi.") &&
                    !name.startsWith("eu.kanade.tachiyomi.animeextension.")
            if (isHostSourceApi || hostSharedPrefixes.any(name::startsWith)) {
                runCatching { return host.loadClass(name) }
            }

            // Extension classes/private dependencies are isolated when present.
            runCatching { return findClass(name) }

            // Fall through to the host for the extension API and shared runtime.
            return host.loadClass(name)
        }
    }

    override fun getResource(name: String): java.net.URL? =
        system?.getResource(name)
            ?: findResource(name)
            ?: host.getResource(name)

    override fun getResources(name: String): java.util.Enumeration<java.net.URL> {
        val all = buildList {
            system?.getResources(name)?.let { addAll(it.toList()) }
            runCatching { addAll(findResources(name).toList()) }
            host.getResources(name)?.let { addAll(it.toList()) }
        }
        return java.util.Collections.enumeration(all)
    }
}
