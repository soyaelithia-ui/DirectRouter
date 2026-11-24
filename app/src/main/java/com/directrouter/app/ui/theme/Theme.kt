package com.directrouter.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

internal fun tr(es: String, en: String): String {
    return if (Locale.getDefault().language.equals("en", ignoreCase = true)) en else es
}

object RouterStatusColors {
    val ActiveEmerald = Color(0xFF10B981)
    val ActiveEmeraldContainerDark = Color(0xFF063B2C)
    val ActiveEmeraldContainerLight = Color(0xFFD1FAE5)

    val WarningAmber = Color(0xFFF59E0B)
    val WarningAmberContainerDark = Color(0xFF3E2A06)
    val WarningAmberContainerLight = Color(0xFFFEF3C7)

    val ErrorCoral = Color(0xFFF43F5E)
    val ErrorCoralContainerDark = Color(0xFF43111E)
    val ErrorCoralContainerLight = Color(0xFFFFE4E6)

    val InactiveSlate = Color(0xFF64748B)
    val ElectricCyan = Color(0xFF38BDF8)
    val DeepBlueAccent = Color(0xFF0284C7)
}

private val DirectRouterDarkColorScheme = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF042235),
    primaryContainer = Color(0xFF0C3654),
    onPrimaryContainer = Color(0xFFC7ECFF),
    secondary = Color(0xFF34D399),
    onSecondary = Color(0xFF032C1E),
    secondaryContainer = Color(0xFF0B3D2E),
    onSecondaryContainer = Color(0xFFA7F3D0),
    tertiary = Color(0xFF818CF8),
    onTertiary = Color(0xFF16194A),
    tertiaryContainer = Color(0xFF282D69),
    onTertiaryContainer = Color(0xFFE0E7FF),
    background = Color(0xFF0A101D),
    onBackground = Color(0xFFE6EDF7),
    surface = Color(0xFF0A101D),
    onSurface = Color(0xFFE6EDF7),
    surfaceVariant = Color(0xFF162135),
    onSurfaceVariant = Color(0xFF9FB0C8),
    surfaceContainerLowest = Color(0xFF070C16),
    surfaceContainerLow = Color(0xFF101829),
    surfaceContainer = Color(0xFF152033),
    surfaceContainerHigh = Color(0xFF1C2A42),
    surfaceContainerHighest = Color(0xFF243552),
    outline = Color(0xFF334666),
    outlineVariant = Color(0xFF22314A),
    error = Color(0xFFFB7185),
    onError = Color(0xFF4C0519),
    errorContainer = Color(0xFF4C1120),
    onErrorContainer = Color(0xFFFFD6DC),
)

private val DirectRouterLightColorScheme = lightColorScheme(
    primary = Color(0xFF0284C7),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF082F49),
    secondary = Color(0xFF059669),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF064E3B),
    tertiary = Color(0xFF4F46E5),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE0E7FF),
    onTertiaryContainer = Color(0xFF1E1B4B),
    background = Color(0xFFF3F6FB),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF3F6FB),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF1F5F9),
    surfaceContainerHighest = Color(0xFFE2E8F0),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFE11D48),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE4E6),
    onErrorContainer = Color(0xFF881337),
)

private val DirectRouterTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

private val DirectRouterShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
)

@Composable
fun DirectRouterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DirectRouterDarkColorScheme else DirectRouterLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = DirectRouterTypography,
        shapes = DirectRouterShapes,
        content = content,
    )
}
