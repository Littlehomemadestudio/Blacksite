package studio.oracle.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "agent_sessions")
data class AgentSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val model: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "agent_messages",
    foreignKeys = [
        ForeignKey(
            entity = AgentSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sessionId"]), Index(value = ["createdAt"])]
)
data class AgentMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val role: String,
    val content: String,
    val createdAt: Long
)

@Entity(tableName = "documents", indices = [Index(value = ["importedAt"])])
data class DocumentEntity(
    @PrimaryKey val id: String,
    val title: String,
    val fileName: String,
    val mimeType: String,
    val storagePath: String,
    val sizeBytes: Long,
    val pageCount: Int,
    val extractedCharacters: Int,
    val importedAt: Long
)

@Entity(
    tableName = "document_chunks",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["documentId"]), Index(value = ["pageNumber"])]
)
data class DocumentChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: String,
    val ordinal: Int,
    val pageNumber: Int?,
    val sectionLabel: String,
    val content: String
)

@Entity(tableName = "workflows", indices = [Index(value = ["updatedAt"])])
data class WorkflowEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "workflow_steps",
    foreignKeys = [
        ForeignKey(
            entity = WorkflowEntity::class,
            parentColumns = ["id"],
            childColumns = ["workflowId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["workflowId", "position"])]
)
data class WorkflowStepEntity(
    @PrimaryKey val id: String,
    val workflowId: String,
    val position: Int,
    val actionType: String,
    val title: String,
    val input: String
)

@Entity(
    tableName = "local_notes",
    indices = [Index(value = ["title"], unique = true), Index(value = ["updatedAt"])]
)
data class LocalNoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "workflow_runs",
    foreignKeys = [
        ForeignKey(
            entity = WorkflowEntity::class,
            parentColumns = ["id"],
            childColumns = ["workflowId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["workflowId", "startedAt"])]
)
data class WorkflowRunEntity(
    @PrimaryKey val id: String,
    val workflowId: String,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val summary: String
)

@Entity(
    tableName = "workflow_run_logs",
    foreignKeys = [
        ForeignKey(
            entity = WorkflowRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["runId", "createdAt"])]
)
data class WorkflowRunLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: String,
    val stepId: String?,
    val status: String,
    val detail: String,
    val createdAt: Long
)

@Dao
interface AgentDao {
    @Query("SELECT * FROM agent_sessions ORDER BY updatedAt DESC")
    fun observeSessions(): Flow<List<AgentSessionEntity>>

    @Query("SELECT * FROM agent_sessions ORDER BY updatedAt DESC")
    suspend fun sessionsSnapshot(): List<AgentSessionEntity>

    @Query("SELECT * FROM agent_sessions WHERE id = :id LIMIT 1")
    suspend fun findSession(id: String): AgentSessionEntity?

    @Query("SELECT * FROM agent_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    fun observeMessages(sessionId: String): Flow<List<AgentMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: AgentSessionEntity)

    @Insert
    suspend fun insertMessage(message: AgentMessageEntity): Long

    @Query("UPDATE agent_sessions SET title = :title, model = :model, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateSession(id: String, title: String, model: String, updatedAt: Long)

    @Query("UPDATE agent_sessions SET model = :model, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateModel(id: String, model: String, updatedAt: Long)

    @Query("DELETE FROM agent_messages WHERE sessionId = :sessionId")
    suspend fun clearMessages(sessionId: String)

    @Query("UPDATE agent_sessions SET title = 'New thread', updatedAt = :updatedAt WHERE id = :sessionId")
    suspend fun resetTitle(sessionId: String, updatedAt: Long)

    @Query("DELETE FROM agent_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)
}

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY importedAt DESC")
    fun observeDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents ORDER BY importedAt DESC")
    suspend fun documentsSnapshot(): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun findDocument(id: String): DocumentEntity?

    @Query("SELECT * FROM document_chunks WHERE documentId = :documentId ORDER BY ordinal ASC")
    suspend fun chunksForDocument(documentId: String): List<DocumentChunkEntity>

    @Query("SELECT * FROM document_chunks WHERE content LIKE '%' || :term || '%' ORDER BY id DESC LIMIT 500")
    suspend fun chunksMatching(term: String): List<DocumentChunkEntity>

    @Insert
    suspend fun insertDocument(document: DocumentEntity)

    @Insert
    suspend fun insertChunks(chunks: List<DocumentChunkEntity>)

    @Transaction
    suspend fun saveDocumentWithChunks(document: DocumentEntity, chunks: List<DocumentChunkEntity>) {
        insertDocument(document)
        insertChunks(chunks)
    }

    @Query("DELETE FROM documents WHERE id = :documentId")
    suspend fun deleteDocument(documentId: String)
}

@Dao
interface WorkflowDao {
    @Query("SELECT * FROM workflows ORDER BY updatedAt DESC")
    fun observeWorkflows(): Flow<List<WorkflowEntity>>

    @Query("SELECT * FROM workflows WHERE id = :id LIMIT 1")
    suspend fun findWorkflow(id: String): WorkflowEntity?

    @Query("SELECT * FROM workflow_steps WHERE workflowId = :workflowId ORDER BY position ASC")
    suspend fun stepsFor(workflowId: String): List<WorkflowStepEntity>

    @Query("SELECT * FROM local_notes ORDER BY updatedAt DESC")
    fun observeNotes(): Flow<List<LocalNoteEntity>>

    @Query("SELECT * FROM local_notes WHERE title = :title COLLATE NOCASE LIMIT 1")
    suspend fun findNoteByTitle(title: String): LocalNoteEntity?

    @Query("SELECT * FROM workflow_runs ORDER BY startedAt DESC LIMIT 100")
    fun observeRuns(): Flow<List<WorkflowRunEntity>>

    @Query("SELECT * FROM workflow_runs WHERE workflowId = :workflowId ORDER BY startedAt DESC LIMIT 50")
    fun observeRunsForWorkflow(workflowId: String): Flow<List<WorkflowRunEntity>>

    @Query("SELECT * FROM workflow_run_logs WHERE runId = :runId ORDER BY createdAt ASC, id ASC")
    suspend fun logsForRun(runId: String): List<WorkflowRunLogEntity>

    @Insert
    suspend fun insertWorkflow(workflow: WorkflowEntity)

    @Query("UPDATE workflows SET title = :title, description = :description, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateWorkflow(id: String, title: String, description: String, updatedAt: Long)

    @Insert
    suspend fun insertSteps(steps: List<WorkflowStepEntity>)

    @Query("DELETE FROM workflow_steps WHERE workflowId = :workflowId")
    suspend fun deleteSteps(workflowId: String)

    @Transaction
    suspend fun replaceSteps(workflowId: String, steps: List<WorkflowStepEntity>) {
        deleteSteps(workflowId)
        if (steps.isNotEmpty()) insertSteps(steps)
    }

    @Insert
    suspend fun insertNote(note: LocalNoteEntity)

    @Query("UPDATE local_notes SET content = CASE WHEN content = '' THEN :suffix ELSE content || char(10) || char(10) || :suffix END, updatedAt = :now WHERE id = :id")
    suspend fun appendToNote(id: String, suffix: String, now: Long): Int

    @Insert
    suspend fun insertRun(run: WorkflowRunEntity)

    @Query("UPDATE workflow_runs SET status = :status, finishedAt = :finishedAt, summary = :summary WHERE id = :runId")
    suspend fun finishRun(runId: String, status: String, finishedAt: Long, summary: String)

    @Insert
    suspend fun insertRunLog(log: WorkflowRunLogEntity)

    @Query("DELETE FROM workflows WHERE id = :workflowId")
    suspend fun deleteWorkflow(workflowId: String)
}

@Database(
    entities = [
        AgentSessionEntity::class,
        AgentMessageEntity::class,
        DocumentEntity::class,
        DocumentChunkEntity::class,
        WorkflowEntity::class,
        WorkflowStepEntity::class,
        LocalNoteEntity::class,
        WorkflowRunEntity::class,
        WorkflowRunLogEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class OracleDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao
    abstract fun documentDao(): DocumentDao
    abstract fun workflowDao(): WorkflowDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS documents (" +
                        "id TEXT NOT NULL, title TEXT NOT NULL, fileName TEXT NOT NULL, " +
                        "mimeType TEXT NOT NULL, storagePath TEXT NOT NULL, sizeBytes INTEGER NOT NULL, " +
                        "pageCount INTEGER NOT NULL, extractedCharacters INTEGER NOT NULL, " +
                        "importedAt INTEGER NOT NULL, PRIMARY KEY(id))"
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_documents_importedAt ON documents(importedAt)")
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS document_chunks (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, documentId TEXT NOT NULL, " +
                        "ordinal INTEGER NOT NULL, pageNumber INTEGER, sectionLabel TEXT NOT NULL, " +
                        "content TEXT NOT NULL, FOREIGN KEY(documentId) REFERENCES documents(id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_document_chunks_documentId ON document_chunks(documentId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_document_chunks_pageNumber ON document_chunks(pageNumber)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS workflows (" +
                        "id TEXT NOT NULL, title TEXT NOT NULL, description TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))"
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_workflows_updatedAt ON workflows(updatedAt)")
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS workflow_steps (" +
                        "id TEXT NOT NULL, workflowId TEXT NOT NULL, position INTEGER NOT NULL, " +
                        "actionType TEXT NOT NULL, title TEXT NOT NULL, input TEXT NOT NULL, PRIMARY KEY(id), " +
                        "FOREIGN KEY(workflowId) REFERENCES workflows(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_workflow_steps_workflowId_position ON workflow_steps(workflowId, position)")
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS local_notes (" +
                        "id TEXT NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))"
                )
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_local_notes_title ON local_notes(title)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_local_notes_updatedAt ON local_notes(updatedAt)")
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS workflow_runs (" +
                        "id TEXT NOT NULL, workflowId TEXT NOT NULL, status TEXT NOT NULL, " +
                        "startedAt INTEGER NOT NULL, finishedAt INTEGER, summary TEXT NOT NULL, PRIMARY KEY(id), " +
                        "FOREIGN KEY(workflowId) REFERENCES workflows(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_workflow_runs_workflowId_startedAt ON workflow_runs(workflowId, startedAt)")
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS workflow_run_logs (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, runId TEXT NOT NULL, stepId TEXT, " +
                        "status TEXT NOT NULL, detail TEXT NOT NULL, createdAt INTEGER NOT NULL, " +
                        "FOREIGN KEY(runId) REFERENCES workflow_runs(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_workflow_run_logs_runId_createdAt ON workflow_run_logs(runId, createdAt)")
            }
        }

        @Volatile
        private var instance: OracleDatabase? = null

        fun get(context: Context): OracleDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    OracleDatabase::class.java,
                    "oracle_studio.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}
