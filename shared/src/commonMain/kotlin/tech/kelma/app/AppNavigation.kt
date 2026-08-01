package tech.kelma.app

internal enum class CollectionDestination {
    Decks,
    Add,
    Browse,
    Options,
    Plugins,
    Stats,
    Sync,
}

internal enum class CollectionNavigationAction {
    OpenDecks,
    OpenAdd,
    OpenBrowse,
    OpenOptions,
    OpenPlugins,
    OpenStats,
    OpenSync,
}

/** A single destination replaces stack-like combinations of independent visibility flags. */
internal fun CollectionDestination.navigate(action: CollectionNavigationAction): CollectionDestination = when (action) {
    CollectionNavigationAction.OpenDecks -> CollectionDestination.Decks
    CollectionNavigationAction.OpenAdd -> CollectionDestination.Add
    CollectionNavigationAction.OpenBrowse -> CollectionDestination.Browse
    CollectionNavigationAction.OpenOptions -> CollectionDestination.Options
    CollectionNavigationAction.OpenPlugins -> CollectionDestination.Plugins
    CollectionNavigationAction.OpenStats -> CollectionDestination.Stats
    CollectionNavigationAction.OpenSync -> CollectionDestination.Sync
}
