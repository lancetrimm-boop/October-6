package com.example.data.semantic

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer

/**
 * Concrete implementation of [EmbeddingProvider] for the MobileCLIP-S0 image encoder.
 *
 * Provides:
 * - 512-dimensional visual embeddings
 * - NCHW image preprocessing [1, 3, 256, 256]
 * - 85% center-crop before resize (improves subject focus for photos & video frames)
 * - Pixel scaling to [0.0, 1.0]
 * - L2 unit normalization
 */
class MobileCLIPEmbeddingProvider(
    private val engine: MobileCLIPInferenceEngine
) : EmbeddingProvider {

    override val descriptor: EmbeddingModelDescriptor = EmbeddingModelDescriptor(
        modelId = "mobileclip-s0",
        modelVersion = 1,
        dimensionality = 512,
        primaryType = SemanticRepresentationType.VISUAL,
        runtimeFormat = ModelRuntimeFormat.ONNX,
        quantization = QuantizationType.NONE_FP32,
        artifactHash = "sha256:mobileclip-s0-v1"
    )

    override val supportedTypes: Set<SemanticRepresentationType> = setOf(
        SemanticRepresentationType.VISUAL
    )

    override fun isReady(): Boolean = engine.isLoaded()

    companion object {
        private val globalInferenceSemaphore = kotlinx.coroutines.sync.Semaphore(1)
        /** Fraction of the shorter side retained for center crop before model resize. */
        private const val CENTER_CROP_FRACTION = 0.85f
    }

    override suspend fun generateEmbedding(
        mediaId: String,
        input: SemanticInput,
        sourceDataHash: String
    ): EmbeddingResult = withContext(Dispatchers.Default) {
        if (!isReady()) {
            return@withContext EmbeddingResult.Failure(
                EmbeddingErrorCode.MODEL_UNAVAILABLE,
                "MobileCLIP inference engine not ready."
            )
        }

        val bitmap = when (input) {
            is SemanticInput.ExplicitBitmap -> input.bitmap
            else -> return@withContext EmbeddingResult.Failure(
                EmbeddingErrorCode.UNSUPPORTED_TYPE,
                "MobileCLIP provider only supports ExplicitBitmap input in this phase."
            )
        }

        return@withContext try {
            globalInferenceSemaphore.withPermit {
                android.util.Log.i("QUERY_EMBEDDING", "CLIP_IMAGE_INFER_START: mediaId=$mediaId bitmap=${bitmap.width}x${bitmap.height}")
                // 1. Preprocess: 85% center-crop, resize, convert to NCHW FloatBuffer
                val processedBuffer = preprocessBitmap(bitmap)

                // 2. Inference
                val rawEmbedding = engine.infer(processedBuffer, 256, 256)

                // 3. L2 Normalization
                val normalizedEmbedding = VectorMath.l2Normalize(rawEmbedding)
                android.util.Log.i("QUERY_EMBEDDING", "CLIP_IMAGE_INFER_SUCCESS: dim=${normalizedEmbedding.size}")

                // 4. Construct Representation
                val representationId = "sem_${mediaId}_visual_${descriptor.modelId}_v${descriptor.modelVersion}"
                val representation = SemanticRepresentation(
                    id = representationId,
                    mediaId = mediaId,
                    type = SemanticRepresentationType.VISUAL,
                    modelDescriptor = descriptor,
                    dimensionality = descriptor.dimensionality,
                    vector = normalizedEmbedding,
                    sourceDataHash = sourceDataHash,
                    confidence = 1.0f
                )

                EmbeddingResult.Success(representation)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EmbeddingResult.Failure(
                EmbeddingErrorCode.INFERENCE_ERROR,
                "Failed to generate MobileCLIP embedding: ${e.message}",
                e
            )
        }
    }

    private fun preprocessBitmap(bitmap: Bitmap): FloatBuffer {
        val targetSize = 256

        // 85% center-crop: keep the central region to focus on subject (photos + video frames)
        val cropSide = (minOf(bitmap.width, bitmap.height) * CENTER_CROP_FRACTION).toInt().coerceAtLeast(1)
        val cropX = ((bitmap.width - cropSide) / 2).coerceAtLeast(0)
        val cropY = ((bitmap.height - cropSide) / 2).coerceAtLeast(0)
        val cropped = try {
            Bitmap.createBitmap(bitmap, cropX, cropY, cropSide, cropSide)
        } catch (_: Exception) {
            bitmap
        }

        val scaledBitmap = if (cropped.width != targetSize || cropped.height != targetSize) {
            Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)
        } else {
            cropped
        }

        val totalPixels = targetSize * targetSize
        val pixels = IntArray(totalPixels)
        scaledBitmap.getPixels(pixels, 0, targetSize, 0, 0, targetSize, targetSize)

        val buffer = FloatBuffer.allocate(3 * totalPixels)
        val backingArray = buffer.array()
        
        val gOffset = totalPixels
        val bOffset = 2 * totalPixels

        for (i in 0 until totalPixels) {
            val px = pixels[i]
            backingArray[i] = ((px shr 16) and 0xFF) / 255.0f
            backingArray[gOffset + i] = ((px shr 8) and 0xFF) / 255.0f
            backingArray[bOffset + i] = (px and 0xFF) / 255.0f
        }

        if (scaledBitmap !== bitmap && scaledBitmap !== cropped) {
            scaledBitmap.recycle()
        }
        if (cropped !== bitmap) {
            cropped.recycle()
        }

        buffer.rewind()
        return buffer
    }

    override fun close() {
        engine.close()
    }
}
