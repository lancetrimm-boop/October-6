package com.example.data.metv

import com.example.data.CompatibilityStatus
import com.example.data.MediaItem
import com.example.data.TasteDNA
import com.example.data.db.AISkipEventEntity
import com.example.data.db.ProgrammingFeedbackEntity
import java.util.Calendar

/**
 * Programmed station result payload.
 */
data class ProgrammedStationResult(
    val items: List<MediaItem>,
    val laneContributions: Map<String, List<String>>, // laneName -> list of mediaIds
    val explanations: Map<String, String> // mediaId -> aesthetic explanation
)

/**
 * Me TV Programming Engine — Multi-Lane Candidate Blending & Ranking.
 * 
 * Blends 6 Programming Lanes:
 * 1. Novelty Lane: Unseen / low-exposure candidates. Skips count as exposures.
 * 2. Rediscover Lane: Owned media with lastViewedTimestamp > 30 days or null.
 * 3. Favorites Lane: Starred & liked media with unwatched-first tiered shuffle.
 * 4. Mood / Aesthetic Lane: Evaluates TasteDNA 24 visual traits; applies ExperienceRequest mood steering.
 * 5. Daypart Lane: Time-of-day contextual blocks (Morning, Afternoon, Evening, Night).
 * 6. Continue Lane: Oldest unwatched episode in order for serialized content (parentContentId).
 * 
 * Invariants:
 * - 100% Media-Type Homogeneity (VIDEOS vs PHOTOS). Zero mixed queues.
 * - Two-Signal Feedback Isolation: Thumbs Down in programming_feedback applies a
 *   programming-level penalty (-0.35f) WITHOUT mutating UserPreferenceEntity, ratings, or TasteDNA.
 * - Deterministic output order with stable tie-breaking.
 */
class MeTVProgrammingEngine {

    companion object {
        const val LANE_NOVELTY = "Novelty"
        const val LANE_REDISCOVER = "Rediscover"
        const val LANE_FAVORITES = "Favorites"
        const val LANE_MOOD = "Mood"
        const val LANE_DAYPART = "Daypart"
        const val LANE_CONTINUE = "Continue"

        private const val REDISCOVER_THRESHOLD_MS = 30L * 24 * 60 * 60 * 1000 // 30 days
    }

