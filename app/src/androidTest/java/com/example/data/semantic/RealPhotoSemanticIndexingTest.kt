package com.example.data.semantic

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.CompatibilityStatus
import com.example.data.MediaItem
import com.example.data.db.AuraDatabase
import com.example.util.ModelAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RealPhotoSemanticIndexingTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: AuraDatabase
    private lateinit var semanticRepo: RoomSemanticRepresentationRepository
    private lateinit var visionEngine: OnnxRuntimeMobileCLIPInferenceEngine
    private lateinit var textEngine: OnnxRuntimeMobileCLIPTextInferenceEngine
    private lateinit var visionProvider: TinyCLIPEmbeddingProvider
    private lateinit var textProvider: TinyCLIPTextEmbeddingProvider
    private lateinit var retriever: DefaultSemanticCandidateRetriever
    private lateinit var searchService: DefaultSemanticSearchService
    private lateinit var indexingService: DefaultVisualIndexingService

    companion object {
        private const val TAG = "RealPhotoSemanticTest"
        private const val DEMO_PHOTOS_DIR = "/sdcard/Pictures/AuraDemoMedia/photos"
    }

    @Before
    fun setUp() {
        // 1. Isolated in-memory database
        database = Room.inMemoryDatabaseBuilder(context, AuraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        semanticRepo = RoomSemanticRepresentationRepository(database.semanticRepresentationDao())

        // 2. Load production ONNX models
        val visionPath = ModelAssetLoader.getLocalPath(context, "models/tinyclip_vision.onnx")
        val textPath = ModelAssetLoader.getLocalPath(context, "models/tinyclip_text.onnx")

        visionEngine = OnnxRuntimeMobileCLIPInferenceEngine(modelPath = visionPath)
        textEngine = OnnxRuntimeMobileCLIPTextInferenceEngine(modelPath = textPath)

        visionProvider = TinyCLIPEmbeddingProvider(visionEngine)

        val vocabJson = context.assets.open("models/mobileclip_vocab.json").use { it.bufferedReader().readText() }
        val mergesTxt = context.assets.open("models/mobileclip_merges.txt").use { it.bufferedReader().readText() }
        val tokenizer = ClipBpeTokenizer.fromAssets(vocabJson, mergesTxt)
        textProvider = TinyCLIPTextEmbeddingProvider(textEngine, tokenizer)

        retriever = DefaultSemanticCandidateRetriever(semanticRepo)
        searchService = DefaultSemanticSearchService(visionProvider, retriever)
        indexingService = DefaultVisualIndexingService(
            visualProvider = visionProvider,
            candidateRetriever = retriever,
            repository = semanticRepo,
            dispatcher = Dispatchers.Main
        )
    }

    @After
    fun tearDown() {
        try { visionProvider.close() } catch (_: Exception) {}
        try { textProvider.close() } catch (_: Exception) {}
        try { database.close() } catch (_: Exception) {}
    }

    private fun discoverDemoPhotos(): List<MediaItem> {
        val discoveredItems = mutableListOf<MediaItem>()
        val demoDir = File(DEMO_PHOTOS_DIR)

        if (!demoDir.exists() || !demoDir.isDirectory) {
            Log.w(TAG, "Demo photos directory not found: $DEMO_PHOTOS_DIR")
            return emptyList()
        }

        val fileList = demoDir.walkTopDown().filter { it.isFile && (it.extension.equals("jpg", true) || it.extension.equals("png", true) || it.extension.equals("webp", true)) }.toList()
        Log.i(TAG, "Discovered ${fileList.size} photo files on disk in $DEMO_PHOTOS_DIR")

        for (file in fileList) {
            var mediaUri = Uri.fromFile(file).toString()
            var mediaId = "file_${file.nameWithoutExtension}"
            var sizeBytes = file.length()

            // Try MediaStore lookup for Content URI if indexed
            val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.SIZE)
            val selection = "${MediaStore.Images.Media.DATA} = ?"
            val selectionArgs = arrayOf(file.absolutePath)

            try {
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                        val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                        val msId = cursor.getLong(idIdx)
                        sizeBytes = cursor.getLong(sizeIdx)
                        mediaUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, msId).toString()
                        mediaId = "ms_img_$msId"
                        Log.i(TAG, "MediaStore matched ${file.name} -> $mediaUri")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaStore query failed for ${file.name}: ${e.message}")
            }

            val item = MediaItem(
                id = mediaId,
                title = file.name,
                uriPath = mediaUri,
                mediaType = "PHOTO",
                sizeBytes = sizeBytes,
                contentHash = "hash_${file.name}_${sizeBytes}",
                compatibilityStatus = CompatibilityStatus.PLAYABLE
            )
            discoveredItems.add(item)
        }

        return discoveredItems
    }

    @Test
    fun testRealPhotoMediaStoreDiscoveryAndIndexing() = runBlocking {
        val demoItems = discoverDemoPhotos()
        assertEquals("Expected exactly 7 deployed demo photos", 7, demoItems.size)

        indexingService.initializeIndex()

        val indexedRepresentations = mutableListOf<SemanticRepresentation>()

        for (item in demoItems) {
            val result = indexingService.indexVisual(context, item)
            assertTrue("Indexing must succeed for real photo ${item.title}", result is EmbeddingResult.Success)

            val rep = (result as EmbeddingResult.Success).representation
            assertEquals(512, rep.dimensionality)
            assertEquals("tinyclip-vit-8m-16", rep.modelDescriptor.modelId)
            assertEquals(item.id, rep.mediaId)

            // Vector finiteness and normalization
            for (v in rep.vector) {
                assertFalse("Vector element must not be NaN", v.isNaN())
                assertFalse("Vector element must not be Infinite", v.isInfinite())
            }
            assertEquals(1.0f, VectorMath.magnitude(rep.vector), 1e-4f)

            // Verify persistence in Room
            val persisted = semanticRepo.getSpecificRepresentation(item.id, SemanticRepresentationType.VISUAL, visionProvider.descriptor)
            assertNotNull("Representation must be persisted in Room for ${item.id}", persisted)
            assertEquals(rep.dimensionality, persisted?.dimensionality)

            indexedRepresentations.add(rep)
        }

        // Re-initialize candidate index to reflect all 7 representations
        indexingService.initializeIndex()
        val totalCandidates = retriever.candidateCount(SemanticRepresentationType.VISUAL)
        assertEquals(7, totalCandidates)

        Log.i(TAG, "SUCCESSFULLY INDEXED ALL 7 REAL DEMO PHOTOS")
    }

    @Test
    fun testRealPhotoCrossImageRetrieval() = runBlocking {
        val demoItems = discoverDemoPhotos()
        assertEquals(7, demoItems.size)

        indexingService.initializeIndex()
        val representations = mutableMapOf<String, SemanticRepresentation>()

        for (item in demoItems) {
            val result = indexingService.indexVisual(context, item)
            assertTrue(result is EmbeddingResult.Success)
            representations[item.title] = (result as EmbeddingResult.Success).representation
        }

        indexingService.initializeIndex()

        // Pick golden dog photo as query
        val queryTitle = "demo_dog_golden_01.jpg"
        val queryRep = representations[queryTitle]
        assertNotNull("Query representation for $queryTitle must exist", queryRep)

        val searchResult = searchService.search(
            queryVector = queryRep!!.vector,
            queryLabel = queryTitle,
            topK = 7,
            targetType = SemanticRepresentationType.VISUAL,
            expectedDescriptor = visionProvider.descriptor
        )

        assertTrue(searchResult.isSuccess)
        assertTrue(searchResult.hasMatches)
        assertEquals(7, searchResult.candidates.size)

        Log.i(TAG, "=== CROSS-IMAGE RETRIEVAL RANKINGS (Query: $queryTitle) ===")
        searchResult.candidates.forEachIndexed { rank, candidate ->
            val matchedItem = demoItems.find { it.id == candidate.mediaId }
            Log.i(TAG, "Rank ${rank + 1}: ${matchedItem?.title ?: candidate.mediaId} | Score: ${candidate.score}")
        }

        // Self-match must be rank 1 with score ~1.0
        val topCandidate = searchResult.candidates.first()
        val queryMediaItem = demoItems.find { it.title == queryTitle }
        assertEquals(queryMediaItem?.id, topCandidate.mediaId)
        assertEquals(1.0f, topCandidate.score, 1e-3f)
    }

    @Test
    fun testRealPhotoTextToImageRetrieval() = runBlocking {
        val demoItems = discoverDemoPhotos()
        assertEquals(7, demoItems.size)

        indexingService.initializeIndex()
        for (item in demoItems) {
            val result = indexingService.indexVisual(context, item)
            assertTrue(result is EmbeddingResult.Success)
        }
        indexingService.initializeIndex()

        val textQueries = listOf(
            "golden dog playing",
            "red sports car",
            "sunset over beach",
            "colorful parrot"
        )

        Log.i(TAG, "=== TEXT-TO-IMAGE RETRIEVAL RANKINGS ===")

        for (queryText in textQueries) {
            val textResult = textProvider.generateEmbedding("query_txt", SemanticInput.Text(queryText), "hash_txt")
            assertTrue("Text embedding generation must succeed for '$queryText'", textResult is EmbeddingResult.Success)
            val textRep = (textResult as EmbeddingResult.Success).representation

            val searchResult = searchService.search(
                queryVector = textRep.vector,
                queryLabel = queryText,
                topK = 7,
                targetType = SemanticRepresentationType.VISUAL,
                expectedDescriptor = visionProvider.descriptor
            )

            assertTrue(searchResult.isSuccess)

            Log.i(TAG, "--- Query: '$queryText' ---")
            searchResult.candidates.forEachIndexed { rank, candidate ->
                val matchedItem = demoItems.find { it.id == candidate.mediaId }
                Log.i(TAG, "Rank ${rank + 1}: ${matchedItem?.title ?: candidate.mediaId} | Score: ${candidate.score}")
            }
        }
    }

    @Test
    fun testNegativeCases_UnreadableImageAndIncompatibleDescriptor() = runBlocking {
        // 1. Unreadable image
        val unreadableItem = MediaItem(
            id = "unreadable_001",
            title = "non_existent.jpg",
            uriPath = "/sdcard/Pictures/non_existent_file_12345.jpg",
            mediaType = "PHOTO",
            compatibilityStatus = CompatibilityStatus.PLAYABLE
        )

        val indexResult = indexingService.indexVisual(context, unreadableItem)
        assertTrue("Indexing unreadable file must return Failure", indexResult is EmbeddingResult.Failure)
        val failure = indexResult as EmbeddingResult.Failure
        assertEquals(EmbeddingErrorCode.INVALID_INPUT, failure.errorCode)

        // 2. Incompatible Descriptor Rejection in Search
        val incompatibleDesc = EmbeddingModelDescriptor(
            modelId = "mobileclip-s0",
            modelVersion = 1,
            dimensionality = 512,
            primaryType = SemanticRepresentationType.VISUAL
        )

        val searchResult = searchService.search(
            queryVector = FloatArray(512) { 0.1f },
            queryLabel = "Incompatible Test",
            topK = 5,
            targetType = SemanticRepresentationType.VISUAL,
            expectedDescriptor = incompatibleDesc
        )

        assertFalse("Search with incompatible descriptor must fail", searchResult.isSuccess)
        assertTrue("Candidate list must be empty on descriptor rejection", searchResult.candidates.isEmpty())
        assertNotNull("Error message must describe descriptor mismatch", searchResult.errorMessage)
    }
}
