package com.cassiadev.harmonysyncer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cassiadev.harmonysyncer.R
import com.cassiadev.harmonysyncer.audio.ClipInfo
import com.cassiadev.harmonysyncer.audio.TrackSlot
import com.cassiadev.harmonysyncer.ui.theme.DANGER_SOFT
import com.cassiadev.harmonysyncer.ui.theme.LocalHarmonyExtraColors
import com.cassiadev.harmonysyncer.ui.theme.TINT_BORDER
import com.cassiadev.harmonysyncer.ui.theme.TINT_SOFT
import com.cassiadev.harmonysyncer.ui.theme.TINT_TILE
import com.cassiadev.harmonysyncer.ui.theme.tintOver

/**
 * One audio slot. Only its add/change button is clickable, so the file name stays a separate
 * semantics node that tests and TalkBack can reach.
 */
@Composable
fun TrackCard(
    slot: TrackSlot,
    clip: ClipInfo?,
    loading: Boolean,
    error: String?,
    enabled: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val isA = slot == TrackSlot.A
    // A failed slot takes the danger color for its whole card, as design .track-card.error does.
    val track = if (error != null) colors.error else trackColor(slot)
    val trackText = if (error != null) colors.error else trackTextColor(slot)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(if (isA) AudioPreviewTags.CARD_A else AudioPreviewTags.CARD_B)
            .clip(shape)
            .background(track.tintOver(colors.surface, if (error != null) DANGER_SOFT else TINT_SOFT))
            .border(1.dp, track.tintOver(colors.surface, TINT_BORDER), shape)
            .then(
                // Design .ready-page .track-card is the compact summary card.
                if (compact) Modifier.padding(14.dp) else Modifier.padding(start = 17.dp, top = 17.dp, end = 17.dp, bottom = 15.dp),
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(track.tintOver(colors.surface, TINT_TILE)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_audio_bars),
                    contentDescription = null,
                    tint = track,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (clip == null) {
                    Text(
                        stringResource(if (isA) R.string.add_track_a else R.string.add_track_b),
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.add_track_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                } else {
                    Text(
                        stringResource(if (isA) R.string.track_a else R.string.track_b),
                        // Explicit line heights: sizes alone keep bodyLarge's 24sp line and loosen the card.
                        fontSize = if (compact) 12.sp else 14.sp,
                        lineHeight = if (compact) 16.sp else 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        clip.name,
                        fontSize = if (compact) 12.sp else 13.sp,
                        lineHeight = if (compact) 16.sp else 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(if (isA) AudioPreviewTags.NAME_A else AudioPreviewTags.NAME_B),
                    )
                    Text(
                        stringResource(R.string.clip_details, clip.durationSeconds, clip.sampleRate),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            val selectTag = if (isA) AudioPreviewTags.SELECT_A else AudioPreviewTags.SELECT_B
            if (clip == null) {
                FilledIconButton(
                    onClick = onSelect,
                    enabled = enabled,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = colors.surface,
                        contentColor = track,
                    ),
                    modifier = Modifier.size(48.dp).testTag(selectTag),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_add),
                        contentDescription = stringResource(if (isA) R.string.select_file_a else R.string.select_file_b),
                    )
                }
            } else {
                val changeDescription = stringResource(if (isA) R.string.change_file_a else R.string.change_file_b)
                TextButton(
                    onClick = onSelect,
                    enabled = enabled,
                    colors = ButtonDefaults.textButtonColors(contentColor = trackText),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag(selectTag)
                        .semantics { contentDescription = changeDescription },
                ) {
                    Text(stringResource(R.string.change_file), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (loading) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LinearProgressIndicator(
                    color = track,
                    trackColor = track.tintOver(colors.surface, 0.18f),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.track_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
internal fun trackColor(slot: TrackSlot) =
    if (slot == TrackSlot.A) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary

/** Track-colored text on a card tint; Audio A's accent is only 4.34:1 on its own tint. */
@Composable
internal fun trackTextColor(slot: TrackSlot) =
    if (slot == TrackSlot.A) LocalHarmonyExtraColors.current.accentOnTint else MaterialTheme.colorScheme.secondary
