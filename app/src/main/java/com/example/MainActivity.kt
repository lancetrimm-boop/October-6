package com.example

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.DatabaseState
import com.example.data.MediaItem
import com.example.data.MediaRepository
import com.example.ui.components.AuraBottomNavigation
import com.example.ui.components.NavDestination
import com.example.ui.screens.BlueprintWorkspaceScreen
import com.example.ui.screens.CollectionsScreen
import com.example.ui.screens.CompareScreen
import com.example.ui.screens.DiscoverScreen
import com.example.ui.screens.EngagementDebuggerScreen
import com.example.ui.screens.FavoritesScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.MediaDetailScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.theme.AuraBackground
import com.example.ui.theme.AuraCrispWhite
import com.example.ui.theme.AuraMidnight
import com.example.ui.theme.AuraOnSurface
import com.example.ui.theme.AuraPurple
import com.example.ui.theme.AuraSpacing
import com.example.ui.theme.AuraTheme
import com.example.ui.theme.DiscoveryViolet
import com.example.ui.theme.ThemeMode
import com.example.ui.theme.ThemePreferences
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val repository = MediaRepository.instance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // [REMEDIATION] Startup Watchdog (P0)
        // If the database is not ready within the timeout, we transition to a recovery state.
        // We use a longer timeout (30s) in Developer Builds to account for background model loading on emulators.
        lifecycleScope.launch {
            val timeout = if (com.example.BuildConfig.ENABLE_DEVELOPER_TOOLS) 30000L else 12000L
            kotlinx.coroutines.delay(timeout)
            if (repository.databaseState.value != DatabaseState.READY) {
                Log.w("AuraWatchdog", "Initialization timeout reached (${timeout / 1000}s). Triggering recovery UI.")
                repository.forceTimeoutState()
            }
        }

        repository.initDatabase(applicationContext)
        if (com.example.BuildConfig.ENABLE_DEVELOPER_TOOLS) {
            com.example.ui.models.BaselineManager.initialize(applicationContext)
            com.example.ui.models.RegressionArtifactManager.initialize(applicationContext)
        }
        enableEdgeToEdge()
        val themePreferences = ThemePreferences(applicationContext)
        setContent {
            val themeMode by themePreferences.themeMode.collectAsState()
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            AuraTheme(darkTheme = darkTheme) {
                AuraApp(
                    repository = repository,
                    themeMode = themeMode,
                    onThemeModeChange = { themePreferences.setThemeMode(it) }
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d("AuraLifecycle", "MainActivity.onStop: Trimming idle players and pausing active previews")
        com.example.ui.components.VideoPreviewPool.pauseAllActive()
        com.example.ui.components.VideoPreviewPool.releaseIdlePool()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.d("AuraLifecycle", "MainActivity.onTrimMemory level=$level")
        if (level >= TRIM_MEMORY_BACKGROUND || level == TRIM_MEMORY_UI_HIDDEN) {
            com.example.ui.components.VideoPreviewPool.pauseAllActive()
            com.example.ui.components.VideoPreviewPool.releaseIdlePool()
            try {
                coil.Coil.imageLoader(applicationContext).memoryCache?.clear()
            } catch (e: Exception) {
                Log.w("AuraLifecycle", "Failed to trim Coil image memory cache", e)
            }
        }
    }
}

@Composable
fun AuraApp(
    repository: MediaRepository,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: (ThemeMode) -> Unit = {}
) {
    val databaseState by repository.databaseState.collectAsStateWithLifecycle()
    val initializationDetail by repository.initializationDetail.collectAsStateWithLifecycle()
    val context = LocalContext.current

    when (databaseState) {
        DatabaseState.READY -> {
            AuraMainContent(
                repository = repository,
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange
            )
        }
        DatabaseState.INITIALIZING, DatabaseState.VERIFYING, DatabaseState.TRANSITIONING, DatabaseState.TRANSITION_REQUIRED -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = AuraPurple)
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(AuraSpacing.M))
                    Text(
                        text = initializationDetail,
                        color = AuraMidnight.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        DatabaseState.TIMEOUT -> {
            Box(modifier = Modifier.fillMaxSize().padding(AuraSpacing.L), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Initialization Stalled",
                        color = AuraMidnight,
                        fontWeight = FontWeight.Black,
                        style = MaterialTheme.typography.headlineSmall
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(AuraSpacing.XS))
                    Text(
                        text = "Aura is taking longer than usual to secure your library. This might happen during heavy system load.",
                        color = AuraMidnight.copy(alpha = 0.7f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(AuraSpacing.L))
                    Button(
                        onClick = { repository.initDatabase(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = AuraPurple),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Retry Initialization", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        DatabaseState.TRANSITION_FAILED, DatabaseState.CORRUPTED, DatabaseState.ENCRYPTION_FAILED -> {
            var showResetDialog by remember { mutableStateOf(false) }
            val context = LocalContext.current

            if (showResetDialog) {
                AlertDialog(
                    onDismissRequest = { showResetDialog = false },
                    title = { Text("Secure Database Unavailable", fontWeight = FontWeight.Bold) },
                    text = {
                        Text("Aura cannot access the secure library because its encryption link is broken. No data has been deleted yet.\n\nResetting will quarantine the current library and start fresh. Old files will be preserved on this device for possible recovery.")
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showResetDialog = false
                            repository.resetDatabase(context)
                        }) {
                            Text("Reset Library", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { 
                            showResetDialog = false 
                            (context as? Activity)?.finish()
                        }) {
                            Text("Exit App")
                        }
                    }
                )
            }

            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Secure Initialization Failed",
                        color = AuraMidnight,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.headlineSmall
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp))
                    Text(
                        text = "Technical State: ${databaseState.name}",
                        color = AuraMidnight.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(16.dp))
                    TextButton(onClick = { showResetDialog = true }) {
                        Text("View Recovery Options", color = AuraPurple)
                    }
                }
            }
        }
        DatabaseState.NOT_INITIALIZED -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AuraPurple)
            }
        }
    }
}

