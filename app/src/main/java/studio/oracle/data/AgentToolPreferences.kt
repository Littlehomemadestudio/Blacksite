package studio.oracle.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.agentToolsStore by preferencesDataStore(name = "oracle_agent_tools")

/**
 * User-controlled capabilities exposed to Gemini. Sensitive capabilities are opt-in:
 * phone-file access is only enabled after the user grants a Storage Access Framework tree.
 */
data class AgentToolSettings(
    val googleSearch: Boolean = true,
    val urlContext: Boolean = false,
    val cloudCodeExecution: Boolean = false,
    val localPython: Boolean = true,
    val phoneFiles: Boolean = false,
    val thinkingLevel: String = "high",
    val maxOutputTokens: Int = 8192,
    val contextMessages: Int = 20
) {
    companion object {
        val THINKING_LEVELS = setOf("low", "medium", "high")
        val CONTEXT_CHOICES = setOf(12, 20, 32, 48)
        val OUTPUT_CHOICES = setOf(4096, 8192, 16384, 32768)
    }
}

class AgentToolPreferences(context: Context) {
    private val store = context.applicationContext.agentToolsStore

    val settings: Flow<AgentToolSettings> = store.data.map { values ->
        AgentToolSettings(
            googleSearch = values[GOOGLE_SEARCH] ?: true,
            urlContext = values[URL_CONTEXT] ?: false,
            cloudCodeExecution = values[CLOUD_CODE_EXECUTION] ?: false,
            localPython = values[LOCAL_PYTHON] ?: true,
            phoneFiles = values[PHONE_FILES] ?: false,
            thinkingLevel = values[THINKING_LEVEL] ?: "high",
            maxOutputTokens = values[MAX_OUTPUT_TOKENS] ?: 8192,
            contextMessages = values[CONTEXT_MESSAGES] ?: 20
        )
    }

    suspend fun setGoogleSearch(enabled: Boolean) = store.edit { it[GOOGLE_SEARCH] = enabled }
    suspend fun setUrlContext(enabled: Boolean) = store.edit { it[URL_CONTEXT] = enabled }
    suspend fun setCloudCodeExecution(enabled: Boolean) = store.edit { it[CLOUD_CODE_EXECUTION] = enabled }
    suspend fun setLocalPython(enabled: Boolean) = store.edit { it[LOCAL_PYTHON] = enabled }
    suspend fun setPhoneFiles(enabled: Boolean) = store.edit { it[PHONE_FILES] = enabled }

    suspend fun setThinkingLevel(value: String) {
        require(value in AgentToolSettings.THINKING_LEVELS)
        store.edit { it[THINKING_LEVEL] = value }
    }

    suspend fun setMaxOutputTokens(value: Int) {
        require(value in AgentToolSettings.OUTPUT_CHOICES)
        store.edit { it[MAX_OUTPUT_TOKENS] = value }
    }

    suspend fun setContextMessages(value: Int) {
        require(value in AgentToolSettings.CONTEXT_CHOICES)
        store.edit { it[CONTEXT_MESSAGES] = value }
    }

    private companion object {
        val GOOGLE_SEARCH = booleanPreferencesKey("agent_google_search")
        val URL_CONTEXT = booleanPreferencesKey("agent_url_context")
        val CLOUD_CODE_EXECUTION = booleanPreferencesKey("agent_cloud_code_execution")
        val LOCAL_PYTHON = booleanPreferencesKey("agent_local_python")
        val PHONE_FILES = booleanPreferencesKey("agent_phone_files")
        val THINKING_LEVEL = stringPreferencesKey("agent_thinking_level")
        val MAX_OUTPUT_TOKENS = intPreferencesKey("agent_max_output_tokens")
        val CONTEXT_MESSAGES = intPreferencesKey("agent_context_messages")
    }
}
