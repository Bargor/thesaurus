package pl.bargor.thesaurus

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val ThesaurusColors = lightColorScheme()

@Composable
fun ThesaurusTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ThesaurusColors,
        content = content,
    )
}
