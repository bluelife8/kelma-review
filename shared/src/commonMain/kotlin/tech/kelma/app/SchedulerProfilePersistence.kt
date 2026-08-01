package tech.kelma.app

import kotlinx.serialization.json.Json
import tech.kelma.db.KelmaDatabase

internal class SchedulerProfilePersistence(
    private val database: KelmaDatabase,
    private val json: Json,
    private val rebuildSchedules: () -> Unit,
) {
    private val queries = database.kelmaQueries

    fun load(): SchedulerProfileState {
        val local = queries.selectActiveLocalSchedulerProfile { version, settingsJson, createdAt ->
            LocalSchedulerProfile(
                version = version,
                settings = json.decodeFromString<SchedulerProfileSettings>(settingsJson).validated(),
                createdAtMillis = createdAt,
            )
        }.executeAsOneOrNull() ?: SchedulerProfileState().local
        val cloudState = queries.selectCloudSchedulerProfile { cloudJson, projectionsJson ->
            cloudJson?.let { json.decodeFromString<CloudSchedulerProfile>(it) } to
                projectionsJson?.let { json.decodeFromString<SchedulerProjectionSummary>(it) }
        }.executeAsOneOrNull()
        val outbox = loadOutbox()
        return SchedulerProfileState(
            local = local,
            cloud = cloudState?.first,
            projections = cloudState?.second ?: SchedulerProjectionSummary(),
            syncStatus = when (outbox?.state) {
                "pending" -> SchedulerProfileSyncStatus.Pending
                "uploaded" -> SchedulerProfileSyncStatus.AwaitingConfirmation
                "conflict" -> SchedulerProfileSyncStatus.Conflict
                else -> SchedulerProfileSyncStatus.Current
            },
            desiredLocalVersion = outbox?.localProfileVersion,
            desiredCloudBaseVersion = outbox?.baseProfileVersion,
            acknowledgedCloudVersion = outbox?.acknowledgedProfileJson?.let {
                json.decodeFromString<CloudSchedulerProfile>(it).version
            },
        )
    }

    fun activeOptions(): DeckOptions = load().local.settings.asDeckOptions()

    fun applyLocal(
        settings: SchedulerProfileSettings,
        publishToCloud: Boolean,
        nowMillis: Long,
    ): SchedulerProfileState {
        val validated = settings.validated(forCloud = publishToCloud)
        database.transaction {
            queries.insertLocalSchedulerProfile(json.encodeToString(validated), nowMillis)
            val localVersion = checkNotNull(
                queries.selectLatestLocalSchedulerProfileVersion().executeAsOne().max,
            )
            queries.ensureSchedulerProfileState()
            queries.activateLocalSchedulerProfile(localVersion)
            if (publishToCloud) {
                require(queries.selectAuth().executeAsOneOrNull() != null) {
                    "Sign in before publishing an account scheduler profile"
                }
                val existing = loadOutbox()
                require(existing?.state != "uploaded") {
                    "Wait for the acknowledged scheduler profile to be confirmed"
                }
                require(existing?.state != "conflict") {
                    "Resolve the scheduler profile conflict before publishing again"
                }
                val baseVersion = loadCloudProfile()?.version ?: 0L
                val idempotencyKey = randomUuidString()
                val candidate = validated.toCandidate(baseVersion, idempotencyKey)
                queries.upsertSchedulerProfileOutbox(
                    localVersion,
                    baseVersion,
                    idempotencyKey,
                    json.encodeToString(candidate),
                    nowMillis,
                )
            }
            rebuildSchedules()
        }
        return load()
    }

    fun applyCloudLocally(nowMillis: Long): SchedulerProfileState {
        val cloud = requireNotNull(loadCloudProfile()) { "Sync a KelmaSync scheduler profile first" }
        return applyLocal(cloud.asSettings(), publishToCloud = false, nowMillis = nowMillis)
    }

    fun prepare(): SchedulerProfileCandidate? = loadOutbox()
        ?.takeIf { it.state == "pending" }
        ?.candidate

    fun applyPush(result: SyncPushResult) {
        database.transaction {
            result.acknowledgedSchedulerProfile?.profile?.let { acknowledged ->
                val outbox = loadOutbox()
                if (outbox != null && outbox.idempotencyKey == acknowledged.idempotencyKey) {
                    queries.markSchedulerProfileOutboxUploaded(
                        acknowledgedProfileJson = json.encodeToString(acknowledged),
                        idempotencyKey = outbox.idempotencyKey,
                    )
                }
            }
            result.conflicts.firstOrNull { it.kind == SchedulerProfileConflictKind }?.let { conflict ->
                val server = json.decodeFromString<CloudSchedulerProfile>(conflict.serverJson)
                val projections = loadProjectionSummary()
                queries.upsertCloudSchedulerProfile(
                    json.encodeToString(server),
                    json.encodeToString(projections),
                    currentEpochMillis(),
                )
                queries.markSchedulerProfileOutboxConflict(conflict.serverJson)
            }
        }
    }

    fun observeCloud(response: SchedulerProfileResponse, nowMillis: Long) {
        response.profile.asSettings()
        database.transaction {
            queries.upsertCloudSchedulerProfile(
                json.encodeToString(response.profile),
                json.encodeToString(response.projections),
                nowMillis,
            )
            val outbox = loadOutbox()
            if (outbox?.state == "uploaded") {
                val acknowledged = outbox.acknowledgedProfileJson?.let {
                    json.decodeFromString<CloudSchedulerProfile>(it)
                }
                val confirmed = acknowledged != null &&
                    response.profile.version == acknowledged.version &&
                    response.profile.idempotencyKey == outbox.idempotencyKey &&
                    response.profile.configHash == acknowledged.configHash
                if (confirmed) {
                    queries.deleteSchedulerProfileOutbox()
                } else {
                    queries.markSchedulerProfileOutboxConflict(json.encodeToString(response.profile))
                }
            }
        }
    }

    fun resolveConflict(keepLocal: Boolean, nowMillis: Long) {
        database.transaction {
            val outbox = requireNotNull(loadOutbox()?.takeIf { it.state == "conflict" }) {
                "Scheduler profile conflict no longer exists"
            }
            if (!keepLocal) {
                queries.deleteSchedulerProfileOutbox()
                return@transaction
            }
            val server = requireNotNull(loadCloudProfile()) { "KelmaSync profile is unavailable" }
            val active = load().local
            val idempotencyKey = randomUuidString()
            val candidate = active.settings.toCandidate(server.version, idempotencyKey)
            queries.retrySchedulerProfileOutbox(
                localProfileVersion = active.version,
                baseProfileVersion = server.version,
                idempotencyKey = idempotencyKey,
                candidateJson = json.encodeToString(candidate),
                createdAt = nowMillis,
            )
        }
    }

    fun clearAccount() {
        queries.clearSchedulerProfileOutbox()
        queries.clearSchedulerProfileState()
        queries.clearLocalSchedulerProfileVersions()
    }

    private fun loadCloudProfile(): CloudSchedulerProfile? =
        queries.selectCloudSchedulerProfile { cloudJson, projectionsJson -> cloudJson to projectionsJson }
            .executeAsOneOrNull()
            ?.first
            ?.let { json.decodeFromString(it) }

    private fun loadProjectionSummary(): SchedulerProjectionSummary =
        queries.selectCloudSchedulerProfile { cloudJson, projectionsJson -> cloudJson to projectionsJson }
            .executeAsOneOrNull()
            ?.second
            ?.let { json.decodeFromString(it) }
            ?: SchedulerProjectionSummary()

    private fun loadOutbox(): SchedulerProfileOutboxRow? =
        queries.selectSchedulerProfileOutbox {
                localVersion, baseVersion, idempotencyKey, candidateJson, state,
                acknowledgedJson, conflictJson, createdAt ->
            SchedulerProfileOutboxRow(
                localProfileVersion = localVersion,
                baseProfileVersion = baseVersion,
                idempotencyKey = idempotencyKey,
                candidate = json.decodeFromString(candidateJson),
                state = state,
                acknowledgedProfileJson = acknowledgedJson,
                conflictJson = conflictJson,
                createdAtMillis = createdAt,
            )
        }.executeAsOneOrNull()
}

private data class SchedulerProfileOutboxRow(
    val localProfileVersion: Long,
    val baseProfileVersion: Long,
    val idempotencyKey: String,
    val candidate: SchedulerProfileCandidate,
    val state: String,
    val acknowledgedProfileJson: String?,
    val conflictJson: String?,
    val createdAtMillis: Long,
)

internal const val SchedulerProfileConflictKind = "scheduler_profile"
