package com.example.multihoprelay.util

import com.example.multihoprelay.model.TransferState

object FormatUtils {
    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB (%d B)".format(kb, bytes)
        val mb = kb / 1024.0
        return "%.2f MB (%d B)".format(mb, bytes)
    }

    fun formatTransferState(state: TransferState): String = when (state) {
        TransferState.DISCOVERING -> "Discovering Route"
        TransferState.ROUTE_FOUND -> "Route Established"
        TransferState.PREPARING -> "Preparing Chunks"
        TransferState.WAITING_FOR_APPROVAL -> "Waiting for receiver approval..."
        TransferState.ENCRYPTING -> "Encrypting Chunks"
        TransferState.TRANSFERRING -> "Sending Chunks"
        TransferState.RELAYING -> "Relaying Traffic"
        TransferState.PENDING_APPROVAL -> "Awaiting Receiver Approval"
        TransferState.RECEIVING -> "Receiving Chunks"
        TransferState.VERIFYING -> "Verifying Checksum"
        TransferState.COMPLETED -> "Transfer Complete!"
        TransferState.REJECTED -> "Rejected"
        TransferState.FAILED -> "Failed"
        TransferState.CANCELLED -> "Cancelled"
        TransferState.EXPIRED -> "Transfer request expired — receiver did not respond"
        TransferState.NO_ROUTE -> "No Route Found"
    }
}
