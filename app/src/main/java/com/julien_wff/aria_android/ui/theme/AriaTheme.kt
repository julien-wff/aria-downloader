package com.julien_wff.aria_android.ui.theme

import com.julien_wff.aria_android.downloads.Appearance
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext

@Composable
fun AriaTheme(appearance: Appearance = Appearance.SYSTEM, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = appearance == Appearance.DARK ||
        (appearance == Appearance.SYSTEM && isSystemInDarkTheme())
    val colors = if (dark) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }
    SideEffect {
        (context as? ComponentActivity)?.enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
    }
    val type = Typography()
    MaterialTheme(
        colorScheme = colors,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        typography = Typography(
            displayLarge = type.displayLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
            displayMedium = type.displayMedium.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
            displaySmall = type.displaySmall.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
            headlineLarge = type.headlineLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
            headlineMedium = type.headlineMedium.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
            headlineSmall = type.headlineSmall.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
            titleLarge = type.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
            titleMedium = type.titleMedium.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
            titleSmall = type.titleSmall.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
            bodyLarge = type.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = type.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
            bodySmall = type.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
            labelLarge = type.labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp),
            labelMedium = type.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
            labelSmall = type.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        ),
        content = content,
    )
}
