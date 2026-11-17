package studio.oracle.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.ColumnInfo
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

@Entity(
    tableName = "workspaces",
    indices = [Index(value = ["updatedAt"])]
)
data class WorkspaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "agent_sessions",
    indices = [Index(value = ["workspaceId", "updatedAt"])]
)
data class AgentSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val model: String,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "'workspace-default'")
    val workspaceId: String = "workspace-default"
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
    val createdAt: Long,
    @ColumnInfo(defaultValue = "''") val agentTrace: String = "",
    @ColumnInfo(defaultValue = "''") val attachments: String = ""
)

@Entity(
    tableName = "documents",
    indices = [Index(value = ["importedAt"]), Index(value = ["workspaceId"])]
)
data class DocumentEntity(
    @PrimaryKey val id: String,
    val title: String,
    val fileName: String,
    val mimeType: String,
    val storagePath: String,
    val sizeBytes: Long,
    val pageCount: Int,
    val extractedCharacters: Int,
    val importedAt: Long,
    @ColumnInfo(defaultValue = "'workspace-default'")
    val workspaceId: String = "workspace-default"
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

@Entity(
    tableName = "workflows",
    indices = [Index(value = ["workspaceId", "updatedAt"])]
)
data class WorkflowEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "'workspace-default'")
    val workspaceId: String = "workspace-default"
)

