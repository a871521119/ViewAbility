package com.sohu.viewability.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Demo 使用的深色 Material 配色。 */
private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

/** Demo 使用的浅色 Material 配色。 */
private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40

    /* 如需定制完整 Material 颜色，可在这里继续补充以下颜色。
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

/**
 * 应用 Demo 的 Material 主题。
 *
 * @param darkTheme 是否使用深色主题。
 * @param dynamicColor 是否允许 Android 12 及以上使用系统动态颜色。
 * @param content 需要套用主题的 Compose 内容。
 */
@Composable
fun ViewAbilityTheme(
    /** 是否使用深色主题。 */
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** 是否使用 Android 12 及以上提供的系统动态颜色。 */
    dynamicColor: Boolean = true,
    /** 需要应用 MaterialTheme 的内容。 */
    content: @Composable () -> Unit
) {
    /** 根据系统版本、主题状态和动态颜色开关选择最终配色。 */
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            /** 当前 Compose 所属 Android 上下文。 */
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