@Composable
fun AuraMainContent(
    repository: MediaRepository,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: (ThemeMode) -> Unit = {}
) {
    val discoverViewModel: com.example.ui.screens.DiscoverViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return com.example.ui.screens.DiscoverViewModel(repository) as T
            }
        }
    )

    val cleanupViewModel: com.example.ui.screens.CleanupIntelligenceViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                val entitlementRepo = repository.entitlementRepository
                    ?: throw IllegalStateException("EntitlementRepository not initialized")
                return com.example.ui.screens.CleanupIntelligenceViewModel(repository, entitlementRepo) as T
            }
        }
    )

    val cleanupReviewViewModel: com.example.ui.screens.CleanupReviewViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                val entitlementRepo = repository.entitlementRepository
                    ?: throw IllegalStateException("EntitlementRepository not initialized")
                return com.example.ui.screens.CleanupReviewViewModel(repository, entitlementRepo) as T
            }
        }
    )

    val playbackDiagnosticsViewModel: com.example.ui.screens.PlaybackDiagnosticsViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                val errorLogRepo = repository.playbackErrorLogRepository
                    ?: throw IllegalStateException("PlaybackErrorLogRepository not initialized in READY state")
                return com.example.ui.screens.PlaybackDiagnosticsViewModel(
                    errorLogRepo,
                    repository.conversionQueueRepository
                ) as T
            }
        }
    )

    val favoritesViewModel: com.example.ui.screens.FavoritesViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return com.example.ui.screens.FavoritesViewModel(repository) as T
            }
        }
    )

    val channelViewModel: com.example.ui.screens.ChannelViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return com.example.ui.screens.ChannelViewModel(repository) as T
            }
        }
    )

    var showEngagementDebugger by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    val mediaItems by repository.visibleMediaItems.collectAsStateWithLifecycle()
    val compareSession by repository.compareSelectionSession.collectAsStateWithLifecycle()
    val mediaItemsMap by repository.mediaItemsMap.collectAsStateWithLifecycle()
    val pairwiseState by repository.pairwiseState.collectAsStateWithLifecycle()
    LaunchedEffect(pairwiseState) {
        Log.d("PlaylistTrace", "pairwiseState updated: A=${pairwiseState.optionA.id}, B=${pairwiseState.optionB.id}")
    }
    val compareMediaType by repository.compareMediaType.collectAsStateWithLifecycle()
    val compareStrategy by repository.compareStrategy.collectAsStateWithLifecycle()
    val compareSort by repository.compareSort.collectAsStateWithLifecycle()
    val consentState by repository.consentState.collectAsStateWithLifecycle()
    val importProgress by repository.importProgress.collectAsStateWithLifecycle()
    val scanProgress by repository.scanProgress.collectAsStateWithLifecycle()
    val activePlaylist by repository.activePlaylist.collectAsStateWithLifecycle()
    val isPlayerActive by repository.isPlayerActive.collectAsStateWithLifecycle()
    val databaseState by repository.databaseState.collectAsStateWithLifecycle()
    val currentSelectedItem = activePlaylist?.currentItem

    val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        arrayOf(
            android.Manifest.permission.READ_MEDIA_VIDEO,
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            android.Manifest.permission.READ_MEDIA_VIDEO,
            android.Manifest.permission.READ_MEDIA_IMAGES
        )
    } else {
        arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    val scanPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val isGranted = repository.hasStoragePermission(context)
        if (isGranted) {
            repository.libraryPreferences?.setFirstRunScanState(com.example.data.FirstRunScanState.GRANTED_SCAN_PENDING)
            coroutineScope.launch {
                repository.scanLocalMedia(context, isManual = false)
                repository.libraryPreferences?.setFirstRunScanState(com.example.data.FirstRunScanState.INITIAL_SCAN_COMPLETE)
            }
        } else {
            repository.libraryPreferences?.setFirstRunScanState(com.example.data.FirstRunScanState.DENIED)
        }
    }

    val triggerScanOrRequestPermissions: () -> Unit = {
        if (repository.hasStoragePermission(context)) {
            repository.libraryPreferences?.setFirstRunScanState(com.example.data.FirstRunScanState.GRANTED_SCAN_PENDING)
            coroutineScope.launch {
                repository.scanLocalMedia(context, isManual = true)
                repository.libraryPreferences?.setFirstRunScanState(com.example.data.FirstRunScanState.INITIAL_SCAN_COMPLETE)
            }
        } else {
            scanPermissionLauncher.launch(permissionsToRequest)
        }
    }

    LaunchedEffect(databaseState) {
        if (databaseState == DatabaseState.READY) {
            val libPrefs = repository.libraryPreferences
            val currentState = libPrefs?.firstRunScanState?.value ?: com.example.data.FirstRunScanState.NEVER_REQUESTED
            val hasPerm = repository.hasStoragePermission(context)

            if (hasPerm) {
                if (currentState != com.example.data.FirstRunScanState.INITIAL_SCAN_COMPLETE) {
                    libPrefs?.setFirstRunScanState(com.example.data.FirstRunScanState.GRANTED_SCAN_PENDING)
                    coroutineScope.launch {
                        repository.scanLocalMedia(context, isManual = false)
                        libPrefs?.setFirstRunScanState(com.example.data.FirstRunScanState.INITIAL_SCAN_COMPLETE)
                    }
                }
            } else {
                if (currentState == com.example.data.FirstRunScanState.NEVER_REQUESTED) {
                    scanPermissionLauncher.launch(permissionsToRequest)
                } else if (currentState == com.example.data.FirstRunScanState.INITIAL_SCAN_COMPLETE) {
                    libPrefs?.setFirstRunScanState(com.example.data.FirstRunScanState.PERMISSION_REVOKED)
                }
            }
        }
    }

    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var isPendingComparisonDelete by remember { mutableStateOf(false) }

    val deleteRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        // Handle physical deletion result via unified manager
        repository.safeDeleteManager.handleDeletionResult(result.resultCode)
        
        // Handle legacy single-item delete result
        if (result.resultCode == Activity.RESULT_OK) {
            pendingDeleteId?.let { id ->
                if (isPendingComparisonDelete) {
                    repository.deleteComparisonMedia(id)
                } else {
                    repository.deleteMediaItem(id)
                }
            }
        }
        pendingDeleteId = null
        isPendingComparisonDelete = false
    }

    val requestDeletion: (String, Boolean) -> Unit = { id, isComparison ->
        val item = repository.getMediaItemById(id)
        if (item != null) {
            val uri = Uri.parse(item.uriPath)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && uri.toString().startsWith("content://media/")) {
                try {
                    // Save pending state for callback
                    pendingDeleteId = id
                    isPendingComparisonDelete = isComparison
                    val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
                    deleteRequestLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                } catch (e: Exception) {
                    // Fallback to direct delete attempt
                    try {
                        val deleted = context.contentResolver.delete(uri, null, null)
                        if (deleted > 0) {
                            if (isComparison) repository.deleteComparisonMedia(id) else repository.deleteMediaItem(id)
                        } else if (isComparison) {
                            // If delete returns 0 but it's a comparison, we still might want to remove it from app
                            repository.deleteComparisonMedia(id)
                        } else {
                            repository.deleteMediaItem(id)
                        }
                    } catch (e2: Exception) {
                        // Force app-level removal if file is missing or unresolvable
                        if (isComparison) repository.deleteComparisonMedia(id) else repository.deleteMediaItem(id)
                    }
                }
            } else {
                // Legacy or non-MediaStore (e.g. app-owned private files)
                try {
                    context.contentResolver.delete(uri, null, null)
                } catch (e: Exception) {}
                // Always proceed with app-level deletion for legacy
                if (isComparison) repository.deleteComparisonMedia(id) else repository.deleteMediaItem(id)
            }
        }
    }

    var currentRoute by rememberSaveable { mutableStateOf(NavDestination.LIBRARY.route) }
    var currentImprovementId by remember { mutableStateOf<String?>(null) }
    var currentFindingId by remember { mutableStateOf<String?>(null) }
    var showImprovementDetail by remember { mutableStateOf(false) }
    var showFindingDetail by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    var showAuraMomentsSelection by remember { mutableStateOf(false) }
    var activeMomentsMode by remember { mutableStateOf<com.example.data.MomentsMode?>(null) }

    val onUnlockPro: () -> Unit = {
        (context as? Activity)?.let { activity ->
            if (com.example.BuildConfig.ENABLE_DEVELOPER_TOOLS || repository.billingManager == null) {
                (activity as? androidx.activity.ComponentActivity)?.lifecycleScope?.launch {
                    repository.entitlementRepository?.updateEntitlement(true)
                    cleanupReviewViewModel.loadRecommendations()
                }
            } else {
                repository.billingManager?.launchPurchaseFlow(activity)
            }
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = {
                Text(
                    text = "Exit Aura?",
                    fontWeight = FontWeight.Bold,
                    color = AuraMidnight
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to exit?",
                    color = AuraMidnight
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitDialog = false
                        (context as? Activity)?.finish()
                    }
                ) {
                    Text(
                        text = "Exit",
                        fontWeight = FontWeight.Bold,
                        color = DiscoveryViolet
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text(
                        text = "Cancel",
                        color = AuraMidnight
                    )
                }
            },
            containerColor = AuraCrispWhite
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (!isPlayerActive && !showEngagementDebugger && !showAuraMomentsSelection && activeMomentsMode == null) {
                AuraBottomNavigation(
                    currentRoute = currentRoute,
                    onNavigate = { destination ->
                        currentRoute = destination.route
                    }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (activeMomentsMode != null) {
                com.example.ui.screens.AuraMomentsSlideshowScreen(
                    mode = activeMomentsMode!!,
                    repository = repository,
                    onClose = { activeMomentsMode = null },
                    onSeeSimilar = { targetItem ->
                        val requestId = java.util.UUID.randomUUID().toString().take(6)
                        coroutineScope.launch {
                            val response = repository.getSimilarMedia(targetItem, requestId)
                            if (response.isSuccess && response.candidates.isNotEmpty()) {
                                val targetType = targetItem.mediaType
                                val similarCandidates = response.candidates
                                    .map { it.item }
                                    .filter { it.mediaType.equals(targetType, ignoreCase = true) }
                                    .filter { it.id != targetItem.id }
                                val queue = listOf(targetItem) + similarCandidates
                                repository.setPlaylist(items = queue, initialIndex = 0, sourceTitle = "Similar — ${targetItem.title}", isFinite = true)
                                activeMomentsMode = null // Exit slideshow to play similar items
                            }
                        }
                    }
                )
            } else if (showAuraMomentsSelection) {
                com.example.ui.screens.AuraMomentsSelectionScreen(
                    onSelectMode = { mode ->
                        showAuraMomentsSelection = false
                        activeMomentsMode = mode
                    },
                    onBack = {
                        showAuraMomentsSelection = false
                    }
                )
            } else if (showEngagementDebugger && com.example.BuildConfig.ENABLE_DEVELOPER_TOOLS) {
                EngagementDebuggerScreen(
                    repository = repository,
                    onBack = { showEngagementDebugger = false },
                    onNavigateToCleanupDebug = {
                        showEngagementDebugger = false
                        currentRoute = NavDestination.CLEANUP_DASHBOARD.route
                    }
                )
            } else if (currentRoute == NavDestination.INTELLIGENCE.route && com.example.BuildConfig.ENABLE_DEVELOPER_TOOLS) {
                val intelligenceRepository = repository.intelligenceRepository
                if (intelligenceRepository == null || databaseState != DatabaseState.READY) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    if (showImprovementDetail && currentImprovementId != null) {
                        com.example.ui.screens.ImprovementDetailScreen(
                            improvementId = currentImprovementId!!,
                            repository = intelligenceRepository,
                            onBack = { showImprovementDetail = false }
                        )
                    } else if (showFindingDetail && currentFindingId != null) {
                        com.example.ui.screens.FindingDetailScreen(
                            findingId = currentFindingId!!,
                            repository = intelligenceRepository,
                            onBack = { showFindingDetail = false },
                            onViewImprovement = { id ->
                                currentImprovementId = id
                                showImprovementDetail = true
                            }
                        )
                    } else {
                        com.example.ui.screens.AuraIntelligenceScreen(
                            repository = intelligenceRepository,
                            onNavigateToImprovement = { id ->
                                currentImprovementId = id
                                showImprovementDetail = true
                            },
                            onNavigateToFinding = { id ->
                                currentFindingId = id
                                showFindingDetail = true
                            },
                            onNavigateToCleanupDebug = {
                                currentRoute = NavDestination.CLEANUP_DASHBOARD.route
                            }
                        )
                    }
                }
            } else if (isPlayerActive && currentSelectedItem != null) {
                MediaDetailScreen(
                    playlistState = activePlaylist,
                    item = currentSelectedItem,
                    repository = repository,
                    onBack = {
                        repository.clearPlaylist()
                    },
                    onFavoriteToggle = { id -> repository.toggleFavorite(id) },
                    onNext = { repository.nextPlaylistItem() },
                    onPrevious = { repository.previousPlaylistItem() },
                    onSelectIndex = { idx -> repository.selectPlaylistItem(idx) },
                    onUpdateRating = { id, rating -> repository.updateRating(id, rating) },
                    onReloadChannel = {
                        val currentChannel = channelViewModel.selectedChannel.value
                            ?: return@MediaDetailScreen
                        val filterType = channelViewModel.selectedFilterType.value
                        channelViewModel.reloadChannel(currentChannel, filterType) { reloadedItems ->
                            if (reloadedItems.isNotEmpty()) {
                                val sourceTitle = if (filterType == "PHOTOS") {
                                    "Aura Moments"
                                } else {
                                    "Aura Channel — ${currentChannel.title}"
                                }
                                repository.setChannelPlaylist(
                                    channel = currentChannel,
                                    filterType = filterType,
                                    items = reloadedItems,
                                    initialIndex = 0,
                                    sourceTitle = sourceTitle
                                )
                            } else {
                                repository.clearPlaylist()
                            }
                        }
                    },
                    onDeleteMedia = { id ->
                        val isComparison = activePlaylist?.sourceTitle == "Compare Pair"
                        requestDeletion(id, isComparison)
                    },
                    onMicroMoment = { id, taps -> repository.recordMicroMoment(id, taps) },
                    onSeeSimilar = { targetItem ->
                        val requestId = java.util.UUID.randomUUID().toString().take(6)
                        Log.d("SEE_SIMILAR_TRACE", "STAGE=ORCHESTRATOR requestId=$requestId sourceId=${targetItem.id} title=\"${targetItem.title}\"")
                        coroutineScope.launch {
                            val response = repository.getSimilarMedia(targetItem, requestId)
                            Log.d("SEE_SIMILAR_TRACE", "STAGE=RESULT requestId=$requestId isSuccess=${response.isSuccess} resultCount=${response.candidates.size}")
                            
                            if (response.isSuccess && response.candidates.isNotEmpty()) {
                                val targetType = targetItem.mediaType
                                val similarCandidates = response.candidates
                                    .map { it.item }
                                    .filter { it.mediaType == targetType }
                                    .filter { it.id != targetItem.id }
                                val queue = listOf(targetItem) + similarCandidates
                                repository.setPlaylist(items = queue, initialIndex = 0, sourceTitle = "See Similar — ${targetItem.title}", isFinite = true)
                                
                                val active = repository.activePlaylist.value
                                if (active != null && active.items.isNotEmpty()) {
                                    val count = active.items.size
                                    val countMsg = if (count == 1) "Found 1 similar item" else "Found $count similar items"
                                    Log.d("SEE_SIMILAR_TRACE", "STAGE=SUCCESS requestId=$requestId playlistSize=$count")
                                    android.widget.Toast.makeText(context, countMsg, android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    Log.e("SEE_SIMILAR_TRACE", "STAGE=PLAYLIST_FAILURE requestId=$requestId - Playlist became empty after sanitization.")
                                    android.widget.Toast.makeText(context, "No playable similar items found", android.widget.Toast.LENGTH_LONG).show()
                                }
                            } else if (!response.isSuccess) {
                                Log.e("SEE_SIMILAR_TRACE", "STAGE=FAILURE requestId=$requestId error=${response.errorMessage}")
                                android.widget.Toast.makeText(context, "Couldn't find similar items", android.widget.Toast.LENGTH_LONG).show()
                            } else {
                                Log.d("SEE_SIMILAR_TRACE", "STAGE=NO_RESULTS requestId=$requestId")
                                android.widget.Toast.makeText(context, "Found 0 similar items", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onAISkipEvent = { mediaId, eventType, fromPos, toPos ->
                        repository.recordAISkipEvent(mediaId, eventType, fromPos, toPos)
                    },
                    onAddVisualReference = { item ->
                        repository.addVisualReference(item)
                    }
                )
            } else {
                AnimatedContent(
                    targetState = currentRoute,
                    transitionSpec = {
                        val initialIndex = when (initialState) {
                            NavDestination.LIBRARY.route -> 0
                            NavDestination.CHANNELS.route -> 1
                            NavDestination.COMPARE.route -> 2
                            NavDestination.DISCOVER.route -> 3
                            NavDestination.FINDS.route -> 3
                            NavDestination.MY_STUFF.route -> 4
                            NavDestination.COLLECTIONS.route -> 4
                            NavDestination.PROFILE.route -> 4
                            NavDestination.PLAYBACK_DIAGNOSTICS.route -> 4
                            NavDestination.INTELLIGENCE.route -> 5
                            NavDestination.FAVORITES.route -> 6
                            NavDestination.CLEANUP_REVIEW.route -> 7
                            NavDestination.CLEANUP_DASHBOARD.route -> 8
                            else -> 0
                        }
                        val targetIndex = when (targetState) {
                            NavDestination.LIBRARY.route -> 0
                            NavDestination.CHANNELS.route -> 1
                            NavDestination.COMPARE.route -> 2
                            NavDestination.DISCOVER.route -> 3
                            NavDestination.FINDS.route -> 3
                            NavDestination.MY_STUFF.route -> 4
                            NavDestination.COLLECTIONS.route -> 4
                            NavDestination.PROFILE.route -> 4
                            NavDestination.PLAYBACK_DIAGNOSTICS.route -> 4
                            NavDestination.INTELLIGENCE.route -> 5
                            NavDestination.FAVORITES.route -> 6
                            NavDestination.CLEANUP_REVIEW.route -> 7
                            NavDestination.CLEANUP_DASHBOARD.route -> 8
                            else -> 0
                        }

                        if (targetIndex > initialIndex) {
                            (slideInHorizontally(
                                initialOffsetX = { it },
                                animationSpec = tween(300, easing = FastOutSlowInEasing)
                            ) + fadeIn(animationSpec = tween(300))).togetherWith(
                                slideOutHorizontally(
                                    targetOffsetX = { -it },
                                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                                ) + fadeOut(animationSpec = tween(300))
                            )
                        } else {
                            (slideInHorizontally(
                                initialOffsetX = { -it },
                                animationSpec = tween(300, easing = FastOutSlowInEasing)
                            ) + fadeIn(animationSpec = tween(300))).togetherWith(
                                slideOutHorizontally(
                                    targetOffsetX = { it },
                                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                                ) + fadeOut(animationSpec = tween(300))
                            )
                        }
                    },
                    label = "screen_transition"
                ) { route ->
                    when (route) {
                        NavDestination.LIBRARY.route -> {
                            LibraryScreen(
                                mediaItems = mediaItems,
                                repository = repository,
                                importProgress = importProgress,
                                scanProgress = scanProgress,
                                onMediaSelect = { _ -> /* Playlist handled internally by screen */ },
                                onCompareLaunch = { selectedIds ->
                                    repository.startCompareSelectionSession(selectedIds)
                                    currentRoute = NavDestination.COMPARE.route
                                },
                                onFavoriteToggle = { id -> repository.recordLike(id) },
                                onCreateSearchSeededChannel = { channel ->
                                    coroutineScope.launch {
                                        repository.saveSearchSeededChannel(channel)
                                        channelViewModel.selectChannel(channel)
                                        channelViewModel.loadChannelPreviews()
                                        currentRoute = NavDestination.CHANNELS.route
                                    }
                                },
                                onImportUris = { uris ->
                                    repository.importMediaFromUris(context, uris)
                                },
                                onScanDevice = {
                                    triggerScanOrRequestPermissions()
                                },
                                onSyncClick = {
                                    if (com.example.BuildConfig.ENABLE_DEVELOPER_TOOLS) {
                                        currentRoute = NavDestination.INTELLIGENCE.route
                                    }
                                },
                                deleteLauncher = deleteRequestLauncher
                            )
                        }
                        NavDestination.CHANNELS.route -> {
                            com.example.ui.screens.AuraChannelsScreen(
                                viewModel = channelViewModel,
                                onMediaSelect = { selectedItem, channelItems ->
                                    val selectedChannel = channelViewModel.selectedChannel.value
                                    val initialIndex = if (selectedChannel.channelKind == com.example.data.ChannelKind.SEARCH_SEEDED && selectedChannel.referenceMediaIds.isNotEmpty()) {
                                        val firstRefId = selectedChannel.referenceMediaIds.first()
                                        channelItems.indexOfFirst { it.id == firstRefId }
                                            .takeIf { it >= 0 }
                                            ?: channelItems.indexOfFirst { it.id == selectedItem.id }.coerceAtLeast(0)
                                    } else {
                                        channelItems.indexOfFirst { it.id == selectedItem.id }.coerceAtLeast(0)
                                    }
                                    val filterType = channelViewModel.selectedFilterType.value
                                    val isPhoto = !selectedItem.mediaType.equals("VIDEO", ignoreCase = true) && !selectedItem.mediaType.equals("MOVIE", ignoreCase = true)
                                    val sourceTitle = if (isPhoto) "Aura Moments" else "Aura Channel — ${selectedChannel.title}"
                                    repository.setChannelPlaylist(
                                        channel = selectedChannel,
                                        filterType = filterType,
                                        items = channelItems,
                                        initialIndex = initialIndex,
                                        sourceTitle = sourceTitle
                                    )
                                },
                                onDeleteChannel = { channel ->
                                    channelViewModel.deleteChannel(channel)
                                }
                            )
                        }
                        NavDestination.MY_STUFF.route -> {
                            com.example.ui.screens.MyStuffScreen(
                                repository = repository,
                                mediaItems = mediaItems,
                                themeMode = themeMode,
                                onThemeModeChange = onThemeModeChange,
                                onNavigateToFavorites = {
                                    currentRoute = NavDestination.FAVORITES.route
                                },
                                onNavigateToCleanup = {
                                    currentRoute = NavDestination.CLEANUP_REVIEW.route
                                },
                                onNavigateToPrivacyPolicy = {
                                    currentRoute = NavDestination.PRIVACY_POLICY.route
                                },
                                onNavigateToDiagnostics = {
                                    currentRoute = NavDestination.PLAYBACK_DIAGNOSTICS.route
                                },
                                onLaunchAuraMoments = {
                                    showAuraMomentsSelection = true
                                },
                                onMediaSelect = { selectedItem, styleItems ->
                                    val selectedIndex = styleItems.indexOfFirst { it.id == selectedItem.id }
                                    if (selectedIndex != -1) {
                                        repository.setPlaylist(
                                            items = styleItems,
                                            initialIndex = selectedIndex,
                                            sourceTitle = "Signature Style"
                                        )
                                    }
                                },
                                onCollectionSelect = { collectionItem, itemsInCollection ->
                                    repository.setPlaylist(
                                        items = itemsInCollection,
                                        initialIndex = itemsInCollection.indexOfFirst { it.id == collectionItem.id }.coerceAtLeast(0),
                                        sourceTitle = "Collection"
                                    )
                                },
                                onFavoriteToggle = { id -> repository.addToFavorites(id) },
                                onSearch = { query ->
                                    repository.librarySearchQuery = query
                                    currentRoute = NavDestination.LIBRARY.route
                                }
                            )
                        }
                        NavDestination.DISCOVER.route -> {
                            DiscoverScreen(
                                repository = repository,
                                viewModel = discoverViewModel,
                                onMediaSelect = { item ->
                                    // Handle direct media selection if needed
                                },
                                onObsessionSelect = { obsession ->
                                    // Obsession selection handled by DiscoverViewModel/Screen
                                },
                                onScanAndImport = {
                                    currentRoute = NavDestination.LIBRARY.route
                                }
                            )
                        }
                        NavDestination.COMPARE.route -> {
                            CompareScreen(
                                pairwiseState = pairwiseState,
                                session = compareSession,
                                mediaItemsMap = mediaItemsMap,
                                compareMediaType = compareMediaType,
                                compareStrategy = compareStrategy,
                                compareSort = compareSort,
                                isRepositoryReady = (databaseState == com.example.data.DatabaseState.READY),
                                onMediaTypeSelect = { filter -> repository.setCompareMediaType(filter) },
                                onStrategySelect = { strategy -> repository.setCompareStrategy(strategy) },
                                onSortSelect = { sort -> repository.setCompareSort(sort) },
                                onVote = { chosenId -> coroutineScope.launch { repository.recordComparisonVote(chosenId) } },
                                onSkip = { coroutineScope.launch { repository.skipComparison() } },
                                onDeleteMedia = { id -> requestDeletion(id, true) },
                                onFavoriteToggle = { id -> repository.addToFavorites(id) },
                                onExitSession = {
                                    repository.exitCompareSelectionSession()
                                    currentRoute = NavDestination.LIBRARY.route
                                },
                                onRestartSession = { repository.restartCompareSelectionSession() },
                                onMediaSelect = { item ->
                                    repository.setPlaylist(
                                        items = listOf(pairwiseState.optionA, pairwiseState.optionB),
                                        initialIndex = if (item.id == pairwiseState.optionA.id) 0 else 1,
                                        sourceTitle = "Compare Pair"
                                    )
                                }
                            )
                        }
                        NavDestination.COLLECTIONS.route -> {
                            CollectionsScreen(
                                mediaItems = mediaItems,
                                repository = repository,
                                onCollectionSelect = { collectionItem, itemsInCollection ->
                                    repository.setPlaylist(
                                        items = itemsInCollection,
                                        initialIndex = itemsInCollection.indexOfFirst { it.id == collectionItem.id }.coerceAtLeast(0),
                                        sourceTitle = "Collection"
                                    )
                                },
                                onFavoriteToggle = { id -> repository.addToFavorites(id) },
                                onLaunchAuraMoments = { showAuraMomentsSelection = true },
                                onSearch = { query ->
                                    repository.librarySearchQuery = query
                                    currentRoute = NavDestination.LIBRARY.route
                                }
                            )
                        }
                        NavDestination.FAVORITES.route -> {
                            val sections by favoritesViewModel.sections.collectAsStateWithLifecycle()
                            val isLoading by favoritesViewModel.isLoading.collectAsStateWithLifecycle()
                            FavoritesScreen(
                                sections = sections,
                                isLoading = isLoading,
                                onMediaSelect = { selectedItem, sectionItems ->
                                    val selectedIndex = sectionItems.indexOfFirst { it.id == selectedItem.id }
                                    if (selectedIndex != -1) {
                                        repository.setPlaylist(
                                            items = sectionItems,
                                            initialIndex = selectedIndex,
                                            sourceTitle = "Favorites"
                                        )
                                    }
                                },
                                onFavoriteToggle = { id -> 
                                    repository.removeFromFavorites(id)
                                    favoritesViewModel.refreshFavorites()
                                },
                                onLike = { id ->
                                    repository.recordLike(id)
                                    repository.addToFavorites(id)
                                },
                                onBack = {
                                    currentRoute = NavDestination.MY_STUFF.route
                                },
                                onRefresh = {
                                    favoritesViewModel.refreshFavorites()
                                }
                            )
                        }
                        NavDestination.PROFILE.route -> {
                            ProfileScreen(
                                repository = repository,
                                onNavigateToFavorites = {
                                    currentRoute = NavDestination.FAVORITES.route
                                },
                                onNavigateToCleanup = {
                                    currentRoute = NavDestination.CLEANUP_REVIEW.route
                                },
                                onNavigateToPrivacyPolicy = {
                                    currentRoute = NavDestination.PRIVACY_POLICY.route
                                },
                                onNavigateToDiagnostics = {
                                    currentRoute = NavDestination.PLAYBACK_DIAGNOSTICS.route
                                },
                                onLaunchAuraMoments = {
                                    showAuraMomentsSelection = true
                                },
                                onMediaSelect = { selectedItem, styleItems ->
                                    val selectedIndex = styleItems.indexOfFirst { it.id == selectedItem.id }
                                    if (selectedIndex != -1) {
                                        repository.setPlaylist(
                                            items = styleItems,
                                            initialIndex = selectedIndex,
                                            sourceTitle = "Signature Style"
                                        )
                                    }
                                }
                            )
                        }
                        NavDestination.PLAYBACK_DIAGNOSTICS.route -> {
                            com.example.ui.screens.PlaybackDiagnosticsScreen(
                                viewModel = playbackDiagnosticsViewModel,
                                onBack = {
                                    currentRoute = NavDestination.PROFILE.route
                                }
                            )
                        }
                        NavDestination.PRIVACY_POLICY.route -> {
                            com.example.ui.screens.PrivacyPolicyScreen(
                                onBack = {
                                    currentRoute = NavDestination.PROFILE.route
                                }
                            )
                        }
                        NavDestination.CLEANUP_REVIEW.route -> {
                            com.example.ui.screens.CleanupReviewScreen(
                                viewModel = cleanupReviewViewModel,
                                onBack = {
                                    currentRoute = NavDestination.PROFILE.route
                                },
                                onMediaDetail = { item, reviewList ->
                                    val index = reviewList.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
                                    repository.setPlaylist(reviewList, index, "Smart Cleanup Review")
                                },
                                deleteLauncher = deleteRequestLauncher,
                                onUnlockPro = onUnlockPro
                            )
                        }
                        NavDestination.CLEANUP_DASHBOARD.route -> {
                            com.example.ui.screens.CleanupIntelligenceDashboardScreen(
                                viewModel = cleanupViewModel,
                                onBack = {
                                    currentRoute = NavDestination.PROFILE.route
                                },
                                onUnlockPro = onUnlockPro
                            )
                        }
                    }
                }
            }

            // Global Emotional Intelligence Experience Overlay
            com.example.ui.components.AuraMomentOverlay(repository = repository)
        }
    }
}
