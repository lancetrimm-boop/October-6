package com.example.data.intelligence

import com.example.data.semantic.RankedChannelItem
import com.example.data.semantic.SearchChannel
import com.example.data.semantic.LexicalCandidateRetriever
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Coordinates multi-channel candidate retrieval based on intelligence requests.
 * Decouples query modality from specific retrieval implementations.
 */
class RetrievalRouter(
    val lexicalRetriever: LexicalCandidateRetriever,
    val semanticProvider: SemanticRetrievalProvider?,
    var visualProvider: VisualRetrievalProvider?
) {
    /**
     * Executes parallel retrieval across all available and appropriate channels.
     */
    suspend fun retrieve(request: IntelligenceRequest): Map<SearchChannel, List<RankedChannelItem>> = coroutineScope {
        val channelResults = mutableMapOf<SearchChannel, List<RankedChannelItem>>()
        
        val modalityMetadata = mutableMapOf<String, String>()
        modalityMetadata["mode"] = request.mode.name
        request.query?.let { modalityMetadata["query_hash"] = it.hashCode().toString() }
        if (request.visualVector != null) modalityMetadata["visual_modality"] = "vector"
        if (request.queryVectors != null) modalityMetadata["visual_modality"] = "multi_vector"

        com.example.data.intelligence.DecisionTraceCollector.logEvent(
            request.requestId, 
            com.example.ui.models.TraceEventType.CHANNEL_RETRIEVAL_START,
            detail = "Mode: ${request.mode}, Query: ${request.query?.take(20) ?: "None"}",
            metadata = modalityMetadata
        )
        
        // 1. Lexical Channel (Keyword/Metadata)
        val lexicalDeferred = async {
            val query = request.query ?: ""
            if (query.isNotBlank()) {
                try {
                    val results = lexicalRetriever.retrieveKeywordCandidates(query, request.limit * 2)
                    com.example.data.intelligence.DecisionTraceCollector.logEvent(
                        request.requestId,
                        com.example.ui.models.TraceEventType.CHANNEL_RETRIEVAL_COMPLETE,
                        detail = "Channel: KEYWORD, Count: ${results.size}",
                        metadata = mapOf("channel" to "KEYWORD", "count" to results.size.toString())
                    )
                    results
                } catch (e: Exception) {
                    android.util.Log.e("RetrievalRouter", "Lexical retrieval failed", e)
                    emptyList()
                }
            } else emptyList()
        }

        // 2. Semantic Channel (MiniLM Content)
        val semanticDeferred = async {
            val query = request.query
            if (query != null && query.isNotBlank() && semanticProvider != null && semanticProvider.isReady()) {
                try {
                    val results = semanticProvider.retrieveSemanticCandidates(query, request.limit * 2, 0.22f)
                    com.example.data.intelligence.DecisionTraceCollector.logEvent(
                        request.requestId,
                        com.example.ui.models.TraceEventType.CHANNEL_RETRIEVAL_COMPLETE,
                        detail = "Channel: SEMANTIC_CONTENT, Count: ${results.size}",
                        metadata = mapOf("channel" to "SEMANTIC_CONTENT", "count" to results.size.toString())
                    )
                    results
                } catch (e: Exception) {
                    android.util.Log.e("RetrievalRouter", "Semantic retrieval failed", e)
                    emptyList()
                }
            } else emptyList()
        }

        // 3. Visual Channel (CLIP Visual)
        val visualDeferred = async {
            val provider = visualProvider
            if (provider != null && provider.isReady()) {
                try {
                    val vector = request.visualVector
                    val queryVectors = request.queryVectors
                    val query = request.query
                    val results = when {
                        // Multi-Vector Union Strategy (A ∪ B ∪ C)
                        queryVectors != null && queryVectors.isNotEmpty() -> {
                            val allResults = queryVectors.flatMap { v ->
                                provider.retrieveVisualCandidates(v, request.limit, 0.0f)
                            }
                            
                            // Deduplicate and re-rank
                            allResults.groupBy { it.mediaId }
                                .map { (id, items) ->
                                    val best = items.minBy { it.rank }
                                    RankedChannelItem(
                                        mediaId = id,
                                        rawScore = items.maxOf { it.rawScore },
                                        rank = best.rank,
                                        metadata = best.metadata
                                    )
                                }
                                .sortedWith(compareBy({ it.rank }, { -it.rawScore }))
                                .take(request.limit * 2)
                                .mapIndexed { index, item -> item.copy(rank = index + 1) }
                        }
                        vector != null -> provider.retrieveVisualCandidates(vector, request.limit * 2, 0.0f)
                        // AURA REPAIR: Only invoke text-to-visual if the query is a simple text request 
                        // and we have no visual vectors (prevent invalid type relay for multimodal)
                        query != null && query.isNotBlank() && request.queryVectors == null -> {
                            provider.retrieveVisualCandidates(query, request.limit * 2, 0.26f)
                        }
                        else -> emptyList()
                    }
                    com.example.data.intelligence.DecisionTraceCollector.logEvent(
                        request.requestId,
                        com.example.ui.models.TraceEventType.CHANNEL_RETRIEVAL_COMPLETE,
                        detail = "Channel: SEMANTIC_VISUAL, Count: ${results.size}",
                        metadata = mapOf("channel" to "SEMANTIC_VISUAL", "count" to results.size.toString())
                    )
                    results
                } catch (e: Exception) {
                    android.util.Log.e("RetrievalRouter", "Visual retrieval failed", e)
                    emptyList()
                }
            } else emptyList()
        }

        // Collect results
        val lexical = lexicalDeferred.await()
        if (lexical.isNotEmpty()) channelResults[SearchChannel.KEYWORD] = lexical

        val semantic = semanticDeferred.await()
        if (semantic.isNotEmpty()) channelResults[SearchChannel.SEMANTIC_CONTENT] = semantic

        val visual = visualDeferred.await()
        if (visual.isNotEmpty()) channelResults[SearchChannel.SEMANTIC_VISUAL] = visual

        channelResults
    }
}
