package pl.bargor.thesaurus.ui.taxonomy

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.moveActiveCategory

/** Screen-owned drag preview; persistence happens only through the explicit drop callback. */
internal class TaxonomyDragState {
    var previewOrderIds by mutableStateOf<List<String>?>(null)
        private set
    var draggedId by mutableStateOf<String?>(null)
        private set
    var targetId by mutableStateOf<String?>(null)
        private set
    var dragDistance by mutableStateOf(0f)
        private set

    fun start(id: String, categories: List<CategoryWithSubcategories>) {
        draggedId = id
        targetId = id
        previewOrderIds = categories.map { it.category.id }
        dragDistance = 0f
    }

    fun drag(delta: Float, categories: List<CategoryWithSubcategories>, listState: LazyListState) {
        val nodesById = categories.associateBy { it.category.id }
        val orderedNodes = previewOrderIds?.mapNotNull(nodesById::get) ?: categories
        val activeIds = orderedNodes.filterNot { it.category.archived }.map { it.category.id }
        dragDistance += delta
        val visibleItems = listState.layoutInfo.visibleItemsInfo
        val draggedItem = visibleItems.firstOrNull { it.key == draggedId }
        val center = draggedItem?.let {
            it.offset + it.size / 2f + dragDistance
        }
        val targetItem = center?.let { draggedCenter ->
            visibleItems.filter { it.key in activeIds }
                .minByOrNull { abs(draggedCenter - (it.offset + it.size / 2f)) }
        }
        val nearestId = targetItem?.key as? String
        if (nearestId != null) {
            targetId = nearestId
            val dragged = draggedId
            if (dragged != null && nearestId != dragged) {
                val currentIds = previewOrderIds ?: categories.map { it.category.id }
                val previewNodes = currentIds.mapNotNull(nodesById::get)
                previewOrderIds = previewNodes
                    .map(CategoryWithSubcategories::category)
                    .moveActiveCategory(dragged, nearestId)
                    .map(Category::id)
                dragDistance += draggedItem.offset - targetItem.offset
            }
        }
    }

    fun end(commit: Boolean, categories: List<CategoryWithSubcategories>, onReorder: (List<String>) -> Unit) {
        val dragged = draggedId
        val preview = previewOrderIds
        val currentIds = categories.map { it.category.id }
        if (commit && dragged != null && preview != null && preview != currentIds) {
            onReorder(preview)
        }
        previewOrderIds = null
        draggedId = null
        targetId = null
        dragDistance = 0f
    }
}
