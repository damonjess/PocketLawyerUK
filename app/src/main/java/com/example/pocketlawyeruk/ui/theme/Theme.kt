package com.example.pocketlawyeruk.ui.theme

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

// Top-Secret Cyber HUD Palette
val MidnightBg = Color(0xFF05080D)        // Deep obsidian terminal black
val CardSurface = Color(0xFF0B121C)       // Stealth tactical slate
val CardSurfaceAlt = Color(0xFF0E1826)    // Elevated HUD panel
val CardBorder = Color(0xFF1A3A4E)        // Glowing cyan-tinted hairline
val CreamAccent = Color(0xFF00F0FF)       // Primary Electric Cyber Cyan
val CreamTextDark = Color(0xFF031016)     // High-contrast ink on cyan buttons
val MutedButtonBg = Color(0xFF1A2836)     // Disabled stealth slate
val TextPrimary = Color(0xFFE6F4F8)       // Crisp cold terminal white
val TextSecondary = Color(0xFF6E92A8)     // Tactical radar blue-grey

// Semantic Cyber Status Accents
val AmberBg = Color(0xFF291D04)
val AmberText = Color(0xFFFFB800)         // Warning Amber
val EmeraldBg = Color(0xFF06261C)
val EmeraldText = Color(0xFF00FF9C)       // Matrix Verified Green
val RoseBg = Color(0xFF2D0714)
val RoseText = Color(0xFFFF2A5F)          // Classified Violation Crimson

// Tactical Typography & Shapes
val SerifDisplay = FontFamily.Monospace
val CyberMono = FontFamily.Monospace
val TacticalCardShape = CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp, topEnd = 4.dp, bottomStart = 4.dp)
val TacticalPillShape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp, topEnd = 2.dp, bottomStart = 2.dp)

private val CyberColorScheme = darkColorScheme(
    background = MidnightBg,
    surface = CardSurface,
    primary = CreamAccent,
    onPrimary = CreamTextDark,
    secondary = EmeraldText,
    onSecondary = CreamTextDark,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    outline = CardBorder
)

@Composable
fun PocketLawyerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CyberColorScheme,
        content = content
    )
}
