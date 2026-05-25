package dev.chirpboard.app.feature.llm.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun LlmProviderIcon(
    provider: LlmProvider,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    size: Dp = 22.dp,
) {
    Icon(
        painter = painterResource(provider.iconRes),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
internal fun LlmProviderIconBadge(
    provider: LlmProvider,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Surface(
        modifier = modifier.size(40.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            LlmProviderIcon(
                provider = provider,
                contentDescription = contentDescription,
                size = 22.dp,
            )
        }
    }
}
