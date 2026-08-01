package tech.kelma.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CardMediaViewUiTest {
    private val media = CardMedia("clip.mp3", byteArrayOf(1))
    private val blocks = listOf<CardBlock>(
        CardTextBlock("spoken text"),
        CardAudioBlock(media.filename, media),
    )

    @Test
    fun sharedCardAudioControlIsInlineAndPlayable() = runComposeUiTest {
        val played = AtomicBoolean(false)
        setContent {
            KelmaTheme {
                Column(Modifier.width(360.dp)) {
                    CardFaceContent(
                        text = "spoken text",
                        audio = listOf(media),
                        images = emptyList(),
                        blocks = blocks,
                        textStyle = TextStyle(fontSize = 18.sp),
                        onPlayAudio = { played.set(true) },
                    )
                }
            }
        }

        assertVerticallyInline()
        onNodeWithContentDescription("Play clip.mp3").performClick()
        assertTrue(played.get())
    }

    @Test
    fun audioStaysBesidePrecedingTextWhileFollowingBreakRemains() = runComposeUiTest {
        val lineBlocks = listOf<CardBlock>(
            CardTextBlock("line one", trailingLineBreak = true),
            CardAudioBlock(media.filename, media),
            CardTextBlock("line two", leadingLineBreak = true),
        )
        setContent {
            KelmaTheme {
                Column(Modifier.width(360.dp)) {
                    CardFaceContent(
                        text = "line one\nline two",
                        audio = listOf(media),
                        images = emptyList(),
                        blocks = lineBlocks,
                        textStyle = TextStyle(fontSize = 18.sp),
                        onPlayAudio = {},
                    )
                }
            }
        }

        val inlineText = buildInlineCardText(lineBlocks).text
        assertTrue(inlineText.startsWith("line one "))
        assertTrue(inlineText.endsWith("\nline two"))
        onNodeWithText("\nline two", substring = true).assertExists()
    }

    @Test
    fun desktopReviewAudioControlIsInlineAndPlayable() = runComposeUiTest {
        val played = AtomicBoolean(false)
        setContent {
            KelmaTheme {
                Column(Modifier.width(360.dp)) {
                    DesktopCardFaceContent(
                        text = "spoken text",
                        audio = listOf(media),
                        images = emptyList(),
                        blocks = blocks,
                        onPlayAudio = { played.set(true) },
                    )
                }
            }
        }

        assertVerticallyInline()
        onNodeWithContentDescription("Play clip.mp3").performClick()
        assertTrue(played.get())
    }

    private fun androidx.compose.ui.test.ComposeUiTest.assertVerticallyInline() {
        val textBounds = onNodeWithText("spoken text", substring = true).fetchSemanticsNode().boundsInRoot
        val audioBounds = onNodeWithContentDescription("Play clip.mp3").fetchSemanticsNode().boundsInRoot
        assertTrue(textBounds.bottom >= audioBounds.top && audioBounds.bottom >= textBounds.top)
    }
}
