package com.example.data

import android.content.ContentUris
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.flow.first
import com.example.compatibility.*
import com.example.data.contribution.*
import com.example.data.db.*
import com.example.data.intelligence.*
import com.example.data.semantic.*
import com.example.data.entitlement.EntitlementRepository
import com.example.data.billing.BillingManager
import com.example.data.blueprint.BlueprintArtifactManager
import com.example.ui.models.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.random.Random

// --- Domain Models ---

data class ClipInteractionSummary(val title: String, val previewCount: Int, val selectCount: Int, val exportCount: Int, val score: Int)
data class AISkipStats(val totalSkipForwards: Int = 0, val totalSkipBacks: Int = 0, val totalSkipReversals: Int = 0, val totalWatchedDestinations: Int = 0)
data class EngagementMetrics(val totalPlays: Int = 0, val favoriteCount: Int = 0, val averageRating: Float = 0f, val personalizationScore: Int = 0, val totalComparisons: Int = 0, val microMomentCount: Int = 0, val itemsDiscovered: Int = 0, val totalClipPreviews: Int = 0, val totalClipSelections: Int = 0, val totalClipExports: Int = 0, val topEngagedClips: List<ClipInteractionSummary> = emptyList(), val pairwiseDiagnostics: PairwiseDiagnostics = PairwiseDiagnostics(), val aiSkipStats: AISkipStats = AISkipStats())
data class PairwiseDiagnostics(val totalEligibleMedia: Int = 0, val top100CandidatePoolSize: Int = 0, val comparedCandidateCount: Int = 0, val neverComparedCount: Int = 0, val poolRefreshTimestamp: Long = 0L, val topCandidateIds: List<String> = emptyList(), val recentRepetitionCount: Int = 0, val lastSelectionReason: String = "Not initialized")
data class ImportProgressState(val isImporting: Boolean = false, val processedFiles: Int = 0, val totalFiles: Int = 0, val progressPercent: Int = 0, val statusText: String = "")
data class ScanProgressState(val scanSessionId: Long = 0L, val isScanning: Boolean = false, val isManual: Boolean = false, val discoveredCount: Int = 0, val processedCount: Int = 0, val failedCount: Int = 0, val totalCount: Int = 0, val statusText: String = "", val isComplete: Boolean = false, val errorCode: ScanError? = null)

enum class ScanError { PERMISSION_DENIED, STORAGE_ACCESS_FAILED, DATABASE_ERROR, MIGRATION_FAILED, DATABASE_CORRUPT, ENCRYPTION_ERROR, QUERY_EMPTY, UNKNOWN_ERROR, QUERY_FAILURE, CANCELED }

sealed class DiscoveryResult {
    data class Complete(val entities: List<MediaEntity>, val discoveredIds: Set<String>, val scannedVolumes: Set<String>, val scannedMediaTypes: Set<String>) : DiscoveryResult()
    data class Incomplete(val reason: String, val errorCode: ScanError, val cause: Throwable? = null) : DiscoveryResult()
}

data class PlaylistState(
    val items: List<MediaItem> = emptyList(),
    val currentIndex: Int = 0,
    val authoritativeMediaId: String? = null,
    val sourceTitle: String = "",
    val channel: Channel? = null,
    val channelFilterType: String? = null,
    val isFinite: Boolean = false
) {
    val currentItem: MediaItem? get() = items.getOrNull(currentIndex)
    val hasNext: Boolean get() = (currentIndex < items.size - 1)
    val hasPrevious: Boolean get() = (currentIndex > 0)
}

enum class DatabaseState { NOT_INITIALIZED, INITIALIZING, TRANSITION_REQUIRED, TRANSITIONING, VERIFYING, READY, TRANSITION_FAILED, CORRUPTED, ENCRYPTION_FAILED, TIMEOUT }
enum class AIState { NOT_INITIALIZED, BOOTSTRAP, EARLY_READY, HEAVY_READY, ERROR, READY, DISABLED }

enum class SortCategory { STANDARD, INTELLIGENT }

enum class StandardSortOption(val displayName: String, val description: String) {
    NEWEST_FIRST("Recently Added", "Your latest additions."),
    RECENTLY_PLAYED("Recently Played", "Media you've watched recently."),
    TITLE_ASC("Title A-Z", "Alphabetical."),
    TITLE_DESC("Title Z-A", "Reverse alphabetical."),
    SHORTEST_DURATION("Shortest", "Short clips."),
    LONGEST_DURATION("Longest", "Long videos."),
    MOST_PLAYED("Most Played", "Most viewed."),
    LEAST_PLAYED("Least Played", "Least viewed."),
    RANDOM("Random", "Fresh random order."),
    SIZE("File Size", "Largest first."),
    RATING("Rating", "Highest rated first.")
}

enum class IntelligentSortOption(val displayName: String, val description: String) {
    PERSONALIZED("Personalized", "AI thoughts based on Taste DNA."),
    DISCOVER("Discover", "Unseen gems from your library."),
    REDISCOVER("Rediscover", "Favorites you haven't seen in a while."),
    HIDDEN_GEMS("Hidden Gems", "High quality items you might have missed."),
    FAVORITES("Favorites", "Highly rated and liked media."),
    SURPRISE_ME("Surprise Me", "Random AI selection."),
    RANKING_REFINEMENT("Refinement", "Items needing your feedback.")
}

/**
 * Main repository for media items and Aura intelligence integration.
 */
