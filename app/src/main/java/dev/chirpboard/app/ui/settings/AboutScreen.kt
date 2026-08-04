package dev.chirpboard.app.ui.settings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import dev.chirpboard.app.R
import dev.chirpboard.app.core.ui.components.AnimatedAlertDialog
import dev.chirpboard.app.core.ui.components.ChirpSettingsDetailScaffold
import dev.chirpboard.app.core.ui.components.SettingsListItem
import dev.chirpboard.app.core.ui.theme.ChirpSpacing

/**
 * About screen showing app information and legal links.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val appInfo = remember { getAppInfo(context) }
    var showPrivacyNotice by rememberSaveable { mutableStateOf(false) }

    if (showPrivacyNotice) {
        PrivacyNoticeDialog(onDismiss = { showPrivacyNotice = false })
    }

    ChirpSettingsDetailScaffold(
        title = stringResource(R.string.about_title),
        onNavigateBack = onNavigateBack,
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            // Inter-group rhythm only; intra-group spacing is handled by Spacers inside each
            // item's Column so spacedBy never doubles up.
            verticalArrangement = Arrangement.spacedBy(ChirpSpacing.Large),
            contentPadding = PaddingValues(vertical = ChirpSpacing.Large),
        ) {
            item {
                // Brand + version block: one Column so spacedBy controls only the gap to the
                // next group, while the Spacers here set the intra-group rhythm.
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(modifier = Modifier.height(ChirpSpacing.Small))

                    // Brand identity: the real app icon (blue waveform-bird on the cream rounded
                    // square, the launcher PNG). Rendered with Image — Icon would tint the
                    // multicolor artwork into a flat silhouette.
                    Image(
                        painter = painterResource(R.mipmap.ic_launcher),
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                    )

                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    // PROP-8: one combined line, e.g. "Version 1.2.3 (456)", instead of separate
                    // Version and Build rows.
                    Text(
                        text =
                            stringResource(
                                R.string.about_version_build,
                                appInfo.versionName,
                                appInfo.versionCode,
                            ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                // Tagline above body so the headline reads larger than the version line above it.
                Column(
                    modifier = Modifier.padding(horizontal = ChirpSpacing.ScreenHorizontal),
                    verticalArrangement = Arrangement.spacedBy(ChirpSpacing.Small),
                ) {
                    Text(
                        text = stringResource(R.string.about_description_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.about_description_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                // Legal links + footer block: one Column so the two rows sit at row density and
                // the spacedBy gap stays between groups only.
                Column(modifier = Modifier.fillMaxWidth()) {
                    SettingsListItem(
                        icon = Icons.Rounded.Policy,
                        title = stringResource(R.string.about_privacy_title),
                        subtitle = stringResource(R.string.about_privacy_subtitle),
                        onClick = { showPrivacyNotice = true },
                    )

                    HorizontalDivider(modifier = Modifier.padding(start = AboutRowDividerInset))

                    SettingsListItem(
                        icon = Icons.Rounded.Code,
                        title = stringResource(R.string.about_open_source_title),
                        subtitle = stringResource(R.string.about_open_source_subtitle),
                        onClick = {
                            openUrl(context, context.getString(R.string.about_open_source_url))
                        },
                    )

                    Spacer(modifier = Modifier.height(ChirpSpacing.ExtraLarge))

                    Text(
                        text = stringResource(R.string.about_footer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = ChirpSpacing.ScreenHorizontal),
                    )
                }
            }
        }
    }
}

/** Indent for the About legal-row divider; matches the hub's 72dp inset past the 40dp leading
 *  icon so the divider starts flush with the row text. */
private val AboutRowDividerInset = 72.dp

/**
 * PLH-9: the in-app Privacy Notice — on-device transcription, local-only storage, the optional
 * cloud AI providers, and backup/restore behavior. The backup paragraph is the user-facing
 * wording for the policy documented in res/xml/data_extraction_rules.xml; keep them in sync.
 */
@Composable
private fun PrivacyNoticeDialog(onDismiss: () -> Unit) {
    AnimatedAlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Policy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text(stringResource(R.string.about_privacy_title)) },
        text = {
            // PROP-8: short bold lead-in label above each paragraph so the notice scans as
            // four topics rather than one wall of text.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ChirpSpacing.Medium),
            ) {
                PrivacyParagraph(
                    label = stringResource(R.string.about_privacy_notice_transcription_label),
                    body = stringResource(R.string.about_privacy_notice_transcription),
                )
                PrivacyParagraph(
                    label = stringResource(R.string.about_privacy_notice_storage_label),
                    body = stringResource(R.string.about_privacy_notice_storage),
                )
                PrivacyParagraph(
                    label = stringResource(R.string.about_privacy_notice_cloud_label),
                    body = stringResource(R.string.about_privacy_notice_cloud),
                )
                PrivacyParagraph(
                    label = stringResource(R.string.about_privacy_notice_backup_label),
                    body = stringResource(R.string.about_privacy_notice_backup),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.about_privacy_notice_confirm))
            }
        },
    )
}

/** A scannable privacy paragraph: a small bold lead-in label over its body text. */
@Composable
private fun PrivacyParagraph(
    label: String,
    body: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ChirpSpacing.ExtraSmall)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class AppInfo(
    val versionName: String,
    val versionCode: Long,
)

private fun getAppInfo(context: Context): AppInfo =
    try {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        AppInfo(
            versionName = packageInfo.versionName ?: "Unknown",
            versionCode = PackageInfoCompat.getLongVersionCode(packageInfo),
        )
    } catch (e: PackageManager.NameNotFoundException) {
        AppInfo(
            versionName = "Unknown",
            versionCode = 0,
        )
    }

private fun openUrl(
    context: Context,
    url: String,
) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        // Handle case where no browser is available
    }
}
