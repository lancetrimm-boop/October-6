package com.example.data.cleanup

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.example.data.MediaItem
import com.example.data.MediaRepository
import com.example.data.db.RejectedMediaEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.app.PendingIntent
import com.squareup.moshi.JsonClass
import com.example.data.db.UserPreferenceEntity
import kotlinx.coroutines.launch
import android.util.Log

enum class DeletionState {
    IDLE,
    PENDING,
    AURA_CONFIRMATION_REQUIRED,
    CONFIRMED,
    CANCELLED,
    FAILED
}

class SafeDeleteManager(
    private val repository: MediaRepository,
    private val managerScope: kotlinx.coroutines.CoroutineScope
) {
    private val _deletionState = MutableStateFlow(DeletionState.IDLE)
    val deletionState: StateFlow<DeletionState> = _deletionState.asStateFlow()

    private var pendingMediaStoreItems: List<MediaItem> = emptyList()
    private var pendingInternalItems: List<MediaItem> = emptyList()
    private var pendingRecommendations: List<CleanupRecommendation> = emptyList()

    companion object {
        private const val KEY_PENDING_TRANSACTION = "pending_cleanup_transaction"
    }

    /**
     * Delegate for MediaStore deletion requests to allow testing without static mocking.
     */
    @androidx.annotation.VisibleForTesting
    internal var mediaStoreDeleteProvider: (Context, List<Uri>) -> PendingIntent? = { context, uris ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            MediaStore.createDeleteRequest(context.contentResolver, uris)
        } else null
    }

    /**
     * Initiates the deletion workflow for a set of items.
     */
    fun requestDeletion(
        context: Context,
        items: List<MediaItem>,
        recommendations: List<CleanupRecommendation>,
        launcher: ActivityResultLauncher<IntentSenderRequest>
    ) {
        val isEntitled = recommendations.isEmpty() || repository.entitlementRepository?.isFeatureAvailable(
            com.example.data.entitlement.ProFeature.CLEANUP_AUTOMATION
        ) == true

        if (!isEntitled) {
            Log.w("SafeDeleteManager", "requestDeletion aborted: Pro feature CLEANUP_AUTOMATION is not available.")
            _deletionState.value = DeletionState.FAILED
            resetPending()
            clearPersistedState()
            return
        }

        if (items.isEmpty()) return

        pendingRecommendations = recommendations
        
        val mediaStoreItems = mutableListOf<MediaItem>()
        val internalItems = mutableListOf<MediaItem>()
        
        items.forEach { item ->
            try {
                val uri = Uri.parse(item.uriPath)
                if (uri.authority == "media") {
                    mediaStoreItems.add(item)
                } else {
                    internalItems.add(item)
                }
            } catch (_: Exception) {
                internalItems.add(item)
            }
        }

        pendingMediaStoreItems = mediaStoreItems
        pendingInternalItems = internalItems

        // Priority 1: Trigger System Deletion for MediaStore items
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mediaStoreItems.isNotEmpty()) {
            _deletionState.value = DeletionState.PENDING
            persistState(DeletionState.PENDING)
            try {
                val uris = mediaStoreItems.map { Uri.parse(it.uriPath) }
                mediaStoreDeleteProvider(context, uris)?.let { pendingIntent ->
                    val request = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                    launcher.launch(request)
                } ?: run {
                    _deletionState.value = DeletionState.FAILED
                    resetPending()
                    clearPersistedState()
                }
            } catch (e: Exception) {
                Log.e("SafeDeleteManager", "Failed to create delete request", e)
                _deletionState.value = DeletionState.FAILED
                resetPending()
                clearPersistedState()
            }
        } else if (internalItems.isNotEmpty()) {
            // No MediaStore items, but have internal items -> Request Aura confirmation
            _deletionState.value = DeletionState.AURA_CONFIRMATION_REQUIRED
            persistState(DeletionState.AURA_CONFIRMATION_REQUIRED)
        } else {
            _deletionState.value = DeletionState.FAILED
            resetPending()
            clearPersistedState()
        }
    }

    /**
     * Processes the result of the system deletion dialog.
     */
    fun handleDeletionResult(resultCode: Int) {
        if (resultCode == Activity.RESULT_OK) {
            // Authoritative confirmation for MS items
            reconcileDatabaseAfterDeletion(pendingMediaStoreItems)
            pendingMediaStoreItems = emptyList()
            
            // Proceed to internal confirmation if needed
            if (pendingInternalItems.isNotEmpty()) {
                _deletionState.value = DeletionState.AURA_CONFIRMATION_REQUIRED
                persistState(DeletionState.AURA_CONFIRMATION_REQUIRED)
            } else {
                _deletionState.value = DeletionState.CONFIRMED
                clearPersistedState()
            }
        } else {
            _deletionState.value = DeletionState.CANCELLED
            resetPending()
            clearPersistedState()
        }
    }

    /**
     * Confirms and executes deletion for internal (non-MediaStore) items.
     */
    fun confirmInternalDeletion() {
        if (pendingInternalItems.isNotEmpty()) {
            reconcileDatabaseAfterDeletion(pendingInternalItems)
            pendingInternalItems = emptyList()
        }
        _deletionState.value = DeletionState.CONFIRMED
        clearPersistedState()
        resetPending()
    }

    /**
     * Cancels the entire deletion workflow.
     */
    fun cancelDeletion() {
        _deletionState.value = DeletionState.CANCELLED
        clearPersistedState()
        resetPending()
    }

    private fun resetPending() {
        pendingMediaStoreItems = emptyList()
        pendingInternalItems = emptyList()
        pendingRecommendations = emptyList()
    }

    private fun reconcileDatabaseAfterDeletion(itemsToDelete: List<MediaItem>) {
        itemsToDelete.forEach { item ->
            val rec = pendingRecommendations.find { it.mediaId == item.id }
            reconcileRecoveryItem(item.toRecoveryItem(rec))
        }
    }

    /**
     * Attempts to recover state after process death.
     */
    suspend fun recoverPendingDeletions(context: Context) {
        val db = repository.getDatabase() ?: return
        val pref = db.userPreferenceDao().getPreference(KEY_PENDING_TRANSACTION) ?: return
        
        try {
            val adapter = repository.getMoshi().adapter(PersistedDeletionTransaction::class.java)
            val transaction = adapter.fromJson(pref.value) ?: return
            
            Log.i("SafeDeleteManager", "Recovering pending deletions from state: ${transaction.state}")

            if (transaction.state == DeletionState.PENDING.name) {
                // APP DIED DURING SYSTEM DIALOG: Check if items were actually deleted
                val confirmedDeleted = transaction.mediaStoreItems.filter { item ->
                    !checkUriExists(context, item.uriPath)
                }
                
                if (confirmedDeleted.isNotEmpty()) {
                    Log.i("SafeDeleteManager", "Recovery: ${confirmedDeleted.size} items confirmed deleted while offline. Reconciling DB.")
                    confirmedDeleted.forEach { reconcileRecoveryItem(it) }
                }
            }
            
            // Note: Internal items require explicit session-bound confirmation and are not
            // automatically deleted during recovery to prevent silent data removal.
            
        } catch (e: Exception) {
            Log.e("SafeDeleteManager", "Failed to recover pending deletions", e)
        } finally {
            clearPersistedState()
        }
    }

    private fun persistState(state: DeletionState) {
        val msItems = pendingMediaStoreItems.map { it.toRecoveryItem(pendingRecommendations.find { r -> r.mediaId == it.id }) }
        val intItems = pendingInternalItems.map { it.toRecoveryItem(pendingRecommendations.find { r -> r.mediaId == it.id }) }
        
        val transaction = PersistedDeletionTransaction(
            mediaStoreItems = msItems,
            internalItems = intItems,
            state = state.name
        )
        
        try {
            val adapter = repository.getMoshi().adapter(PersistedDeletionTransaction::class.java)
            val json = adapter.toJson(transaction)
            managerScope.launch {
                repository.getDatabase()?.userPreferenceDao()?.insertPreference(
                    UserPreferenceEntity(KEY_PENDING_TRANSACTION, json)
                )
            }
        } catch (e: Exception) {
            Log.e("SafeDeleteManager", "Failed to persist deletion state", e)
        }
    }

    private fun clearPersistedState() {
        managerScope.launch {
            repository.getDatabase()?.userPreferenceDao()?.deletePreference(KEY_PENDING_TRANSACTION)
        }
    }

    private fun MediaItem.toRecoveryItem(rec: CleanupRecommendation?): RecoveryItem {
        return RecoveryItem(
            id = id,
            uriPath = uriPath,
            imageUrl = imageUrl,
            title = title,
            mediaType = mediaType,
            category = rec?.category?.name ?: "User Deleted",
            keepScore = rec?.keepScore ?: 0f,
            compatibilityStatus = compatibilityStatus.name,
            containerFormat = containerFormat,
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            contentHash = contentHash
        )
    }

    private fun reconcileRecoveryItem(item: RecoveryItem) {
        // 1. Add to Rejected Media to prevent re-import
        val rejected = RejectedMediaEntity(
            id = "del_${item.id}_${System.currentTimeMillis()}",
            uriPath = item.uriPath,
            title = item.title,
            mediaType = item.mediaType,
            reason = item.category,
            compatibilityStatus = item.compatibilityStatus,
            containerFormat = item.containerFormat,
            videoCodec = item.videoCodec,
            audioCodec = item.audioCodec,
            contentHash = item.contentHash,
            timestampRejected = System.currentTimeMillis()
        )
        repository.addRejectedMedia(rejected)

        // 2. Remove from MediaRepository and DB
        repository.deleteMediaItem(item.id)
        
        // 3. Clear thumbnail cache
        com.example.util.MediaThumbnailFetcher.removeThumbnail(item.uriPath)
        if (item.imageUrl.isNotEmpty()) {
            com.example.util.MediaThumbnailFetcher.removeThumbnail(item.imageUrl)
        }
        
        // 4. Record learning signal
        repository.recordCleanupSignal(
            mediaId = item.id,
            category = item.category,
            score = item.keepScore,
            isDelete = true
        )
    }

    private fun checkUriExists(context: Context, uriPath: String): Boolean {
        // We only reliably check MediaStore existence for recovery
        if (!uriPath.startsWith("content://media/")) return true 
        return try {
            val uri = Uri.parse(uriPath)
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { 
                it.moveToFirst() 
            } == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Marks an item as 'Kept' by the user, protecting it from future cleanup targeting.
     */
    fun markAsKept(item: MediaItem, recommendation: CleanupRecommendation) {
        // Protect the item by favoriting it
        if (!item.isFavorite) {
            repository.toggleFavorite(item.id)
        }
        
        repository.recordCleanupSignal(
            mediaId = item.id,
            category = recommendation.category.name,
            score = recommendation.keepScore,
            isDelete = false
        )
    }
}

@JsonClass(generateAdapter = true)
data class RecoveryItem(
    val id: String,
    val uriPath: String,
    val imageUrl: String,
    val title: String,
    val mediaType: String,
    val category: String,
    val keepScore: Float,
    val compatibilityStatus: String,
    val containerFormat: String,
    val videoCodec: String,
    val audioCodec: String,
    val contentHash: String?
)

@JsonClass(generateAdapter = true)
data class PersistedDeletionTransaction(
    val mediaStoreItems: List<RecoveryItem>,
    val internalItems: List<RecoveryItem>,
    val state: String,
    val timestamp: Long = System.currentTimeMillis()
)
