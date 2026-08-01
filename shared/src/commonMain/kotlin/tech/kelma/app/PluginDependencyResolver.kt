package tech.kelma.app

data class PluginResolution(
    val loadOrder: List<InstalledPlugin>,
    val blocked: Map<String, String>,
)

internal fun resolvePluginLoadOrder(plugins: List<InstalledPlugin>): PluginResolution {
    val enabled = plugins.filter(InstalledPlugin::enabled).associateBy { it.manifest.id }
    val blocked = linkedMapOf<String, String>()
    enabled.values.forEach { plugin ->
        plugin.manifest.dependencies.filterNot(PluginDependency::optional).forEach { dependency ->
            val installed = enabled[dependency.id]
            when {
                installed == null && plugin.manifest.id !in blocked ->
                    blocked[plugin.manifest.id] = "Missing dependency ${dependency.id}"
                installed != null &&
                    SemanticVersion.parse(installed.manifest.version) < SemanticVersion.parse(dependency.minimumVersion) &&
                    plugin.manifest.id !in blocked ->
                    blocked[plugin.manifest.id] = "Dependency ${dependency.id} is too old"
            }
        }
    }
    val ordered = mutableListOf<InstalledPlugin>()
    val visiting = mutableSetOf<String>()
    val visited = mutableSetOf<String>()

    fun visit(plugin: InstalledPlugin): Boolean {
        val id = plugin.manifest.id
        if (id in visited) return id !in blocked
        if (!visiting.add(id)) {
            blocked[id] = "Dependency cycle"
            return false
        }
        var valid = id !in blocked
        plugin.manifest.dependencies.forEach { dependency ->
            val target = enabled[dependency.id]
            if (target != null && !visit(target) && !dependency.optional) valid = false
        }
        visiting.remove(id)
        visited += id
        if (valid && id !in blocked) ordered += plugin
        else if (id !in blocked) blocked[id] = "Dependency is blocked"
        return valid
    }

    enabled.values.sortedBy { it.manifest.id }.forEach(::visit)
    return PluginResolution(ordered.distinctBy { it.manifest.id }, blocked)
}
