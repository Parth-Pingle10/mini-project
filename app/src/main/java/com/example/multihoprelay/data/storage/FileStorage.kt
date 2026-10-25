package com.example.multihoprelay.data.storage

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * Abstraction over where transfer files live on disk, so [com.example.multihoprelay.networking.TransferManager]
 * can be unit-tested with a plain in-memory/temp-directory fake instead of needing a real
 * Android [Context] (see the test-only `FakeFileStore` used by `TransferManagerTest`).
 */
interface TransferFileStore {
    fun stagingFileFor(transferId: UUID): File
    fun finalFileFor(transferId: UUID, fileName: String): File
    fun moveToFinal(staging: File, destination: File)
    fun deleteStaging(transferId: UUID)
}

/**
 * Manages where transfer files live on disk. Deliberately uses app-private storage
 * (`context.filesDir`), never a shared/public directory, and never automatically opens,
 * executes, or launches any received file - see requirement #18 ("Security Against
 * Malicious Files"). A received file only becomes visible to the user via the Transfers
 * screen, where they can explicitly choose to open/share it through Android's normal
 * "Open with" chooser (which itself enforces the OS security model for the target app).
 */
class FileStorage(context: Context) : TransferFileStore {

    private val stagingDir = File(context.filesDir, "staging").apply { mkdirs() }
    private val receivedDir = File(context.filesDir, "received").apply { mkdirs() }

    /** A working file for an in-progress incoming transfer; chunks are written here as they arrive. */
    override fun stagingFileFor(transferId: UUID): File = File(stagingDir, "$transferId.part")

    /** Final resting place for a fully verified, decrypted, received file (still app-private). */
    override fun finalFileFor(transferId: UUID, fileName: String): File {
        val safeName = sanitizeFileName(fileName)
        return File(receivedDir, "${transferId}_$safeName")
    }

    override fun moveToFinal(staging: File, destination: File) {
        destination.parentFile?.mkdirs()
        if (!staging.renameTo(destination)) {
            staging.copyTo(destination, overwrite = true)
            staging.delete()
        }
    }

    override fun deleteStaging(transferId: UUID) {
        stagingFileFor(transferId).delete()
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "received_file" }
}
