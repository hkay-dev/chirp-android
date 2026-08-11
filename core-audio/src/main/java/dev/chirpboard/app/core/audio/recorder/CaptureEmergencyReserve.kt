package dev.chirpboard.app.core.audio.recorder

import android.content.Context
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * One process-wide, content-free cache reserve prepared from the application idle path.
 * Capture code can only delete an already-complete reserve. It never waits for preparation.
 */
object CaptureEmergencyReserve {
    internal const val RESERVE_BYTES = 4L * 1024L * 1024L
    private const val TAG = "CaptureReserve"
    private const val RESERVE_FILE = "capture-emergency-reserve.bin"
    private const val PARTIAL_FILE = "$RESERVE_FILE.partial"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var initializedDirectory: File? = null
    private var store: EmergencyReserveStore? = null

    /** Starts preparation and returns immediately. Safe to call more than once. */
    fun initialize(context: Context) {
        val directory = context.cacheDir
        val nextStore =
            synchronized(lock) {
                if (initializedDirectory == directory) return
                initializedDirectory = directory
                val storageManager = context.getSystemService(StorageManager::class.java)
                EmergencyReserveStore(
                    directory = directory,
                    reserveBytes = RESERVE_BYTES,
                    reserveFileName = RESERVE_FILE,
                    partialFileName = PARTIAL_FILE,
                    allocationSupported = { fd -> storageManager?.isAllocationSupported(fd) == true },
                    allocate = { fd, bytes -> checkNotNull(storageManager).allocateBytes(fd, bytes) },
                ).also { store = it }
            }
        scope.launch {
            runCatching { nextStore.prepare() }
                .onFailure { Log.w(TAG, "Could not prepare emergency capture reserve", it) }
        }
    }

    /** Deletes only a completed reserve. Never waits for an in-flight preparation. */
    fun reclaim(): Boolean = synchronized(lock) { store?.reclaim() == true }
}

internal class EmergencyReserveStore(
    private val directory: File,
    private val reserveBytes: Long,
    reserveFileName: String,
    partialFileName: String,
    private val allocationSupported: (FileDescriptor) -> Boolean,
    private val allocate: (FileDescriptor, Long) -> Unit,
) {
    private val reserveFile = File(directory, reserveFileName)
    private val partialFile = File(directory, partialFileName)
    private val lock = Any()
    private var preparing = false
    private var discardPreparation = false

    fun prepare(): Boolean {
        synchronized(lock) {
            if (reserveFile.isFile && reserveFile.length() == reserveBytes) {
                partialFile.delete()
                return true
            }
            reserveFile.delete()
            partialFile.delete()
            discardPreparation = false
            preparing = true
        }

        val prepared =
            runCatching {
                check(directory.isDirectory || directory.mkdirs())
                FileOutputStream(partialFile, false).use { output ->
                    check(allocationSupported(output.fd))
                    allocate(output.fd, reserveBytes)
                    output.fd.sync()
                }
                check(partialFile.length() == reserveBytes)
                true
            }.getOrDefault(false)

        return synchronized(lock) {
            preparing = false
            if (!prepared || discardPreparation) {
                partialFile.delete()
                false
            } else {
                moveReplacing(partialFile, reserveFile)
                reserveFile.isFile && reserveFile.length() == reserveBytes
            }
        }
    }

    fun reclaim(): Boolean =
        synchronized(lock) {
            discardPreparation = true
            if (preparing || !reserveFile.isFile || reserveFile.length() != reserveBytes) {
                false
            } else {
                reserveFile.delete()
            }
        }

    internal fun reserveFileForTest(): File = reserveFile

    internal fun partialFileForTest(): File = partialFile

    private fun moveReplacing(
        source: File,
        destination: File,
    ) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

internal fun Throwable.isStorageExhaustion(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is ErrnoException &&
            (current.errno == OsConstants.ENOSPC || current.errno == OsConstants.EDQUOT)
        ) {
            return true
        }
        val message = current.message.orEmpty().lowercase()
        if (message.contains("no space left") || message.contains("quota exceeded")) return true
        current = current.cause
    }
    return false
}
