package tech.kelma.app

import kotlinx.serialization.Serializable

@Serializable
data class DeckOptionsPreset(
    val id: String,
    val name: String,
    val options: DeckOptions,
    val createdAtMillis: Long,
    val modifiedAtMillis: Long,
)

data class DeckPresetState(
    val presets: List<DeckOptionsPreset> = emptyList(),
    val assignments: Map<String, String> = emptyMap(),
) {
    fun presetForDeck(deckName: String): DeckOptionsPreset? =
        assignments.entries.firstOrNull { it.key.equals(deckName, ignoreCase = true) }
            ?.value
            ?.let { presetId -> presets.firstOrNull { it.id == presetId } }
}

internal fun validatePresetName(input: String): String {
    val normalized = input.trim()
    require(normalized.isNotEmpty()) { "Enter a preset name" }
    require(normalized.length <= 80) { "Preset names cannot exceed 80 characters" }
    require('\n' !in normalized && '\r' !in normalized) { "Preset names cannot contain line breaks" }
    return normalized
}
