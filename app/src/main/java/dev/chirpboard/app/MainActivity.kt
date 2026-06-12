package dev.chirpboard.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.chirpboard.app.core.ui.components.ChirpScaffoldSurface
import dev.chirpboard.app.core.ui.theme.ChirpTheme
import dev.chirpboard.app.core.ui.theme.DynamicColorPreference
import dev.chirpboard.app.navigation.AppNavHost
import dev.chirpboard.app.navigation.SharedAudioRequest
import dev.chirpboard.app.navigation.toSharedAudioRequestOrNull
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var dynamicColorPreference: DynamicColorPreference

    private var sharedAudioRequest by mutableStateOf<SharedAudioRequest?>(null)
    private var startupPromptsRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // LOAD-6: install the AndroidX splash screen before super.onCreate so the branded
        // starting window (parakeet mark on the brand surface) hands off smoothly into the app's
        // first Compose frame, instead of cutting from a flat default window.
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        sharedAudioRequest = intent.toSharedAudioRequestOrNull()

        setContent {
            // DECISIONS (Color/brand): brand lavender is the default; collect the user's
            // "Use system colors (Material You)" choice and pass it into ChirpTheme so power users
            // can opt into wallpaper-derived color. The keyboard IME reads the same preference.
            val useDynamicColor by dynamicColorPreference.useDynamicColor
                .collectAsStateWithLifecycle(initialValue = DynamicColorPreference.DEFAULT_USE_DYNAMIC_COLOR)
            ChirpTheme(dynamicColor = useDynamicColor) {
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
