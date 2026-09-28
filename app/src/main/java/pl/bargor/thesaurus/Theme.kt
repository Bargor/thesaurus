package pl.bargor.thesaurus

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val ThesaurusLightColors = lightColorScheme()
private val ThesaurusDarkColors = darkColorScheme()

@Composable
fun ThesaurusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) ThesaurusDarkColors else ThesaurusLightColors,
        content = content,
    )
}
