package com.example.ui.screens

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.*
import com.example.data.semantic.SearchRequest
import com.example.ui.models.LibraryItemUi
import com.example.ui.components.*
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import coil.compose.AsyncImage
import com.example.util.MediaThumbnailFetcher
import com.example.ui.models.LibraryPresentationState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    mediaItems: List<MediaItem>,
    repository: MediaRepository,
    importProgress: ImportProgressState,
    scanProgress: ScanProgressState = ScanProgressState(),
    onMediaSelect: (MediaItem) -> Unit,
    onCompareLaunch: (Set<String>) -> Unit,
    onFavoriteToggle: ((String) -> Unit)? = null,
    onCreateSearchSeededChannel: ((Channel) -> Unit)? = null,
    onImportUris: ((List<Uri>) -> Unit)? = null,
    onScanDevice: (suspend () -> Unit)? = null,
    onSyncClick: (() -> Unit)? = null,
    deleteLauncher: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest>? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selectedFilter by repository.libraryFilterFlow.collectAsStateWithLifecycle()
    val activeCategory by repository.activeSortCategory.collectAsStateWithLifecycle()
    val standardSort by repository.selectedStandardSort.collectAsStateWithLifecycle()
    val intelligentSort by repository.selectedIntelligentSort.collectAsStateWithLifecycle()
    val searchRequest by repository.librarySearchRequest.collectAsStateWithLifecycle()
    val searchError by repository.searchErrorMessage.collectAsStateWithLifecycle()
    val activeVisualReferences by repository.activeVisualReferences.collectAsStateWithLifecycle()
    val aiState by repository.aiState.collectAsStateWithLifecycle()
    val dbState by repository.databaseState.collectAsStateWithLifecycle()
    
    val latestSortedItemsFromRepo by repository.latestAiSortRecommendation.collectAsStateWithLifecycle()
    val mediaItemsMap by repository.mediaItemsMap.collectAsStateWithLifecycle()

    val libraryPrefs = remember(repository) { repository.libraryPreferences }
    val gridDensity by (libraryPrefs?.gridDensity ?: MutableStateFlow(160f)).collectAsStateWithLifecycle()
    val autoScrollSpeed by (libraryPrefs?.autoScrollSpeed ?: MutableStateFlow(AutoScrollSpeed.MEDIUM)).collectAsStateWithLifecycle()
    var stableMediaItems by remember { mutableStateOf(mediaItems) }
    var lastSortKey by remember { mutableStateOf(Triple(selectedFilter, activeCategory, standardSort)) }

    val currentSortKey = Triple(selectedFilter, activeCategory, standardSort)
    if (currentSortKey != lastSortKey) {
        lastSortKey = currentSortKey
        stableMediaItems = mediaItems
    }

    val visualReadiness by repository.observeVisualIndexReadiness().collectAsStateWithLifecycle(null)

    val uiState = LibraryPresentationState(
        mediaItems = stableMediaItems,
        selectedFilter = selectedFilter,
        activeCategory = activeCategory,
        standardSort = standardSort,
        intelligentSort = intelligentSort,
        searchRequest = searchRequest,
        searchError = searchError,
        activeVisualReferences = activeVisualReferences,
        aiState = aiState,
        dbState = dbState,
        gridDensity = gridDensity,
        autoScrollSpeed = autoScrollSpeed,
        importProgress = importProgress,
        scanProgress = scanProgress,
        provenanceMap = repository.latestLibraryProvenance.collectAsStateWithLifecycle().value,
        admissionProgress = repository.presentationMediaStore.admissionProgress.collectAsStateWithLifecycle().value,
        visualReadiness = visualReadiness
    )

    LibraryContent(
        state = uiState,
        latestSortedItems = latestSortedItemsFromRepo,
        mediaItemsMap = mediaItemsMap,
        onMediaSelect = { item, list ->
            repository.setLibraryPlaylist(list, list.indexOfFirst { it.id == item.id }.coerceAtLeast(0))
            onMediaSelect(item)
        },
        onCompareLaunch = onCompareLaunch,
        onFavoriteToggle = { onFavoriteToggle?.invoke(it) },
        onImportUris = { onImportUris?.invoke(it) },
        onFilterChange = { repository.libraryFilter = it },
        onCategoryChange = { repository.sortCategory = it },
        onStandardSortChange = { repository.standardSort = it },
        onIntelligentSortChange = { repository.intelligentSort = it },
        onSearchQueryChange = { repository.librarySearchQuery = it },
        onSyncClick = {
            if (onSyncClick != null) {
                onSyncClick.invoke()
            } else if (dbState != DatabaseState.READY) {
                repository.initDatabase(context)
            }
        },
        onAutoScrollSpeedChange = { libraryPrefs?.setAutoScrollSpeed(it) },
        onGridDensityChange = { libraryPrefs?.setGridDensity(it) },
        onRefresh = { 
            repository.refreshLibrary(context)
            repository.refreshSort()
        },
        onSafeDeleteRequest = { ids, launcher ->
            val itemsToDelete = ids.mapNotNull { mediaItemsMap[it] }
            repository.safeDeleteManager.requestDeletion(
                context = context,
                items = itemsToDelete,
                recommendations = emptyList(),
                launcher = launcher
            )
        },
        onLike = { id ->
            repository.recordLike(id)
            repository.addToFavorites(id)
        },
        onAddVisualReference = { repository.addVisualReference(it) },
        onRemoveVisualReference = { index -> 
            val list = repository.activeVisualReferences.value
            if (index in list.indices) {
                repository.removeVisualReference(list[index].id)
            }
        },
        onClearSearch = { repository.clearSearch() },
        onSearchByImage = { bitmap, uri -> repository.searchByImage(bitmap, uri) },
        onSearchByMultipleImages = { items -> repository.searchByMultipleImages(items) },
        onSaveSearchAsChannel = { channel -> onCreateSearchSeededChannel?.invoke(channel) ?: scope.launch { repository.saveSearchSeededChannel(channel) } },
        onTriggerScan = {
            scope.launch {
                onScanDevice?.invoke()
            }
        },
        deletionStateFlow = repository.safeDeleteManager.deletionState,
        libraryScrollIndex = repository.libraryScrollIndex,
        libraryScrollOffset = repository.libraryScrollOffset,
        onScrollPositionChange = { idx, offset ->
            repository.libraryScrollIndex = idx
            repository.libraryScrollOffset = offset
        },
        deleteLauncher = deleteLauncher,
        onLoadNextPage = { repository.loadNextLibraryPage() },
        onRetryFailedEnrichment = { repository.retryFailedEnrichment() },
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryContent(
    state: LibraryPresentationState,
    latestSortedItems: List<LibraryItemUi>,
    mediaItemsMap: Map<String, MediaItem>,
    onFilterChange: (String) -> Unit,
    onCategoryChange: (SortCategory) -> Unit,
    onStandardSortChange: (StandardSortOption) -> Unit,
    onIntelligentSortChange: (IntelligentSortOption) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSyncClick: () -> Unit,
    onAutoScrollSpeedChange: (AutoScrollSpeed) -> Unit,
    onGridDensityChange: (Float) -> Unit,
    onMediaSelect: (MediaItem, List<MediaItem>) -> Unit,
    onCompareLaunch: (Set<String>) -> Unit,
    onFavoriteToggle: (String) -> Unit,
    onImportUris: (List<Uri>) -> Unit,
    onTriggerScan: () -> Unit,
    onRefresh: () -> Unit,
    onSafeDeleteRequest: (Set<String>, androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest>) -> Unit,
    onLike: (String) -> Unit,
    onAddVisualReference: (MediaItem) -> Unit,
    onRemoveVisualReference: (Int) -> Unit,
    onClearSearch: () -> Unit,
    onSearchByImage: (Bitmap, String) -> Unit,
    onSearchByMultipleImages: (List<MediaItem>) -> Unit,
    onSaveSearchAsChannel: (Channel) -> Unit = {},
    deletionStateFlow: kotlinx.coroutines.flow.StateFlow<com.example.data.cleanup.DeletionState>,
    libraryScrollIndex: Int,
    libraryScrollOffset: Int,
    onScrollPositionChange: (Int, Int) -> Unit,
    deleteLauncher: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.IntentSenderRequest>? = null,
    onLoadNextPage: () -> Unit = {},
    onRetryFailedEnrichment: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val coroutineScope = rememberCoroutineScope()
    val visualReadiness = state.visualReadiness

    // AURA REPAIR: Global search error feedback
    LaunchedEffect(state.searchError) {
        state.searchError?.let { error ->
            val msg = if (error == "STILL_PROCESSING") "Visual Search Failed: Still Processing" else error
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    // Multi-select state
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    // AURA REPAIR: Bug A - UI-owned search input state to prevent typing corruption
    var searchFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(state.searchRequest.query ?: ""))
    }

    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = libraryScrollIndex,
        initialFirstVisibleItemScrollOffset = libraryScrollOffset
    )

    // Library Browser Auto-Paging: Trigger when <= 20 items remain in current view
    val shouldLoadMore by remember {
        derivedStateOf {
            val total = gridState.layoutInfo.totalItemsCount
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && (total - lastVisible) <= 20
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            com.example.data.MediaRepository.getInstance(context).loadNextLibraryPage()
        }
    }

    var isAutoScrollActive by remember { mutableStateOf(false) }
    var lastInteractionTime by remember { mutableLongStateOf(0L) }

    // Auto-Scroll Engine (Update 4)
    LaunchedEffect(isAutoScrollActive, state.autoScrollSpeed, lastInteractionTime, isSelectionMode) {
        if (!isAutoScrollActive || isSelectionMode) return@LaunchedEffect

        // Resumption Delay: Wait 2s after last interaction
        val now = System.currentTimeMillis()
        val timeSinceInteraction = now - lastInteractionTime
        if (timeSinceInteraction < 2000) {
            delay(2000 - timeSinceInteraction)
        }

        var lastFrameTimeNanos = 0L
        while (isActive) {
            withFrameNanos { frameTimeNanos ->
                if (lastFrameTimeNanos == 0L) {
                    lastFrameTimeNanos = frameTimeNanos
                    return@withFrameNanos
                }
                
                val elapsedSeconds = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f
                lastFrameTimeNanos = frameTimeNanos
                
                val pixelsToScroll = state.autoScrollSpeed.pixelsPerSecond * elapsedSeconds
                
                // Perform scroll
                coroutineScope.launch {
                    gridState.scrollBy(pixelsToScroll)
                }
            }
        }
    }

    var isSearchActive by remember { mutableStateOf(false) }
    // Sync searchQuery with searchRequest when it changes from other sources
    LaunchedEffect(state.searchRequest, state.activeVisualReferences) {
        isSearchActive = state.activeVisualReferences.isNotEmpty() || state.searchRequest !is SearchRequest.Text || state.searchRequest.query?.isNotEmpty() == true || state.searchRequest.visualVector != null
    }

    // Automatically scroll to top when filters or sorts change
    var isFirstLoad by remember { mutableStateOf(true) }
    LaunchedEffect(state.selectedFilter, state.activeCategory, state.standardSort, state.intelligentSort) {
        if (isFirstLoad) {
            isFirstLoad = false
        } else {
            gridState.scrollToItem(0)
        }
    }

    // Clear selection mode when deletion finishes
    val deletionState by deletionStateFlow.collectAsStateWithLifecycle()
    LaunchedEffect(deletionState) {
        if (deletionState == com.example.data.cleanup.DeletionState.CONFIRMED ||
            deletionState == com.example.data.cleanup.DeletionState.CANCELLED ||
            deletionState == com.example.data.cleanup.DeletionState.FAILED) {
            isSelectionMode = false
            selectedIds = emptySet()
        }
    }

    val showScrollToTop by remember {
        derivedStateOf { gridState.firstVisibleItemIndex > 8 }
    }

    // AURA STABILITY FIX: UI-side list stability to prevent items from jumping while viewing.
    // In unstable modes (Discover), we lock the list and only update it on manual refresh or sort change.
    var stableItems by remember { mutableStateOf<List<LibraryItemUi>>(emptyList()) }
    val isUnstableMode = state.activeCategory == SortCategory.INTELLIGENT && state.intelligentSort == IntelligentSortOption.DISCOVER

    LaunchedEffect(latestSortedItems) {
        val currentIds = latestSortedItems.map { it.id }
        val stableIds = stableItems.map { it.id }
        if (stableItems.isEmpty() || currentIds.toSet() != stableIds.toSet()) {
            stableItems = latestSortedItems
        }
    }

    LaunchedEffect(state.activeCategory, state.intelligentSort, state.selectedFilter, state.searchRequest) {
        // Sort/Filter change acts as a "thaw" signal
        stableItems = latestSortedItems
    }

    // Filter out deleted items from the stable snapshot to ensure they disappear immediately
    val displayItems = remember(stableItems, mediaItemsMap) {
        stableItems.filter { mediaItemsMap.containsKey(it.id) }
    }

    LaunchedEffect(gridState, displayItems) {
        snapshotFlow { 
            val layoutInfo = gridState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            val visibleIds = visibleItems.mapNotNull { it.key as? String }
            val allIds = displayItems.map { it.id }
            val firstIdx = gridState.firstVisibleItemIndex
            Triple(visibleIds, allIds, firstIdx to gridState.isScrollInProgress)
        }.collect { (visible, all, scrollState) ->
            val (firstIdx, scrolling) = scrollState
            PreviewCoordinator.updateWindow(visible, all, firstIdx)
            // For now, treat any scroll as "active". In a real velocity check, 
            // we'd use a threshold.
            PreviewCoordinator.setScrollingFast(scrolling)
        }
    }

    LaunchedEffect(gridState) {
        snapshotFlow { Pair(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset) }
            .collect { (index, offset) ->
                onScrollPositionChange(index, offset)
            }
    }

    // Auto-load next Library page when user scrolls near the end (~20 items remaining)
    LaunchedEffect(gridState, displayItems.size) {
        snapshotFlow {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible to displayItems.size
        }.collect { (lastVisible, total) ->
            if (total > 0 && lastVisible >= (total - 20).coerceAtLeast(0)) {
                onLoadNextPage()
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            onImportUris(uris)
        }
    }

    val visualSearchLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val bitmap = MediaThumbnailFetcher.getThumbnail(context, uri.toString())
                if (bitmap != null) {
                    onSearchByImage(bitmap, uri.toString())
                    isSearchActive = true
                }
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isLandscape = this.maxWidth > this.maxHeight
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Selection Header or Sticky Search Bar
            if (isSelectionMode) {
                // Ensure Auto-Scroll is disabled in selection mode
                SideEffect { isAutoScrollActive = false }

                SelectionHeader(
                    selectedCount = selectedIds.size,
                    isSearchActive = searchFieldValue.text.isNotBlank() || state.activeVisualReferences.isNotEmpty(),
                    onDelete = {
                        if (deleteLauncher != null) {
                            onSafeDeleteRequest(selectedIds, deleteLauncher)
                        }
                    },
                    onCompare = {
                        if (selectedIds.size >= 4) {
                            onCompareLaunch(selectedIds)
                        }
                    },
                    onSearchSimilar = {
                        val selectedItems = selectedIds.mapNotNull { mediaItemsMap[it] }
                        if (selectedItems.isNotEmpty()) {
                            if (searchFieldValue.text.isBlank() && state.activeVisualReferences.isEmpty()) {
                                onClearSearch()
                            }

                            if (selectedItems.size >= 2) {
                                onSearchByMultipleImages(selectedItems)
                            } else {
                                onAddVisualReference(selectedItems[0])
                            }
                            isSelectionMode = false
                            selectedIds = emptySet()
                        }
                    },
                    onAddToSearch = {
                        val selectedItems = selectedIds.mapNotNull { mediaItemsMap[it] }
                        selectedItems.forEach { onAddVisualReference(it) }
                        isSelectionMode = false
                        selectedIds = emptySet()
                    },
                    onCancel = {
                        isSelectionMode = false
                        selectedIds = emptySet()
                    }
                )
            } else {
                if (!isLandscape) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = AuraSpacing.M, vertical = AuraSpacing.XXS),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        AuraSectionHeader(
                            title = "Library",
                            subtitle = "Your complete media collection",
                            modifier = Modifier.weight(1f)
                        )
                        
                        if (state.aiState != AIState.READY && state.aiState != AIState.NOT_INITIALIZED && state.aiState != AIState.DISABLED) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(end = AuraSpacing.S)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = DiscoveryViolet
                                )
                                Spacer(modifier = Modifier.width(AuraSpacing.XS))
                                Text(
                                    text = "Aura is learning...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = DiscoveryViolet,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        
                        if (state.scanProgress.isScanning || state.importProgress.isImporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp).padding(end = AuraSpacing.M),
                                strokeWidth = 2.dp,
                                color = DiscoveryViolet
                            )
                        }
                    }
                }

                LibrarySearchBar(
                    textFieldValue = searchFieldValue,
                    onQueryChange = {
                        searchFieldValue = it
                        onSearchQueryChange(it.text)
                    },
                    activeReferences = state.activeVisualReferences,
                    onRemoveReference = { onRemoveVisualReference(it) },
                    onClearSearch = {
                        searchFieldValue = TextFieldValue("")
                        onClearSearch()
                    },
                    onImageSearchClick = {
                        visualSearchLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                )

                if (searchFieldValue.text.isNotBlank() || state.activeVisualReferences.isNotEmpty()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AuraSpacing.M, vertical = AuraSpacing.XXS),
                        color = AuraCrispWhite,
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AuraSubtleBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = AuraSpacing.M, vertical = AuraSpacing.S),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Turn this search into a channel",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AuraMidnight,
                                fontWeight = FontWeight.SemiBold
                            )
                            Button(
                                onClick = {
                                    val title = searchFieldValue.text.ifBlank {
                                        if (state.activeVisualReferences.isNotEmpty()) "Visual Search" else "Search Channel"
                                    }
                                    val channel = com.example.data.Channel(
                                        id = "channel_search_${System.currentTimeMillis()}",
                                        title = title,
                                        query = searchFieldValue.text,
                                        referenceMediaIds = state.activeVisualReferences.map { it.id },
                                        channelKind = com.example.data.ChannelKind.SEARCH_SEEDED,
                                        strategyId = "SEARCH_SEEDED",
                                        isProGated = true
                                    )
                                    onSaveSearchAsChannel(channel)
                                    android.widget.Toast.makeText(context, "Saved as Channel: $title", android.widget.Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = DiscoveryViolet),
                                contentPadding = PaddingValues(horizontal = AuraSpacing.M, vertical = 4.dp),
                                modifier = Modifier.semantics { contentDescription = "Make this search a channel" }
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Make Channel", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            if (!isSelectionMode) {
                LibraryFilterSortStrip(
                    selectedFilter = state.selectedFilter,
                    onFilterChange = { onFilterChange(it) },
                    activeCategory = state.activeCategory,
                    onCategoryChange = { onCategoryChange(it) },
                    standardSort = state.standardSort,
                    onStandardSortChange = { onStandardSortChange(it) },
                    intelligentSort = state.intelligentSort,
                    onIntelligentSortChange = { onIntelligentSortChange(it) },
                    isAutoScrollActive = isAutoScrollActive,
                    onAutoScrollToggle = { isAutoScrollActive = it },
                    autoScrollSpeed = state.autoScrollSpeed,
                    onAutoScrollSpeedChange = { onAutoScrollSpeedChange(it) }
                )
            }

            if (!state.admissionProgress.isAnalysisComplete) {
                com.example.ui.components.AdmissionProgressBanner(progress = state.admissionProgress)
            }

            PullToRefreshBox(
                isRefreshing = state.scanProgress.isScanning && state.scanProgress.isManual,
                onRefresh = { 
                    onRefresh()
                    stableItems = emptyList() // Force reload on manual refresh
                },
                modifier = Modifier.fillMaxSize()
            ) {
                if (displayItems.isEmpty()) {
                    val isLibraryEmpty = state.mediaItems.isEmpty()
                    val isActivelyScanning = state.scanProgress.isScanning || (!state.admissionProgress.isAnalysisComplete && state.admissionProgress.totalScanned > 0)

                    if (isActivelyScanning && isLibraryEmpty) {
                        com.example.ui.components.LibraryScanningPlaceholder()
                    } else if (isSearchActive) {
                        EmptySearchStateView(
                            isStillProcessing = state.searchError == "STILL_PROCESSING",
                            onClearSearch = {
                                isSearchActive = false
                                searchFieldValue = TextFieldValue("")
                                onClearSearch()
                            }
                        )
                    } else if (!isLibraryEmpty && state.activeCategory == SortCategory.INTELLIGENT) {
                        EmptySortStateView(
                            vibeName = state.intelligentSort.displayName,
                            onReset = {
                                onCategoryChange(SortCategory.STANDARD)
                                onStandardSortChange(StandardSortOption.NEWEST_FIRST)
                            }
                        )
                    } else {
                        EmptyLibraryView(
                            onImportClick = { photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                            onScanClick = { onTriggerScan() }
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, _, zoom, _ ->
                                    // 1. Pause auto-scroll on any manual gesture
                                    lastInteractionTime = System.currentTimeMillis()
                                    
                                    // 2. Handle Pinch-to-Zoom
                                    if (zoom != 1f) {
                                        val newDensity = state.gridDensity / zoom
                                        onGridDensityChange(newDensity)
                                    }
                                }
                            }
                    ) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = state.gridDensity.dp),
                            state = gridState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = AuraSpacing.M, 
                                end = AuraSpacing.M, 
                                top = AuraSpacing.XS, 
                                bottom = AuraSpacing.XXL
                            ),
                            horizontalArrangement = Arrangement.spacedBy(AuraSpacing.GridGap),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            itemsIndexed(
                                items = displayItems,
                                key = { _, item -> item.id }
                            ) { _, item ->
                                val mediaItem = mediaItemsMap[item.id]
                                if (mediaItem != null) {
                                    val rankScore = state.provenanceMap[item.id]?.rankScore
                                    AuraMediaTile(
                                        item = mediaItem,
                                        rankScore = rankScore,
                                        isSelected = selectedIds.contains(item.id),
                                        isSelectionMode = isSelectionMode,
                                        onClick = {
                                            lastInteractionTime = System.currentTimeMillis()
                                            if (isSelectionMode) {
                                                selectedIds = if (selectedIds.contains(item.id)) {
                                                    selectedIds - item.id
                                                } else {
                                                    selectedIds + item.id
                                                }
                                                if (selectedIds.isEmpty()) isSelectionMode = false
                                            } else {
                                                val currentMediaItems = displayItems.mapNotNull { mediaItemsMap[it.id] }
                                                onMediaSelect(mediaItem, currentMediaItems)
                                            }
                                        },
                                        onLongClick = {
                                            lastInteractionTime = System.currentTimeMillis()
                                            if (!isSelectionMode) {
                                                isSelectionMode = true
                                                selectedIds = setOf(item.id)
                                            } else {
                                                selectedIds = if (selectedIds.contains(item.id)) {
                                                    selectedIds - item.id
                                                } else {
                                                    selectedIds + item.id
                                                }
                                                if (selectedIds.isEmpty()) isSelectionMode = false
                                            }
                                        },
                                        onLike = {
                                            onLike(item.id)
                                        }
                                    )
                                }
                            }
                        }

                        // Floating Scroll-to-Top Button
                        androidx.compose.animation.AnimatedVisibility(
                            visible = showScrollToTop,
                            enter = fadeIn() + slideInVertically { it / 2 },
                            exit = fadeOut() + slideOutVertically { it / 2 },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(bottom = 72.dp, end = AuraSpacing.M)
                        ) {
                            SmallFloatingActionButton(
                                onClick = {
                                    coroutineScope.launch {
                                        gridState.animateScrollToItem(0)
                                    }
                                },
                                containerColor = DiscoveryViolet,
                                contentColor = Color.White,
                                shape = CircleShape
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowUp,
                                    contentDescription = "Scroll to top"
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptySortStateView(
    vibeName: String,
    onReset: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AuraSpacing.XL),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.FilterListOff,
            contentDescription = null,
            tint = AuraMutedSlate.copy(alpha = 0.3f),
            modifier = Modifier.size(56.dp)
        )
        Spacer(modifier = Modifier.height(AuraSpacing.M))
        Text(
            text = "No items in $vibeName",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black,
            color = AuraMidnight,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(AuraSpacing.XS))
        Text(
            text = "Aura couldn't find any media that matches this vibe right now. Try interacting with more items or choosing a different sort.",
            style = MaterialTheme.typography.bodyMedium,
            color = AuraSlate,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
        Spacer(modifier = Modifier.height(AuraSpacing.L))
        AuraButton(
            text = "RESET TO STANDARD",
            onClick = onReset
        )
    }
}

