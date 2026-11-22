package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val PulseDarkColorScheme = darkColorScheme(
    primary = PulseCyan,
    onPrimary = ObsidianBackground,
    primaryContainer = PulseCyanDim,
    onPrimaryContainer = TextPrimary,
    secondary = ConnectedEmerald,
    onSecondary = ObsidianBackground,
    secondaryContainer = ConnectedEmeraldDim,
    onSecondaryContainer = TextPrimary,
    tertiary = ConnectingAmber,
    onTertiary = ObsidianBackground,
    tertiaryContainer = ConnectingAmberDim,
    onTertiaryContainer = TextPrimary,
    error = ErrorCoral,
    onError = TextPrimary,
    errorContainer = ErrorCoralDim,
    onErrorContainer = TextPrimary,
    background = ObsidianBackground,
    onBackground = TextPrimary,
    surface = SlateSurface,
    onSurface = TextPrimary,
    surfaceVariant = SlateSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = ObsidianBackground,
    surfaceContainerLow = SlateSurface,
    surfaceContainer = SlateSurfaceElevated,
    surfaceContainerHigh = SlateSurfaceHigh,
    outline = HairlineBorder,
    outlineVariant = HairlineBorder,
)

private val PulseShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun PulseVpnTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = PulseDarkColorScheme,
        typography = PulseTypography,
        shapes = PulseShapes,
        content = content,
    )
}
