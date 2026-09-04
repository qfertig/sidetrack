package com.sidetrack.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
fun MiniPlayer(
    trackTitle: String,
    artistName: String,
    albumArtUrl: String?,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onClick: () -> Unit,
) {
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .focusHighlight()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Art sits flush against the edge — square, full bar height, no
        // padding/gap — so the bar reads as one solid strip instead of leaving
        // dead space before the text starts.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .size(56.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (albumArtUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(albumArtUrl).size(112).build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxHeight(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        // Title and artist scroll together as one line — a fixed two-line stack
        // either squished each field independently or cut the artist off outright;
        // this way a long name just takes its turn scrolling into view instead.
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
                .basicMarquee(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = trackTitle.ifEmpty { "No track" },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = "  •  ",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.5f),
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = artistName.ifEmpty { "---" },
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                softWrap = false,
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        IconButton(
            onClick = onPlayPause,
            modifier = Modifier
                .padding(end = 8.dp)
                .size(48.dp),
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = Modifier.size(28.dp),
                tint = Color.White,
            )
        }
    }
}
