package studio.oracle.data

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CrossMemoryStoreTest {
    private lateinit var preferences: SharedPreferences
    private lateinit var store: CrossMemoryStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferences = context.getSharedPreferences("oracle_cross_memory", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        store = CrossMemoryStore(context)
    }

    @After
    fun tearDown() {
        preferences.edit().clear().commit()
    }

    @Test
    fun memoryIsDisabledByDefault() {
        assertFalse(store.isEnabled())
        assertEquals("", store.contextBlock("ORACLE workspace"))
    }

    @Test
    fun explicitOptInIsPersisted() {
        store.setEnabled(true)
        assertTrue(store.isEnabled())
    }

    @Test
    fun disabledMemoryTrailerIsStrippedWithoutBeingSaved() {
        val visible = store.consumeReply(
            """The answer.
<oracle-memory>{"items":[{"category":"project","content":"ORACLE workspace uses a dark native UI"}]}</oracle-memory>"""
        )
        assertEquals("The answer.", visible)
        assertEquals("", store.contextBlock("ORACLE workspace"))
    }

    @Test
    fun onlyKeywordRelevantMemoriesAreIncluded() {
        store.setEnabled(true)
        store.consumeReply(
            """Saved.
<oracle-memory>{"items":[{"category":"project","content":"ORACLE Android workspace uses a dark native UI"},{"category":"preference","content":"Prefers concise study explanations"}]}</oracle-memory>"""
        )

        val context = store.contextBlock("Continue the ORACLE Android workspace UI")
        assertTrue(context.contains("dark native UI"))
        assertFalse(context.contains("concise study explanations"))
    }

    @Test
    fun unrelatedRequestsReceiveNoMemoryContext() {
        store.setEnabled(true)
        store.consumeReply(
            """Saved.
<oracle-memory>{"items":[{"category":"project","content":"ORACLE Android workspace uses a dark native UI"}]}</oracle-memory>"""
        )

        assertEquals("", store.contextBlock("Explain photosynthesis"))
    }

    @Test
    fun clearingRemovesSavedMemories() {
        store.setEnabled(true)
        store.consumeReply(
            """Saved.
<oracle-memory>{"items":[{"category":"project","content":"ORACLE Android workspace uses a dark native UI"}]}</oracle-memory>"""
        )
        store.clear()

        assertEquals("", store.contextBlock("ORACLE Android workspace"))
    }
}
