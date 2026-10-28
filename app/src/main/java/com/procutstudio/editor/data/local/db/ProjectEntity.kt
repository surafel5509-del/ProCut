package com.procutstudio.editor.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Room row for a project.
 *
 * The editing document lives in [documentJson] so a project is always read and written atomically;
 * the remaining columns are denormalised copies used for cheap list queries (recent projects,
 * sorting, thumbnails) without deserialising every document.
 */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at") val updatedAtMs: Long,
    @ColumnInfo(name = "duration_us") val durationUs: Long,
    @ColumnInfo(name = "aspect_ratio") val aspectRatio: String,
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String?,
    @ColumnInfo(name = "document_version") val documentVersion: Int,
    @ColumnInfo(name = "document_json") val documentJson: String,
    @ColumnInfo(name = "clip_count") val clipCount: Int,
)

/** Projection used by the home screen. */
data class ProjectSummaryRow(
    val id: String,
    val name: String,
    @ColumnInfo(name = "updated_at") val updatedAtMs: Long,
    @ColumnInfo(name = "duration_us") val durationUs: Long,
    @ColumnInfo(name = "aspect_ratio") val aspectRatio: String,
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String?,
    @ColumnInfo(name = "clip_count") val clipCount: Int,
)

@Dao
interface ProjectDao {

    @Query(
        """
        SELECT id, name, updated_at, duration_us, aspect_ratio, thumbnail_path, clip_count
        FROM projects
        ORDER BY updated_at DESC
        """,
    )
    fun observeSummaries(): Flow<List<ProjectSummaryRow>>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun load(id: String): ProjectEntity?

    @Query("SELECT * FROM projects ORDER BY updated_at DESC")
    suspend fun loadAll(): List<ProjectEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM projects WHERE document_json LIKE '%' || :needle || '%'")
    suspend fun countProjectsReferencing(needle: String): Int

    @Query("UPDATE projects SET thumbnail_path = :path WHERE id = :id")
    suspend fun updateThumbnail(id: String, path: String?)

    @Query("UPDATE projects SET name = :name, updated_at = :updatedAtMs WHERE id = :id")
    suspend fun updateName(id: String, name: String, updatedAtMs: Long)
}

@Database(entities = [ProjectEntity::class], version = 1, exportSchema = true)
abstract class ProCutDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    companion object {
        const val NAME = "procut.db"
    }
}
