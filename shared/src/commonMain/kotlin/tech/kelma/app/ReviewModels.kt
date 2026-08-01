package tech.kelma.app

enum class Rating(val label: String, val shortcut: String) {
    Again("Again", "1"),
    Hard("Hard", "2"),
    Good("Good", "3"),
    Easy("Easy", "4"),
}

enum class ReviewShortcut {
    Space,
    One,
    Two,
    Three,
    Four,
}

data class CardMedia(
    val filename: String,
    val bytes: ByteArray,
)

sealed interface CardBlock

data class CardTextBlock(
    val text: String,
    val html: String? = null,
    val leadingLineBreak: Boolean = false,
    val trailingLineBreak: Boolean = false,
) : CardBlock

data class CardAudioBlock(
    val filename: String,
    val media: CardMedia?,
) : CardBlock

data class CardImageBlock(
    val filename: String,
    val media: CardMedia?,
) : CardBlock

data class ReviewCard(
    val id: Long,
    val front: String,
    val back: String,
    val noteGuid: String = "",
    val noteMarked: Boolean = false,
    val flag: Int = 0,
    val frontAudio: List<CardMedia> = emptyList(),
    val backAudio: List<CardMedia> = emptyList(),
    val frontImages: List<CardMedia> = emptyList(),
    val backImages: List<CardMedia> = emptyList(),
    val frontBlocks: List<CardBlock> = emptyList(),
    val backBlocks: List<CardBlock> = emptyList(),
    val frontHtml: String? = null,
    val backHtml: String? = null,
    val fullAnswerHtml: String? = null,
    val cardCss: String = "",
    val answerCardCss: String? = null,
)

internal fun ReviewCard.hasUnloadedMedia(): Boolean =
    (frontAudio + backAudio + frontImages + backImages).any { it.bytes.isEmpty() } ||
        (frontBlocks + backBlocks).any { block ->
            when (block) {
                is CardAudioBlock -> block.media?.bytes?.isEmpty() == true
                is CardImageBlock -> block.media?.bytes?.isEmpty() == true
                is CardTextBlock -> false
            }
        }

internal fun ReviewCard.hydrateMedia(load: (String) -> ByteArray?): ReviewCard {
    val loaded = mutableMapOf<String, CardMedia>()
    fun CardMedia.hydrated(): CardMedia = loaded.getOrPut(filename) {
        if (bytes.isNotEmpty()) this else load(filename)?.let { copy(bytes = it) } ?: this
    }
    fun CardBlock.hydrated(): CardBlock = when (this) {
        is CardAudioBlock -> copy(media = media?.hydrated())
        is CardImageBlock -> copy(media = media?.hydrated())
        is CardTextBlock -> this
    }
    return copy(
        frontAudio = frontAudio.map { it.hydrated() },
        backAudio = backAudio.map { it.hydrated() },
        frontImages = frontImages.map { it.hydrated() },
        backImages = backImages.map { it.hydrated() },
        frontBlocks = frontBlocks.map { it.hydrated() },
        backBlocks = backBlocks.map { it.hydrated() },
    )
}
