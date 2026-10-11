package pl.bargor.thesaurus.ui.data

enum class DataFormat { CSV, JSON }
enum class DataDirection { EXPORT, IMPORT }
data class DataPickerRequest(val id: String, val format: DataFormat, val direction: DataDirection,
    val suggestedFileName: String? = null, val launched: Boolean = false)
data class DataUiState(
    val busy: Boolean = false,
    val warning: DataFormat? = null,
    val pickerRequest: DataPickerRequest? = null,
    val leaf: DataFormat? = null,
    val errorCode: String? = null,
    val completedExport: DataFormat? = null,
    val importEnabled: Boolean = false,
    val importBlockCode: String? = "DATA_CHECKING",
    val canExport: Boolean = false,
)
