package com.example.multihoprelay.fakes

import com.example.multihoprelay.data.storage.TransferFileStore
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** [TransferFileStore] backed by a plain JVM temp directory, for unit tests. */
class FakeFileStore : TransferFileStore {
    private val root: File = Files.createTempDirectory("multihoprelay-test").toFile()

    override fun stagingFileFor(transferId: UUID): File = File(root, "$transferId.part")

    override fun finalFileFor(transferId: UUID, fileName: String): File =
        File(root, "${transferId}_${fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")}")

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
}
