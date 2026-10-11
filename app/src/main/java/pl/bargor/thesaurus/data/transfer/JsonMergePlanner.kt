package pl.bargor.thesaurus.data.transfer

import java.security.MessageDigest
import java.util.Locale
import pl.bargor.thesaurus.data.model.*

private fun jsonName(value: String) = value.trim().lowercase(Locale.ROOT)
private fun contentKey(vararg values: String): String = values.joinToString("") { "${it.length}:$it" }
private fun jsonId(key: String): String = "json_" + MessageDigest.getInstance("SHA-256")
    .digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

fun jsonCategoryContentKey(category: Category): String = contentKey(jsonName(category.name),
    CategoryPalette.forCategory(category).token, category.archived.toString(), category.defaultEntryType.name)
fun jsonSubcategoryContentKey(subcategory: Subcategory): String = contentKey(subcategory.categoryId,
    jsonName(subcategory.name), subcategory.archived.toString())

/** Tombstones never resurrect; an imported tombstone cannot delete an existing active entry. */
fun jsonEntryMergeOutcome(existing: LedgerEntry, incoming: LedgerEntry): JsonMergeWriteOutcome =
    if (csvImportFingerprint(existing) != csvImportFingerprint(incoming) || (!existing.deleted && incoming.deleted))
        JsonMergeWriteOutcome.CONFLICT else JsonMergeWriteOutcome.SKIPPED

