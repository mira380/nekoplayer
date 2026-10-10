package com.nekoplayer.app.ui

import com.nekoplayer.app.data.AppDatabase
import org.json.JSONArray

internal data class DictionaryPopupExpansion(
    val mode: Mode = Mode.FIRST,
    val customDictionaryNames: Set<String> = emptySet(),
) {
    enum class Mode(val storedValue: String) {
        FIRST("first"),
        CUSTOM("custom"),
    }

    fun isExpanded(dictionaryName: String, groupIndex: Int): Boolean = when (mode) {
        Mode.FIRST -> groupIndex == 0
        Mode.CUSTOM -> dictionaryName in customDictionaryNames
    }

    companion object {
        const val MODE_SETTING = "dictionary.popup.autoExpandMode"
        const val DICTIONARIES_SETTING = "dictionary.popup.expandedDictionaries"

        fun load(database: AppDatabase): DictionaryPopupExpansion {
            val mode = Mode.entries.firstOrNull {
                it.storedValue == database.readString(MODE_SETTING, Mode.FIRST.storedValue)
            } ?: Mode.FIRST
            val names = (database.readSetting(DICTIONARIES_SETTING) as? JSONArray)
                ?.let { array ->
                    buildSet {
                        for (index in 0 until array.length()) {
                            array.optString(index).takeIf(String::isNotBlank)?.let(::add)
                        }
                    }
                }
                .orEmpty()
            return DictionaryPopupExpansion(mode, names)
        }
    }
}