@Entity(
    tableName = "workflow_triggers",
    foreignKeys = [
        ForeignKey(
            entity = WorkflowEntity::class,
            parentColumns = ["id"],
            childColumns = ["workflowId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["workspaceId", "enabled"]),
        Index(value = ["workspaceId", "updatedAt"])
    ]
)
data class WorkflowTriggerEntity(
    @PrimaryKey val workflowId: String,
    val workspaceId: String,
    val intervalMinutes: Long,
    val enabled: Boolean,
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
    indices = [
        Index(value = ["workspaceId", "title"], unique = true),
        Index(value = ["workspaceId", "updatedAt"])
    ]
)
data class LocalNoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "'workspace-default'")
    val workspaceId: String = "workspace-default"
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
    @Query("SELECT * FROM agent_sessions WHERE workspaceId = :workspaceId ORDER BY updatedAt DESC")
    fun observeSessions(workspaceId: String): Flow<List<AgentSessionEntity>>

    @Query("SELECT * FROM agent_sessions WHERE workspaceId = :workspaceId ORDER BY updatedAt DESC")
    suspend fun sessionsSnapshot(workspaceId: String): List<AgentSessionEntity>

    @Query("SELECT * FROM agent_sessions WHERE id = :id LIMIT 1")
    suspend fun findSession(id: String): AgentSessionEntity?

    @Query("SELECT * FROM agent_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    fun observeMessages(sessionId: String): Flow<List<AgentMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: AgentSessionEntity)

    @Insert
    suspend fun insertMessage(message: AgentMessageEntity): Long

    @Query("UPDATE agent_messages SET agentTrace = :agentTrace WHERE id = :messageId")
    suspend fun updateMessageTrace(messageId: Long, agentTrace: String): Int

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
    @Query("SELECT * FROM documents WHERE workspaceId = :workspaceId ORDER BY importedAt DESC")
    fun observeDocuments(workspaceId: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE workspaceId = :workspaceId ORDER BY importedAt DESC")
    suspend fun documentsSnapshot(workspaceId: String): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun findDocument(id: String): DocumentEntity?

    @Query("SELECT * FROM document_chunks WHERE documentId = :documentId ORDER BY ordinal ASC")
    suspend fun chunksForDocument(documentId: String): List<DocumentChunkEntity>

    @Query(
        "SELECT document_chunks.* FROM document_chunks " +
            "INNER JOIN documents ON documents.id = document_chunks.documentId " +
            "WHERE documents.workspaceId = :workspaceId " +
            "AND document_chunks.content LIKE '%' || :term || '%' " +
            "ORDER BY document_chunks.id DESC LIMIT 500"
    )
    suspend fun chunksMatching(workspaceId: String, term: String): List<DocumentChunkEntity>

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
interface WorkspaceDao {
    @Query("SELECT * FROM workspaces ORDER BY updatedAt DESC, name COLLATE NOCASE ASC")
    fun observeWorkspaces(): Flow<List<WorkspaceEntity>>

    @Query("SELECT * FROM workspaces WHERE id = :id LIMIT 1")
    suspend fun findWorkspace(id: String): WorkspaceEntity?

    @Query("SELECT COUNT(*) FROM workspaces WHERE name = :name COLLATE NOCASE")
    suspend fun countName(name: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWorkspace(workspace: WorkspaceEntity)

    @Query("UPDATE workspaces SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun renameWorkspace(id: String, name: String, updatedAt: Long): Int
}

@Dao
interface WorkflowDao {
    @Query("SELECT * FROM workflows WHERE workspaceId = :workspaceId ORDER BY updatedAt DESC")
    fun observeWorkflows(workspaceId: String): Flow<List<WorkflowEntity>>

    @Query("SELECT * FROM workflows WHERE id = :id AND workspaceId = :workspaceId LIMIT 1")
    suspend fun findWorkflow(id: String, workspaceId: String): WorkflowEntity?

    @Query(
        "SELECT workflow_steps.* FROM workflow_steps " +
            "INNER JOIN workflows ON workflows.id = workflow_steps.workflowId " +
            "WHERE workflow_steps.workflowId = :workflowId AND workflows.workspaceId = :workspaceId " +
            "ORDER BY workflow_steps.position ASC"
    )
    suspend fun stepsFor(workflowId: String, workspaceId: String): List<WorkflowStepEntity>

    @Query("SELECT * FROM local_notes WHERE workspaceId = :workspaceId ORDER BY updatedAt DESC")
    fun observeNotes(workspaceId: String): Flow<List<LocalNoteEntity>>

    @Query("SELECT * FROM local_notes WHERE workspaceId = :workspaceId AND title = :title COLLATE NOCASE LIMIT 1")
    suspend fun findNoteByTitle(title: String, workspaceId: String): LocalNoteEntity?

    @Query(
        "SELECT workflow_runs.* FROM workflow_runs " +
            "INNER JOIN workflows ON workflows.id = workflow_runs.workflowId " +
            "WHERE workflows.workspaceId = :workspaceId " +
            "ORDER BY workflow_runs.startedAt DESC LIMIT 100"
    )
    fun observeRuns(workspaceId: String): Flow<List<WorkflowRunEntity>>

    @Query(
        "SELECT workflow_runs.* FROM workflow_runs " +
            "INNER JOIN workflows ON workflows.id = workflow_runs.workflowId " +
            "WHERE workflow_runs.workflowId = :workflowId AND workflows.workspaceId = :workspaceId " +
            "ORDER BY workflow_runs.startedAt DESC LIMIT 50"
    )
    fun observeRunsForWorkflow(workflowId: String, workspaceId: String): Flow<List<WorkflowRunEntity>>

    @Query(
        "SELECT workflow_run_logs.* FROM workflow_run_logs " +
            "INNER JOIN workflow_runs ON workflow_runs.id = workflow_run_logs.runId " +
            "INNER JOIN workflows ON workflows.id = workflow_runs.workflowId " +
            "WHERE workflow_run_logs.runId = :runId AND workflows.workspaceId = :workspaceId " +
            "ORDER BY workflow_run_logs.createdAt ASC, workflow_run_logs.id ASC"
    )
    suspend fun logsForRun(runId: String, workspaceId: String): List<WorkflowRunLogEntity>

    @Insert
    suspend fun insertWorkflow(workflow: WorkflowEntity)

    @Query(
        "UPDATE workflows SET title = :title, description = :description, updatedAt = :updatedAt " +
            "WHERE id = :id AND workspaceId = :workspaceId"
    )
    suspend fun updateWorkflow(
        id: String,
        workspaceId: String,
        title: String,
        description: String,
        updatedAt: Long
    ): Int

    @Insert
    suspend fun insertSteps(steps: List<WorkflowStepEntity>)

    @Query("DELETE FROM workflow_steps WHERE workflowId = :workflowId")
    suspend fun deleteSteps(workflowId: String)

    @Transaction
    suspend fun replaceSteps(workflowId: String, steps: List<WorkflowStepEntity>) {
        deleteSteps(workflowId)
        if (steps.isNotEmpty()) insertSteps(steps)
    }

    @Transaction
    suspend fun saveWorkflowAndSteps(
        id: String,
        workspaceId: String,
        title: String,
        description: String,
        updatedAt: Long,
        steps: List<WorkflowStepEntity>
    ): Boolean {
        if (updateWorkflow(id, workspaceId, title, description, updatedAt) != 1) return false
        replaceSteps(id, steps)
        return true
    }

    @Insert
    suspend fun insertNote(note: LocalNoteEntity)

    @Query(
        "UPDATE local_notes SET content = CASE WHEN content = '' THEN :suffix " +
            "ELSE content || char(10) || char(10) || :suffix END, updatedAt = :now " +
            "WHERE id = :id AND workspaceId = :workspaceId"
    )
    suspend fun appendToNote(id: String, workspaceId: String, suffix: String, now: Long): Int

    @Insert
    suspend fun insertRun(run: WorkflowRunEntity)

    @Query("UPDATE workflow_runs SET status = :status, finishedAt = :finishedAt, summary = :summary WHERE id = :runId")
    suspend fun finishRun(runId: String, status: String, finishedAt: Long, summary: String)

    @Insert
    suspend fun insertRunLog(log: WorkflowRunLogEntity)

    @Query("DELETE FROM workflows WHERE id = :workflowId AND workspaceId = :workspaceId")
    suspend fun deleteWorkflow(workflowId: String, workspaceId: String): Int
}

@Dao
interface WorkflowTriggerDao {
    @Query("SELECT * FROM workflow_triggers WHERE workspaceId = :workspaceId ORDER BY updatedAt DESC")
    fun observeTriggers(workspaceId: String): Flow<List<WorkflowTriggerEntity>>

    @Query("SELECT * FROM workflow_triggers WHERE workflowId = :workflowId AND workspaceId = :workspaceId LIMIT 1")
    suspend fun findTrigger(workflowId: String, workspaceId: String): WorkflowTriggerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrigger(trigger: WorkflowTriggerEntity)

    @Query("DELETE FROM workflow_triggers WHERE workflowId = :workflowId AND workspaceId = :workspaceId")
    suspend fun deleteTrigger(workflowId: String, workspaceId: String): Int
}

@Entity(
    tableName = "audio_memories",
    indices = [
        Index(value = ["workspaceId", "createdAt"]),
        Index(value = ["workspaceId", "title"])
    ]
)
data class AudioMemoryEntity(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val title: String,
    val storagePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val createdAt: Long,
    val transcript: String,
    val summary: String,
    val tasksJson: String,
    val decisionsJson: String
)

@Dao
interface AudioMemoryDao {
    @Query("SELECT * FROM audio_memories WHERE workspaceId = :workspaceId ORDER BY createdAt DESC")
    fun observeRecords(workspaceId: String): Flow<List<AudioMemoryEntity>>

    @Query("SELECT * FROM audio_memories WHERE id = :id AND workspaceId = :workspaceId LIMIT 1")
    suspend fun findRecord(id: String, workspaceId: String): AudioMemoryEntity?

    @Query(
        "SELECT * FROM audio_memories WHERE workspaceId = :workspaceId AND (" +
            "title LIKE '%' || :query || '%' COLLATE NOCASE OR " +
            "transcript LIKE '%' || :query || '%' COLLATE NOCASE OR " +
            "summary LIKE '%' || :query || '%' COLLATE NOCASE OR " +
            "tasksJson LIKE '%' || :query || '%' COLLATE NOCASE OR " +
            "decisionsJson LIKE '%' || :query || '%' COLLATE NOCASE) " +
            "ORDER BY createdAt DESC LIMIT 100"
    )
    suspend fun search(workspaceId: String, query: String): List<AudioMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: AudioMemoryEntity)

    @Query(
        "UPDATE audio_memories SET transcript = :transcript, summary = :summary, " +
            "tasksJson = :tasksJson, decisionsJson = :decisionsJson " +
            "WHERE id = :id AND workspaceId = :workspaceId"
    )
    suspend fun saveAnalysis(
        id: String, workspaceId: String, transcript: String, summary: String,
        tasksJson: String, decisionsJson: String
    ): Int

    @Query("UPDATE audio_memories SET title = :title WHERE id = :id AND workspaceId = :workspaceId")
    suspend fun rename(id: String, workspaceId: String, title: String): Int

    @Query("DELETE FROM audio_memories WHERE id = :id AND workspaceId = :workspaceId")
    suspend fun delete(id: String, workspaceId: String): Int
}


@Entity(
    tableName = "exam_papers",
    indices = [
        Index(value = ["workspaceId", "updatedAt"]),
        Index(value = ["workspaceId", "status"])
    ]
)
data class ExamPaperEntity(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val title: String,
    val subject: String,
    val grade: String,
    val durationMinutes: Int,
    val totalMarks: Double,
    val paperJson: String,
    val answerKeyJson: String,
    val answersJson: String,
    val status: String,
    val score: Double?,
    val createdAt: Long,
    val updatedAt: Long
)

@Dao
interface ExamPaperDao {
    @Query("SELECT * FROM exam_papers WHERE workspaceId = :workspaceId ORDER BY updatedAt DESC")
    fun observePapers(workspaceId: String): Flow<List<ExamPaperEntity>>

    @Query("SELECT * FROM exam_papers WHERE id = :id AND workspaceId = :workspaceId LIMIT 1")
    suspend fun find(id: String, workspaceId: String): ExamPaperEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ExamPaperEntity)

    @Query(
        "UPDATE exam_papers SET answersJson = :answersJson, status = :status, score = :score, updatedAt = :updatedAt " +
            "WHERE id = :id AND workspaceId = :workspaceId"
    )
    suspend fun updateAttempt(
        id: String,
        workspaceId: String,
        answersJson: String,
        status: String,
        score: Double?,
        updatedAt: Long
    ): Int

    @Query("DELETE FROM exam_papers WHERE id = :id AND workspaceId = :workspaceId")
    suspend fun delete(id: String, workspaceId: String): Int
}

@Database(
    entities = [
        WorkspaceEntity::class,
        AgentSessionEntity::class,
        AgentMessageEntity::class,
        DocumentEntity::class,
        DocumentChunkEntity::class,
        WorkflowEntity::class,
        WorkflowStepEntity::class,
        LocalNoteEntity::class,
        WorkflowRunEntity::class,
        WorkflowRunLogEntity::class,
        WorkflowTriggerEntity::class,
        AudioMemoryEntity::class,
        ExamPaperEntity::class
    ],
    version = 9,
    exportSchema = false
)
abstract class OracleDatabase : RoomDatabase() {
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun agentDao(): AgentDao
    abstract fun documentDao(): DocumentDao
    abstract fun workflowDao(): WorkflowDao
    abstract fun workflowTriggerDao(): WorkflowTriggerDao
    abstract fun audioMemoryDao(): AudioMemoryDao
    abstract fun examPaperDao(): ExamPaperDao

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
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_documents_importedAt ON documents(importedAt)"
                )
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS document_chunks (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, documentId TEXT NOT NULL, " +
                        "ordinal INTEGER NOT NULL, pageNumber INTEGER, sectionLabel TEXT NOT NULL, " +
                        "content TEXT NOT NULL, FOREIGN KEY(documentId) REFERENCES documents(id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_document_chunks_documentId ON document_chunks(documentId)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_document_chunks_pageNumber ON document_chunks(pageNumber)"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS workspaces (" +
                        "id TEXT NOT NULL, name TEXT NOT NULL, description TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_workspaces_updatedAt ON workspaces(updatedAt)"
                )
                database.execSQL(
                    "INSERT OR IGNORE INTO workspaces (id, name, description, createdAt, updatedAt) " +
                        "VALUES ('workspace-default', 'Personal', 'Your personal workspace', 0, 0)"
                )
                database.execSQL(
                    "ALTER TABLE agent_sessions ADD COLUMN workspaceId TEXT NOT NULL DEFAULT 'workspace-default'"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_agent_sessions_workspaceId_updatedAt " +
                        "ON agent_sessions(workspaceId, updatedAt)"
                )
                database.execSQL(
                    "ALTER TABLE documents ADD COLUMN workspaceId TEXT NOT NULL DEFAULT 'workspace-default'"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_documents_workspaceId ON documents(workspaceId)"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS workspaces (" +
                        "id TEXT NOT NULL, name TEXT NOT NULL, description TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))"
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_workspaces_updatedAt ON workspaces(updatedAt)")
                database.execSQL(
                    "INSERT OR IGNORE INTO workspaces (id, name, description, createdAt, updatedAt) " +
                        "VALUES ('workspace-default', 'Personal', 'Your personal workspace', 0, 0)"
                )
                if (!database.hasColumn("agent_sessions", "workspaceId")) {
                    database.execSQL("ALTER TABLE agent_sessions ADD COLUMN workspaceId TEXT NOT NULL DEFAULT 'workspace-default'")
                }
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_agent_sessions_workspaceId_updatedAt " +
                        "ON agent_sessions(workspaceId, updatedAt)"
                )
                if (!database.hasColumn("documents", "workspaceId")) {
                    database.execSQL("ALTER TABLE documents ADD COLUMN workspaceId TEXT NOT NULL DEFAULT 'workspace-default'")
                }
                database.execSQL("CREATE INDEX IF NOT EXISTS index_documents_workspaceId ON documents(workspaceId)")

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

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                if (!database.hasColumn("workflows", "workspaceId")) {
                    database.execSQL(
                        "ALTER TABLE workflows ADD COLUMN workspaceId TEXT NOT NULL DEFAULT 'workspace-default'"
                    )
                }
                database.execSQL("DROP INDEX IF EXISTS index_workflows_updatedAt")
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_workflows_workspaceId_updatedAt " +
                        "ON workflows(workspaceId, updatedAt)"
                )

                if (!database.hasColumn("local_notes", "workspaceId")) {
                    database.execSQL(
                        "ALTER TABLE local_notes ADD COLUMN workspaceId TEXT NOT NULL DEFAULT 'workspace-default'"
                    )
                }
                database.execSQL("DROP INDEX IF EXISTS index_local_notes_title")
                database.execSQL("DROP INDEX IF EXISTS index_local_notes_updatedAt")
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_local_notes_workspaceId_title " +
                        "ON local_notes(workspaceId, title)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_local_notes_workspaceId_updatedAt " +
                        "ON local_notes(workspaceId, updatedAt)"
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                if (!database.hasColumn("agent_messages", "agentTrace")) {
                    database.execSQL("ALTER TABLE agent_messages ADD COLUMN agentTrace TEXT NOT NULL DEFAULT ''")
                }
                if (!database.hasColumn("agent_messages", "attachments")) {
                    database.execSQL("ALTER TABLE agent_messages ADD COLUMN attachments TEXT NOT NULL DEFAULT ''")
                }
            }
        }


        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS audio_memories (" +
                        "id TEXT NOT NULL, workspaceId TEXT NOT NULL, title TEXT NOT NULL, " +
                        "storagePath TEXT NOT NULL, mimeType TEXT NOT NULL, sizeBytes INTEGER NOT NULL, " +
                        "durationMs INTEGER NOT NULL, createdAt INTEGER NOT NULL, transcript TEXT NOT NULL, " +
                        "summary TEXT NOT NULL, tasksJson TEXT NOT NULL, decisionsJson TEXT NOT NULL, " +
                        "PRIMARY KEY(id))"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_audio_memories_workspaceId_createdAt " +
                        "ON audio_memories(workspaceId, createdAt)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_audio_memories_workspaceId_title " +
                        "ON audio_memories(workspaceId, title)"
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS workflow_triggers (" +
                        "workflowId TEXT NOT NULL, workspaceId TEXT NOT NULL, " +
                        "intervalMinutes INTEGER NOT NULL, enabled INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, PRIMARY KEY(workflowId), " +
                        "FOREIGN KEY(workflowId) REFERENCES workflows(id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_workflow_triggers_workspaceId_enabled " +
                        "ON workflow_triggers(workspaceId, enabled)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_workflow_triggers_workspaceId_updatedAt " +
                        "ON workflow_triggers(workspaceId, updatedAt)"
                )
            }
        }


        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS exam_papers (" +
                        "id TEXT NOT NULL, workspaceId TEXT NOT NULL, title TEXT NOT NULL, " +
                        "subject TEXT NOT NULL, grade TEXT NOT NULL, durationMinutes INTEGER NOT NULL, " +
                        "totalMarks REAL NOT NULL, paperJson TEXT NOT NULL, answerKeyJson TEXT NOT NULL, " +
                        "answersJson TEXT NOT NULL, status TEXT NOT NULL, score REAL, " +
                        "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id))"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_exam_papers_workspaceId_updatedAt " +
                        "ON exam_papers(workspaceId, updatedAt)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_exam_papers_workspaceId_status " +
                        "ON exam_papers(workspaceId, status)"
                )
            }
        }

        private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean =
            query("PRAGMA table_info($table)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIndex >= 0 && cursor.getString(nameIndex) == column) return@use true
                }
                false
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                    .build()
                    .also { instance = it }
            }
    }
}
