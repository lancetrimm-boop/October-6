package com.example.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.mediamodel.toMediaItem
import com.example.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

data class ChannelPreviewState(
    val channel: Channel,
    val candidateItem: MediaItem?,
    val fullItems: List<MediaItem>
)

class ChannelViewModel(
    private val repository: MediaRepository
) : ViewModel() {

    private val programmer = ChannelProgrammer()
    private val sessionManager = ChannelSessionManager(programmer)
    val channelState: StateFlow<ChannelState> = sessionManager.channelState

    val defaultChannels: List<Channel> get() = ChannelRegistry.allFixedChannels()

    val allChannels: List<Channel> get() = ChannelRegistry.allChannels()

    private val _selectedChannel = MutableStateFlow<Channel>(ChannelRegistry.defaultChannel())
    val selectedChannel: StateFlow<Channel> = _selectedChannel.asStateFlow()

    private val _selectedFilterType = MutableStateFlow<String>("VIDEOS")
    val selectedFilterType: StateFlow<String> = _selectedFilterType.asStateFlow()

    private val _channelPreviews = MutableStateFlow<List<ChannelPreviewState>>(emptyList())
    val channelPreviews: StateFlow<List<ChannelPreviewState>> = _channelPreviews.asStateFlow()

    private val _isRefreshing = MutableStateFlow<Boolean>(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _isLoading = MutableStateFlow<Boolean>(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var refreshEpoch = 0
    private val currentSessionExposures: Map<String, Int>
        get() = repository.exposureRepository?.getSessionExposuresSnapshot() ?: emptyMap()

    val slideshowDelaySeconds: StateFlow<Int> = repository.slideshowDelaySeconds

    private val availableMedia: List<MediaItem>
        get() {
            val eligible = repository.presentationMediaStore.eligibleMedia.value
            val items = if (eligible.isNotEmpty()) {
                eligible.map { it.toMediaItem() }
            } else {
                val candidateList = repository.candidateEligibleMediaItems.value
                if (candidateList.isNotEmpty()) {
                    candidateList
                } else {
                    repository.visibleMediaItems.value.ifEmpty { repository.mediaItems.value }
                }
            }
            return items.filter { !it.isDeleted && repository.isItemEligibleForCandidates(it) }
        }

    init {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                repository.presentationMediaStore.eligibleMedia,
                _selectedFilterType
            ) { eligible, _ ->
                eligible
            }.collect { eligible ->
                if (eligible.isNotEmpty() && _channelPreviews.value.isEmpty()) {
                    loadChannelPreviews()
                }
            }
        }
        viewModelScope.launch {
            _selectedFilterType.collect {
                loadChannelPreviews()
            }
        }
        loadChannelPreviews()
        selectChannel(ChannelRegistry.defaultChannel())
    }

    fun loadChannelPreviews() {
        viewModelScope.launch(repository.dispatcher) {
            _isLoading.value = true
            try {
                val available = availableMedia
                val filter = _selectedFilterType.value
                val context = ChannelProgrammingContext(
                    availableMedia = available,
                    filterType = filter,
                    tasteDNA = repository.tasteDNA.value,
                    exposureMap = available.associate { it.id to it.exposureCount },
                    refreshEpoch = refreshEpoch,
                    sessionExposures = currentSessionExposures,
                    decayedExposureMap = available.associate { it.id to it.getDecayedExposure() }
                )

                val usedPreviewIds = mutableSetOf<String>()

                val states = allChannels.map { channel ->
                    val programmed = programmer.programChannel(channel, context, limit = 20)

                    val candidate = if (channel.channelKind == ChannelKind.SEARCH_SEEDED && channel.referenceMediaIds.isNotEmpty()) {
                        val firstRefId = channel.referenceMediaIds.first()
                        programmed.find { it.id == firstRefId }
                            ?: available.find { it.id == firstRefId }
                            ?: programmed.firstOrNull { it.id !in usedPreviewIds }
                    } else {
                        programmed.firstOrNull { it.id !in usedPreviewIds }
                    }
                    if (candidate != null) {
                        usedPreviewIds.add(candidate.id)
                    }

                    val adjustedFullItems = if (candidate != null) {
                        listOf(candidate) + programmed.filter { it.id != candidate.id }
                    } else programmed

                    ChannelPreviewState(
                        channel = channel,
                        candidateItem = candidate,
                        fullItems = adjustedFullItems
                    )
                }
                _channelPreviews.value = states
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun selectChannel(channel: Channel) {
        _selectedChannel.value = channel
        viewModelScope.launch {
            sessionManager.selectChannel(
                channel = channel,
                repository = repository,
                filterType = _selectedFilterType.value,
                refreshEpoch = refreshEpoch,
                sessionExposures = currentSessionExposures
            )
        }
    }

    fun deleteChannel(channel: Channel) {
        if (channel.channelKind != ChannelKind.SEARCH_SEEDED) return
        viewModelScope.launch {
            repository.deleteSearchSeededChannel(channel.id)
            loadChannelPreviews()
            if (_selectedChannel.value.id == channel.id) {
                selectChannel(ChannelRegistry.defaultChannel())
            }
        }
    }

    fun deleteChannel(channelId: String) {
        val ch = ChannelRegistry.get(channelId) ?: return
        deleteChannel(ch)
    }

    fun reloadChannel(
        channel: Channel,
        filterType: String = _selectedFilterType.value,
        onComplete: (List<MediaItem>) -> Unit
    ) {
        viewModelScope.launch {
            val available = availableMedia
            val db = repository.getDatabase()
            val feedback = db?.programmingFeedbackDao()?.getAllFeedback() ?: emptyList()
            val skipEvents = db?.aiSkipDao()?.observeAllEvents()?.firstOrNull() ?: emptyList()

            val context = ChannelProgrammingContext(
                availableMedia = available,
                filterType = filterType,
                tasteDNA = repository.tasteDNA.value,
                programmingFeedback = feedback,
                exposureMap = available.associate { it.id to it.exposureCount },
                skipEvents = skipEvents,
                refreshEpoch = refreshEpoch,
                sessionExposures = currentSessionExposures,
                decayedExposureMap = available.associate { it.id to it.getDecayedExposure() }
            )
            val items = programmer.programChannel(
                channel = channel,
                context = context,
                limit = 20
            )
            onComplete(items)
        }
    }

    fun setFilterType(filterType: String) {
        if (_selectedFilterType.value == filterType) return
        _selectedFilterType.value = filterType
        loadChannelPreviews()
        selectChannel(_selectedChannel.value)
    }

    fun setSlideshowDelaySec(sec: Int) {
        repository.slideshowDelaySec = sec
    }

    fun refreshActiveChannel() {
        val current = _selectedChannel.value
        selectChannel(current)
        loadChannelPreviews()
    }

    fun refreshChannels() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                // Record candidate IDs actually presented as active previews in ExposureRepository
                _channelPreviews.value.forEach { previewState ->
                    previewState.candidateItem?.let { item ->
                        repository.exposureRepository?.recordEvent(
                            mediaId = item.id,
                            eventType = com.example.data.ExposureEventType.PREVIEW_SEEN,
                            timestampMs = System.currentTimeMillis()
                        )
                    }
                }
                refreshEpoch++

                val available = availableMedia
                val filter = _selectedFilterType.value
                val context = ChannelProgrammingContext(
                    availableMedia = available,
                    filterType = filter,
                    tasteDNA = repository.tasteDNA.value,
                    exposureMap = available.associate { it.id to it.exposureCount },
                    refreshEpoch = refreshEpoch,
                    sessionExposures = currentSessionExposures,
                    decayedExposureMap = available.associate { it.id to it.getDecayedExposure() }
                )

                val usedPreviewIds = mutableSetOf<String>()

                val states = allChannels.map { channel ->
                    val programmed = programmer.programChannel(channel, context, limit = 20)

                    val candidate = if (channel.channelKind == ChannelKind.SEARCH_SEEDED && channel.referenceMediaIds.isNotEmpty()) {
                        val firstRefId = channel.referenceMediaIds.first()
                        programmed.find { it.id == firstRefId }
                            ?: available.find { it.id == firstRefId }
                            ?: programmed.firstOrNull { it.id !in usedPreviewIds }
                    } else {
                        programmed.firstOrNull { it.id !in usedPreviewIds }
                    }
                    if (candidate != null) {
                        usedPreviewIds.add(candidate.id)
                    }

                    val adjustedFullItems = if (candidate != null) {
                        listOf(candidate) + programmed.filter { it.id != candidate.id }
                    } else programmed

                    ChannelPreviewState(
                        channel = channel,
                        candidateItem = candidate,
                        fullItems = adjustedFullItems
                    )
                }
                _channelPreviews.value = states

                val currentChannel = _selectedChannel.value
                sessionManager.selectChannel(
                    channel = currentChannel,
                    repository = repository,
                    filterType = filter,
                    refreshEpoch = refreshEpoch,
                    sessionExposures = currentSessionExposures
                )
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
