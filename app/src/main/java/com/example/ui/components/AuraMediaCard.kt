package com.example.ui.components

import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import androidx.annotation.OptIn
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.isActive
import com.example.util.MediaThumbnailFetcher
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import com.example.data.MediaItem
import com.example.ui.models.LibraryItemUi
import com.example.ui.theme.AuraBackground
import com.example.ui.theme.AuraBorder
import com.example.ui.theme.AuraBrandGradient
import com.example.ui.theme.AuraCrispWhite
import com.example.ui.theme.AuraMagenta
import com.example.ui.theme.AuraMidnight
import com.example.ui.theme.AuraMutedSlate
import com.example.ui.theme.AuraOnSurface
import com.example.ui.theme.AuraOnSurfaceVariant
import com.example.ui.theme.AuraPurple
import com.example.ui.theme.AuraSpacing
import com.example.ui.theme.AuraStarGold
import com.example.ui.theme.AuraSubtleBorder
import com.example.ui.theme.AuraSubtleSurface
import com.example.ui.theme.AuraSurface
import com.example.ui.theme.DiscoveryGradient
import com.example.ui.theme.DiscoveryViolet

private data class ThumbnailResult(
    val itemId: String,
    val bitmap: Bitmap?,
    val isFailure: Boolean = false
)

/**
 * REUSABLE MEDIA TILE (UPDATE 3)
 * Unified presentation component for all visual media grids.
 * Implements authoritative identity, media-first visuals, and direct interaction.
 */
@Composable
fun AuraMediaTile(
    item: MediaItem,
    onClick: () -> Unit,
    onLike: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    locationTag: String = "grid",
    rankScore: Double? = null
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var showDoubleTapHeart by remember { mutableStateOf(false) }

    // Phase 2: Preview Exposure tracking (1.25s continuous viewport visibility)
    LaunchedEffect(item.id) {
        if (item.id.isNotBlank()) {
            delay(1250L)
            com.example.data.MediaRepository.getInstance(context).exposureRepository?.recordEvent(
                mediaId = item.id,
                eventType = com.example.data.ExposureEventType.PREVIEW_SEEN,
                timestampMs = System.currentTimeMillis()
            )
        }
    }

    // Interaction Visual States
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1.0f,
        animationSpec = tween(150),
        label = "tile_press_scale"
    )

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .pointerInput(item.id, isSelectionMode) {
                detectTapGestures(
                    onTap = { onClick() },
                    onDoubleTap = {
                        // Double tap only active in normal browsing mode
                        if (!isSelectionMode) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showDoubleTapHeart = true
                            onLike()
                        }
                    },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    }
                )
            }
            .clip(RoundedCornerShape(8.dp)) // Refined rounding
            .background(AuraSubtleSurface)
            .testTag("media_tile_${item.id}")
    ) {
        // Authoritative Thumbnail Component
        AuraMediaThumbnail(
            itemId = item.id,
            mediaType = item.mediaType,
            imageUrl = item.imageUrl,
            uriPath = item.uriPath,
            convertedUri = item.convertedUri,
            title = item.title,
            modifier = Modifier.fillMaxSize(),
            locationTag = locationTag
        )

        // Phase 4: Developer Diagnostic Badge
        if (com.example.BuildConfig.ENABLE_DEVELOPER_TOOLS && rankScore != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp),
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = "%.2f".format(rankScore),
                    color = Color.Green,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }

        // Video Duration Badge (Functional Context)
        val isVideo = item.mediaType.equals("VIDEO", ignoreCase = true) || item.mediaType.equals("Movie", ignoreCase = true)
        if (isVideo && item.duration.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(AuraSpacing.XXS),
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = item.duration,
                    color = Color.White,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }

        // Selection & Mode Overlays
        if (isSelectionMode || isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (isSelected) DiscoveryViolet.copy(alpha = 0.25f)
                        else Color.Black.copy(alpha = 0.05f)
                    )
            )
            
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(2.dp, DiscoveryGradient, RoundedCornerShape(4.dp))
                )
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(20.dp)
                )
            }
        }

        // Like Animation
        AnimatedVisibility(
            visible = showDoubleTapHeart,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = null,
                tint = AuraMagenta,
                modifier = Modifier.size(48.dp)
            )
        }

        LaunchedEffect(showDoubleTapHeart) {
            if (showDoubleTapHeart) {
                delay(500)
                showDoubleTapHeart = false
            }
        }
    }
}

