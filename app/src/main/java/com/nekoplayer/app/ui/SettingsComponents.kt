package com.nekoplayer.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text as Material3Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

import com.nekoplayer.app.mining.AnkiNoteFieldTemplates

import kotlin.math.roundToInt

@Composable
internal fun SettingsCategoryGroup(
    categories: List<String>,
    selected: (String) -> Unit,
    title: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        title?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
        }
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                categories.forEachIndexed { index, category ->
                    Row(
                        Modifier.fillMaxWidth().height(64.dp).clickable { selected(category) }.padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            categoryIcon(category),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                        Text(
                            category,
                            modifier = Modifier.weight(1f).padding(start = 16.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = localizeUiText("Open ${localizeUiText(category)}"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    if (index != categories.lastIndex) {
                        HorizontalDivider(Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun <T> AnkiChoiceRow(
    label: String,
    value: String,
    enabled: Boolean,
    expanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, onClick = onExpand),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Material3Text(value.ifBlank { localizeUiText("None") }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            TextButton(onClick = onExpand, enabled = enabled) {
                Text("Choose")
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Material3Text(optionLabel(option)) },
                        onClick = {
                            onDismiss()
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun AnkiSourceFieldMappingRow(
    source: AnkiSourceField,
    destination: String,
    options: List<String>,
    enabled: Boolean,
    menuExpanded: Boolean,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    onDestinationChange: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).clickable(enabled = enabled, onClick = onMenuOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(source.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Material3Text(
                destination.ifBlank { localizeUiText("Not mapped") },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            TextButton(onClick = onMenuOpen, enabled = enabled) {
                Text("Choose")
                Icon(Icons.Default.ArrowDropDown, contentDescription = localizeUiText("Choose ${localizeUiText(source.label)} field"))
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = onMenuDismiss) {
                DropdownMenuItem(
                    text = { Text("Not mapped") },
                    onClick = {
                        onMenuDismiss()
                        onDestinationChange("")
                    },
                )
                options.forEach { field ->
                    DropdownMenuItem(
                        text = { Material3Text(field) },
                        onClick = {
                            onMenuDismiss()
                            onDestinationChange(field)
                        },
                    )
                }
            }
        }
    }
}

internal fun removeAnkiTemplateToken(template: String, token: String): String {
    val remaining = Regex("\\{([^{}]+)\\}").replace(template) { match ->
        if (AnkiNoteFieldTemplates.containsToken(match.value, token)) "" else match.value
    }
    return remaining.replace(Regex("\\s+"), " ").trim()
}

@Composable
internal fun SettingSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        SettingCard(spacing = 10.dp, content = content)
    }
}

internal data class ThemePaletteOption(
    val label: String,
    val id: String,
    val background: Color,
    val surface: Color,
    val accent: Color,
)

@Composable
internal fun ThemePaletteRow(option: ThemePaletteOption, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            Modifier.size(width = 48.dp, height = 30.dp).clip(RoundedCornerShape(7.dp)).background(option.background),
        ) {
            Box(Modifier.weight(1f).fillMaxSize().padding(4.dp).clip(RoundedCornerShape(3.dp)).background(option.surface))
            Box(Modifier.weight(1f).fillMaxSize().padding(vertical = 4.dp, horizontal = 2.dp).clip(RoundedCornerShape(3.dp)).background(option.accent))
            Box(Modifier.weight(1f).fillMaxSize().padding(4.dp).clip(RoundedCornerShape(3.dp)).background(option.surface.copy(alpha = 0.72f)))
        }
        Text(option.label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable
internal fun AccentColorRow(label: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(28.dp)
                .then(if (selected) Modifier.border(2.dp, color, CircleShape).padding(3.dp) else Modifier)
                .clip(CircleShape)
                .background(color),
        )
        Text(label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
        RadioButton(selected = selected, onClick = null)
    }
}

private fun categoryIcon(category: String) = when (category) {
    "Anki" -> Icons.Default.Style
    "Dictionary settings" -> Icons.AutoMirrored.Filled.MenuBook
    "Appearance" -> Icons.Default.ColorLens
    "Playback" -> Icons.Default.PlayArrow
    "Subtitles" -> Icons.Default.Subtitles
    "Capture and audio" -> Icons.Default.GraphicEq
    "About and storage" -> Icons.Default.Info
    else -> Icons.Default.GraphicEq
}

@Composable
internal fun SettingCard(spacing: androidx.compose.ui.unit.Dp = 6.dp, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
        }
    }
}

@Composable
internal fun SettingSwitch(label: String, checked: Boolean, detail: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium)
            detail?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun SettingSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    snapStep: Float = 0f,
    onFinished: (Float) -> Unit,
) {
    fun snap(input: Float): Float = if (snapStep > 0f) snapToGrid(input, snapStep, range) else input
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(valueLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value,
            onValueChange = { onChange(snap(it)) },
            valueRange = range,
            onValueChangeFinished = { onFinished(snap(value)) },
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 10.dp),
            track = { sliderState ->
                val activeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.48f)
                val inactiveColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                val stopColor = MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxWidth().height(16.dp)) {
                    val trackHeight = size.height
                    val endRadius = 5.dp.toPx()
                    val cornerRadius = CornerRadius(endRadius, endRadius)
                    val progressWidth = size.width * sliderState.coercedValueAsFraction
                    drawRoundRect(
                        color = inactiveColor,
                        topLeft = Offset.Zero,
                        size = Size(size.width, trackHeight),
                        cornerRadius = cornerRadius,
                    )
                    if (progressWidth > 0f) {
                        drawRoundRect(
                            color = activeColor,
                            topLeft = Offset.Zero,
                            size = Size(progressWidth, trackHeight),
                            cornerRadius = cornerRadius,
                        )
                    }
                    drawCircle(
                        color = stopColor,
                        radius = 2.dp.toPx(),
                        center = Offset(size.width - endRadius, trackHeight / 2f),
                    )
                }
            },
        )
    }
}

internal fun snapToGrid(value: Float, step: Float, range: ClosedFloatingPointRange<Float>): Float {
    val bounded = value.coerceIn(range.start, range.endInclusive)
    if (step <= 0f) return bounded
    return ((bounded / step).roundToInt() * step).coerceIn(range.start, range.endInclusive)
}

internal fun audioSourceLabel(source: AudioSourceSetting, index: Int): String = when (source.kind) {
    "jpod101" -> "JapanesePod101"
    "language-pod-101" -> "Language Pod 101"
    "jisho" -> "Jisho"
    "custom-url" -> "Custom URL ${index + 1}"
    "custom-json" -> "Custom JSON ${index + 1}"
    else -> source.name.replaceFirstChar(Char::uppercase)
}
