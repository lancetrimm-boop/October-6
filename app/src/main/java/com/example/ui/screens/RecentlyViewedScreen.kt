package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.example.data.MediaItem
import com.example.data.MediaRepository
import com.example.ui.components.AuraLogoIcon
import com.example.ui.components.AuraMediaTile
import com.example.ui.theme.*

@Composable
fun RecentlyViewedScreen(
    repository: MediaRepository,
    onMediaSelect: (MediaItem, List<MediaItem>) -> Unit,
    onLike: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val watchHistory by repository.watchHistory.collectAsState(initial = emptyList())

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (watchHistory.isEmpty()) {
            EmptyRecentlyViewedView()
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = AuraSpacing.M,
                    end = AuraSpacing.M,
                    top = AuraSpacing.M,
                    bottom = AuraSpacing.XXL
                ),
                horizontalArrangement = Arrangement.spacedBy(AuraSpacing.GridGap),
                verticalArrangement = Arrangement.spacedBy(AuraSpacing.S)
            ) {
                items(watchHistory, key = { it.id }) { item ->
                    AuraMediaTile(
                        item = item,
                        onClick = { onMediaSelect(item, watchHistory) },
                        onLike = { onLike(item.id) },
                        onLongClick = { /* No-op on recent tile long click */ }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyRecentlyViewedView() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AuraLogoIcon(size = 64.dp)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Recently Viewed",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "No recently viewed media. Videos and photos you play or view will appear here.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}
