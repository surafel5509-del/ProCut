package com.procutstudio.editor.data.local

import com.procutstudio.editor.core.util.ProCutLogger
import com.procutstudio.editor.domain.model.EditorProject
import com.procutstudio.editor.domain.model.ProjectSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** Raised when a stored document cannot be read. Always carries a user-presentable message. */
class ProjectDocumentException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reads and writes the project document.
 *
 * The JSON is versioned with [ProjectSchema.CURRENT_VERSION]. Unknown keys are ignored and missing
 * keys fall back to Kotlin defaults, which keeps older documents loadable when new fields are
 * added. A document written by a *newer* schema is rejected with a clear message instead of being
 * silently mangled.
 */
@Singleton
class ProjectDocumentCodec @Inject constructor() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
        explicitNulls = false
    }

    fun encode(project: EditorProject): String = json.encodeToString(EditorProject.serializer(), project)

    fun decode(raw: String): EditorProject {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }
            .getOrElse { throw ProjectDocumentException("This project's data is corrupted and cannot be opened.", it) }
            ?: throw ProjectDocumentException("This project's data is corrupted and cannot be opened.")
        val version = runCatching {
            root["schemaVersion"]?.jsonPrimitive?.content?.toIntOrNull()
        }.getOrNull() ?: ProjectSchema.CURRENT_VERSION

        if (version > ProjectSchema.CURRENT_VERSION) {
            throw ProjectDocumentException(
                "This project was created with a newer version of ProCut Studio. Update the app to open it.",
            )
        }
        if (version < ProjectSchema.MIN_SUPPORTED_VERSION) {
            throw ProjectDocumentException("This project uses an unsupported legacy format.")
        }
        return try {
            migrate(json.decodeFromJsonElement(EditorProject.serializer(), root), version)
                .copy(schemaVersion = ProjectSchema.CURRENT_VERSION)
                .sanitized()
        } catch (error: Exception) {
            ProCutLogger.e(ProCutLogger.TAG_PROJECT, "Failed to decode project document (v$version)", error)
            throw ProjectDocumentException("This project could not be opened: ${error.message ?: "invalid data"}", error)
        }
    }

    /**
     * Schema migrations land here. Version 1 is the initial format, so this is a pass-through that
     * exists to keep call sites and tests honest about the migration step.
     */
    private fun migrate(project: EditorProject, fromVersion: Int): EditorProject {
        var migrated = project
        if (fromVersion < 1) {
            migrated = migrated.copy(schemaVersion = 1)
        }
        return migrated
    }
}
