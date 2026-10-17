package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AuraDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class SaveDrivenLearningTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var repository: MediaRepository
    private lateinit var database: AuraDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AuraDatabase::class.java).allowMainThreadQueries().build()
        repository = MediaRepository(testDispatcher)
        repository.setDatabaseForTesting(database)
    }

    @After
    fun tearDown() {
        try {
            repository.close()
        } catch (_: Exception) {}
        database.close()
    }

    private fun createMediaItem(id: String, title: String): MediaItem {
        return MediaItem(
            id = id,
            title = title,
            mediaType = "VIDEO",
            duration = "01:00",
            genre = "Test",
            compatibilityStatus = CompatibilityStatus.PLAYABLE,
            moodTags = listOf("vibrant", "contrast")
        )
    }

    @Test
    fun testSaveMedia_TriggersSingleLearningSignal_IsIdempotentOnRepeat() = testScope.runTest {
        val item = createMediaItem("m1", "Media 1")
        repository.setMediaItemsForTesting(listOf(item))
        repository.updateTasteDNA(TasteDNA(isFineTuningEnabled = true), isUserGenerated = true, evidenceCategory = "Manual Setup")
        advanceUntilIdle()

        val initialDna = repository.tasteDNA.value

        // First save
        repository.saveMedia("m1")
        advanceUntilIdle()

        assertTrue(repository.isSaved("m1"))
        val dnaAfterFirstSave = repository.tasteDNA.value
        // Verify preference was updated
        assertNotEquals(initialDna, dnaAfterFirstSave)

        // Repeat save (should be idempotent / no duplicate accumulation)
        repository.saveMedia("m1")
        advanceUntilIdle()

        val dnaAfterRepeatSave = repository.tasteDNA.value
        assertEquals(dnaAfterFirstSave, dnaAfterRepeatSave)
    }

    @Test
    fun testRemoveMedia_DoesNotTriggerNegativeReinforcement() = testScope.runTest {
        val item = createMediaItem("m1", "Media 1")
        repository.setMediaItemsForTesting(listOf(item))
        repository.updateTasteDNA(TasteDNA(isFineTuningEnabled = true), isUserGenerated = true, evidenceCategory = "Manual Setup")
        advanceUntilIdle()

        repository.saveMedia("m1")
        advanceUntilIdle()
        val dnaAfterSave = repository.tasteDNA.value

        // Remove media
        repository.removeMedia("m1")
        advanceUntilIdle()

        assertFalse(repository.isSaved("m1"))
        val dnaAfterRemove = repository.tasteDNA.value
        // Removal must not mutate TasteDNA
        assertEquals(dnaAfterSave, dnaAfterRemove)
    }
}
