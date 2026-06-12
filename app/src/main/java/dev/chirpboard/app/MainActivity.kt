package dev.chirpboard.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.core.ui.components.ChirpScaffoldSurface
import dev.chirpboard.app.core.ui.theme.ChirpTheme
import dev.chirpboard.app.core.ui.theme.DynamicColorPreference
import dev.chirpboard.app.navigation.AppNavHost
import dev.chirpboard.app.navigation.Screen
import dev.chirpboard.app.navigation.SharedAudioRequest
import dev.chirpboard.app.navigation.toSharedAudioRequestOrNull
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var dynamicColorPreference: DynamicColorPreference

    @Inject
    lateinit var recordingStateManager: RecordingStateManager

    private var sharedAudioRequest by mutableStateOf<SharedAudioRequest?>(null)

    /**
     * One-shot deep-link target consumed by [AppNavHost] (keyboard-settings gear alias,
     * launcher shortcut). Cleared after navigation so rotations do not re-fire it.
     */
    private var pendingNavRoute by mutableStateOf<String?>(null)

    /**
     * LIF-04: the startup-prompt latch is restored from instance state so a rotation (or any
     * activity recreation) does not re-fire the system permission dialog after a denial.
     */
    private var startupPromptsRequested = false

    /** PRF-9: gates the splash hand-off until the first theme-preference emission lands. */
    private var themePreferenceLoaded = false

    /** PLT-06: re-checked in onResume so returning from system settings updates the banner. */
    private val notificationsEnabledState = mutableStateOf(true)

    /**
     * LIF-04: modern result API replaces the legacy requestPermissions(int) call — it survives
     * process death while the system dialog is showing. The result only needs to refresh the
     * notifications-off banner state; recording flows re-check permissions at their own gates.
     */
    private val startupPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            notificationsEnabledState.value = areNotificationsEnabled()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // LOAD-6: install the AndroidX splash screen before super.onCreate so the branded
        // starting window (parakeet mark on the brand surface) hands off smoothly into the app's
        // first Compose frame, instead of cutting from a flat default window.
        val splashScreen = installSplashScreen()
        // PRF-9: hold the splash for the (sub-30ms) DataStore theme read so Material You users
        // do not see a brand-lavender first frame snap to the dynamic palette.
        splashScreen.setKeepOnScreenCondition { !themePreferenceLoaded }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        startupPromptsRequested =
            savedInstanceState?.getBoolean(KEY_STARTUP_PROMPTS_REQUESTED) ?: false

        sharedAudioRequest = intent.toSharedAudioRequestOrNull()
        // Deep links only on a fresh launch: a recreation (rotation) re-delivers the same
        // intent, and re-navigating would yank the user away from wherever they went.
        if (savedInstanceState == null) {
            pendingNavRoute = intent.toDeepLinkRouteOrNull()
        }

        setContent {
            // DECISIONS (Color/brand): brand lavender is the default; collect the user's
            // "Use system colors (Material You)" choice and pass it into ChirpTheme so power users
            // can opt into wallpaper-derived color. The keyboard IME reads the same preference.
            val useDynamicColor by dynamicColorPreference.useDynamicColor
                .collectAsStateWithLifecycle(initialValue = null)
            themePreferenceLoaded = useDynamicColor != null
            ChirpTheme(
                dynamicColor = useDynamicColor ?: DynamicColorPreference.DEFAULT_USE_DYNAMIC_COLOR,
            ) {
                ChirpScaffoldSurface {
                    Column(modifier = Modifier.fillMaxSize()) {
                        NotificationsOffBanner(
                            recordingStateManager = recordingStateManager,
                            notificationsEnabled = notificationsEnabledState.value,
                            onOpenSettings = ::openNotificationSettings,
                        )
                        AppNavHost(
                            incomingSharedAudioRequest = sharedAudioRequest,
                            pendingDeepLinkRoute = pendingNavRoute,
                            onDeepLinkConsumed = { pendingNavRoute = null },
                            onStartupPromptGateChanged = ::maybeRequestStartupPrompts,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedAudioRequest = intent.toSharedAudioRequestOrNull()
        intent.toDeepLinkRouteOrNull()?.let { pendingNavRoute = it }
    }

    override fun onResume() {
        super.onResume()
        notificationsEnabledState.value = areNotificationsEnabled()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_STARTUP_PROMPTS_REQUESTED, startupPromptsRequested)
    }

    /**
     * Maps launch intents to one-shot navigation targets:
     * - the system keyboard-settings gear launches the [KEYBOARD_SETTINGS_ALIAS] activity-alias
     *   (input_method.xml settingsActivity) -> keyboard settings (LIF-15);
     * - the "Start recording" launcher shortcut sends [ACTION_START_RECORDING] -> Record screen
     *   with autoStart (the shortcut tap is a user interaction, so FGS-mic eligible).
     */
    private fun Intent.toDeepLinkRouteOrNull(): String? =
        when {
            component?.className == KEYBOARD_SETTINGS_ALIAS -> Screen.KeyboardSettings.route
            action == ACTION_START_RECORDING -> Screen.Record.createRoute(autoStart = true)
            else -> null
        }

    private fun maybeRequestStartupPrompts(canRequest: Boolean) {
        if (!canRequest || startupPromptsRequested) {
            return
        }

        startupPromptsRequested = true
        requestRuntimePermissions()
    }

    private fun requestRuntimePermissions() {
        val permissions =
            listOf(
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ).filter {
                ContextCompat.checkSelfPermission(this, it) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }

        if (permissions.isNotEmpty()) {
            startupPermissionsLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun areNotificationsEnabled(): Boolean =
        NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun openNotificationSettings() {
        val intent =
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "No activity handles APP_NOTIFICATION_SETTINGS", e)
        }
    }

    private companion object {
        const val TAG = "MainActivity"
        const val KEY_STARTUP_PROMPTS_REQUESTED = "startupPromptsRequested"
        const val KEYBOARD_SETTINGS_ALIAS = "dev.chirpboard.app.KeyboardSettingsLauncherActivity"
        const val ACTION_START_RECORDING = "dev.chirpboard.app.action.START_RECORDING"
    }
}

/**
 * PLT-06: when a recording is live but notifications are disabled, the foreground-service
 * notification (duration + Pause/Done) is invisible — the OS mic dot is the only signal.
 * This dismissible banner tells the user and links to the app's notification settings.
 * Android offers no third system prompt after two denials, so a settings link is the only
 * reliable re-enable affordance.
 */
@Composable
private fun NotificationsOffBanner(
    recordingStateManager: RecordingStateManager,
    notificationsEnabled: Boolean,
    onOpenSettings: () -> Unit,
) {
    val recordingState by recordingStateManager.state.collectAsStateWithLifecycle()
    var dismissed by rememberSaveable { mutableStateOf(false) }
    val recordingActive =
        recordingState is RecordingState.Recording ||
            recordingState is RecordingState.Starting ||
            recordingState is RecordingState.Paused

    if (notificationsEnabled || !recordingActive || dismissed) {
        return
    }

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .statusBarsPadding(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.notifications_off_banner_text),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(modifier = Modifier.align(Alignment.End)) {
                TextButton(onClick = { dismissed = true }) {
                    Text(stringResource(R.string.notifications_off_banner_dismiss))
                }
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.notifications_off_banner_action))
                }
            }
        }
    }
}
