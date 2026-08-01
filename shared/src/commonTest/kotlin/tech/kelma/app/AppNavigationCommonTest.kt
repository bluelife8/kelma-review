package tech.kelma.app

import kotlin.test.Test
import kotlin.test.assertEquals

class AppNavigationCommonTest {
    @Test
    fun browseToAddToDecksNeverRevealsBrowseAgain() {
        val afterBrowse = CollectionDestination.Decks.navigate(CollectionNavigationAction.OpenBrowse)
        val afterAdd = afterBrowse.navigate(CollectionNavigationAction.OpenAdd)
        val afterDecks = afterAdd.navigate(CollectionNavigationAction.OpenDecks)

        assertEquals(CollectionDestination.Browse, afterBrowse)
        assertEquals(CollectionDestination.Add, afterAdd)
        assertEquals(CollectionDestination.Decks, afterDecks)
    }

    @Test
    fun everyTopLevelActionReplacesEveryPreviousDestination() {
        val expected = mapOf(
            CollectionNavigationAction.OpenDecks to CollectionDestination.Decks,
            CollectionNavigationAction.OpenAdd to CollectionDestination.Add,
            CollectionNavigationAction.OpenBrowse to CollectionDestination.Browse,
            CollectionNavigationAction.OpenOptions to CollectionDestination.Options,
            CollectionNavigationAction.OpenPlugins to CollectionDestination.Plugins,
            CollectionNavigationAction.OpenStats to CollectionDestination.Stats,
            CollectionNavigationAction.OpenSync to CollectionDestination.Sync,
        )

        CollectionDestination.entries.forEach { startingDestination ->
            expected.forEach { (action, destination) ->
                assertEquals(destination, startingDestination.navigate(action))
            }
        }
    }
}
