package com.example.data.semantic

/**
 * Categorical search/retrieval ranking channels participating in hybrid fusion.
 */
enum class SearchChannel {
    /**
     * Lexical token matching, BM25, exact substring, and metadata attribute matching.
     */
    KEYWORD,

    /**
     * Dense neural text semantic vector retrieval (e.g. MiniLM CONTENT representations).
     */
    SEMANTIC_CONTENT,

    /**
     * Dense neural visual embedding retrieval (e.g. MobileCLIP VISUAL representations).
     */
    SEMANTIC_VISUAL,

    /**
     * Personalized user preference or taste affinity trait ranking.
     */
    PERSONALIZED
}

/**
 * High-level search query modality.
 */
enum class SearchQueryType {
    /**
     * Natural language text query.
     */
    TEXT,

    /**
     * Visual reference image query (Search-by-image).
     */
    VISUAL,

    /**
     * Combined visual and textual query (Stage 11 Context Expansion).
     */
    COMPOUND,

    /**
     * Multiple visual reference intersection (Phase 2).
     */
    MULTI_VISUAL
}

/**
 * Unified search request model for Aura.
 * 
 * Supports polymorphic query modalities (Text, Visual, Compound) while maintaining 
 * a lightweight, vector-ready representation for the search pipeline.
 *
 * Designed to be immutable and transient query state.
 */
sealed interface SearchRequest {
    /**
     * Unique identifier for this search session/request.
     */
    val requestId: String

    /**
     * Optional natural language search string.
     */
    val query: String?

    /**
     * Optional pre-encoded visual search vector.
     */
    val visualVector: FloatArray?

    /**
     * Optional multiple visual reference vectors for intersection search.
     */
    val visualVectors: List<FloatArray>?

    /**
     * Optional URI to the reference image (for UI display purposes).
     */
    val referenceUri: String?

    /**
     * Optional multiple URIs for multi-visual search.
     */
    val referenceUris: List<String>?

    /**
     * Identifies the query modality based on implementation type.
     */
    val queryType: SearchQueryType

    /**
     * Natural language text search request.
     */
    data class Text(
        override val query: String,
        override val requestId: String = java.util.UUID.randomUUID().toString().take(8)
    ) : SearchRequest {
        override val visualVector: FloatArray? = null
        override val visualVectors: List<FloatArray>? = null
        override val referenceUri: String? = null
        override val referenceUris: List<String>? = null
        override val queryType: SearchQueryType = SearchQueryType.TEXT
    }

    data class Visual(
        override val visualVector: FloatArray,
        override val referenceUri: String? = null,
        override val requestId: String = java.util.UUID.randomUUID().toString().take(8)
    ) : SearchRequest {
        override val query: String? = null
        override val visualVectors: List<FloatArray> = listOf(visualVector)
        override val referenceUris: List<String>? = referenceUri?.let { listOf(it) }
        override val queryType: SearchQueryType = SearchQueryType.VISUAL
    }

    data class Compound(
        override val query: String,
        override val visualVector: FloatArray,
        override val referenceUri: String? = null,
        override val requestId: String = java.util.UUID.randomUUID().toString().take(8)
    ) : SearchRequest {
        override val visualVectors: List<FloatArray> = listOf(visualVector)
        override val referenceUris: List<String>? = referenceUri?.let { listOf(it) }
        override val queryType: SearchQueryType = SearchQueryType.COMPOUND
    }

    /**
     * Multiple visual reference intersection (Phase 2).
     */
    data class MultiVisual(
        override val visualVectors: List<FloatArray>,
        override val referenceUris: List<String>,
        override val query: String? = null, // Allow optional text constraint
        override val requestId: String = java.util.UUID.randomUUID().toString().take(8)
    ) : SearchRequest {
        override val visualVector: FloatArray? = visualVectors.firstOrNull()
        override val referenceUri: String? = referenceUris.firstOrNull()
        override val queryType: SearchQueryType = SearchQueryType.MULTI_VISUAL
    }
}

/**
 * Ranked candidate item emitted by an individual retrieval channel.
 *
 * @property mediaId Unique media item identifier.
 * @property rawScore Uncalibrated channel-specific score (e.g. cosine similarity, BM25 score, hit count).
 * @property rank 1-based ordinal rank within the channel's candidate list.
 * @property metadata Optional channel-specific debugging or explainability metadata.
 */
data class RankedChannelItem(
    val mediaId: String,
    val rawScore: Float,
    val rank: Int,
    val metadata: Map<String, String> = emptyMap()
) {
    init {
        require(mediaId.isNotBlank()) { "mediaId cannot be blank" }
        require(rank >= 1) { "rank must be a 1-based positive integer (got $rank)" }
    }
}

/**
 * Configuration parameters for Reciprocal Rank Fusion (RRF) and hybrid search execution.
 *
 * @property rrfConstantK Smoothing constant in RRF denominator (standard literature default = 60).
 * @property channelWeights Relative importance multipliers for each search channel (must be non-negative).
 * @property topK Maximum number of fused candidates to return.
 * @property minSemanticSimilarity User-facing relevance gate for final results.
 * @property minNeuralRetrievalSimilarity Recall/Retrieval gate for initial candidate selection.
 */
