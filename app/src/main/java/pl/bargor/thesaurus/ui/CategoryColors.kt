package pl.bargor.thesaurus.ui

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import pl.bargor.thesaurus.R
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.CategoryPalette
import pl.bargor.thesaurus.data.model.CategorySwatch

fun CategorySwatch.asColor(): Color = Color((0xFF000000L or hex).toInt())

fun Category.accentColor(): Color = CategoryPalette.forCategory(this).asColor()

fun categoryAccentColor(categoryId: String, colorToken: String?): Color =
    CategoryPalette.forCategory(categoryId, colorToken).asColor()

/** Tinted containers keep category text readable in both theme modes. */
fun Color.categoryContainer(surface: Color, selected: Boolean, dark: Boolean): Color =
    copy(alpha = if (selected) { if (dark) 0.42f else 0.25f } else { if (dark) 0.20f else 0.11f })
        .compositeOver(surface)

fun Color.contrastingContent(): Color = if (luminance() > 0.48f) Color.Black else Color.White

@StringRes
fun CategorySwatch.nameRes(): Int = when (token) {
    "amber" -> R.string.category_color_amber
    "blue" -> R.string.category_color_blue
    "violet" -> R.string.category_color_violet
    "pink" -> R.string.category_color_pink
    "cyan" -> R.string.category_color_cyan
    "slate" -> R.string.category_color_slate
    "teal" -> R.string.category_color_teal
    "green" -> R.string.category_color_green
    "red" -> R.string.category_color_red
    "gray" -> R.string.category_color_gray
    "orange" -> R.string.category_color_orange
    "yellow" -> R.string.category_color_yellow
    "lime" -> R.string.category_color_lime
    "emerald" -> R.string.category_color_emerald
    "sky" -> R.string.category_color_sky
    "indigo" -> R.string.category_color_indigo
    "purple" -> R.string.category_color_purple
    "rose" -> R.string.category_color_rose
    "brown" -> R.string.category_color_brown
    "mint" -> R.string.category_color_mint
    "navy" -> R.string.category_color_navy
    "lavender" -> R.string.category_color_lavender
    "coral" -> R.string.category_color_coral
    "peach" -> R.string.category_color_peach
    "gold" -> R.string.category_color_gold
    "olive" -> R.string.category_color_olive
    "forest" -> R.string.category_color_forest
    "ocean" -> R.string.category_color_ocean
    "plum" -> R.string.category_color_plum
    "wine" -> R.string.category_color_wine
    "steel" -> R.string.category_color_steel
    "sand" -> R.string.category_color_sand
    else -> error("Nieobsługiwany token koloru: $token")
}
