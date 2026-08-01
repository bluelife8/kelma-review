package tech.kelma.app

data class SyncLogEntry(
    val id: Long,
    val occurredAtMillis: Long,
    val level: SyncLogLevel,
    val phase: String,
    val message: String,
)

enum class SyncLogLevel { Info, Success, Warning, Error }

data class SyncProgress(
    val level: SyncLogLevel = SyncLogLevel.Info,
    val phase: String,
    val message: String,
    val replaceLatest: Boolean = false,
)

internal fun sanitizeSyncLogMessage(message: String): String =
    if (message.contains("X-Amz-", ignoreCase = true)) {
        "Media transfer failed; temporary download credentials were redacted"
    } else {
        message
    }

internal fun SyncUploadPlan.summaryLines(): List<SyncProgress> = buildList {
    val noteUpserts = notes.count { it.operation != "delete" }
    val noteDeletes = notes.size - noteUpserts
    val cards = notes.sumOf { it.cards.size } + decks.sumOf { it.cards.size }
    val mediaBytes = media.sumOf { it.bytes.size.toLong() }
    add(
        SyncProgress(
            phase = "OUTBOX",
            message = "Snapshot: ${reviews.size.syncCount()} reviews · ${notes.size.syncCount()} notes " +
                "(${noteUpserts.syncCount()} upserts/${noteDeletes.syncCount()} deletes) · " +
                "${cards.syncCount()} cards · " +
                "${decks.size.syncCount()} deck changes · ${media.size.syncCount()} media " +
                "(${formatSyncBytes(mediaBytes)}) · ${if (schedulerProfile == null) 0 else 1} scheduler profile",
        ),
    )
    schedulerProfile?.let { profile ->
        add(
            SyncProgress(
                phase = "SCHEDULER PROFILE",
                message = "Pending cloud v${profile.baseProfileVersion} publication · " +
                    "parameters ${profile.parameterSource.name.lowercase()} · " +
                    "retention ${profile.retentionSource.name.lowercase()}",
            ),
        )
    }
    val deckUpserts = decks.count { it.operation == "upsert" }
    if (deckUpserts > 0) {
        add(SyncProgress(phase = "DECK", message = "${deckUpserts.syncCount()} deck upserts"))
    }
    decks.filter { it.operation != "upsert" }.forEach { deck ->
        val target = deck.targetName?.let { " → $it" }.orEmpty()
        add(
            SyncProgress(
                phase = "DECK",
                message = "${deck.operation}: ${deck.sourceName}$target · ${deck.cards.size.syncCount()} cards · " +
                    "delete ${(deck.deleteRequest?.cards?.size ?: 0).syncCount()} cards/" +
                    "${(deck.deleteRequest?.notes?.size ?: 0).syncCount()} notes",
            ),
        )
    }
}

enum class SyncPullResource(val phase: String, val label: String) {
    Manifest("MANIFEST", "change manifest"),
    Records("DOWNLOAD", "collection records"),
    Media("MEDIA DOWNLOAD", "media files"),
}

data class SyncPullProgress(
    val resource: SyncPullResource,
    val completed: Int,
    val total: Int,
    val completedBytes: Long = 0,
    val totalBytes: Long = 0,
    val detail: String = "",
)

internal fun SyncPullProgress.toSyncProgress(replaceLatest: Boolean): SyncProgress {
    val baseMessage = when {
        resource == SyncPullResource.Manifest && completed == 0 -> "Requesting the change manifest"
        resource == SyncPullResource.Manifest -> "Change manifest received"
        resource == SyncPullResource.Media && totalBytes > 0 && completed < total ->
            "Downloading ${resource.label} · ${formatSyncBytes(completedBytes)} / ${formatSyncBytes(totalBytes)} · " +
                "${completed.syncCount()} / ${total.syncCount()} files"
        resource == SyncPullResource.Media && totalBytes > 0 ->
            "Downloaded ${formatSyncBytes(totalBytes)} · ${total.syncCount()} ${resource.label}"
        completed < total -> "Downloading ${resource.label} · ${completed.syncCount()} / ${total.syncCount()}"
        else -> "Downloaded ${total.syncCount()} ${resource.label}"
    }
    val message = if (detail.isBlank()) baseMessage else "$baseMessage · $detail"
    return SyncProgress(
        level = if (completed == total) SyncLogLevel.Success else SyncLogLevel.Info,
        phase = resource.phase,
        message = message,
        replaceLatest = replaceLatest,
    )
}

internal fun SyncPushProgress.toSyncProgress(replaceLatest: Boolean): SyncProgress {
    val message = if (completed < total) {
        "Uploading ${resource.label} · ${completed.syncCount()} / ${total.syncCount()}"
    } else {
        buildString {
            append("Finished ${resource.label} · ")
            append(accepted.syncCount())
            append(" accepted")
            if (conflicts > 0) append(" · ${conflicts.syncCount()} conflicts")
        }
    }
    return SyncProgress(
        level = when {
            completed < total -> SyncLogLevel.Info
            conflicts > 0 -> SyncLogLevel.Warning
            else -> SyncLogLevel.Success
        },
        phase = resource.phase,
        message = message,
        replaceLatest = replaceLatest,
    )
}

internal fun formatSyncDuration(durationMillis: Long): String = when {
    durationMillis < 1_000L -> "$durationMillis ms"
    durationMillis < 60_000L -> {
        val tenths = (durationMillis + 50L) / 100L
        "${tenths / 10}.${tenths % 10} s"
    }
    else -> {
        val totalSeconds = durationMillis / 1_000L
        "${totalSeconds / 60}m ${totalSeconds % 60}s"
    }
}

private fun Int.syncCount(): String = toLong().syncCount()

private fun Long.syncCount(): String = toString().reversed().chunked(3).joinToString(",").reversed()

private fun formatSyncBytes(bytes: Long): String = when {
    bytes < 1_024L -> "$bytes B"
    bytes < 1_048_576L -> "${bytes / 1_024L} KiB"
    bytes < 1_073_741_824L -> {
        val tenths = bytes * 10L / 1_048_576L
        "${tenths / 10}.${tenths % 10} MiB"
    }
    else -> {
        val hundredths = bytes * 100L / 1_073_741_824L
        "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')} GiB"
    }
}
