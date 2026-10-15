package com.example.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.DiscoverSessionManager
import com.example.data.MediaRepository
import com.example.data.MediaItem
import com.example.data.ObsessionRecommendation
import com.example.data.SystemDiscoveryState
import com.example.data.TasteDNA
import com.example.data.DiscoveryPolicy
import com.example.data.IntelligenceStats
import com.example.data.CreatorProfile
import com.example.data.ObsessionContentBatch
import com.example.data.DiscoverSnapshot
import com.example.data.TasteReveal
import com.example.data.TasteRevealGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class DiscoverFeedState {
    object Loading : DiscoverFeedState()
    data class Success(
        val snapshot: DiscoverSnapshot,
        val tasteReveal: TasteReveal? = null
    ) : DiscoverFeedState()
    data class Error(val message: String) : DiscoverFeedState()
}

sealed class ObsessionDetailState {
    object Idle : ObsessionDetailState()
    data class Active(
        val obsession: ObsessionRecommendation,
        val batch: ObsessionContentBatch,
        val isLoading: Boolean = false
    ) : ObsessionDetailState()
}

class DiscoverViewModel(
    private val repository: MediaRepository
) : ViewModel() {

    private val sessionManager = DiscoverSessionManager()
    private var refreshJob: kotlinx.coroutines.Job? = null
    private var isRefreshing = false
    
    private val _feedState = MutableStateFlow<DiscoverFeedState>(DiscoverFeedState.Loading)
    val feedState: StateFlow<DiscoverFeedState> = _feedState.asStateFlow()

    private val _detailState = MutableStateFlow<ObsessionDetailState>(ObsessionDetailState.Idle)
    val detailState: StateFlow<ObsessionDetailState> = _detailState.asStateFlow()

    private var hasSeenTasteReveal = false

    private suspend fun getGatedItems(): List<MediaItem> = try {
        repository.candidateEligibleMediaItems.first()
    } catch (_: Exception) {
        try { repository.mediaItems.first() } catch (_: Exception) { emptyList() }
    }

    init {
        // Initial load: Sync with repository snapshot or trigger fresh generation
        viewModelScope.launch {
            repository.discoverSnapshot.collect { existing ->
                val currentState = _feedState.value
                if (existing != null && !repository.isDiscoverSnapshotStale()) {
                    // Update state to Success if we have a valid snapshot
                    _feedState.value = DiscoverFeedState.Success(existing)
                } else if (existing == null && currentState is DiscoverFeedState.Loading) {
                    // If we have no data and are stuck in the initial loading state, trigger refresh
                    refresh(forceNewSession = true)
                }
            }
        }

        // Phase 10/12: Observe visibleMediaItems to resolve "Awaiting media" race condition
        viewModelScope.launch {
            val flow = try { repository.candidateEligibleMediaItems } catch (_: Exception) { null } ?: repository.mediaItems
            flow.collect { items ->
                val currentState = _feedState.value
                if (items.isNotEmpty() && currentState is DiscoverFeedState.Success && currentState.snapshot.obsessions.isEmpty()) {
                    android.util.Log.d("DiscoverViewModel", "Media items became available. Auto-refreshing Discover.")
                    refresh(forceNewSession = false)
                }
                
                // If we're in an active obsession detail, check if items were deleted
                val currentDetail = _detailState.value
                if (currentDetail is ObsessionDetailState.Active) {
                    val remainingInBatch = currentDetail.batch.items.filter { batchItem ->
                        items.any { it.id == batchItem.id }
                    }
                    if (remainingInBatch.size != currentDetail.batch.items.size) {
                        _detailState.value = currentDetail.copy(
                            batch = currentDetail.batch.copy(items = remainingInBatch)
                        )
                    }
                }
            }
        }

        // Phase 12: Observe intelligenceStats to refresh when personalization scores change (e.g. after Pairwise)
        viewModelScope.launch {
            var lastComparisons = repository.intelligenceStats.value.totalComparisons
            repository.intelligenceStats.collect { stats ->
                val currentState = _feedState.value
                if (currentState is DiscoverFeedState.Success && currentState.snapshot.obsessions.isNotEmpty()) {
                    if (stats.totalComparisons != lastComparisons) {
                        lastComparisons = stats.totalComparisons
                        android.util.Log.d("DiscoverViewModel", "Intelligence stats updated. Refreshing Discover scores.")
                        refresh(forceNewSession = false)
                    }
                } else {
                    lastComparisons = stats.totalComparisons
                }
            }
        }

        // Phase 12: Refresh boundaries - Observe policy/intent changes
        viewModelScope.launch {
            combine(repository.discoveryPolicy, repository.userIntent) { p, i -> p to i }
                .collect {
                    // If policy or intent changes while we have a success state, force a refresh
                    if (_feedState.value is DiscoverFeedState.Success && !isRefreshing) {
                        refresh(forceNewSession = false)
                    }
                }
        }
    }

    fun markTasteRevealSeen() {
        val current = _feedState.value
        if (current is DiscoverFeedState.Success) {
            hasSeenTasteReveal = true
            _feedState.value = current.copy(tasteReveal = null)
        }
    }

    fun triggerManualTasteReveal() {
        viewModelScope.launch {
            val items = getGatedItems()
            val dna = repository.tasteDNA.first()
            val profile = repository.preferenceProfile.first()
            val stats = repository.intelligenceStats.first()
            
            val reveal = TasteRevealGenerator.generate(dna, stats, profile)
            val current = _feedState.value
            if (current is DiscoverFeedState.Success && reveal != null) {
                _feedState.value = current.copy(tasteReveal = reveal)
            }
        }
    }

    fun refresh(forceNewSession: Boolean = false) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            // If already loading and not forced, skip
            if (_feedState.value is DiscoverFeedState.Loading && !forceNewSession) return@launch
            
            isRefreshing = true
            // Set Loading state if forced or if we don't have a snapshot yet
            if (forceNewSession || repository.discoverSnapshot.value == null) {
                _feedState.value = DiscoverFeedState.Loading
            }

            try {
                val items = getGatedItems()
                val dna = repository.tasteDNA.first()
                val profile = repository.preferenceProfile.first()
                val stats = repository.intelligenceStats.first()
                val creators = repository.creatorProfiles.first()

                if (items.isEmpty()) {
                    val emptySnap = DiscoverSnapshot(
                        obsessions = emptyList(),
                        systemState = com.example.data.SystemDiscoveryState(),
                        seenIds = emptySet()
                    )
                    repository.updateDiscoverSnapshot(emptySnap)
                    _feedState.value = DiscoverFeedState.Success(emptySnap)
                } else {
                    val snapshot = sessionManager.generateSnapshot(
                        repository, dna, profile, stats, creators, forceNewSession
                    )
                    repository.updateDiscoverSnapshot(snapshot)
                    
                    // Pull-to-refresh and feed updates refresh content directly in place without interrupting
                    // the user with a Taste Reveal overlay.
                    _feedState.value = DiscoverFeedState.Success(snapshot, null)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _feedState.value = DiscoverFeedState.Error(e.message ?: "Failed to generate recommendations")
            } finally {
                isRefreshing = false
            }
        }
    }

    fun selectObsession(obsession: ObsessionRecommendation) {
        viewModelScope.launch {
            repository.interactionRepository?.let { iRepo ->
                com.example.data.AuraInteractionService.logInteraction(
                    repository,
                    iRepo,
                    com.example.data.AuraInteractionType.OBSESSION_OPENED,
                    metadata = mapOf("obsessionId" to obsession.id)
                )
            }
            _detailState.value = ObsessionDetailState.Active(
                obsession,
                ObsessionContentBatch(obsession.id, emptyList(), false),
                true
            )
            
            val items = getGatedItems()
            val dna = repository.tasteDNA.first()
            val profile = repository.preferenceProfile.first()
            val policy = repository.discoveryPolicy.first()
            val stats = repository.intelligenceStats.first()
            val creators = repository.creatorProfiles.first()
            
            try {
                val batch = sessionManager.realizeBatch(
                    repository, obsession, items, dna, profile, policy, stats, creators
                )
                repository.reportDiscoverProvenance(batch.provenanceMap)
                _detailState.value = ObsessionDetailState.Active(obsession, batch, false)
            } catch (e: Exception) {
                _detailState.value = ObsessionDetailState.Idle
            }
        }
    }

    fun deselectObsession() {
        repository.interactionRepository?.let { iRepo ->
            com.example.data.AuraInteractionService.logInteraction(
                repository,
                iRepo,
                com.example.data.AuraInteractionType.OBSESSION_ABANDONED
            )
        }
        _detailState.value = ObsessionDetailState.Idle
    }

    fun expandCurrentObsession() {
        val currentState = _detailState.value as? ObsessionDetailState.Active ?: return
        if (!currentState.batch.canExpand || currentState.isLoading) return

        viewModelScope.launch {
            repository.interactionRepository?.let { iRepo ->
                com.example.data.AuraInteractionService.logInteraction(
                    repository,
                    iRepo,
                    com.example.data.AuraInteractionType.BATCH_EXPANDED,
                    metadata = mapOf("obsessionId" to currentState.obsession.id)
                )
            }
            _detailState.value = currentState.copy(isLoading = true)

            val items = getGatedItems()
            val dna = repository.tasteDNA.first()
            val profile = repository.preferenceProfile.first()
            val policy = repository.discoveryPolicy.first()
            val stats = repository.intelligenceStats.first()
            val creators = repository.creatorProfiles.first()

            try {
                val expandedBatch = sessionManager.realizeBatch(
                    repository, currentState.obsession, items, dna, profile, policy, stats, creators,
                    existingItems = currentState.batch.items
                )
                repository.reportDiscoverProvenance(expandedBatch.provenanceMap)
                _detailState.value = currentState.copy(batch = expandedBatch, isLoading = false)
            } catch (e: Exception) {
                _detailState.value = currentState.copy(isLoading = false)
            }
        }
    }

    fun trySomethingNew() {
        repository.interactionRepository?.let { iRepo ->
            com.example.data.AuraInteractionService.logInteraction(
                repository,
                iRepo,
                com.example.data.AuraInteractionType.TRY_SOMETHING_NEW
            )
        }
        val currentFeed = _feedState.value as? DiscoverFeedState.Success ?: return
        val currentObsession = (_detailState.value as? ObsessionDetailState.Active)?.obsession
        
        // Find another obsession that isn't the current one
        val next = currentFeed.snapshot.obsessions.filter { it.id != currentObsession?.id }.randomOrNull()
        if (next != null) {
            selectObsession(next)
        } else {
            deselectObsession()
        }
    }
}

