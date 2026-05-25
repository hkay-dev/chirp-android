package dev.chirpboard.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.chirpboard.app.core.ui.theme.ChirpTheme
import dev.chirpboard.app.navigation.AppNavHost
import dev.chirpboard.app.navigation.SharedAudioRequest
import dev.chirpboard.app.navigation.toSharedAudioRequestOrNull

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var sharedAudioRequest by mutableStateOf<SharedAudioRequest?>(null)
    private var startupPromptsRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedAudioRequest = intent.toSharedAudioRequestOrNull()

        setContent {
            ChirpTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    AppNavHost(
                        incomingSharedAudioRequest = sharedAudioRequest,
                        onStartupPromptGateChanged = ::maybeRequestStartupPrompts,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedAudioRequest = intent.toSharedAudioRequestOrNull()
    }

    private fun maybeRequestStartupPrompts(canRequest: Boolean) {
        if (!canRequest || startupPromptsRequested) {
            return
        }

        startupPromptsRequested = true
        requestRuntimePermissions()
        requestAllFilesAccessPermission()
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(android.Manifest.permission.RECORD_AUDIO)
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
        }
    }

    private fun requestAllFilesAccessPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) {
            return
        }

        try {
            val intent =
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
        }
    }
}
