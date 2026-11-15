package studio.oracle.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.oraclePreferences by preferencesDataStore(name = "oracle_preferences")

/** Stores non-secret app preferences. API credentials remain in SecureKeyStore. */
class ModelPreferences(context: Context) {
    private val store = context.applicationContext.oraclePreferences

    val selectedModel: Flow<String> = store.data.map { preferences ->
        preferences[MODEL_KEY]?.takeUnless { it == "gemini-2.5-flash" } ?: DEFAULT_MODEL
    }

    suspend fun setSelectedModel(model: String) {
        require(model.isNotBlank() && model.matches(Regex("[A-Za-z0-9._-]+"))) {
            "Invalid Gemini model identifier."
        }
        store.edit { preferences -> preferences[MODEL_KEY] = model }
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.5-flash"
        private val MODEL_KEY = stringPreferencesKey("selected_gemini_model")
    }
}


/** Persists the active local workspace independently from the Gemini model choice. */
class WorkspacePreferences(context: Context) {
    private val store = context.applicationContext.oraclePreferences

    val activeWorkspaceId: Flow<String?> = store.data.map { preferences ->
        preferences[ACTIVE_WORKSPACE_KEY]
    }

    suspend fun setActiveWorkspaceId(id: String) {
        require(id.isNotBlank()) { "Workspace id cannot be blank." }
        store.edit { preferences -> preferences[ACTIVE_WORKSPACE_KEY] = id }
    }

    companion object {
        private val ACTIVE_WORKSPACE_KEY = stringPreferencesKey("active_workspace_id")
    }
}
