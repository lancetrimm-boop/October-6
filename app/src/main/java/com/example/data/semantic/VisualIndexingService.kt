package com.example.data.semantic

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.data.MediaItem
import com.example.util.MediaThumbnailFetcher
import kotlinx.coroutines.*
import java.io.File

/**
 * Service responsible for generating and persisting visual embeddings for MediaItems.
 */
interface VisualIndexingService {
    suspend fun indexVisual(context: Context, item: MediaItem): EmbeddingResult
    suspend fun initializeIndex()
}

class DefaultVisualIndexingService(
    private val visualProvider: EmbeddingProvider,
    private val candidateRetriever: SemanticCandidateRetriever,
    private val repository: SemanticRepresentationRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : VisualIndexingService {

    private val inFlightJobs = java.util.concurrent.ConcurrentHashMap<String, Deferred<EmbeddingResult>>()

    companion object {
        /** Version 6: Full-resolution photo decode reindex. */
        const val VISUAL_INDEX_VERSION = 5
        const val IMAGE_INDEX_VERSION = 6

        /** Match TinyCLIP / MobileCLIP input size to avoid full-res decode + downscale. */
        private const val INFERENCE_FRAME_SIZE = 224
    }

    override suspend fun initializeIndex() {
        candidateRetriever.initializeIndex(
            type = SemanticRepresentationType.VISUAL,
            descriptor = visualProvider.descriptor
        )
    }

    override suspend fun indexVisual(context: Context, item: MediaItem): EmbeddingResult = withContext(dispatcher) {
        val mediaId = item.id
        val newDeferred = CompletableDeferred<EmbeddingResult>()
        val existing = inFlightJobs.putIfAbsent(mediaId, newDeferred)

        if (existing != null) {
            return@withContext try {
                existing.await()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                EmbeddingResult.Failure(
                    EmbeddingErrorCode.INFERENCE_ERROR,
                    e.message ?: "Execution failed"
                )
            }
        }

        try {
            val result = executeIndexVisualInternal(context, item)
            newDeferred.complete(result)
            result
        } catch (e: Throwable) {
            newDeferred.completeExceptionally(e)
            if (e is CancellationException) throw e
            EmbeddingResult.Failure(
                EmbeddingErrorCode.INFERENCE_ERROR,
                e.message ?: "Execution failed"
            )
        } finally {
            inFlightJobs.remove(mediaId, newDeferred)
        }
    }

    private suspend fun executeIndexVisualInternal(context: Context, item: MediaItem): EmbeddingResult {
        return try {
            val descriptor = visualProvider.descriptor
            val isVideo = item.mediaType.equals("VIDEO", ignoreCase = true) ||
                item.mediaType.equals("MOVIE", ignoreCase = true) ||
                item.mediaType.startsWith("VIDEO", ignoreCase = true)
            val targetVersion = if (isVideo) VISUAL_INDEX_VERSION else IMAGE_INDEX_VERSION

            val sourceHash = item.contentHash ?: "v1_${item.id}_${item.sizeBytes}"

            Log.i("VisualIndexing", "START_INDEXING: mediaId=${item.id} type=${item.mediaType} version=$targetVersion")

            val existing = repository.getSpecificRepresentation(
                mediaId = item.id,
                type = SemanticRepresentationType.VISUAL,
                descriptor = descriptor
            )

            if (existing != null &&
                existing.sourceDataHash == sourceHash &&
                existing.documentVersion == targetVersion) {

                Log.i("VisualIndexing", "INDEX_REUSE: mediaId=${item.id}")
                candidateRetriever.onRepresentationAdded(existing)
                return EmbeddingResult.Success(existing)
            }

            val representation = if (isVideo) {
                indexVideoMultiFrame(context, item, sourceHash)
            } else {
                indexImageSingleFrame(context, item, sourceHash, targetVersion)
            }

            val dbWriteStart = System.nanoTime()
            if (representation != null) {
                repository.saveRepresentation(representation)
                candidateRetriever.onRepresentationAdded(representation)
                val dbWriteMs = (System.nanoTime() - dbWriteStart) / 1_000_000

                val strategy = if (isVideo) "ADAPTIVE_SCENE_AWARE" else "FULL_IMAGE"
                Log.i("VisualIndexingPerf", "PERF_DB_WRITE: token=${item.id.hashCode()} dbWriteMs=$dbWriteMs strategy=$strategy")
                Log.i("VisualIndexing", "INDEX_SUCCESS: mediaId=${item.id} dim=${representation.dimensionality} strategy=$strategy")
                EmbeddingResult.Success(representation)
            } else {
                Log.w("VisualIndexing", "INDEX_FAILURE_EXTRACTION: mediaId=${item.id}")
                EmbeddingResult.Failure(
                    EmbeddingErrorCode.INVALID_INPUT,
                    "Failed to extract visual content for media: ${item.id}"
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("VisualIndexing", "INDEX_ERROR: mediaId=${item.id} error=${e.message}", e)
            EmbeddingResult.Failure(
                errorCode = EmbeddingErrorCode.INFERENCE_ERROR,
                message = "Visual indexing failed: ${e.message}",
                cause = e
            )
        }
    }

    private suspend fun indexImageSingleFrame(
        context: Context,
        item: MediaItem,
        sourceHash: String,
        version: Int
    ): SemanticRepresentation? {
        val uri = item.uriPath.ifEmpty { item.imageUrl }
        val decodeStart = System.nanoTime()
        // Prefer full-image decode for photos; fall back to thumbnail path
        val bitmap = MediaThumbnailFetcher.loadFullImageForEmbedding(context, uri)
            ?: MediaThumbnailFetcher.getThumbnail(context, uri)
            ?: return null
        val decodeMs = (System.nanoTime() - decodeStart) / 1_000_000

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val thermalStatus = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            powerManager?.currentThermalStatus ?: -1
        } else {
            -1
        }
        val nativeHeap = android.os.Debug.getNativeHeapAllocatedSize()

        Log.i("VisualIndexingPerf", "PERF_PHOTO_DECODE: token=${item.id.hashCode()} decodeMs=$decodeMs thermal=$thermalStatus nativeHeapBytes=$nativeHeap")

        val result = visualProvider.generateEmbedding(
            mediaId = item.id,
            input = SemanticInput.ExplicitBitmap(bitmap),
            sourceDataHash = sourceHash
        )
        return (result as? EmbeddingResult.Success)?.representation?.copy(documentVersion = version)
    }

    private suspend fun indexVideoMultiFrame(context: Context, item: MediaItem, sourceHash: String): SemanticRepresentation? = withContext(dispatcher) {
        val videoStart = System.nanoTime()
        val durationMs = if (item.durationMs > 0) item.durationMs else VideoFrameSampler.getVideoDuration(context, item.uriPath)
        
        val samplerStart = System.nanoTime()
        val samplingResult = VideoFrameSampler.getSampleTimestampsWithTiming(durationMs, context, item.uriPath)
        val timestamps = samplingResult.timestamps
        val analysisDecodeMs = samplingResult.analysisDecodeMs
        val samplerMs = (System.nanoTime() - samplerStart) / 1_000_000

        val frames = mutableListOf<VideoFrameRepresentation>()
        val retriever = MediaMetadataRetriever()
        var inferenceDecodeMs = 0L
        try {
            val uri = if (item.uriPath.startsWith("/")) Uri.fromFile(File(item.uriPath)) else Uri.parse(item.uriPath)
            if (item.uriPath.startsWith("content://") || item.uriPath.startsWith("file://") || item.uriPath.startsWith("/")) {
                retriever.setDataSource(context, uri)
            } else {
                retriever.setDataSource(item.uriPath)
            }
            val rotStr = try { retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) } catch (_: Exception) { null }
            val rotation = rotStr?.toIntOrNull() ?: 0

            for (timeUs in timestamps) {
                val frameDecodeStart = System.nanoTime()
                // Request model input size directly (224 for TinyCLIP) to avoid
                // full-resolution decode + later downscale in the provider.
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        INFERENCE_FRAME_SIZE,
                        INFERENCE_FRAME_SIZE
                    )
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let {
                        Bitmap.createScaledBitmap(it, INFERENCE_FRAME_SIZE, INFERENCE_FRAME_SIZE, true).also { scaled ->
                            if (scaled !== it) it.recycle()
                        }
                    }
                }
                inferenceDecodeMs += (System.nanoTime() - frameDecodeStart) / 1_000_000

                if (bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
                    val validBitmap = if (rotation != 0) com.example.util.MediaOrientationUtils.rotateBitmapIfNeeded(bitmap, rotation) else bitmap
                    val result = visualProvider.generateEmbedding(
                        mediaId = item.id,
                        input = SemanticInput.ExplicitBitmap(validBitmap),
                        sourceDataHash = sourceHash
                    )
                    if (result is EmbeddingResult.Success) {
                        val rep = result.representation
                        val frameId = "sem_frame_${item.id}_${timeUs}_${visualProvider.descriptor.modelId}_v${visualProvider.descriptor.modelVersion}"
                        frames.add(VideoFrameRepresentation(
                            id = frameId,
                            mediaId = item.id,
                            timestampUs = timeUs,
                            modelDescriptor = visualProvider.descriptor,
                            documentVersion = VISUAL_INDEX_VERSION,
                            dimensionality = visualProvider.descriptor.dimensionality,
                            vector = rep.vector
                        ))
                    }
                    if (validBitmap !== bitmap && !bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("VisualIndexing", "Multi-frame video extraction failed for ${item.id}: ${e.message}")
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        val dbSaveStart = System.nanoTime()
        if (frames.isNotEmpty()) {
            repository.deleteFramesForMedia(item.id)
            repository.saveFrames(frames)
        }
        val dbSaveMs = (System.nanoTime() - dbSaveStart) / 1_000_000
        val videoTotalMs = (System.nanoTime() - videoStart) / 1_000_000
        Log.i("VisualIndexingPerf", "PERF_VIDEO_SUMMARY: token=${item.id.hashCode()} durationMs=$durationMs sampleCount=${timestamps.size} samplerMs=$samplerMs analysisDecodeMs=$analysisDecodeMs inferenceDecodeMs=$inferenceDecodeMs dbSaveMs=$dbSaveMs videoTotalMs=$videoTotalMs")

        if (frames.isEmpty()) return@withContext null

        val frameVectors = frames.map { it.vector }
        // Max-pool across frames then L2-normalize
        val aggregatedVector = VectorMath.maxPool(frameVectors)
        val normalizedVector = VectorMath.l2Normalize(aggregatedVector)

        return@withContext SemanticRepresentation(
            id = "sem_${item.id}_visual_${visualProvider.descriptor.modelId}_v${visualProvider.descriptor.modelVersion}",
            mediaId = item.id,
            type = SemanticRepresentationType.VISUAL,
            modelDescriptor = visualProvider.descriptor,
            documentVersion = VISUAL_INDEX_VERSION,
            dimensionality = visualProvider.descriptor.dimensionality,
            vector = normalizedVector,
            sourceDataHash = sourceHash,
            confidence = 1.0f
        )
    }
}
