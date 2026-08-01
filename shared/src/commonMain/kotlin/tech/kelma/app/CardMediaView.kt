package tech.kelma.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

@Composable
fun CardFaceContent(
    text: String,
    audio: List<CardMedia>,
    images: List<CardMedia>,
    blocks: List<CardBlock>,
    textStyle: TextStyle,
    onPlayAudio: (CardMedia) -> Unit,
) {
    val content = blocks.ifEmpty { fallbackBlocks(text, audio, images) }
    groupedContent(content).forEachIndexed { index, group ->
        if (index > 0) Spacer(Modifier.height(12.dp))
        val image = group.singleOrNull() as? CardImageBlock
        if (image != null) {
            CardImage(image.media, image.filename, 280.dp)
        } else {
            InlineCardContent(group, textStyle, desktop = false, onPlayAudio)
        }
    }
}

@Composable
fun DesktopCardFaceContent(
    text: String,
    audio: List<CardMedia>,
    images: List<CardMedia>,
    blocks: List<CardBlock>,
    onPlayAudio: (CardMedia) -> Unit,
) {
    val content = blocks.ifEmpty { fallbackBlocks(text, audio, images) }
    var inlineIndex = 0
    groupedContent(content).forEachIndexed { index, group ->
        val image = group.singleOrNull() as? CardImageBlock
        if (index > 0) Spacer(Modifier.height(if (image == null) 18.dp else 14.dp))
        if (image != null) {
            CardImage(image.media, image.filename, 260.dp)
        } else {
            InlineCardContent(
                blocks = group,
                style = TextStyle(
                    color = KelmaDesktopColors.TextPrimary,
                    fontSize = 20.sp,
                    lineHeight = if (inlineIndex++ == 0) 28.sp else 30.sp,
                ),
                desktop = true,
                onPlayAudio = onPlayAudio,
            )
        }
    }
}

/** Groups text and audio together while keeping images as their own vertical blocks. */
private fun groupedContent(content: List<CardBlock>): List<List<CardBlock>> {
    val result = mutableListOf<List<CardBlock>>()
    val inline = mutableListOf<CardBlock>()
    fun flushInline() {
        if (inline.isNotEmpty()) {
            result += inline.toList()
            inline.clear()
        }
    }
    content.forEach { block ->
        if (block is CardImageBlock) {
            flushInline()
            result += listOf(block)
        } else {
            inline += block
        }
    }
    flushInline()
    return result
}

@Composable
private fun InlineCardContent(
    blocks: List<CardBlock>,
    style: TextStyle,
    desktop: Boolean,
    onPlayAudio: (CardMedia) -> Unit,
) {
    val text = remember(blocks) { buildInlineCardText(blocks) }
    val placeholderSize = if (desktop) 28.sp else 24.sp
    val inlineContent = blocks.mapIndexedNotNull { index, block ->
        val audio = block as? CardAudioBlock ?: return@mapIndexedNotNull null
        audioId(index) to InlineTextContent(
            placeholder = Placeholder(
                width = placeholderSize,
                height = placeholderSize,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
            ),
        ) {
            InlineAudioControl(audio, desktop, onPlayAudio)
        }
    }.toMap()
    Text(
        text = text,
        inlineContent = inlineContent,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        style = style,
    )
}

private fun CardTextBlock.styledText(): AnnotatedString =
    html?.let(::renderInlineHtml) ?: AnnotatedString(text)

internal fun buildInlineCardText(blocks: List<CardBlock>): AnnotatedString = buildAnnotatedString {
    var endsWithWhitespace = false
    var endsWithLineBreak = false
    blocks.forEachIndexed { index, block ->
        when (block) {
            is CardTextBlock -> {
                if (block.leadingLineBreak && length > 0 && !endsWithLineBreak) {
                    append("\n")
                    endsWithWhitespace = true
                    endsWithLineBreak = true
                }
                val styled = block.styledText()
                append(styled)
                styled.lastOrNull()?.let { last ->
                    endsWithWhitespace = last.isWhitespace()
                    endsWithLineBreak = last == '\n'
                }
                val audioFollows = blocks.getOrNull(index + 1) is CardAudioBlock
                if (block.trailingLineBreak && !endsWithLineBreak && !audioFollows) {
                    append("\n")
                    endsWithWhitespace = true
                    endsWithLineBreak = true
                }
            }
            is CardAudioBlock -> {
                if (length > 0 && !endsWithWhitespace) append(" ")
                appendInlineContent(audioId(index), "play audio")
                append(" ")
                endsWithWhitespace = true
                endsWithLineBreak = false
            }
            is CardImageBlock -> Unit
        }
    }
}

private fun audioId(index: Int): String = "audio-$index"

@Composable
private fun InlineAudioControl(
    block: CardAudioBlock,
    desktop: Boolean,
    onPlayAudio: (CardMedia) -> Unit,
) {
    val media = block.media
    val background = when {
        media == null -> KelmaColors.Bad.copy(alpha = 0.16f)
        desktop -> KelmaDesktopColors.Gold
        else -> MaterialTheme.colorScheme.primary
    }
    val foreground = when {
        media == null -> KelmaColors.Bad
        desktop -> KelmaDesktopColors.Background
        else -> MaterialTheme.colorScheme.onPrimary
    }
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(1.dp)
            .clickable(enabled = media != null) { media?.let(onPlayAudio) },
        color = background,
        contentColor = foreground,
        shape = CircleShape,
        border = BorderStroke(
            1.dp,
            if (media == null) KelmaColors.Bad.copy(alpha = 0.45f) else foreground.copy(alpha = 0.22f),
        ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (media == null) {
                Text("!", color = foreground, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            } else {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "Play ${block.filename}",
                    tint = foreground,
                )
            }
        }
    }
}

private fun fallbackBlocks(
    text: String,
    audio: List<CardMedia>,
    images: List<CardMedia>,
): List<CardBlock> = buildList {
    if (text.isNotBlank()) add(CardTextBlock(text))
    audio.forEach { add(CardAudioBlock(it.filename, it)) }
    images.forEach { add(CardImageBlock(it.filename, it)) }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun CardImage(media: CardMedia?, filename: String, maxHeight: Dp) {
    if (media == null) {
        MissingMedia("Image unavailable", filename)
        return
    }
    val bitmap = remember(media.bytes) {
        runCatching { media.bytes.decodeToImageBitmap() }.getOrNull()
    }
    if (bitmap == null) {
        MissingMedia("Image could not be decoded", filename)
        return
    }
    Image(
        bitmap = bitmap,
        contentDescription = filename,
        modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight),
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun MissingMedia(message: String, filename: String) {
    Surface(
        color = KelmaColors.Bad.copy(alpha = 0.10f),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, KelmaColors.Bad.copy(alpha = 0.45f)),
    ) {
        Text(
            text = "$message · $filename",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = KelmaColors.Bad,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
        )
    }
}
