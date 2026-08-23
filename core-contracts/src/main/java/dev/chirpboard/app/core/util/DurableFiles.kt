package dev.chirpboard.app.core.util

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Crash-durability primitives for app-private files: staged writes promoted by an atomic
 * rename, and the directory fsync that keeps a completed rename from rolling back on
 * sudden power loss.
 */
object DurableFiles {
    /**
     * Best-effort fsync of [directory] so a completed rename or create survives sudden
     * power loss. Failures are swallowed: some test and vendor filesystems refuse
     * directory fsync, and the payload bytes were already synced before this runs.
     */
    fun syncDirectory(directory: File) {
        val descriptor =
            runCatching { Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0) }.getOrNull()
                ?: return
        try {
            Os.fsync(descriptor)
        } catch (_: Exception) {
            // The strongest guarantee this filesystem offers has already been taken.
        } finally {
            runCatching { Os.close(descriptor) }
        }
    }

    /**
     * Replaces [target] with [staging], preferring an atomic move and falling back to a
     * plain replace on filesystems that reject atomic moves. Throws on failure.
     */
    fun atomicReplace(
        staging: File,
        target: File,
    ) {
        try {
            Files.move(
                staging.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Writes [target] durably: [write] streams into a same-directory staging file
     * (`target.name + stagingSuffix`), the descriptor is synced, the staging file replaces
     * [target] atomically, and the parent directory is synced so the rename holds. On
     * failure the staging file is removed and the exception propagates.
     *
     * [stagingSuffix] matters when other code filters files by name: pick a suffix that
     * existing sweepers and readers already expect to skip (or protect). It is part of the
     * crash-recovery contract — the keyboard handoff and chunk-checkpoint sweepers both
     * match `.partial` exactly — so the staging name stays derived from the target rather
     * than being made unique per call.
     *
     * That makes concurrent writes to the same [target] the caller's problem: two of them
     * share one staging file, so the second truncates the first mid-write and the first
     * can then rename the second's partial bytes over [target]. Every caller today
     * serializes externally (the session journal writes under `journalLock`, the Obsidian
     * exporter under `writeMutex`, the Studio draft cancels the previous job); a new caller
     * must do the same.
     */
    fun writeAtomically(
        target: File,
        stagingSuffix: String = ".tmp",
        write: (FileOutputStream) -> Unit,
    ) {
        val directory = target.parentFile ?: throw IOException("No parent directory for ${target.name}")
        val staging = File(directory, "${target.name}$stagingSuffix")
        try {
            FileOutputStream(staging).use { output ->
                write(output)
                output.fd.sync()
            }
            atomicReplace(staging, target)
            syncDirectory(directory)
        } catch (e: Exception) {
            runCatching { staging.delete() }
            throw e
        }
    }

    /** [writeAtomically] with a UTF-8 text payload. */
    fun writeTextAtomically(
        target: File,
        text: String,
        stagingSuffix: String = ".tmp",
    ) {
        writeAtomically(target, stagingSuffix) { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
}
