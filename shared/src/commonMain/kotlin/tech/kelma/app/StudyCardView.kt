package tech.kelma.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
internal fun StudyCard(
    card: ReviewCard,
    showingAnswer: Boolean,
    desktopLayout: Boolean,
    onPlayAudio: (CardMedia) -> Unit,
    onCardTap: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
    preparedCard: PreparedStudyCard? = null,
    forceFallback: Boolean = false,
) {
    // Desktop menus and dialogs render in-scene and cannot draw above the heavyweight WebView
    // panel, so callers force the lightweight renderer while such an overlay is open. The
    // retained browser keeps its last frame and remounts seamlessly when the overlay closes.
    if (shouldUseRichReviewCard() && !forceFallback) {
        val prepared = preparedCard?.face(showingAnswer) ?: remember(card, showingAnswer, desktopLayout) {
            prepareStudyCardFace(card, showingAnswer, desktopLayout)
        }
        Surface(
            modifier = modifier.fillMaxSize(),
            color = KelmaColors.Surface,
            contentColor = KelmaColors.TextPrimary,
            shape = androidx.compose.ui.graphics.RectangleShape,
        ) {
            PlatformHtmlCardFace(
                html = prepared.document,
                updateScript = prepared.updateScript,
                onPlayAudio = { filename ->
                    prepared.audioByName[filename.substringAfterLast('/').replace("%20", " ")]
                        ?.let(onPlayAudio)
                },
                onCardTap = onCardTap,
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }

    val answerRequester = remember { BringIntoViewRequester() }
    val cardScroll = rememberScrollState()
    LaunchedEffect(showingAnswer) {
        if (showingAnswer) {
            delay(260)
            answerRequester.bringIntoView()
        }
    }

    Surface(
        modifier = modifier
            .widthIn(max = if (desktopLayout) 900.dp else 640.dp)
            .fillMaxWidth()
            .then(if (desktopLayout) Modifier.heightIn(min = 320.dp) else Modifier),
        color = if (desktopLayout) KelmaDesktopColors.Background else KelmaColors.Surface,
        contentColor = if (desktopLayout) KelmaDesktopColors.TextPrimary else KelmaColors.TextPrimary,
        shape = if (desktopLayout) MaterialTheme.shapes.medium else androidx.compose.ui.graphics.RectangleShape,
    ) {
        Column(
            modifier = Modifier
                .then(if (desktopLayout) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
                .platformPointerScroll(cardScroll)
                .verticalScroll(cardScroll)
                .padding(
                    horizontal = if (desktopLayout) 54.dp else 16.dp,
                    vertical = if (desktopLayout) 38.dp else 18.dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CardSide(card, answer = false, desktop = desktopLayout, onPlayAudio = onPlayAudio)
            AnimatedVisibility(
                visible = showingAnswer,
                enter = expandVertically(
                    animationSpec = tween(240),
                    expandFrom = Alignment.Top,
                ) + fadeIn(animationSpec = tween(160, delayMillis = 80)),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().bringIntoViewRequester(answerRequester),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 24.dp),
                        color = if (desktopLayout) KelmaDesktopColors.Border else KelmaColors.SurfaceBorder,
                    )
                    CardSide(card, answer = true, desktop = desktopLayout, onPlayAudio = onPlayAudio)
                }
            }
        }
    }
}

internal data class PreparedStudyCard(
    val question: PreparedStudyCardFace,
    val answer: PreparedStudyCardFace,
) {
    fun face(showingAnswer: Boolean): PreparedStudyCardFace = if (showingAnswer) answer else question
}

internal data class PreparedStudyCardFace(
    val document: String,
    val updateScript: String,
    val audioByName: Map<String, CardMedia>,
)

internal fun prepareStudyCard(card: ReviewCard, desktopLayout: Boolean): PreparedStudyCard = PreparedStudyCard(
    question = prepareStudyCardFace(card, showingAnswer = false, desktopLayout = desktopLayout),
    answer = prepareStudyCardFace(card, showingAnswer = true, desktopLayout = desktopLayout),
)

private fun prepareStudyCardFace(
    card: ReviewCard,
    showingAnswer: Boolean,
    desktopLayout: Boolean,
): PreparedStudyCardFace {
    val frontHtml = card.frontHtml ?: escapeHtml(card.front).replace("\n", "<br>")
    val backHtml = card.backHtml ?: escapeHtml(card.back).replace("\n", "<br>")
    val body = if (showingAnswer) {
        card.fullAnswerHtml ?: "$frontHtml<hr id=\"answer\">$backHtml"
    } else {
        frontHtml
    }
    val images = if (showingAnswer) card.frontImages + card.backImages else card.frontImages
    val audio = if (showingAnswer) card.frontAudio + card.backAudio else card.frontAudio
    val document = buildCardHtmlDocument(
        CardHtmlFace(
            body = body,
            css = if (showingAnswer) card.answerCardCss ?: card.cardCss else card.cardCss,
            images = images.distinctBy(CardMedia::filename),
            audio = audio.distinctBy(CardMedia::filename),
        ),
        desktop = desktopLayout,
        scrollToAnswer = showingAnswer,
    )
    return PreparedStudyCardFace(
        document = document,
        updateScript = cardDomUpdateScript(document),
        audioByName = audio.associateBy {
            it.filename.substringAfterLast('/').replace("%20", " ")
        },
    )
}

@Composable
private fun CardSide(
    card: ReviewCard,
    answer: Boolean,
    desktop: Boolean,
    onPlayAudio: (CardMedia) -> Unit,
) {
    val text = if (answer) card.back else card.front
    val audio = if (answer) card.backAudio else card.frontAudio
    val images = if (answer) card.backImages else card.frontImages
    val blocks = if (answer) card.backBlocks else card.frontBlocks
    val html = if (answer) card.backHtml else card.frontHtml
    RichOrFallbackCardFace(
        html = html,
        css = if (answer) card.answerCardCss ?: card.cardCss else card.cardCss,
        text = text,
        audio = audio,
        images = images,
        blocks = blocks,
        textStyle = TextStyle(
            color = KelmaColors.TextPrimary,
            fontSize = if (answer) 20.sp else 22.sp,
            lineHeight = if (answer) 31.sp else 34.sp,
            fontWeight = FontWeight.Normal,
        ),
        desktop = desktop,
        onPlayAudio = onPlayAudio,
    )
}