data class HybridSearchConfig(
    val rrfConstantK: Int = 60,
    val channelWeights: Map<SearchChannel, Double> = mapOf(
        SearchChannel.KEYWORD to 1.0,
        SearchChannel.SEMANTIC_CONTENT to 0.40,
        SearchChannel.SEMANTIC_VISUAL to 0.55,
        SearchChannel.PERSONALIZED to 0.0
    ),
    val topK: Int = 40,
    val minSemanticSimilarity: Float = 0.35f,
    val minTextSemanticSimilarity: Float = 0.32f,
    val minNeuralRetrievalSimilarity: Float = 0.26f,
    val relativeNeuralCutoff: Float = 0.82f,
    val maxPureNeuralResults: Int = 10,
    val minRankScoreRatioOfTop: Float = 0.55f
) {
    init {
        require(rrfConstantK > 0) { "rrfConstantK must be positive (got $rrfConstantK)" }
        require(topK > 0) { "topK must be positive (got $topK)" }
        for ((channel, weight) in channelWeights) {
            require(weight >= 0.0) { "Weight for channel $channel must be non-negative (got $weight)" }
        }
        val totalWeight = channelWeights.values.sum()
        require(totalWeight > 0.0) { "Sum of channel weights must be strictly positive (got $totalWeight)" }
    }

    fun getNormalizedWeight(channel: SearchChannel): Double {
        val total = channelWeights.values.sum()
        val raw = channelWeights[channel] ?: 0.0
        return if (total > 0.0) raw / total else 0.0
    }
}

/**
 * Structured explanation of why a specific media item was ranked for a query.
 */
enum class MatchReasonType {
    EXACT_FILENAME,
    STRONG_FILENAME_MATCH,
    SEMANTIC_METADATA_MATCH,
    VISUAL_CONTENT_MATCH,
    CROSS_MODAL_MATCH,
    PERSONALIZED_RELEVANCE,
    MULTI_CHANNEL_ALIGNMENT,
    DEEP_SCENE_MATCH
}

data class MatchReason(
    val type: MatchReasonType,
    val confidence: Float,
    val description: String
)

/**
 * Fused candidate item produced by Reciprocal Rank Fusion.
 *
 * @property mediaId Unique media item identifier.
 * @property rrfScore Aggregated Reciprocal Rank Fusion score.
 * @property channelRanks Map of 1-based rank positions per contributing channel.
 * @property channelScores Map of raw channel scores per contributing channel.
 * @property matchReasons Structured evidence for the ranking.
 * @property matchExplanation Human-readable provenance explanation of the hybrid ranking.
 */
data class HybridCandidate(
    val mediaId: String,
    val rrfScore: Double,
    val channelRanks: Map<SearchChannel, Int>,
    val channelScores: Map<SearchChannel, Float>,
    val isAuthoritativeLexical: Boolean = false,
    val matchReasons: List<MatchReason> = emptyList(),
    val matchExplanation: String = ""
) {
    init {
        require(mediaId.isNotBlank()) { "mediaId cannot be blank" }
        require(rrfScore >= 0.0) { "rrfScore must be non-negative (got $rrfScore)" }
    }

    val isMultiChannelMatch: Boolean get() = channelRanks.size > 1
}

/**
 * Encapsulates the complete result of a hybrid search query execution.
 * 
 * Includes detailed diagnostic metadata for behavioral validation.
 *
 * @property query Raw search query text (or descriptive summary for visual).
 * @property queryType The modality of the original search request.
 * @property requestId Correlated request identifier.
 * @property candidates Ordered list of fused candidates (sorted descending by RRF score).
 * @property latencyMs Execution latency in milliseconds.
 * @property totalCandidatesConsidered Total unique media items evaluated across all channels before top-K trimming.
 * @property channelCandidateCounts Item count contributed by each individual search channel.
 * @property deepRerankCount Number of candidates that underwent deep frame-level reranking.
 * @property frameVectorsLoaded Total number of individual frame embeddings retrieved for reranking.
 * @property isSuccess Indicates whether execution succeeded.
 * @property errorMessage Error description if execution failed.
 */
data class HybridSearchResult(
    val query: String,
    val queryType: SearchQueryType = SearchQueryType.TEXT,
    val requestId: String = "",
    val candidates: List<HybridCandidate>,
    val latencyMs: Long,
    val totalCandidatesConsidered: Int,
    val channelCandidateCounts: Map<SearchChannel, Int>,
    val deepRerankCount: Int = 0,
    val frameVectorsLoaded: Int = 0,
    val isSuccess: Boolean = true,
    val errorMessage: String? = null
) {
    val topMatch: HybridCandidate? get() = candidates.firstOrNull()
    val hasMatches: Boolean get() = candidates.isNotEmpty()
    val candidateCount: Int get() = candidates.size
}
