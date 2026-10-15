package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Room Data Access Object for persisted semantic representations.
 */
@Dao
interface SemanticRepresentationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(representation: SemanticRepresentationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(representations: List<SemanticRepresentationEntity>)

    @Update
    suspend fun update(representation: SemanticRepresentationEntity)

    @Query("SELECT * FROM semantic_representations WHERE id = :id")
    suspend fun getById(id: String): SemanticRepresentationEntity?

    @Query("SELECT * FROM semantic_representations WHERE mediaId = :mediaId")
    suspend fun getForMedia(mediaId: String): List<SemanticRepresentationEntity>

    @Query("SELECT * FROM semantic_representations WHERE mediaId = :mediaId")
    fun observeForMedia(mediaId: String): Flow<List<SemanticRepresentationEntity>>

    @Query("SELECT * FROM semantic_representations WHERE representationType = :type AND modelId = :modelId AND modelVersion = :version")
    suspend fun getCompatibleRepresentations(
        type: String,
        modelId: String,
        version: Int
    ): List<SemanticRepresentationEntity>

    @Query("SELECT * FROM semantic_representations WHERE representationType = :type AND modelId = :modelId AND modelVersion = :version")
    fun observeCompatibleRepresentations(
        type: String,
        modelId: String,
        version: Int
    ): Flow<List<SemanticRepresentationEntity>>

    @Query("SELECT * FROM semantic_representations WHERE mediaId = :mediaId AND representationType = :type AND modelId = :modelId AND modelVersion = :version LIMIT 1")
    suspend fun getSpecificRepresentation(
        mediaId: String,
        type: String,
        modelId: String,
        version: Int
    ): SemanticRepresentationEntity?

    @Query("SELECT * FROM semantic_representations WHERE modelId = :modelId AND modelVersion = :version")
    suspend fun getByModel(modelId: String, version: Int): List<SemanticRepresentationEntity>

    @Query("SELECT * FROM semantic_representations WHERE representationType = :type")
    suspend fun getByType(type: String): List<SemanticRepresentationEntity>

    @Query("SELECT COUNT(*) FROM semantic_representations")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM semantic_representations WHERE representationType = :type AND modelId = :modelId AND modelVersion = :version")
    suspend fun countCompatible(type: String, modelId: String, version: Int): Int

    @Query("""
        SELECT COUNT(DISTINCT m.id) 
        FROM media_items m 
        JOIN semantic_representations s ON m.id = s.mediaId 
        WHERE m.isDeleted = 0 
          AND m.compatibilityStatus NOT IN ('CORRUPT', 'UNSUPPORTED', 'DELETED')
          AND s.representationType = :type 
          AND s.modelId = :modelId 
          AND s.modelVersion = :version 
          AND s.dimensionality = :dim 
          AND length(s.vectorData) = :expectedByteLength
    """)
    suspend fun countVerifiedVisual(
        type: String = "VISUAL",
        modelId: String = "mobileclip-s0",
        version: Int = 1,
        dim: Int = 512,
        expectedByteLength: Int = 2048
    ): Int

    @Query("""
        SELECT COUNT(DISTINCT m.id) 
        FROM media_items m 
        JOIN semantic_representations s ON m.id = s.mediaId 
        WHERE m.isDeleted = 0 
          AND m.compatibilityStatus NOT IN ('CORRUPT', 'UNSUPPORTED', 'DELETED')
          AND s.representationType = :type 
          AND s.dimensionality = :dim 
          AND length(s.vectorData) = :expectedByteLength
    """)
    fun observeVerifiedVisualCount(
        type: String = "VISUAL",
        dim: Int = 512,
        expectedByteLength: Int = 2048
    ): Flow<Int>

    @Query("SELECT EXISTS(SELECT 1 FROM semantic_representations WHERE mediaId = :mediaId AND representationType = :type AND modelId = :modelId AND modelVersion = :version)")
    suspend fun exists(mediaId: String, type: String, modelId: String, version: Int): Boolean

    @Query("DELETE FROM semantic_representations WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM semantic_representations WHERE mediaId = :mediaId")
    suspend fun deleteForMedia(mediaId: String)

    @Query("DELETE FROM semantic_representations WHERE modelId = :modelId AND modelVersion = :version")
    suspend fun deleteByModel(modelId: String, version: Int)

    @Query("DELETE FROM semantic_representations")
    suspend fun clearAll()

    // --- VIDEO FRAME REPRESENTATIONS (Stage 8) ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFrame(frame: VideoFrameRepresentationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAllFrames(frames: List<VideoFrameRepresentationEntity>)

    @Query("SELECT * FROM video_frame_representations WHERE mediaId = :mediaId")
    suspend fun getFramesForMedia(mediaId: String): List<VideoFrameRepresentationEntity>

    @Query("SELECT * FROM video_frame_representations WHERE mediaId IN (:mediaIds)")
    suspend fun getFramesForBatch(mediaIds: List<String>): List<VideoFrameRepresentationEntity>

    @Query("DELETE FROM video_frame_representations WHERE mediaId = :mediaId")
    suspend fun deleteFramesForMedia(mediaId: String)

    @Query("DELETE FROM video_frame_representations")
    suspend fun clearAllFrames()
}
