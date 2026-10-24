package com.mylibrary.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Teal = Color(0xFF0F766E)
private val Amber = Color(0xFFD97706)

private val Light = lightColorScheme(
    primary = Teal, onPrimary = Color.White, primaryContainer = Color(0xFFCCFBF1), onPrimaryContainer = Color(0xFF042F2E),
    secondary = Amber, background = Color(0xFFF8FAFA), surface = Color.White, surfaceVariant = Color(0xFFE7EFEE),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF5EEAD4), onPrimary = Color(0xFF042F2E), primaryContainer = Color(0xFF115E59), onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFFFBBF24), background = Color(0xFF0B1514), surface = Color(0xFF111D1C), surfaceVariant = Color(0xFF1C2B2A),
)

private val Type = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Type, content = content)
}