@Composable
fun AuraMediaThumbnail(
    itemId: String,
    mediaType: String,
    imageUrl: String,
    uriPath: String,
    title: String,
    modifier: Modifier = Modifier,
    convertedUri: String? = null,
    locationTag: String = "generic"
) {
    val isVideo = mediaType.equals("VIDEO", ignoreCase = true) || mediaType.equals("Movie", ignoreCase = true)
    val context = LocalContext.current
    
    // AURA REPAIR: Identity-safe state to prevent recycled cards from showing stale thumbnails
    var thumbnailResult by remember(itemId, uriPath, imageUrl, convertedUri) { mutableStateOf<ThumbnailResult?>(null) }
    
    // AURA REPAIR: Track if video is actually rendering to prevent black frame gap
    var isVideoRendering by remember(itemId, uriPath, imageUrl, convertedUri) { mutableStateOf(false) }

    LaunchedEffect(itemId, uriPath, imageUrl, convertedUri) {
        // Preference: Converted > Local uriPath > Remote imageUrl
        val isLocalUri = uriPath.isNotEmpty() && (uriPath.startsWith("content") || uriPath.startsWith("file") || uriPath.startsWith("/"))
        val primaryUri = when {
            !convertedUri.isNullOrEmpty() -> convertedUri
            isLocalUri -> uriPath
            imageUrl.isNotEmpty() -> imageUrl
            else -> uriPath
        }
        
        if (primaryUri.isNotEmpty()) {
            var bitmap = MediaThumbnailFetcher.getThumbnail(context, primaryUri)
            if (bitmap == null && primaryUri != uriPath && uriPath.isNotEmpty()) {
                bitmap = MediaThumbnailFetcher.getThumbnail(context, uriPath)
            }
            if (bitmap == null && primaryUri != imageUrl && imageUrl.isNotEmpty()) {
                bitmap = MediaThumbnailFetcher.getThumbnail(context, imageUrl)
            }
            
            // Invariant: only apply if itemId still matches the requested one
            if (isActive) {
                thumbnailResult = ThumbnailResult(itemId, bitmap, isFailure = bitmap == null)
                if (bitmap == null && isVideo) {
                    com.example.data.MediaRepository.getInstance(context).updateItemCompatibilityStatus(itemId, com.example.data.CompatibilityStatus.THUMBNAIL_FAILED)
                }
            }
        } else {
            thumbnailResult = ThumbnailResult(itemId, null, isFailure = true)
        }
    }

    Box(
        modifier = modifier
            .background(AuraSubtleSurface),
        contentAlignment = Alignment.Center
    ) {
        val result = thumbnailResult
        
        // LAYER 1: Static Thumbnail (Always underneath as a safety baseline)
        when {
            result == null -> {
                // State 1: Loading (Subtle gradient shimmer)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(AuraSubtleSurface, AuraSubtleBorder, AuraSubtleSurface)
                            )
                        )
                )
            }
            result.bitmap != null && result.itemId == itemId -> {
                // State 2: Success (Identity Verified)
                Box(modifier = Modifier.fillMaxSize()) {
                    // Blurred Background
                    Image(
                        bitmap = result.bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(30.dp)
                            .graphicsLayer(alpha = 0.5f),
                        contentScale = ContentScale.Crop
                    )
                    // Aspect-Fit Foreground
                    Image(
                        bitmap = result.bitmap.asImageBitmap(),
                        contentDescription = title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
            else -> {
                // State 3: Failure (Deliberate Themed Fallback)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (isVideo) Icons.Outlined.Movie else Icons.Outlined.Image,
                        contentDescription = "Preview Unavailable",
                        tint = AuraMutedSlate.copy(alpha = 0.4f),
                        modifier = Modifier.size(32.dp)
                    )
                    if (title.isNotEmpty()) {
                        Text(
                            text = "NO PREVIEW",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black,
                            color = AuraMutedSlate.copy(alpha = 0.5f),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        // LAYER 2: Video Preview (Becomes visible only when first frame is rendered)
        // AURA REPAIR: Allow VideoTilePreview to attempt playback even if thumbnail generation failed.
        if (isVideo && result != null && result.itemId == itemId) {
            VideoTilePreview(
                itemId = itemId,
                videoUri = convertedUri ?: uriPath,
                imageUrl = imageUrl,
                onFirstFrameRendered = { isVideoRendering = true },
                locationTag = locationTag,
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .graphicsLayer { 
                        // Cross-fade to video once it's actually ready
                        alpha = if (isVideoRendering) 1f else 0f 
                    }
            )
        }
    }
}

/**
 * DEPRECATED: Use AuraMediaTile (Unified Pipeline)
 */
@Composable
fun LibraryGalleryMediaTile(
    item: LibraryItemUi,
    onClick: () -> Unit,
    onLike: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    onLongClick: (() -> Unit)? = null
) {
    // Mapping back to temporary MediaItem for compatibility until LibraryScreen is refactored
    val mockItem = MediaItem(
        id = item.id,
        title = item.title,
        mediaType = item.mediaType,
        uriPath = item.uriPath,
        imageUrl = item.imageUrl,
        duration = item.duration
    )
    
    AuraMediaTile(
        item = mockItem,
        onClick = onClick,
        onLike = onLike,
        onLongClick = { onLongClick?.invoke() },
        isSelected = isSelected,
        modifier = modifier
    )
}

/**
 * DEPRECATED: Use AuraMediaTile
 */
@Composable
fun AuraSquareMediaTile(
    item: MediaItem,
    onClick: () -> Unit,
    onLike: () -> Unit = {},
    onFavoriteToggle: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    AuraMediaTile(
        item = item,
        onClick = onClick,
        onLike = onLike,
        onLongClick = { onFavoriteToggle?.invoke() },
        modifier = modifier
    )
}

@Composable
fun AuraFeaturedMediaCard(
    item: MediaItem,
    onClick: () -> Unit,
    onLike: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var showDoubleTapHeart by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                brush = Brush.linearGradient(
                    colors = item.gradientColors.map { Color(it) }
                )
            )
            .border(1.dp, AuraSubtleBorder, RoundedCornerShape(20.dp))
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { onClick() },
                    onDoubleTap = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showDoubleTapHeart = true
                        onLike?.invoke()
                    }
                )
            }
            .testTag("featured_card_${item.id}")
    ) {
        AuraMediaThumbnail(
            itemId = item.id,
            mediaType = item.mediaType,
            imageUrl = item.imageUrl,
            uriPath = item.uriPath,
            title = item.title,
            modifier = Modifier.fillMaxSize(),
            locationTag = "featured"
        )
        
        // Scrim gradient
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.75f)
                        )
                    )
                )
        )

        // Double tap heart pop animation
        androidx.compose.animation.AnimatedVisibility(
            visible = showDoubleTapHeart,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = null,
                tint = AuraMagenta,
                modifier = Modifier.size(56.dp)
            )
        }
        LaunchedEffect(showDoubleTapHeart) {
            if (showDoubleTapHeart) {
                delay(500)
                showDoubleTapHeart = false
            }
        }

        // Title and Metadata Overlay
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        ) {
            Text(
                text = "FEATURED",
                color = AuraMagenta,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = item.title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${item.year}  ·  ${item.duration}  ·  ${item.genre}",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // Play Button Overlay
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 20.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(DiscoveryGradient),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Play Featured",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
fun AuraContinueWatchingCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .testTag("continue_watching_card"),
        color = AuraCrispWhite,
        border = androidx.compose.foundation.BorderStroke(1.dp, AuraSubtleBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumb
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DiscoveryGradient),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Resume",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = item.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = AuraMidnight
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.progressText.ifEmpty { "In progress" },
                    fontSize = 12.sp,
                    color = AuraMutedSlate
                )
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { item.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = DiscoveryViolet,
                    trackColor = AuraSubtleBorder
                )
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object VideoPreviewPool {
    private var maxActivePreviews: Int = 8 // Default mid-range baseline

    private fun updateMaxPreviews(context: Context) {
        if (maxActivePreviews != 8) return // Only compute once
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val totalRamGb = memoryInfo.totalMem / (1024 * 1024 * 1024)
        
        maxActivePreviews = when {
            totalRamGb < 6 -> 4    // Low-end
            totalRamGb < 12 -> 8   // Mid-range
            else -> 16             // High-end
        }
        Log.i("VideoPreviewPool", "Adaptive allocation: $maxActivePreviews slots (RAM: ${totalRamGb}GB)")
    }
    
    private const val MAX_IDLE_POOL = 4 // Strict limit on idle players to free hardware decoders
    
    // Key is "itemId_locationTag" to prevent player stealing between different UI contexts
    private val activePlayers = mutableMapOf<String, ExoPlayer>()
    private val accessOrder = mutableListOf<String>()
    private val idlePlayers = mutableListOf<ExoPlayer>()

    // Tracks current priority of each active player for adaptive eviction
    private val playerPriorities = mutableMapOf<String, PreviewPriority>()

    // Track listeners to avoid leaks during reuse
    private val playerListeners = mutableMapOf<ExoPlayer, Player.Listener>()

    @Synchronized
    fun acquirePlayer(
        context: Context, 
        itemId: String, 
        locationTag: String,
        uriString: String, 
        imageUrl: String = "",
        priority: PreviewPriority = PreviewPriority.VISIBLE,
        onFirstFrameRendered: (() -> Unit)? = null
    ): ExoPlayer? {
        updateMaxPreviews(context)
        val poolKey = "${itemId}_$locationTag"
        
        // Update priority if already active
        playerPriorities[poolKey] = priority

        if (activePlayers.containsKey(poolKey)) {
            accessOrder.remove(poolKey)
            accessOrder.add(poolKey)
            val existing = activePlayers[poolKey]
            existing?.apply {
                // If reusing an existing player for the same key, update the listener to the new callback
                playerListeners[this]?.let { removeListener(it) }
                val listener = createListener(itemId, this, onFirstFrameRendered)
                addListener(listener)
                playerListeners[this] = listener

                repeatMode = Player.REPEAT_MODE_ONE
                
                // RESOURCE OPTIMIZATION: Only play if VISIBLE. Paused NEARBY saves rendering/decoder cycles.
                playWhenReady = (priority == PreviewPriority.VISIBLE)
                
                if (playbackState == Player.STATE_ENDED) {
                    seekTo(0)
                }
            }
            return existing
        }

        // Adaptive Preparation: If scrolling fast and priority is not VISIBLE, defer
        val isFast = PreviewCoordinator.isScrollingFast.value
        if (isFast && priority != PreviewPriority.VISIBLE) {
            return null
        }

        // Performance Fix: Reuse players from idle pool or evict lowest priority active
        val player = when {
            idlePlayers.isNotEmpty() -> idlePlayers.removeAt(0)
            activePlayers.size < maxActivePreviews -> buildNewPlayer(context)
            else -> {
                // EVICTION STRATEGY: Find the lowest priority item to evict
                val priorityNearby = PreviewCoordinator.priorityNearbyIds.value
                val keyToEvict = accessOrder.firstOrNull { playerPriorities[it] == PreviewPriority.NONE }
                    ?: accessOrder.firstOrNull { 
                        playerPriorities[it] == PreviewPriority.NEARBY && 
                        !priorityNearby.contains(it.substringBeforeLast("_"))
                    }
                    ?: accessOrder.firstOrNull { playerPriorities[it] == PreviewPriority.NEARBY }
                    ?: accessOrder.first() // Fallback to LRU if all are VISIBLE

                accessOrder.remove(keyToEvict)
                playerPriorities.remove(keyToEvict)
                val evictedPlayer = activePlayers.remove(keyToEvict)
                evictedPlayer?.apply {
                    stop()
                    clearMediaItems()
                    idlePlayers.add(this)
                }
                
                // Now that we've freed up a slot (pushed to idlePlayers), pick it up
                if (idlePlayers.isNotEmpty()) idlePlayers.removeAt(0) else buildNewPlayer(context)
            }
        }
        
        return try {
            player.apply {
                val uri = when {
                    uriString.isNotEmpty() && (uriString.startsWith("http") || uriString.startsWith("content") || uriString.startsWith("file") || uriString.startsWith("android.resource")) -> Uri.parse(uriString)
                    uriString.isNotEmpty() && uriString.startsWith("/") -> Uri.fromFile(java.io.File(uriString))
                    imageUrl.isNotEmpty() && (imageUrl.startsWith("http") || imageUrl.startsWith("content") || imageUrl.startsWith("file") || imageUrl.startsWith("android.resource")) -> Uri.parse(imageUrl)
                    imageUrl.isNotEmpty() && imageUrl.startsWith("/") -> Uri.fromFile(java.io.File(imageUrl))
                    else -> Uri.parse("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4")
                }
                
                // Reset player state for reuse
                stop()
                clearMediaItems()
                
                // Replace listener safely
                playerListeners[this]?.let { removeListener(it) }
                val listener = createListener(itemId, this, onFirstFrameRendered)
                addListener(listener)
                playerListeners[this] = listener
                
                setMediaItem(Media3Item.fromUri(uri))
                volume = 0f // Muted preview
                repeatMode = Player.REPEAT_MODE_ONE // Looping preview
                
                prepare()
                // RESOURCE OPTIMIZATION: Only play if VISIBLE. NEARBY pre-warms but stays paused.
                playWhenReady = (priority == PreviewPriority.VISIBLE)
            }
            activePlayers[poolKey] = player
            accessOrder.add(poolKey)
            player
        } catch (e: Exception) {
            Log.e("VideoPreviewPool", "Failed to configure player for $itemId", e)
            null
        }
    }

    private fun createListener(
        itemId: String,
        player: ExoPlayer,
        onFirstFrameRendered: (() -> Unit)?
    ): Player.Listener {
        return object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    player.seekTo(0)
                    player.playWhenReady = true
                }
            }

            override fun onRenderedFirstFrame() {
                // AURA REPAIR: Trigger transition once the video is actually visible
                onFirstFrameRendered?.invoke()
            }

            override fun onPlayerError(error: PlaybackException) {
                val repo = com.example.data.MediaRepository.instance
                val mediaItem = repo.getMediaItemById(itemId)
                repo.recordPlaybackError(error, player, mediaItem)
            }
        }
    }

    private fun buildNewPlayer(context: Context): ExoPlayer {
        // Optimized LoadControl for fast-starting, low-buffer previews
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(1500, 3000, 500, 1000)
            .build()

        // Performance Fix: Disable Audio Renderers for Grid Previews to save decoders (Stage 9 Architecture)
        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .setEnableAudioTrackPlaybackParams(false)
        
        // Force low-resolution track selection and EXPLICITLY DISABLE AUDIO TRACKS
        val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters()
                .setMaxVideoSize(360, 360)
                .setForceLowestBitrate(true)
                .setDisabledTrackTypes(setOf(androidx.media3.common.C.TRACK_TYPE_AUDIO)))
        }

        return ExoPlayer.Builder(context.applicationContext, renderersFactory)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .build()
    }

    @Synchronized
    fun releasePlayer(itemId: String, locationTag: String) {
        val poolKey = "${itemId}_$locationTag"
        Log.d("VideoPreviewPool", "releasePlayer: $poolKey")
        
        // AURA REPAIR: REVERTED RETAIN LOGIC. 
        // Retention was causing leaks during screen transitions because PreviewCoordinator 
        // updates were racing with onDispose. Players are now released back to the idle pool immediately.
        accessOrder.remove(poolKey)
        playerPriorities.remove(poolKey)
        val player = activePlayers.remove(poolKey)
        player?.apply {
            try {
                stop()
                clearMediaItems()
                // AURA REPAIR: Only move to idle pool if under limit, else release to free decoders
                if (idlePlayers.size < MAX_IDLE_POOL) {
                    idlePlayers.add(this)
                } else {
                    release()
                }
            } catch (_: Exception) {}
        }
    }

    @Synchronized
    fun pauseAllActive() {
        Log.d("VideoPreviewPool", "pauseAllActive: Pausing ${activePlayers.size} active preview players")
        activePlayers.values.forEach { player ->
            try {
                if (player.isPlaying) {
                    player.pause()
                }
            } catch (_: Exception) {}
        }
    }

    @Synchronized
    fun releaseIdlePool() {
        Log.d("VideoPreviewPool", "releaseIdlePool: Releasing ${idlePlayers.size} idle players")
        idlePlayers.forEach { player ->
            try {
                player.stop()
                player.clearMediaItems()
                player.release()
            } catch (_: Exception) {}
        }
        idlePlayers.clear()
    }

    @Synchronized
    fun releaseAll() {
        accessOrder.clear()
        playerPriorities.clear()
        activePlayers.values.forEach { it.release() }
        activePlayers.clear()
        idlePlayers.forEach { it.release() }
        idlePlayers.clear()
        playerListeners.clear()
    }
}

