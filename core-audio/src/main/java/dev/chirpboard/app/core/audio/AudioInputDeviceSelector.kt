package dev.chirpboard.app.core.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One capture session as built by [AudioInputDeviceSelector.buildAudioRecord]: the
 * configured [AudioRecord] plus the token of this session's active-device publication.
 * Engines hold the token and pass it back to
 * [AudioInputDeviceSelector.clearActiveDevice] at teardown, so a finished session's
 * late clear can never clobber the state a newer session has already published.
 */
data class AudioCaptureSession(
    val record: AudioRecord,
    val sessionToken: Long,
)

/**
 * The ACTIVE capture device disappeared mid-session (hot-unplug, Bluetooth link drop).
 * Emitted on [AudioInputDeviceSelector.deviceLostEvents] so every surface can react to
 * the same physical event; [deviceName] is best-effort (the published summary's name,
 * else recovered from the removal notification) for "X disconnected" messaging.
 */
data class DeviceLostEvent(
    val deviceId: Int,
    val deviceName: String?,
)

@Singleton
class AudioInputDeviceSelector
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val audioSettingsStore: AudioSettingsStore,
    ) {
        private val audioManager = context.getSystemService(AudioManager::class.java)
        private val _activeDeviceLabel = MutableStateFlow<String?>(null)
        val activeDeviceLabel: StateFlow<String?> = _activeDeviceLabel.asStateFlow()

        /**
         * The device the current capture session actually selected (with any
         * preferred-device fallback annotation), shared app-wide so every surface can
         * display it. Null while no capture is live.
         */
        private val _activeDevice = MutableStateFlow<ActiveInputDevice?>(null)
        val activeDevice: StateFlow<ActiveInputDevice?> = _activeDevice.asStateFlow()

        /**
         * Live snapshot of connected input devices, refreshed by [AudioDeviceCallback]
         * on hot-plug so pickers on every surface share one always-current list.
         */
        private val _availableDevices = MutableStateFlow<List<AudioInputDeviceSummary>>(emptyList())
        val availableDevices: StateFlow<List<AudioInputDeviceSummary>> = _availableDevices.asStateFlow()

        /**
         * Bumped whenever input devices are hot-plugged so settings UI can refresh its
         * device list while open instead of showing a stale one-shot snapshot.
         */
        private val _devicesChangedTick = MutableStateFlow(0L)
        val devicesChangedTick: StateFlow<Long> = _devicesChangedTick.asStateFlow()

        /**
         * Hot-unplug events for the ACTIVE capture device, shared so every surface
         * (service auto-stop, keyboard hint, recognition advisory) can react to the same
         * physical event. Buffered so emission from the main-thread device callback never
         * drops under a briefly slow collector.
         */
        private val _deviceLostEvents =
            MutableSharedFlow<DeviceLostEvent>(extraBufferCapacity = DEVICE_LOST_EVENT_BUFFER)
        val deviceLostEvents: SharedFlow<DeviceLostEvent> = _deviceLostEvents.asSharedFlow()

        /**
         * Guards the per-session mutable state below: capture starts and routing
         * refreshes write from IO/capture threads while [deviceCallback] reads on the
         * main thread, so every compound read/mutation of [activeDeviceId],
         * [latestSessionToken] and the active-device flows happens under this monitor.
         */
        private val stateLock = Any()

        /** The live capture session's device id; guarded by [stateLock]. */
        private var activeDeviceId: Int? = null

        /**
         * Token of the latest active-device publication, monotonically increasing from 1
         * for the first capture session. Guarded by [stateLock]; see [clearActiveDevice].
         */
        private var latestSessionToken = 0L

        /** Legacy single-listener adapter over [deviceLostEvents]; see [setOnActiveDeviceLostListener]. */
        @Volatile
        private var onActiveDeviceLost: ((lostDeviceName: String?) -> Unit)? = null

        /** Routing listeners registered via [observeRouting], keyed by record identity. */
        private val routingListeners = mutableMapOf<AudioRecord, AudioRouting.OnRoutingChangedListener>()

        private val deviceCallback =
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    if (addedDevices.any { it.isSource }) {
                        _devicesChangedTick.value += 1
                        refreshAvailableDevices()
                    }
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    if (removedDevices.any { it.isSource }) {
                        _devicesChangedTick.value += 1
                        refreshAvailableDevices()
                    }
                    // Snapshot id + name together under the lock so a torn view (an id
                    // whose summary is not published yet) is impossible; dispatch outside
                    // it so listener code never runs while holding the monitor.
                    val lost =
                        synchronized(stateLock) {
                            val activeId = activeDeviceId
                            if (activeId == null || removedDevices.none { it.id == activeId }) {
                                null
                            } else {
                                val lostName =
                                    _activeDevice.value?.summary?.productName
                                        ?: removedDevices
                                            .firstOrNull { it.id == activeId }
                                            ?.let { summaryFor(it).productName }
                                DeviceLostEvent(deviceId = activeId, deviceName = lostName)
                            }
                        } ?: return
                    Log.w(TAG, "Active input device disconnected: ${lost.deviceName}")
                    _deviceLostEvents.tryEmit(lost)
                    onActiveDeviceLost?.invoke(lost.deviceName)
                }
            }

        init {
            audioManager.registerAudioDeviceCallback(deviceCallback, null)
            refreshAvailableDevices()
        }

        fun listInputDevices(): List<AudioInputDeviceSummary> =
            surfaceableInputDevices(inputDevices().map(::summaryFor))

        /**
         * Whether the app may read real Bluetooth device names/addresses. Without the
         * grant pickers degrade to type labels ("Bluetooth") and composite selection keys.
         */
        fun hasBluetoothConnectPermission(): Boolean =
            runCatching {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)

        /**
         * Re-enumerates devices immediately — pickers call this after a BLUETOOTH_CONNECT
         * grant so real Bluetooth names replace the type-label placeholders.
         */
        fun refreshDevices() {
            _devicesChangedTick.value += 1
            refreshAvailableDevices()
        }

        /**
         * Capture-start device selection: the persisted preference when its device is
         * present, otherwise the highest-priority connected device (USB > Bluetooth >
         * wired > built-in). Selection runs over the recordable list (see
         * [recordableInputDevices]) so a stale manual key can never pin a non-recordable
         * "Other" endpoint the picker does not show. Publishes the choice (including the
         * preferred-absent fallback annotation) on [activeDevice] for every surface to
         * display.
         */
        suspend fun resolvePreferredDevice(): AudioDeviceInfo? = resolveAndPublish().device

        /** One capture-start resolution: the chosen platform device plus its session token. */
        private data class ResolvedSelection(
            val device: AudioDeviceInfo?,
            val sessionToken: Long,
        )

        private suspend fun resolveAndPublish(): ResolvedSelection {
            val settings = audioSettingsStore.currentSettings()
            val devices = inputDevices()
            val summaries = recordableInputDevices(devices.map(::summaryFor))
            val choice =
                chooseInputDevice(
                    devices = summaries,
                    policy = settings.inputDevicePolicy,
                    manualKey = settings.manualDeviceAddress,
                )
            val resolvedSummary = choice.device
            val resolved = resolvedSummary?.let { summary -> devices.firstOrNull { it.id == summary.id } }
            val fallbackName =
                if (choice.preferredMissing) {
                    settings.manualDeviceName
                        ?: displayNameFromSelectionKey(settings.manualDeviceAddress)
                } else {
                    null
                }
            val sessionToken =
                synchronized(stateLock) {
                    // Summary flows first, id last: the main-thread removal callback keys
                    // on the id, so it must never observe an id whose summary has not
                    // been published yet.
                    _activeDevice.value = resolvedSummary?.let { ActiveInputDevice(it, fallbackName) }
                    _activeDeviceLabel.value = resolvedSummary?.productName
                    activeDeviceId = resolved?.id
                    ++latestSessionToken
                }
            Log.i(
                TAG,
                "Input device selected: ${resolvedSummary?.productName} " +
                    "(${resolvedSummary?.typeLabel}, policy=${settings.inputDevicePolicy}, " +
                    "preferredMissing=${choice.preferredMissing}, sessionToken=$sessionToken)",
            )
            return ResolvedSelection(device = resolved, sessionToken = sessionToken)
        }

        /**
         * Builds the capture session's [AudioRecord], pinned to the resolved preferred
         * device. The returned [AudioCaptureSession] carries the session token engines
         * pass back to [clearActiveDevice] at teardown, so a finished session's late
         * clear can never wipe the state a newer session has already published.
         */
        @SuppressLint("MissingPermission")
        suspend fun buildAudioRecord(
            audioSource: Int,
            sampleRate: Int,
            channelConfig: Int,
            audioFormat: Int,
            bufferSize: Int,
        ): AudioCaptureSession {
            val selection = resolveAndPublish()
            val device = selection.device
            val record = AudioRecord(audioSource, sampleRate, channelConfig, audioFormat, bufferSize)
            if (device != null) {
                val applied = record.setPreferredDevice(device)
                Log.i(TAG, "setPreferredDevice(id=${device.id}) accepted=$applied")
                if (!applied) {
                    // The platform rejected the pin, so capture may route anywhere. Drop
                    // the optimistic id and let refreshActiveDeviceFromRouting (the
                    // single mutation point) establish the truth from the live stream.
                    Log.w(TAG, "Preferred device rejected; awaiting routing refresh for the real route")
                    synchronized(stateLock) {
                        if (latestSessionToken == selection.sessionToken && activeDeviceId == device.id) {
                            activeDeviceId = null
                        }
                    }
                }
            }
            return AudioCaptureSession(record = record, sessionToken = selection.sessionToken)
        }

        /**
         * Best-effort correction of the optimistic active-device label from the platform's
         * actual capture routing. [AudioRecord.getRoutedDevice] is only populated once the
         * stream is live, so engines call this after the first successful read; the
         * [observeRouting] listener funnels every later reroute here too, making this the
         * single routing-driven mutation point. Keeping [activeDeviceId] honest also makes
         * device-lost detection track the real device. Always logs the effective route so
         * on-device routing verification has a record.
         */
        fun refreshActiveDeviceFromRouting(record: AudioRecord) {
            val routed = runCatching { record.routedDevice }.getOrNull() ?: return
            val summary = summaryFor(routed)
            Log.i(TAG, "Effective capture route: ${summary.productName} (${summary.typeLabel}, id=${routed.id})")
            synchronized(stateLock) {
                if (routed.id == activeDeviceId) return
                Log.i(TAG, "Routing differs from requested device; updating active device")
                // Same ordering discipline as publication: summary flows before the id.
                _activeDevice.value = ActiveInputDevice(summary, _activeDevice.value?.fallbackFromPreferredName)
                _activeDeviceLabel.value = summary.productName
                activeDeviceId = routed.id
            }
        }

        /**
         * Subscribes to [record]'s live routing changes: the platform reroutes capture
         * silently (preferred device unplugged, Bluetooth link drop, policy change) and
         * [AudioRecord.getRoutedDevice] is the only truth about what is actually being
         * captured. The listener runs on the main looper and funnels every change through
         * [refreshActiveDeviceFromRouting], the single routing-driven mutation point.
         * Idempotent per record; engines pair it with [stopObservingRouting] in their
         * release paths.
         */
        fun observeRouting(record: AudioRecord) {
            val listener = AudioRouting.OnRoutingChangedListener { refreshActiveDeviceFromRouting(record) }
            synchronized(routingListeners) {
                if (routingListeners.containsKey(record)) return
                routingListeners[record] = listener
            }
            record.addOnRoutingChangedListener(listener, Handler(Looper.getMainLooper()))
        }

        /**
         * Removes the routing listener registered by [observeRouting]. Engines must call
         * this in their release paths, before [AudioRecord.release], so the listener never
         * leaks past the session.
         */
        fun stopObservingRouting(record: AudioRecord) {
            val listener = synchronized(routingListeners) { routingListeners.remove(record) } ?: return
            runCatching { record.removeOnRoutingChangedListener(listener) }
        }

        /**
         * Registers the single legacy device-lost listener: a thin adapter over
         * [deviceLostEvents] kept so the recording service's existing registration keeps
         * working — the dispatch point that emits the flow also invokes this lambda (on
         * the main-thread device callback). New consumers should collect
         * [deviceLostEvents], which supports any number of surfaces at once.
         */
        fun setOnActiveDeviceLostListener(listener: ((lostDeviceName: String?) -> Unit)?) {
            onActiveDeviceLost = listener
        }

        /**
         * Clears active-device state after the capture session identified by
         * [sessionToken] (from [AudioCaptureSession]) ends. No-ops when a newer session
         * has already published: a finished session's late teardown (e.g. the service's
         * stop lifecycle racing a fresh keyboard dictation) must never clobber the newer
         * session's state. Deliberately does NOT clear the device-lost listener: its
         * lifecycle belongs to whoever registered it (the recording service holds it for
         * its whole lifetime), and nulling it here used to leave the next capture of a
         * surviving service instance without any device-lost handling.
         */
        fun clearActiveDevice(sessionToken: Long) {
            synchronized(stateLock) {
                if (sessionToken != latestSessionToken) return
                clearActiveDeviceLocked()
            }
        }

        /** Unconditional clear, kept only until every surface passes its session token. */
        @Deprecated(
            message =
                "An unconditional clear can clobber a newer session's published state; " +
                    "pass the AudioCaptureSession token instead.",
            replaceWith = ReplaceWith("clearActiveDevice(sessionToken)"),
        )
        fun clearActiveDevice() {
            synchronized(stateLock) { clearActiveDeviceLocked() }
        }

        private fun clearActiveDeviceLocked() {
            activeDeviceId = null
            _activeDevice.value = null
            _activeDeviceLabel.value = null
        }

        private fun refreshAvailableDevices() {
            _availableDevices.value = surfaceableInputDevices(inputDevices().map(::summaryFor))
        }

        private fun summaryFor(device: AudioDeviceInfo): AudioInputDeviceSummary =
            summaryFor(device, hasBluetoothConnectPermission())

        companion object {
            private const val TAG = "AudioInputDeviceSelector"
            private const val FALLBACK_PRODUCT_NAME = "Unknown device"
            private const val COMPOSITE_KEY_PREFIX = "device:"
            private const val COMPOSITE_KEY_PARTS = 3
            private const val DEVICE_LOST_EVENT_BUFFER = 4

            /** Fallback-priority order, best first; mirrored by [priorityOf]. */
            val PRIORITY_ORDER =
                listOf(
                    AudioInputDeviceKind.Usb,
                    AudioInputDeviceKind.BluetoothLe,
                    AudioInputDeviceKind.Bluetooth,
                    AudioInputDeviceKind.WiredHeadset,
                    AudioInputDeviceKind.BuiltIn,
                    AudioInputDeviceKind.Other,
                )

            /**
             * The capture-side candidate list: drops the non-recordable "Other" endpoints
             * (telephony, FM, remote-submix) so capture-start selection can never pin one
             * — a legacy manual key for such an endpoint now reports the preference
             * missing (ranked fallback + notice), consistent with what the picker shows.
             * Deliberately does NOT dedup: duplicate built-in rows must remain matchable
             * so a key persisted for a sibling row the picker collapsed still resolves to
             * hardware. Pure for testability.
             */
            fun recordableInputDevices(
                summaries: List<AudioInputDeviceSummary>,
            ): List<AudioInputDeviceSummary> = summaries.filter { it.kind != AudioInputDeviceKind.Other }

            /**
             * The user-facing input list: Android (notably Samsung) enumerates the same
             * logical mic as several [AudioDeviceInfo] rows (e.g. two TYPE_BUILTIN_MIC
             * entries for the bottom/reference mics) and also returns non-recordable
             * "Other" endpoints (telephony, FM, remote-submix) that are not meaningful
             * recording sources. Drop the Other kinds (via [recordableInputDevices]) and
             * collapse to one row per user-meaningful choice — its displayed (kind, name,
             * address) identity. Devices with distinct non-blank addresses are genuinely
             * different hardware (e.g. two same-model USB mics on a hub) and both stay
             * selectable, while blank-address duplicates (the multiple built-in-mic rows
             * sharing the "Built-in microphone" display name, hidden-name Bluetooth) still
             * collapse because the user could never tell them apart. Pure for testability.
             */
            fun surfaceableInputDevices(
                summaries: List<AudioInputDeviceSummary>,
            ): List<AudioInputDeviceSummary> =
                recordableInputDevices(summaries)
                    .distinctBy { Triple(it.kind, it.productName, it.address?.takeIf(String::isNotBlank)) }

            fun kindFor(type: Int): AudioInputDeviceKind =
                when (type) {
                    AudioDeviceInfo.TYPE_BUILTIN_MIC -> AudioInputDeviceKind.BuiltIn
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_ACCESSORY,
                    -> AudioInputDeviceKind.Usb
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_AUX_LINE,
                    -> AudioInputDeviceKind.WiredHeadset
                    AudioDeviceInfo.TYPE_BLE_HEADSET -> AudioInputDeviceKind.BluetoothLe
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioInputDeviceKind.Bluetooth
                    else -> AudioInputDeviceKind.Other
                }

            /**
             * Fallback priority when the preferred device is absent (or none is set):
             * an explicitly connected external mic (USB, then Bluetooth, then wired)
             * almost always signals intent to use it, so the built-in mic ranks last.
             */
            fun priorityOf(kind: AudioInputDeviceKind): Int = PRIORITY_ORDER.indexOf(kind)

            fun devicePriority(type: Int): Int = priorityOf(kindFor(type))

            fun rankDevices(devices: List<AudioInputDeviceSummary>): List<AudioInputDeviceSummary> =
                devices.sortedWith(compareBy({ priorityOf(it.kind) }, { it.productName }))

            /**
             * Pure capture-start selection algorithm (unit-tested as a matrix):
             * the manual preference when its device is connected; otherwise the
             * highest-priority connected device per [priorityOf]. [InputDeviceChoice.preferredMissing]
             * reports a stored-but-absent manual preference so surfaces can show a
             * transient "using X instead" notice.
             */
            fun chooseInputDevice(
                devices: List<AudioInputDeviceSummary>,
                policy: AudioInputDevicePolicy,
                manualKey: String?,
            ): InputDeviceChoice {
                val ranked = rankDevices(devices)
                return when (policy) {
                    AudioInputDevicePolicy.Manual -> {
                        val preferred = findDeviceForSelectionKey(devices, manualKey)
                        InputDeviceChoice(
                            device = preferred ?: ranked.firstOrNull(),
                            preferredMissing = preferred == null && !manualKey.isNullOrBlank(),
                        )
                    }
                    AudioInputDevicePolicy.PreferBuiltIn ->
                        InputDeviceChoice(
                            device =
                                devices.firstOrNull { it.kind == AudioInputDeviceKind.BuiltIn }
                                    ?: ranked.firstOrNull(),
                            preferredMissing = false,
                        )
                    AudioInputDevicePolicy.Automatic ->
                        InputDeviceChoice(device = ranked.firstOrNull(), preferredMissing = false)
                }
            }

            fun typeLabel(type: Int): String =
                when (type) {
                    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in"
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    -> "USB"
                    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
                    AudioDeviceInfo.TYPE_AUX_LINE -> "Line in"
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
                    AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE"
                    else -> "Other"
                }

            /**
             * Stable persistence key for a manual device selection. Uses the hardware
             * address when present; otherwise a type+name composite. Bluetooth and wired
             * devices report a blank address without BLUETOOTH_CONNECT, and the old
             * behavior of persisting the transient numeric id (or blank, which deleted
             * the stored key) silently broke manual selection for exactly those devices.
             */
            fun selectionKeyFor(
                type: Int,
                address: String?,
                productName: String?,
            ): String =
                address?.takeIf { it.isNotBlank() }
                    ?: "$COMPOSITE_KEY_PREFIX$type:${productName.orEmpty()}"

            fun matchesSelectionKey(
                device: AudioDeviceInfo,
                key: String?,
            ): Boolean {
                if (key.isNullOrBlank()) return false
                val address = device.address
                if (address.isNotBlank() && address == key) return true
                return selectionKeyFor(device.type, address, device.productName?.toString()) == key
            }

            fun summaryMatchesSelectionKey(
                summary: AudioInputDeviceSummary,
                key: String?,
            ): Boolean {
                if (key.isNullOrBlank()) return false
                return summary.selectionKey == key ||
                    (summary.address?.isNotBlank() == true && summary.address == key)
            }

            /**
             * The connected device a persisted manual key refers to: an exact key/address
             * match wins; otherwise [bluetoothIdentityFallbackMatches] resolves composite
             * keys across BLUETOOTH_CONNECT grant/revoke transitions. Every surface that
             * picks ONE device for a stored key (capture-start selection, picker checkmark)
             * must go through this so exact matches always take precedence over the
             * relaxed fallback.
             */
            fun findDeviceForSelectionKey(
                devices: List<AudioInputDeviceSummary>,
                key: String?,
            ): AudioInputDeviceSummary? {
                if (key.isNullOrBlank()) return null
                return devices.firstOrNull { summaryMatchesSelectionKey(it, key) }
                    ?: devices.firstOrNull { bluetoothIdentityFallbackMatches(it, key) }
            }

            /**
             * Relaxed match for composite keys whose Bluetooth identity is hidden on either
             * side, because granting or revoking BLUETOOTH_CONNECT changes what
             * [selectionKeyFor] produces for the SAME physical device:
             *
             * - Key persisted pre-grant ("device:<type>:<own model>" or blank name, since
             *   the platform hides the real name/address): after the grant the device
             *   reports its MAC + real name, so only the type can still identify it.
             * - Key persisted post-grant with a real name but no address: after a revoke
             *   the device's name degrades to the phone's model, so again only the type
             *   survives the transition.
             *
             * Matching by Bluetooth type mirrors the precision the picker had when the
             * hidden side was persisted/observed — pre-grant the user could only ever
             * distinguish devices by type label anyway. Address-based (MAC) keys are never
             * relaxed: after a revoke no device exposes an address, so a MAC key reports
             * its device as missing (named via the persisted display name) rather than
             * guessing.
             */
            fun bluetoothIdentityFallbackMatches(
                summary: AudioInputDeviceSummary,
                key: String?,
                ownModelName: String? = Build.MODEL,
            ): Boolean {
                if (key == null || !key.startsWith(COMPOSITE_KEY_PREFIX)) return false
                val parts = key.split(":", limit = COMPOSITE_KEY_PARTS)
                val keyType = parts.getOrNull(1)?.toIntOrNull() ?: return false
                val keyKind = kindFor(keyType)
                val keyIsBluetooth =
                    keyKind == AudioInputDeviceKind.Bluetooth || keyKind == AudioInputDeviceKind.BluetoothLe
                if (!keyIsBluetooth || summary.kind != keyKind) return false
                val keyName = parts.getOrNull(2).orEmpty()
                val keyNameHidden = keyName.isBlank() || keyName == ownModelName
                return keyNameHidden || summary.bluetoothNameHidden
            }

            /**
             * Best-effort display name recovered from a composite selection key, used to
             * name a missing preferred device when no display name was persisted with it.
             */
            fun displayNameFromSelectionKey(key: String?): String? {
                if (key == null || !key.startsWith(COMPOSITE_KEY_PREFIX)) return null
                return key
                    .split(":", limit = COMPOSITE_KEY_PARTS)
                    .getOrNull(2)
                    ?.takeIf { it.isNotBlank() }
            }

            /**
             * Builds the UI summary for a device. The selection key is always derived from
             * the RAW product name so persisted manual selections stay stable within one
             * permission state; only the displayed name degrades. A Bluetooth device whose
             * name is unavailable (blank, or the platform substituted the phone's own model
             * because the app lacks BLUETOOTH_CONNECT) is labeled by its type and flagged so
             * pickers can offer the permission rationale. Granting/revoking BLUETOOTH_CONNECT
             * changes a Bluetooth device's raw name AND address, so keys persisted across
             * that transition are resolved by [bluetoothIdentityFallbackMatches] via
             * [findDeviceForSelectionKey].
             */
            fun summaryFor(
                device: AudioDeviceInfo,
                hasBluetoothPermission: Boolean,
            ): AudioInputDeviceSummary {
                val kind = kindFor(device.type)
                val rawName = device.productName?.toString().orEmpty()
                val isBluetooth =
                    kind == AudioInputDeviceKind.Bluetooth || kind == AudioInputDeviceKind.BluetoothLe
                val nameHidden =
                    isBluetooth && !hasBluetoothPermission && (rawName.isBlank() || rawName == Build.MODEL)
                val label = typeLabel(device.type)
                val displayName =
                    when {
                        nameHidden -> label
                        kind == AudioInputDeviceKind.BuiltIn -> BUILT_IN_DISPLAY_NAME
                        rawName.isNotBlank() -> rawName
                        else -> FALLBACK_PRODUCT_NAME
                    }
                return AudioInputDeviceSummary(
                    id = device.id,
                    productName = displayName,
                    typeLabel = label,
                    kind = kind,
                    address = device.address,
                    selectionKey = selectionKeyFor(device.type, device.address, rawName),
                    bluetoothNameHidden = nameHidden,
                )
            }

            /** Friendlier than echoing the phone's model name for the built-in mic. */
            const val BUILT_IN_DISPLAY_NAME = "Built-in microphone"
        }

        private fun inputDevices(): List<AudioDeviceInfo> {
            return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        }
    }
