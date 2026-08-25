package tech.kelma.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AccountLegalControlsUiTest {
    @Test
    fun signedInAccountActionsRemainSemanticallyDistinct() = runComposeUiTest {
        val signedOut = AtomicBoolean(false)
        val removed = AtomicBoolean(false)
        setContent {
            KelmaTheme {
                AccountLegalControlsDialog(
                    signedIn = true,
                    username = "person@example.com",
                    working = false,
                    onChooseAccount = {},
                    onSwitchAccount = {},
                    onSignOut = { signedOut.set(true) },
                    onRemoveFromDevice = { removed.set(true) },
                    onOpenUri = {},
                    onDismiss = {},
                )
            }
        }

        onNodeWithText("Signed in as person@example.com").assertIsDisplayed()
        onNodeWithTag("account-sign-out").performClick()
        assertTrue(signedOut.get())

        onNodeWithTag("account-remove-device").performClick()
        onNodeWithText("Remove local account data?").assertIsDisplayed()
        onNodeWithTag("confirm-remove-device").performClick()
        assertTrue(removed.get())
    }

    @Test
    fun deletionRequiresConsequencesConfirmationBeforeOpeningThePublicFlow() = runComposeUiTest {
        val opened = AtomicReference<String?>(null)
        setContent {
            KelmaTheme {
                AccountLegalControlsDialog(
                    signedIn = true,
                    username = "person@example.com",
                    working = false,
                    onChooseAccount = {},
                    onSwitchAccount = {},
                    onSignOut = {},
                    onRemoveFromDevice = {},
                    onOpenUri = opened::set,
                    onDismiss = {},
                )
            }
        }

        onNodeWithTag("account-delete-kelma").performScrollTo().performClick()
        onNodeWithText("Continue to account deletion?").assertIsDisplayed()
        assertEquals(null, opened.get())
        onNodeWithTag("confirm-delete-kelma").performClick()
        assertEquals(KelmaAccountDeletionUrl, opened.get())
    }

    @Test
    fun privacyTermsSupportAndLicenseResourcesUseCanonicalUrls() = runComposeUiTest {
        val opened = AtomicReference<String?>(null)
        setContent {
            KelmaTheme {
                AccountLegalControlsDialog(
                    signedIn = false,
                    username = null,
                    working = false,
                    onChooseAccount = {},
                    onSwitchAccount = {},
                    onSignOut = {},
                    onRemoveFromDevice = {},
                    onOpenUri = opened::set,
                    onDismiss = {},
                )
            }
        }

        val resources = listOf(
            "legal-privacy" to KelmaReviewPrivacyUrl,
            "legal-terms" to KelmaReviewTermsUrl,
            "legal-support" to KelmaReviewSupportUrl,
            "about-source" to KelmaReviewSourceUrl,
            "about-license" to KelmaReviewLicenseUrl,
            "about-notices" to KelmaReviewNoticesUrl,
        )
        resources.forEach { (tag, expectedUrl) ->
            onNodeWithTag(tag).performScrollTo().performClick()
            assertEquals(expectedUrl, opened.get())
        }
    }
}
