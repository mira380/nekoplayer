package com.nekoplayer.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun PlayerSubtitleTimingDialog(
    offsetDraft: Long,
    offsetInput: String,
    onOffsetChange: (Long, String) -> Unit,
    onOffsetInputChange: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    onApply: (Long) -> Unit,
) {
    val validOffset = offsetInput.toLongOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Subtitle timing") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(formatSubtitleOffset(offsetDraft), style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = offsetDraft.coerceIn(-10_000L, 10_000L).toFloat(),
                    onValueChange = { value ->
                        val snapped = ((value / 100f).roundToInt() * 100L).coerceIn(-10_000L, 10_000L)
                        onOffsetChange(snapped, snapped.toString())
                    },
                    valueRange = -10_000f..10_000f,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("−10 s", style = MaterialTheme.typography.labelSmall)
                    Text("+10 s", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedTextField(
                    value = offsetInput,
                    onValueChange = onOffsetInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Offset in milliseconds") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    singleLine = true,
                )
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        confirmButton = {
            TextButton(
                enabled = validOffset != null,
                onClick = { validOffset?.let(onApply) },
            ) { Text("Apply") }
        },
    )
}
