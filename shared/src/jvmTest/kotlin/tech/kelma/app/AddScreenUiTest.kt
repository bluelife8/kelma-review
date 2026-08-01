package tech.kelma.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AddScreenUiTest {
    @Test
    fun desktopEditorValidatesSavesAndClearsTheDraft() = runComposeUiTest {
        val saved = AtomicReference<AddNoteDraft?>(null)
        setContent {
            KelmaTheme {
                AddScreen(
                    deckNames = listOf("French", "Travel"),
                    onBack = {},
                    onSave = { draft ->
                        saved.set(draft)
                        null
                    },
                )
            }
        }

        onNodeWithContentDescription("Choose deck").performClick()
        onNodeWithText("Travel").performClick()
        onNodeWithTag("add-front").performTextInput("bonjour")
        onNodeWithTag("add-back").performTextInput("hello")
        onNodeWithTag("add-tags").performTextInput("french, greeting")
        onNodeWithTag("add-save").performClick()

        waitUntil(timeoutMillis = 5_000) { saved.get() != null }
        assertEquals(
            AddNoteDraft("Travel", "bonjour", "hello", listOf("french", "greeting")),
            saved.get(),
        )
        onNodeWithText("Added to Travel").assertIsDisplayed()
        onNodeWithTag("add-front").assertIsDisplayed()
        onNodeWithTag("add-back").assertIsDisplayed()
    }

    @Test
    fun deckGearAddEntryPrefillsThatDeck() = runComposeUiTest {
        setContent {
            KelmaTheme {
                AddScreen(
                    deckNames = listOf("French", "Travel"),
                    initialDeckName = "Travel",
                    onBack = {},
                    onSave = { null },
                )
            }
        }

        onNodeWithText("Travel").assertIsDisplayed()
    }

    @Test
    fun attachmentPickerPersistsMediaBeforeInsertingMarkup() = runComposeUiTest {
        val attached = AtomicReference<PickedMediaFile?>(null)
        val picker = object : MediaPicker {
            override suspend fun pick(kind: AttachmentKind): PickedMediaFile =
                PickedMediaFile("photo one.png", "image/png", byteArrayOf(1, 2, 3))
        }
        setContent {
            KelmaTheme {
                AddScreen(
                    deckNames = listOf("Deck"),
                    mediaPicker = picker,
                    onBack = {},
                    onAttach = { media -> attached.set(media); media.filename },
                    onSave = { null },
                )
            }
        }

        onNodeWithContentDescription("Attach image").performClick()
        waitUntil { attached.get() != null }
        onNodeWithTag("add-front").assertTextContains("<img src=\"photo one.png\">")
        onNodeWithText("Attached photo one.png").assertIsDisplayed()
    }

    @Test
    fun mobileEditorUsesItsOwnStackedPresentation() = runComposeUiTest {
        var saved = false
        setContent {
            var fields by remember {
                mutableStateOf(listOf(AddFieldState("Front"), AddFieldState("Back")))
            }
            var focused by remember { mutableStateOf(0) }
            val state = AddUiState(
                notetypes = NotetypeCatalog.builtIns,
                notetype = NotetypeCatalog.basic,
                deckNames = listOf("French", "Travel"),
                deckName = "French",
                fields = fields,
                focusedField = focused,
                tags = emptyList(),
                tagInput = "",
                saving = false,
                message = null,
                messageIsError = false,
                history = emptyList(),
            )
            val actions = AddActions(
                onSelectNotetype = {},
                onDeckNameChange = {},
                onFieldChange = { index, value ->
                    fields = fields.mapIndexed { i, field -> if (i == index) field.copy(value = value) else field }
                },
                onFocusField = { focused = it },
                onToggleSticky = {},
                onTogglePreview = {},
                onClearFields = {},
                onTagInputChange = {},
                onCommitTag = {},
                onRemoveTag = {},
                onSave = { saved = true },
                onBack = {},
                onSync = {},
            )
            KelmaTheme { MobileAddScreen(state, actions) }
        }

        onNodeWithText("TYPE").assertIsDisplayed()
        onNodeWithTag("add-front").performTextInput("hola")
        onNodeWithTag("add-back").performTextInput("hello")
        onNodeWithTag("add-save").performClick()
        waitUntil(timeoutMillis = 5_000) { saved }
    }
}
