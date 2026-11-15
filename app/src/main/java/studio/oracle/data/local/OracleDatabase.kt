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
    val createdAt: Long
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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWorkspace(workspace: WorkspaceEntity)

    @Query("UPDATE workspaces SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun renameWorkspace(id: String, name: String, updatedAt: Long): Int
}

@Database(
    entities = [
        WorkspaceEntity::class,
        AgentSessionEntity::class,
        AgentMessageEntity::class,
        DocumentEntity::class,
        DocumentChunkEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class OracleDatabase : RoomDatabase() {
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun agentDao(): AgentDao
    abstract fun documentDao(): DocumentDao

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
