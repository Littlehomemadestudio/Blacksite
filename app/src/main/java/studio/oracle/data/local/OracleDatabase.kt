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

@Database(
    entities = [AgentSessionEntity::class, AgentMessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class OracleDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao

    companion object {
        @Volatile
        private var instance: OracleDatabase? = null

        fun get(context: Context): OracleDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    OracleDatabase::class.java,
                    "oracle_studio.db"
                ).build().also { instance = it }
            }
    }
}