@Composable
private fun EmptySearchStateView(
    isStillProcessing: Boolean,
    onClearSearch: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AuraSpacing.XL),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (isStillProcessing) Icons.Default.Sync else Icons.Default.SearchOff,
            contentDescription = null,
            tint = AuraMutedSlate.copy(alpha = 0.3f),
            modifier = Modifier.size(56.dp)
        )
        Spacer(modifier = Modifier.height(AuraSpacing.M))
        Text(
            text = if (isStillProcessing) "Analyzing Media" else "No matches found",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black,
            color = AuraMidnight,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(AuraSpacing.XS))
        Text(
            text = if (isStillProcessing) 
                "Aura is still generating embeddings for your library. This may take a few moments." 
                else "We couldn't find any media matching your current constraints.",
            style = MaterialTheme.typography.bodyMedium,
            color = AuraSlate,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
        Spacer(modifier = Modifier.height(AuraSpacing.L))
        AuraButton(
            text = "CLEAR SEARCH",
            onClick = onClearSearch
        )
    }
}

@Composable
private fun SelectionHeader(
    selectedCount: Int,
    isSearchActive: Boolean,
    onDelete: () -> Unit,
    onCompare: () -> Unit,
    onSearchSimilar: () -> Unit,
    onAddToSearch: () -> Unit,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DiscoveryGradient)
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = AuraSpacing.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(AuraSpacing.S))
            Text(
                text = "$selectedCount SELECTED",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
        }
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isSearchActive) {
                Surface(
                    onClick = onAddToSearch,
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.height(30.dp)
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = AuraSpacing.S),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "ADD TO SEARCH",
                            color = DiscoveryViolet,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
                Spacer(modifier = Modifier.width(AuraSpacing.S))
            }

            if (selectedCount == 1) {
                IconButton(onClick = onSearchSimilar, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.ImageSearch, contentDescription = "Search Similar", tint = Color.White)
                }
                Spacer(modifier = Modifier.width(AuraSpacing.XS))
            } else if (selectedCount >= 2) {
                Surface(
                    onClick = onSearchSimilar,
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.height(30.dp)
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = AuraSpacing.XS),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AutoAwesomeMotion, contentDescription = null, tint = DiscoveryViolet, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(AuraSpacing.XXS))
                            Text(
                                text = "COMMON",
                                color = DiscoveryViolet,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(AuraSpacing.S))
            }

            Surface(
                onClick = onCompare,
                enabled = selectedCount >= 4,
                shape = CircleShape,
                color = if (selectedCount >= 4) Color.White else Color.White.copy(alpha = 0.2f),
                modifier = Modifier.height(30.dp)
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = AuraSpacing.S),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "COMPARE",
                        color = if (selectedCount >= 4) DiscoveryViolet else Color.White.copy(alpha = 0.5f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(AuraSpacing.S))

            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
            }
        }
    }
}

