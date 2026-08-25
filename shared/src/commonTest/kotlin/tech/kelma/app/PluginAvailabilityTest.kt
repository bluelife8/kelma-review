package tech.kelma.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginAvailabilityTest {
    @Test
    fun appStoreBuildRejectsPluginNavigation() {
        assertFalse(pluginNavigationAvailable(false, CollectionDestination.Plugins))
        assertTrue(pluginNavigationAvailable(false, CollectionDestination.Options))
        assertTrue(pluginNavigationAvailable(true, CollectionDestination.Plugins))
    }

    @Test
    fun appStoreBuildIgnoresPersistedRendererAssignments() {
        val assignment = PluginRendererAssignment(
            scope = PluginRendererScope.Deck,
            targetId = "Deck",
            rendererId = "tech.kelma.sample.renderer",
        )
        val state = PluginRendererAssignmentState(listOf(assignment))

        assertEquals(state, pluginRendererAssignmentsForBuild(true, state))
        assertTrue(pluginRendererAssignmentsForBuild(false, state).assignments.isEmpty())
    }
}
