package ec.edu.espoch.omniflow.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ec.edu.espoch.omniflow.domain.model.LabStatus

val EspochRed = Color(0xFFC8102E)
val EspochRedDark = Color(0xFF9E0B22)
val RedSoft = Color(0xFFFDECEE)
val RedTint = Color(0xFFFFF6F7)
val Ink = Color(0xFF1F1F24)
val InkSoft = Color(0xFF6B6B76)
val Line = Color(0xFFECE6E7)
val CanvasBg = Color(0xFFF8F6F6)

val StatusGreen = Color(0xFF2E9E5B)
val StatusAmber = Color(0xFFE8A200)
val StatusGrey = Color(0xFF8A8A94)
val StatusPurple = Color(0xFF7B1FA2)

fun LabStatus.color(): Color = when (this) {
    LabStatus.LIBRE -> StatusGreen
    LabStatus.OCUPADO -> EspochRed
    LabStatus.PROXIMO -> StatusAmber
    LabStatus.FUERA_DE_HORARIO -> StatusGrey
    LabStatus.ANOMALO -> StatusPurple
}

private val Colors = lightColorScheme(
    primary = EspochRed,
    onPrimary = Color.White,
    primaryContainer = RedSoft,
    onPrimaryContainer = EspochRedDark,
    secondary = EspochRedDark,
    onSecondary = Color.White,
    background = CanvasBg,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = RedTint,
    onSurfaceVariant = InkSoft,
    outline = Line,
    outlineVariant = Line,
    error = Color(0xFFB3261E),
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)

@Composable
fun OmniFlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = AppTypography, content = content)
}
