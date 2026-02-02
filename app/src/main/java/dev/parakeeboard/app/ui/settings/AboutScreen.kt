package dev.parakeeboard.app.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * About screen showing app information and legal links.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val appInfo = remember { getAppInfo(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            
            // App icon
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            
            // App name
            Text(
                text = "Parakeet Keyboard",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            // Version info
            Text(
                text = "Version ${appInfo.versionName}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Build ${appInfo.versionCode}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Description
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Offline voice-to-text keyboard with AI-powered processing",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Transcribe your voice locally using the Parakeet TDT model, then optionally polish your text with Gemini AI.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Legal links
            SettingsItem(
                icon = Icons.Default.Policy,
                title = "Privacy Policy",
                subtitle = "How we handle your data",
                onClick = {
                    // Placeholder URL - replace with actual privacy policy
                    openUrl(context, "https://example.com/privacy")
                }
            )
            
            SettingsItem(
                icon = Icons.Default.Description,
                title = "Terms of Service",
                subtitle = "Usage terms and conditions",
                onClick = {
                    // Placeholder URL - replace with actual terms
                    openUrl(context, "https://example.com/terms")
                }
            )
            
            SettingsItem(
                icon = Icons.Default.Code,
                title = "Open Source Licenses",
                subtitle = "Third-party libraries used",
                onClick = {
                    // Placeholder - implement license viewer
                }
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Footer
            Text(
                text = "Made with care",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private data class AppInfo(
    val versionName: String,
    val versionCode: Long
)

private fun getAppInfo(context: Context): AppInfo {
    return try {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        AppInfo(
            versionName = packageInfo.versionName ?: "Unknown",
            versionCode = packageInfo.longVersionCode
        )
    } catch (e: PackageManager.NameNotFoundException) {
        AppInfo(
            versionName = "Unknown",
            versionCode = 0
        )
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    } catch (e: Exception) {
        // Handle case where no browser is available
    }
}
