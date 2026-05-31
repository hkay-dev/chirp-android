package dev.chirpboard.app.feature.transcription

import java.util.UUID

internal class FakeTranscriptionWorkScheduler : TranscriptionWorkScheduler {
    data class EnqueuedWork(
        val recordingId: UUID,
        val executionToken: String,
        val correlationId: String?,
        val workName: String,
    )

    val transcriptions = mutableListOf<EnqueuedWork>()
    val enhancements = mutableListOf<EnqueuedWork>()
    val cancelledTranscriptions = mutableListOf<UUID>()
    val cancelledEnhancements = mutableListOf<UUID>()
    val recordingTagInfos = mutableMapOf<UUID, List<ScheduledWorkInfo>?>()
    val uniqueWorkInfos = mutableMapOf<String, List<ScheduledWorkInfo>?>()

    override fun enqueueTranscription(
        recordingId: UUID,
        executionToken: String,
        correlationId: String?,
    ): String {
        val workName = TranscriptionWorkRequest.workName(recordingId)
        transcriptions += EnqueuedWork(recordingId, executionToken, correlationId, workName)
        return workName
    }

    override fun enqueueEnhancement(
        recordingId: UUID,
        executionToken: String,
        correlationId: String?,
    ): String {
        val workName = RecordingEnhancementWorkRequest.workName(recordingId)
        enhancements += EnqueuedWork(recordingId, executionToken, correlationId, workName)
        return workName
    }

    override fun cancelTranscription(recordingId: UUID) {
        cancelledTranscriptions += recordingId
    }

    override fun cancelEnhancement(recordingId: UUID) {
        cancelledEnhancements += recordingId
    }

    override suspend fun getWorkInfosByRecordingTag(recordingId: UUID): List<ScheduledWorkInfo>? =
        recordingTagInfos[recordingId] ?: emptyList()

    override suspend fun getWorkInfosForUniqueWork(workName: String): List<ScheduledWorkInfo>? =
        uniqueWorkInfos[workName] ?: emptyList()
}