internal object JsonMergePlanner {
    fun plan(snapshot: JsonBackupSnapshot, document: BackupDocument): JsonMergeSession {
        val items = mutableListOf<JsonMergeItem>()
        val operations = linkedMapOf<Int, JsonMergeOperation>()
        fun add(kind: JsonMergeItemKind, source: String?, target: String?, status: JsonMergeStatus,
            code: String? = null, operation: JsonMergeOperation? = null) {
            if (operation != null) operations[items.size] = operation
            items += JsonMergeItem(kind, source, target, status, code)
        }
        val owner = snapshot.identity.uid
        fun author(id: String) = id.takeIf { it in snapshot.memberIds } ?: owner
        val household = snapshot.household
        when {
            household.openingBalanceGrosze == document.openingBalanceGrosze ->
                add(JsonMergeItemKind.OPENING_BALANCE, null, null, JsonMergeStatus.SKIPPED)
            household.openingBalanceGrosze == 0L && snapshot.entries.isEmpty() && household.ledgerRevision == 0L ->
                add(JsonMergeItemKind.OPENING_BALANCE, null, null, JsonMergeStatus.NEW,
                    operation = JsonMergeOperation.OpeningBalance(document.openingBalanceGrosze))
            else -> add(JsonMergeItemKind.OPENING_BALANCE, null, null, JsonMergeStatus.CONFLICT, "JSON_OPENING_BALANCE_CONFLICT")
        }
        val categoryIds = mutableMapOf<String, String>()
        val categoriesById = snapshot.categories.associateBy { it.id }.toMutableMap()
        val categoriesByContent = snapshot.categories.groupBy(::jsonCategoryContentKey).mapValues { it.value.toMutableList() }.toMutableMap()
        document.categories.sortedBy { it.id }.forEach { source ->
            var candidate = Category("temporary", household.id, source.name.trim(),
                CategoryPalette.forCategory(source.id, source.color).token, source.archived,
                EntryType.valueOf(source.defaultEntryType.name), author(source.authorId), owner)
            val key = jsonCategoryContentKey(candidate)
            val matches = categoriesByContent[key].orEmpty()
            if (matches.size > 1) {
                add(JsonMergeItemKind.CATEGORY, source.id, null, JsonMergeStatus.CONFLICT, "JSON_AMBIGUOUS_CATEGORY")
            } else if (matches.size == 1) {
                categoryIds[source.id] = matches.single().id
                add(JsonMergeItemKind.CATEGORY, source.id, matches.single().id, JsonMergeStatus.SKIPPED)
            } else {
                candidate = candidate.copy(id = jsonId(contentKey("category", key)))
                if (categoriesById.containsKey(candidate.id)) {
                    add(JsonMergeItemKind.CATEGORY, source.id, candidate.id, JsonMergeStatus.CONFLICT, "JSON_ID_CONFLICT")
                } else {
                    categoryIds[source.id] = candidate.id
                    categoriesById[candidate.id] = candidate
                    categoriesByContent.getOrPut(key) { mutableListOf() }.add(candidate)
                    add(JsonMergeItemKind.CATEGORY, source.id, candidate.id, JsonMergeStatus.NEW,
                        operation = JsonMergeOperation.NewCategory(candidate))
                }
            }
        }
        val subIds = mutableMapOf<Pair<String, String>, String>()
        val subsById = snapshot.subcategories.associateBy { it.categoryId to it.id }.toMutableMap()
        val subsByContent = snapshot.subcategories.groupBy(::jsonSubcategoryContentKey).mapValues { it.value.toMutableList() }.toMutableMap()
        document.subcategories.sortedWith(compareBy({ it.categoryId }, { it.id })).forEach { source ->
            val parent = categoryIds[source.categoryId]
            if (parent == null) {
                add(JsonMergeItemKind.SUBCATEGORY, source.id, null, JsonMergeStatus.CONFLICT, "JSON_PARENT_CONFLICT")
                return@forEach
            }
            var candidate = Subcategory("temporary", household.id, parent, source.name.trim(), source.archived, author(source.authorId), owner)
            val key = jsonSubcategoryContentKey(candidate)
            val matches = subsByContent[key].orEmpty()
            if (matches.size > 1) {
                add(JsonMergeItemKind.SUBCATEGORY, source.id, null, JsonMergeStatus.CONFLICT, "JSON_AMBIGUOUS_SUBCATEGORY")
            } else if (matches.size == 1) {
                subIds[source.categoryId to source.id] = matches.single().id
                add(JsonMergeItemKind.SUBCATEGORY, source.id, matches.single().id, JsonMergeStatus.SKIPPED)
            } else {
                candidate = candidate.copy(id = jsonId(contentKey("subcategory", key)))
                if (subsById.containsKey(parent to candidate.id)) {
                    add(JsonMergeItemKind.SUBCATEGORY, source.id, candidate.id, JsonMergeStatus.CONFLICT, "JSON_ID_CONFLICT")
                } else {
                    subIds[source.categoryId to source.id] = candidate.id
                    subsById[parent to candidate.id] = candidate
                    subsByContent.getOrPut(key) { mutableListOf() }.add(candidate)
                    add(JsonMergeItemKind.SUBCATEGORY, source.id, candidate.id, JsonMergeStatus.NEW,
                        operation = JsonMergeOperation.NewSubcategory(candidate))
                }
            }
        }
        val entriesById = snapshot.entries.associateBy { it.id }
        val consumed = mutableSetOf<String>()
        val byStatus = mutableMapOf<Pair<String, Boolean>, java.util.ArrayDeque<LedgerEntry>>()
        val byAuthor = mutableMapOf<Triple<String, Boolean, String>, java.util.ArrayDeque<LedgerEntry>>()
        snapshot.entries.sortedBy { it.id }.forEach { existing ->
            val fingerprint = csvImportFingerprint(existing)
            byStatus.getOrPut(fingerprint to existing.deleted) { java.util.ArrayDeque() }.add(existing)
            byAuthor.getOrPut(Triple(fingerprint, existing.deleted, existing.authorId)) { java.util.ArrayDeque() }.add(existing)
        }
        fun take(queue: java.util.ArrayDeque<LedgerEntry>?): LedgerEntry? {
            while (queue != null && queue.isNotEmpty()) {
                val next = queue.removeFirst()
                if (consumed.add(next.id)) return next
            }
            return null
        }
        val candidates = mutableListOf<Pair<BackupEntry, LedgerEntry>>()
        document.entries.sortedBy { it.id }.forEach { source ->
            val parent = categoryIds[source.categoryId]
            val sub = source.subcategoryId?.let { subIds[source.categoryId to it] }
            if (parent == null || (source.subcategoryId != null && sub == null)) {
                add(JsonMergeItemKind.ENTRY, source.id, null, JsonMergeStatus.CONFLICT, "JSON_TAXONOMY_CONFLICT")
                return@forEach
            }
            val candidate = LedgerEntry(jsonId(contentKey("entry", document.metadata.sourceHouseholdId, source.id)), household.id, source.amountGrosze, source.date,
                source.title?.trim()?.takeIf(String::isNotEmpty), parent, sub, normalizeTags(source.tags),
                author(source.authorId), owner, deleted = source.deleted, deletedById = owner.takeIf { source.deleted })
            candidates += source to candidate
        }
        fun matched(source: BackupEntry, candidate: LedgerEntry, existing: LedgerEntry) {
            consumed.add(existing.id)
            val outcome = jsonEntryMergeOutcome(existing, candidate)
            add(JsonMergeItemKind.ENTRY, source.id, existing.id,
                if (outcome == JsonMergeWriteOutcome.CONFLICT) JsonMergeStatus.CONFLICT else JsonMergeStatus.SKIPPED,
                if (outcome == JsonMergeWriteOutcome.CONFLICT) "JSON_ENTRY_CONFLICT" else null)
        }
        val unmatched = mutableListOf<Pair<BackupEntry, LedgerEntry>>()
        // Reserve every source lineage before consuming content pools. Otherwise an
        // earlier equal-content row could consume a later row's retry identity.
        candidates.forEach { (source, candidate) ->
            val exact = if (document.metadata.sourceHouseholdId == household.id) entriesById[source.id] else null
            val lineage = entriesById[candidate.id]
            val existing = exact ?: lineage
            if (existing != null) {
                if (existing.id in consumed) add(JsonMergeItemKind.ENTRY, source.id, existing.id,
                    JsonMergeStatus.CONFLICT, "JSON_ID_CONFLICT")
                else matched(source, candidate, existing)
            } else unmatched += source to candidate
        }
        // Match old history one-to-one, preferring both deletion state and author.
        // Newly planned records never enter these pools: distinct file IDs preserve
        // legitimate equal-valued entries and their financial multiplicity.
        val remaining = mutableListOf<Pair<BackupEntry, LedgerEntry>>()
        unmatched.forEach { (source, candidate) ->
            val fingerprint = csvImportFingerprint(candidate)
            val existing = take(byAuthor[Triple(fingerprint, candidate.deleted, candidate.authorId)])
                ?: take(byStatus[fingerprint to candidate.deleted])
            if (existing != null) matched(source, candidate, existing)
            else remaining += source to candidate
        }
        // Reserve same-state content matches for the whole source before considering
        // deletion-state mismatches; a tombstone cannot steal an active row's match.
        remaining.forEach { (source, candidate) ->
            val fingerprint = csvImportFingerprint(candidate)
            val existing = take(byAuthor[Triple(fingerprint, !candidate.deleted, candidate.authorId)])
                ?: take(byStatus[fingerprint to !candidate.deleted])
            if (existing != null) matched(source, candidate, existing)
            else add(JsonMergeItemKind.ENTRY, source.id, candidate.id, JsonMergeStatus.NEW,
                operation = JsonMergeOperation.NewEntry(candidate))
        }
        // Reject an oversized merged destination as one preparation failure, before
        // opening balance or taxonomy can be committed. Count unique planned writes.
        limit(snapshot.categories.size + operations.values.count { it is JsonMergeOperation.NewCategory } <= BackupLimits.MAX_CATEGORIES)
        limit(snapshot.subcategories.size + operations.values.count { it is JsonMergeOperation.NewSubcategory } <= BackupLimits.MAX_SUBCATEGORIES)
        limit(snapshot.entries.size + operations.values.count { it is JsonMergeOperation.NewEntry } <= BackupLimits.MAX_ENTRIES)
        return JsonMergeSession(snapshot, operations, items)
    }
}
