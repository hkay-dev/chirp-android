package dev.chirpboard.app.feature.recording.session

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.feature.recording.session.validation.RecordingFileValidator
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// Compiled once; parseSimpleJsonObject runs for every journal line during recovery scans.
private val SIMPLE_JSON_FIELD_REGEX = """"([^"]+)":"((?:\\.|[^"\\])*)"|"([^"]+)":(-?\d+)""".toRegex()

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

    val hasRecoverableAudioArtifact: Boolean
        get() =
            candidateRecoveryPaths()
                .map(::File)
                .any { file -> file.exists() && file.length() >= RecordingSessionJournal.MIN_RECOVERABLE_FILE_BYTES }

    val isRecoverable: Boolean
        get() =
            isSafelisted ||
                (state == SessionJournalState.ABANDONED && hasRecoverableAudioArtifact)

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

    private fun candidateRecoveryPaths(): List<String> =
        buildList {
            finalAudioPath?.let(::add)
            add(audioPath)
            addAll(segmentPaths)
            checkpointPath?.let(::add)
            add(RecordingFileValidator.checkpointPathFor(audioPath))
            add(RecordingFileValidator.recoveryPathFor(audioPath))
            finalAudioPath?.let { finalPath ->
                add(RecordingFileValidator.checkpointPathFor(finalPath))
                add(RecordingFileValidator.recoveryPathFor(finalPath))
            }
        }
}

/**
 * Single-pass view of the session journal for orphan cleanup. Holds every loaded
 * entry plus quarantined-entry referenced paths captured under one [journalLock]
 * hold, and derives the three projections (all-referenced, safelisted, started-at)
 * in memory so the cleaner does not re-scan the directory three times.
 */
class CleanupJournalSnapshot internal constructor(
    private val entries: List<RecordingSessionEntry>,
    private val quarantinedReferencedPaths: Set<String>,
    private val referencedPathsOf: (RecordingSessionEntry) -> List<String>,
) {
    fun allReferencedAudioPaths(): Set<String> =
        entries.flatMap(referencedPathsOf).toSet() + quarantinedReferencedPaths

    fun safelistedAudioPaths(): Set<String> =
        entries.filter { it.isSafelisted }.flatMap(referencedPathsOf).toSet()

    fun startedAtByAudioPath(): Map<String, Long> =
        entries.flatMap { entry ->
            referencedPathsOf(entry).map { it to entry.startedAtEpochMs }
        }.toMap()
}

