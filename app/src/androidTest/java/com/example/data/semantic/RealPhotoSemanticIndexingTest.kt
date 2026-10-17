package com.example.data.semantic

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
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
    }

    @Before
    fun setUp() {
        grantStoragePermissions()

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
        searchService = DefaultSemanticSearchService(textProvider, retriever)
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

    private fun grantStoragePermissions() {
        try {
            val pkg = context.packageName
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.executeShellCommand("pm grant $pkg android.permission.READ_MEDIA_IMAGES")
            automation.executeShellCommand("pm grant $pkg android.permission.READ_EXTERNAL_STORAGE")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to grant storage permissions: ${e.message}")
        }
    }

    private fun discoverDemoPhotos(): List<MediaItem> {
        val discoveredItems = mutableListOf<MediaItem>()

        // 1. Try MediaStore discovery
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATA
        )

        val selection = "${MediaStore.Images.Media.DATA} LIKE ?"
        val selectionArgs = arrayOf("%AuraDemoMedia/photos%")

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Images.Media.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)

                while (cursor.moveToNext()) {
                    val msId = cursor.getLong(idIdx)
                    val name = cursor.getString(nameIdx)
                    val size = cursor.getLong(sizeIdx)
                    val dataPath = cursor.getString(dataIdx)
                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, msId).toString()

                    val item = MediaItem(
                        id = "ms_img_$msId",
                        title = name,
                        uriPath = contentUri,
                        mediaType = "PHOTO",
                        sizeBytes = size,
                        contentHash = "hash_${name}_$size",
                        compatibilityStatus = CompatibilityStatus.PLAYABLE
                    )
                    discoveredItems.add(item)
                    Log.i(TAG, "MediaStore discovered photo: $name -> $contentUri (path=$dataPath)")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore query failed: ${e.message}")
        }

        if (discoveredItems.size == 7) {
            return discoveredItems.distinctBy { it.title }
        }

        // 2. Fallback to File system discovery
        Log.i(TAG, "MediaStore returned ${discoveredItems.size} items. Falling back to File system discovery.")
        val possiblePaths = listOf(
            File("/storage/emulated/0/Pictures/AuraDemoMedia/photos"),
            File("/sdcard/Pictures/AuraDemoMedia/photos"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AuraDemoMedia/photos")
        )

        val demoDir = possiblePaths.firstOrNull { it.exists() && it.isDirectory } ?: return emptyList()

        val fileList = demoDir.walkTopDown()
            .filter { it.isFile && (it.extension.equals("jpg", true) || it.extension.equals("png", true) || it.extension.equals("webp", true)) }
            .toList()
            .distinctBy { it.name }

        val fileItems = mutableListOf<MediaItem>()
        for (file in fileList) {
            val fileUri = Uri.fromFile(file).toString()
            val mediaId = "file_${file.nameWithoutExtension}"
            val sizeBytes = file.length()

            val item = MediaItem(
                id = mediaId,
                title = file.name,
                uriPath = fileUri,
                mediaType = "PHOTO",
                sizeBytes = sizeBytes,
                contentHash = "hash_${file.name}_${sizeBytes}",
                compatibilityStatus = CompatibilityStatus.PLAYABLE
            )
            fileItems.add(item)
            Log.i(TAG, "File discovered photo: ${file.name} -> $fileUri (id=$mediaId)")
        }

        return fileItems
    }

    @Test
    fun testRealPhotoMediaStoreDiscoveryAndIndexing() {
        runBlocking {
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
            val totalCandidates = retriever.getIndexSize(SemanticRepresentationType.VISUAL, visionProvider.descriptor)
            assertEquals(7, totalCandidates)

            Log.i(TAG, "SUCCESSFULLY INDEXED ALL 7 REAL DEMO PHOTOS")
        }
    }

    @Test
    fun testRealPhotoCrossImageRetrieval() {
        runBlocking {
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
                Log.i(TAG, "Rank ${rank + 1}: ${matchedItem?.title ?: candidate.mediaId} | Score: ${candidate.similarityScore}")
            }

            // Self-match must be rank 1 with score ~1.0
            val topCandidate = searchResult.candidates.first()
            val queryMediaItem = demoItems.find { it.title == queryTitle }
            assertEquals(queryMediaItem?.id, topCandidate.mediaId)
            assertEquals(1.0f, topCandidate.similarityScore, 1e-3f)
        }
    }

    @Test
    fun testRealPhotoTextToImageRetrieval() {
        runBlocking {
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
                val searchResult = searchService.search(
                    query = queryText,
                    topK = 7,
                    targetType = SemanticRepresentationType.VISUAL,
                    expectedDescriptor = visionProvider.descriptor
                )

                assertTrue("Text search must succeed for '$queryText'", searchResult.isSuccess)

                Log.i(TAG, "--- Query: '$queryText' ---")
                searchResult.candidates.forEachIndexed { rank, candidate ->
                    val matchedItem = demoItems.find { it.id == candidate.mediaId }
                    Log.i(TAG, "Rank ${rank + 1}: ${matchedItem?.title ?: candidate.mediaId} | Score: ${candidate.similarityScore}")
                }
            }
        }
    }

    @Test
    fun testNegativeCases_UnreadableImageAndIncompatibleDescriptor() {
        runBlocking {
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
                query = "sunset over water",
                topK = 5,
                targetType = SemanticRepresentationType.VISUAL,
                expectedDescriptor = incompatibleDesc
            )

            assertFalse("Search with incompatible descriptor must fail", searchResult.isSuccess)
            assertTrue("Candidate list must be empty on descriptor rejection", searchResult.candidates.isEmpty())
            assertNotNull("Error message must describe descriptor mismatch", searchResult.errorMessage)
        }
    }
}
