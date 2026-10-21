package com.cassiadev.harmonysyncer.ui

import android.content.ActivityNotFoundException
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.cassiadev.harmonysyncer.R
import com.cassiadev.harmonysyncer.audio.AUDIO_MAX_DURATION_SECONDS
import com.cassiadev.harmonysyncer.audio.ClipInfo
import com.cassiadev.harmonysyncer.audio.MixSettings
import com.cassiadev.harmonysyncer.audio.PreviewPhase
import com.cassiadev.harmonysyncer.audio.PreviewUiState
import com.cassiadev.harmonysyncer.audio.TrackSlot
import com.cassiadev.harmonysyncer.ui.theme.DANGER_BORDER
import com.cassiadev.harmonysyncer.ui.theme.DANGER_SOFT
import com.cassiadev.harmonysyncer.ui.theme.HarmonySyncerTheme
import com.cassiadev.harmonysyncer.ui.theme.LocalHarmonyExtraColors
import com.cassiadev.harmonysyncer.ui.theme.tintOver
import kotlin.math.roundToInt

/** Stable test handles that survive copy and layout changes. */
object AudioPreviewTags {
    const val SELECT_A = "selectA"
    const val SELECT_B = "selectB"
    const val NAME_A = "nameA"
    const val NAME_B = "nameB"
    const val CARD_A = "cardA"
    const val CARD_B = "cardB"
    const val VOLUME_B = "volumeB"
    const val TIMING = "timing"
    const val OFFSET = "offset"
    const val PLAY = "play"
    const val STOP = "stop"
    const val SAMPLE = "sample"
    const val CREDIT = "credit"
}

// Dataset record with the CC BY 4.0 license for the bundled Locus iste excerpt.
private const val SAMPLE_SOURCE_URL = "https://zenodo.org/records/4607766"

/**
 * Start layout until both files are chosen, then the ready layout with volumes, timing and playback.
 * The layout is derived from [state] alone, so it survives recreation with the controller.
 */
