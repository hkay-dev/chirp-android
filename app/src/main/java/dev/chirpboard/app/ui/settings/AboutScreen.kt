package dev.chirpboard.app.ui.settings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import dev.chirpboard.app.R
import dev.chirpboard.app.core.ui.components.SettingsListItem

/**
 * About screen showing app information and legal links.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val appInfo = remember { getAppInfo(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.desc_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(vertical = 16.dp),
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))

                // App icon
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )

                // App name
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                // Version info
                Text(
                    text = stringResource(R.string.about_version, appInfo.versionName),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.about_build, appInfo.versionCode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                // Description
                ListItem(
                    modifier = Modifier.fillMaxWidth(),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.about_description_title),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.about_description_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )

                Spacer(modifier = Modifier.height(8.dp))
            }

            item {
                // Legal links
                SettingsListItem(
                    icon = Icons.Default.Policy,
                    title = stringResource(R.string.about_privacy_title),
                    subtitle = stringResource(R.string.about_privacy_subtitle),
                    onClick = {
                        // Show in-app privacy notice
                    },
                )

                SettingsListItem(
                    icon = Icons.Default.Code,
                    title = stringResource(R.string.about_open_source_title),
                    subtitle = stringResource(R.string.about_open_source_subtitle),
                    onClick = {
                        openUrl(context, "https://github.com/k2-fsa/sherpa-onnx")
                    },
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Footer
                Text(
                    text = stringResource(R.string.about_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