@Composable
fun LibraryFilterSortStrip(
    selectedFilter: String,
    onFilterChange: (String) -> Unit,
    activeCategory: SortCategory,
    onCategoryChange: (SortCategory) -> Unit,
    standardSort: StandardSortOption,
    onStandardSortChange: (StandardSortOption) -> Unit,
    intelligentSort: IntelligentSortOption,
    onIntelligentSortChange: (IntelligentSortOption) -> Unit,
    isAutoScrollActive: Boolean,
    onAutoScrollToggle: (Boolean) -> Unit,
    autoScrollSpeed: AutoScrollSpeed,
    onAutoScrollSpeedChange: (AutoScrollSpeed) -> Unit,
    modifier: Modifier = Modifier
) {
    var isSortMenuExpanded by remember { mutableStateOf(false) }

    val currentSortTitle = if (activeCategory == SortCategory.STANDARD) {
        when (standardSort) {
            StandardSortOption.NEWEST_FIRST -> "Newest"
            StandardSortOption.RATING -> "Rating"
            StandardSortOption.MOST_PLAYED -> "Popular"
            StandardSortOption.RECENTLY_PLAYED -> "Recent"
            StandardSortOption.TITLE_ASC -> "A-Z"
            StandardSortOption.TITLE_DESC -> "Z-A"
            StandardSortOption.SHORTEST_DURATION -> "Shortest"
            StandardSortOption.LONGEST_DURATION -> "Longest"
            StandardSortOption.SIZE -> "Size"
            StandardSortOption.RANDOM -> "Shuffle"
            StandardSortOption.LEAST_PLAYED -> "Unplayed"
        }
    } else {
        when (intelligentSort) {
            IntelligentSortOption.PERSONALIZED -> "Personalized"
            IntelligentSortOption.DISCOVER -> "Discover"
            IntelligentSortOption.SURPRISE_ME -> "Surprise Me"
            else -> intelligentSort.displayName
        }
    }

    val currentSortIcon = if (activeCategory == SortCategory.STANDARD) {
        Icons.Default.SwapVert
    } else {
        Icons.Default.AutoAwesome
    }

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AuraSpacing.M, vertical = AuraSpacing.XXS)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left Side: Media-type Filter Chips
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AuraSpacing.S)
            ) {
                AuraFilterChip(
                    label = "All",
                    isSelected = selectedFilter == "ALL",
                    onClick = { onFilterChange("ALL") }
                )
                AuraFilterChip(
                    label = "Photos",
                    isSelected = selectedFilter == "PHOTO",
                    onClick = { onFilterChange("PHOTO") }
                )
                AuraFilterChip(
                    label = "Videos",
                    isSelected = selectedFilter == "VIDEO",
                    onClick = { onFilterChange("VIDEO") }
                )
            }

            Spacer(modifier = Modifier.width(AuraSpacing.M))

            // Right Side: Unified Sort Selector + Auto-Scroll Toggle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AuraSpacing.S)
            ) {
                // Unified Sort Selector Pill
                Box {
                    Surface(
                        onClick = { isSortMenuExpanded = true },
                        shape = RoundedCornerShape(16.dp),
                        color = if (activeCategory == SortCategory.INTELLIGENT) DiscoveryViolet.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(
                            width = 1.dp,
                            brush = if (activeCategory == SortCategory.INTELLIGENT) DiscoveryGradient else SolidColor(MaterialTheme.colorScheme.outlineVariant)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .height(32.dp)
                                .padding(horizontal = AuraSpacing.M),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = currentSortIcon,
                                contentDescription = null,
                                tint = if (activeCategory == SortCategory.INTELLIGENT) DiscoveryViolet else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = currentSortTitle,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (activeCategory == SortCategory.INTELLIGENT) DiscoveryViolet else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Sort Menu",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = isSortMenuExpanded,
                        onDismissRequest = { isSortMenuExpanded = false },
                        modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                    ) {
                        // Section 1: Standard Sorts
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "STANDARD SORTS",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Black,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            onClick = {},
                            enabled = false
                        )

                        listOf(
                            StandardSortOption.NEWEST_FIRST to "Newest",
                            StandardSortOption.RATING to "Rating",
                            StandardSortOption.MOST_PLAYED to "Popular",
                            StandardSortOption.RECENTLY_PLAYED to "Recent",
                            StandardSortOption.TITLE_ASC to "Title A-Z",
                            StandardSortOption.TITLE_DESC to "Title Z-A",
                            StandardSortOption.SHORTEST_DURATION to "Shortest",
                            StandardSortOption.LONGEST_DURATION to "Longest",
                            StandardSortOption.SIZE to "Largest File",
                            StandardSortOption.RANDOM to "Shuffle"
                        ).forEach { (opt, title) ->
                            val isSel = activeCategory == SortCategory.STANDARD && standardSort == opt
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSel) DiscoveryViolet else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                leadingIcon = {
                                    if (isSel) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = DiscoveryViolet, modifier = Modifier.size(16.dp))
                                    }
                                },
                                onClick = {
                                    isSortMenuExpanded = false
                                    onCategoryChange(SortCategory.STANDARD)
                                    onStandardSortChange(opt)
                                }
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

                        // Section 2: Aura AI Vibes
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "AURA AI VIBES",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Black,
                                    color = DiscoveryViolet
                                )
                            },
                            onClick = {},
                            enabled = false
                        )

                        listOf(
                            IntelligentSortOption.PERSONALIZED to "Personalized",
                            IntelligentSortOption.DISCOVER to "Discover",
                            IntelligentSortOption.SURPRISE_ME to "Surprise Me"
                        ).forEach { (opt, title) ->
                            val isSel = activeCategory == SortCategory.INTELLIGENT && intelligentSort == opt
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSel) DiscoveryViolet else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                leadingIcon = {
                                    if (isSel) {
                                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = DiscoveryViolet, modifier = Modifier.size(16.dp))
                                    }
                                },
                                onClick = {
                                    isSortMenuExpanded = false
                                    onCategoryChange(SortCategory.INTELLIGENT)
                                    onIntelligentSortChange(opt)
                                }
                            )
                        }
                    }
                }

                // Auto-Scroll Toggle & Speed Selector
                AutoScrollToggle(
                    isEnabled = isAutoScrollActive,
                    onToggle = onAutoScrollToggle
                )

                if (isAutoScrollActive) {
                    AutoScrollSpeedSelector(
                        currentSpeed = autoScrollSpeed,
                        onSpeedSelected = onAutoScrollSpeedChange
                    )
                }
            }
        }

        HorizontalDivider(color = AuraSubtleBorder, thickness = 0.5.dp)
    }
}

