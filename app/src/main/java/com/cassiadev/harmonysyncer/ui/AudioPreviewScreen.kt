package com.cassiadev.harmonysyncer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cassiadev.harmonysyncer.R
import com.cassiadev.harmonysyncer.audio.ClipInfo
import com.cassiadev.harmonysyncer.audio.MixSettings
import com.cassiadev.harmonysyncer.audio.PreviewUiState
import com.cassiadev.harmonysyncer.ui.theme.HarmonySyncerTheme
import kotlin.math.roundToInt

/** Stable test handles that survive copy and layout changes. */
object AudioPreviewTags {
    const val SELECT_A = "selectA"
    const val SELECT_B = "selectB"
    const val NAME_A = "nameA"
    const val NAME_B = "nameB"
    const val VOLUME_B = "volumeB"
    const val OFFSET = "offset"
    const val PLAY = "play"
    const val STOP = "stop"
}

@Composable
fun AudioPreviewScreen(
    state: PreviewUiState,
    onSelectA: () -> Unit,
    onSelectB: () -> Unit,
    onSettingsChange: (MixSettings) -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canEdit = state.canEdit
    val canPlay = state.canPlay

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.preview_intro), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.supported_audio),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AudioFileCard(
            title = stringResource(R.string.track_a),
            selectLabel = stringResource(R.string.select_track_a),
            clip = state.trackA,
            enabled = canEdit,
            onSelect = onSelectA,
            nameTag = AudioPreviewTags.NAME_A,
            selectTag = AudioPreviewTags.SELECT_A,
        )
        AudioFileCard(
            title = stringResource(R.string.track_b),
            selectLabel = stringResource(R.string.select_track_b),
            clip = state.trackB,
            enabled = canEdit,
            onSelect = onSelectB,
            nameTag = AudioPreviewTags.NAME_B,
            selectTag = AudioPreviewTags.SELECT_B,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.mix_controls), style = MaterialTheme.typography.titleMedium)
                VolumeControl(
                    label = stringResource(R.string.volume_a),
                    value = state.settings.volumeA,
                    enabled = canEdit,
                    onChange = { onSettingsChange(state.settings.copy(volumeA = it)) },
                )
                VolumeControl(
                    label = stringResource(R.string.volume_b),
                    value = state.settings.volumeB,
                    enabled = canEdit,
                    onChange = { onSettingsChange(state.settings.copy(volumeB = it)) },
                    modifier = Modifier.testTag(AudioPreviewTags.VOLUME_B),
                )
                val offsetLabel = stringResource(R.string.offset_b)
                val offsetValue = stringResource(
                    R.string.offset_value,
                    if (state.settings.offsetMs > 0) "+${state.settings.offsetMs}" else "${state.settings.offsetMs}",
                )
                ControlLabel(offsetLabel, offsetValue)
                Slider(
                    value = state.settings.offsetMs.toFloat(),
                    onValueChange = { onSettingsChange(state.settings.copy(offsetMs = it.roundToInt())) },
                    valueRange = -300f..300f,
                    steps = 599,
                    enabled = canEdit,
                    modifier = Modifier.testTag(AudioPreviewTags.OFFSET).semantics {
                        contentDescription = offsetLabel
                        stateDescription = offsetValue
                    },
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.offset_earlier), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.offset_later), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { onSettingsChange(state.settings.copy(offsetMs = 0)) },
                    enabled = canEdit && state.settings.offsetMs != 0,
                ) { Text(stringResource(R.string.reset_offset)) }
                Text(
                    stringResource(R.string.mix_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            stringResource(
                when {
                    state.isLoading -> R.string.status_loading
                    state.isPlaying -> R.string.status_playing
                    canPlay -> R.string.status_ready
                    else -> R.string.status_select_files
                },
            ),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        state.error?.let { error ->
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onPlay, enabled = canPlay, modifier = Modifier.weight(1f).testTag(AudioPreviewTags.PLAY)) {
                Text(stringResource(R.string.play_preview))
            }
            OutlinedButton(
                onClick = onStop,
                enabled = state.isPlaying,
                modifier = Modifier.weight(1f).testTag(AudioPreviewTags.STOP),
            ) {
                Text(stringResource(R.string.stop_preview))
            }
        }
        Text(
            stringResource(R.string.restart_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AudioFileCard(
    title: String,
    selectLabel: String,
    clip: ClipInfo?,
    enabled: Boolean,
    onSelect: () -> Unit,
    nameTag: String,
    selectTag: String,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(clip?.name ?: stringResource(R.string.no_file_selected), modifier = Modifier.testTag(nameTag))
            if (clip != null) {
                Text(
                    stringResource(R.string.clip_details, clip.durationSeconds, clip.sampleRate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onSelect, enabled = enabled, modifier = Modifier.testTag(selectTag)) {
                Text(selectLabel)
            }
        }
    }
}

@Composable
private fun VolumeControl(
    label: String,
    value: Float,
    enabled: Boolean,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val percentage = stringResource(R.string.volume_value, (value * 100).roundToInt())
    ControlLabel(label, percentage)
    Slider(
        value = value,
        onValueChange = { onChange((it * 100).roundToInt() / 100f) },
        steps = 99,
        enabled = enabled,
        modifier = modifier.semantics {
            contentDescription = label
            stateDescription = percentage
        },
    )
}

@Composable
private fun ControlLabel(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, style = MaterialTheme.typography.labelLarge)
    }
}

@Preview(showBackground = true)
@Composable
private fun AudioPreviewScreenPreview() {
    HarmonySyncerTheme {
        AudioPreviewScreen(
            state = PreviewUiState(
                trackA = ClipInfo("Soprano.wav", 48_000, 384_000),
                trackB = ClipInfo("Alto.wav", 48_000, 384_000),
            ),
            onSelectA = {}, onSelectB = {}, onSettingsChange = {}, onPlay = {}, onStop = {},
        )
    }
}
