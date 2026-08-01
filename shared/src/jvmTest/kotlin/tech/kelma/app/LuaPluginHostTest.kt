package tech.kelma.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import tech.kelma.db.KelmaDatabase

class LuaPluginHostTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun packagesLoadInDependencyOrderAndSafeModeStopsAllCode() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val database = KelmaDatabase(driver)
        val store = PersistentCollectionStore(database)
        val commands = PluginCommandRegistry()
        val host = store.createLuaPluginHost(commands, PluginEventRegistry(), PluginRendererRegistry())
        try {
            host.install(
                pluginDocument(
                    manifest("tech.kelma.base"),
                    mapOf(
                        "plugin/init.lua" to "kelma.log.info('base started')",
                        "lua/base/init.lua" to "return { suffix = ' from dependency' }",
                    ),
                ),
            )
            val feature = manifest(
                "tech.kelma.feature",
                capabilities = setOf(PluginCapability.Commands, PluginCapability.Events),
                dependencies = listOf(PluginDependency("tech.kelma.base", "1.0.0")),
            )
            val running = host.install(
                pluginDocument(
                    feature,
                    mapOf(
                        "plugin/init.lua" to
                            """
                            local base = require('base')
                            kelma.commands.register('tech.kelma.feature.hello', 'Hello', function(arguments)
                              return arguments.name .. base.suffix .. ':' ..
                                arguments.kelma_context.screen .. ':' .. arguments.kelma_context.deck_name
                            end)
                            kelma.events.subscribe('review.completed', function(event)
                              kelma.log.info('rated:' .. event.attributes.rating)
                            end)
                            """.trimIndent(),
                    ),
                ),
            )
            assertEquals(listOf("tech.kelma.base", "tech.kelma.feature"), running.running.map(RunningPlugin::pluginId))
            assertEquals(
                PluginValue.StringValue("Kelma from dependency:review:Deck"),
                commands.invoke(
                    PluginCommandInvocation(
                        "tech.kelma.feature.hello",
                        mapOf("name" to PluginValue.StringValue("Kelma")),
                        PluginCommandContext(screen = "review", deckName = "Deck"),
                    ),
                ),
            )
            assertEquals("base started", host.logs("tech.kelma.base").first().message)
            assertTrue(
                host.publish(
                    PluginEvent(
                        "review.completed",
                        mapOf("rating" to PluginValue.StringValue("Good")),
                    ),
                ).isEmpty(),
            )
            assertEquals("rated:Good", host.logs("tech.kelma.feature").first().message)

            val safe = host.setSafeMode(true)
            assertTrue(safe.safeMode)
            assertTrue(safe.running.isEmpty())
            assertTrue(commands.list().isEmpty())
            assertFalse(store.listInstalledPlugins().any { it.status == PluginStatus.Failed })

            val restored = host.setSafeMode(false)
            assertEquals(2, restored.running.size)
            assertEquals(1, commands.list().size)
        } finally {
            host.close()
            driver.close()
        }
    }

    @Test
    fun assignedRendererTransformsCompleteQuestionAndAnswerDocuments() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        val renderers = PluginRendererRegistry()
        val host = store.createLuaPluginHost(
            PluginCommandRegistry(),
            PluginEventRegistry(),
            renderers,
        )
        try {
            host.install(
                pluginDocument(
                    manifest("tech.kelma.renderer", capabilities = setOf(PluginCapability.Ui)),
                    mapOf(
                        "plugin/init.lua" to
                            """
                            kelma.ui.register_renderer('tech.kelma.renderer.wrap', function(request)
                              return {
                                html = '<section>' .. request.html .. '</section>',
                                css = request.css .. '.wrapped{display:block}',
                              }
                            end)
                            """.trimIndent(),
                    ),
                ),
            )
            val card = ReviewCard(
                id = 1,
                front = "front",
                back = "back",
                frontHtml = "<b>front</b>",
                fullAnswerHtml = "<b>front</b><hr id=answer><i>back</i>",
                cardCss = ".card{}",
            )

            val collection = SyncedCollection(
                notes = mapOf("note" to SyncNote("note", notetypeId = 100)),
                cards = mapOf(1L to SyncCard(1, "note", "Deck")),
            )
            val assignments = PluginRendererAssignmentState(
                listOf(
                    PluginRendererAssignment(
                        PluginRendererScope.Deck,
                        "Deck",
                        "tech.kelma.renderer.wrap",
                    ),
                ),
            )

            val batch = renderAssignedReviewCards(
                host,
                renderers,
                assignments,
                collection,
                listOf(card),
                runtimeGeneration = host.state().runtimeGeneration,
            )
            val rendered = batch.cards.getValue(1L).rendered

            assertEquals("<section><b>front</b></section>", rendered.frontHtml)
            assertTrue(rendered.fullAnswerHtml.orEmpty().contains("<hr id=answer>"))
            assertTrue(rendered.cardCss.contains(".wrapped"))
            assertTrue(rendered.answerCardCss.orEmpty().contains(".wrapped"))
            val cached = renderAssignedReviewCards(
                host,
                renderers,
                assignments,
                collection,
                listOf(card),
                runtimeGeneration = host.state().runtimeGeneration,
                existing = batch.cards,
            )
            assertSame(batch.cards.getValue(1L), cached.cards.getValue(1L))
        } finally {
            host.close()
            driver.close()
        }
    }

    @Test
    fun failedAndCapabilityViolatingPluginsAreAttributed() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KelmaDatabase.Schema.create(driver)
        val store = PersistentCollectionStore(KelmaDatabase(driver))
        val host = store.createLuaPluginHost(PluginCommandRegistry(), PluginEventRegistry(), PluginRendererRegistry())
        try {
            val state = host.install(
                pluginDocument(
                    manifest("tech.kelma.violation", capabilities = emptySet()),
                    mapOf(
                        "plugin/init.lua" to
                            "kelma.commands.register('tech.kelma.violation.command', 'Denied', function() end)",
                    ),
                ),
            )
            val failed = state.installed.single()
            assertEquals(PluginStatus.Failed, failed.status)
            assertTrue(failed.errorMessage.orEmpty().contains("capability denied", ignoreCase = true))
            assertTrue(state.running.isEmpty())
        } finally {
            host.close()
            driver.close()
        }
    }

    @Test
    fun packageParserRejectsMissingEntrypoint() {
        val manifest = manifest("tech.kelma.missing")
        val document = InterchangeDocument(
            "missing.kelmaplugin",
            writeStoredZip(listOf(StoredZipEntry("manifest.json", json.encodeToString(manifest).encodeToByteArray()))),
        )
        val failure = kotlin.runCatching { decodePluginPackage(document, json) }.exceptionOrNull()
        assertIs<IllegalArgumentException>(failure)
        assertTrue(failure.message.orEmpty().contains("entrypoint"))
    }

    private fun manifest(
        id: String,
        capabilities: Set<PluginCapability> = setOf(PluginCapability.Commands),
        dependencies: List<PluginDependency> = emptyList(),
    ) = PluginManifest(
        id = id,
        name = id.substringAfterLast('.'),
        version = "1.0.0",
        apiVersion = KelmaPluginApiVersion,
        entrypoint = "plugin/init.lua",
        capabilities = capabilities,
        dependencies = dependencies,
    )

    private fun pluginDocument(manifest: PluginManifest, sources: Map<String, String>): InterchangeDocument {
        val entries = buildList {
            add(StoredZipEntry("manifest.json", json.encodeToString(manifest).encodeToByteArray()))
            sources.toSortedMap().forEach { (path, source) -> add(StoredZipEntry(path, source.encodeToByteArray())) }
        }
        return InterchangeDocument("${manifest.id}.kelmaplugin", writeStoredZip(entries))
    }
}
