package studio.oracle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePolicyTest {
    @Test
    fun normalizesWhitespaceWithoutChangingMeaningfulCharacters() {
        assertEquals("Physics Notes", WorkspacePolicy.normalizeName("  Physics   Notes  "))
        assertEquals("پروژه ریاضی", WorkspacePolicy.normalizeName("  پروژه   ریاضی "))
    }

    @Test
    fun rejectsBlankAndOverlongNames() {
        assertNull(WorkspacePolicy.normalizeName("   \n  "))
        assertNull(WorkspacePolicy.normalizeName("x".repeat(WorkspacePolicy.MAX_NAME_LENGTH + 1)))
    }

    @Test
    fun acceptsTheMaximumSupportedNameLength() {
        val name = "x".repeat(WorkspacePolicy.MAX_NAME_LENGTH)
        assertEquals(name, WorkspacePolicy.normalizeName(name))
    }

    @Test
    fun exposesStablePersonalWorkspaceIdentity() {
        assertEquals("workspace-default", WorkspacePolicy.DEFAULT_WORKSPACE_ID)
        assertEquals("Personal", WorkspacePolicy.DEFAULT_WORKSPACE_NAME)
        assertTrue(WorkspacePolicy.MAX_NAME_LENGTH > 0)
    }
}
