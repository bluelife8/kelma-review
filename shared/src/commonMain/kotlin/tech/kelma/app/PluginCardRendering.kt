package tech.kelma.app

internal data class PluginRenderedCard(
    val rendererId: String,
    val runtimeGeneration: Long,
    val source: ReviewCard,
    val rendered: ReviewCard,
)

internal data class PluginCardRenderBatch(
    val cards: Map<Long, PluginRenderedCard>,
    val failure: String? = null,
)

internal suspend fun renderAssignedReviewCards(
    host: LuaPluginHost,
    registry: PluginRendererRegistry,
    assignments: PluginRendererAssignmentState,
    collection: SyncedCollection,
    cards: List<ReviewCard>,
    runtimeGeneration: Long,
    existing: Map<Long, PluginRenderedCard> = emptyMap(),
): PluginCardRenderBatch {
    val rendered = mutableMapOf<Long, PluginRenderedCard>()
    var failure: String? = null
    cards.forEach { card ->
        val syncCard = collection.cards[card.id] ?: return@forEach
        val note = collection.notes[syncCard.noteGuid] ?: return@forEach
        val rendererId = assignments.rendererFor(syncCard, note) ?: return@forEach
        if (!registry.contains(rendererId)) return@forEach
        val cached = existing[card.id]?.takeIf {
            it.rendererId == rendererId && it.runtimeGeneration == runtimeGeneration && it.source == card
        }
        if (cached != null) {
            rendered[card.id] = cached
            return@forEach
        }
        try {
            rendered[card.id] = PluginRenderedCard(
                rendererId,
                runtimeGeneration,
                card,
                host.renderReviewCard(rendererId, card),
            )
        } catch (rendererFailure: Exception) {
            if (failure == null) failure = rendererFailure.message ?: "Card renderer failed"
        }
    }
    return PluginCardRenderBatch(rendered, failure)
}

internal suspend fun LuaPluginHost.renderReviewCard(
    rendererId: String,
    card: ReviewCard,
): ReviewCard {
    val frontHtml = card.frontHtml ?: escapeHtml(card.front).replace("\n", "<br>")
    val answerHtml = card.fullAnswerHtml ?: buildString {
        append(frontHtml)
        append("<hr id=\"answer\">")
        append(card.backHtml ?: escapeHtml(card.back).replace("\n", "<br>"))
    }
    val front = render(PluginRenderRequest(rendererId, frontHtml, card.cardCss))
    val answer = render(PluginRenderRequest(rendererId, answerHtml, card.cardCss))
    return card.copy(
        frontHtml = front.html,
        fullAnswerHtml = answer.html,
        cardCss = front.css,
        answerCardCss = answer.css,
    )
}

internal fun PluginRenderResult.validated(): PluginRenderResult {
    require(html.encodeToByteArray().size <= MaximumPluginRenderedHtmlBytes) {
        "Plugin-rendered HTML exceeds 2 MiB"
    }
    require(css.encodeToByteArray().size <= MaximumPluginRenderedCssBytes) {
        "Plugin-rendered CSS exceeds 512 KiB"
    }
    require('\u0000' !in html && '\u0000' !in css) { "Plugin-rendered content contains a null byte" }
    return this
}

private const val MaximumPluginRenderedHtmlBytes = 2 * 1024 * 1024
private const val MaximumPluginRenderedCssBytes = 512 * 1024