@Composable
fun AudioPreviewScreen(
    state: PreviewUiState,
    onSelectA: () -> Unit,
    onSelectB: () -> Unit,
    onSettingsChange: (MixSettings) -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onLoadSample: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bothSelected = state.trackA != null && state.trackB != null
    var timingOpen by rememberSaveable { mutableStateOf(false) }
    // A restored flag must not reopen the sheet over the start layout or after editing unlocks.
    LaunchedEffect(bothSelected, state.canEdit) {
        if (!bothSelected || !state.canEdit) timingOpen = false
    }
    val phase = state.phase

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppHeader()
        Column(verticalArrangement = Arrangement.spacedBy(if (bothSelected) 6.dp else 8.dp)) {
            Text(
                stringResource(if (bothSelected) R.string.ready_title else R.string.start_title),
                style = MaterialTheme.typography.headlineSmall,
                // Design .ready-page h1 is smaller than the start headline.
                fontSize = if (bothSelected) 29.sp else TextUnit.Unspecified,
                lineHeight = if (bothSelected) 33.sp else TextUnit.Unspecified,
                letterSpacing = if (bothSelected) (-1.2).sp else TextUnit.Unspecified,
                modifier = Modifier.semantics { heading() },
            )
            if (!bothSelected) {
                Text(
                    stringResource(R.string.start_body),
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!bothSelected) PairRibbon(modifier = Modifier.align(Alignment.CenterHorizontally))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (slot in TrackSlot.entries) {
                TrackCard(
                    slot = slot,
                    clip = if (slot == TrackSlot.A) state.trackA else state.trackB,
                    loading = (phase is PreviewPhase.Loading && phase.slot == slot) ||
                        phase == PreviewPhase.LoadingSample,
                    error = state.error.takeIf { state.failedSlot == slot },
                    enabled = state.canEdit,
                    onSelect = if (slot == TrackSlot.A) onSelectA else onSelectB,
                    compact = bothSelected,
                )
            }
        }
        if (state.trackA?.isSample == true || state.trackB?.isSample == true) SampleCredit()
        // A failed load keeps the previous file of that slot and the other slot's file.
        if (state.failedSlot != null && (state.trackA != null || state.trackB != null)) {
            Notice(
                icon = R.drawable.ic_check,
                text = stringResource(R.string.kept_notice),
                iconColor = LocalHarmonyExtraColors.current.success,
            )
        }

        if (bothSelected) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 7.dp))
            Text(
                stringResource(R.string.volume),
                fontSize = 16.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.3).sp,
                modifier = Modifier.semantics { heading() },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                VolumeControl(
                    slot = TrackSlot.A,
                    value = state.settings.volumeA,
                    enabled = state.canEdit,
                    onChange = { onSettingsChange(state.settings.copy(volumeA = it)) },
                    modifier = Modifier.weight(1f),
                )
                VolumeControl(
                    slot = TrackSlot.B,
                    value = state.settings.volumeB,
                    enabled = state.canEdit,
                    onChange = { onSettingsChange(state.settings.copy(volumeB = it)) },
                    modifier = Modifier.weight(1f),
                    sliderModifier = Modifier.testTag(AudioPreviewTags.VOLUME_B),
                )
            }
            TimingRow(offsetMs = state.settings.offsetMs, enabled = state.canEdit, onClick = { timingOpen = true })
            if (state.isPlaying) {
                Notice(
                    icon = R.drawable.ic_lock,
                    text = stringResource(R.string.lock_notice),
                    iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val selectedCount = listOfNotNull(state.trackA, state.trackB).size
        StatusLine(
            // Design .pair-note: a check in Audio B's color once a file is added.
            showCheck = !bothSelected && selectedCount > 0 && phase == PreviewPhase.Idle,
            text = when (phase) {
                is PreviewPhase.Loading -> stringResource(
                    if (phase.slot == TrackSlot.A) R.string.status_loading_a else R.string.status_loading_b,
                )
                PreviewPhase.LoadingSample -> stringResource(R.string.status_loading_sample)
                PreviewPhase.Rendering -> stringResource(R.string.status_rendering)
                PreviewPhase.Playing -> stringResource(R.string.status_playing)
                PreviewPhase.Idle -> if (bothSelected) {
                    stringResource(R.string.status_ready)
                } else {
                    stringResource(R.string.selected_count, selectedCount)
                }
            },
        )
        if (state.error != null && state.failedSlot == null) ErrorBox(state.error)
        MainButton(state = state, bothSelected = bothSelected, onPlay = onPlay, onStop = onStop)
        if (!bothSelected) {
            TextButton(
                onClick = onLoadSample,
                enabled = state.canEdit,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .heightIn(min = 48.dp)
                    .testTag(AudioPreviewTags.SAMPLE),
            ) {
                Text(stringResource(R.string.try_sample), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.width(6.dp))
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        Text(
            if (bothSelected) {
                stringResource(R.string.replay_hint)
            } else {
                stringResource(R.string.supported_audio, AUDIO_MAX_DURATION_SECONDS)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // Editing locks while loading or playing, so the sheet closes with it and returns afterwards.
    if (timingOpen && bothSelected && state.canEdit) {
        TimingSheet(settings = state.settings, onSettingsChange = onSettingsChange, onDismiss = { timingOpen = false })
    }
}

/** CC BY 4.0 requires credit wherever the bundled recordings are played. */
@Composable
private fun SampleCredit() {
    val uriHandler = LocalUriHandler.current
    val openLabel = stringResource(R.string.open_sample_source)
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = openLabel) {
                // No browser (for example a restricted profile) must not crash the app.
                try {
                    uriHandler.openUri(SAMPLE_SOURCE_URL)
                } catch (_: IllegalArgumentException) {
                } catch (_: ActivityNotFoundException) {
                }
            }
            .testTag(AudioPreviewTags.CREDIT),
    ) {
        Text(
            stringResource(R.string.sample_credit),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textDecoration = TextDecoration.Underline,
        )
    }
}

@Composable
private fun MainButton(state: PreviewUiState, bothSelected: Boolean, onPlay: () -> Unit, onStop: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val buttonColors = ButtonDefaults.buttonColors(
        disabledContainerColor = colors.outlineVariant,
        disabledContentColor = colors.onSurfaceVariant,
    )
    // Minimum height only: large font scales wrap the label instead of clipping it.
    val sizing = Modifier.fillMaxWidth().heightIn(min = 56.dp)
    val shape = RoundedCornerShape(18.dp)
    if (state.isPlaying) {
        val stopLabel = stringResource(R.string.stop_preview)
        Button(
            onClick = onStop,
            shape = shape,
            colors = buttonColors,
            // While mixing the label reads "합치는 중…"; say that activating it stops.
            modifier = sizing.testTag(AudioPreviewTags.STOP).semantics {
                onClick(label = stopLabel) { onStop(); true }
            },
        ) {
            if (state.phase == PreviewPhase.Rendering) {
                CircularProgressIndicator(
                    color = LocalContentColor.current,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
                ButtonLabel(stringResource(R.string.rendering_button))
            } else {
                Icon(painterResource(R.drawable.ic_stop), contentDescription = null)
                ButtonLabel(stringResource(R.string.stop_preview))
            }
        }
    } else {
        Button(
            onClick = onPlay,
            enabled = bothSelected && state.canPlay,
            shape = shape,
            colors = buttonColors,
            modifier = sizing.testTag(AudioPreviewTags.PLAY),
        ) {
            if (bothSelected) {
                Icon(painterResource(R.drawable.ic_play), contentDescription = null)
                ButtonLabel(stringResource(R.string.play_preview))
            } else {
                Text(stringResource(R.string.need_two_files), textAlign = TextAlign.Center, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ButtonLabel(text: String) {
    Spacer(modifier = Modifier.width(10.dp))
    Text(text, textAlign = TextAlign.Center, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun VolumeControl(
    slot: TrackSlot,
    value: Float,
    enabled: Boolean,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    sliderModifier: Modifier = Modifier,
) {
    val isA = slot == TrackSlot.A
    val description = stringResource(if (isA) R.string.volume_a else R.string.volume_b)
    val percentage = stringResource(R.string.volume_value, (value * 100).roundToInt())
    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(if (isA) R.string.track_a else R.string.track_b),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = trackColor(slot),
            )
            Text(
                percentage,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TrackSlider(
            slot = slot,
            value = value,
            onValueChange = { onChange((it * 100).roundToInt() / 100f) },
            valueRange = 0f..1f,
            steps = 99,
            enabled = enabled,
            modifier = sliderModifier.semantics {
                contentDescription = description
                stateDescription = percentage
            },
        )
    }
}

/**
 * The design's range input: one light 4dp track with no filled part (the value is the number beside
 * it), and a 16dp thumb with a 3dp surface ring and a 1dp outer ring. No step ticks; the Material
 * slider keeps its 48dp touch target.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackSlider(
    slot: TrackSlot,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val track = if (enabled) trackColor(slot) else colors.onSurfaceVariant.copy(alpha = 0.38f)
    val trackTint = if (enabled) trackColor(slot).tintOver(colors.surface, 0.18f) else colors.outlineVariant
    val sliderColors = SliderDefaults.colors(
        activeTrackColor = trackTint,
        inactiveTrackColor = trackTint,
        disabledActiveTrackColor = trackTint,
        disabledInactiveTrackColor = trackTint,
    )
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        colors = sliderColors,
        steps = steps,
        valueRange = valueRange,
        thumb = {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(track.tintOver(colors.surface, 0.2f), CircleShape)
                    .padding(1.dp)
                    .background(colors.surface, CircleShape)
                    .padding(3.dp)
                    .background(track, CircleShape),
            )
        },
        track = { sliderState ->
            SliderDefaults.Track(
                sliderState = sliderState,
                modifier = Modifier.height(4.dp),
                enabled = enabled,
                colors = sliderColors,
                drawStopIndicator = null,
                drawTick = { _, _ -> },
                thumbTrackGapSize = 0.dp,
                trackInsideCornerSize = 2.dp,
            )
        },
    )
}

@Composable
private fun Notice(icon: Int, text: String, iconColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Icon(painterResource(icon), contentDescription = null, tint = iconColor, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusLine(showCheck: Boolean, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCheck) {
            Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun ErrorBox(message: String) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = colors.error,
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.error.tintOver(colors.surface, DANGER_SOFT), shape)
            .border(1.dp, colors.error.tintOver(colors.surface, DANGER_BORDER), shape)
            .padding(16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFF8F8F8)
@Composable
private fun StartLayoutPreview() {
    HarmonySyncerTheme {
        AudioPreviewScreen(
            state = PreviewUiState(trackA = ClipInfo("Soprano.wav", 48_000, 384_000)),
            onSelectA = {}, onSelectB = {}, onSettingsChange = {}, onPlay = {}, onStop = {}, onLoadSample = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF8F8F8)
@Composable
private fun ReadyLayoutPreview() {
    HarmonySyncerTheme {
        AudioPreviewScreen(
            state = PreviewUiState(
                trackA = ClipInfo("Soprano.wav", 48_000, 384_000),
                trackB = ClipInfo("Alto.m4a", 44_100, 352_800),
                settings = MixSettings(volumeA = 0.75f, volumeB = 0.9f, offsetMs = 40),
            ),
            onSelectA = {}, onSelectB = {}, onSettingsChange = {}, onPlay = {}, onStop = {}, onLoadSample = {},
        )
    }
}
