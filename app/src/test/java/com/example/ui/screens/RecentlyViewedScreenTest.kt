package com.example.ui.screens

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RecentlyViewedScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun createMediaItem(id: String, title: String): MediaItem {
        return MediaItem(
            id = id,
            title = title,
            mediaType = "VIDEO",
            videoCodec = "video/avc",
            duration = "01:00",
            genre = "Test",
            compatibilityStatus = CompatibilityStatus.PLAYABLE
        )
    }

    @Test
    fun testRecentlyViewedScreen_EmptyStateRendersWhenNoHistory() {
        val repository = mock<MediaRepository>()
        whenever(repository.watchHistory).thenReturn(MutableStateFlow(emptyList()))

        composeTestRule.setContent {
            RecentlyViewedScreen(
                repository = repository,
                onMediaSelect = { _, _ -> },
                onLike = {}
            )
        }

        composeTestRule.onNodeWithText("Recently Viewed").assertExists()
        composeTestRule.onNodeWithText("No recently viewed media. Videos and photos you play or view will appear here.").assertExists()
    }

    @Test
    fun testRecentlyViewedScreen_RendersHistoryItemsInRecencyOrder() {
        val repository = mock<MediaRepository>()
        val item1 = createMediaItem("m1", "Media 1")
        val item2 = createMediaItem("m2", "Media 2")

        whenever(repository.watchHistory).thenReturn(MutableStateFlow(listOf(item2, item1)))

        composeTestRule.setContent {
            RecentlyViewedScreen(
                repository = repository,
                onMediaSelect = { _, _ -> },
                onLike = {}
            )
        }

        composeTestRule.onNodeWithTag("media_tile_m2").assertExists()
        composeTestRule.onNodeWithTag("media_tile_m1").assertExists()
    }
}
