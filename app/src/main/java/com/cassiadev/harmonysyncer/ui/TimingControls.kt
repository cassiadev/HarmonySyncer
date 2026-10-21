package com.cassiadev.harmonysyncer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cassiadev.harmonysyncer.R
import com.cassiadev.harmonysyncer.audio.MixSettings
import com.cassiadev.harmonysyncer.audio.TrackSlot
import kotlin.math.roundToInt

/** Summary row for Audio B's offset; opens [TimingSheet]. */
@Composable
fun TimingRow(offsetMs: Int, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(TimingRowShape)
            .background(colors.surfaceVariant)
            .border(1.dp, colors.outlineVariant, TimingRowShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(AudioPreviewTags.TIMING)
            .alpha(if (enabled) 1f else 0.6f)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_sliders),
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(21.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.timing_title), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.timing_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        Text(
            if (offsetMs == 0) stringResource(R.string.timing_together) else offsetValue(offsetMs),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = colors.secondary,
        )
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

private val TimingRowShape = RoundedCornerShape(15.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimingSheet(settings: MixSettings, onSettingsChange: (MixSettings) -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface,
        tonalElevation = 0.dp,
    ) {
        // Scrolls so the reset and hint stay reachable in landscape with a large font.
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.timing_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            val offsetLabel = stringResource(R.string.offset_b)
            val offsetValue = offsetValue(settings.offsetMs)
            // Design .timing-output: the value large, centered and in Audio B's color.
            Text(
                offsetValue,
                fontSize = 24.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.secondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
            )
            TrackSlider(
                slot = TrackSlot.B,
                value = settings.offsetMs.toFloat(),
                onValueChange = { onSettingsChange(settings.copy(offsetMs = it.roundToInt())) },
                valueRange = -300f..300f,
                steps = 599,
                enabled = true,
                modifier = Modifier.testTag(AudioPreviewTags.OFFSET).semantics {
                    contentDescription = offsetLabel
                    stateDescription = offsetValue
                },
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    stringResource(R.string.offset_earlier),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.offset_later),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = { onSettingsChange(settings.copy(offsetMs = 0)) },
                enabled = settings.offsetMs != 0,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.reset_offset), fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            Text(
                stringResource(R.string.mix_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun offsetValue(offsetMs: Int) =
    stringResource(R.string.offset_value, if (offsetMs > 0) "+$offsetMs" else "$offsetMs")
