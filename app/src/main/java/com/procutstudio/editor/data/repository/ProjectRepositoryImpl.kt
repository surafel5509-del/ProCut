package com.procutstudio.editor.data.repository

import android.content.Context
import android.graphics.Bitmap
import com.procutstudio.editor.core.di.IoDispatcher
import com.procutstudio.editor.core.util.FileUtils
import com.procutstudio.editor.core.util.IdGenerator
import com.procutstudio.editor.core.util.ProCutLogger
import com.procutstudio.editor.data.local.ProjectDocumentCodec
import com.procutstudio.editor.data.local.db.ProjectDao
import com.procutstudio.editor.data.local.db.ProjectEntity
import com.procutstudio.editor.data.local.db.ProjectSummaryRow
import com.procutstudio.editor.data.media.ThumbnailStore
import com.procutstudio.editor.domain.model.AspectRatio
import com.procutstudio.editor.domain.model.EditorProject
import com.procutstudio.editor.domain.model.MediaAsset
import com.procutstudio.editor.domain.model.ProjectSchema
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for projects: Room holds the durable record, the JSON document holds the
 * editing state. Every save is a full, atomic document write, which keeps persistence and undo in
 * sync without a fragile diff protocol.
 */
@Singleton
class ProjectRepositoryImpl @Inject constructor(
    private val dao: ProjectDao,
    private val codec: ProjectDocumentCodec,
    private val thumbnailStore: ThumbnailStore,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ProjectRepository {

    override fun observeProjects(): Flow<List<ProjectSummary>> =
        dao.observeSummaries().map { rows -> rows.map { it.toDomain() } }

    override suspend fun getProject(projectId: String): EditorProject? = withContext(ioDispatcher) {
        val entity = dao.load(projectId) ?: return@withContext null
        try {
            codec.decode(entity.documentJson)
        } catch (error: Exception) {
            ProCutLogger.e(ProCutLogger.TAG_PROJECT, "Unable to open project ${entity.id}", error)
            null
        }
    }

    override suspend fun createProject(
        name: String,
        aspectRatio: AspectRatio,
        assets: List<MediaAsset>,
    ): EditorProject = withContext(ioDispatcher) {
        val project = EditorProject.create(name = name, aspectRatio = aspectRatio, assets = assets)
        saveInternal(project)
        project
    }

    override suspend fun saveProject(project: EditorProject) = withContext(ioDispatcher) {
        saveInternal(project)
    }

    private suspend fun saveInternal(project: EditorProject) {
        val thumbnailPath = resolveThumbnail(project)
        val entity = ProjectEntity(
            id = project.id,
            name = project.name,
            createdAtMs = project.createdAtMs,
            updatedAtMs = project.updatedAtMs,
            durationUs = project.durationUs,
            aspectRatio = project.aspectRatio.name,
            thumbnailPath = thumbnailPath,
            documentVersion = ProjectSchema.CURRENT_VERSION,
            documentJson = codec.encode(project.copy(schemaVersion = ProjectSchema.CURRENT_VERSION)),
            clipCount = project.videoClips.size,
        )
        dao.upsert(entity)
    }

    /** Generates a poster frame the first time a project has video, and reuses it afterwards. */
    private suspend fun resolveThumbnail(project: EditorProject): String? {
        val existing = File(FileUtils.projectThumbnailsDir(context), "${project.id}.jpg")
        if (existing.exists()) return existing.absolutePath
        val asset = project.videoClips.firstNotNullOfOrNull { project.asset(it.assetId) } ?: return null
        val bitmap: Bitmap = thumbnailStore.posterFrame(asset, 640) ?: return null
        return runCatching {
            existing.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out) }
            existing.absolutePath
        }.getOrNull()
    }

    override suspend fun renameProject(projectId: String, newName: String): EditorProject? =
        withContext(ioDispatcher) {
            val project = getProject(projectId) ?: return@withContext null
            val renamed = project.renamed(newName).touch()
            saveInternal(renamed)
            renamed
        }

    override suspend fun duplicateProject(projectId: String): EditorProject? = withContext(ioDispatcher) {
        val original = getProject(projectId) ?: return@withContext null
        val now = System.currentTimeMillis()
        val copy = original.copy(
            id = IdGenerator.projectId(),
            name = "${original.name} copy",
            createdAtMs = now,
            updatedAtMs = now,
        )
        saveInternal(copy)
        copy
    }

    override suspend fun deleteProject(projectId: String, deleteMedia: Boolean) = withContext(ioDispatcher) {
        val entity = dao.load(projectId)
        dao.delete(projectId)
        File(FileUtils.projectThumbnailsDir(context), "$projectId.jpg").delete()
        if (!deleteMedia || entity == null) return@withContext
        val project = runCatching { codec.decode(entity.documentJson) }.getOrNull() ?: return@withContext
        project.assets.forEach { asset ->
            // Never delete a file another project still points at.
            if (dao.countProjectsReferencing(asset.uri) == 0) {
                FileUtils.deleteQuietly(File(asset.uri.removePrefix("file://")))
            }
        }
    }

    override suspend fun updateThumbnail(projectId: String, thumbnailPath: String?) =
        withContext(ioDispatcher) { dao.updateThumbnail(projectId, thumbnailPath) }

    override suspend fun totalStoredMediaBytes(): Long = withContext(ioDispatcher) {
        FileUtils.totalBytes(FileUtils.mediaDir(context)) +
            FileUtils.totalBytes(FileUtils.projectThumbnailsDir(context))
    }

    private fun ProjectSummaryRow.toDomain(): ProjectSummary = ProjectSummary(
        id = id,
        name = name,
        updatedAtMs = updatedAtMs,
        durationUs = durationUs,
        aspectRatio = runCatching { AspectRatio.valueOf(aspectRatio) }.getOrDefault(AspectRatio.RATIO_16_9),
        thumbnailPath = thumbnailPath,
        clipCount = clipCount,
    )
}
