package tech.kelma.app

internal const val KelmaCommandPrefix = "tech.kelma.app."
internal const val OpenDecksCommand = "${KelmaCommandPrefix}open-decks"
internal const val OpenAddCommand = "${KelmaCommandPrefix}open-add"
internal const val OpenBrowseCommand = "${KelmaCommandPrefix}open-browse"
internal const val OpenOptionsCommand = "${KelmaCommandPrefix}open-options"
internal const val OpenStatsCommand = "${KelmaCommandPrefix}open-stats"
internal const val OpenSyncLogCommand = "${KelmaCommandPrefix}open-sync"
internal const val OpenPluginsCommand = "${KelmaCommandPrefix}open-plugins"
internal const val SyncNowCommand = "${KelmaCommandPrefix}sync-now"

internal fun PluginCommandRegistry.registerKelmaCommands() {
    listOf(
        OpenDecksCommand to "Open Decks",
        OpenAddCommand to "Add note",
        OpenBrowseCommand to "Browse cards",
        OpenOptionsCommand to "Open Options",
        OpenStatsCommand to "Open Stats",
        OpenSyncLogCommand to "Open Sync activity",
        OpenPluginsCommand to "Manage plugins",
        SyncNowCommand to "Sync now",
    ).forEach { (id, title) ->
        register(
            PluginCommand(
                pluginId = "tech.kelma.app",
                id = id,
                title = title,
                execute = { PluginValue.StringValue(title) },
            ),
        )
    }
}

private val KelmaCommandIds = setOf(
    OpenDecksCommand,
    OpenAddCommand,
    OpenBrowseCommand,
    OpenOptionsCommand,
    OpenStatsCommand,
    OpenSyncLogCommand,
    OpenPluginsCommand,
    SyncNowCommand,
)

internal fun isKelmaCommand(commandId: String): Boolean = commandId in KelmaCommandIds

internal fun CollectionDestination.pluginScreenName(): String = when (this) {
    CollectionDestination.Decks -> "decks"
    CollectionDestination.Add -> "add"
    CollectionDestination.Browse -> "browse"
    CollectionDestination.Options -> "options"
    CollectionDestination.Plugins -> "plugins"
    CollectionDestination.Stats -> "stats"
    CollectionDestination.Sync -> "sync"
}
