package dev.phonecam.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = TealLight,
    onPrimary = TealInk,
    primaryContainer = Teal,
    onPrimaryContainer = TealContainer,
    secondary = SeaGreyLight,
    secondaryContainer = SeaGreyContainerDark,
    onSecondaryContainer = SeaGreyContainer,
    tertiary = LensLight,
    tertiaryContainer = LensContainerDark,
    onTertiaryContainer = LensContainer,
    background = Night,
    surface = Night,
  )

private val LightColorScheme =
  lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = TealContainer,
    onPrimaryContainer = TealDeep,
    secondary = SeaGrey,
    secondaryContainer = SeaGreyContainer,
    onSecondaryContainer = TealDeep,
    tertiary = Lens,
    tertiaryContainer = LensContainer,
    background = Mist,
    surface = Mist,
  )

@Composable
fun PhoneCamTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Off by default so the logo's colours are used; on Android 12+ this would follow the wallpaper.
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }
      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
