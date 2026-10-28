package com.procutstudio.editor.data.repository

import com.procutstudio.editor.domain.model.AspectRatio
import com.procutstudio.editor.domain.model.EditorProject
import com.procutstudio.editor.domain.model.MediaAsset
import kotlinx.coroutines.flow.Flow

/** Lightweight project row for the home screen and pickers. */
data class ProjectSummary(
    val id: String,
    val name: String,
    val updatedAtMs: Long,
    val durationUs: Long,
    val aspectRatio: AspectRatio,
    val thumbnailPath: String?,
    val clipCount: Int,
)

interface ProjectRepository {
    fun observeProjects(): Flow<List<ProjectSummary>>

    suspend fun getProject(projectId: String): EditorProject?

    suspend fun createProject(
        name: String,
        aspectRatio: AspectRatio,
        assets: List<MediaAsset> = emptyList(),
    ): EditorProject

    suspend fun saveProject(project: EditorProject)

    suspend fun renameProject(projectId: String, newName: String): EditorProject?

    suspend fun duplicateProject(projectId: String): EditorProject?

    suspend fun deleteProject(projectId: String, deleteMedia: Boolean = true)

    suspend fun updateThumbnail(projectId: String, thumbnailPath: String?)

    suspend fun totalStoredMediaBytes(): Long
}
