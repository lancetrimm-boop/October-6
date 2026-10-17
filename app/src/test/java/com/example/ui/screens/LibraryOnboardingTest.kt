package com.example.ui.screens

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibraryOnboardingTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testLibraryOnboarding_RendersTargetCopyAndSingleScanDeviceButton() {
        val repository = mock<MediaRepository>()
        whenever(repository.mediaItems).thenReturn(MutableStateFlow(emptyList()))
        whenever(repository.activeVisualReferences).thenReturn(MutableStateFlow(emptyList()))
        whenever(repository.mediaItemsMap).thenReturn(MutableStateFlow(emptyMap()))
        whenever(repository.selectedSortCategory).thenReturn(MutableStateFlow(SortCategory.STANDARD))
        whenever(repository.selectedStandardSort).thenReturn(MutableStateFlow(StandardSortOption.NEWEST_FIRST))
        whenever(repository.selectedIntelligentSort).thenReturn(MutableStateFlow(IntelligentSortOption.RECOMMENDED))
        whenever(repository.latestAiSortRecommendation).thenReturn(MutableStateFlow(emptyList()))
        whenever(repository.latestLibraryPresentation).thenReturn(MutableStateFlow(LibraryPresentationState(emptyList())))
        whenever(repository.searchErrorState).thenReturn(MutableStateFlow(null))
        whenever(repository.observeVisualIndexReadiness()).thenReturn(flowOf(VisualIndexReadiness(
            state = VisualIndexState.NEUTRAL_EMPTY,
            totalEligible = 0,
            verifiedVisualCount = 0,
            pendingCount = 0,
            processingCount = 0,
            failedCount = 0,
            isModelReady = true,
            label = "Empty"
        )))
        whenever(repository.scanProgressState).thenReturn(MutableStateFlow(ScanProgressState(isScanning = false)))
        whenever(repository.importProgressState).thenReturn(MutableStateFlow(ImportProgressState(isImporting = false)))

        composeTestRule.setContent {
            LibraryScreen(
                repository = repository,
                onMediaSelect = { _, _ -> },
                onNavigateToPairwise = {},
                onNavigateToChannels = {},
                onCompareLaunch = {}
            )
        }

        // Assert target copy
        composeTestRule.onNodeWithText("Meet your media library").assertExists()
        composeTestRule.onNodeWithText("Aura discovers your photos and videos on your device to power personalized playback and visual discovery.").assertExists()

        // Assert single primary action button
        composeTestRule.onNodeWithText("Scan device").assertExists()

        // Assert supporting footer copy
        composeTestRule.onNodeWithText("Find your existing photos and videos in one step.").assertExists()
        composeTestRule.onNodeWithText("Your media stays on your device.").assertExists()

        // Assert obsolete Import button is NOT present in onboarding
        composeTestRule.onNodeWithText("IMPORT").assertDoesNotExist()
    }
}
