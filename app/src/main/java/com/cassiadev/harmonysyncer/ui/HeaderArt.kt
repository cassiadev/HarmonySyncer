package com.cassiadev.harmonysyncer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cassiadev.harmonysyncer.R
import com.cassiadev.harmonysyncer.ui.theme.TINT_SOFT
import com.cassiadev.harmonysyncer.ui.theme.tintOver

@Composable
fun AppHeader(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            // The adaptive-icon foreground keeps its art in the middle 72 of 108 dp; enlarge it to fill the tile.
            Image(
                painter = painterResource(R.mipmap.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.requiredSize(51.dp),
            )
        }
        val spokenName = stringResource(R.string.app_name_spoken)
        Text(
            stringResource(R.string.app_name),
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.6).sp,
            modifier = Modifier.semantics { contentDescription = spokenName },
        )
    }
}

/** Two audio tiles joined by crossing ribbons, as on the design's start screen. Decorative only. */
@Composable
fun PairRibbon(modifier: Modifier = Modifier) {
    val a = MaterialTheme.colorScheme.primary
    val b = MaterialTheme.colorScheme.secondary
    Row(
        // The design asset's start-screen mark: 64dp tall with 56dp tiles.
        modifier = modifier.height(64.dp).clearAndSetSemantics {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AudioTile(a, tilt = -9f)
        Canvas(modifier = Modifier.size(width = 84.dp, height = 50.dp)) {
            val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            fun buildRibbon(highFirst: Boolean) = Path().apply {
                val first = if (highFirst) size.height * 0.1f else size.height * 0.9f
                val second = size.height - first
                moveTo(0f, size.height / 2)
                cubicTo(size.width * 0.3f, first, size.width * 0.7f, second, size.width, size.height / 2)
            }
            drawPath(buildRibbon(highFirst = true), a, style = stroke)
            drawPath(buildRibbon(highFirst = false), b, style = stroke)
        }
        AudioTile(b, tilt = 9f)
    }
}

@Composable
private fun AudioTile(track: Color, tilt: Float) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .rotate(tilt)
            .clip(RoundedCornerShape(19.dp))
            .background(track.tintOver(MaterialTheme.colorScheme.surface, TINT_SOFT)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_audio_bars),
            contentDescription = null,
            tint = track,
            modifier = Modifier.size(28.dp).rotate(-tilt),
        )
    }
}
