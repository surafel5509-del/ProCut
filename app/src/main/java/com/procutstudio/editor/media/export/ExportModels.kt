package com.procutstudio.editor.media.export

import android.net.Uri

/** A user-presentable export failure. The message is shown verbatim in the UI. */
class ExportFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

enum class ExportStage(val label: String) {
    PREPARING("Preparing project"),
    RENDERING("Rendering video"),
    VERIFYING("Verifying output"),
    SAVING("Saving to gallery"),
    DONE("Finished"),
}

data class ExportProgressInfo(
    val stage: ExportStage,
    val fraction: Float,
    val message: String? = null,
)

/** Result of a successful export, after the file was verified and published. */
data class ExportOutcome(
    val uri: Uri,
    val displayName: String,
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val warnings: List<String> = emptyList(),
)
