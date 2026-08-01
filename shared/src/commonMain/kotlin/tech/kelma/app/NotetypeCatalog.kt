package tech.kelma.app

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** A note type the editor can create, with its ordered fields and generated card templates. */
data class AddNotetype(
    val id: Long,
    val name: String,
    val fieldNames: List<String>,
    val cardOrds: List<Int>,
)

/** A read-only view of a generated card template, used by the Cards… dialog. */
data class TemplatePreview(
    val name: String,
    val qfmt: String,
    val afmt: String,
)

/**
 * Built-in note types available to the editor. Their definitions are merged into the collection so
 * cards created offline render with correct question and answer templates.
 */
object NotetypeCatalog {
    const val BasicId = -1L
    const val BasicReversedId = -2L

    val basic = AddNotetype(BasicId, "Basic", listOf("Front", "Back"), listOf(0))
    val basicReversed = AddNotetype(
        BasicReversedId,
        "Basic (and reversed card)",
        listOf("Front", "Back"),
        listOf(0, 1),
    )
    val builtIns: List<AddNotetype> = listOf(basic, basicReversed)

    val definitions: Map<Long, SyncNotetype> = mapOf(
        BasicId to SyncNotetype(
            notetypeId = BasicId,
            name = basic.name,
            definition = definition(
                basic.fieldNames,
                listOf(template("Card 1", 0, "{{Front}}", "{{FrontSide}}<hr id=answer>{{Back}}")),
            ),
        ),
        BasicReversedId to SyncNotetype(
            notetypeId = BasicReversedId,
            name = basicReversed.name,
            definition = definition(
                basicReversed.fieldNames,
                listOf(
                    template("Card 1", 0, "{{Front}}", "{{FrontSide}}<hr id=answer>{{Back}}"),
                    template("Card 2", 1, "{{Back}}", "{{FrontSide}}<hr id=answer>{{Front}}"),
                ),
            ),
        ),
    )

    fun forId(id: Long): AddNotetype = builtIns.firstOrNull { it.id == id } ?: basic

    fun templatesFor(id: Long): List<TemplatePreview> {
        val definition = definitions[id]?.definition ?: return emptyList()
        val templates = definition["tmpls"] as? JsonArray ?: return emptyList()
        return templates.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            TemplatePreview(obj.text("name"), obj.text("qfmt"), obj.text("afmt"))
        }
    }

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.content ?: ""

    private fun definition(fields: List<String>, templates: List<JsonObject>): JsonObject = buildJsonObject {
        putJsonArray("flds") {
            fields.forEach { name -> addJsonObject { put("name", name) } }
        }
        putJsonArray("tmpls") {
            templates.forEach { add(it) }
        }
    }

    private fun template(name: String, ord: Int, qfmt: String, afmt: String): JsonObject = buildJsonObject {
        put("name", name)
        put("ord", ord)
        put("qfmt", qfmt)
        put("afmt", afmt)
    }
}