@Singleton
class RecordingSessionJournal
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /** Serializes journal read-modify-write cycles so concurrent mutators cannot lose updates. */
        private val journalLock = Any()

        private val sessionsDir = File(context.filesDir, "recordings/.sessions").apply { mkdirs() }

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
            synchronized(journalLock) {
                writeEntry(entry)
            }
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
                if (entry.state == SessionJournalState.STOPPING) {
                    // Same guard as updateHeartbeat: a resume that lost the race with a
                    // gated stop must not repoint the STOPPING entry's audioPath at a new
                    // live segment — the finalize worker already owns this entry and would
                    // otherwise consume a half-written capture.
                    entry
                } else {
                    val now = System.currentTimeMillis()
                    entry.copy(
                        audioPath = nextSegmentPath,
                        lastHeartbeatEpochMs = now,
                        activeSegmentStartedAtEpochMs = now,
                    )
                }
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
                        !entry.hasRecoverableAudioArtifact &&
                        // Inclusive: an entry exactly maxAgeMs old prunes. A strict < made the
                        // maxAgeMs = 0 case (and its test) flaky when the heartbeat and the
                        // prune landed in the same millisecond.
                        entry.lastHeartbeatEpochMs <= cutoff
                }
            stale.forEach { deleteEntry(it.sessionId) }
            return stale.size
        }

        /**
         * Deletes quarantined (.corrupt) journal entries older than [maxAgeMs]. Their
         * best-effort referenced audio loses its cleanup shield once the entry is gone,
         * so retention is bounded (30 days, matching the audio quarantine purge) instead
         * of shielding undecodable sessions forever.
         */
        fun pruneCorruptEntries(maxAgeMs: Long = DEFAULT_CORRUPT_PRUNE_AGE_MS): Int {
            val cutoff = System.currentTimeMillis() - maxAgeMs
            synchronized(journalLock) {
                val stale =
                    sessionsDir
                        .listFiles()
                        ?.filter { it.name.endsWith(CORRUPT_SUFFIX) && it.lastModified() < cutoff }
                        .orEmpty()
                stale.forEach { file ->
                    if (!file.delete()) {
                        Log.w(TAG, "Failed to prune corrupt session journal ${file.name}")
                    }
                }
                return stale.size
            }
        }

        fun loadActiveSessions(): List<RecordingSessionEntry> = loadSessions { it.isSafelisted }

        fun loadRecoverableSessions(): List<RecordingSessionEntry> = loadSessions { it.isRecoverable }

        fun loadAllEntries(): List<RecordingSessionEntry> = loadSessions { true }

        fun getSafelistedAudioPaths(): Set<String> =
            loadActiveSessions()
                .flatMap { entry -> referencedPathsFor(entry) }
                .toSet()

        fun getAllReferencedAudioPaths(): Set<String> =
            loadAllEntries()
                .flatMap { entry -> referencedPathsFor(entry) }
                .toSet() + quarantinedReferencedPaths()

        fun startedAtByAudioPath(): Map<String, Long> =
            loadAllEntries().flatMap { entry ->
                referencedPathsFor(entry).map { it to entry.startedAtEpochMs }
            }.toMap()

        /**
         * One directory pass that captures every projection orphan cleanup needs
         * (all entries + quarantined-entry paths), so a single [journalLock] hold
         * replaces the three independent scans of [getAllReferencedAudioPaths],
         * [getSafelistedAudioPaths] and [startedAtByAudioPath]. Derive the three
         * sets from the returned snapshot with [CleanupJournalSnapshot].
         */
        fun loadCleanupSnapshot(): CleanupJournalSnapshot =
            synchronized(journalLock) {
                CleanupJournalSnapshot(
                    entries = loadEntriesLocked { true },
                    quarantinedReferencedPaths = quarantinedReferencedPathsLocked(),
                    referencedPathsOf = ::referencedPathsFor,
                )
            }

        fun findBySessionId(sessionId: UUID): RecordingSessionEntry? =
            synchronized(journalLock) {
                sessionFile(sessionId).takeIf { it.exists() }?.let { file ->
                    runCatching { readEntry(file) }.getOrElse { error ->
                        quarantineCorruptEntry(file, error)
                        null
                    }
                }
            }

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
            synchronized(journalLock) {
                loadEntriesLocked(predicate)
            }

        private fun loadEntriesLocked(predicate: (RecordingSessionEntry) -> Boolean): List<RecordingSessionEntry> =
            // Caller must hold journalLock. Reading under the journal lock prevents torn
            // reads of writeEntry's non-atomic fallback write from being mistaken for
            // corruption.
            sessionsDir
                .listFiles()
                ?.filter { it.extension == "json" }
                ?.mapNotNull { file ->
                    runCatching { readEntry(file) }.getOrElse { error ->
                        // Quarantine instead of silently dropping: the entry stays on disk for
                        // diagnosis and its referenced audio stays out of orphan cleanup.
                        quarantineCorruptEntry(file, error)
                        null
                    }
                }.orEmpty()
                .filter(predicate)

        private fun updateEntry(
            sessionId: UUID,
            transform: (RecordingSessionEntry) -> RecordingSessionEntry,
        ) {
            synchronized(journalLock) {
                val file = sessionFile(sessionId)
                if (!file.exists()) return
                val current =
                    runCatching { readEntry(file) }.getOrElse { error ->
                        quarantineCorruptEntry(file, error)
                        return
                    }
                writeEntry(transform(current))
            }
        }

        private fun deleteEntry(sessionId: UUID) {
            synchronized(journalLock) {
                val deleted = sessionFile(sessionId).delete()
                if (!deleted) {
                    Log.w(TAG, "Failed to delete session journal for $sessionId")
                } else {
                    syncSessionsDirectory()
                }
            }
        }

        private fun quarantineCorruptEntry(
            file: File,
            error: Throwable,
        ) {
            synchronized(journalLock) {
                if (!file.exists()) return
                // Re-check under the lock before the destructive rename: the original
                // failure may have been a torn read racing a writer or a transient
                // read error, and quarantining a healthy live entry would silently
                // disable journaling for its session.
                val recheck = runCatching { readEntry(file) }
                if (recheck.isSuccess) {
                    Log.w(TAG, "Skipped quarantine of session journal ${file.name}; entry parsed on re-read", error)
                    return
                }
                if (recheck.exceptionOrNull() is IOException) {
                    // A read failure is not corruption; keep the entry for the next pass.
                    Log.w(TAG, "Skipped quarantine of unreadable session journal ${file.name}", error)
                    return
                }
                val quarantined = File(file.parentFile, "${file.name}$CORRUPT_SUFFIX")
                if (file.renameTo(quarantined)) {
                    syncSessionsDirectory()
                    // Stamp the quarantine time so pruneCorruptEntries retention starts now.
                    quarantined.setLastModified(System.currentTimeMillis())
                    Log.w(TAG, "Quarantined unparseable session journal ${file.name}", error)
                } else {
                    Log.w(TAG, "Failed to quarantine unparseable session journal ${file.name}", error)
                }
            }
        }

        /**
         * Best-effort audio paths referenced by quarantined (unparseable) journal entries.
         * These files must stay out of orphan cleanup even though the entry cannot be
         * fully decoded any more.
         */
        private fun quarantinedReferencedPaths(): Set<String> =
            synchronized(journalLock) { quarantinedReferencedPathsLocked() }

        private fun quarantinedReferencedPathsLocked(): Set<String> =
            sessionsDir
                .listFiles()
                ?.filter { it.name.endsWith(CORRUPT_SUFFIX) }
                ?.flatMap(::bestEffortReferencedPaths)
                ?.toSet()
                .orEmpty()

        private fun bestEffortReferencedPaths(file: File): List<String> =
            runCatching {
                val values = parseSimpleJsonObject(file.readText())
                buildList {
                    listOfNotNull(values["audioPath"], values["finalAudioPath"])
                        .filter { it.isNotBlank() }
                        .forEach { path ->
                            add(path)
                            add(RecordingFileValidator.checkpointPathFor(path))
                            add(RecordingFileValidator.recoveryPathFor(path))
                        }
                    addAll(decodeSegmentPaths(values["segmentPaths"]))
                    values["checkpointPath"]?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }.getOrDefault(emptyList())

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
            try {
                FileOutputStream(temp).use { output ->
                    output.write(payload.toByteArray(Charsets.UTF_8))
                    output.flush()
                    output.fd.sync()
                }
                if (!temp.renameTo(target)) {
                    throw IOException("Failed to atomically replace session journal ${target.name}")
                }
                // The file sync protects the payload. The directory sync protects the
                // rename itself, so a sudden reboot cannot roll the journal name back.
                syncSessionsDirectory()
            } finally {
                if (temp.exists() && !temp.delete()) {
                    Log.w(TAG, "Failed to clean temporary session journal ${temp.name}")
                }
            }
        }

        private fun syncSessionsDirectory() {
            val descriptor =
                runCatching { Os.open(sessionsDir.absolutePath, OsConstants.O_RDONLY, 0) }.getOrNull()
                    ?: return
            try {
                Os.fsync(descriptor)
            } catch (_: Exception) {
                // Some test and vendor filesystems do not support directory fsync. The
                // journal payload itself was still synced before the atomic rename.
            } finally {
                runCatching { Os.close(descriptor) }
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
            SIMPLE_JSON_FIELD_REGEX.findAll(body).forEach { match ->
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
            const val CORRUPT_SUFFIX = ".corrupt"
            const val MIN_RECOVERABLE_FILE_BYTES = 512L
            const val SEGMENT_ROTATION_INTERVAL_MS = 5 * 60 * 1000L
            const val DEFAULT_ABANDONED_PRUNE_AGE_MS = 30L * 24 * 60 * 60 * 1000
            const val DEFAULT_CORRUPT_PRUNE_AGE_MS = 30L * 24 * 60 * 60 * 1000
        }
    }