@Composable
fun LibrarySearchBar(
    textFieldValue: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    activeReferences: List<MediaItem>,
    onRemoveReference: (Int) -> Unit,
    onClearSearch: () -> Unit,
    onImageSearchClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AuraSpacing.M, vertical = AuraSpacing.XXS),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AuraSpacing.M, vertical = AuraSpacing.XS)
        ) {
            if (activeReferences.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = AuraSpacing.XXS),
                    horizontalArrangement = Arrangement.spacedBy(AuraSpacing.XS),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    itemsIndexed(activeReferences, key = { _, item -> item.id }) { index, item ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DiscoveryViolet.copy(alpha = 0.12f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, DiscoveryViolet.copy(alpha = 0.3f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = AuraSpacing.S, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AuraMediaThumbnail(
                                    itemId = item.id,
                                    mediaType = item.mediaType,
                                    imageUrl = item.imageUrl,
                                    uriPath = item.uriPath,
                                    title = "",
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    locationTag = "search_chip"
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = item.title.ifBlank { "Visual Ref" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(
                                    onClick = { onRemoveReference(index) },
                                    modifier = Modifier.size(16.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove reference",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )

                Spacer(modifier = Modifier.width(AuraSpacing.S))

                androidx.compose.foundation.text.BasicTextField(
                    value = textFieldValue,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            innerTextField()
                        }
                    }
                )

                if (textFieldValue.text.isNotEmpty() || activeReferences.isNotEmpty()) {
                    IconButton(
                        onClick = onClearSearch,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onImageSearchClick,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ImageSearch,
                        contentDescription = "Visual search",
                        tint = DiscoveryViolet,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchHeader(
    activeReferences: List<MediaItem>,
    textFieldValue: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    onImageSearchClick: () -> Unit,
    onRemoveReference: (Int) -> Unit,
    onExit: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding(),
        color = AuraCrispWhite,
        border = androidx.compose.foundation.BorderStroke(width = 0.5.dp, color = AuraSubtleBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = AuraSpacing.S),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onExit, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Exit Search", tint = AuraMidnight)
            }
            
            if (activeReferences.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .padding(horizontal = AuraSpacing.XXS)
                        .widthIn(max = 240.dp), // Increased slightly for better multi-visibility
                    verticalAlignment = Alignment.CenterVertically,
                    contentPadding = PaddingValues(end = 4.dp)
                ) {
                    itemsIndexed(activeReferences, key = { _, item -> item.id }) { index, item ->
                        Box(modifier = Modifier.padding(end = 6.dp)) {
                            Surface(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(AuraSpacing.CornerRadiusSmall)),
                                color = AuraSubtleBorder,
                                onClick = { onRemoveReference(index) }
                            ) {
                                AuraMediaThumbnail(
                                    itemId = item.id,
                                    mediaType = item.mediaType,
                                    imageUrl = item.imageUrl,
                                    uriPath = item.uriPath,
                                    title = "",
                                    modifier = Modifier.fillMaxSize(),
                                    locationTag = "search_header"
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.25f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close, 
                                        contentDescription = "Remove", 
                                        tint = Color.White, 
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                    
                    item {
                        Surface(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(AuraSpacing.CornerRadiusSmall))
                                .border(1.dp, AuraSubtleBorder, RoundedCornerShape(AuraSpacing.CornerRadiusSmall)),
                            color = Color.Transparent,
                            onClick = onImageSearchClick
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Add, 
                                    contentDescription = "Add Reference", 
                                    tint = DiscoveryViolet, 
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            TextField(
                value = textFieldValue,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = if (activeReferences.isNotEmpty()) {
                    { Text("Add constraint...", style = MaterialTheme.typography.bodyLarge, color = AuraMutedSlate) }
                } else null,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = DiscoveryViolet
                ),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = AuraMidnight, fontWeight = FontWeight.Bold),
                trailingIcon = {
                    if (activeReferences.isEmpty()) {
                        IconButton(onClick = onImageSearchClick, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.ImageSearch, contentDescription = "Search by Image", tint = DiscoveryViolet, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun UtilityControlsRow(
    selectedFilter: String,
    onFilterChange: (String) -> Unit,
    onSearchClick: () -> Unit,
    onImportClick: () -> Unit,
    onSyncClick: () -> Unit,
    onScrollToTop: () -> Unit,
    showScrollToTop: Boolean,
    dbState: DatabaseState,
    isCompact: Boolean = false,
    isAutoScrollActive: Boolean = false,
    onAutoScrollToggle: (Boolean) -> Unit = {},
    autoScrollSpeed: AutoScrollSpeed = AutoScrollSpeed.MEDIUM,
    onAutoScrollSpeedChange: (AutoScrollSpeed) -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AuraSpacing.M, vertical = AuraSpacing.XXS), // More compact
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Side: Filters or Auto-Scroll
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AuraSpacing.S), // Increased gap for chips
            modifier = Modifier.weight(1f)
        ) {
            if (!isCompact) {
                AuraFilterChip(
                    label = "All",
                    isSelected = selectedFilter == "ALL",
                    onClick = { onFilterChange("ALL") }
                )
                AuraFilterChip(
                    label = "Photos",
                    isSelected = selectedFilter == "PHOTO",
                    onClick = { onFilterChange("PHOTO") }
                )
                AuraFilterChip(
                    label = "Videos",
                    isSelected = selectedFilter == "VIDEO",
                    onClick = { onFilterChange("VIDEO") }
                )
            } else {
                // Compact mode (Landscape)
                IconButton(onClick = { onFilterChange("ALL") }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = "All", tint = if (selectedFilter == "ALL") DiscoveryViolet else AuraMutedSlate)
                }
            }
        }

        // Right Side: Action Icons + AutoScroll
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AuraSpacing.ControlGap)
        ) {
            AutoScrollToggle(
                isEnabled = isAutoScrollActive,
                onToggle = onAutoScrollToggle
            )
            
            if (isAutoScrollActive) {
                AutoScrollSpeedSelector(
                    currentSpeed = autoScrollSpeed,
                    onSpeedSelected = onAutoScrollSpeedChange
                )
            }

            if (showScrollToTop && !isCompact) {
                IconButton(onClick = onScrollToTop, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "Scroll to top", tint = AuraMutedSlate)
                }
            }
            
            IconButton(onClick = onSearchClick, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Search, contentDescription = "Search", tint = AuraMidnight)
            }
            
            IconButton(onClick = onImportClick, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Import", tint = AuraMidnight)
            }
            
            IconButton(onClick = onSyncClick, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Default.Sync, 
                    contentDescription = "Sync", 
                    tint = if (dbState == DatabaseState.READY) DiscoveryViolet else AuraMutedSlate
                )
            }
        }
    }
}

@Composable
private fun CompactControlsRow(
    activeCategory: SortCategory,
    onCategoryChange: (SortCategory) -> Unit,
    standardSort: StandardSortOption,
    onStandardSortChange: (StandardSortOption) -> Unit,
    intelligentSort: IntelligentSortOption,
    onIntelligentSortChange: (IntelligentSortOption) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AuraSpacing.M, vertical = AuraSpacing.XXS), // More compact vertical padding
        horizontalArrangement = Arrangement.spacedBy(AuraSpacing.S),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AuraSortSelector(
            label = "Mode",
            currentOption = if (activeCategory == SortCategory.STANDARD) "Standard" else "Aura AI",
            isSelected = true,
            options = SortCategory.entries,
            onOptionSelected = onCategoryChange,
            onPillClick = { /* No-op for main pill */ },
            getDisplayName = { it.name.lowercase().replaceFirstChar { char -> char.uppercase() } },
            selectedColor = if (activeCategory == SortCategory.STANDARD) AuraMutedSlate else DiscoveryViolet,
            modifier = Modifier.weight(1f) // Equal distribution
        )

        if (activeCategory == SortCategory.STANDARD) {
            AuraSortSelector(
                label = "Sort",
                currentOption = when (standardSort) {
                    StandardSortOption.NEWEST_FIRST -> "Newest"
                    StandardSortOption.TITLE_ASC -> "A-Z"
                    StandardSortOption.TITLE_DESC -> "Z-A"
                    StandardSortOption.MOST_PLAYED -> "Popular"
                    StandardSortOption.RECENTLY_PLAYED -> "Recent"
                    StandardSortOption.SHORTEST_DURATION -> "Short"
                    StandardSortOption.LONGEST_DURATION -> "Long"
                    StandardSortOption.RANDOM -> "Shuffle"
                    StandardSortOption.LEAST_PLAYED -> "Unplayed"
                    StandardSortOption.SIZE -> "Size"
                    StandardSortOption.RATING -> "Rating"
                },
                isSelected = true,
                options = StandardSortOption.entries,
                onOptionSelected = onStandardSortChange,
                onPillClick = { /* Toggle menu */ },
                getDisplayName = { it.name.replace("_", " ").lowercase().replaceFirstChar { char -> char.uppercase() } },
                modifier = Modifier.weight(1f)
            )
        } else {
            AuraSortSelector(
                label = "Vibe",
                currentOption = when (intelligentSort) {
                    IntelligentSortOption.PERSONALIZED -> "Personalized"
                    IntelligentSortOption.DISCOVER -> "Discover"
                    IntelligentSortOption.REDISCOVER -> "Rediscover"
                    IntelligentSortOption.FAVORITES -> "Favorites"
                    IntelligentSortOption.SURPRISE_ME -> "Surprise Me"
                    IntelligentSortOption.RANKING_REFINEMENT -> "Refinement"
                    IntelligentSortOption.HIDDEN_GEMS -> "Gems"
                },
                isSelected = true,
                options = IntelligentSortOption.entries,
                onOptionSelected = onIntelligentSortChange,
                onPillClick = { /* Toggle menu */ },
                getDisplayName = { it.displayName },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun EmptyLibraryView(
    onImportClick: () -> Unit,
    onScanClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AuraSpacing.XL),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.size(80.dp),
            color = AuraSubtleSurface,
            shape = CircleShape
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.PhotoLibrary,
                    contentDescription = null,
                    tint = DiscoveryViolet.copy(alpha = 0.4f),
                    modifier = Modifier.size(40.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(AuraSpacing.L))
        Text(
            text = "Your library is empty",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black,
            color = AuraMidnight
        )
        Spacer(modifier = Modifier.height(AuraSpacing.XS))
        Text(
            text = "Import media from your device or scan for local content to start building your intelligence profile.",
            style = MaterialTheme.typography.bodyMedium,
            color = AuraMutedSlate, // Standardized color
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )
        Spacer(modifier = Modifier.height(AuraSpacing.XL))
        Row(
            horizontalArrangement = Arrangement.spacedBy(AuraSpacing.M),
            modifier = Modifier.fillMaxWidth()
        ) {
            AuraButton(
                text = "IMPORT", 
                onClick = onImportClick,
                modifier = Modifier.weight(1f)
            )
            AuraOutlinedButton(
                text = "SCAN DEVICE", 
                onClick = onScanClick,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
