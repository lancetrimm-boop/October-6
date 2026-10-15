package com.example.data.intelligence

import com.example.data.MediaItem
import com.example.data.TasteDNA
import com.example.data.DiscoveryPolicy
import com.example.data.UserIntent
import com.example.data.IntelligenceStats
import com.example.data.CreatorProfile

enum class IntelligenceMode {
    SEARCH,
    SIMILAR,
    DISCOVER,
    SORT
}

enum class RankingMode {
    RETRIEVAL,
    DISCOVERY
}

/**
 * Surface-provided intent to guide Core decision making.
 */
enum class ContextualIntent {
    LIBRARY_BROWSE,
    DISCOVER_CATEGORY,
    DETAIL_CONTEXT,
    MENU_SHORTCUT,
    MOMENTS_FLOW
}

/**
 * Epistemic status of intelligence evidence.
 */
enum class EvidenceStatus {
    /**
     * Direct deterministic data (e.g. metadata match, explicit rating).
     */
    KNOWN,

    /**
     * Model-based inferred evidence (e.g. semantic similarity, visual match).
     */
    INFERRED,

    /**
     * Weak evidence derived from fallback behavior or low-confidence models.
     */
    UNCERTAIN
}

enum class EvidenceType {
    LEXICAL_MATCH,
    SEMANTIC_RELEVANCE,
    VISUAL_SIMILARITY,
    METADATA_MATCH,
    TASTE_DNA_ALIGNMENT,
    PAIRWISE_PREFERENCE,
    RELATIONSHIP_MATCH,
    EXPLORATION_VALUE,
    RECENCY_BONUS,
    DIVERSITY_PENALTY
}

/**
 * Structured piece of intelligence evidence supporting a ranking decision.
 */
data class EvidenceItem(
    val type: EvidenceType,
    val score: Float, // Numerical score produced by the logic/model
    val confidence: Float, // Confidence in the reliability of this score (0.0-1.0)
    val status: EvidenceStatus,
    val provenance: String, // Identification of the source/model
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Unified intelligence request model.
 */
data class IntelligenceRequest(
    val mode: IntelligenceMode,
    val rankingMode: RankingMode = if (mode == IntelligenceMode.SEARCH) RankingMode.RETRIEVAL else RankingMode.DISCOVERY,
    val contextualIntent: ContextualIntent = ContextualIntent.LIBRARY_BROWSE,
    val query: String? = null,
    val referenceItemId: String? = null,
    val relatedMediaIds: List<String> = emptyList(),
    val visualVector: FloatArray? = null,
    val queryVectors: List<FloatArray>? = null,
    val limit: Int = 10000,
    val tasteDNA: TasteDNA? = null,
    val profile: TasteDNA.PreferenceProfile? = null,
    val policy: DiscoveryPolicy? = null,
    val intent: UserIntent? = null,
    val stats: IntelligenceStats? = null,
    val creatorProfiles: Map<String, CreatorProfile>? = null,
    val comparisonCounts: Map<String, Int>? = null,
    val seed: Long = 42L,
    val sortOption: String? = null,
    val filterType: String = "ALL",
    val explicitConstraints: Map<String, String> = emptyMap(),
    val useLegacyRanking: Boolean = false,
    val requestId: String = java.util.UUID.randomUUID().toString().take(8),
    /** Deterministic items for developer replay. Bypasses repository access. */
    val poolOverride: List<MediaItem>? = null,
    /** Suppresses side effects like caching and performance reporting. */
    val skipPersistence: Boolean = false
)

/**
 * Ranked candidate item produced by the Intelligence Core.
 */
data class IntelligenceCandidate(
    val item: MediaItem,
    val evidence: List<EvidenceItem>,
    val rankScore: Double, // Final fused/policy score
    val primaryRelevanceScore: Float, // Core relevance (Similarity/Match)
    val secondaryEvidenceScore: Float, // Adjustments (Personalization/Pairwise)
    val provenance: String = "" // Summary explanation
)

/**
 * Encapsulates the complete result of an intelligence operation.
 */
data class IntelligenceResponse(
    val requestId: String,
    val mode: IntelligenceMode,
    val candidates: List<IntelligenceCandidate>,
    val relationshipEvidence: List<EvidenceItem> = emptyList(), // Global relationship context
    val visibilitySealed: Boolean = false,
    val latencyMs: Long,
    val isSuccess: Boolean = true,
    val errorMessage: String? = null
)
