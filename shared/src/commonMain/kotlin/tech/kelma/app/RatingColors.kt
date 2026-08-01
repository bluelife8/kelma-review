package tech.kelma.app

import androidx.compose.ui.graphics.Color

data class RatingButtonColors(
    val container: Color,
    val content: Color,
    val border: Color,
)

fun ratingButtonColors(rating: Rating): RatingButtonColors {
    val accent = when (rating) {
        Rating.Again -> KelmaColors.Bad
        Rating.Hard -> KelmaColors.Hard
        Rating.Good -> KelmaColors.Good
        Rating.Easy -> KelmaColors.Easy
    }
    return RatingButtonColors(
        container = accent.copy(alpha = 0.13f),
        content = accent,
        border = accent,
    )
}
