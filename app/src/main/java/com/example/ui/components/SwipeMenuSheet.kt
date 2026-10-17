package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ClipCandidate
import com.example.data.MediaItem
import com.example.ui.theme.AuraCrispWhite
import com.example.ui.theme.AuraMagenta
import com.example.ui.theme.AuraMidnight
import com.example.ui.theme.AuraSlate
import com.example.ui.theme.AuraStarGold
import com.example.ui.theme.AuraSubtleBorder
import com.example.ui.theme.DiscoveryViolet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeMenuSheet(
    activeItem: MediaItem,
    isVisualSearchActive: Boolean,
    currentRating: Float,
    playbackSpeed: Float,
    isAbRepeatActive: Boolean,
    abPointA: Long?,
    abPointB: Long?,
    currentPositionMs: Long,
    durationMs: Float,
    onDismissRequest: () -> Unit,
    onFavoriteToggle: (String) -> Unit,
    onUpdateRating: ((String, Float) -> Unit)?,
    onPlaybackSpeedChange: (Float) -> Unit,
    onSetAbPointA: (Long) -> Unit,
    onSetAbPointB: (Long) -> Unit,
    onClearAbRepeat: () -> Unit,
    onExportAbClip: (ClipCandidate) -> Unit,
    onCaptureScreenshot: () -> Unit,
    onDeleteMediaRequested: () -> Unit,
    onSeeSimilar: (MediaItem) -> Unit,
    onBeginVisualSearch: (MediaItem) -> Unit,
    onGenerateClips: (MediaItem) -> Unit,
    isSaved: Boolean = false,
    onToggleSaved: (String) -> Unit = {},
    slideshowDelaySec: Int = 4,
    onSlideshowDelayChange: (Int) -> Unit = {},
    isVisualIndexReady: Boolean = true,
    onVisualIndexUnavailable: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showSpeedMenu by remember { mutableStateOf(false) }

    // --- FAVORITE HEART PULSE ANIMATION STATE MACHINE ---
    val isFavorite = activeItem.isFavorite
    var lastSeenItemId by remember { mutableStateOf(activeItem.id) }
    var lastSeenIsFavorite by remember { mutableStateOf(isFavorite) }
    val heartScale = remember { Animatable(1.0f) }

    LaunchedEffect(activeItem.id, isFavorite) {
        if (activeItem.id != lastSeenItemId) {
            // Media item switched — update tracking without pulsing
            lastSeenItemId = activeItem.id
            lastSeenIsFavorite = isFavorite
            heartScale.snapTo(1.0f)
        } else if (isFavorite != lastSeenIsFavorite) {
            // Actual favorite toggle transition on the same media item — pulse!
            lastSeenIsFavorite = isFavorite
            heartScale.animateTo(
                targetValue = 1.3f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
            heartScale.animateTo(
                targetValue = 1.0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = AuraMidnight.copy(alpha = 0.92f),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(AuraSubtleBorder.copy(alpha = 0.4f))
            )
        },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Media Title Row
            val isVideo = activeItem.mediaType.equals("VIDEO", ignoreCase = true)
            val displayFilename = if (activeItem.title.contains(".")) {
                activeItem.title
            } else {
                "${activeItem.title}.${if (isVideo) "mp4" else "jpg"}"
            }
            Text(
                text = displayFilename,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = AuraCrispWhite,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 2. Rating & Playback Control Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AuraSlate.copy(alpha = 0.35f))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Five-Star Rating
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (i in 1..5) {
                        val isStarred = i <= currentRating.toInt()
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Rate $i stars",
                            tint = if (isStarred) AuraStarGold else Color(0x44FFFFFF),
                            modifier = Modifier
                                .size(20.dp)
                                .clickable {
                                    onUpdateRating?.invoke(activeItem.id, i.toFloat())
                                }
                        )
                    }
                }

                // Controls: A/B, Camera, Speed, Favorite, Delete
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 3-Step A/B Repeat Control
                    val abLabel = when {
                        abPointA == null -> "A/B"
                        abPointB == null -> "A: ${formatTimeMs(abPointA.toFloat())}"
                        else -> "A-B Loop"
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = abLabel,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isAbRepeatActive || abPointA != null) DiscoveryViolet else AuraCrispWhite,
                            modifier = Modifier
                                .testTag("ab_repeat_button")
                                .clickable {
                                    if (abPointA == null) {
                                        onSetAbPointA(currentPositionMs)
                                    } else if (abPointB == null) {
                                        onSetAbPointB(currentPositionMs)
                                    } else {
                                        onClearAbRepeat()
                                    }
                                }
                        )

                        // AB Export Action
                        if (abPointA != null && abPointB != null) {
                            IconButton(
                                onClick = {
                                    if (abPointB > abPointA) {
                                        val clip = ClipCandidate(
                                            title = "AB Clip ${System.currentTimeMillis()}",
                                            startTimeMs = abPointA,
                                            endTimeMs = abPointB,
                                            durationSec = ((abPointB - abPointA) / 1000).toInt(),
                                            relevanceScorePercent = 100,
                                            selectionReason = "User defined AB segment"
                                        )
                                        onExportAbClip(clip)
                                    }
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCut,
                                    contentDescription = "Export AB Clip",
                                    tint = DiscoveryViolet,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    // Camera / Frame Snapshot
                    Icon(
                        imageVector = Icons.Default.PhotoCamera,
                        contentDescription = "Capture Screenshot",
                        tint = AuraCrispWhite,
                        modifier = Modifier
                            .size(18.dp)
                            .testTag("capture_frame_button")
                            .clickable { onCaptureScreenshot() }
                    )

                    // Playback / Slideshow Speed Selector
                    Box {
                        Text(
                            text = if (isVideo) "${playbackSpeed}x" else "${slideshowDelaySec}s",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = AuraCrispWhite,
                            modifier = Modifier
                                .testTag(if (isVideo) "playback_speed_button" else "photo_duration_button")
                                .clickable { showSpeedMenu = true }
                        )

                        DropdownMenu(
                            expanded = showSpeedMenu,
                            onDismissRequest = { showSpeedMenu = false },
                            modifier = Modifier.background(AuraMidnight.copy(alpha = 0.95f))
                        ) {
                            if (isVideo) {
                                listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "${speed}x ${if (playbackSpeed == speed) "✓" else ""}",
                                                color = AuraCrispWhite,
                                                fontWeight = if (playbackSpeed == speed) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        onClick = {
                                            onPlaybackSpeedChange(speed)
                                            showSpeedMenu = false
                                        }
                                    )
                                }
                            } else {
                                (3..15).forEach { sec ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "${sec}s ${if (slideshowDelaySec == sec) "✓" else ""}",
                                                color = AuraCrispWhite,
                                                fontWeight = if (slideshowDelaySec == sec) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        onClick = {
                                            onSlideshowDelayChange(sec)
                                            showSpeedMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Favorite / Heart Icon with Pulse Animation
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (isFavorite) AuraMagenta else AuraCrispWhite,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer {
                                scaleX = heartScale.value
                                scaleY = heartScale.value
                            }
                            .testTag("favorite_button")
                            .clickable { onFavoriteToggle(activeItem.id) }
                    )

                    // Saved / Bookmark Icon
                    Icon(
                        imageVector = if (isSaved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = if (isSaved) "Remove from Saved" else "Save",
                        tint = if (isSaved) DiscoveryViolet else AuraCrispWhite,
                        modifier = Modifier
                            .size(18.dp)
                            .testTag("saved_button")
                            .clickable { onToggleSaved(activeItem.id) }
                    )

                    // Delete Trash Icon -> Triggers Confirmation Flow
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Media",
                        tint = Color(0xFFEF4444),
                        modifier = Modifier
                            .size(18.dp)
                            .testTag("delete_media_button")
                            .clickable { onDeleteMediaRequested() }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 3. Action Buttons Row (See Similar, Begin Visual Search / Add to Search, Generate Clips)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PlayerActionButton(
                    label = "See Similar",
                    enabled = isVisualIndexReady,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (isVisualIndexReady) {
                            onSeeSimilar(activeItem)
                        } else {
                            onVisualIndexUnavailable()
                        }
                    }
                )

                val visualSearchLabel = if (isVisualSearchActive) "Add to Search" else "Begin Visual Search"
                PlayerActionButton(
                    label = visualSearchLabel,
                    enabled = isVisualIndexReady,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("begin_visual_search_button"),
                    onClick = {
                        if (isVisualIndexReady) {
                            onBeginVisualSearch(activeItem)
                        } else {
                            onVisualIndexUnavailable()
                        }
                    }
                )

                PlayerActionButton(
                    label = "Generate Clips",
                    modifier = Modifier.weight(1f),
                    onClick = { onGenerateClips(activeItem) }
                )
            }
        }
    }
}

@Composable
private fun PlayerActionButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick),
        color = if (enabled) AuraSlate.copy(alpha = 0.5f) else AuraSlate.copy(alpha = 0.2f),
        border = BorderStroke(1.dp, if (enabled) Color(0x33FFFFFF) else Color(0x11FFFFFF))
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (enabled) AuraCrispWhite else AuraCrispWhite.copy(alpha = 0.4f),
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun formatTimeMs(timeMs: Float): String {
    val totalSeconds = (timeMs / 1000).toInt().coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
}
