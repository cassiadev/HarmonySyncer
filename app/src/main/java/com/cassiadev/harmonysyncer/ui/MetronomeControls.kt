package com.cassiadev.harmonysyncer.ui

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cassiadev.harmonysyncer.R
import com.cassiadev.harmonysyncer.audio.METRONOME_BEATS_PER_BAR
import com.cassiadev.harmonysyncer.audio.METRONOME_MAX_BPM
import com.cassiadev.harmonysyncer.audio.METRONOME_MIN_BPM
import com.cassiadev.harmonysyncer.audio.MetronomeSettings
import com.cassiadev.harmonysyncer.audio.PreviewPhase
import com.cassiadev.harmonysyncer.audio.beatAt
import com.cassiadev.harmonysyncer.audio.estimateTapBpm

private val MetronomeRowShape = RoundedCornerShape(15.dp)
// More than estimateTapBpm reads after a pause; older taps no longer matter.
private const val KEPT_TAPS = 8

/** Summary row for the metronome settings; opens [MetronomeSheet]. Shaped like [TimingRow]. */
@Composable
fun MetronomeRow(settings: MetronomeSettings, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(MetronomeRowShape)
            .background(colors.surfaceVariant)
            .border(1.dp, colors.outlineVariant, MetronomeRowShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(AudioPreviewTags.METRONOME)
            // alpha(1f) drops its layer; adding and removing it left a moved row without its background on Android 10.
            .graphicsLayer { alpha = if (enabled) 1f else 0.6f }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_metronome),
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(21.dp),
        )
        Text(
            stringResource(
                R.string.metronome_summary,
                settings.bpm,
                settings.beatsPerBar,
                stringResource(if (settings.useForRecording) R.string.metronome_on else R.string.metronome_off),
            ),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Metronome settings and the standalone metronome; [phase] tells whether it runs and from which first beat. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetronomeSheet(
    settings: MetronomeSettings,
    phase: PreviewPhase,
    onChange: (MetronomeSettings) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val running = phase as? PreviewPhase.Metronome
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface,
        tonalElevation = 0.dp,
    ) {
        // Scrolls so every control stays reachable in landscape with a large font.
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.metronome_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            SwitchRow(stringResource(R.string.metronome_use), settings.useForRecording) {
                onChange(settings.copy(useForRecording = it))
            }
            // TalkBack reads the value with each button, as the timing slider does; the large number is not a stop.
            val bpmValue = stringResource(R.string.bpm_value, settings.bpm)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("BPM", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                FilledTonalIconButton(
                    onClick = { onChange(settings.copy(bpm = settings.bpm - 1)) },
                    enabled = settings.bpm > METRONOME_MIN_BPM,
                    modifier = Modifier.size(48.dp).semantics { stateDescription = bpmValue },
                ) { Icon(painterResource(R.drawable.ic_remove), contentDescription = stringResource(R.string.bpm_decrease)) }
                Text(
                    "${settings.bpm}",
                    fontSize = 24.sp,
                    lineHeight = 30.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(min = 64.dp).clearAndSetSemantics {},
                )
                FilledTonalIconButton(
                    onClick = { onChange(settings.copy(bpm = settings.bpm + 1)) },
                    enabled = settings.bpm < METRONOME_MAX_BPM,
                    modifier = Modifier.size(48.dp).semantics { stateDescription = bpmValue },
                ) { Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.bpm_increase)) }
            }
            val taps = remember { ArrayList<Long>() }
            OutlinedButton(
                onClick = {
                    taps += System.nanoTime()
                    if (taps.size > KEPT_TAPS) taps.removeAt(0)
                    estimateTapBpm(taps)?.let { onChange(settings.copy(bpm = it)) }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.tap_tempo)) }
            Text(stringResource(R.string.beats_per_bar), style = MaterialTheme.typography.bodyLarge)
            BeatsPerBarChoice(settings.beatsPerBar) { onChange(settings.copy(beatsPerBar = it)) }
            SwitchRow(stringResource(R.string.click_sound), settings.clickOn) { onChange(settings.copy(clickOn = it)) }
            Text(
                stringResource(R.string.click_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            BeatDisplay(settings.bpm, settings.beatsPerBar, running?.startNanos, showCountIn = false)
            Button(
                onClick = if (running != null) onStop else onStart,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(if (running != null) R.string.metronome_stop else R.string.metronome_start)) }
        }
    }
}

/** The 2·3·4·6 beats-per-bar radio options; they wrap so a large font keeps every option whole. */
@Composable
internal fun BeatsPerBarChoice(beatsPerBar: Int, onSelect: (Int) -> Unit) {
    FlowRow(modifier = Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (beats in METRONOME_BEATS_PER_BAR) {
            val selected = beatsPerBar == beats
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .selectable(selected = selected, role = Role.RadioButton) { onSelect(beats) }
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null)
                Text(stringResource(R.string.beats_value, beats), modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * One dot per beat of the bar, lighting the beat sounding now; the bar's first beat has its own color.
 * [startNanos] is the first beat on the `System.nanoTime()` clock, null before it is known. Frames only ask for a
 * redraw: the beat comes from the clock. Not a live region, so TalkBack does not speak into a take.
 */
@Composable
fun BeatDisplay(bpm: Int, beatsPerBar: Int, startNanos: Long?, showCountIn: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    var beat by remember(startNanos, bpm) {
        mutableLongStateOf(if (startNanos == null) -1L else beatAt(System.nanoTime() - startNanos, bpm))
    }
    if (startNanos != null) {
        // An infinite animation: Compose tests cancel it instead of waiting for it to settle.
        LaunchedEffect(startNanos, bpm) {
            while (true) withInfiniteAnimationFrameNanos { beat = beatAt(System.nanoTime() - startNanos, bpm) }
        }
    }
    Row(
        modifier = modifier.testTag(AudioPreviewTags.METRONOME_BEATS),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (index in 0 until beatsPerBar) {
            val lit = beat >= 0 && beat % beatsPerBar == index.toLong()
            val color = when {
                !lit -> colors.outlineVariant
                index == 0 -> colors.primary
                else -> colors.secondary
            }
            Box(modifier = Modifier.size(if (index == 0) 16.dp else 12.dp).clip(CircleShape).background(color))
        }
        if (showCountIn && beat in 0 until beatsPerBar) {
            Text(
                stringResource(R.string.count_in, beat + 1, beatsPerBar),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurfaceVariant,
            )
        }
    }
}
