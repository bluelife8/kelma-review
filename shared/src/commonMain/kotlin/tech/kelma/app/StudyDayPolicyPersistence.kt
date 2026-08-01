package tech.kelma.app

import kotlinx.serialization.json.Json
import tech.kelma.db.KelmaDatabase
import tech.kelma.db.KelmaQueries

internal class StudyDayPolicyPersistence(
    database: KelmaDatabase,
    private val json: Json,
) {
    private val queries = database.kelmaQueries

    fun load(): AccountStudyDayPolicy = loadStudyDayPolicy(queries, json)

    fun observeCloud(
        policy: AccountStudyDayPolicy,
        nowMillis: Long = currentEpochMillis(),
    ): AccountStudyDayPolicy {
        val validated = policy.validated()
        require(validated.version > 0) { "KelmaSync returned an uninitialized study-day policy" }
        queries.upsertStudyDayPolicyState(json.encodeToString(validated), nowMillis)
        return validated
    }

    fun clearAccount() {
        queries.clearStudyDayPolicyState()
    }
}

internal fun loadStudyDayPolicy(queries: KelmaQueries, json: Json): AccountStudyDayPolicy =
    queries.selectStudyDayPolicyState { policyJson, _ ->
        json.decodeFromString<AccountStudyDayPolicy>(policyJson).validated()
    }.executeAsOneOrNull() ?: AccountStudyDayPolicy.systemDefault()
