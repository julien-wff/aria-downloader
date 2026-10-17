package com.julien_wff.aria_android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun AriaTheme(appearance: com.julien_wff.aria_android.downloads.Appearance = com.julien_wff.aria_android.downloads.Appearance.SYSTEM, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (appearance == com.julien_wff.aria_android.downloads.Appearance.DARK ||
        (appearance == com.julien_wff.aria_android.downloads.Appearance.SYSTEM && isSystemInDarkTheme())) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }
    MaterialTheme(colorScheme = colors, content = content)
}
