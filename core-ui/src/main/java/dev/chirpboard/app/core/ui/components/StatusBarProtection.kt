package dev.chirpboard.app.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars

@Composable
fun StatusBarProtection(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
) {
    val topInsetPx = WindowInsets.statusBars.getTop(LocalDensity.current)
    if (topInsetPx <= 0) {
        return
    }

    Spacer(
        modifier =
            modifier
                .fillMaxWidth()
                .height(with(LocalDensity.current) { (topInsetPx * 1.1f).toDp().coerceAtLeast(1.dp) })
                .background(
                    brush =
                        Brush.verticalGradient(
                            colors =
                                listOf(
                                    color.copy(alpha = 0.96f),
                                    color.copy(alpha = 0.72f),
                                    Color.Transparent,
                                ),
                        ),
                ),
    )
}
