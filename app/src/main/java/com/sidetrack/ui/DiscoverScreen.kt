package com.sidetrack.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sidetrack.viewmodel.DiscoverViewModel
import com.sidetrack.viewmodel.LibraryViewModel
import com.sidetrack.viewmodel.PlayerViewModel

@Composable
fun DiscoverScreen(
    playerViewModel: PlayerViewModel,
    libraryViewModel: LibraryViewModel = viewModel(),
    onBack: () -> Unit,
    onGoToAlbum: (String) -> Unit = {},
    onGoToArtist: (String) -> Unit = {},
    discoverViewModel: DiscoverViewModel = viewModel(),
) {
    val state by discoverViewModel.uiState.collectAsState()
    val libraryState by libraryViewModel.uiState.collectAsState()
    var selectedTrackUri by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        discoverViewModel.loadIfNeeded()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(0.dp))
        IconButton(onClick = onBack, modifier = Modifier.focusCircle()) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Discover",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { discoverViewModel.refresh() }, modifier = Modifier.focusCircle()) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Refresh",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    when {
        state.isLoading && state.tracks.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
        state.error != null && state.tracks.isEmpty() -> {
            Box(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.error ?: "Error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        else -> {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                itemsIndexed(state.tracks, key = { _, track -> track.uri }, contentType = { _, _ -> "track" }) { index, track ->
                    TrackRow(
                        index = index + 1,
                        track = track,
                        showAlbumArt = true,
                        onClick = {
                            playerViewModel.loadTrackFromContext(
                                state.trackUris, index, "Discover",
                                contextArtistName = track.artistName,
                            )
                        },
                        onLongClick = { selectedTrackUri = track.uri },
                    )
                }
            }
        }
    }

    if (selectedTrackUri != null) {
        val selectedTrack = state.tracks.find { it.uri == selectedTrackUri }
        val writablePlaylists = remember(libraryState.playlists) {
            libraryState.playlists.filter { it.isWritable }
        }
        TrackActionsSheet(
            trackUri = selectedTrackUri!!,
            playerViewModel = playerViewModel,
            playlists = writablePlaylists,
            onDismiss = { selectedTrackUri = null },
            onGoToAlbum = if (selectedTrack != null) {
                { onGoToAlbum(selectedTrack.albumUri) }
            } else null,
            artists = selectedTrack?.artists.orEmpty(),
            onGoToArtist = onGoToArtist,
            trackName = selectedTrack?.name.orEmpty(),
        )
    }
}
