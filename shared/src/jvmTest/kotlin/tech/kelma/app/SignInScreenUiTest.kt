package tech.kelma.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SignInScreenUiTest {
    @Test
    fun desktopDeckListExposesAccountControls() = runComposeUiTest {
        val opened = AtomicBoolean(false)
        setContent {
            KelmaTheme {
                DesktopDeckListScreen(
                    decks = emptyList(),
                    signedIn = true,
                    syncing = false,
                    syncMessage = null,
                    syncMessageIsError = false,
                    studiedToday = 0,
                    syncedCardCount = 0,
                    localCardCount = 0,
                    syncedMediaBytes = 0,
                    canUndo = false,
                    onUndo = {},
                    onAdd = {},
                    onCreateDeck = { null },
                    deckManagement = DeckManagementActions(
                        onAddCards = {},
                        onBrowseCards = {},
                        onOptions = {},
                        onExport = {},
                        onRename = { _, _ -> null },
                        onDelete = { null },
                    ),
                    onOpenDeck = {},
                    onSignIn = {},
                    onSync = {},
                    onAccount = { opened.set(true) },
                )
            }
        }

        onNodeWithText("Account…").assertIsDisplayed().performClick()
        assertTrue(opened.get())
    }

    @Test
    fun optionalAccountActionsOpenCanonicalWebFlowsOrReturnToLocalUse() = runComposeUiTest {
        val opened = AtomicReference<String?>(null)
        val continued = AtomicBoolean(false)
        setContent {
            KelmaTheme {
                SignInScreen(
                    signingIn = false,
                    error = null,
                    onSignIn = { _, _ -> },
                    onBack = { continued.set(true) },
                    onOpenUri = opened::set,
                )
            }
        }

        onNodeWithText("In Review, a Kelma account is optional and only needed for KelmaSync.")
            .assertIsDisplayed()
        onNodeWithTag("create-kelma-account").performClick()
        assertEquals(KelmaAccountRegistrationUrl, opened.get())
        onNodeWithTag("forgot-password").performClick()
        assertEquals(KelmaPasswordResetUrl, opened.get())
        onNodeWithTag("continue-without-account").performClick()
        assertTrue(continued.get())
    }

    @Test
    fun savedAccountOpensWithoutRequestingItsPassword() = runComposeUiTest {
        val submitted = AtomicReference<Pair<String, String>?>(null)
        val selected = AtomicReference<LocalAccountChoice?>(null)
        setContent {
            KelmaTheme {
                SignInScreen(
                    signingIn = false,
                    error = null,
                    accounts = listOf(
                        LocalAccountChoice("alice@example.com", DefaultKelmaSyncEndpoint),
                        LocalAccountChoice("bob@example.com", DefaultKelmaSyncEndpoint),
                    ),
                    onSignIn = { username, password -> submitted.set(username to password) },
                    onSelectAccount = selected::set,
                    onBack = {},
                )
            }
        }

        onNodeWithTag("saved-account-alice@example.com").assertIsDisplayed().performClick()
        onNodeWithTag("saved-account-bob@example.com").assertIsDisplayed()
        onNodeWithTag("sign-in-username").assertTextContains("alice@example.com")

        assertEquals(LocalAccountChoice("alice@example.com", DefaultKelmaSyncEndpoint), selected.get())
        assertEquals(null, submitted.get())
    }
}