class MediaRepository(val dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    fun getMoshi(): Moshi = moshi
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private var database: AuraDatabase? = null
    fun getDatabase(): AuraDatabase? = database

    val presentationMediaStore: com.example.data.mediastore.PresentationMediaStore = com.example.data.mediastore.DefaultPresentationMediaStore()
    val diagnosticMediaStore: com.example.data.mediastore.DiagnosticMediaStore = com.example.data.mediastore.DefaultDiagnosticMediaStore()

    @Volatile var intelligenceRepository: IntelligenceRepository? = null; private set
    @Volatile var interactionRepository: InteractionRepository? = null; private set
    @Volatile var entitlementRepository: EntitlementRepository? = null; private set
    @Volatile var billingManager: BillingManager? = null; private set
    @Volatile var playbackErrorLogRepository: PlaybackErrorLogRepository? = null; private set
    @Volatile var searchFeedbackRepository: SearchFeedbackRepository? = null; private set
    @Volatile var contributionQueueRepository: ContributionQueueRepository? = null; private set
    @Volatile var conversionQueueRepository: ConversionQueueRepository? = null; private set
    @Volatile var semanticRepresentationRepository: SemanticRepresentationRepository? = null; private set
    @Volatile var embeddingProvider: EmbeddingProvider? = null; private set
    @Volatile var mobileCLIPProvider: EmbeddingProvider? = null; private set
    @Volatile var mobileClipTextProvider: EmbeddingProvider? = null; private set
    @Volatile var semanticCandidateRetriever: SemanticCandidateRetriever? = null; private set
    @Volatile var semanticSearchService: SemanticSearchService? = null; private set
    @Volatile var semanticIndexingService: SemanticIndexingService? = null; private set
    @Volatile var visualIndexingService: VisualIndexingService? = null; private set
    @Volatile var blueprintArtifactManager: BlueprintArtifactManager? = null; private set

    @Volatile var intelligenceCore: AuraIntelligenceCore? = null; private set
    @Volatile var hybridSearchEngine: HybridSearchEngine? = null; private set

    init {
        try {
            val field = android.database.CursorWindow::class.java.getDeclaredField("sCursorWindowSize")
            field.isAccessible = true
            field.set(null, 100 * 1024 * 1024) // 100MB CursorWindow size for large media libraries
        } catch (_: Throwable) {}

        val bootstrapRetriever = ProductionLexicalRetriever()
        val router = RetrievalRouter(bootstrapRetriever, null, null)
        val core = AuraIntelligenceCore(this, router, dispatcher = dispatcher)
        intelligenceCore = core
        hybridSearchEngine = DefaultHybridSearchEngine(core)
    }

    private val _mediaItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val mediaItems = _mediaItems.asStateFlow()

    private val _visibleMediaItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val visibleMediaItems: StateFlow<List<MediaItem>> = _visibleMediaItems.asStateFlow()

    private val _candidateEligibleMediaItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val candidateEligibleMediaItems: StateFlow<List<MediaItem>> = _candidateEligibleMediaItems.asStateFlow()

    private val _mediaItemsMap = MutableStateFlow<Map<String, MediaItem>>(emptyMap())
    val mediaItemsMap: StateFlow<Map<String, MediaItem>> = _mediaItemsMap.asStateFlow()

    private fun setMediaItemsState(items: List<MediaItem>) {
        _mediaItems.value = items
        val visible = items.filter { isItemVisibleInLibrary(it) }
        _visibleMediaItems.value = visible
        _candidateEligibleMediaItems.value = items.filter { isItemEligibleForCandidates(it) }
        _mediaItemsMap.value = visible.associateBy { it.id }
    }

    private fun updateMediaItemsState(transform: (List<MediaItem>) -> List<MediaItem>) {
        _mediaItems.update { current ->
            val updated = transform(current)
            val visible = updated.filter { isItemVisibleInLibrary(it) }
            _visibleMediaItems.value = visible
            _candidateEligibleMediaItems.value = updated.filter { isItemEligibleForCandidates(it) }
            _mediaItemsMap.value = visible.associateBy { it.id }
            updated
        }
    }
    
    open fun isItemEligibleForCandidates(item: MediaItem): Boolean {
        if (item.isDeleted) return false
        return when (getEffectiveCompatibilityStatus(item)) {
            CompatibilityStatus.PLAYABLE,
            CompatibilityStatus.PLAYABLE_SOFTWARE_DECODE -> true
            else -> false
        }
    }
    private val _searchSeededChannels = MutableStateFlow<List<Channel>>(emptyList())
    val searchSeededChannels: StateFlow<List<Channel>> = _searchSeededChannels.asStateFlow()

    suspend fun saveSearchSeededChannel(channel: Channel) {
        val current = _searchSeededChannels.value.toMutableList()
        if (current.none { it.id == channel.id }) {
            current.add(channel)
            _searchSeededChannels.value = current
            ChannelRegistry.addSearchSeededChannel(channel)
            try {
                val adapter = moshi.adapter<List<Channel>>(
                    com.squareup.moshi.Types.newParameterizedType(List::class.java, Channel::class.java)
                )
                database?.userPreferenceDao()?.insertPreference(
                    UserPreferenceEntity("search_seeded_channels", adapter.toJson(current))
                )
            } catch (e: Exception) {}
        }
    }

    suspend fun deleteSearchSeededChannel(channelId: String) {
        val current = _searchSeededChannels.value
        val target = current.find { it.id == channelId } ?: ChannelRegistry.get(channelId) ?: return
        if (target.channelKind != ChannelKind.SEARCH_SEEDED) return

        val updated = current.filterNot { it.id == channelId }
        _searchSeededChannels.value = updated
        ChannelRegistry.removeSearchSeededChannel(channelId)
        try {
            val adapter = moshi.adapter<List<Channel>>(
                com.squareup.moshi.Types.newParameterizedType(List::class.java, Channel::class.java)
            )
            database?.userPreferenceDao()?.insertPreference(
                UserPreferenceEntity("search_seeded_channels", adapter.toJson(updated))
            )
        } catch (e: Exception) {}
    }
    private val _databaseState = MutableStateFlow(DatabaseState.NOT_INITIALIZED); val databaseState = _databaseState.asStateFlow()
    private val _aiState = MutableStateFlow(AIState.NOT_INITIALIZED); val aiState = _aiState.asStateFlow()
    private val _initializationDetail = MutableStateFlow("Pending..."); val initializationDetail = _initializationDetail.asStateFlow()
    private val _databaseErrorMessage = MutableStateFlow<String?>(null); val databaseErrorMessage = _databaseErrorMessage.asStateFlow()
    
    private val _libraryFilter = MutableStateFlow("ALL"); val libraryFilterFlow = _libraryFilter.asStateFlow()
    var libraryFilter: String get() = _libraryFilter.value; set(v) { _libraryFilter.value = v; resetLibraryPage() }
    
    private val _activeSortCategory = MutableStateFlow(SortCategory.STANDARD); val activeSortCategory = _activeSortCategory.asStateFlow()
    var sortCategory: SortCategory get() = _activeSortCategory.value; set(v) { _activeSortCategory.value = v; resetLibraryPage(); scope.launch { database?.userPreferenceDao()?.insertPreference(UserPreferenceEntity("active_sort_category", v.name)) } }
    
    private val _selectedStandardSort = MutableStateFlow(StandardSortOption.NEWEST_FIRST); val selectedStandardSort = _selectedStandardSort.asStateFlow()
    var standardSort: StandardSortOption get() = _selectedStandardSort.value; set(v) { if (v == StandardSortOption.RANDOM) refreshSort(); _selectedStandardSort.value = v; resetLibraryPage(); scope.launch { database?.userPreferenceDao()?.insertPreference(UserPreferenceEntity("selected_standard_sort", v.name)) } }
    
    private val _selectedIntelligentSort = MutableStateFlow(IntelligentSortOption.PERSONALIZED); val selectedIntelligentSort = _selectedIntelligentSort.asStateFlow()
    var intelligentSort: IntelligentSortOption get() = _selectedIntelligentSort.value; set(v) { if (v == IntelligentSortOption.SURPRISE_ME) refreshSort(); _selectedIntelligentSort.value = v; resetLibraryPage(); scope.launch { database?.userPreferenceDao()?.insertPreference(UserPreferenceEntity("selected_intelligent_sort", v.name)) } }
    
    private val _librarySearchRequest = MutableStateFlow<SearchRequest>(SearchRequest.Text("")); val librarySearchRequest = _librarySearchRequest.asStateFlow()
    var librarySearchQuery: String get() = _librarySearchRequest.value.query ?: ""; set(v) { val cur = _librarySearchRequest.value; resetLibraryPage(); _librarySearchRequest.value = when { v.isBlank() -> cur.visualVector?.let { SearchRequest.Visual(it, cur.referenceUri) } ?: SearchRequest.Text("") ; cur.visualVector != null -> SearchRequest.Compound(v, cur.visualVector!!, cur.referenceUri); else -> SearchRequest.Text(v) } }
    
    private val _activePlaylist = MutableStateFlow<PlaylistState?>(null); val activePlaylist = _activePlaylist.asStateFlow()
    private val _isPlayerActive = MutableStateFlow(false); val isPlayerActive = _isPlayerActive.asStateFlow()
    private val _isLibraryReady = MutableStateFlow(false); val isLibraryReady = _isLibraryReady.asStateFlow()
    private val _isForegroundVisualQueryActive = MutableStateFlow(false); val isForegroundVisualQueryActive = _isForegroundVisualQueryActive.asStateFlow()
    
    private val _compareSelectionSession = MutableStateFlow(CompareSelectionSession()); val compareSelectionSession = _compareSelectionSession.asStateFlow()
    private val _compareMediaType = MutableStateFlow(CompareMediaTypeFilter.PHOTOS); val compareMediaType = _compareMediaType.asStateFlow()
    private val _compareStrategy = MutableStateFlow(CompareStrategy.PERSONALIZED); val compareStrategy = _compareStrategy.asStateFlow()
    private val _compareSort = MutableStateFlow(CompareSortOption.RECOMMENDED); val compareSort = _compareSort.asStateFlow()
    
    private val _consentState = MutableStateFlow(ConsentState.NOT_DECIDED); val consentState = _consentState.asStateFlow()
    private val emptyMediaItem = MediaItem(id = "", title = "", mediaType = "PHOTO", year = 2024, duration = "", genre = "Media")
    private val _pairwiseState = MutableStateFlow(PairwiseComparison("p1", 1, 50, emptyMediaItem, emptyMediaItem)); val pairwiseState = _pairwiseState.asStateFlow()
    
    private val _intelligenceStats = MutableStateFlow(IntelligenceStats()); val intelligenceStats = _intelligenceStats.asStateFlow()
    private val _tasteDNA = MutableStateFlow(TasteDNA()); val tasteDNA = _tasteDNA.asStateFlow()
    private val _preferenceProfile = MutableStateFlow(TasteDNA.PreferenceProfile()); val preferenceProfile = _preferenceProfile.asStateFlow()
    private val _discoveryPolicy = MutableStateFlow(DiscoveryPolicy()); val discoveryPolicy = _discoveryPolicy.asStateFlow()
    private val _userIntent = MutableStateFlow(UserIntent()); val userIntent = _userIntent.asStateFlow()
    private val _creatorProfiles = MutableStateFlow<Map<String, CreatorProfile>>(emptyMap()); val creatorProfiles = _creatorProfiles.asStateFlow()
    private val _comparisonCounts = MutableStateFlow<Map<String, Int>>(emptyMap()); val comparisonCounts = _comparisonCounts.asStateFlow()
    private val _pairwiseDiagnostics = MutableStateFlow(PairwiseDiagnostics()); val pairwiseDiagnostics = _pairwiseDiagnostics.asStateFlow()
    
    private val _importProgress = MutableStateFlow(ImportProgressState()); val importProgress = _importProgress.asStateFlow()
    private val _scanProgress = MutableStateFlow(ScanProgressState()); val scanProgress = _scanProgress.asStateFlow()
    private val _watchHistory = MutableStateFlow<List<MediaItem>>(emptyList()); val watchHistory = _watchHistory.asStateFlow()
    private val _recentSearches = MutableStateFlow<List<String>>(emptyList()); val recentSearches = _recentSearches.asStateFlow()
    private val _storedEvidence = MutableStateFlow<List<EvidenceRecord>>(emptyList()); val storedEvidence = _storedEvidence.asStateFlow()
    private val _signatureStyleProfile = MutableStateFlow(SignatureStyleProfile(emptyList(), emptyList())); val signatureStyleProfile = _signatureStyleProfile.asStateFlow()
    private val _latestPerformance = MutableStateFlow<OperationPerformance?>(null); val latestPerformance = _latestPerformance.asStateFlow()
    private val _librarySessionSeed = MutableStateFlow(Random.nextLong())
    private val _libraryPageOffset = MutableStateFlow(0)
    private val LIBRARY_PAGE_SIZE = 25

    fun loadNextLibraryPage() {
        _libraryPageOffset.value = _libraryPageOffset.value + LIBRARY_PAGE_SIZE
    }

    fun resetLibraryPage() {
        _libraryPageOffset.value = 0
    }
    
    private val _discoverSnapshot = MutableStateFlow<DiscoverSnapshot?>(null); val discoverSnapshot = _discoverSnapshot.asStateFlow()

    private var initJob: Job? = null
    val momentDispatcher = AuraMomentDispatcher(this)
    val safeDeleteManager = com.example.data.cleanup.SafeDeleteManager(this, scope)
    var visualContextEngine = com.example.data.visual.VisualContextEngine(this)
    var applicationContext: Context? = null; private set
    var exposureRepository: ExposureRepository? = null; private set
    
    var lastPlaybackPositionMs: Long = 0; private set
    var isResumingFromBackground: Boolean = false; private set

    private val _searchErrorMessage = MutableStateFlow<String?>(null); val searchErrorMessage = _searchErrorMessage.asStateFlow()
    private val _activeVisualReferences = MutableStateFlow<List<MediaItem>>(emptyList()); val activeVisualReferences = _activeVisualReferences.asStateFlow()
    var libraryPreferences: LibraryPreferences? = null; private set
    private val _gridDensity = MutableStateFlow(160f); val gridDensity = _gridDensity.asStateFlow()
    private val _autoScrollSpeed = MutableStateFlow(AutoScrollSpeed.MEDIUM); val autoScrollSpeed = _autoScrollSpeed.asStateFlow()

    var libraryScrollIndex: Int = 0
    var libraryScrollOffset: Int = 0
    var discoverScrollIndex: Int = 0
    var discoverScrollOffset: Int = 0

    private val _latestLibraryProvenance = MutableStateFlow<Map<String, DecisionProvenance>>(emptyMap()); val latestLibraryProvenance = _latestLibraryProvenance.asStateFlow()
    private val _latestDiscoverProvenance = MutableStateFlow<Map<String, DecisionProvenance>>(emptyMap()); val latestDiscoverProvenance = _latestDiscoverProvenance.asStateFlow()

    private val _libraryRepeatMode = MutableStateFlow(false); val libraryRepeatMode = _libraryRepeatMode.asStateFlow()
    var repeatMode: Boolean get() = _libraryRepeatMode.value; set(v) { _libraryRepeatMode.value = v }

    private val _slideshowDelaySeconds = MutableStateFlow(4)
    val slideshowDelaySeconds: StateFlow<Int> = _slideshowDelaySeconds.asStateFlow()

    var slideshowDelaySec: Int
        get() = _slideshowDelaySeconds.value
        set(value) {
            val clamped = value.coerceIn(3, 15)
            _slideshowDelaySeconds.value = clamped
            scope.launch {
                try {
                    database?.userPreferenceDao()?.insertPreference(
                        UserPreferenceEntity("slideshow_delay_seconds", clamped.toString())
                    )
                } catch (_: Exception) {}
            }
        }

    companion object { 
        val instance = MediaRepository()
        fun getInstance(context: Context) = instance.also { it.initDatabase(context) } 

        const val MAX_ADJUSTMENT_PER_VOTE = 0.01
        const val TOTAL_ADJUSTMENT_LIMIT = 0.20
    }

    fun refreshSort() { _librarySessionSeed.value = Random.nextLong() }
    private fun toLibraryItemUi(item: MediaItem) = LibraryItemUi(item.id, item.title, item.mediaType, item.imageUrl, item.uriPath, item.duration, item.selectionReason)
    
    suspend fun loadLibraryPage(
        filterType: String = _libraryFilter.value,
        sortCategory: SortCategory = _activeSortCategory.value,
        standardSort: StandardSortOption = _selectedStandardSort.value,
        intelligentSort: IntelligentSortOption = _selectedIntelligentSort.value,
        sessionSeed: Long = _librarySessionSeed.value,
        inputItems: List<MediaItem> = candidateEligibleMediaItems.value,
        searchRequest: SearchRequest = _librarySearchRequest.value,
        tasteDNA: TasteDNA = _tasteDNA.value,
        profile: TasteDNA.PreferenceProfile = _preferenceProfile.value,
        policy: DiscoveryPolicy = _discoveryPolicy.value,
        intent: UserIntent = _userIntent.value,
        stats: IntelligenceStats = _intelligenceStats.value,
        creatorProfiles: Map<String, CreatorProfile> = _creatorProfiles.value,
        comparisonCounts: Map<String, Int> = _comparisonCounts.value
    ): List<MediaItem> {
        val isSearchActive = when (searchRequest) {
            is SearchRequest.Text -> searchRequest.query.isNotBlank()
            is SearchRequest.Visual -> true
            is SearchRequest.Compound -> true
            is SearchRequest.MultiVisual -> searchRequest.visualVectors.isNotEmpty()
        }

        if (!isSearchActive) {
            return getFilteredAndSortedMedia(
                filterType = filterType,
                sortCategory = sortCategory,
                standardSort = standardSort,
                intelligentSort = intelligentSort,
                sessionSeed = sessionSeed,
                inputItems = inputItems,
                tasteDNA = tasteDNA,
                profile = profile,
                policy = policy,
                intent = intent,
                stats = stats,
                creatorProfiles = creatorProfiles,
                comparisonCounts = comparisonCounts
            )
        }

        val engine = hybridSearchEngine
        if (engine != null && engine.isSemanticReady()) {
            try {
                val res = engine.search(searchRequest, HybridSearchConfig(topK = 100))
                if (res.isSuccess && res.candidates.isNotEmpty()) {
                    val map = inputItems.associateBy { it.id }
                    return res.candidates.mapNotNull { map[it.mediaId] }
                        .filter { matchesFilterType(it, filterType) && isItemVisibleInLibrary(it) }
                } else {
                    if (searchRequest is SearchRequest.Visual || searchRequest is SearchRequest.MultiVisual) {
                        return emptyList()
                    }
                    val query = searchRequest.query ?: ""
                    return performLegacySearch(inputItems.filter { matchesFilterType(it, filterType) && isItemVisibleInLibrary(it) }, query)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("MediaRepository", "Error in loadLibraryPage hybrid search", e)
                if (searchRequest is SearchRequest.Visual || searchRequest is SearchRequest.MultiVisual) {
                    return emptyList()
                }
                val query = searchRequest.query ?: ""
                return performLegacySearch(inputItems.filter { matchesFilterType(it, filterType) && isItemVisibleInLibrary(it) }, query)
            }
        } else {
            if (searchRequest is SearchRequest.Visual || searchRequest is SearchRequest.MultiVisual) {
                return emptyList()
            }
            val query = searchRequest.query ?: ""
            return performLegacySearch(inputItems.filter { matchesFilterType(it, filterType) && isItemVisibleInLibrary(it) }, query)
        }
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val latestSortedFullItemsFlow: StateFlow<List<MediaItem>> = combine(
        visibleMediaItems, _tasteDNA, _preferenceProfile, _libraryFilter, _activeSortCategory,
        _selectedStandardSort, _selectedIntelligentSort,
        _librarySearchRequest.debounce { if ((it is SearchRequest.Text && it.query.isBlank()) || it is SearchRequest.Visual || it is SearchRequest.MultiVisual || it is SearchRequest.Compound) 0L else 300L }.distinctUntilChanged(),
        _librarySessionSeed, _discoveryPolicy, _userIntent, _intelligenceStats, _creatorProfiles, _comparisonCounts, _aiState
    ) { args -> 
        val items = args[0] as List<MediaItem>; val dna = args[1] as TasteDNA; val profile = args[2] as TasteDNA.PreferenceProfile; val filter = args[3] as String; val category = args[4] as SortCategory; val standard = args[5] as StandardSortOption; val intelligent = args[6] as IntelligentSortOption; val search = args[7] as SearchRequest; val seed = args[8] as Long; val policy = args[9] as DiscoveryPolicy; val intent = args[10] as UserIntent; val stats = args[11] as IntelligenceStats; val creators = args[12] as Map<String, CreatorProfile>; val counts = args[13] as Map<String, Int>
        loadLibraryPage(
            filterType = filter,
            sortCategory = category,
            standardSort = standard,
            intelligentSort = intelligent,
            sessionSeed = seed,
            inputItems = items,
            searchRequest = search,
            tasteDNA = dna,
            profile = profile,
            policy = policy,
            intent = intent,
            stats = stats,
            creatorProfiles = creators,
            comparisonCounts = counts
        )
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    
    val latestAiSortRecommendation = combine(
        latestSortedFullItemsFlow,
        _libraryPageOffset
    ) { fullList, offset ->
        Log.d("LibraryPaging", "fullList.size=${fullList.size} offset=$offset emitting=${(offset + LIBRARY_PAGE_SIZE).coerceAtMost(fullList.size)}")
        val end = (offset + LIBRARY_PAGE_SIZE).coerceAtMost(fullList.size)
        fullList.take(end).map { toLibraryItemUi(it) }
    }
    .distinctUntilChanged()
    .flowOn(dispatcher)
    .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val latestLibraryPresentation = combine(latestSortedFullItemsFlow, _libraryPageOffset, _libraryFilter, _activeSortCategory, _selectedStandardSort, _selectedIntelligentSort, _librarySearchRequest, _searchErrorMessage, _activeVisualReferences, _aiState, _databaseState, _gridDensity, _autoScrollSpeed, _importProgress, _scanProgress, latestLibraryProvenance) { args ->
        val items = args[0] as List<MediaItem>; val offset = args[1] as Int; val filter = args[2] as String; val category = args[3] as SortCategory; val std = args[4] as StandardSortOption; val intel = args[5] as IntelligentSortOption; val search = args[6] as SearchRequest; val err = args[7] as String?; val visual = args[8] as List<MediaItem>; val ai = args[9] as AIState; val db = args[10] as DatabaseState; val grid = args[11] as Float; val auto = args[12] as AutoScrollSpeed; val import = args[13] as ImportProgressState; val scan = args[14] as ScanProgressState; val prov = args[15] as Map<String, DecisionProvenance>
        val end = (offset + LIBRARY_PAGE_SIZE).coerceAtMost(items.size)
        val pagedItems = items.take(end)
        LibraryPresentationState(pagedItems, filter, category, std, intel, search, err, visual, ai, db, false, auto, grid, false, emptySet(), import, scan, prov)
    }.stateIn(scope, SharingStarted.Eagerly, LibraryPresentationState(emptyList()))

    val latestDiscoverPresentation = combine(_discoverSnapshot, _databaseState, _aiState, latestDiscoverProvenance, _tasteDNA, _preferenceProfile, _discoveryPolicy) { args ->
        val snap = args[0] as DiscoverSnapshot?; val db = args[1] as DatabaseState; val ai = args[2] as AIState; val prov = args[3] as Map<String, DecisionProvenance>; val dna = args[4] as TasteDNA; val profile = args[5] as TasteDNA.PreferenceProfile; val policy = args[6] as DiscoveryPolicy
        DiscoverPresentationState(snap?.obsessions ?: emptyList(), ConfidenceEngine.calculateDiscoveryState(candidateEligibleMediaItems.value, _intelligenceStats.value), ai == AIState.BOOTSTRAP, null, null, dna, profile, policy, prov)
    }.stateIn(scope, SharingStarted.Eagerly, DiscoverPresentationState())

    fun initDatabase(context: Context) {
        applicationContext = context.applicationContext
        if (blueprintArtifactManager == null) blueprintArtifactManager = BlueprintArtifactManager(context.applicationContext)
        if (libraryPreferences == null) libraryPreferences = LibraryPreferences(context.applicationContext)
        
        if (_databaseState.value == DatabaseState.READY) return

        synchronized(this) {
            if (_databaseState.value == DatabaseState.TIMEOUT || _databaseState.value == DatabaseState.TRANSITION_FAILED) {
                initJob?.cancel()
                initJob = null
            }
            if (initJob?.isActive == true) return
            
            if (database != null) {
                _databaseState.value = DatabaseState.READY
                startDatabaseObservers(database!!)
                if (intelligenceCore == null) scope.launch { initAI(context) }
                return
            }

            _databaseState.value = DatabaseState.INITIALIZING
            _initializationDetail.value = "Initializing secure layer..."
            initJob = scope.launch {
                try {
                    // Try to initialize SQLCipher, but don't crash if it fails (e.g. in tests)
                    try {
                        SQLCipherInitializer.initialize(context)
                    } catch (e: Exception) {
                        Log.w("MediaRepository", "SQLCipher init failed, continuing...")
                    }
                    val transitionResult = LegacyDatabaseEncryptionMigrator.ensureEncryption(context)
                    if (transitionResult is LegacyDatabaseEncryptionMigrator.TransitionResult.Failure) { _databaseErrorMessage.value = transitionResult.reason; _databaseState.value = if (transitionResult.reason.contains("corrupt")) DatabaseState.CORRUPTED else DatabaseState.TRANSITION_FAILED; return@launch }
                    _databaseState.value = DatabaseState.VERIFYING
                    val db = AuraDatabase.getInstance(context); database = db
                    exposureRepository = ExposureRepository(db.exposureEventDao())
                    val consentManager = ContributionConsentManager(SharedPreferencesConsentStorage(context))
                    contributionQueueRepository = ContributionQueueRepository(db.contributionQueueDao(), consentManager)
                    scope.launch { consentManager.consentStateFlow.collect { _consentState.value = it } }
                    intelligenceRepository = IntelligenceRepository(db.intelligenceDao(), this@MediaRepository, moshi, scope, db)
                    interactionRepository = InteractionRepository(db.interactionDao(), moshi, scope)
                    playbackErrorLogRepository = PlaybackErrorLogRepository(db.playbackErrorLogDao(), scope)
                    entitlementRepository = EntitlementRepository(db.userPreferenceDao(), scope)
                    billingManager = BillingManager(context, entitlementRepository!!, scope)
                    searchFeedbackRepository = SearchFeedbackRepository(db.searchFeedbackDao(), scope)
                    conversionQueueRepository = ConversionQueueRepository(db.conversionJobDao(), db.userPreferenceDao(), androidx.work.WorkManager.getInstance(context))
                    semanticRepresentationRepository = RoomSemanticRepresentationRepository(db.semanticRepresentationDao())
                    // Publish database readiness IMMEDIATELY once Room & SQLCipher are open.
                    // AI model loading (ONNX asset extraction + session allocation) is heavy
                    // and must not block DatabaseState.READY, otherwise the MainActivity
                    // 45s watchdog fires forceTimeoutState() on cold starts.
                    _databaseState.value = DatabaseState.READY
                    startDatabaseObservers(db)
                    startAiHealthObserver(context)
                    startStuckEnrichmentRecovery(context)
                    try {
                        db.mediaDao().markUnextractableMediaAsTerminal()
                    } catch (e: Exception) {
                        Log.w("MediaRepository", "Failed to mark unextractable media as terminal", e)
                    }
                    LegacyDatabaseEncryptionMigrator.cleanupLegacyBackups(context)
                    // Bootstrap AI models asynchronously in the background
                    launch {
                        try {
                            initAI(context)
                            if (_aiState.value == AIState.READY) {
                                backfillSemantics()
                                applicationContext?.let { ctx -> AuraEnrichmentWorker.schedule(ctx) }
                            }
                        } catch (e: Exception) {
                            Log.e("MediaRepository", "Non-fatal background AI initialization error", e)
                            _aiState.value = AIState.ERROR
                        }
                    }
                } catch (t: Throwable) { if (t !is CancellationException) { _databaseErrorMessage.value = t.message; _databaseState.value = DatabaseState.TRANSITION_FAILED } }
            }
        }
    }

    private var observersJob: Job? = null

    private fun isDatabaseClosedException(e: Throwable): Boolean {
        val msg = e.message ?: ""
        return e is IllegalStateException && (
            msg.contains("connection pool has been closed", ignoreCase = true) ||
            (msg.contains("database", ignoreCase = true) && msg.contains("closed", ignoreCase = true))
        )
    }
    
    @androidx.annotation.VisibleForTesting
    internal fun startDatabaseObservers(db: AuraDatabase) {
        observersJob?.cancel()
        observersJob = scope.launch {
            launch {
                try {
                    db.mediaDao().getAllMedia().conflate().transform { emit(it); if (_scanProgress.value.isScanning) delay(3000) }.collect { entities ->
                        val items = entities.map { it.toMediaItem() }.filter {
                            !it.isDeleted && it.compatibilityStatus !in listOf(
                                CompatibilityStatus.CORRUPT,
                                CompatibilityStatus.UNREADABLE,
                                CompatibilityStatus.DELETED,
                                CompatibilityStatus.REPLACED
                            )
                        }
                        setMediaItemsState(items)
                        _activePlaylist.update { p -> if (p == null) null else { val list = p.items.map { s -> items.find { it.id == s.id } ?: s }; val idx = p.authoritativeMediaId?.let { id -> list.indexOfFirst { it.id == id }.let { if (it != -1) it else p.currentIndex.coerceAtMost(list.size-1) } } ?: p.currentIndex.coerceAtMost(list.size-1); p.copy(items = list, currentIndex = idx, authoritativeMediaId = list.getOrNull(idx)?.id) } }
                        if (items.filter { it.itemCount == null }.size >= 2) refreshPairwiseCandidatePoolAndSelectNext(false)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e is IllegalStateException && isDatabaseClosedException(e)) {
                        // DB closed
                    } else {
                        Log.e("MediaRepository", "Error observing media database", e)
                    }
                }
            }
            launch {
                try {
                    db.mediaDao().getWatchHistory().collect { _watchHistory.value = it.map { it.toMediaItem() } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Safe catch
                }
            }
            launch {
                try {
                    db.searchHistoryDao().getRecentSearches().collect { _recentSearches.value = it.map { it.query } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Safe catch
                }
            }
            launch {
                try {
                    db.pairwiseDao()?.getAllOutcomes()?.collect { outcomes -> val counts = mutableMapOf<String, Int>(); outcomes.forEach { counts[it.optionAId] = (counts[it.optionAId] ?: 0) + 1; counts[it.optionBId] = (counts[it.optionBId] ?: 0) + 1 }; _comparisonCounts.value = counts }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Safe catch
                }
            }
            launch {
                try {
                    val tasteDnaAdapter = moshi.adapter(TasteDNA::class.java)
                    val profileAdapter = moshi.adapter(TasteDNA.PreferenceProfile::class.java)
                    val discoveryPolicyAdapter = moshi.adapter(DiscoveryPolicy::class.java)
                    db.userPreferenceDao()?.getPreference("taste_dna")?.value?.let { try { tasteDnaAdapter.fromJson(it)?.let { _tasteDNA.value = it.sanitize() } } catch (e: Exception) {} }
                    db.userPreferenceDao()?.getPreference("preference_profile")?.value?.let { try { profileAdapter.fromJson(it)?.let { _preferenceProfile.value = it.sanitize() } } catch (e: Exception) {} }
                    db.userPreferenceDao()?.getPreference("discovery_policy")?.value?.let { try { discoveryPolicyAdapter.fromJson(it)?.let { _discoveryPolicy.value = it } } catch (e: Exception) {} }
                    db.userPreferenceDao()?.getPreference("slideshow_delay_seconds")?.value?.let { str ->
                        str.toIntOrNull()?.let { delayVal ->
                            _slideshowDelaySeconds.value = delayVal.coerceIn(1, 10)
                        }
                    }
                    db.userPreferenceDao()?.getPreference("search_seeded_channels")?.value?.let { json ->
                        try {
                            val channelListAdapter = moshi.adapter<List<Channel>>(
                                com.squareup.moshi.Types.newParameterizedType(List::class.java, Channel::class.java)
                            )
                            channelListAdapter.fromJson(json)?.let { loaded ->
                                _searchSeededChannels.value = loaded
                                loaded.forEach { ChannelRegistry.addSearchSeededChannel(it) }
                            }
                        } catch (e: Exception) {}
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IllegalStateException) {
                    if (!isDatabaseClosedException(e)) throw e
                }
            }
            launch {
                try {
                    db.creatorDao()?.getAllCreators()?.collect { _creatorProfiles.value = it.associate { it.id to CreatorProfile(it.id, it.name, it.platform, it.affinityScore, it.interactionCount, it.lastInteractionTimestamp, if (it.topMoodTagsJson.isBlank()) emptyList() else it.topMoodTagsJson.split(",")) } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IllegalStateException) {
                    if (!isDatabaseClosedException(e)) throw e
                }
            }
            launch {
                try {
                    db.evidenceDao()?.getAllEvidence()?.collect { _storedEvidence.value = it.map { EvidenceRecord(id = it.id, tier = EvidenceTier.valueOf(it.tier), sampleCount = it.sampleCount, score = it.score, quality = it.quality, source = it.source, timestamp = it.timestamp, associatedManifestId = it.associatedManifestId) } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IllegalStateException) {
                    if (!isDatabaseClosedException(e)) throw e
                }
            }
        }
    }

    private suspend fun initAI(context: Context) {
        if (intelligenceCore != null && aiState.value == AIState.READY) return
        _aiState.value = AIState.BOOTSTRAP
        try {
            val engine = try { OnnxRuntimeMiniLMInferenceEngine(modelPath = com.example.util.ModelAssetLoader.getLocalPath(context, "models/all-minilm-l6-v2.onnx")) } catch (e: Exception) { LocalMiniLMInferenceEngine() }
            val tokenizer = try { BertWordPieceTokenizer.fromVocabText(context.assets.open("models/vocab.txt").use { it.bufferedReader().readText() }) } catch (e: Exception) { BertWordPieceTokenizer() }
            val provider = MiniLMEmbeddingProvider(engine, tokenizer); embeddingProvider = provider
            val retriever = DefaultSemanticCandidateRetriever(semanticRepresentationRepository!!); semanticCandidateRetriever = retriever
            semanticSearchService = DefaultSemanticSearchService(provider, retriever)
            semanticIndexingService = DefaultSemanticIndexingService(provider, retriever, semanticRepresentationRepository!!)
            scope.launch { try { retriever.initializeIndex(provider.descriptor.primaryType, provider.descriptor) } catch (e: Exception) {} }
            val router = RetrievalRouter(ProductionLexicalRetriever(), semanticSearchService as? SemanticRetrievalProvider, null)
            intelligenceCore = AuraIntelligenceCore(this, router, dispatcher = dispatcher)
            hybridSearchEngine = DefaultHybridSearchEngine(intelligenceCore!!)
            try {
                val clipEngine = try {
                    OnnxRuntimeMobileCLIPInferenceEngine(modelPath = com.example.util.ModelAssetLoader.getLocalPath(context, "models/tinyclip_vision.onnx"))
                } catch (e: Exception) {
                    Log.w("MediaRepository", "Failed to load TinyCLIP ONNX image model, falling back to LocalMobileCLIPInferenceEngine", e)
                    LocalMobileCLIPInferenceEngine()
                }
                mobileCLIPProvider = TinyCLIPEmbeddingProvider(clipEngine)

                val clipTokenizer = try {
                    ClipBpeTokenizer.fromAssets(context.assets.open("models/mobileclip_vocab.json").use { it.bufferedReader().readText() }, context.assets.open("models/mobileclip_merges.txt").use { it.bufferedReader().readText() })
                } catch (e: Exception) {
                    ClipBpeTokenizer(emptyMap(), emptyList())
                }
                val textEngine = try {
                    OnnxRuntimeMobileCLIPTextInferenceEngine(modelPath = com.example.util.ModelAssetLoader.getLocalPath(context, "models/tinyclip_text.onnx"))
                } catch (e: Exception) {
                    LocalMobileCLIPTextInferenceEngine()
                }
                mobileClipTextProvider = TinyCLIPTextEmbeddingProvider(textEngine, clipTokenizer)

                visualIndexingService = DefaultVisualIndexingService(mobileCLIPProvider!!, retriever, semanticRepresentationRepository!!)
                scope.launch { try { visualIndexingService?.initializeIndex() } catch (e: Exception) {} }
                visualContextEngine = com.example.data.visual.VisualContextEngine(this, mobileCLIPProvider, semanticRepresentationRepository, semanticCandidateRetriever)
                router.visualProvider = DefaultMobileCLIPVisualRetriever(DefaultSemanticSearchService(mobileClipTextProvider!!, retriever))
                hybridSearchEngine = DefaultHybridSearchEngine(intelligenceCore!!)
            } catch (e: Exception) {
                Log.e("MediaRepository", "TinyCLIP initialization error", e)
                if (mobileCLIPProvider == null) {
                    mobileCLIPProvider = TinyCLIPEmbeddingProvider(LocalMobileCLIPInferenceEngine())
                }
            }
            _aiState.value = AIState.READY
            try { 
                backfillSemantics()
                AuraEnrichmentWorker.schedule(context) 
            } catch (e: Exception) { Log.w("MediaRepository", "Failed to schedule enrichment", e) }
        } catch (e: Exception) { _aiState.value = AIState.ERROR }
    }

    fun getMediaItemById(id: String) = _mediaItems.value.find { it.id == id } ?: _activePlaylist.value?.items?.find { it.id == id }
    
    fun logInteraction(item: MediaItem) {
        val now = System.currentTimeMillis(); updateMediaItemsState { list -> list.map { if (it.id == item.id) it.copy(viewCount = it.viewCount + 1, lastViewedTimestamp = now) else it } }
        scope.launch { database?.mediaDao()?.let { dao -> dao.getMediaById(item.id)?.let { dao.update(it.copy(playCount = it.playCount + 1, lastViewedTimestamp = now)) } } }
        if (_tasteDNA.value.isFineTuningEnabled) { var dna = _tasteDNA.value; PersonalizationTraitMapper.getEffectiveTraitAdjustments(item).forEach { (dim, mult) -> dna = dna.updateLearnedDimension(dim, mult * 0.005, 0.20) }; if (dna != _tasteDNA.value) updateTasteDNA(dna, false, "View") }
        learnPreferenceSignals(item, isSuccess = true)
    }
    
    fun recordMediaCompletion(id: String) {
        getMediaItemById(id)?.let { item ->
            updateMediaItemsState { list -> list.map { if (it.id == id) it.copy(progress = 1.0f) else it } }
            scope.launch { 
                database?.mediaDao()?.let { dao -> dao.getMediaById(id)?.let { dao.update(it.copy(progress = 1.0f)) } }
                database?.savedMediaDao()?.deleteMedia(id)
            }
            if (_tasteDNA.value.isFineTuningEnabled) { var dna = _tasteDNA.value; PersonalizationTraitMapper.getEffectiveTraitAdjustments(item).forEach { (dim, mult) -> dna = dna.updateLearnedDimension(dim, mult * 0.01, 0.20) }; updateTasteDNA(dna, false, "Completion") }
            learnPreferenceSignals(item, isCompletion = true, isSuccess = true)
        }
    }
    
    private fun learnPreferenceSignals(item: MediaItem, isCompletion: Boolean = false, isDiscovery: Boolean = false, isSuccess: Boolean = true) {
        val current = _preferenceProfile.value; val reason = item.selectionReason ?: ""; val staged = current.copy(interactionsCount = current.interactionsCount + 1, similaritySignal = current.similaritySignal + (if (isCompletion || reason.contains("match")) 1 else 0), collaborativeSignal = current.collaborativeSignal + (if (isDiscovery || reason.contains("Discovery")) 1 else 0))
        if (staged.interactionsCount >= 10) updatePreferenceProfile(staged.copy(contentSimilarityWeight = staged.contentSimilarityWeight + (if (staged.similaritySignal > 3) 0.01 else 0.0), collaborativeWeight = staged.collaborativeWeight + (if (staged.collaborativeSignal > 3) 0.01 else 0.0), interactionsCount = 0).normalize(2.0))
        else { _preferenceProfile.value = staged; scope.launch { database?.userPreferenceDao()?.insertPreference(UserPreferenceEntity("preference_profile", moshi.adapter(TasteDNA.PreferenceProfile::class.java).toJson(staged))) } }
    }
    
    fun updateTasteDNA(dna: TasteDNA, isUserGenerated: Boolean = false, evidenceCategory: String = "Manual") { _tasteDNA.value = dna; scope.launch { val tasteDnaAdapter = moshi.adapter(TasteDNA::class.java); database?.userPreferenceDao()?.insertPreference(UserPreferenceEntity("taste_dna", tasteDnaAdapter.toJson(dna))); refreshPairwiseCandidatePoolAndSelectNext(false) } }
    
    fun updatePreferenceProfile(profile: TasteDNA.PreferenceProfile) { _preferenceProfile.value = profile; scope.launch { val profileAdapter = moshi.adapter(TasteDNA.PreferenceProfile::class.java); database?.userPreferenceDao()?.insertPreference(UserPreferenceEntity("preference_profile", profileAdapter.toJson(profile))); refreshPairwiseCandidatePoolAndSelectNext(false) } }
    
    fun injectEvidence(tier: EvidenceTier, count: Int, score: Double, quality: Double, manifestId: String? = null) { scope.launch { val id = UUID.randomUUID().toString(); database?.evidenceDao()?.insertEvidence(EvidenceEntity(id, tier.name, count, score, quality, "Agent", System.currentTimeMillis(), manifestId)); intelligenceRepository?.onEvidenceAvailable(id) } }
    
    fun clearEvidence() { scope.launch { database?.evidenceDao()?.clearAll() } }
    
    private fun scoreMediaItemForPersonalization(mediaId: String): Float = getMediaItemById(mediaId)?.let { intelligenceCore?.scorePersonalization(it, _tasteDNA.value) } ?: 0.5f
    
    private val pairwiseLock = Any()
    private val pairwiseWins = mutableMapOf<String, Int>()
    private val pairwiseLosses = mutableMapOf<String, Int>()
    private val recentPairs = mutableListOf<Pair<String, String>>()
    private val recentItemIds = mutableListOf<String>()
    
    @Volatile private var preFetchedPair: Pair<MediaItem, MediaItem>? = null
    @Volatile private var cachedDiscoveryState: SystemDiscoveryState? = null
    @Volatile private var cachedDiscoveryStateTimestamp: Long = 0L

    private fun getDiscoveryStateMemoized(items: List<MediaItem>): SystemDiscoveryState {
        val now = System.currentTimeMillis()
        val cached = cachedDiscoveryState
        if (cached != null && (now - cachedDiscoveryStateTimestamp < 30_000L)) {
            return cached
        }
        val fresh = ConfidenceEngine.calculateDiscoveryState(items, _intelligenceStats.value)
        cachedDiscoveryState = fresh
        cachedDiscoveryStateTimestamp = now
        return fresh
    }

    fun refreshPairwiseCandidatePoolAndSelectNext(forceNextPair: Boolean = true) {
        scope.launch(dispatcher) {
            val session = _compareSelectionSession.value
            val mediaTypeFilter = _compareMediaType.value
            val rawItems = if (session.isActive) candidateEligibleMediaItems.value.filter { it.id in session.selectedIds } else candidateEligibleMediaItems.value
            val items = rawItems.filter { item ->
                val isVideo = item.mediaType.equals("VIDEO", ignoreCase = true) || item.mediaType.equals("MOVIE", ignoreCase = true) || item.mediaType.startsWith("VIDEO", ignoreCase = true) || item.mediaType.startsWith("MOVIE", ignoreCase = true)
                when (mediaTypeFilter) {
                    CompareMediaTypeFilter.PHOTOS -> !isVideo
                    CompareMediaTypeFilter.VIDEOS -> isVideo
                }
            }
            val context = applicationContext
            val eligible = items.filter { item ->
                if (item.isDeleted || item.itemCount != null) return@filter false
                isItemEligibleForCandidates(item) && isItemVisibleInLibrary(item) && (item.uriPath.isNotBlank() || item.imageUrl.isNotBlank() || item.id.isNotBlank())
            }
            if (eligible.size < 2) { 
                _pairwiseState.value = PairwiseComparison("p_empty", _pairwiseState.value.roundNumber, 50, emptyMediaItem, emptyMediaItem)
                preFetchedPair = null
                if (session.isActive && !session.isComplete) {
                    _compareSelectionSession.update { it.copy(isComplete = true, completionReason = "Fewer than 2 eligible items remain.") }
                }
                return@launch 
            }

            val currentPair = _pairwiseState.value
            val isCurrentEmpty = currentPair.optionA.id.isEmpty() || currentPair.optionB.id.isEmpty()
            val shouldAdvance = forceNextPair || isCurrentEmpty

            if (forceNextPair) {
                preFetchedPair = null
            }

            var selectedPair: Pair<MediaItem, MediaItem>? = null
            if (shouldAdvance && preFetchedPair != null) {
                val candidate = preFetchedPair!!
                if (context == null || (isUriAccessible(context, candidate.first.uriPath) && isUriAccessible(context, candidate.second.uriPath))) {
                    selectedPair = candidate
                }
                preFetchedPair = null
            }

            if (selectedPair == null) {
                val currentWins = synchronized(pairwiseLock) { pairwiseWins.toMap() }
                val currentLosses = synchronized(pairwiseLock) { pairwiseLosses.toMap() }
                val currentRecentPairs = synchronized(pairwiseLock) { recentPairs.toList() }
                val currentRecentItemIds = synchronized(pairwiseLock) { recentItemIds.toList() }
                val top100 = RecommendationEngine.getTop100PairwiseCandidates(
                    repository = this@MediaRepository, 
                    winsMap = currentWins, 
                    lossesMap = currentLosses, 
                    mediaTypeFilter = mediaTypeFilter.name,
                    compareStrategy = _compareStrategy.value, 
                    compareSort = _compareSort.value,
                    inputItems = eligible
                )
                _pairwiseDiagnostics.value = PairwiseDiagnostics(
                    totalEligibleMedia = eligible.size,
                    top100CandidatePoolSize = top100.size,
                    poolRefreshTimestamp = System.currentTimeMillis(),
                    topCandidateIds = top100.map { it.first.id }
                )
                val discoveryState = getDiscoveryStateMemoized(items)
                val rawPair = RecommendationEngine.selectNextPairFromPool(
                    top100, 
                    _comparisonCounts.value, 
                    if (session.isActive) session.comparedPairIds else currentRecentPairs, 
                    if (session.isActive) session.comparedPairIds.flatMap { listOf(it.first, it.second) } else currentRecentItemIds, 
                    mediaTypeFilter.name, 
                    _librarySessionSeed.value, 
                    DiscoveryPolicyManager.resolveStrategy(_discoveryPolicy.value, _userIntent.value, RecommendationObjective.RANKING_REFINEMENT, discoveryState, _tasteDNA.value, _preferenceProfile.value), 
                    _tasteDNA.value, 
                    _creatorProfiles.value, 
                    _compareStrategy.value
                )
                if (rawPair != null && context != null) {
                    if (!isUriAccessible(context, rawPair.first.uriPath)) {
                        updateItemCompatibilityStatus(rawPair.first.id, CompatibilityStatus.UNREADABLE)
                    } else if (!isUriAccessible(context, rawPair.second.uriPath)) {
                        updateItemCompatibilityStatus(rawPair.second.id, CompatibilityStatus.UNREADABLE)
                    } else {
                        selectedPair = rawPair
                    }
                } else {
                    selectedPair = rawPair
                }
            }

            if (shouldAdvance && selectedPair != null) {
                val next = selectedPair!!
                val round = if (session.isActive) session.roundNumber else (if (isCurrentEmpty) 1 else _pairwiseState.value.roundNumber + 1)
                _pairwiseState.value = PairwiseComparison("p$round", round, if (session.isActive) session.maxRounds else 50, next.first, next.second)
                if (!session.isActive) { 
                    synchronized(pairwiseLock) {
                        recentPairs.add(0, next.first.id to next.second.id)
                        if (recentPairs.size > 10) recentPairs.removeAt(10)
                        recentItemIds.add(0, next.first.id)
                        recentItemIds.add(0, next.second.id)
                        while (recentItemIds.size > 20) { recentItemIds.removeAt(recentItemIds.size - 1) } 
                    }
                }
                recordExposures(listOf(next.first.id, next.second.id))
            } else if (shouldAdvance && session.isActive && !session.isComplete) {
                _compareSelectionSession.update { it.copy(isComplete = true, completionReason = "All unique pairs exhausted.") }
                _pairwiseState.value = PairwiseComparison("p_empty", _pairwiseState.value.roundNumber, 50, emptyMediaItem, emptyMediaItem)
            }

            // Asynchronously pre-fetch the next pair for the subsequent vote
            try {
                val currentWins = synchronized(pairwiseLock) { pairwiseWins.toMap() }
                val currentLosses = synchronized(pairwiseLock) { pairwiseLosses.toMap() }
                val currentRecentPairs = synchronized(pairwiseLock) { recentPairs.toList() }
                val currentRecentItemIds = synchronized(pairwiseLock) { recentItemIds.toList() }
                val top100 = RecommendationEngine.getTop100PairwiseCandidates(
                    repository = this@MediaRepository, 
                    winsMap = currentWins, 
                    lossesMap = currentLosses, 
                    mediaTypeFilter = mediaTypeFilter.name,
                    compareStrategy = _compareStrategy.value, 
                    compareSort = _compareSort.value,
                    inputItems = eligible
                )
                val discoveryState = getDiscoveryStateMemoized(items)
                val nextPair = RecommendationEngine.selectNextPairFromPool(
                    top100, 
                    _comparisonCounts.value, 
                    if (session.isActive) session.comparedPairIds else currentRecentPairs, 
                    if (session.isActive) session.comparedPairIds.flatMap { listOf(it.first, it.second) } else currentRecentItemIds, 
                    mediaTypeFilter.name, 
                    _librarySessionSeed.value + 1L, 
                    DiscoveryPolicyManager.resolveStrategy(_discoveryPolicy.value, _userIntent.value, RecommendationObjective.RANKING_REFINEMENT, discoveryState, _tasteDNA.value, _preferenceProfile.value), 
                    _tasteDNA.value, 
                    _creatorProfiles.value, 
                    _compareStrategy.value
                )
                if (nextPair != null && (nextPair.first.id != _pairwiseState.value.optionA.id || nextPair.second.id != _pairwiseState.value.optionB.id)) {
                    preFetchedPair = nextPair
                }
            } catch (e: Exception) {
                Log.w("MediaRepository", "Pre-fetch pair failed", e)
            }
        }
    }
    
    private val LOW_WATER_THRESHOLD = 5
    private var isReplenishing = false

    fun setPlaylist(items: List<MediaItem>, initialIndex: Int, sourceTitle: String = "Playlist", isFinite: Boolean = false) {
        if (items.isEmpty()) { _activePlaylist.value = null; return }; _isPlayerActive.value = true
        val sanitized = items.filter { isItemVisibleInLibrary(it) }
        if (sanitized.isEmpty()) { _activePlaylist.value = null; return }
        
        lastPlaybackPositionMs = 0L
        isResumingFromBackground = false
        
        val idx = items.getOrNull(initialIndex)?.let { orig -> sanitized.indexOfFirst { it.id == orig.id }.let { if (it != -1) it else 0 } } ?: 0
        _activePlaylist.value = PlaylistState(sanitized, idx, sanitized.getOrNull(idx)?.id, sourceTitle, isFinite = isFinite)
    }

    fun setChannelPlaylist(
        channel: Channel,
        filterType: String,
        items: List<MediaItem>,
        initialIndex: Int = 0,
        sourceTitle: String
    ) {
        if (items.isEmpty()) { _activePlaylist.value = null; return }
        _isPlayerActive.value = true
        val sanitized = items.filter { isItemVisibleInLibrary(it) }
        if (sanitized.isEmpty()) { _activePlaylist.value = null; return }

        lastPlaybackPositionMs = 0L
        isResumingFromBackground = false

        val safeIndex = initialIndex.coerceIn(0, (sanitized.size - 1).coerceAtLeast(0))
        _activePlaylist.value = PlaylistState(
            items = sanitized,
            currentIndex = safeIndex,
            authoritativeMediaId = sanitized.getOrNull(safeIndex)?.id,
            sourceTitle = sourceTitle,
            channel = channel,
            channelFilterType = filterType
        )
    }

    fun extendActivePlaylist(newItems: List<MediaItem>) {
        val current = _activePlaylist.value ?: return
        if (newItems.isEmpty()) return
        val existingIds = current.items.map { it.id }.toSet()
        val sanitizedNew = newItems.filter { isItemVisibleInLibrary(it) && !existingIds.contains(it.id) }
        if (sanitizedNew.isEmpty()) return

        _activePlaylist.value = current.copy(items = current.items + sanitizedNew)
    }

    /**
     * Returns the next [count] eligible Library items not already present in [excludeIds],
     * using the same sorted/filtered order that the Library grid uses.
     */
    private suspend fun getNextLibraryBatch(excludeIds: Set<String>, count: Int): List<MediaItem> {
        val full = latestSortedFullItemsFlow.first()
        Log.d("LibraryPaging", "getNextLibraryBatch full.size=${full.size} exclude=${excludeIds.size} returning up to $count")
        return full
            .asSequence()
            .filter { it.id !in excludeIds && !it.isDeleted }
            .take(count)
            .toList()
    }
    
    fun clearPlaylist() { 
        _isPlayerActive.value = false
        _activePlaylist.value = null 
        lastPlaybackPositionMs = 0L
        isResumingFromBackground = false
    }

    private val channelProgrammer = ChannelProgrammer()

    fun checkAndReplenishActivePlaylist(currentIndex: Int, totalSize: Int) {
        val current = _activePlaylist.value ?: return
        if (current.isFinite) return
        val remaining = totalSize - 1 - currentIndex
        if (remaining > LOW_WATER_THRESHOLD || isReplenishing) return

        isReplenishing = true
        scope.launch {
            try {
                if (current.channel == null) {
                    // Library Playback Replenishment
                    val fullList = latestSortedFullItemsFlow.value
                    val existingIds = current.items.map { it.id }.toSet()
                    val newCandidates = fullList.filter { !it.isDeleted && !existingIds.contains(it.id) }.take(10)
                    if (newCandidates.isNotEmpty()) {
                        extendActivePlaylist(newCandidates)
                    }
                } else {
                    // Channel Playback Replenishment
                    val selectedChannel = current.channel
                    val existingIds = current.items.map { it.id }.toSet()
                    val candidatePool = candidateEligibleMediaItems.value.filter { !existingIds.contains(it.id) }
                    val channelContext = ChannelProgrammingContext(
                        availableMedia = candidatePool,
                        filterType = current.channelFilterType ?: "ALL",
                        tasteDNA = _tasteDNA.value,
                        exposureMap = candidatePool.associate { it.id to it.exposureCount },
                        sessionExposures = exposureRepository?.getSessionExposuresSnapshot() ?: emptyMap(),
                        decayedExposureMap = candidatePool.associate { it.id to it.getDecayedExposure() }
                    )
                    val nextBlock = channelProgrammer.programChannel(selectedChannel, channelContext, limit = 10)
                    if (nextBlock.isNotEmpty() && _activePlaylist.value?.channel?.id == selectedChannel.id) {
                        extendActivePlaylist(nextBlock)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("MediaRepository", "Error in checkAndReplenishActivePlaylist", e)
            } finally {
                isReplenishing = false
            }
        }
    }

    fun selectPlaylistItem(index: Int) {
        _activePlaylist.update { current ->
            if (current != null && index in current.items.indices) {
                val safeIndex = index.coerceIn(0, current.items.lastIndex)
                val next = current.items[safeIndex]
                recordView(next.id)

                checkAndReplenishActivePlaylist(safeIndex, current.items.size)

                current.copy(
                    currentIndex = safeIndex,
                    authoritativeMediaId = next.id
                )
            } else current
        }
    }
    fun nextPlaylistItem() { _activePlaylist.value?.let { if (it.hasNext) selectPlaylistItem(it.currentIndex + 1) } }
    fun previousPlaylistItem() { _activePlaylist.value?.let { if (it.hasPrevious) selectPlaylistItem(it.currentIndex - 1) } }
    
    fun recordView(id: String) { _mediaItems.value.find { it.id == id }?.let { logInteraction(it) } }
    fun recordExposure(id: String) { recordExposures(listOf(id)) }
    
    fun importMediaFromUris(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            _importProgress.value = ImportProgressState(isImporting = true, totalFiles = uris.size)
            val new = uris.mapNotNull { uri -> try { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION); val type = if ((context.contentResolver.getType(uri) ?: "").startsWith("video/")) "VIDEO" else "PHOTO"
                val report = AuraMediaCompatibilityEngine.analyzeMedia(context, uri.toString(), type)
                if (AuraMediaCompatibilityEngine.isEligibleForImport(report.status)) MediaEntity(id = "import_${UUID.randomUUID()}", title = "Imported", mediaType = type, imageUrl = uri.toString(), uriPath = uri.toString(), sizeBytes = report.sizeBytes, durationMs = report.durationMs, compatibilityStatus = report.status.name, conversionStatus = report.conversionStatus.name) else null
            } catch (e: Exception) { null } }
            if (new.isNotEmpty()) database?.mediaDao()?.insertAll(new)
            _importProgress.value = ImportProgressState(isImporting = false)
        }
    }
    
    private val exposureBuffer = mutableSetOf<String>(); private var exposureFlushJob: Job? = null
    fun recordExposures(ids: List<String>) { if (ids.isEmpty()) return; synchronized(exposureBuffer) { exposureBuffer.addAll(ids) }; if (exposureFlushJob?.isActive != true) exposureFlushJob = scope.launch { delay(5000); flushExposures() } }
    private suspend fun flushExposures() { val ids = synchronized(exposureBuffer) { val copy = exposureBuffer.toList(); exposureBuffer.clear(); copy }; if (ids.isEmpty()) return; val now = System.currentTimeMillis(); database?.mediaDao()?.let { dao -> val entities = ids.mapNotNull { id -> dao.getMediaById(id)?.let { it.copy(exposureCount = it.exposureCount + 1, lastExposedTimestamp = now) } }; if (entities.isNotEmpty()) dao.updateAll(entities) } }
    
    fun updateRating(id: String, rating: Float) { scope.launch { updateMediaItemsState { items -> items.map { if (it.id == id) it.copy(rating = rating) else it } }; database?.mediaDao()?.getMediaById(id)?.let { database?.mediaDao()?.update(it.copy(rating = rating)) } } }
    fun setFavorite(id: String, isFavorite: Boolean) {
        val item = getMediaItemById(id) ?: return
        val updated = item.copy(isFavorite = isFavorite)
        updateMediaItemsState { list -> list.map { if (it.id == id) updated else it } }
        
        _activePlaylist.update { playlist ->
            playlist?.copy(
                items = playlist.items.map { if (it.id == id) it.copy(isFavorite = isFavorite) else it }
            )
        }

        scope.launch {
            database?.mediaDao()?.update(updated.toEntity())
            if (isFavorite) {
                database?.microMomentDao()?.insertMoment(MicroMomentEntity(mediaId = id, tapCount = 1, timestamp = System.currentTimeMillis()))
                if (_tasteDNA.value.isFineTuningEnabled) {
                    var dna = _tasteDNA.value
                    PersonalizationTraitMapper.getEffectiveTraitAdjustments(item).forEach { (dim, mult) -> 
                        dna = dna.updateLearnedDimension(dim, mult * 0.05, 0.20) 
                    }
                    if (dna != _tasteDNA.value) updateTasteDNA(dna, false, "Favorite")
                }
            }
        }
    }

    fun toggleFavorite(id: String) {
        val item = getMediaItemById(id) ?: return
        setFavorite(id, !item.isFavorite)
    }

    fun addToFavorites(id: String) { setFavorite(id, true) }
    fun removeFromFavorites(id: String) { setFavorite(id, false) }

    // --- Saved Media (Bookmarking) API ---

    fun saveMedia(mediaId: String) {
        if (mediaId.isBlank()) return
        scope.launch {
            val savedDao = database?.savedMediaDao() ?: return@launch
            savedDao.saveMedia(SavedMediaEntity(mediaId = mediaId, savedAt = System.currentTimeMillis()))
        }
    }

    fun removeMedia(mediaId: String) {
        if (mediaId.isBlank()) return
        scope.launch {
            val savedDao = database?.savedMediaDao() ?: return@launch
            savedDao.deleteMedia(mediaId)
        }
    }

    private val savedToggleMutex = kotlinx.coroutines.sync.Mutex()

    fun toggleSaved(mediaId: String) {
        if (mediaId.isBlank()) return
        scope.launch {
            savedToggleMutex.withLock {
                val savedDao = database?.savedMediaDao() ?: return@withLock
                val isCurrentlySaved = savedDao.isSaved(mediaId)
                if (isCurrentlySaved) {
                    savedDao.deleteMedia(mediaId)
                } else {
                    savedDao.saveMedia(SavedMediaEntity(mediaId = mediaId, savedAt = System.currentTimeMillis()))
                }
            }
        }
    }

    suspend fun isSaved(mediaId: String): Boolean {
        val savedDao = database?.savedMediaDao() ?: return false
        return savedDao.isSaved(mediaId)
    }

    fun observeIsSaved(mediaId: String): Flow<Boolean> {
        val savedDao = database?.savedMediaDao() ?: return flowOf(false)
        return savedDao.observeIsSaved(mediaId)
    }

    fun observeSavedMedia(): Flow<List<MediaItem>> {
        val savedDao = database?.savedMediaDao() ?: return flowOf(emptyList())
        return savedDao.getAllSaved().map { savedEntities ->
            val dao = database?.mediaDao()
            savedEntities.mapNotNull { entity ->
                val item = _mediaItems.value.find { it.id == entity.mediaId }
                    ?: dao?.getMediaById(entity.mediaId)?.toMediaItem()
                item
            }.filter { isItemVisibleInLibrary(it) }
        }
    }

    suspend fun getSavedCount(): Int {
        val savedDao = database?.savedMediaDao() ?: return 0
        return savedDao.getSavedCount()
    }
    
    fun recordLike(id: String) { 
        scope.launch { database?.microMomentDao()?.insertMoment(MicroMomentEntity(mediaId = id, tapCount = 1, timestamp = System.currentTimeMillis())) }
    }
    
    fun getPairwiseWins(): Map<String, Int> = emptyMap()
    fun getPairwiseLosses(): Map<String, Int> = emptyMap()
    fun getComparisonCounts(): Map<String, Int> = _comparisonCounts.value
    suspend fun getActualLikeCounts(ids: List<String>): Map<String, Int> = database?.microMomentDao()?.getLikeCountsForBatch(ids)?.associate { it.mediaId to it.count } ?: emptyMap()
    fun reportPerformance(perf: OperationPerformance) { _latestPerformance.value = perf }
    
    private val pendingPlaybackErrors = java.util.concurrent.ConcurrentLinkedQueue<Pair<PlaybackErrorLogEntity, ((Long) -> Unit)?>>()

    private fun enqueueOrRecordError(entity: PlaybackErrorLogEntity, onComplete: ((Long) -> Unit)?) {
        val repo = playbackErrorLogRepository
        if (repo != null) {
            scope.launch {
                val id = repo.recordError(entity)
                onComplete?.invoke(id)
            }
        } else {
            pendingPlaybackErrors.add(Pair(entity, onComplete))
        }
    }

    private fun flushPendingPlaybackErrors() {
        val repo = playbackErrorLogRepository ?: return
        while (pendingPlaybackErrors.isNotEmpty()) {
            val (entity, onComplete) = pendingPlaybackErrors.poll() ?: break
            scope.launch {
                val id = repo.recordError(entity)
                onComplete?.invoke(id)
            }
        }
    }

    fun recordRouterFailure(item: MediaItem, route: PlaybackRouteResult, onComplete: ((Long) -> Unit)? = null) {
        val entity = com.example.util.AuraPlaybackDiagnostics.captureRouterFailure(route, item, null)
        enqueueOrRecordError(entity, onComplete)
        if (route is PlaybackRouteResult.Unsupported || route is PlaybackRouteResult.Corrupt) {
            updateItemCompatibilityStatus(item.id, if (route is PlaybackRouteResult.Corrupt) CompatibilityStatus.CORRUPT else CompatibilityStatus.UNSUPPORTED)
        }
    }

    fun recordPlaybackError(error: androidx.media3.common.PlaybackException, player: androidx.media3.common.Player, item: MediaItem?, onComplete: ((Long) -> Unit)? = null) {
        val entity = com.example.util.AuraPlaybackDiagnostics.captureError(error, player, item, null)
        enqueueOrRecordError(entity, onComplete)
        if (item != null) {
            val isFileNotFound = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NO_PERMISSION ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                    error.message?.contains("file not found", ignoreCase = true) == true ||
                    error.message?.contains("READ_POSITION_OUT_OF_RANGE", ignoreCase = true) == true
            val isParsingError = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
            if (isFileNotFound) {
                updateItemCompatibilityStatus(item.id, CompatibilityStatus.UNREADABLE)
            } else if (isParsingError || item.compatibilityStatus == CompatibilityStatus.THUMBNAIL_FAILED) {
                updateItemCompatibilityStatus(item.id, CompatibilityStatus.UNSUPPORTED)
            }
        }
    }

    fun updateItemCompatibilityStatus(itemId: String, status: CompatibilityStatus) {
        scope.launch(dispatcher) {
            updateMediaItemsState { list ->
                list.map { if (it.id == itemId) it.copy(compatibilityStatus = status) else it }
            }
            val entity = database?.mediaDao()?.getMediaById(itemId)
            if (entity != null) {
                database?.mediaDao()?.update(entity.copy(compatibilityStatus = status.name))
            }
        }
    }

    fun updatePlaybackErrorRecovery(id: Long, attempted: Boolean, successful: Boolean?) {
        val repo = playbackErrorLogRepository ?: return
        scope.launch {
            repo.updateRecoveryStatus(id, attempted, successful)
        }
    }

    suspend fun getMediaItemByIdAuthoritative(id: String) = database?.mediaDao()?.getMediaById(id)?.toMediaItem()
    
    private var activeVisualSearchJob: Job? = null

    fun addVisualReference(item: MediaItem) { 
        val newList = (_activeVisualReferences.value + item).distinctBy { it.id }
        _activeVisualReferences.value = newList
        triggerVisualSearchForReferences(newList)
    }

    fun removeVisualReference(id: String) {
        val newList = _activeVisualReferences.value.filterNot { it.id == id }
        _activeVisualReferences.value = newList
        if (newList.isEmpty()) {
            removeVisualAnchor()
        } else {
            triggerVisualSearchForReferences(newList)
        }
    }

    private fun triggerVisualSearchForReferences(items: List<MediaItem>) {
        activeVisualSearchJob?.cancel()
        if (items.isEmpty()) {
            removeVisualAnchor()
            return
        }
        activeVisualSearchJob = scope.launch(dispatcher) {
            searchByMultipleImagesInternal(items)
        }
    }
    
    fun recordAISkipEvent(id: String, type: String, from: Long, to: Long) {
        scope.launch {
            database?.aiSkipDao()?.insertEvent(AISkipEventEntity(mediaId = id, eventType = type, fromPosMs = from, toPosMs = to, timestamp = System.currentTimeMillis()))
        }
    }
    
    fun recordMicroMoment(id: String, taps: Int) { scope.launch { database?.microMomentDao()?.insertMoment(MicroMomentEntity(mediaId = id, tapCount = taps, timestamp = System.currentTimeMillis())) } }
    fun skipComparison() { 
        val currentPair = _pairwiseState.value
        if (currentPair.optionA.id.isNotEmpty() && currentPair.optionB.id.isNotEmpty()) {
            if (_compareSelectionSession.value.isActive) {
                _compareSelectionSession.update {
                    val nextRound = it.roundNumber + 1
                    val isLimit = nextRound > it.maxRounds
                    it.copy(
                        roundNumber = nextRound,
                        skips = it.skips + 1,
                        comparedPairIds = it.comparedPairIds + (currentPair.optionA.id to currentPair.optionB.id),
                        isComplete = it.isComplete || isLimit,
                        completionReason = if (isLimit) "Session round limit reached." else it.completionReason
                    )
                }
            }
            synchronized(pairwiseLock) {
                recentPairs.add(0, currentPair.optionA.id to currentPair.optionB.id)
                if (recentPairs.size > 10) recentPairs.removeAt(10)
                recentItemIds.add(0, currentPair.optionA.id)
                recentItemIds.add(0, currentPair.optionB.id)
                while (recentItemIds.size > 20) { recentItemIds.removeAt(recentItemIds.size - 1) }
            }
        }
        refreshPairwiseCandidatePoolAndSelectNext(true) 
    }
    suspend fun getSkipCounts(ids: List<String>): Map<String, Int> = database?.aiSkipDao()?.getSkipCountsForBatch(ids)?.associate { it.mediaId to it.count } ?: emptyMap()
    suspend fun getContentHashFrequencies(): List<ContentHashFrequency> = database?.mediaDao()?.getContentHashFrequencies() ?: emptyList()

    fun deleteComparisonMedia(id: String) {
        deleteMediaItem(id)
    }

    fun deleteMediaItem(id: String) {
        updateMediaItemsState { it.filterNot { it.id == id } }
        scope.launch { database?.mediaDao()?.deleteById(id); deleteSemanticDataForMedia(id) }
        _activePlaylist.update { p -> if (p == null) null else {
            val list = p.items.filterNot { it.id == id }
            if (list.isEmpty()) { _isPlayerActive.value = false; null } else {
                val idx = if (p.authoritativeMediaId == id) p.currentIndex.coerceAtMost(list.size - 1) else list.indexOfFirst { it.id == p.authoritativeMediaId }.let { if (it != -1) it else p.currentIndex.coerceAtMost(list.size - 1) }
                p.copy(items = list, currentIndex = idx, authoritativeMediaId = list.getOrNull(idx)?.id)
            }
        }}

        if (_compareSelectionSession.value.isActive) {
            _compareSelectionSession.update {
                it.copy(
                    selectedIds = it.selectedIds - id,
                    originalCount = (it.originalCount - 1).coerceAtLeast(0)
                )
            }
        }

        val currentPair = _pairwiseState.value
        val isPairAffected = currentPair.optionA.id == id || currentPair.optionB.id == id

        if (isPairAffected) {
            synchronized(pairwiseLock) {
                if (currentPair.optionA.id.isNotEmpty()) recentItemIds.add(0, currentPair.optionA.id)
                if (currentPair.optionB.id.isNotEmpty()) recentItemIds.add(0, currentPair.optionB.id)
                if (currentPair.optionA.id.isNotEmpty() && currentPair.optionB.id.isNotEmpty()) {
                    recentPairs.add(0, currentPair.optionA.id to currentPair.optionB.id)
                }
                while (recentItemIds.size > 20) { recentItemIds.removeAt(recentItemIds.size - 1) }
                while (recentPairs.size > 10) { recentPairs.removeAt(recentPairs.size - 1) }
            }
            _pairwiseState.value = PairwiseComparison("p_empty", currentPair.roundNumber, 50, emptyMediaItem, emptyMediaItem)
            preFetchedPair = null
            refreshPairwiseCandidatePoolAndSelectNext(forceNextPair = true)
        } else if (_compareSelectionSession.value.isActive) {
            refreshPairwiseCandidatePoolAndSelectNext(forceNextPair = true)
        }
    }

    suspend fun getFilteredAndSortedMedia(filterType: String, sortCategory: SortCategory, standardSort: StandardSortOption, intelligentSort: IntelligentSortOption, 
        sessionSeed: Long = 42L, inputItems: List<MediaItem> = candidateEligibleMediaItems.value, tasteDNA: TasteDNA = _tasteDNA.value, profile: TasteDNA.PreferenceProfile = _preferenceProfile.value,
        policy: DiscoveryPolicy = _discoveryPolicy.value, intent: UserIntent = _userIntent.value, stats: IntelligenceStats = _intelligenceStats.value, creatorProfiles: Map<String, CreatorProfile> = _creatorProfiles.value,
        comparisonCounts: Map<String, Int> = _comparisonCounts.value
    ): List<MediaItem> {
        if (inputItems.isEmpty()) return emptyList()

        if (sortCategory == SortCategory.STANDARD) {
            val filtered = inputItems.filter { matchesFilterType(it, filterType) && isItemVisibleInLibrary(it) }
            return when (standardSort) {
                StandardSortOption.TITLE_ASC -> filtered.sortedBy { it.title }
                StandardSortOption.TITLE_DESC -> filtered.sortedByDescending { it.title }
                StandardSortOption.NEWEST_FIRST -> filtered.sortedByDescending { it.dateAdded }
                StandardSortOption.SHORTEST_DURATION -> filtered.sortedBy { it.durationMs }
                StandardSortOption.LONGEST_DURATION -> filtered.sortedByDescending { it.durationMs }
                StandardSortOption.MOST_PLAYED -> filtered.sortedByDescending { it.viewCount }
                StandardSortOption.LEAST_PLAYED -> filtered.sortedBy { it.viewCount }
                StandardSortOption.SIZE -> filtered.sortedByDescending { it.sizeBytes }
                StandardSortOption.RATING -> filtered.sortedByDescending { it.rating }
                StandardSortOption.RECENTLY_PLAYED -> filtered.sortedByDescending { it.lastViewedTimestamp ?: 0L }
                StandardSortOption.RANDOM -> filtered.shuffled(java.util.Random(sessionSeed))
            }
        }

        val request = IntelligenceRequest(mode = IntelligenceMode.SORT, 
            sortOption = intelligentSort.name,
            filterType = filterType, tasteDNA = tasteDNA, profile = profile, stats = stats, creatorProfiles = creatorProfiles, seed = sessionSeed, 
            policy = policy, intent = intent, comparisonCounts = comparisonCounts, poolOverride = inputItems, limit = 10000)
        
        val response = try {
            intelligenceCore?.processRequest(request)
        } catch (e: Exception) {
            null
        }
        
        if (response?.isSuccess == true) {
            _signatureStyleProfile.value = SignatureStyleProvider.calculateStyleProfile(tasteDNA, inputItems)
            return response.candidates.map { it.item }
        }

        val filtered = inputItems.filter { matchesFilterType(it, filterType) && isItemVisibleInLibrary(it) }
        return filtered
    }

    private fun isUriAccessible(context: Context, uriString: String): Boolean {
        if (uriString.isBlank()) return false
        return try {
            val uri = android.net.Uri.parse(uriString)
            if (uri.scheme == "file" || uriString.startsWith("/")) {
                val path = if (uriString.startsWith("file://")) uri.path ?: uriString.removePrefix("file://") else uriString
                val file = java.io.File(path)
                file.exists() && file.canRead()
            } else if (uri.scheme == "content") {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
            } else {
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun matchesFilterType(item: MediaItem, filterType: String): Boolean = when (filterType.uppercase()) { "PHOTO" -> item.mediaType.uppercase() in listOf("PHOTO", "IMAGE"); "VIDEO" -> item.mediaType.uppercase() in listOf("VIDEO", "MOVIE"); else -> true }

    fun getEffectiveCompatibilityStatus(item: MediaItem): CompatibilityStatus {
        if (item.isDeleted) return CompatibilityStatus.DELETED
        if (item.conversionStatus == ConversionStatus.CONVERTED && !item.convertedUri.isNullOrBlank()) {
            return CompatibilityStatus.PLAYABLE
        }
        return item.compatibilityStatus
    }

    open fun isItemVisibleInLibrary(item: MediaItem): Boolean {
        if (item.isDeleted) return false
        return when (getEffectiveCompatibilityStatus(item)) {
            CompatibilityStatus.PLAYABLE,
            CompatibilityStatus.PLAYABLE_SOFTWARE_DECODE,
            CompatibilityStatus.ANALYSIS_PENDING,
            CompatibilityStatus.ANALYSIS_IN_PROGRESS,
            CompatibilityStatus.THUMBNAIL_FAILED -> true
            else -> false
        }
    }

    internal fun calculateLexicalScore(item: MediaItem, query: String): Float {
        val q = query.trim().lowercase()
        if (q.isBlank()) return 0f

        val title = item.title.lowercase()
        val filename = item.uriPath.substringAfterLast('/').substringAfterLast('\\').lowercase()
        val genre = item.genre.lowercase()
        val tags = item.moodTags.map { it.lowercase() }
        val tokens = q.split(Regex("""\s+""")).filter { it.isNotBlank() }

        var score = 0f

        if (title == q || filename == q) {
            score = maxOf(score, 100f)
        } else if (title.startsWith(q) || filename.startsWith(q)) {
            score = maxOf(score, 90f)
        } else if (title.contains(" $q") || filename.contains(" $q") || title.contains("_$q") || filename.contains("_$q") || title.contains("-$q") || filename.contains("-$q")) {
            score = maxOf(score, 80f)
        } else if (title.contains(q) || filename.contains(q)) {
            score = maxOf(score, 70f)
        } else if (genre == q || tags.any { it == q }) {
            score = maxOf(score, 65f)
        } else if (genre.contains(q) || tags.any { it.contains(q) }) {
            score = maxOf(score, 50f)
        } else if (tokens.size > 1 && tokens.all { t -> title.contains(t) || filename.contains(t) || genre.contains(t) || tags.any { it.contains(t) } }) {
            score = maxOf(score, 60f)
        } else if (tokens.any { t -> title.contains(t) || filename.contains(t) || genre.contains(t) || tags.any { it.contains(t) } }) {
            score = maxOf(score, 30f)
        }

        return score
    }

    private fun performLegacySearch(items: List<MediaItem>, query: String): List<MediaItem> {
        return items.mapNotNull { item ->
            val score = calculateLexicalScore(item, query)
            if (score > 0f) Pair(item, score) else null
        }.sortedByDescending { it.second }.map { it.first }
    }

    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    suspend fun scanLocalMedia(context: Context, isManual: Boolean = false): Boolean = scanLocalMediaInternal(context, isManual)
    
    private suspend fun scanLocalMediaInternal(context: Context, isManual: Boolean = false): Boolean {
        val scanId = System.currentTimeMillis()
        if (!hasStoragePermission(context)) {
            _scanProgress.value = ScanProgressState(
                scanSessionId = scanId,
                isScanning = false,
                isComplete = false,
                isManual = isManual,
                errorCode = ScanError.PERMISSION_DENIED
            )
            presentationMediaStore.markAnalysisComplete()
            return false
        }
        _scanProgress.value = ScanProgressState(scanSessionId = scanId, isScanning = true, isManual = isManual)
        try {
            val res = discoverLocalMedia(context)
            if (res is DiscoveryResult.Complete) {
                database?.mediaDao()?.insertOrIgnorePreservingAnalysis(res.entities)
                _isLibraryReady.value = true
                
                // Complete scan progress immediately so newly discovered media appears instantly in UI
                _scanProgress.value = ScanProgressState(
                    scanSessionId = scanId,
                    isScanning = false,
                    isComplete = true,
                    isManual = isManual,
                    discoveredCount = res.entities.size
                )
                
                // Perform background intelligence/visual enrichment asynchronously (Discover -> Persist -> Display -> Enrich)
                scope.launch(dispatcher) {
                    processPendingMedia(context, scanId, res.scannedVolumes, isManual)
                }
            } else {
                _scanProgress.value = ScanProgressState(
                    scanSessionId = scanId,
                    isScanning = false,
                    isComplete = true,
                    isManual = isManual
                )
            }
            return true
        } catch (e: Exception) {
            _scanProgress.value = ScanProgressState(
                scanSessionId = scanId,
                isScanning = false,
                isComplete = false,
                isManual = isManual,
                errorCode = ScanError.UNKNOWN_ERROR
            )
            if (e is CancellationException) throw e
            return false
        }
    }
    
    private suspend fun discoverLocalMedia(context: Context): DiscoveryResult {
        val entities = mutableListOf<MediaEntity>()
        val ids = mutableSetOf<String>()
        val volumes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.getExternalVolumeNames(context) else setOf("external")
        volumes.forEach { vol ->
            // Query Videos
            context.contentResolver.query(
                MediaStore.Video.Media.getContentUri(vol),
                arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.TITLE,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.DATE_MODIFIED
                ),
                null, null, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Video.Media._ID)
                val titleCol = cursor.getColumnIndex(MediaStore.Video.Media.TITLE)
                val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                val durCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                val addCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
                val modCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(if (idCol >= 0) idCol else 0)
                    val title = (if (titleCol >= 0) cursor.getString(titleCol) else null) ?: "Video"
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    val durationMs = if (durCol >= 0) cursor.getLong(durCol) else 0L
                    val dateAdded = (if (addCol >= 0) cursor.getLong(addCol) else 0L) * 1000
                    val dateModified = (if (modCol >= 0) cursor.getLong(modCol) else 0L) * 1000
                    val uriPath = ContentUris.withAppendedId(MediaStore.Video.Media.getContentUri(vol), id).toString()
                    val minutes = (durationMs / 1000) / 60
                    val seconds = (durationMs / 1000) % 60
                    val durationStr = if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"

                    entities.add(
                        MediaEntity(
                            id = "local_vid_$id",
                            title = title,
                            mediaType = "VIDEO",
                            duration = durationStr,
                            durationMs = durationMs,
                            uriPath = uriPath,
                            imageUrl = uriPath,
                            sizeBytes = size,
                            dateAdded = if (dateAdded > 0) dateAdded else System.currentTimeMillis(),
                            dateModified = dateModified,
                            compatibilityStatus = CompatibilityStatus.PLAYABLE.name
                        )
                    )
                    ids.add("local_vid_$id")
                }
            }
            // Query Images
            context.contentResolver.query(
                MediaStore.Images.Media.getContentUri(vol),
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.TITLE,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.DATE_MODIFIED
                ),
                null, null, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                val titleCol = cursor.getColumnIndex(MediaStore.Images.Media.TITLE)
                val sizeCol = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                val addCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val modCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(if (idCol >= 0) idCol else 0)
                    val title = (if (titleCol >= 0) cursor.getString(titleCol) else null) ?: "Photo"
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    val dateAdded = (if (addCol >= 0) cursor.getLong(addCol) else 0L) * 1000
                    val dateModified = (if (modCol >= 0) cursor.getLong(modCol) else 0L) * 1000
                    val uriPath = ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(vol), id).toString()
                    val isSnapshot = title.contains("Aura_Frame_") || title.contains("Snapshot") || title.contains("Aura Frame")

                    entities.add(
                        MediaEntity(
                            id = "local_img_$id",
                            title = title,
                            mediaType = "PHOTO",
                            genre = if (isSnapshot) "User Screenshots" else "Media",
                            category = if (isSnapshot) "User Media" else "For You",
                            moodTagsJson = if (isSnapshot) "User Media,Snapshot,Photo" else "",
                            uriPath = uriPath,
                            imageUrl = uriPath,
                            sizeBytes = size,
                            dateAdded = if (dateAdded > 0) dateAdded else System.currentTimeMillis(),
                            dateModified = dateModified,
                            compatibilityStatus = CompatibilityStatus.PLAYABLE.name
                        )
                    )
                    ids.add("local_img_$id")
                }
            }
        }
        return DiscoveryResult.Complete(entities, ids, volumes, setOf("VIDEO", "PHOTO"))
    }
    
    internal suspend fun processPendingMedia(context: Context, scanId: Long, volumes: Set<String>, isManual: Boolean = false) { 
        val totalCount = _mediaItems.value.size
        val initialPendingCount = database?.mediaDao()?.getPendingAnalysis()?.size ?: 0
        presentationMediaStore.updateProgress(
            totalScanned = totalCount,
            totalAdmitted = visibleMediaItems.value.size,
            isAnalysisComplete = initialPendingCount == 0
        )

        while (true) {
            val batch = database?.mediaDao()?.getPendingAnalysisBatch(50) ?: emptyList()
            if (batch.isEmpty()) break

            batch.forEach { entity ->
                try {
                    val report = AuraMediaCompatibilityEngine.analyzeMedia(context, entity.uriPath, entity.mediaType) 
                    val updatedEntity = entity.copy(
                        compatibilityStatus = report.status.name,
                        containerFormat = report.containerFormat,
                        videoCodec = report.videoCodec,
                        audioCodec = report.audioCodec,
                        width = report.width,
                        height = report.height,
                        durationMs = if (report.durationMs > 0) report.durationMs else entity.durationMs,
                        sizeBytes = if (report.sizeBytes > 0) report.sizeBytes else entity.sizeBytes,
                        compatibilityReason = report.compatibilityReason,
                        conversionStatus = report.conversionStatus.name,
                        convertedUri = report.convertedUri ?: "",
                        lastCompatibilityCheckTimestamp = System.currentTimeMillis()
                    )
                    updateMediaItemsState { list ->
                        list.map { if (it.id == entity.id) updatedEntity.toMediaItem() else it }
                    }
                    database?.mediaDao()?.update(updatedEntity)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e("MediaRepository", "Error processing pending media item ${entity.id}", e)
                    try {
                        val failedEntity = entity.copy(compatibilityStatus = "CORRUPT", compatibilityReason = e.message ?: "Analysis error")
                        database?.mediaDao()?.update(failedEntity)
                    } catch (_: Exception) {}
                }
                presentationMediaStore.updateProgress(
                    totalScanned = totalCount,
                    totalAdmitted = visibleMediaItems.value.size,
                    isAnalysisComplete = false
                )
            }
            kotlinx.coroutines.yield()
        }

        presentationMediaStore.markAnalysisComplete()
        backfillSemantics()
    }
    internal suspend fun reconcileExistingMedia(context: Context) { 
        database?.mediaDao()?.getAllMediaSync()?.forEach { entity -> 
            if (entity.compatibilityStatus == "ANALYSIS_PENDING" || entity.compatibilityStatus == "THUMBNAIL_FAILED") { 
                val report = AuraMediaCompatibilityEngine.analyzeMedia(context, entity.uriPath, entity.mediaType) 
                val updatedEntity = entity.copy(
                    compatibilityStatus = report.status.name,
                    containerFormat = report.containerFormat,
                    videoCodec = report.videoCodec,
                    audioCodec = report.audioCodec,
                    width = report.width,
                    height = report.height,
                    durationMs = if (report.durationMs > 0) report.durationMs else entity.durationMs,
                    sizeBytes = if (report.sizeBytes > 0) report.sizeBytes else entity.sizeBytes,
                    compatibilityReason = report.compatibilityReason,
                    conversionStatus = report.conversionStatus.name,
                    convertedUri = report.convertedUri ?: "",
                    lastCompatibilityCheckTimestamp = System.currentTimeMillis()
                )
                updateMediaItemsState { list ->
                    list.map { if (it.id == entity.id) updatedEntity.toMediaItem() else it }
                }
                database?.mediaDao()?.update(updatedEntity) 
            } 
        } 
    }
    private suspend fun backfillSemantics() { database?.mediaDao()?.getAllMediaSync()?.filter { it.compatibilityStatus in listOf("PLAYABLE", "PLAYABLE_SOFTWARE_DECODE", "ANALYSIS_PENDING") && !it.isDeleted }?.let { processSemanticsForBatch(it) } }
    private suspend fun processSemanticsForBatch(entities: List<MediaEntity>) { val sIdx = semanticIndexingService ?: return; val vIdx = visualIndexingService; val ctx = applicationContext ?: return; entities.forEach { entity -> try { val item = entity.toMediaItem(); sIdx.indexMediaItem(item); vIdx?.indexVisual(ctx, item) } catch (e: Exception) { if (e is CancellationException) throw e } } }
    private suspend fun deleteSemanticDataForMedia(id: String) { semanticRepresentationRepository?.deleteForMedia(id) }
    
    private fun MediaItem.toEntity(): MediaEntity = MediaEntity(id = id, title = title, mediaType = mediaType, year = year, duration = duration, genre = genre, imageUrl = imageUrl, gradientColorsJson = gradientColors.joinToString(","), rating = rating, isFavorite = isFavorite, progress = progress, progressText = progressText, category = category, aiSummary = aiSummary, moodTagsJson = moodTags.joinToString(","), itemCount = itemCount, eloRating = eloRating, uriPath = uriPath, dateAdded = dateAdded, dateModified = dateModified, sizeBytes = sizeBytes, durationMs = durationMs, width = width, height = height, lastViewedTimestamp = lastViewedTimestamp, playCount = viewCount, exposureCount = exposureCount, lastExposedTimestamp = lastExposedTimestamp, contentHash = contentHash, parentContentId = parentContentId, isDeleted = isDeleted, compatibilityStatus = compatibilityStatus.name, containerFormat = containerFormat, videoCodec = videoCodec, audioCodec = audioCodec, compatibilityReason = compatibilityReason, conversionStatus = conversionStatus.name, convertedUri = convertedUri ?: "", lastCompatibilityCheckTimestamp = lastCompatibilityCheckTimestamp, selectionReason = if (isEphemeralReason(selectionReason)) null else selectionReason, creatorId = creatorId, creatorName = creatorName, sourcePlatform = sourcePlatform, replacedByMediaId = replacedByMediaId)
    
    private fun MediaEntity.toMediaItem(): MediaItem { val isVideo = mediaType.equals("VIDEO", ignoreCase = true) || mediaType.equals("Movie", ignoreCase = true); val gradients = if (isVideo) listOf(0xFF1E1B4BL, 0xFF4338CAL, 0xFF7C3AEDL) else listOf(0xFF311B92L, 0xFF6A1B9AL, 0xFFD946EFL); return MediaItem(id = id, title = title, mediaType = if (isVideo) "VIDEO" else "PHOTO", year = year, duration = duration, genre = genre, imageUrl = imageUrl, gradientColors = if (gradientColorsJson.isBlank()) gradients else gradientColorsJson.split(",").mapNotNull { it.toLongOrNull() }, rating = rating, isFavorite = isFavorite, progress = progress, progressText = progressText, category = category, aiSummary = aiSummary, moodTags = if (moodTagsJson.isEmpty()) emptyList() else moodTagsJson.split(","), uriPath = uriPath, itemCount = itemCount, sizeBytes = sizeBytes, dateAdded = dateAdded, dateModified = dateModified, durationMs = durationMs, width = width, height = height, lastViewedTimestamp = lastViewedTimestamp, viewCount = playCount, exposureCount = exposureCount, lastExposedTimestamp = lastExposedTimestamp, contentHash = contentHash, parentContentId = parentContentId, eloRating = eloRating, isDeleted = isDeleted, compatibilityStatus = try { CompatibilityStatus.valueOf(compatibilityStatus) } catch (e: Exception) { CompatibilityStatus.ANALYSIS_PENDING }, containerFormat = containerFormat, videoCodec = videoCodec, audioCodec = audioCodec, compatibilityReason = compatibilityReason, conversionStatus = try { ConversionStatus.valueOf(conversionStatus) } catch (e: Exception) { ConversionStatus.NONE }, convertedUri = convertedUri.ifBlank { null }, lastCompatibilityCheckTimestamp = lastCompatibilityCheckTimestamp, selectionReason = if (compatibilityStatus == "ANALYSIS_FAILED") "Retry Analysis" else selectionReason, creatorId = creatorId, creatorName = creatorName, sourcePlatform = sourcePlatform, replacedByMediaId = replacedByMediaId) }
    
    private fun isEphemeralReason(reason: String?): Boolean = reason != null && (reason in setOf("Surprise!", "For You", "Hidden Gem", "Best Match", "Personalized", "New Discovery", "Blast from the Past", "Your Favorite") || Regex("""\d+% Match""").matches(reason))
    
    suspend fun convertMediaItem(context: Context, itemId: String, deleteOriginalAfter: Boolean = false, onProgress: ((Int) -> Unit)? = null): ConversionResult { val item = getMediaItemById(itemId) ?: return ConversionResult(false, null, null, "Not found"); return AuraMediaConverter.convertToUniversalFormat(context, item, deleteOriginalAfter, onProgress).also { if (it.isSuccess && it.updatedItem != null) { val updated = it.updatedItem!!; updateMediaItemsState { list -> list.map { if (it.id == itemId) updated else it } }; database?.mediaDao()?.update(updated.toEntity()) } } }
    
    suspend fun getSimilarMedia(item: MediaItem, requestId: String = "NONE"): IntelligenceResponse {
        _isForegroundVisualQueryActive.value = true
        return try {
            val req = IntelligenceRequest(
                mode = IntelligenceMode.SIMILAR,
                referenceItemId = item.id,
                requestId = requestId,
                filterType = "ALL",
                limit = 25
            )
            intelligenceCore?.processRequest(req) ?: IntelligenceResponse(
                requestId = requestId,
                mode = IntelligenceMode.SIMILAR,
                candidates = emptyList(),
                latencyMs = 0,
                isSuccess = false,
                errorMessage = "Intelligence Core not initialized"
            )
        } finally {
            _isForegroundVisualQueryActive.value = false
        }
    }
    
    fun recordSearch(query: String) { if (query.isBlank()) return; scope.launch(Dispatchers.IO) { database?.let { db -> db.searchHistoryDao().deleteSearchByQuery(query); db.searchHistoryDao().insertSearch(SearchHistoryEntity(query = query)) } } }
    fun clearSearchHistory() { scope.launch(Dispatchers.IO) { database?.searchHistoryDao()?.clearSearchHistory() } }
    fun updateDiscoveryPolicy(p: DiscoveryPolicy) { _discoveryPolicy.value = p }
    fun updateConsentState(state: ConsentState) { contributionQueueRepository?.consentManager?.setConsentState(state) }
    fun isDiscoverSnapshotStale(): Boolean = _discoverSnapshot.value?.let { System.currentTimeMillis() - it.generationId > 30 * 60 * 1000L } ?: true
    fun updateDiscoverSnapshot(snapshot: DiscoverSnapshot) { _discoverSnapshot.value = snapshot }
    fun clearSearch() { 
        _activeVisualReferences.value = emptyList()
        _librarySearchRequest.value = SearchRequest.Text("") 
    }
    fun searchByImage(bitmap: android.graphics.Bitmap, uri: String? = null) {
        val scaled = if (bitmap.width > 512 || bitmap.height > 512) android.graphics.Bitmap.createScaledBitmap(bitmap, 512, 512, true) else bitmap
        scope.launch(Dispatchers.Default) {
            _isForegroundVisualQueryActive.value = true
            try {
                if (_aiState.value != AIState.READY) {
                    _aiState.first { it == AIState.READY || it == AIState.ERROR }
                }
                val provider = mobileCLIPProvider
                if (provider != null) {
                    val result = provider.generateEmbedding("query_${UUID.randomUUID()}", SemanticInput.ExplicitBitmap(scaled), "query_image")
                    if (result is EmbeddingResult.Success) {
                        _librarySearchRequest.value = SearchRequest.Visual(result.representation.vector, uri)
                    }
                } else {
                    Log.w("MediaRepository", "searchByImage: mobileCLIPProvider is null after AI initialization")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("MediaRepository", "searchByImage error", e)
            } finally {
                _isForegroundVisualQueryActive.value = false
            }
        }
    }
    fun removeVisualAnchor() { _librarySearchRequest.value = SearchRequest.Text(_librarySearchRequest.value.query ?: "") }
    fun addRejectedMedia(entity: RejectedMediaEntity) { scope.launch { database?.rejectedMediaDao()?.insert(entity) } }
    fun forceFlushExposures() { scope.launch { flushExposures() } }
    fun setLibraryPlaylist(items: List<MediaItem>, initialIndex: Int) { setPlaylist(items, initialIndex, "Library") }

    val favoritesSections: StateFlow<List<IntelligentSection>> = visibleMediaItems.map { items ->
        val favorites = items.filter { it.isFavorite }
        listOf(IntelligentSection("Favorites", "Everything you've liked and rated highly.", favorites))
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun addMediaEntity(entity: MediaEntity) { scope.launch { database?.mediaDao()?.insert(entity) } }
    suspend fun getEngagementMetrics(): EngagementMetrics = getEngagementMetricsInternal()
    private suspend fun getEngagementMetricsInternal(): EngagementMetrics {
        val db = database ?: return EngagementMetrics()
        val allItems = _mediaItems.value
        val voteCount = db.pairwiseDao().getVoteCount()
        val clipDao = db.clipInteractionDao()
        val skipDao = db.aiSkipDao()
        return EngagementMetrics(
            totalPlays = allItems.sumOf { it.viewCount }, favoriteCount = allItems.count { it.isFavorite },
            averageRating = if (allItems.any { it.rating > 0 }) allItems.filter { it.rating > 0 }.map { it.rating }.average().toFloat() else 0f,
            personalizationScore = _intelligenceStats.value.personalizationScore, totalComparisons = voteCount, itemsDiscovered = allItems.size,
            totalClipPreviews = clipDao.getTotalClipPreviews(), totalClipSelections = clipDao.getTotalClipSelections(), totalClipExports = clipDao.getTotalClipExports(),
            topEngagedClips = clipDao.getTopEngagedClips().map { ClipInteractionSummary(it.clipTitle, it.previewCount, it.selectCount, it.exportCount, it.previewCount + it.selectCount*2 + it.exportCount*5) },
            pairwiseDiagnostics = _pairwiseDiagnostics.value,
            aiSkipStats = AISkipStats(skipDao.getTotalSkipForwards(), skipDao.getTotalSkipBacks(), skipDao.getTotalSkipReversals(), skipDao.getTotalWatchedDestinations())
        )
    }

    suspend fun generateClosedLoopReport(baseline: Double = 50.0, target: Double = 50.0): ClosedLoopReport {
        val metrics = getEngagementMetrics()
        val evidenceList = mutableListOf<EvidenceRecord>()
        if (metrics.totalPlays + metrics.totalComparisons > 0) evidenceList.add(EvidenceRecord(UUID.randomUUID().toString(), EvidenceTier.PRODUCTION, metrics.totalPlays + metrics.totalComparisons, metrics.personalizationScore.toDouble(), 0.9, "AuraTelemetry"))
        evidenceList.addAll(_storedEvidence.value)
        return ClosedLoopEngine.evaluate(baseline, metrics.personalizationScore.toDouble(), target, evidenceList)
    }
    
    fun startCompareSelectionSession(selectedIds: Set<String>) {
        if (selectedIds.size < 4) return
        _compareSelectionSession.value = CompareSelectionSession(isActive = true, selectedIds = selectedIds, originalCount = selectedIds.size, roundNumber = 1)
        refreshPairwiseCandidatePoolAndSelectNext(true)
    }
    fun restartCompareSelectionSession() {
        _compareSelectionSession.update { it.copy(roundNumber = 1, wins = 0, losses = 0, skips = 0, comparedPairIds = emptyList(), isComplete = false, completionReason = null) }
        refreshPairwiseCandidatePoolAndSelectNext(true)
    }
    fun exitCompareSelectionSession() { _compareSelectionSession.value = CompareSelectionSession(); refreshPairwiseCandidatePoolAndSelectNext(true) }
    fun setCompareMediaType(filter: CompareMediaTypeFilter) { _compareMediaType.value = filter; refreshPairwiseCandidatePoolAndSelectNext(true) }
    fun setCompareStrategy(strategy: CompareStrategy) { _compareStrategy.value = strategy; refreshPairwiseCandidatePoolAndSelectNext(true) }
    fun setCompareSort(sort: CompareSortOption) { _compareSort.value = sort; refreshPairwiseCandidatePoolAndSelectNext(true) }
    fun recordComparisonVote(chosenId: String) {
        val currentPair = _pairwiseState.value
        Log.i("PAIRWISE_TRACE", "RECORD_COMPARISON_VOTE_START: chosenId=$chosenId optionA=${currentPair.optionA.id} optionB=${currentPair.optionB.id}")
        if (currentPair.optionA.id.isEmpty() || currentPair.optionB.id.isEmpty()) return
        
        val idA = currentPair.optionA.id
        val idB = currentPair.optionB.id

        val db = database
        if (db != null) {
            try {
                runBlocking(dispatcher) {
                    db.pairwiseDao().insertOutcome(PairwiseOutcomeEntity(optionAId = idA, optionBId = idB, chosenId = chosenId, roundNumber = currentPair.roundNumber))
                }
            } catch (e: Exception) {
                Log.e("MediaRepository", "Failed to persist pairwise comparison vote", e)
                return
            }
        }

        if (_compareSelectionSession.value.isActive) {
            _compareSelectionSession.update {
                val nextRound = it.roundNumber + 1
                val isLimit = nextRound > it.maxRounds
                it.copy(
                    roundNumber = nextRound,
                    wins = if (chosenId == currentPair.optionA.id) it.wins + 1 else it.wins, 
                    losses = if (chosenId == currentPair.optionB.id) it.losses + 1 else it.losses,
                    comparedPairIds = it.comparedPairIds + (currentPair.optionA.id to currentPair.optionB.id),
                    isComplete = it.isComplete || isLimit,
                    completionReason = if (isLimit) "Session round limit reached." else it.completionReason
                )
            }
        }
        
        _comparisonCounts.update { current ->
            val cA = (current[idA] ?: 0) + 1
            val cB = (current[idB] ?: 0) + 1
            current + (idA to cA) + (idB to cB)
        }
        
        synchronized(pairwiseLock) {
            recentPairs.add(0, idA to idB)
            if (recentPairs.size > 10) recentPairs.removeAt(10)
            recentItemIds.add(0, idA)
            recentItemIds.add(0, idB)
            while (recentItemIds.size > 20) { recentItemIds.removeAt(recentItemIds.size - 1) }
            
            if (chosenId == idA) {
                pairwiseWins[idA] = (pairwiseWins[idA] ?: 0) + 1
                pairwiseLosses[idB] = (pairwiseLosses[idB] ?: 0) + 1
            } else {
                pairwiseWins[idB] = (pairwiseWins[idB] ?: 0) + 1
                pairwiseLosses[idA] = (pairwiseLosses[idA] ?: 0) + 1
            }
        }
        
        val winner = if (chosenId == idA) currentPair.optionA else currentPair.optionB
        if (_tasteDNA.value.isFineTuningEnabled) {
            val winTraits = PersonalizationTraitMapper.getEffectiveTraitAdjustments(winner)
            var dna = _tasteDNA.value
            winTraits.forEach { (dim, mult) -> dna = dna.updateLearnedDimension(dim, mult * 0.01, 0.20) }
            updateTasteDNA(dna, false, "Vote")
        }
        _intelligenceStats.update { it.copy(personalizationScore = (it.personalizationScore + 1).coerceAtMost(100), totalComparisons = it.totalComparisons + 1) }
        refreshPairwiseCandidatePoolAndSelectNext(true)
    }

    fun updatePlaybackPosition(pos: Long) { lastPlaybackPositionMs = pos }
    fun setResumingFromBackground(r: Boolean) { isResumingFromBackground = r }

    fun resetDatabase(context: Context) {
        scope.launch {
            _databaseState.value = DatabaseState.INITIALIZING
            database?.close(); database = null
            context.getDatabasePath("aura_intelligence.db").let { if (it.exists()) it.renameTo(context.getDatabasePath("aura_quarantine_${System.currentTimeMillis()}.db")) }
            setMediaItemsState(emptyList())
            initDatabase(context)
        }
    }

    fun setAutoScrollSpeed(speed: AutoScrollSpeed) { _autoScrollSpeed.value = speed }
    fun setGridDensity(density: Float) { _gridDensity.value = density }
    fun setMediaItemsForTesting(items: List<MediaItem>) { 
        setMediaItemsState(items) 
        _databaseState.value = DatabaseState.READY
    }
    fun setDatabaseForTesting(db: AuraDatabase) { 
        observersJob?.cancel()
        database = db
        _databaseState.value = DatabaseState.READY
        startDatabaseObservers(db)
    }
    fun setApplicationContextForTesting(context: Context) { applicationContext = context }
    fun setIntelligenceCoreForTesting(core: AuraIntelligenceCore?) { 
        intelligenceCore = core 
        hybridSearchEngine = if (core != null) DefaultHybridSearchEngine(core) else null
    }
    fun setComparisonCountForTesting(counts: Map<String, Int>) { _comparisonCounts.value = counts }
    fun setComparisonCountForTesting(mediaId: String, count: Int) { _comparisonCounts.update { it + (mediaId to count) } }
    suspend fun getLikeCount(mediaId: String): Int = getActualLikeCounts(listOf(mediaId))[mediaId] ?: 0
    fun setPlaybackErrorLogRepositoryForTesting(repo: PlaybackErrorLogRepository?) { 
        playbackErrorLogRepository = repo 
        if (repo != null) {
            flushPendingPlaybackErrors()
        }
    }
    fun resetTestingState() { 
        setMediaItemsState(emptyList())
        _databaseState.value = DatabaseState.NOT_INITIALIZED
        pendingPlaybackErrors.clear()
        initJob?.cancel()
        observersJob?.cancel()
    }

    fun recordCompareSelectionVote(chosenId: String) = recordComparisonVote(chosenId)
    fun skipCompareSelectionPair() = skipComparison()

    fun searchByMultipleImages(items: List<MediaItem>) {
        if (items.isEmpty()) {
            clearSearch()
            return
        }
        val references = items.distinctBy { it.id }
        _activeVisualReferences.value = references
        triggerVisualSearchForReferences(references)
    }

    private suspend fun searchByMultipleImagesInternal(items: List<MediaItem>) {
        if (items.isEmpty()) {
            clearSearch()
            return
        }
        val ctx = applicationContext ?: return
        _isForegroundVisualQueryActive.value = true
        try {
            if (_aiState.value != AIState.READY) {
                _aiState.first { it == AIState.READY || it == AIState.ERROR }
            }
            val vectors = mutableListOf<FloatArray>()
            val uris = mutableListOf<String>()
            items.forEach { item ->
                val uri = item.uriPath.ifEmpty { item.imageUrl }
                if (uri.isNotEmpty()) {
                    uris.add(uri)
                }
                val repo = semanticRepresentationRepository
                val provider = mobileCLIPProvider
                val existing = repo?.getForMedia(item.id)?.firstOrNull { it.type == SemanticRepresentationType.VISUAL }
                if (existing != null) {
                    vectors.add(existing.vector)
                } else if (uri.isNotEmpty()) {
                    val bitmap = com.example.util.MediaThumbnailFetcher.getThumbnail(ctx, uri)
                    if (bitmap != null) {
                        val scaled = if (bitmap.width > 512 || bitmap.height > 512) android.graphics.Bitmap.createScaledBitmap(bitmap, 512, 512, true) else bitmap
                        if (provider != null && provider.isReady()) {
                            val result = provider.generateEmbedding(item.id, SemanticInput.ExplicitBitmap(scaled), "query_image")
                            if (result is EmbeddingResult.Success) {
                                vectors.add(result.representation.vector)
                            }
                        }
                        if (scaled !== bitmap) scaled.recycle()
                    }
                }
            }
            if (vectors.isNotEmpty()) {
                if (vectors.size == 1) {
                    _librarySearchRequest.value = SearchRequest.Visual(vectors[0], uris.firstOrNull())
                } else {
                    _librarySearchRequest.value = SearchRequest.MultiVisual(vectors, uris)
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("MediaRepository", "Error in searchByMultipleImagesInternal", e)
        } finally {
            _isForegroundVisualQueryActive.value = false
        }
    }
    private fun extractVolumeFromUri(uriString: String): String? {
        return try {
            val uri = android.net.Uri.parse(uriString)
            if (uri.authority == "media") {
                uri.pathSegments.firstOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun reconcileDeletions(discoveryResult: DiscoveryResult): Boolean {
        val db = database ?: return false
        
        if (discoveryResult !is DiscoveryResult.Complete) {
            Log.w("AURA_SCAN_RUNTIME", "[REPO] reconcileDeletions aborted: Discovery was not complete/authoritative.")
            return false
        }
        
        val result = discoveryResult
        val allDiscoveredIds = result.discoveredIds
        val scannedVolumes = result.scannedVolumes
        val scannedMediaTypes = result.scannedMediaTypes

        val currentItems = db.mediaDao().getAllMediaSync()
        val toDelete = mutableListOf<String>()
        var skippedCount = 0
        
        currentItems.forEach { item ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            
            if (item.id.startsWith("local_")) {
                val itemVolume = extractVolumeFromUri(item.uriPath)
                val itemType = item.mediaType // "PHOTO" or "VIDEO"
                
                val isVolumeVerified = itemVolume != null && (scannedVolumes.contains(itemVolume) || scannedVolumes.contains("external") || scannedVolumes.contains("external_primary"))
                val isTypeVerified = scannedMediaTypes.contains(itemType)

                if (isVolumeVerified && isTypeVerified) {
                    if (item.id !in allDiscoveredIds) {
                        toDelete.add(item.id)
                    }
                } else {
                    skippedCount++
                }
            }
        }
        
        if (toDelete.isNotEmpty()) {
            db.mediaDao().deleteByIds(toDelete)
            toDelete.forEach { deleteSemanticDataForMedia(it) }
            updateMediaItemsState { current -> current.filterNot { it.id in toDelete } }
            Log.d("AURA_SCAN_RUNTIME", "[REPO] reconcileDeletions complete. Purged ${toDelete.size} items via batch delete.")
        }
        
        Log.d("AURA_SCAN_RUNTIME", "[REPO] reconcileDeletions summary: Purged ${toDelete.size} items. Preserved $skippedCount items outside verified scope. Total DB items: ${db.mediaDao().getCount()}")
        return true
    }
    fun cancelScan() { /* Logic */ }

    fun recordCleanupSignal(mediaId: String, category: String, score: Float, isDelete: Boolean, reason: String? = null) { /* Logic */ }
    fun reportDiscoverProvenance(prov: Map<String, DecisionProvenance>) { _latestDiscoverProvenance.value = prov }
    fun reportLibraryProvenance(prov: Map<String, DecisionProvenance>) { _latestLibraryProvenance.value = prov }
    fun enqueueVisualLikeContext(mediaId: String, uri: String, playbackPositionMs: Long, durationMs: Long) { /* Logic */ }

    fun logClipInteraction(summary: ClipInteractionEntity) {
        scope.launch { database?.clipInteractionDao()?.insertOrUpdate(summary) }
    }

    fun forceTimeoutState() { _databaseState.value = DatabaseState.TIMEOUT }

    fun close() {
        initJob?.cancel()
        observersJob?.cancel()
        scope.cancel()
    }

    private inner class ProductionLexicalRetriever : LexicalCandidateRetriever { 
        override suspend fun retrieveKeywordCandidates(query: String, topK: Int): List<RankedChannelItem> { 
            val scored = visibleMediaItems.value.mapNotNull { item ->
                val score = calculateLexicalScore(item, query)
                if (score > 0f) Pair(item, score) else null
            }.sortedByDescending { it.second }

            return scored.take(topK).mapIndexed { idx, (item, score) ->
                RankedChannelItem(
                    mediaId = item.id,
                    rawScore = score,
                    rank = idx + 1,
                    metadata = mapOf("is_authoritative" to (score >= 70f).toString())
                )
            }
        } 
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeVisualIndexReadiness(): Flow<VisualIndexReadiness> {
        return _databaseState.flatMapLatest { state ->
            val db = database
            if (state != DatabaseState.READY || db == null) {
                kotlinx.coroutines.flow.flowOf(
                    VisualIndexReadiness(
                        state = VisualIndexState.YELLOW_INDEXING,
                        totalEligible = 0,
                        verifiedVisualCount = 0,
                        pendingCount = 0,
                        processingCount = 0,
                        failedCount = 0,
                        label = "Initializing Database...",
                        isModelReady = false
                    )
                )
            } else {
                db.semanticRepresentationDao().observeVerifiedVisualCount()
                    .combine(visibleMediaItems) { verifiedCount, visibleItems ->
                        val metrics = try { db.mediaDao().getEnrichmentMetrics() } catch (_: Exception) { null }
                        val totalEligible = metrics?.totalEligible ?: visibleItems.size
                        val terminalFailedCount = metrics?.terminalFailedCount ?: 0
                        val pendingCount = maxOf(0, totalEligible - verifiedCount - terminalFailedCount)
                        val failedCount = metrics?.failedCount ?: 0
                        val isProviderReady = mobileCLIPProvider?.isReady() == true

                        val isCompleteWithFailures = (verifiedCount + terminalFailedCount >= totalEligible) && totalEligible > 0

                        val currentAiState = _aiState.value
                        val st = when {
                            totalEligible == 0 -> VisualIndexState.NEUTRAL_EMPTY
                            !isProviderReady && (currentAiState == AIState.BOOTSTRAP || currentAiState == AIState.NOT_INITIALIZED) -> VisualIndexState.YELLOW_INDEXING
                            !isProviderReady -> VisualIndexState.RED_INCOMPLETE
                            terminalFailedCount > 0 && terminalFailedCount >= failedCount -> VisualIndexState.RED_INCOMPLETE
                            isCompleteWithFailures || (verifiedCount >= totalEligible && totalEligible > 0) -> VisualIndexState.GREEN_READY
                            else -> VisualIndexState.YELLOW_INDEXING
                        }

                        if (failedCount > 0 && terminalFailedCount < failedCount && isProviderReady) {
                            scope.launch(Dispatchers.IO) {
                                kotlinx.coroutines.delay(1500L)
                                db.mediaDao().recoverStuckEnrichment()
                                applicationContext?.let { ctx -> scheduleEnrichmentWhenReady(ctx) }
                            }
                        }

                        val lbl = when (st) {
                            VisualIndexState.GREEN_READY -> {
                                if (terminalFailedCount > 0) {
                                    "Visual Index Ready — $verifiedCount indexed, $terminalFailedCount skipped"
                                } else {
                                    "Visual Index Ready (100%)"
                                }
                            }
                            VisualIndexState.YELLOW_INDEXING -> {
                                if (!isProviderReady) {
                                    "Visual Index Engine Loading..."
                                } else {
                                    val pct = if (totalEligible > 0) (verifiedCount * 100) / totalEligible else 0
                                    "Indexing Visuals: $verifiedCount / $totalEligible ($pct%)"
                                }
                            }
                            VisualIndexState.RED_INCOMPLETE -> {
                                if (!isProviderReady) "Visual Index Engine Loading..."
                                else "Visual Index Incomplete ($failedCount Failed) — Tap to Retry"
                            }
                            VisualIndexState.NEUTRAL_EMPTY -> "No Eligible Media"
                        }

                        VisualIndexReadiness(
                            state = st,
                            totalEligible = totalEligible,
                            verifiedVisualCount = verifiedCount,
                            pendingCount = pendingCount,
                            processingCount = 0,
                            failedCount = failedCount,
                            label = lbl,
                            isModelReady = isProviderReady
                        )
                    }
            }
        }
    }

    fun refreshLibrary(context: Context) {
        scope.launch(Dispatchers.IO) {
            try {
                database?.mediaDao()?.recoverStuckEnrichment()
                scanLocalMedia(context, isManual = true)
                reconcileExistingMedia(context)
                scheduleEnrichmentWhenReady(context)
            } catch (e: Exception) {
                Log.e("MediaRepository", "Error during library refresh", e)
            }
        }
    }

    private suspend fun scheduleEnrichmentWhenReady(context: Context) {
        if (_aiState.value != AIState.READY) {
            try {
                _aiState.first { it == AIState.READY || it == AIState.ERROR }
            } catch (_: Exception) {}
        }
        if (_aiState.value == AIState.READY) {
            backfillSemantics()
            AuraEnrichmentWorker.schedule(context)
        }
    }

    private var aiHealthJob: Job? = null
    private var lastAiRecoveryTimestamp = 0L

    private fun startAiHealthObserver(context: Context) {
        aiHealthJob?.cancel()
        aiHealthJob = scope.launch(Dispatchers.IO) {
            var bootstrapStartTime = System.currentTimeMillis()
            while (isActive) {
                delay(5000L)
                val currentState = _aiState.value
                val isProviderReady = mobileCLIPProvider?.isReady() == true
                val now = System.currentTimeMillis()
                
                val isStuckBootstrap = currentState == AIState.BOOTSTRAP && !isProviderReady && (now - bootstrapStartTime > 30000L)
                val isFailedError = currentState == AIState.ERROR
                
                if ((isFailedError || isStuckBootstrap) && (now - lastAiRecoveryTimestamp > 60000L)) {
                    lastAiRecoveryTimestamp = now
                    bootstrapStartTime = now
                    Log.w("MediaRepository", "Auto-recovering AI engine after ERROR / not-ready (state=$currentState, providerReady=$isProviderReady)")
                    try {
                        initAI(context)
                        if (_aiState.value == AIState.READY) {
                            scheduleEnrichmentWhenReady(context)
                        }
                    } catch (e: Exception) {
                        Log.e("MediaRepository", "Auto-recovery initAI failed", e)
                        _aiState.value = AIState.ERROR
                    }
                }
            }
        }
    }

    private var stuckRecoveryJob: Job? = null

    private fun startStuckEnrichmentRecovery(context: Context) {
        stuckRecoveryJob?.cancel()
        stuckRecoveryJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(120000L) // Every 2 minutes
                val db = database ?: continue
                val metrics = try { db.mediaDao().getEnrichmentMetrics() } catch (_: Exception) { null }
                val totalEligible = metrics?.totalEligible ?: 0
                val verifiedVisualCount = metrics?.visualCount ?: 0
                val failedCount = metrics?.failedCount ?: 0
                val terminalFailedCount = metrics?.terminalFailedCount ?: 0

                val isComplete = totalEligible > 0 && (verifiedVisualCount + terminalFailedCount >= totalEligible)
                if (!isComplete || failedCount > terminalFailedCount) {
                    Log.d("MediaRepository", "Background auto-recovering stuck enrichment: verified=$verifiedVisualCount / total=$totalEligible, failed=$failedCount")
                    try {
                        db.mediaDao().recoverStuckEnrichment()
                        if (_aiState.value == AIState.READY) {
                            scheduleEnrichmentWhenReady(context)
                        }
                    } catch (e: Exception) {
                        Log.w("MediaRepository", "Background stuck recovery iteration failed", e)
                    }
                }
            }
        }
    }

    fun retryFailedEnrichment() {
        val db = database ?: return
        scope.launch(Dispatchers.IO) {
            try {
                // If the AI engine failed during startup (or was never ready), re-bootstrap
                // it before attempting enrichment. Without this, every worker batch fails
                // immediately and the UI stays stuck on "Tap to Retry".
                if (_aiState.value == AIState.ERROR || mobileCLIPProvider?.isReady() != true) {
                    applicationContext?.let { ctx ->
                        Log.i("MediaRepository", "retryFailedEnrichment: re-bootstrapping AI engine")
                        initAI(ctx)
                    }
                }
                db.mediaDao().recoverStuckEnrichment()
                applicationContext?.let { ctx ->
                    reconcileExistingMedia(ctx)
                    backfillSemantics()
                    AuraEnrichmentWorker.schedule(ctx)
                }
            } catch (e: Exception) {
                Log.e("MediaRepository", "Error retrying failed enrichment", e)
            }
        }
    }
}

enum class VisualIndexState {
    GREEN_READY,
    YELLOW_INDEXING,
    RED_INCOMPLETE,
    NEUTRAL_EMPTY
}

data class VisualIndexReadiness(
    val state: VisualIndexState,
    val totalEligible: Int,
    val verifiedVisualCount: Int,
    val pendingCount: Int,
    val processingCount: Int,
    val failedCount: Int,
    val label: String,
    val isModelReady: Boolean
)
