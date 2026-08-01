package tech.kelma.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AccountSchedulerProfileControlsUiTest {
    @Test
    fun explicitLocalPublishAndCloudApplyActionsRemainSeparate() = runComposeUiTest {
        val localApply = AtomicInteger()
        val published = AtomicInteger()
        val cloudApply = AtomicInteger()
        setContent {
            KelmaTheme {
                DesktopAccountSchedulerProfileControls(
                    state = SchedulerProfileState(
                        local = LocalSchedulerProfile(
                            2,
                            SchedulerProfileSettings(
                                parameterSource = SchedulerProfileSource.Manual,
                                retentionSource = SchedulerProfileSource.ClientOptimized,
                            ),
                            1_000,
                        ),
                        cloud = CloudSchedulerProfile(version = 4),
                        syncStatus = SchedulerProfileSyncStatus.Pending,
                    ),
                    signedIn = true,
                    enabled = true,
                    onApplyCurrent = { publish ->
                        if (publish) published.incrementAndGet() else localApply.incrementAndGet()
                        null
                    },
                    onApplyCloud = { cloudApply.incrementAndGet(); null },
                )
            }
        }

        onNodeWithText("Local v2 · parameters manual · retention optimized").assertIsDisplayed()
        onNodeWithText("KelmaSync v4 · upload pending · 0 projections pending").assertIsDisplayed()
        onNodeWithTag("profile-apply-local").performClick()
        waitUntil { localApply.get() == 1 }
        onNodeWithTag("profile-apply-publish").performClick()
        waitUntil { published.get() == 1 }
        onNodeWithTag("profile-use-cloud").performClick()
        waitUntil { cloudApply.get() == 1 }
        assertEquals(1, localApply.get())
        assertEquals(1, published.get())
        assertEquals(1, cloudApply.get())
    }
}
