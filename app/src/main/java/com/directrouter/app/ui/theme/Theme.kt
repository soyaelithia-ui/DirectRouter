package com.directrouter.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.directrouter.app.data.AppThemeMode
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

/**
 * "Pulso Eléctrico / Mesh-Node" Obsidian-Blue Dark Color Scheme.
 */
private val DirectRouterDarkColorScheme = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF042235),
    primaryContainer = Color(0xFF0B3554),
    onPrimaryContainer = Color(0xFFCDEEFF),
    secondary = Color(0xFF10B981),
    onSecondary = Color(0xFF02281B),
    secondaryContainer = Color(0xFF073B2B),
    onSecondaryContainer = Color(0xFFA7F3D0),
    tertiary = Color(0xFF34D399),
    onTertiary = Color(0xFF032C1E),
    tertiaryContainer = Color(0xFF0B4030),
    onTertiaryContainer = Color(0xFFD1FAE5),
    background = Color(0xFF060B16),
    onBackground = Color(0xFFE9F0FA),
    surface = Color(0xFF060B16),
    onSurface = Color(0xFFE9F0FA),
    surfaceVariant = Color(0xFF121D33),
    onSurfaceVariant = Color(0xFF9BB0CB),
    surfaceContainerLowest = Color(0xFF040811),
    surfaceContainerLow = Color(0xFF0A1324),
    surfaceContainer = Color(0xFF0F1B31),
    surfaceContainerHigh = Color(0xFF162542),
    surfaceContainerHighest = Color(0xFF1E3154),
    outline = Color(0xFF31476B),
    outlineVariant = Color(0xFF1D2D49),
    error = Color(0xFFFB7185),
    onError = Color(0xFF4C0519),
    errorContainer = Color(0xFF4C1120),
    onErrorContainer = Color(0xFFFFD6DC),
)

/**
 * True-Black AMOLED Color Scheme with pure #000000 background & lowest surfaces,
 * crisp dark elevated cards (#0B0F17 / #111827), and high-contrast Electric Cyan / Emerald accents.
 */
private val DirectRouterAmoledColorScheme = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF001E2E),
    primaryContainer = Color(0xFF082D47),
    onPrimaryContainer = Color(0xFFD6F1FF),
    secondary = Color(0xFF10B981),
    onSecondary = Color(0xFF002116),
    secondaryContainer = Color(0xFF053325),
    onSecondaryContainer = Color(0xFFA7F3D0),
    tertiary = Color(0xFF34D399),
    onTertiary = Color(0xFF002116),
    tertiaryContainer = Color(0xFF063829),
    onTertiaryContainer = Color(0xFFD1FAE5),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF111827),
    onSurfaceVariant = Color(0xFF94A3B8),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF070A10),
    surfaceContainer = Color(0xFF0B0F17),
    surfaceContainerHigh = Color(0xFF111827),
    surfaceContainerHighest = Color(0xFF1A2233),
    outline = Color(0xFF2D3B50),
    outlineVariant = Color(0xFF1B2433),
    error = Color(0xFFFB7185),
    onError = Color(0xFF4C0519),
    errorContainer = Color(0xFF420D1A),
    onErrorContainer = Color(0xFFFFD6DC),
)

/**
 * Clean Crisp Light Color Scheme harmonized with the "Pulso Eléctrico / Mesh-Node" palette.
 */
private val DirectRouterLightColorScheme = lightColorScheme(
    primary = Color(0xFF0284C7),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCF2FE),
    onPrimaryContainer = Color(0xFF082F49),
    secondary = Color(0xFF059669),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF064E3B),
    tertiary = Color(0xFF0D9488),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCCFBF1),
    onTertiaryContainer = Color(0xFF134E4A),
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF0B1324),
    surface = Color(0xFFF4F7FB),
    onSurface = Color(0xFF0B1324),
    surfaceVariant = Color(0xFFE3EAF4),
    onSurfaceVariant = Color(0xFF475569),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEFF4FA),
    surfaceContainerHighest = Color(0xFFE2EAF4),
    outline = Color(0xFFC5D2E3),
    outlineVariant = Color(0xFFDFE7F2),
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
fun isDarkThemeActive(themeMode: AppThemeMode): Boolean {
    return when (themeMode) {
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK, AppThemeMode.AMOLED -> true
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
}

@Composable
fun DirectRouterTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val isDark = isDarkThemeActive(themeMode)
    val isAmoled = themeMode == AppThemeMode.AMOLED
    val useDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val colorScheme = if (useDynamic) {
        val context = LocalContext.current
        if (isDark) {
            val dynamicDark = dynamicDarkColorScheme(context)
            if (isAmoled) {
                dynamicDark.copy(
                    background = Color(0xFF000000),
                    surface = Color(0xFF000000),
                    surfaceContainerLowest = Color(0xFF000000),
                    surfaceContainerLow = Color(0xFF070A10),
                    surfaceContainer = Color(0xFF0B0F17),
                    surfaceContainerHigh = Color(0xFF111827),
                    surfaceContainerHighest = Color(0xFF1A2233),
                    surfaceVariant = Color(0xFF111827),
                    outlineVariant = Color(0xFF1B2433),
                )
            } else {
                dynamicDark
            }
        } else {
            dynamicLightColorScheme(context)
        }
    } else {
        when {
            isAmoled -> DirectRouterAmoledColorScheme
            isDark -> DirectRouterDarkColorScheme
            else -> DirectRouterLightColorScheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = DirectRouterTypography,
        shapes = DirectRouterShapes,
        content = content,
    )
}