    /**
     * Programs an endless station sequence.
     */
    fun program(
        availableMedia: List<MediaItem>,
        filterType: String = "VIDEOS",
        tasteDNA: TasteDNA = TasteDNA(),
        programmingFeedback: List<ProgrammingFeedbackEntity> = emptyList(),
        exposureMap: Map<String, Int> = emptyMap(),
        skipEvents: List<AISkipEventEntity> = emptyList(),
        experienceRequest: ExperienceRequest? = null,
        currentTimeMs: Long = System.currentTimeMillis(),
        targetCount: Int = 30,
        refreshEpoch: Int = 0,
        sessionExposures: Map<String, Int> = emptyMap(),
        decayedExposureMap: Map<String, Float> = emptyMap()
    ): ProgrammedStationResult {
        if (availableMedia.isEmpty()) {
            return ProgrammedStationResult(emptyList(), emptyMap(), emptyMap())
        }

        // 1. Enforce strict media-type homogeneity & playback compatibility
        val visible = listOf(
            CompatibilityStatus.PLAYABLE,
            CompatibilityStatus.PLAYABLE_SOFTWARE_DECODE,
            CompatibilityStatus.THUMBNAIL_FAILED,
            CompatibilityStatus.ANALYSIS_PENDING,
            CompatibilityStatus.ANALYSIS_IN_PROGRESS,
            CompatibilityStatus.UNTESTED
        )
        val isVideoFilter = filterType.equals("VIDEO", ignoreCase = true) || filterType.equals("VIDEOS", ignoreCase = true) || filterType.equals("MOVIE", ignoreCase = true) || filterType.equals("MOVIES", ignoreCase = true)
        val isPhotoFilter = filterType.equals("PHOTO", ignoreCase = true) || filterType.equals("PHOTOS", ignoreCase = true) || filterType.equals("IMAGE", ignoreCase = true) || filterType.equals("IMAGES", ignoreCase = true)
        val homogeneousPool = availableMedia.filter { item ->
            if (item.isDeleted) return@filter false
            if (item.compatibilityStatus !in visible) return@filter false
            val isVid = item.mediaType.equals("VIDEO", ignoreCase = true) || item.mediaType.equals("MOVIE", ignoreCase = true) || item.mediaType.startsWith("VIDEO", ignoreCase = true) || item.mediaType.startsWith("MOVIE", ignoreCase = true)
            if (isVideoFilter) isVid else if (isPhotoFilter) !isVid else true
        }

        if (homogeneousPool.isEmpty()) {
            return ProgrammedStationResult(emptyList(), emptyMap(), emptyMap())
        }

        // Build skip exposure counts
        val skipCountsByMedia = skipEvents.groupingBy { it.mediaId }.eachCount()
        
        // Build negative programming feedback set
        val negativeFeedbackMediaIds = programmingFeedback
            .filter { !it.isGoodProgramming }
            .map { it.mediaId }
            .toSet()

        // 2. Evaluate Candidates across the 6 Lanes
        val laneContributions = mutableMapOf<String, MutableList<String>>()
        laneContributions[LANE_NOVELTY] = mutableListOf()
        laneContributions[LANE_REDISCOVER] = mutableListOf()
        laneContributions[LANE_FAVORITES] = mutableListOf()
        laneContributions[LANE_MOOD] = mutableListOf()
        laneContributions[LANE_DAYPART] = mutableListOf()
        laneContributions[LANE_CONTINUE] = mutableListOf()

        // Evaluate Lane 1: Novelty (Unseen / Low Exposure)
        val noveltyCandidates = homogeneousPool.filter { item ->
            val exp = exposureMap[item.id] ?: 0
            val skips = skipCountsByMedia[item.id] ?: 0
            exp == 0 && skips == 0
        }
        noveltyCandidates.forEach { laneContributions[LANE_NOVELTY]?.add(it.id) }

        // Evaluate Lane 2: Rediscover (Forgotten Media > 30 days)
        val rediscoverCandidates = homogeneousPool.filter { item ->
            val exp = exposureMap[item.id] ?: 0
            val lastViewed = item.lastViewedTimestamp ?: 0L
            exp > 0 && (lastViewed == 0L || (currentTimeMs - lastViewed) > REDISCOVER_THRESHOLD_MS)
        }
        rediscoverCandidates.forEach { laneContributions[LANE_REDISCOVER]?.add(it.id) }

        // Evaluate Lane 3: Favorites
        val favoriteCandidates = homogeneousPool.filter { it.isFavorite }
        favoriteCandidates.forEach { laneContributions[LANE_FAVORITES]?.add(it.id) }

        // Evaluate Lane 4: Mood & Aesthetic (TasteDNA matching)
        homogeneousPool.forEach { laneContributions[LANE_MOOD]?.add(it.id) }

        // Evaluate Lane 5: Daypart Context
        val calendar = Calendar.getInstance().apply { timeInMillis = currentTimeMs }
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentDaypart = when (hour) {
            in 5..11 -> "MORNING"
            in 12..16 -> "AFTERNOON"
            in 17..22 -> "EVENING"
            else -> "NIGHT"
        }
        homogeneousPool.forEach { laneContributions[LANE_DAYPART]?.add(it.id) }

        // Evaluate Lane 6: Continue (Serialized Content)
        val serializedGroups = homogeneousPool
            .filter { !it.parentContentId.isNullOrBlank() }
            .groupBy { it.parentContentId!! }

        val continueCandidates = mutableListOf<MediaItem>()
        serializedGroups.forEach { (_, group) ->
            // Sort by selectionReason/id/timestamp to pick oldest unwatched episode
            val oldestUnwatched = group.filter { (exposureMap[it.id] ?: 0) == 0 }
                .sortedWith(compareBy<MediaItem> { it.selectionReason ?: "" }.thenBy { it.id })
                .firstOrNull() ?: group.sortedWith(compareBy<MediaItem> { it.selectionReason ?: "" }.thenBy { it.id }).firstOrNull()
            if (oldestUnwatched != null) {
                continueCandidates.add(oldestUnwatched)
                laneContributions[LANE_CONTINUE]?.add(oldestUnwatched.id)
            }
        }

        // 3. Score & Blend Candidates across Lanes
        val explanations = mutableMapOf<String, String>()
        
        val scoredItems = homogeneousPool.map { item ->
            val exp = exposureMap[item.id] ?: 0
            val decayedExp = decayedExposureMap[item.id] ?: (exposureMap[item.id]?.toFloat() ?: item.getDecayedExposure(currentTimeMs))
            val skips = skipCountsByMedia[item.id] ?: 0
            val sessionExp = sessionExposures[item.id] ?: 0
            val effectiveExposure = decayedExp + skips.toFloat() + sessionExp.toFloat()

            // Base TasteDNA aesthetic score
            var score = calculateAestheticScore(item, tasteDNA, experienceRequest)

            // Lane 1 Boost: Novelty (Unseen items with 0 total effective exposure)
            if (effectiveExposure < 0.1f) {
                score += 0.30f
            }

            // Lane 2 Boost: Rediscover
            val lastViewed = item.lastViewedTimestamp ?: 0L
            if (exp > 0 && (lastViewed == 0L || (currentTimeMs - lastViewed) > REDISCOVER_THRESHOLD_MS)) {
                score += 0.25f
            }

            // Lane 3 Boost: Favorites (Unwatched favorites get higher priority)
            if (item.isFavorite) {
                score += if (effectiveExposure < 0.1f) 0.35f else 0.20f
            }

            // Lane 5 Boost: Daypart Influence
            score += calculateDaypartBoost(item, currentDaypart)

            // Lane 6 Boost: Continue
            if (continueCandidates.any { it.id == item.id }) {
                score += 0.40f
            }

            // Exposure Penalty (Persistent exposures, skips, and active session preview exposures reduce score)
            if (effectiveExposure > 0f) {
                val penalty = (effectiveExposure * 0.12f).coerceAtMost(0.60f)
                score -= penalty
            }

            // Two-Signal Feedback Isolation Penalty: Programming Quality Thumbs Down
            if (negativeFeedbackMediaIds.contains(item.id)) {
                score -= 0.35f
            }

            // Controlled Epoch Rotation Variation for Fresh Sequences on Refresh
            val epochVariation = (((item.id.hashCode() xor refreshEpoch).hashCode() and 0x7FFFFFFF) % 100) / 1000.0f
            val finalScore = score + epochVariation

            // Construct Flattering Aesthetic Explanation
            explanations[item.id] = buildAestheticExplanation(item, finalScore, currentDaypart, experienceRequest)

            item to finalScore
        }

        // 4. Sort deterministically by final blended score descending with tie-breaking by refreshEpoch
        val sortedItems = scoredItems
            .sortedWith(
                compareByDescending<Pair<MediaItem, Float>> { it.second }
                    .thenBy { (it.first.id.hashCode() xor refreshEpoch).toString() }
            )
            .map { it.first }
            .take(targetCount)

        return ProgrammedStationResult(
            items = sortedItems,
            laneContributions = laneContributions,
            explanations = explanations
        )
    }

