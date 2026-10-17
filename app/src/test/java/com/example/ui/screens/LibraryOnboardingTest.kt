package com.example.ui.screens

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.*
import com.example.data.cleanup.DeletionState
import com.example.data.semantic.SearchRequest
import com.example.ui.models.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibraryOnboardingTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testLibraryOnboarding_RendersTargetCopyAndSingleScanDeviceButton() {
        val emptyState = LibraryPresentationState(
            mediaItems = emptyList(),
            selectedFilter = "ALL",
            activeCategory = SortCategory.STANDARD,
            standardSort = StandardSortOption.NEWEST_FIRST,
            intelligentSort = IntelligentSortOption.PERSONALIZED,
            searchRequest = SearchRequest.Text(""),
            activeVisualReferences = emptyList()
        )

        composeTestRule.setContent {
            LibraryContent(
                state = emptyState,
                latestSortedItems = emptyList(),
                mediaItemsMap = emptyMap(),
                onFilterChange = {},
                onCategoryChange = {},
                onStandardSortChange = {},
                onIntelligentSortChange = {},
                onSearchQueryChange = {},
                onSyncClick = {},
                onAutoScrollSpeedChange = {},
                onGridDensityChange = {},
                onMediaSelect = { _, _ -> },
                onCompareLaunch = {},
                onFavoriteToggle = {},
                onImportUris = {},
                onTriggerScan = {},
                onRefresh = {},
                onSafeDeleteRequest = { _, _ -> },
                onLike = {},
                onAddVisualReference = {},
                onRemoveVisualReference = {},
                onClearSearch = {},
                onSearchByImage = { _, _ -> },
                onSearchByMultipleImages = {},
                deletionStateFlow = MutableStateFlow(DeletionState.IDLE),
                libraryScrollIndex = 0,
                libraryScrollOffset = 0,
                onScrollPositionChange = { _, _ -> },
                onLoadNextPage = {},
                onRetryFailedEnrichment = {}
            )
        }

        // Assert target copy
        composeTestRule.onNodeWithText("Meet your media library").assertExists()
        composeTestRule.onNodeWithText("Aura discovers your photos and videos on your device to power personalized playback and visual discovery.").assertExists()

        // Assert single primary action button
        composeTestRule.onNodeWithText("SCAN DEVICE").assertExists()

        // Assert supporting footer copy
        composeTestRule.onNodeWithText("Find your existing photos and videos in one step.").assertExists()
        composeTestRule.onNodeWithText("Your media stays on your device.").assertExists()

        // Assert obsolete Import button is NOT present in onboarding
        composeTestRule.onNodeWithText("IMPORT").assertDoesNotExist()
    }
}
