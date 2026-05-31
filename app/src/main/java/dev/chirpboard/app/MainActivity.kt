package dev.chirpboard.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.chirpboard.app.core.ui.components.ChirpScaffoldSurface
import dev.chirpboard.app.core.ui.theme.ChirpTheme
import dev.chirpboard.app.navigation.AppNavHost
import dev.chirpboard.app.navigation.SharedAudioRequest
import dev.chirpboard.app.navigation.toSharedAudioRequestOrNull

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var sharedAudioRequest by mutableStateOf<SharedAudioRequest?>(null)
    private var startupPromptsRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        sharedAudioRequest = intent.toSharedAudioRequestOrNull()

        setContent {
            ChirpTheme {
                ChirpScaffoldSurface {
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
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(android.Manifest.permission.RECORD_AUDIO)
        }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
        }
    }
}