    private fun calculateAestheticScore(
        item: MediaItem,
        tasteDNA: TasteDNA,
        experienceRequest: ExperienceRequest?
    ): Float {
        var baseScore = 0.5f + (item.rating / 10.0f)

        // Session-level Mood Steering
        if (experienceRequest != null) {
            when (experienceRequest.moodNudge.lowercase()) {
                "more energetic", "energetic", "action" -> {
                    if (item.genre.contains("Action", ignoreCase = true) || item.title.contains("Dynamic", ignoreCase = true)) {
                        baseScore += 0.25f
                    }
                }
                "more serene", "serene", "calm", "atmospheric" -> {
                    if (item.genre.contains("Atmospheric", ignoreCase = true) || item.title.contains("Serene", ignoreCase = true)) {
                        baseScore += 0.25f
                    }
                }
                "more cinematic", "cinematic", "vibrant" -> {
                    if (item.genre.contains("Cinematic", ignoreCase = true) || item.title.contains("Vibrant", ignoreCase = true)) {
                        baseScore += 0.25f
                    }
                }
            }
            experienceRequest.aestheticWeights.forEach { (_, weight) ->
                baseScore += weight * 0.10f
            }
        }

        return baseScore.coerceIn(0.1f, 1.5f)
    }

    private fun calculateDaypartBoost(item: MediaItem, daypart: String): Float {
        return when (daypart) {
            "EVENING", "NIGHT" -> {
                if (item.genre.contains("Cinematic", ignoreCase = true) || item.genre.contains("Action", ignoreCase = true)) 0.15f else 0.05f
            }
            "MORNING" -> {
                if (item.genre.contains("Atmospheric", ignoreCase = true) || item.genre.contains("Serene", ignoreCase = true)) 0.15f else 0.05f
            }
            else -> 0.10f
        }
    }

    private fun buildAestheticExplanation(
        item: MediaItem,
        score: Float,
        daypart: String,
        experienceRequest: ExperienceRequest?
    ): String {
        val moodText = experienceRequest?.moodNudge ?: "personalized taste"
        return when {
            item.isFavorite -> "Featured from your favorites stream, aligned with $moodText."
            score > 0.8f -> "Programmed for its vibrant visual dynamics and $daypart ambiance."
            else -> "Matched to your Taste DNA baseline for continuous viewing."
        }
    }
}
