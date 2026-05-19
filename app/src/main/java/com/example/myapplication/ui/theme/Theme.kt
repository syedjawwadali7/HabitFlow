package com.example.myapplication.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class HabitTheme {
    MODERN, EMERALD_ZEN, AMOLED_NEON
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    habitTheme: HabitTheme = HabitTheme.MODERN,
    content: @Composable () -> Unit
) {
    val colorScheme = when (habitTheme) {
        HabitTheme.MODERN -> {
            if (darkTheme) darkColorScheme(
                primary = PrimaryModern,
                background = DarkBackground,
                surface = DarkSurface
            ) else lightColorScheme(
                primary = PrimaryModern,
                background = LightBackground,
                surface = LightSurface
            )
        }
        HabitTheme.EMERALD_ZEN -> {
            if (darkTheme) darkColorScheme(
                primary = EmeraldPrimary,
                background = EmeraldBackgroundDark,
                surface = Color(0xFF065F46)
            ) else lightColorScheme(
                primary = EmeraldPrimary,
                background = EmeraldBackgroundLight,
                surface = Color.White
            )
        }
        HabitTheme.AMOLED_NEON -> {
            if (darkTheme) darkColorScheme(
                primary = NeonPrimary,
                secondary = NeonSecondary,
                background = NeonBackgroundDark,
                surface = Color(0xFF121212)
            ) else lightColorScheme(
                primary = NeonPrimary,
                secondary = NeonSecondary,
                background = NeonBackgroundLight,
                surface = Color.White
            )
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
