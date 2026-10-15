package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.MediaItem
import com.example.data.MediaRepository
import com.example.ui.theme.*

enum class MyStuffTab(val title: String) {
    PROFILE("Profile"),
    COLLECTIONS("Collections"),
    SAVED("Saved")
}

@Composable
fun MyStuffScreen(
    repository: MediaRepository,
    mediaItems: List<MediaItem>,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    onNavigateToFavorites: () -> Unit,
    onNavigateToCleanup: () -> Unit,
    onNavigateToPrivacyPolicy: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
    onLaunchAuraMoments: () -> Unit,
    onMediaSelect: (MediaItem, List<MediaItem>) -> Unit,
    onCollectionSelect: (MediaItem, List<MediaItem>) -> Unit,
    onFavoriteToggle: (String) -> Unit,
    onSearch: (String) -> Unit,
    initialTab: MyStuffTab = MyStuffTab.PROFILE,
    modifier: Modifier = Modifier
) {
    var selectedTab by rememberSaveable { mutableStateOf(initialTab) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // My Stuff Top Bar Selector
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding(),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 1.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AuraSpacing.M, vertical = AuraSpacing.S)
            ) {
                Text(
                    text = "My Stuff",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(AuraSpacing.S))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AuraSpacing.S)
                ) {
                    MyStuffTab.entries.forEach { tab ->
                        val isSelected = selectedTab == tab
                        Surface(
                            onClick = { selectedTab = tab },
                            shape = RoundedCornerShape(AuraSpacing.S),
                            color = if (isSelected) DiscoveryViolet.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(
                                width = 1.dp,
                                brush = if (isSelected) DiscoveryGradient else androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.outlineVariant)
                            ),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (tab == MyStuffTab.SAVED) {
                                        Icon(
                                            imageVector = if (isSelected) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                                            contentDescription = "Saved",
                                            tint = if (isSelected) DiscoveryViolet else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    Text(
                                        text = tab.title,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) DiscoveryViolet else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // Move Visual Index indicator directly below the Profile button and above the Profile text
                val visualReadiness by repository.observeVisualIndexReadiness().collectAsStateWithLifecycle(null)
                visualReadiness?.let { readiness ->
                    Spacer(modifier = Modifier.height(AuraSpacing.S))
                    com.example.ui.components.VisualIndexReadinessPill(
                        readiness = readiness,
                        onRetryClick = { repository.retryFailedEnrichment() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Selected Tab Content
        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                MyStuffTab.PROFILE -> {
                    ProfileScreen(
                        repository = repository,
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        onNavigateToFavorites = onNavigateToFavorites,
                        onNavigateToCleanup = onNavigateToCleanup,
                        onNavigateToPrivacyPolicy = onNavigateToPrivacyPolicy,
                        onNavigateToDiagnostics = onNavigateToDiagnostics,
                        onLaunchAuraMoments = onLaunchAuraMoments,
                        onMediaSelect = onMediaSelect
                    )
                }
                MyStuffTab.COLLECTIONS -> {
                    CollectionsScreen(
                        mediaItems = mediaItems,
                        repository = repository,
                        onCollectionSelect = onCollectionSelect,
                        onFavoriteToggle = onFavoriteToggle,
                        onLaunchAuraMoments = onLaunchAuraMoments,
                        onSearch = onSearch
                    )
                }
                MyStuffTab.SAVED -> {
                    SavedScreen(
                        repository = repository,
                        onMediaSelect = onMediaSelect,
                        onLike = onFavoriteToggle
                    )
                }
            }
        }
    }
}
