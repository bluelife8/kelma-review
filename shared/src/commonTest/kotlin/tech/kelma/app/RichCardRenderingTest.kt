package tech.kelma.app

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RichCardRenderingTest {
    @Test
    fun documentPreservesTemplateCssAndEmbedsOnlyLocalImages() {
        val document = buildCardHtmlDocument(
            CardHtmlFace(
                body = "<div class=accent><img src='photo.png'><img src='https://tracker.invalid/x.png'></div>",
                css = ".accent { color: rgb(1, 2, 3); text-align: right; }",
                images = listOf(CardMedia("photo.png", byteArrayOf(1, 2, 3))),
            ),
            desktop = false,
        )

        assertContains(document, ".accent { color: rgb(1, 2, 3); text-align: right; }")
        assertContains(document, "data:image/png;base64,AQID")
        assertContains(document, "Missing image: x.png")
        assertFalse(document.contains("tracker.invalid"))
        assertContains(document, "default-src 'none'")
        assertContains(document, "prefers-reduced-motion")
    }

    @Test
    fun soundTokensBecomeAccessiblePlatformBridgeControls() {
        val document = buildCardHtmlDocument(
            CardHtmlFace(
                body = "prompt [sound:voice clip.mp3] [sound:missing.mp3]",
                css = "",
                images = emptyList(),
                audio = listOf(CardMedia("voice clip.mp3", byteArrayOf(1))),
            ),
            desktop = false,
        )

        assertContains(document, "href=\"kelma-audio:voice%20clip.mp3\"")
        assertContains(document, "aria-label=\"Play voice clip.mp3\"")
        assertContains(document, "messageHandlers.kelmaAudio")
        assertContains(document, "messageHandlers.kelmaCardTap")
        assertContains(document, "<body class=\"card\"")
        assertContains(document, "<!--kelma-card-body-start-->")
        assertContains(document, "class=\"kelma-audio-play\"")
        assertContains(document, "border-left:11px solid #0F100A")
        assertContains(document, "Missing audio: missing.mp3")
    }

    @Test
    fun richRendererHonorsOnlyTheJvmTestEscapeHatch() {
        if (isDesktopApp) assertFalse(shouldUseRichReviewCard())
        else assertTrue(shouldUseRichReviewCard())
    }

    @Test
    fun cardContentIsSafelyCenteredInTheDesktopViewport() {
        val document = buildCardHtmlDocument(
            CardHtmlFace("question", "", emptyList()),
            desktop = true,
        )

        assertContains(document, "display:flex;flex-direction:column")
        assertContains(document, "#kelma-card-content")
        assertContains(document, "margin-block:auto")
        assertContains(document, "<main id=\"kelma-card-content\">question</main>")
    }

    @Test
    fun answerDocumentScrollsToTheAnswerDividerAfterMediaLoads() {
        val document = buildCardHtmlDocument(
            CardHtmlFace("front<hr id=answer>back", "", emptyList()),
            desktop = false,
            scrollToAnswer = true,
        )

        assertContains(document, "getElementById('answer')")
        assertContains(document, "scrollIntoView")
        assertContains(document, "window.addEventListener('load'")
    }

    @Test
    fun preparedCardBuildsQuestionAndAnswerDocumentsAheadOfReveal() {
        val prepared = prepareStudyCard(
            ReviewCard(
                id = 1,
                front = "question",
                back = "answer",
                frontAudio = listOf(CardMedia("question.mp3", byteArrayOf(1))),
                backAudio = listOf(CardMedia("answer.mp3", byteArrayOf(2))),
            ),
            desktopLayout = false,
        )

        assertContains(prepared.question.document, "question")
        assertFalse(prepared.question.document.contains("answer"))
        assertContains(prepared.answer.document, "question<hr id=\"answer\">answer")
        assertEquals(setOf("question.mp3"), prepared.question.audioByName.keys)
        assertEquals(setOf("question.mp3", "answer.mp3"), prepared.answer.audioByName.keys)
    }

    @Test
    fun persistentBrowserUpdateReplacesBodyAndRetainsAnswerScrolling() {
        val document = buildCardHtmlDocument(
            CardHtmlFace("front<hr id=\"answer\">back", ".card { color: red; }", emptyList()),
            desktop = false,
            scrollToAnswer = true,
        )

        val script = cardDomUpdateScript(document)

        assertContains(script, "document.body.innerHTML")
        assertContains(script, "document.getElementById('kelma-card-style')")
        assertContains(script, "if(true){requestAnimationFrame")
    }

    @Test
    fun interactiveCardElementsRetainNativeClicksOnTouch() {
        val document = buildCardHtmlDocument(
            CardHtmlFace("<details><summary>Grammar</summary>Explanation</details>", "", emptyList()),
            desktop = false,
        )

        assertContains(document, "summary,details")
        assertContains(document, "if(resolve(e.target,sx)){handledAt=endedAt;e.preventDefault();}")
        assertContains(
            document,
            "if(!b&&interactive(e.target)){if(a)e.preventDefault();return;}" +
                "if(Date.now()-handledAt<700)",
        )
        assertFalse(document.contains("active=false;handledAt=Date.now()"))
    }

    @Test
    fun documentRetainsInlineScriptsButPreventsLinkNavigation() {
        val document = buildCardHtmlDocument(
            CardHtmlFace("<script>document.body.dataset.ready='yes'</script><a href='https://example.com'>link</a>", "", emptyList()),
            desktop = true,
        )

        assertContains(document, "document.body.dataset.ready='yes'")
        assertContains(document, "e.preventDefault()")
        assertContains(document, "script-src 'unsafe-inline'")
    }
}
