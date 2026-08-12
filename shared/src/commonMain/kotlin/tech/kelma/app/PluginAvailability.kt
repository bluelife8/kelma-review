package tech.kelma.app

internal fun pluginNavigationAvailable(
    externalPluginsEnabled: Boolean,
    destination: CollectionDestination,
): Boolean = externalPluginsEnabled || destination != CollectionDestination.Plugins

internal fun pluginRendererAssignmentsForBuild(
    externalPluginsEnabled: Boolean,
    assignments: PluginRendererAssignmentState,
): PluginRendererAssignmentState = if (externalPluginsEnabled) assignments else PluginRendererAssignmentState()
