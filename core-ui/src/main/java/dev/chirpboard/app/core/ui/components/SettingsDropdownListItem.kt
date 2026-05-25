package dev.chirpboard.app.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.ui.R

/**
 * Settings row with an enum-style dropdown picker in the trailing slot.
 */
@Composable
fun <T> SettingsDropdownListItem(
    title: String,
    supportingText: String,
    options: List<T>,
    selectedOption: T,
    optionLabel: @Composable (T) -> String,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingContent: @Composable (() -> Unit)? = null,
    additionalSupportingContent: @Composable (() -> Unit)? = null,
    trailingIconContentDescription: String? = null,
) {
    var isDropdownExpanded by remember { mutableStateOf(false) }

    val textColor by animateColorAsState(
        targetValue =
            if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "dropdownTextColor",
    )
    val iconTint by animateColorAsState(
        targetValue =
            if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "dropdownIconTint",
    )

    ListItem(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { isDropdownExpanded = true },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = leadingContent,
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                additionalSupportingContent?.invoke()
            }
        },
        trailingContent = {
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier =
                        Modifier
                            .clip(MaterialTheme.shapes.small)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(
                                    alpha = if (enabled) 1f else 0.5f,
                                ),
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = optionLabel(selectedOption),
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = trailingIconContentDescription,
                        tint = iconTint,
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(
                    expanded = isDropdownExpanded,
                    onDismissRequest = { isDropdownExpanded = false },
                ) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(optionLabel(option)) },
                            onClick = {
                                onOptionSelected(option)
                                isDropdownExpanded = false
                            },
                            trailingIcon =
                                if (option == selectedOption) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = stringResource(R.string.desc_selected),
                                        )
                                    }
                                } else {
                                    null
                                },
                        )
                    }
                }
            }
        },
    )
}
