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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.core.ui.components.ChirpScaffoldSurface
import dev.chirpboard.app.core.ui.theme.ChirpTheme
import dev.chirpboard.app.core.ui.theme.DynamicColorPreference
import dev.chirpboard.app.data.repository.ProfileRepository
import dev.chirpboard.app.navigation.AppNavHost
import dev.chirpboard.app.navigation.Screen
import dev.chirpboard.app.core.transcription.ACTION_OPEN_TRANSCRIPTION_RECORDING
import dev.chirpboard.app.core.transcription.EXTRA_TRANSCRIPTION_RECORDING_ID
import dev.chirpboard.app.navigation.SharedAudioRequest
import dev.chirpboard.app.navigation.toSharedAudioRequestOrNull
import dev.chirpboard.app.shortcut.ProfileShortcutManager
import dev.chirpboard.app.feature.transcription.TerminalRecordingNotificationDelivery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var dynamicColorPreference: DynamicColorPreference

    @Inject
    lateinit var recordingStateManager: RecordingStateManager

    @Inject
    lateinit var profileRepository: ProfileRepository

    @Inject
    lateinit var profileShortcutManager: ProfileShortcutManager

    @Inject
    lateinit var terminalRecordingNotificationDelivery: TerminalRecordingNotificationDelivery

    private val terminalNotificationRecovery by lazy {
        TerminalNotificationRecoveryLauncher(
            scope = lifecycleScope,
            delivery = terminalRecordingNotificationDelivery,
            onFailure = { error -> Log.w(TAG, "Failed to replay pending transcription notifications", error) },
        )
    }

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
            refreshNotificationAccess()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // LOAD-6: install the AndroidX splash screen before super.onCreate so the branded
        // starting window (parakeet mark on the brand surface) hands off smoothly into the app's
        // first Compose frame, instead of cutting from a flat default window.
        val splashScreen = installSplashScreen()
        // PRF-9: hold the splash for the (sub-30ms) DataStore theme read so Material You users
        // do not see a brand-lavender first frame snap to the dynamic palette. Wall-clock
        // capped: a stalled preference read must degrade to a palette snap, not an app that
        // hangs on its splash forever.
        val splashHoldStart = android.os.SystemClock.uptimeMillis()
        splashScreen.setKeepOnScreenCondition {
            !themePreferenceLoaded &&
                android.os.SystemClock.uptimeMillis() - splashHoldStart < SPLASH_THEME_HOLD_MAX_MS
        }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        startupPromptsRequested =
            savedInstanceState?.getBoolean(KEY_STARTUP_PROMPTS_REQUESTED) ?: false

        // Shares and deep links only on a fresh launch: a recreation re-delivers the same
        // intent. For deep links, re-navigating would yank the user away from wherever they
        // went. For shared audio it is worse: a relaunch after process death redelivers the
        // system's stored copy of the intent WITHOUT the in-process dedup token extra, so
        // re-parsing would mint a new token and import the share again — a duplicate
        // recording whenever the process died after the import persisted. In-flight imports
        // survive rotation inside the activity-scoped SharedAudioHandoffViewModel.
        if (savedInstanceState == null) {
            sharedAudioRequest = intent.toSharedAudioRequestOrNull()
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

        observeProfilesForShortcuts()
    }

    /**
     * Keeps the dynamic per-profile launcher shortcuts in sync with the profile set. Collected on
     * a background dispatcher (ShortcutManager IPC + icon resource decode) while the activity is at
     * least STARTED; [ProfileRepository.getAllProfiles] already de-duplicates identical Room
     * re-emissions, and the profile list is distinct-checked again here so a shortcut rewrite only
     * happens when the set actually changed. Error emissions (which carry an empty fallback list)
     * are skipped — mirroring one would wipe every dynamic shortcut over a transient DB hiccup.
     */
    private fun observeProfilesForShortcuts() {
        // repeatOnLifecycle must be entered from the main dispatcher; the Room collection and the
        // ShortcutManager push themselves run on Dispatchers.Default so no IO/IPC touches the UI
        // thread.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                profileRepository
                    .getAllProfiles()
                    .mapNotNull { state -> state.value.takeIf { state.errorMessage == null } }
                    .distinctUntilChanged()
                    .flowOn(Dispatchers.Default)
                    .collect { profiles ->
                        withContext(Dispatchers.Default) {
                            try {
                                profileShortcutManager.pushDynamicShortcuts(profiles)
                            } catch (e: IllegalStateException) {
                                // e.g. rate-limited by the launcher; the next change retries.
                                Log.w(TAG, "Failed to refresh profile shortcuts", e)
                            } catch (e: IllegalArgumentException) {
                                // e.g. the launcher rejects the shortcut count; never crash over
                                // launcher decoration.
                                Log.w(TAG, "Failed to refresh profile shortcuts", e)
                            }
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
        refreshNotificationAccess()
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
     *   with autoStart (the shortcut tap is a user interaction, so FGS-mic eligible);
     * - a per-profile launcher/pinned shortcut sends [ACTION_RECORD_WITH_PROFILE] with an
     *   [EXTRA_PROFILE_ID] UUID -> Record screen with autoStart pre-selecting that profile. An
     *   absent or unparseable profile id falls back to a plain autoStart recording.
     */
    private fun Intent.toDeepLinkRouteOrNull(): String? =
        when {
            component?.className == KEYBOARD_SETTINGS_ALIAS -> Screen.KeyboardSettings.route
            action == ACTION_START_RECORDING -> Screen.Record.createRoute(autoStart = true)
            action == ACTION_RECORD_WITH_PROFILE ->
                Screen.Record.createRoute(
                    autoStart = true,
                    profileId = parseUuidOrNull(getStringExtra(EXTRA_PROFILE_ID)),
                )
            action == ACTION_OPEN_TRANSCRIPTION_RECORDING ->
                parseUuidOrNull(getStringExtra(EXTRA_TRANSCRIPTION_RECORDING_ID))
                    ?.let(Screen.ProcessingStudio::createRoute)
            else -> null
        }

    /** Accepts the extra only when it is a well-formed UUID; otherwise the launch falls back. */
    private fun parseUuidOrNull(raw: String?): String? =
        raw?.let {
            try {
                UUID.fromString(it).toString()
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Ignoring non-UUID navigation extra", e)
                null
            }
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

    private fun refreshNotificationAccess() {
        val enabled = areNotificationsEnabled()
        notificationsEnabledState.value = enabled
        terminalNotificationRecovery.onNotificationAccess(enabled)
    }

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

    companion object {
        private const val TAG = "MainActivity"
        private const val KEY_STARTUP_PROMPTS_REQUESTED = "startupPromptsRequested"

        /** Upper bound on holding the splash for the theme read (normally sub-30ms). */
        private const val SPLASH_THEME_HOLD_MAX_MS = 500L
        private const val KEYBOARD_SETTINGS_ALIAS = "dev.chirpboard.app.KeyboardSettingsLauncherActivity"
        private const val ACTION_START_RECORDING = "dev.chirpboard.app.action.START_RECORDING"

        /**
         * Explicit-component action a per-profile launcher/pinned shortcut sends to start a
         * recording pre-selecting [EXTRA_PROFILE_ID]. Read by [ProfileShortcutManager] when it
         * builds shortcut intents.
         */
        const val ACTION_RECORD_WITH_PROFILE = "dev.chirpboard.app.action.RECORD_WITH_PROFILE"

        /** String UUID of the profile to record with, carried by [ACTION_RECORD_WITH_PROFILE]. */
        const val EXTRA_PROFILE_ID = "profileId"
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
