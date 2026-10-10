package com.nekoplayer.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nekoplayer.app.data.DictionaryEntry
import com.nekoplayer.app.data.DictionaryRepository
import com.nekoplayer.app.data.DictionaryTermMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun DictionaryScreen(
    repository: DictionaryRepository,
    refreshKey: Int,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<DictionaryEntry>()) }
    var metadata by remember { mutableStateOf(emptyList<DictionaryTermMeta>()) }
    var dictionaries by remember { mutableStateOf(emptyList<String>()) }
    var selectedDictionary by remember { mutableStateOf<String?>(null) }
    var dictionaryMenuExpanded by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshKey) {
        dictionaries = withContext(Dispatchers.IO) {
            repository.listDictionaries().filter { it.kind == "term" }.map { it.name }
        }
    }
    LaunchedEffect(query, selectedDictionary, refreshKey) {
        val term = query.trim()
        if (term.isEmpty()) {
            results = emptyList()
            metadata = emptyList()
            message = null
            searching = false
            return@LaunchedEffect
        }
        delay(220)
        searching = true
        try {
            val pair = withContext(Dispatchers.IO) {
                val hits = repository.search(term, 100)
                val meta = repository.termMeta(hits.flatMap { listOf(it.expression, it.reading) }.toSet())
                hits to meta
            }
            results = pair.first.filter { selectedDictionary == null || it.dictionaryName == selectedDictionary }
            metadata = pair.second
            message = null
        } catch (failure: Exception) {
            results = emptyList()
            metadata = emptyList()
            message = failure.message ?: "Dictionary search failed."
        } finally {
            searching = false
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { androidx.compose.material3.Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text("Search") },
        )
        if (dictionaries.isNotEmpty()) {
            Box(modifier = Modifier.padding(vertical = 6.dp)) {
                FilterChip(
                    selected = selectedDictionary != null,
                    onClick = { dictionaryMenuExpanded = true },
                    modifier = Modifier.widthIn(max = 280.dp),
                    label = {
                        Text(
                            selectedDictionary ?: "All dictionaries",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingIcon = {
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    },
                )
                DropdownMenu(
                    expanded = dictionaryMenuExpanded,
                    onDismissRequest = { dictionaryMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("All dictionaries") },
                        onClick = {
                            selectedDictionary = null
                            dictionaryMenuExpanded = false
                        },
                    )
                    dictionaries.forEach { name ->
                        DropdownMenuItem(
                            text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = {
                                selectedDictionary = name
                                dictionaryMenuExpanded = false
                            },
                        )
                    }
                }
            }
        }
        if (searching) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 2.dp))
        }
        message?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        val groups = results.groupBy { it.expression to it.reading }.toList()
        if (query.isNotBlank() && !searching && results.isEmpty() && message == null) {
            Text(
                "No matching entries.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 14.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(groups, key = { it.first.first + "\u001f" + it.first.second }) { (key, entries) ->
                DictionaryTermCard(
                    expression = key.first,
                    reading = key.second,
                    entries = entries,
                    metadata = metadata.filter {
                        it.expression == key.first || it.reading == key.first ||
                            it.expression == key.second || it.reading == key.second
                    },
                )
            }
        }
    }
}

@Composable
private fun DictionaryTermCard(
    expression: String,
    reading: String,
    entries: List<DictionaryEntry>,
    metadata: List<DictionaryTermMeta>,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    expression,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (reading.isNotBlank() && reading != expression) {
                    Text(
                        reading,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val pitchAccents = metadata.asSequence()
                .filter { it.mode.equals("pitch", ignoreCase = true) }
                .flatMap { item ->
                    PitchAccentGraph.parse(item.detailJson, item.reading.ifBlank { reading })
                        .asSequence()
                        .map { item.dictionaryName to it }
                }
                .distinctBy { (name, pattern) ->
                    name.lowercase() to (pattern.reading to (pattern.position to pattern.tags))
                }
                .take(8)
                .toList()
            if (pitchAccents.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Pitch accent",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(pitchAccents) { (dictionaryName, pattern) ->
                            PitchAccentGraph.Card(dictionaryName, pattern)
                        }
                    }
                }
            }

            val displayMetadata = metadata.filterNot { it.mode.equals("pitch", ignoreCase = true) }
            if (displayMetadata.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(displayMetadata.take(5)) { item ->
                        DictionaryBadge(dictionaryMetaLabel(item))
                    }
                }
            }

            entries.forEachIndexed { index, entry ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            entry.dictionaryName,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        val tags = entry.termTags.orEmpty() + entry.definitionTags.orEmpty()
                        if (tags.isNotEmpty()) {
                            Text(
                                tags.distinct().joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    YomitanGlossary(entry.glossary)
                }
            }
        }
    }
}

private fun dictionaryMetaLabel(meta: DictionaryTermMeta): String {
    val value = meta.display?.takeIf { it.isNotBlank() }
        ?: if (meta.mode == "freq") meta.sortValue.toString().removeSuffix(".0")
        else meta.detailJson?.takeIf { it.isNotBlank() }?.take(18)
        ?: meta.sortValue.toString().removeSuffix(".0")
    val label = when (meta.mode.lowercase()) {
        "freq" -> "Freq"
        "pitch" -> "Pitch"
        "ipa" -> "IPA"
        else -> meta.mode.replaceFirstChar { it.uppercase() }
    }
    return label + " " + value
}
