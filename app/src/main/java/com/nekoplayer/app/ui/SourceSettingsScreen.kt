package com.nekoplayer.app.ui

import android.content.Context
import android.annotation.SuppressLint
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SeekBarPreference
import androidx.preference.TwoStatePreference
import com.nekoplayer.app.extensions.ExtensionManager
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.preferenceKey

private data class SourceSettingsSection(
    val title: String?,
    val preferences: List<Preference>,
)

private sealed interface SourceSettingsContent {
    data class Ready(
        val preferenceManager: PreferenceManager,
        val sections: List<SourceSettingsSection>,
    ) : SourceSettingsContent

    data class Failed(val message: String) : SourceSettingsContent
}

@SuppressLint("RestrictedApi")
@Composable
internal fun SourceSettingsScreen(
    item: ExtensionManager.InstalledVideoSource,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val content = remember(context, item.source) {
        try {
            val preferenceManager = PreferenceManager(context).apply {
                setSharedPreferencesName(item.source.preferenceKey())
                setSharedPreferencesMode(Context.MODE_PRIVATE)
            }
            val screen = preferenceManager.createPreferenceScreen(context)
            (item.source as? ConfigurableAnimeSource)?.setupPreferenceScreen(screen)
            SourceSettingsContent.Ready(
                preferenceManager = preferenceManager,
                sections = collectSourceSettings(screen),
            )
        } catch (error: LinkageError) {
            SourceSettingsContent.Failed(
                error.message?.lineSequence()?.firstOrNull()?.take(180)
                    ?: "This source’s settings are incompatible with the installed extension.",
            )
        } catch (error: Exception) {
            SourceSettingsContent.Failed(
                error.message?.lineSequence()?.firstOrNull()?.take(180)
                    ?: "The source did not provide editable settings.",
            )
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = localizeUiText("Back"))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    item.source.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.extension.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        when (content) {
            is SourceSettingsContent.Failed -> {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Couldn’t load source settings", style = MaterialTheme.typography.titleSmall)
                        Text(
                            content.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            is SourceSettingsContent.Ready -> {
                val sections = content.sections
                if (sections.isEmpty()) {
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("No source settings", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "This source does not expose any options that can be changed here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        sections.forEachIndexed { sectionIndex, section ->
                            if (!section.title.isNullOrBlank()) {
                                item(key = "section:$sectionIndex") {
                                    Text(
                                        section.title,
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                }
                            }
                            itemsIndexed(
                                items = section.preferences,
                                key = { index, preference ->
                                    "source-pref:$sectionIndex:$index:${preference.key.orEmpty()}"
                                },
                                contentType = { _, _ -> "source_preference" },
                            ) { _, preference ->
                                SourcePreferenceControl(preference)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun collectSourceSettings(screen: PreferenceScreen): List<SourceSettingsSection> {
    val sections = linkedMapOf<String?, MutableList<Preference>>()

    fun visit(group: PreferenceGroup, currentSection: String?) {
        for (index in 0 until group.preferenceCount) {
            val preference = group.getPreference(index)
            if (!preference.isVisible) continue
            when (preference) {
                is PreferenceCategory -> visit(
                    preference,
                    preference.title?.toString()?.takeIf(String::isNotBlank) ?: currentSection,
                )
                is PreferenceScreen -> visit(preference, currentSection)
                is PreferenceGroup -> visit(preference, currentSection)
                is ListPreference,
                is TwoStatePreference,
                is EditTextPreference,
                is MultiSelectListPreference,
                is SeekBarPreference,
                -> sections.getOrPut(currentSection) { mutableListOf() }.add(preference)
            }
        }
    }

    visit(screen, null)
    return sections.mapNotNull { (title, preferences) ->
        preferences.takeIf(List<Preference>::isNotEmpty)?.let { SourceSettingsSection(title, it) }
    }
}

@Composable
private fun SourcePreferenceControl(preference: Preference) {
    when (preference) {
        is ListPreference -> ListPreferenceControl(preference)
        is TwoStatePreference -> TwoStatePreferenceControl(preference)
        is EditTextPreference -> EditTextPreferenceControl(preference)
        is MultiSelectListPreference -> MultiSelectPreferenceControl(preference)
        is SeekBarPreference -> SeekBarPreferenceControl(preference)
    }
}

@Composable
private fun ListPreferenceControl(preference: ListPreference) {
    val entries = preference.entries.orEmpty()
    val values = preference.entryValues.orEmpty()
    var selectedValue by remember(preference) { mutableStateOf(preference.value.orEmpty()) }
    var showOptions by remember(preference) { mutableStateOf(false) }
    val selectedIndex = values.indexOfFirst { it.toString() == selectedValue }
    val selectedLabel = entries.getOrNull(selectedIndex)?.toString()
        ?: selectedValue.takeIf(String::isNotBlank)
        ?: preference.summary?.toString()

    PreferenceCard(
        title = preference.title?.toString() ?: preference.key.orEmpty(),
        detail = selectedLabel,
        enabled = preference.isEnabled && entries.isNotEmpty() && values.isNotEmpty(),
        onClick = { showOptions = true },
        trailing = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )

    if (showOptions) {
        AlertDialog(
            onDismissRequest = { showOptions = false },
            title = { Text(preference.title?.toString() ?: "Choose an option") },
            text = {
                LazyColumn(
                    Modifier.heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(entries.toList()) { index, entry ->
                        val value = values.getOrNull(index)?.toString() ?: return@itemsIndexed
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (preference.callChangeListener(value)) {
                                    preference.value = value
                                    selectedValue = value
                                    showOptions = false
                                }
                            }.padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = value == selectedValue,
                                onClick = {
                                    if (preference.callChangeListener(value)) {
                                        preference.value = value
                                        selectedValue = value
                                        showOptions = false
                                    }
                                },
                            )
                            Text(entry.toString(), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showOptions = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TwoStatePreferenceControl(preference: TwoStatePreference) {
    var checked by remember(preference) { mutableStateOf(preference.isChecked) }
    PreferenceCard(
        title = preference.title?.toString() ?: preference.key.orEmpty(),
        detail = preference.summary?.toString(),
        enabled = preference.isEnabled,
        onClick = {
            val updated = !checked
            if (preference.callChangeListener(updated)) {
                preference.isChecked = updated
                checked = updated
            }
        },
        trailing = {
            Switch(
                checked = checked,
                enabled = preference.isEnabled,
                onCheckedChange = { updated ->
                    if (preference.callChangeListener(updated)) {
                        preference.isChecked = updated
                        checked = updated
                    }
                },
            )
        },
    )
}

@Composable
private fun EditTextPreferenceControl(preference: EditTextPreference) {
    var text by remember(preference) { mutableStateOf(preference.text.orEmpty()) }
    var draft by remember(preference) { mutableStateOf(text) }
    var showEditor by remember(preference) { mutableStateOf(false) }
    PreferenceCard(
        title = preference.title?.toString() ?: preference.key.orEmpty(),
        detail = text.takeIf(String::isNotBlank) ?: preference.summary?.toString(),
        enabled = preference.isEnabled,
        onClick = {
            draft = text
            showEditor = true
        },
        trailing = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )

    if (showEditor) {
        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = { Text(preference.title?.toString() ?: "Edit value") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (preference.callChangeListener(draft)) {
                            preference.text = draft
                            text = draft
                            showEditor = false
                        }
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showEditor = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun MultiSelectPreferenceControl(preference: MultiSelectListPreference) {
    val entries = preference.entries.orEmpty()
    val values = preference.entryValues.orEmpty()
    var selectedValues by remember(preference) { mutableStateOf(preference.values.toSet()) }
    var draft by remember(preference) { mutableStateOf(selectedValues) }
    var showOptions by remember(preference) { mutableStateOf(false) }
    val selectedLabels = entries.indices.mapNotNull { index ->
        val value = values.getOrNull(index)?.toString() ?: return@mapNotNull null
        entries[index].toString().takeIf { value in selectedValues }
    }.joinToString()

    PreferenceCard(
        title = preference.title?.toString() ?: preference.key.orEmpty(),
        detail = selectedLabels.ifBlank { preference.summary?.toString() },
        enabled = preference.isEnabled && entries.isNotEmpty() && values.isNotEmpty(),
        onClick = {
            draft = selectedValues
            showOptions = true
        },
        trailing = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )

    if (showOptions) {
        AlertDialog(
            onDismissRequest = { showOptions = false },
            title = { Text(preference.title?.toString() ?: "Choose options") },
            text = {
                LazyColumn(
                    Modifier.heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(entries.toList()) { index, entry ->
                        val value = values.getOrNull(index)?.toString() ?: return@itemsIndexed
                        val selected = value in draft
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                draft = if (selected) draft - value else draft + value
                            }.padding(vertical = 4.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = selected,
                                onCheckedChange = { checked ->
                                    draft = if (checked) draft + value else draft - value
                                },
                            )
                            Text(entry.toString(), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (preference.callChangeListener(draft)) {
                            preference.values = draft
                            selectedValues = draft
                            showOptions = false
                        }
                    },
                ) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { showOptions = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SeekBarPreferenceControl(preference: SeekBarPreference) {
    var sliderValue by remember(preference) { mutableFloatStateOf(preference.value.toFloat()) }
    val min = preference.min.toFloat()
    val max = preference.max.toFloat()
    val range = min..max.coerceAtLeast(min + 1f)

    PreferenceCard(
        title = preference.title?.toString() ?: preference.key.orEmpty(),
        detail = preference.summary?.toString() ?: sliderValue.toInt().toString(),
        enabled = preference.isEnabled,
        onClick = null,
        trailing = { Text(sliderValue.toInt().toString(), color = MaterialTheme.colorScheme.onSurfaceVariant) },
        extra = {
            Slider(
                value = sliderValue.coerceIn(range),
                onValueChange = { next ->
                    sliderValue = next
                    if (preference.updatesContinuously) {
                        persistSeekBarValue(preference, next.toInt()) { sliderValue = it.toFloat() }
                    }
                },
                onValueChangeFinished = {
                    persistSeekBarValue(preference, sliderValue.toInt()) { sliderValue = it.toFloat() }
                },
                valueRange = range,
                enabled = preference.isEnabled,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        },
    )
}

private fun persistSeekBarValue(
    preference: SeekBarPreference,
    value: Int,
    onRejected: (Int) -> Unit,
) {
    if (preference.callChangeListener(value)) {
        preference.value = value
    } else {
        onRejected(preference.value)
    }
}

@Composable
private fun PreferenceCard(
    title: String,
    detail: String?,
    enabled: Boolean,
    onClick: (() -> Unit)?,
    trailing: @Composable () -> Unit,
    extra: @Composable (() -> Unit)? = null,
) {
    Card(
        onClick = onClick ?: {},
        enabled = enabled && onClick != null,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!detail.isNullOrBlank()) {
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.size(12.dp))
                trailing()
            }
            extra?.invoke()
        }
    }
}
