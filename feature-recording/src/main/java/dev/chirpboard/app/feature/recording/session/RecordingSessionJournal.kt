package dev.chirpboard.app.feature.recording.session

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.feature.recording.session.validation.RecordingFileValidator
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class SessionJournalState {
    ACTIVE,
    STOPPING,
    FINALIZED,
    ABANDONED,
    ;

    companion object {
        fun fromStorage(value: String?): SessionJournalState =
            entries.firstOrNull { it.name == value } ?: ACTIVE
    }
}

data class RecordingSessionEntry(
    val sessionId: UUID,
    val audioPath: String,
    val finalAudioPath: String?,
    val segmentPaths: List<String>,
    val origin: RecordingOrigin,
    val profileId: UUID?,
    val recordingId: UUID?,
    val startedAtEpochMs: Long,
    val lastHeartbeatEpochMs: Long,
    val lastSegmentFinalizedAtEpochMs: Long?,
    val activeSegmentStartedAtEpochMs: Long,
    val fileBytes: Long,
    val checkpointPath: String?,
    val state: SessionJournalState,
    val correlationId: String?,
) {
    val isSafelisted: Boolean
        get() = state == SessionJournalState.ACTIVE || state == SessionJournalState.STOPPING

    fun exportAudioPath(): String = finalAudioPath ?: audioPath

    fun orderedSegmentFiles(activePath: String? = null): List<File> {
        val orderedPaths = segmentPaths.toMutableList()
        val active = activePath ?: audioPath
        if (active.isNotBlank() && active !in orderedPaths) {
            orderedPaths.add(active)
        }
        return orderedPaths.map(::File).filter { it.exists() }
    }

    fun usesSegmentCapture(): Boolean = finalAudioPath != null || segmentPaths.isNotEmpty()
}

