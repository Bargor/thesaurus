package pl.bargor.thesaurus.data.transfer

import java.time.Instant

object BackupContract {
    private val instantPattern = Regex("\\d{4}-.*Z")
    private val colors = setOf(
        "amber", "blue", "violet", "pink", "cyan", "slate", "teal", "green", "red", "gray",
        "orange", "yellow", "lime", "emerald", "sky", "indigo", "purple", "rose", "brown",
        "mint", "navy", "lavender", "coral", "peach", "gold", "olive", "forest", "ocean",
        "plum", "wine", "steel", "sand",
    )

    fun validate(document: BackupDocument) {
        if (document.formatVersion != 1) fail(TransferErrorCode.UNSUPPORTED_VERSION)
        limit(document.categories.size <= BackupLimits.MAX_CATEGORIES)
        limit(document.subcategories.size <= BackupLimits.MAX_SUBCATEGORIES)
        limit(document.entries.size <= BackupLimits.MAX_ENTRIES)
        id(document.metadata.sourceHouseholdId)
        instant(document.metadata.exportedAt)
        document.metadata.appVersion?.let { text(it, 128) }
        valid(document.categories.map { it.id }.toSet().size == document.categories.size)
        valid(document.entries.map { it.id }.toSet().size == document.entries.size)
        val categories = document.categories.map { it.id }.toSet()
        val subcategories = document.subcategories.map { it.categoryId to it.id }.toSet()
        valid(subcategories.size == document.subcategories.size)
        document.categories.forEach {
            id(it.id); text(it.name, 60)
            valid(it.color == null || it.color in colors)
            audit(it.authorId, it.updatedById, it.createdAt, it.updatedAt)
        }
        document.subcategories.forEach {
            id(it.id); id(it.categoryId); text(it.name, 60)
            valid(it.categoryId in categories)
            audit(it.authorId, it.updatedById, it.createdAt, it.updatedAt)
        }
        document.entries.forEach {
            id(it.id); id(it.categoryId)
            valid(it.amountGrosze != 0L)
            valid(it.date.year in 0..9999)
            it.title?.let { title -> text(title, 160, allowBlank = true) }
            valid(it.categoryId in categories)
            it.subcategoryId?.let { sub ->
                id(sub); valid(it.categoryId to sub in subcategories)
            }
            limit(it.tags.size <= 10)
            valid(it.tags.toSet().size == it.tags.size)
            it.tags.forEach { tag -> text(tag, 40) }
            audit(it.authorId, it.updatedById, it.createdAt, it.updatedAt)
            if (it.deleted) {
                valid(it.deletedById != null)
                it.deletedById?.let(::id)
                it.deletedAt?.let(::instant)
            } else {
                valid(it.deletedById == null && it.deletedAt == null)
            }
        }
    }

    private fun audit(author: String, updater: String, created: Instant?, updated: Instant?) {
        id(author); id(updater); created?.let(::instant); updated?.let(::instant)
    }

    private fun instant(value: Instant) = valid(value.toString().matches(instantPattern))
    private fun id(value: String) {
        text(value, 128)
        valid(value == value.trim() && '/' !in value)
    }
    private fun text(value: String, max: Int, allowBlank: Boolean = false) {
        valid(value.length <= max && (allowBlank || value.isNotBlank()))
        validUnicode(value)
    }
}

internal fun validUnicode(value: String) {
    var index = 0
    while (index < value.length) {
        val char = value[index++]
        if (char.isHighSurrogate()) {
            valid(index < value.length && value[index].isLowSurrogate())
            index++
        } else valid(!char.isLowSurrogate())
    }
}

internal fun valid(condition: Boolean) { if (!condition) fail(TransferErrorCode.INVALID_VALUE) }
internal fun limit(condition: Boolean) { if (!condition) fail(TransferErrorCode.LIMIT_EXCEEDED) }
internal fun fail(code: TransferErrorCode): Nothing = throw BackupContractException(code)