@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoTilePreview(
    itemId: String,
    videoUri: String,
    imageUrl: String,
    onFirstFrameRendered: () -> Unit,
    modifier: Modifier = Modifier,
    locationTag: String = "generic"
) {
    val context = LocalContext.current
    var playerState by remember(itemId, locationTag) { mutableStateOf<ExoPlayer?>(null) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    
    // Track priority reactively
    val visibleIds by PreviewCoordinator.visibleIds.collectAsState()
    val nearbyIds by PreviewCoordinator.nearbyIds.collectAsState()
    
    val priority = remember(itemId, visibleIds, nearbyIds, locationTag) {
        if (locationTag.startsWith("compare_")) {
            PreviewPriority.VISIBLE
        } else {
            PreviewCoordinator.getPriority(itemId)
        }
    }

    // Performance Fix: Debounce player acquisition during fast scrolling
    LaunchedEffect(itemId, locationTag, priority) {
        val isFast = PreviewCoordinator.isScrollingFast.value
        
        if (priority == PreviewPriority.NONE) {
            // If it's not even nearby, don't bother and clear state if we had one
            if (playerState != null) {
                VideoPreviewPool.releasePlayer(itemId, locationTag)
                playerState = null
            }
            return@LaunchedEffect
        }
        
        // RESOURCE OPTIMIZATION: If scrolling fast, ONLY prepare VISIBLE items.
        // Defer NEARBY preparation until scrolling slows or stops.
        if (isFast && priority == PreviewPriority.NEARBY) {
            // Drop existing player if we moved from VISIBLE to NEARBY during fast scroll
            if (playerState != null) {
                VideoPreviewPool.releasePlayer(itemId, locationTag)
                playerState = null
            }
            return@LaunchedEffect
        }

        val acquisitionDelay = when {
            locationTag.startsWith("compare_") -> 0L // Immediate acquisition for Compare previews
            priority == PreviewPriority.VISIBLE -> 400L
            priority == PreviewPriority.NEARBY -> 1200L // Longer delay for nearby pre-warming
            else -> 2000L
        }
        
        delay(acquisitionDelay)
        playerState = VideoPreviewPool.acquirePlayer(
            context, 
            itemId, 
            locationTag, 
            videoUri, 
            imageUrl,
            priority = priority,
            onFirstFrameRendered = onFirstFrameRendered
        )
    }

    DisposableEffect(itemId, locationTag) {
        onDispose {
            playerViewRef?.player = null
            VideoPreviewPool.releasePlayer(itemId, locationTag)
            playerState = null
        }
    }

    val player = playerState
    if (player != null) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    this.player = player
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setEnableComposeSurfaceSyncWorkaround(true)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    playerViewRef = this
                }
            },
            update = { playerView ->
                playerViewRef = playerView
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                if (playerView.player != player) {
                    playerView.player = player
                }
                playerView.requestLayout()
            },
            modifier = modifier
                .fillMaxSize()
                .clipToBounds()
        )
    }
}