@Singleton
class RecordingSessionJournal
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val sessionsDir: File
            get() = File(context.filesDir, "recordings/.sessions").apply { mkdirs() }

        fun createSession(
            sessionId: UUID,
            audioPath: String,
            origin: RecordingOrigin,
            profileId: UUID?,
            recordingId: UUID?,
            correlationId: String?,
            finalAudioPath: String? = null,
        ): RecordingSessionEntry {
            val now = System.currentTimeMillis()
            val entry =
                RecordingSessionEntry(
                    sessionId = sessionId,
                    audioPath = audioPath,
                    finalAudioPath = finalAudioPath,
                    segmentPaths = emptyList(),
                    origin = origin,
                    profileId = profileId,
                    recordingId = recordingId,
                    startedAtEpochMs = now,
                    lastHeartbeatEpochMs = now,
                    lastSegmentFinalizedAtEpochMs = null,
                    activeSegmentStartedAtEpochMs = now,
                    fileBytes = 0L,
                    checkpointPath = null,
                    state = SessionJournalState.ACTIVE,
                    correlationId = correlationId,
                )
            writeEntry(entry)
            return entry
        }

        fun appendCompletedSegment(
            sessionId: UUID,
            completedSegmentPath: String,
            nextSegmentPath: String,
            fileBytes: Long,
        ) {
            updateEntry(sessionId) { entry ->
                val now = System.currentTimeMillis()
                val completedSegments =
                    if (completedSegmentPath in entry.segmentPaths) {
                        entry.segmentPaths
                    } else {
                        entry.segmentPaths + completedSegmentPath
                    }
                entry.copy(
                    segmentPaths = completedSegments,
                    audioPath = nextSegmentPath,
                    fileBytes = fileBytes,
                    lastHeartbeatEpochMs = now,
                    lastSegmentFinalizedAtEpochMs = now,
                    activeSegmentStartedAtEpochMs = now,
                )
            }
        }

        fun commitPausedSegment(
            sessionId: UUID,
            completedSegmentPath: String,
            fileBytes: Long,
        ) {
            updateEntry(sessionId) { entry ->
                val now = System.currentTimeMillis()
                val completedSegments =
                    if (completedSegmentPath in entry.segmentPaths) {
                        entry.segmentPaths
                    } else {
                        entry.segmentPaths + completedSegmentPath
                    }
                entry.copy(
                    segmentPaths = completedSegments,
                    audioPath = completedSegmentPath,
                    fileBytes = fileBytes,
                    lastHeartbeatEpochMs = now,
                    lastSegmentFinalizedAtEpochMs = now,
                )
            }
        }

        fun commitStoppedSegment(
            sessionId: UUID,
            completedSegmentPath: String,
            fileBytes: Long,
        ) {
            updateEntry(sessionId) { entry ->
                val now = System.currentTimeMillis()
                val completedSegments =
                    if (completedSegmentPath in entry.segmentPaths) {
                        entry.segmentPaths
                    } else {
                        entry.segmentPaths + completedSegmentPath
                    }
                entry.copy(
                    segmentPaths = completedSegments,
                    audioPath = completedSegmentPath,
                    fileBytes = fileBytes,
                    lastHeartbeatEpochMs = now,
                    lastSegmentFinalizedAtEpochMs = now,
                )
            }
        }

        fun beginNextSegment(
            sessionId: UUID,
            nextSegmentPath: String,
        ) {
            updateEntry(sessionId) { entry ->
                val now = System.currentTimeMillis()
                entry.copy(
                    audioPath = nextSegmentPath,
                    lastHeartbeatEpochMs = now,
                    activeSegmentStartedAtEpochMs = now,
                )
            }
        }

        fun updateHeartbeat(
            sessionId: UUID,
            fileBytes: Long,
        ) {
            updateEntry(sessionId) { entry ->
                if (entry.state == SessionJournalState.STOPPING) {
                    entry
                } else {
                    entry.copy(
                        lastHeartbeatEpochMs = System.currentTimeMillis(),
                        fileBytes = fileBytes,
                        state = SessionJournalState.ACTIVE,
                    )
                }
            }
        }

        fun updateCheckpoint(
            sessionId: UUID,
            checkpointPath: String,
            fileBytes: Long,
        ) {
            updateEntry(sessionId) { entry ->
                entry.copy(
                    checkpointPath = checkpointPath,
                    fileBytes = fileBytes,
                    lastHeartbeatEpochMs = System.currentTimeMillis(),
                )
            }
        }

        fun markStopping(sessionId: UUID) {
            updateEntry(sessionId) { entry ->
                entry.copy(state = SessionJournalState.STOPPING)
            }
        }

        fun markFinalized(sessionId: UUID) {
            deleteEntry(sessionId)
        }

        fun markAbandoned(sessionId: UUID) {
            updateEntry(sessionId) { entry ->
                entry.copy(state = SessionJournalState.ABANDONED)
            }
        }

        fun pruneAbandonedEntries(maxAgeMs: Long = DEFAULT_ABANDONED_PRUNE_AGE_MS): Int {
            val cutoff = System.currentTimeMillis() - maxAgeMs
            val stale =
                loadAllEntries().filter { entry ->
                    entry.state == SessionJournalState.ABANDONED &&
                        entry.lastHeartbeatEpochMs < cutoff
                }
            stale.forEach { deleteEntry(it.sessionId) }
            return stale.size
        }

        fun loadActiveSessions(): List<RecordingSessionEntry> = loadSessions { it.isSafelisted }

        fun loadRecoverableSessions(): List<RecordingSessionEntry> = loadActiveSessions()

        fun loadAllEntries(): List<RecordingSessionEntry> = loadSessions { true }

        fun getSafelistedAudioPaths(): Set<String> =
            loadActiveSessions()
                .flatMap { entry -> referencedPathsFor(entry) }
                .toSet()

        fun getAllReferencedAudioPaths(): Set<String> =
            loadAllEntries()
                .flatMap { entry -> referencedPathsFor(entry) }
                .toSet()

        fun startedAtByAudioPath(): Map<String, Long> =
            loadAllEntries().flatMap { entry ->
                referencedPathsFor(entry).map { it to entry.startedAtEpochMs }
            }.toMap()

        fun findBySessionId(sessionId: UUID): RecordingSessionEntry? =
            sessionFile(sessionId).takeIf { it.exists() }?.let { readEntry(it) }

        internal fun referencedPathsFor(entry: RecordingSessionEntry): List<String> =
            buildList {
                add(entry.audioPath)
                entry.finalAudioPath?.let(::add)
                addAll(entry.segmentPaths)
                entry.checkpointPath?.let(::add)
                add(RecordingFileValidator.checkpointPathFor(entry.audioPath))
                add(RecordingFileValidator.recoveryPathFor(entry.audioPath))
                entry.finalAudioPath?.let { finalPath ->
                    add(RecordingFileValidator.checkpointPathFor(finalPath))
                    add(RecordingFileValidator.recoveryPathFor(finalPath))
                }
            }

        private fun loadSessions(predicate: (RecordingSessionEntry) -> Boolean): List<RecordingSessionEntry> =
            sessionsDir
                .listFiles()
                ?.filter { it.extension == "json" }
                ?.mapNotNull { file ->
                    runCatching { readEntry(file) }.getOrNull()
                }.orEmpty()
                .filter(predicate)

        private fun updateEntry(
            sessionId: UUID,
            transform: (RecordingSessionEntry) -> RecordingSessionEntry,
        ) {
            val file = sessionFile(sessionId)
            if (!file.exists()) return
            val updated = transform(readEntry(file))
            writeEntry(updated)
        }

        private fun deleteEntry(sessionId: UUID) {
            val deleted = sessionFile(sessionId).delete()
            if (!deleted) {
                Log.w(TAG, "Failed to delete session journal for $sessionId")
            }
        }

        private fun sessionFile(sessionId: UUID): File = File(sessionsDir, "$sessionId.json")

        private fun writeEntry(entry: RecordingSessionEntry) {
            val fields =
                buildList {
                    add(""""sessionId":"${entry.sessionId}"""")
                    add(""""audioPath":"${escapeJson(entry.audioPath)}"""")
                    entry.finalAudioPath?.let { add(""""finalAudioPath":"${escapeJson(it)}"""") }
                    if (entry.segmentPaths.isNotEmpty()) {
                        add(""""segmentPaths":"${escapeJson(encodeSegmentPaths(entry.segmentPaths))}"""")
                    }
                    add(""""origin":"${entry.origin.name}"""")
                    entry.profileId?.let { add(""""profileId":"$it"""") }
                    entry.recordingId?.let { add(""""recordingId":"$it"""") }
                    add(""""startedAtEpochMs":${entry.startedAtEpochMs}""")
                    add(""""lastHeartbeatEpochMs":${entry.lastHeartbeatEpochMs}""")
                    entry.lastSegmentFinalizedAtEpochMs?.let {
                        add(""""lastSegmentFinalizedAtEpochMs":$it""")
                    }
                    add(""""activeSegmentStartedAtEpochMs":${entry.activeSegmentStartedAtEpochMs}""")
                    add(""""fileBytes":${entry.fileBytes}""")
                    entry.checkpointPath?.let { add(""""checkpointPath":"${escapeJson(it)}"""") }
                    add(""""state":"${entry.state.name}"""")
                    entry.correlationId?.let { add(""""correlationId":"${escapeJson(it)}"""") }
                }
            val payload = "{${fields.joinToString(",")}}"
            val target = sessionFile(entry.sessionId)
            val temp = File(target.parentFile, "${target.name}.tmp")
            temp.writeText(payload)
            if (!temp.renameTo(target)) {
                target.writeText(payload)
                temp.delete()
            }
        }

        private fun readEntry(file: File): RecordingSessionEntry {
            val values = parseSimpleJsonObject(file.readText())
            return RecordingSessionEntry(
                sessionId = UUID.fromString(values.getValue("sessionId")),
                audioPath = values.getValue("audioPath"),
                finalAudioPath = values["finalAudioPath"]?.takeIf { it.isNotBlank() },
                segmentPaths = decodeSegmentPaths(values["segmentPaths"]),
                origin = RecordingOrigin.valueOf(values.getValue("origin")),
                profileId = values["profileId"]?.takeIf { it.isNotBlank() }?.let(UUID::fromString),
                recordingId = values["recordingId"]?.takeIf { it.isNotBlank() }?.let(UUID::fromString),
                startedAtEpochMs = values.getValue("startedAtEpochMs").toLong(),
                lastHeartbeatEpochMs = values.getValue("lastHeartbeatEpochMs").toLong(),
                lastSegmentFinalizedAtEpochMs = values["lastSegmentFinalizedAtEpochMs"]?.toLongOrNull(),
                activeSegmentStartedAtEpochMs =
                    values["activeSegmentStartedAtEpochMs"]?.toLongOrNull()
                        ?: values.getValue("startedAtEpochMs").toLong(),
                fileBytes = values["fileBytes"]?.toLongOrNull() ?: 0L,
                checkpointPath = values["checkpointPath"]?.takeIf { it.isNotBlank() },
                state = SessionJournalState.fromStorage(values["state"]),
                correlationId = values["correlationId"]?.takeIf { it.isNotBlank() },
            )
        }

        internal fun encodeSegmentPaths(paths: List<String>): String = paths.joinToString(SEGMENT_PATH_DELIMITER)

        internal fun decodeSegmentPaths(raw: String?): List<String> =
            raw
                ?.takeIf { it.isNotBlank() }
                ?.split(SEGMENT_PATH_DELIMITER)
                ?.filter { it.isNotBlank() }
                .orEmpty()

        internal fun escapeJson(value: String): String =
            value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")

        internal fun parseSimpleJsonObject(raw: String): Map<String, String> {
            val body = raw.trim().removePrefix("{").removeSuffix("}")
            if (body.isBlank()) return emptyMap()
            val result = linkedMapOf<String, String>()
            val regex = """"([^"]+)":"((?:\\.|[^"\\])*)"|"([^"]+)":(-?\d+)""".toRegex()
            regex.findAll(body).forEach { match ->
                val stringKey = match.groupValues[1]
                val stringValue = match.groupValues[2]
                val numberKey = match.groupValues[3]
                val numberValue = match.groupValues[4]
                if (stringKey.isNotBlank()) {
                    result[stringKey] = stringValue.replace("\\\"", "\"").replace("\\\\", "\\")
                } else if (numberKey.isNotBlank()) {
                    result[numberKey] = numberValue
                }
            }
            return result
        }

        companion object {
            private const val TAG = "RecordingSessionJournal"
            private const val SEGMENT_PATH_DELIMITER = "\u001f"
            const val MIN_RECOVERABLE_FILE_BYTES = 512L
            const val CHECKPOINT_INTERVAL_MS = 15 * 60 * 1000L
            const val SEGMENT_ROTATION_INTERVAL_MS = 5 * 60 * 1000L
            const val DEFAULT_ABANDONED_PRUNE_AGE_MS = 30L * 24 * 60 * 60 * 1000
        }
    }
