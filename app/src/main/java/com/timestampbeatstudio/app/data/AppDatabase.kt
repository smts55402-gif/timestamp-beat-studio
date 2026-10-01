package com.timestampbeatstudio.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** One transcription project. Raw words and inaudible ranges are stored as JSON;
 *  the second-by-second timeline is always rebuilt deterministically via
 *  core SecondBucketizer (never stored), so the pipeline stays reproducible. */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val audioDisplayName: String,
    val audioUriString: String,
    val durationSec: Double,
    val language: String?,
    val rawWordsJson: String,
    val inaudibleJson: String,
    val createdAt: Long
)

/** Manual per-second text correction. ORIGINAL vs CORRECTED are stored separately;
 *  the raw transcription JSON is never modified by edits. */
@Entity(tableName = "corrections", primaryKeys = ["projectId", "second"])
data class CorrectionEntity(
    val projectId: Long,
    val second: Int,
    val originalText: String,
    val correctedText: String
)

/** Record of an export the user performed. */
@Entity(tableName = "exports")
data class ExportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val format: String,
    val fileName: String,
    val createdAt: Long
)

@Dao
interface ProjectDao {
    @Insert
    suspend fun insert(project: ProjectEntity): Long

    @Update
    suspend fun update(project: ProjectEntity)

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getById(id: Long): ProjectEntity?

    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    fun observeRecent(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    suspend fun listRecent(): List<ProjectEntity>
}

@Dao
interface CorrectionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(correction: CorrectionEntity)

    @Query("SELECT * FROM corrections WHERE projectId = :projectId")
    suspend fun forProject(projectId: Long): List<CorrectionEntity>
}

@Dao
interface ExportDao {
    @Insert
    suspend fun insert(export: ExportEntity): Long

    @Query("SELECT * FROM exports WHERE projectId = :projectId ORDER BY createdAt DESC")
    suspend fun forProject(projectId: Long): List<ExportEntity>
}

@Database(
    entities = [ProjectEntity::class, CorrectionEntity::class, ExportEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun correctionDao(): CorrectionDao
    abstract fun exportDao(): ExportDao
}
