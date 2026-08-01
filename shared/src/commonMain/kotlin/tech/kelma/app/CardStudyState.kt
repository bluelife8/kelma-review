package tech.kelma.app

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CardStudyState {
    @SerialName("active")
    Active,

    @SerialName("suspended")
    Suspended,
}

internal data class LocalCardStudyState(
    val cardId: Long,
    val noteGuid: String,
    val cardOrd: Int,
    val state: CardStudyState,
    val clientModifiedAtMillis: Long,
    val uploadState: String,
)

internal fun cardStudyKey(noteGuid: String, cardOrd: Int): String = "$noteGuid\u0000$cardOrd"

internal fun String.asCardStudyState(): CardStudyState =
    if (this == "suspended") CardStudyState.Suspended else CardStudyState.Active

internal fun String.splitCardStudyKey(): Pair<String, Int> {
    val separator = lastIndexOf('\u0000')
    require(separator >= 0) { "Invalid card study-state key" }
    return substring(0, separator) to substring(separator + 1).toInt()
}
