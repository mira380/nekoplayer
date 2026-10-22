package com.nekoplayer.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi

import com.nekoplayer.app.media.SubtitleTrack

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
internal fun TrackDialog(
    title: String,
    tracks: Tracks,
    kind: String,
    externalTracks: List<SubtitleTrack>,
    selectedExternalTrackId: String?,
    useExternal: Boolean,
    embeddedEnabled: Boolean,
    onExternal: (SubtitleTrack) -> Unit,
    onSelect: (Int, Tracks.Group, Int) -> Unit,
    onOff: () -> Unit,
    onDismiss: () -> Unit,
) {
    val trackType = if (kind == "audio") C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
    val groups = tracks.groups.filter { it.type == trackType }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn {
                if (kind == "subtitle") {
                    item {
                        TextButton(onClick = { onOff() }, modifier = Modifier.fillMaxWidth()) {
                            Text("${if (!useExternal && !embeddedEnabled) "✓  " else ""}${localizeUiText("Subtitles off")}", modifier = Modifier.fillMaxWidth())
                        }
                    }
                    items(externalTracks, key = SubtitleTrack::id) { track ->
                        TextButton(onClick = { onExternal(track) }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "${if (track.id == selectedExternalTrackId) "✓  " else ""}${track.name} (${localizeUiText("external")})",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                items(groups.flatMap { group -> (0 until group.length).map { group to it } }) { (group, index) ->
                    val format = group.mediaTrackGroup.getFormat(index)
                    val label = listOfNotNull(format.label?.takeIf(String::isNotBlank), format.language?.takeIf(String::isNotBlank))
                        .distinct().joinToString(" · ").ifBlank { "Track ${index + 1}" }
                    val selected = group.isTrackSelected(index)
                    TextButton(onClick = { onSelect(trackType, group, index) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${if (selected) "✓  " else ""}${localizeUiText(label)}", modifier = Modifier.fillMaxWidth())
                    }
                }
                if (groups.isEmpty() && (kind == "audio" || externalTracks.isEmpty())) {
                    item { Text("No ${if (kind == "audio") "audio" else "subtitle"} tracks found.") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
