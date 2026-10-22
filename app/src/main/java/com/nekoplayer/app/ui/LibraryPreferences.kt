package com.nekoplayer.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue

import com.nekoplayer.app.data.AppDatabase

import java.util.Locale

internal fun readLibraryGroups(database: AppDatabase): List<String> {
    val stored = (database.readSetting("library.groups") as? org.json.JSONArray) ?: return emptyList()
    return (0 until stored.length()).asSequence()
        .mapNotNull { index -> stored.optString(index).trim().takeIf(String::isNotEmpty) }
        .distinctBy { it.lowercase(java.util.Locale.ROOT) }
        .toList()
}

internal fun readCollapsedLibraryGroups(database: AppDatabase): Set<String> {
    val stored = (database.readSetting("library.collapsedGroups") as? org.json.JSONArray)
        ?: return emptySet()
    return (0 until stored.length()).asSequence()
        .mapNotNull { index -> stored.optString(index).takeIf(String::isNotBlank) }
        .toSet()
}

internal fun writeCollapsedLibraryGroups(database: AppDatabase, collapsedGroups: Set<String>) {
    database.writeSetting(
        "library.collapsedGroups",
        org.json.JSONArray(collapsedGroups.filter(String::isNotBlank).sorted()),
    )
}

internal fun readLibraryGroupAssignments(database: AppDatabase): Map<String, Set<String>> {
    val stored = (database.readSetting("library.groupAssignments") as? org.json.JSONObject) ?: return emptyMap()
    val canonicalGroups = readLibraryGroups(database).associateBy { it.lowercase(java.util.Locale.ROOT) }
    return buildMap {
        val keys = stored.keys()
        while (keys.hasNext()) {
            val itemKey = keys.next()
            val assigned = stored.optJSONArray(itemKey) ?: continue
            val names = (0 until assigned.length()).asSequence()
                .mapNotNull { index ->
                    assigned.optString(index).trim().takeIf(String::isNotEmpty)?.let { name ->
                        canonicalGroups[name.lowercase(java.util.Locale.ROOT)] ?: name
                    }
                }
                .distinctBy { it.lowercase(java.util.Locale.ROOT) }
                .toSet()
            if (names.isNotEmpty()) put(itemKey, names)
        }
    }
}

internal fun localLibraryGroupKey(showName: String): String =
    "local:${showName.trim().lowercase(java.util.Locale.ROOT)}"

internal fun sourceLibraryGroupKey(entry: AppDatabase.SourceLibraryEntry): String =
    "source:${entry.key}"
