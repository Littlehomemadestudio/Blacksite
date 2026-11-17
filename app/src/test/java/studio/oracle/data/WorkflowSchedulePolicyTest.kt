package studio.oracle.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowSchedulePolicyTest {
    @Test
    fun acceptsOnlySupportedDeferrableIntervals() {
        WorkflowSchedulePolicy.INTERVAL_OPTIONS_MINUTES.forEach { interval ->
            assertNull(WorkflowSchedulePolicy.validateInterval(interval))
        }
        assertTrue(WorkflowSchedulePolicy.validateInterval(14L)!!.contains("minimum 15"))
        assertTrue(WorkflowSchedulePolicy.validateInterval(45L)!!.contains("supported"))
        assertTrue(WorkflowSchedulePolicy.validateInterval(1_445L)!!.contains("supported"))
    }

    @Test
    fun intervalLabelsAreHumanReadable() {
        assertEquals("15 minutes", WorkflowSchedulePolicy.intervalLabel(15L))
        assertEquals("1 hour", WorkflowSchedulePolicy.intervalLabel(60L))
        assertEquals("24 hours", WorkflowSchedulePolicy.intervalLabel(1_440L))
    }

    @Test
    fun uniqueWorkNamesIsolateWorkspacesAndWorkflows() {
        val base = WorkflowSchedulePolicy.uniqueWorkName("workspace-a", "workflow-a")
        assertNotEquals(base, WorkflowSchedulePolicy.uniqueWorkName("workspace-b", "workflow-a"))
        assertNotEquals(base, WorkflowSchedulePolicy.uniqueWorkName("workspace-a", "workflow-b"))
    }
}
