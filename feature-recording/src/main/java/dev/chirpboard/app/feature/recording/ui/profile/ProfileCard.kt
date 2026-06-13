package dev.chirpboard.app.feature.recording.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.ui.theme.ChirpSpacing
import dev.chirpboard.app.core.ui.R as CoreR
import dev.chirpboard.app.feature.recording.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileCard(
    profileItem: ProfileItemState,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }
    val profile = profileItem.profile

    ListItem(
        modifier = modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(
                text = profile.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            shape = CircleShape,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                val icon = profile.icon
                if (!icon.isNullOrBlank()) {
                    Text(
                        text = icon,
                        style = MaterialTheme.typography.titleLarge,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        supportingContent = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ChirpSpacing.ExtraSmall),
                verticalArrangement = Arrangement.spacedBy(ChirpSpacing.ExtraSmall),
            ) {
                if (profile.autoTranscribe) {
                    FeatureChip(label = stringResource(R.string.rec_profile_chip_transcribe))
                }
                if (profile.autoTitle) {
                    FeatureChip(label = stringResource(R.string.rec_profile_chip_auto_title))
                }
                if (profile.autoSummary) {
                    FeatureChip(label = stringResource(R.string.rec_profile_chip_auto_summary))
                }
                if (profile.autoExportToObsidian) {
                    FeatureChip(label = stringResource(R.string.rec_profile_chip_obsidian))
                }
                val processingMode = profile.defaultProcessingMode
                if (!processingMode.isNullOrBlank() && processingMode != "none") {
                    FeatureChip(label = profileProcessingModeLabel(processingMode))
                }
            }
        },
        trailingContent = {
            // Menu
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(CoreR.string.desc_more_options),
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(CoreR.string.rec_delete)) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                    )
                }
            }
        }
    )
}

@Composable
private fun FeatureChip(
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = ChirpSpacing.Small, vertical = ChirpSpacing.ExtraSmall),
        )
    }
}
