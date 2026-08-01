# Kotlin plugin system

## Principle

Plugins are trusted third-party code. Installing one is equivalent to installing software. Kelma uses restricted Lua libraries, capability gates, memory and instruction budgets, safe mode, and diagnostics as defense in depth, but does not claim that an in-process runtime is an operating-system security boundary.

Lua is the portable public plugin format. JVM JARs are an optional desktop-only escape hatch.

## Lua runtime

Embed standard Lua 5.4, not LuaJIT.

```text
Android/JVM desktop -> JNI -> Lua C API
iOS                 -> Kotlin/Native cinterop -> Lua C API
```

The same Lua plugin source runs on desktop, Android, and the iOS community build. Kelma vendors unmodified standard Lua 5.4.8 under its MIT license and wraps it with a small MIT C host.

## Package layout

A `.kelmaplugin` file is a ZIP archive with this layout:

```text
manifest.json
plugin/example.lua
lua/example/init.lua
lua/example/server.lua
LICENSE
```

The loader provides Neovim-like runtime paths and `require()` behavior. A plugin's own `lua/` tree takes precedence over dependency runtime paths. Plugins may depend on other plugins by stable ID and compatible minimum semantic version.

```json
{
  "id": "org.example.hello",
  "name": "Hello",
  "version": "1.0.0",
  "apiVersion": 1,
  "entrypoint": "plugin/example.lua",
  "runtime": "lua54",
  "capabilities": ["Commands", "Events"],
  "dependencies": []
}
```

Create the archive with ordinary ZIP tooling and give it the `.kelmaplugin`
extension. Paths must be relative, portable ASCII, and unique; packages are limited to 16 MiB,
256 files, and 2 MiB per file.

## Implemented runtime

The shared client provides API generation 1 manifests pinned to the `lua54`
runtime identifier, semantic-version validation, deterministic minimum-version
dependency ordering, cycle/missing-dependency blocking, durable package files,
enablement, logs, and safe mode, JSON-compatible boundary values, and
namespaced command, event, and pure renderer registries.

Standard Lua 5.4 executes through JNI on JVM desktop and Android and through
Kotlin/Native cinterop on iOS. Every plugin receives a separate state with a
32 MiB allocator budget and a five-million-instruction budget per protected
call. `io`, `os`, `debug`, `load`, `loadfile`, `dofile`, bytecode dumping, dynamic
C loaders, and the ordinary filesystem package searchers are unavailable. Package paths, expanded
sizes, file counts, UTF-8 Lua sources, entrypoints, dependencies, namespaces,
and declared capabilities are validated before execution. The manager previews
capabilities before installation and supports reload, disable, safe mode,
diagnostics, command inspection, durable deck/note-type renderer assignments,
and uninstall. Failures and startup time are attributed by plugin ID without
card contents. Assignments survive temporary plugin unavailability and safely
fall back to the original Kelma card.

## Current Lua API

```text
kelma.commands.register(id, title, callback)
kelma.events.subscribe(name, callback)
kelma.ui.register_renderer(id, callback)
kelma.json.encode(value) / decode(text)
kelma.json.null / array(value) / object(value)
kelma.log.debug/info/warn/error(message)
kelma.capabilities.commands/events/ui
```

`app.started` and content-free `review.completed` events are currently emitted.
Command arguments and results are JSON-compatible values. Every command also
receives `arguments.kelma_context`, containing the current screen and optional
deck name without note content. Stable screen values are `sign-in`,
`deck-overview`, `review`, `decks`, `add`, `browse`, `options`, `plugins`,
`stats`, and `sync`. Commands are searchable and keyboard-selectable through Cmd/Ctrl+K on desktop.
Mobile exposes plugin command actions from Plugin Manager rather than a global
command-palette modal. Built-in
navigation/sync commands use the same registry.

Renderers are pure HTML/CSS transforms. Users explicitly assign them by deck or
note type in Plugin Manager; the nearest assigned deck or parent deck takes
precedence. Kelma renders both the complete question and complete answer,
validates 2 MiB HTML and 512
KiB CSS limits, caches unchanged results for the active queue, and falls back
to the unmodified card on failure. Platform WebViews, media embedding, CSP,
network blocking, and external-navigation blocking remain owned by Kelma.

The capability-versioned roadmap adds `kelma.services`, transactional notes,
cards, decks, and reviews, portable host UI trees, permission-scoped files and
networking, workers/channels, desktop processes, plugin-owned SQLite, storage,
and OS-backed secrets. These names are not exposed until their host service is
implemented; declaration in a manifest does not manufacture an unavailable API.

Built-in behavior should use registries that plugins can also target:

```text
SchedulerRegistry
ReviewQueueRegistry
RendererRegistry
ImporterRegistry
ExporterRegistry
SyncProviderRegistry
ScreenRegistry
CommandRegistry
```

## Concurrency

Lua coroutines are cooperative, not parallel. Each OS worker thread receives a separate `lua_State`; workers communicate with messages. A plugin may own a localhost server using raw sockets or a bundled generic event-loop library. Kelma-specific server code is not required.

Collection calls from worker states use asynchronous RPC onto the core/database dispatcher.

## UI

Portable Lua plugins return a host UI tree rendered by Compose. Generic escape hatches include canvas, HTML/WebView, menus, keyboard/gesture hooks, and custom windows where supported.

Desktop JAR plugins may implement direct Compose panels, but these are tied to the supported Kotlin and Compose ABI range.

## Desktop JAR plugins

After the Lua API stabilizes, use PF4J or a similarly small Apache-licensed loader. A JAR can implement the public `KelmaPlugin` interface and use JVM libraries. JAR plugins are fully trusted and desktop-only.

The public JAR SDK must not expose internal database classes. Restart-after-update is acceptable initially; reliable class unloading is not a requirement.

## Platform limits

- Desktop: Lua, workers, processes, and optional native/JAR modules.
- Android: Lua within the app sandbox; background servers require Android lifecycle/foreground-service support.
- iOS community: Lua while the app is active; no downloaded native modules or arbitrary processes.
- iOS App Store: no external plugin runtime in the submitted build.

## Compatibility

Every API is versioned. Prefer additive evolution. Plugins declare a supported API range. Collection changes use stable DTOs rather than internal Kotlin classes.

## Operational requirements

- Start without plugins/safe mode.
- Attribute errors and startup time to a plugin.
- Disable a plugin without deleting its data.
- Detect dependency cycles.
- Preserve plugin configuration when temporarily unavailable.
- Provide logs, event/command inspection, and a plugin bisect command.

## Power test

An independent Lua plugin must be able to implement an AnkiConnect-compatible localhost API using only generic sockets/workers/JSON and public collection APIs. No AnkiConnect-specific Kotlin code is allowed.
