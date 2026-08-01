package tech.kelma.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object KelmaColors {
    val Background = Color(0xFF0F100A)
    val BackgroundAlt = Color(0xFF141610)
    val Surface = Color(0xFF1B1D16)
    val SurfaceElevated = Color(0xFF24271D)
    val SurfaceHigh = Color(0xFF2D3024)
    val SurfaceBorder = Color(0xFF3A3D31)
    val Hairline = Color(0xFF2A2C22)
    val Gold = Color(0xFFC9AC6B)
    val GoldSoft = Color(0xFFDCC48F)
    val GoldBright = Color(0xFFECD49A)
    val TextPrimary = Color(0xFFF4F1E7)
    val TextSecondary = Color(0xFFADAEA1)
    val TextMuted = Color(0xFF7B7D70)
    val Good = Color(0xFF84B07D)
    val Bad = Color(0xFFD38975)
    val NewCard = Color(0xFF7FB2C6)
    val Young = Color(0xFF9CC593)
    val Hard = Color(0xFFB8995F)
    val Easy = Color(0xFF6F9FB0)
}

object KelmaDesktopColors {
    val Background = Color(0xFF0F100B)
    val Toolbar = Color(0xFF1B1A12)
    val Surface = Color(0xFF1C1B13)
    val SurfaceHigh = Color(0xFF393629)
    val Border = Color(0xFF403821)
    val TextPrimary = Color(0xFFF4F1E7)
    val TextSecondary = Color(0xFFC7C2B4)
    val TextMuted = Color(0xFF958B70)
    val Accent = Color(0xFF08A64B)
    val Gold = Color(0xFFE8CF91)
    val New = Color(0xFF86BEF4)
    val Learn = Color(0xFFFF6B73)
    val Due = Color(0xFF18C45A)
    val UtilityButton = Color(0xFF737373)
}

private val MobileColorScheme = darkColorScheme(
    primary = KelmaColors.Gold,
    onPrimary = KelmaColors.Background,
    primaryContainer = KelmaColors.SurfaceElevated,
    onPrimaryContainer = KelmaColors.GoldSoft,
    secondary = KelmaColors.GoldSoft,
    onSecondary = KelmaColors.Background,
    secondaryContainer = KelmaColors.SurfaceHigh,
    onSecondaryContainer = KelmaColors.TextPrimary,
    tertiary = KelmaColors.Good,
    onTertiary = KelmaColors.Background,
    tertiaryContainer = KelmaColors.SurfaceElevated,
    onTertiaryContainer = KelmaColors.Good,
    background = KelmaColors.Background,
    onBackground = KelmaColors.TextPrimary,
    surface = KelmaColors.Background,
    onSurface = KelmaColors.TextPrimary,
    surfaceVariant = KelmaColors.Surface,
    onSurfaceVariant = KelmaColors.TextSecondary,
    outline = KelmaColors.SurfaceBorder,
    outlineVariant = KelmaColors.Hairline,
    error = KelmaColors.Bad,
    onError = KelmaColors.Background,
    errorContainer = KelmaColors.SurfaceElevated,
    onErrorContainer = KelmaColors.Bad,
)

private val DesktopColorScheme = darkColorScheme(
    primary = KelmaDesktopColors.Gold,
    onPrimary = KelmaDesktopColors.Background,
    primaryContainer = KelmaDesktopColors.SurfaceHigh,
    onPrimaryContainer = KelmaDesktopColors.TextPrimary,
    secondary = KelmaDesktopColors.TextSecondary,
    onSecondary = KelmaDesktopColors.Background,
    tertiary = KelmaDesktopColors.Accent,
    onTertiary = KelmaDesktopColors.Background,
    background = KelmaDesktopColors.Background,
    onBackground = KelmaDesktopColors.TextPrimary,
    surface = KelmaDesktopColors.Background,
    onSurface = KelmaDesktopColors.TextPrimary,
    surfaceVariant = KelmaDesktopColors.Surface,
    onSurfaceVariant = KelmaDesktopColors.TextSecondary,
    outline = KelmaDesktopColors.Border,
    outlineVariant = KelmaDesktopColors.Border,
    error = KelmaColors.Bad,
    onError = KelmaDesktopColors.Background,
)

private val MobileShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
)

private val DesktopShapes = Shapes(
    small = RoundedCornerShape(5.dp),
    medium = RoundedCornerShape(7.dp),
    large = RoundedCornerShape(10.dp),
)

private val MobileTypography = Typography(
    headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)
private val DesktopTypography = Typography(
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Normal),
    headlineMedium = TextStyle(fontSize = 25.sp, lineHeight = 33.sp, fontWeight = FontWeight.Normal),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun KelmaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isDesktopApp) DesktopColorScheme else MobileColorScheme,
        shapes = if (isDesktopApp) DesktopShapes else MobileShapes,
        typography = if (isDesktopApp) DesktopTypography else MobileTypography,
        content = content,
    )
}
