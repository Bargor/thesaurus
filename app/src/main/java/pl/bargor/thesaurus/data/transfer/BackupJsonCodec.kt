package pl.bargor.thesaurus.data.transfer

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Explicit strict streaming I/O; no Gson reflection, Unsafe, domain objects or floating amounts. */
object BackupJsonCodec {
    private val integerPattern = Regex("-?(0|[1-9][0-9]*)")
    private val datePattern = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val instantPattern = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.[0-9]{1,9})?Z")
    fun encode(document: BackupDocument): ByteArray {
        BackupContract.validate(document)
        val output = ByteArrayOutputStream()
        val bounded = object : OutputStream() {
            private var count = 0
            override fun write(value: Int) {
                limit(count < BackupLimits.MAX_BYTES)
                output.write(value)
                count++
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                limit(length <= BackupLimits.MAX_BYTES - count)
                output.write(bytes, offset, length)
                count += length
            }
        }
        JsonWriter(OutputStreamWriter(bounded, Charsets.UTF_8)).use { writer ->
            writer.setStrictness(Strictness.STRICT)
            writer.serializeNulls = true
            write(writer, document.toWire())
        }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray): BackupDocument {
        limit(bytes.size <= BackupLimits.MAX_BYTES)
        val content = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            fail(TransferErrorCode.MALFORMED_UTF8)
        }
        return try {
            JsonReader(StringReader(content)).use { reader ->
                reader.setStrictness(Strictness.STRICT)
                reader.setNestingLimit(BackupLimits.MAX_JSON_DEPTH)
                val root = read(reader, 0, Shape.DOCUMENT).obj()
                if (reader.peek() != JsonToken.END_DOCUMENT) fail(TransferErrorCode.MALFORMED_JSON)
                root.document().also(BackupContract::validate)
            }
        } catch (error: BackupContractException) {
            throw error
        } catch (_: IOException) {
            fail(TransferErrorCode.MALFORMED_JSON)
        } catch (_: IllegalStateException) {
            fail(TransferErrorCode.MALFORMED_JSON)
        } catch (_: IllegalArgumentException) {
            fail(TransferErrorCode.INVALID_VALUE)
        }
    }

    private data class NumberToken(val lexical: String)

    private enum class Shape { DOCUMENT, METADATA, CATEGORY, SUBCATEGORY, ENTRY, CATEGORIES, SUBCATEGORIES, ENTRIES, TAGS, SCALAR }
    private val documentFields = setOf("formatVersion", "metadata", "openingBalanceGrosze", "categories", "subcategories", "entries")
    private val metadataFields = setOf("exportedAt", "sourceHouseholdId", "appVersion")
    private val categoryFields = setOf("id", "name", "authorId", "updatedById", "color", "archived", "defaultEntryType", "createdAt", "updatedAt")
    private val subcategoryFields = setOf("id", "categoryId", "name", "authorId", "updatedById", "archived", "createdAt", "updatedAt")
    private val entryFields = setOf("id", "amountGrosze", "date", "categoryId", "authorId", "updatedById", "title", "subcategoryId", "tags", "createdAt", "updatedAt", "deleted", "deletedAt", "deletedById")

    private fun read(reader: JsonReader, depth: Int, shape: Shape): Any? = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            limit(depth < BackupLimits.MAX_JSON_DEPTH)
            val allowed = when (shape) {
                Shape.DOCUMENT -> documentFields
                Shape.METADATA -> metadataFields
                Shape.CATEGORY -> categoryFields
                Shape.SUBCATEGORY -> subcategoryFields
                Shape.ENTRY -> entryFields
                else -> fail(TransferErrorCode.INVALID_VALUE)
            }
            val result = linkedMapOf<String, Any?>()
            reader.beginObject()
            while (reader.hasNext()) {
                limit(result.size < 32)
                val name = reader.nextName()
                validUnicode(name)
                if (result.containsKey(name)) fail(TransferErrorCode.MALFORMED_JSON)
                valid(name in allowed)
                val child = when {
                    shape == Shape.DOCUMENT && name == "metadata" -> Shape.METADATA
                    shape == Shape.DOCUMENT && name == "categories" -> Shape.CATEGORIES
                    shape == Shape.DOCUMENT && name == "subcategories" -> Shape.SUBCATEGORIES
                    shape == Shape.DOCUMENT && name == "entries" -> Shape.ENTRIES
                    shape == Shape.ENTRY && name == "tags" -> Shape.TAGS
                    else -> Shape.SCALAR
                }
                result[name] = read(reader, depth + 1, child)
                if (shape == Shape.DOCUMENT && name == "formatVersion" && result.number(name) != 1L) {
                    fail(TransferErrorCode.UNSUPPORTED_VERSION)
                }
            }
            reader.endObject()
            result
        }
        JsonToken.BEGIN_ARRAY -> {
            limit(depth < BackupLimits.MAX_JSON_DEPTH)
            val (maximum, child) = when (shape) {
                Shape.CATEGORIES -> BackupLimits.MAX_CATEGORIES to Shape.CATEGORY
                Shape.SUBCATEGORIES -> BackupLimits.MAX_SUBCATEGORIES to Shape.SUBCATEGORY
                Shape.ENTRIES -> BackupLimits.MAX_ENTRIES to Shape.ENTRY
                Shape.TAGS -> 10 to Shape.SCALAR
                else -> fail(TransferErrorCode.INVALID_VALUE)
            }
            val result = mutableListOf<Any?>()
            reader.beginArray()
            while (reader.hasNext()) {
                limit(result.size < maximum)
                result += read(reader, depth + 1, child)
            }
            reader.endArray()
            result
        }
        JsonToken.STRING -> reader.nextString().also(::validUnicode)
        JsonToken.NUMBER -> NumberToken(reader.nextString())
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NULL -> { reader.nextNull(); null }
        else -> fail(TransferErrorCode.MALFORMED_JSON)
    }

    private fun write(writer: JsonWriter, value: Any?) {
        when (value) {
            null -> writer.nullValue()
            is String -> writer.value(value)
            is Long -> writer.value(value)
            is Int -> writer.value(value.toLong())
            is Boolean -> writer.value(value)
            is Map<*, *> -> {
                writer.beginObject()
                value.forEach { (key, item) -> writer.name(key as String); write(writer, item) }
                writer.endObject()
            }
            is List<*> -> {
                writer.beginArray(); value.forEach { write(writer, it) }; writer.endArray()
            }
            else -> fail(TransferErrorCode.INVALID_VALUE)
        }
    }

    private fun Any?.obj(): Map<String, Any?> {
        if (this !is Map<*, *>) fail(TransferErrorCode.INVALID_VALUE)
        @Suppress("UNCHECKED_CAST")
        return this as Map<String, Any?>
    }
    private fun Map<String, Any?>.fields(vararg allowed: String) {
        valid(keys.all { it in allowed })
    }
    private fun Map<String, Any?>.string(key: String): String =
        this[key] as? String ?: fail(TransferErrorCode.INVALID_VALUE)
    private fun Map<String, Any?>.optionalString(key: String): String? =
        if (this[key] == null) null else string(key)
    private fun Map<String, Any?>.boolean(key: String): Boolean =
        this[key] as? Boolean ?: fail(TransferErrorCode.INVALID_VALUE)
    private fun Map<String, Any?>.number(key: String): Long {
        val number = this[key] as? NumberToken ?: fail(TransferErrorCode.INVALID_VALUE)
        valid(number.lexical.matches(integerPattern))
        return number.lexical.toLongOrNull() ?: fail(TransferErrorCode.INVALID_VALUE)
    }
    private fun Map<String, Any?>.array(key: String, max: Int): List<Any?> {
        val list = this[key] as? List<*> ?: fail(TransferErrorCode.INVALID_VALUE)
        limit(list.size <= max)
        return list
    }
    private fun Map<String, Any?>.time(key: String): Instant? = optionalString(key)?.let {
        valid(it.matches(instantPattern))
        try {
            Instant.parse(it).also { parsed ->
                // Instant.parse accepts leap seconds and 24:00; require a real ISO calendar time.
                valid(java.time.LocalDateTime.parse(it.removeSuffix("Z")).toInstant(java.time.ZoneOffset.UTC) == parsed)
            }
        } catch (_: DateTimeParseException) { fail(TransferErrorCode.INVALID_VALUE) }
    }
    private fun Map<String, Any?>.date(key: String): LocalDate {
        val value = string(key)
        valid(value.matches(datePattern))
        return try { LocalDate.parse(value) } catch (_: DateTimeParseException) {
            fail(TransferErrorCode.INVALID_VALUE)
        }
    }

    private fun Map<String, Any?>.document(): BackupDocument {
        val version = number("formatVersion")
        if (version != 1L) fail(TransferErrorCode.UNSUPPORTED_VERSION)
        fields("formatVersion", "metadata", "openingBalanceGrosze", "categories", "subcategories", "entries")
        val metadata = this["metadata"].obj().run {
            fields("exportedAt", "sourceHouseholdId", "appVersion")
            ExportMetadata(time("exportedAt") ?: fail(TransferErrorCode.INVALID_VALUE),
                string("sourceHouseholdId"), optionalString("appVersion"))
        }
        return BackupDocument(metadata, number("openingBalanceGrosze"),
            array("categories", BackupLimits.MAX_CATEGORIES).map { it.obj().category() },
            array("subcategories", BackupLimits.MAX_SUBCATEGORIES).map { it.obj().subcategory() },
            array("entries", BackupLimits.MAX_ENTRIES).map { it.obj().entry() })
    }
    private fun Map<String, Any?>.category(): BackupCategory {
        fields("id", "name", "authorId", "updatedById", "color", "archived", "defaultEntryType", "createdAt", "updatedAt")
        return BackupCategory(string("id"), string("name"), string("authorId"), string("updatedById"),
            optionalString("color"), boolean("archived"), TransferEntryType.valueOf(string("defaultEntryType")),
            time("createdAt"), time("updatedAt"))
    }
    private fun Map<String, Any?>.subcategory(): BackupSubcategory {
        fields("id", "categoryId", "name", "authorId", "updatedById", "archived", "createdAt", "updatedAt")
        return BackupSubcategory(string("id"), string("categoryId"), string("name"), string("authorId"),
            string("updatedById"), boolean("archived"), time("createdAt"), time("updatedAt"))
    }
    private fun Map<String, Any?>.entry(): BackupEntry {
        fields("id", "amountGrosze", "date", "categoryId", "authorId", "updatedById", "title", "subcategoryId",
            "tags", "createdAt", "updatedAt", "deleted", "deletedAt", "deletedById")
        return BackupEntry(string("id"), number("amountGrosze"), date("date"), string("categoryId"),
            string("authorId"), string("updatedById"), optionalString("title"), optionalString("subcategoryId"),
            array("tags", 10).map { it as? String ?: fail(TransferErrorCode.INVALID_VALUE) },
            time("createdAt"), time("updatedAt"), boolean("deleted"), time("deletedAt"), optionalString("deletedById"))
    }

    private fun BackupDocument.toWire(): Map<String, Any?> = linkedMapOf(
        "formatVersion" to formatVersion,
        "metadata" to linkedMapOf("exportedAt" to metadata.exportedAt.toString(),
            "sourceHouseholdId" to metadata.sourceHouseholdId, "appVersion" to metadata.appVersion),
        "openingBalanceGrosze" to openingBalanceGrosze,
        "categories" to categories.sortedBy { it.id }.map {
            linkedMapOf("id" to it.id, "name" to it.name, "color" to it.color, "archived" to it.archived,
                "defaultEntryType" to it.defaultEntryType.name, "authorId" to it.authorId,
                "updatedById" to it.updatedById, "createdAt" to it.createdAt?.toString(), "updatedAt" to it.updatedAt?.toString())
        },
        "subcategories" to subcategories.sortedWith(compareBy({ it.categoryId }, { it.id })).map {
            linkedMapOf("id" to it.id, "categoryId" to it.categoryId, "name" to it.name, "archived" to it.archived,
                "authorId" to it.authorId, "updatedById" to it.updatedById,
                "createdAt" to it.createdAt?.toString(), "updatedAt" to it.updatedAt?.toString())
        },
        "entries" to entries.sortedBy { it.id }.map {
            linkedMapOf("id" to it.id, "amountGrosze" to it.amountGrosze, "date" to it.date.toString(),
                "title" to it.title, "categoryId" to it.categoryId, "subcategoryId" to it.subcategoryId,
                "tags" to it.tags.sorted(), "authorId" to it.authorId, "updatedById" to it.updatedById,
                "createdAt" to it.createdAt?.toString(), "updatedAt" to it.updatedAt?.toString(),
                "deleted" to it.deleted, "deletedAt" to it.deletedAt?.toString(), "deletedById" to it.deletedById)
        },
    )
}
