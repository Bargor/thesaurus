package pl.bargor.thesaurus

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import pl.bargor.thesaurus.data.connectivity.AndroidNetworkMonitor
import pl.bargor.thesaurus.data.connectivity.NetworkMonitor

@Composable
internal fun rememberNetworkMonitor(): NetworkMonitor {
    val context = LocalContext.current
    return remember(context) { AndroidNetworkMonitor(context) }
}

/** Consume a share request only after dispatching the chooser, as in the original family route. */
@Composable
internal fun FamilyShareEffect(shareUrl: String?, onShareHandled: () -> Unit) {
    val context = LocalContext.current
    val shareChooserTitle = stringResource(R.string.family_share_chooser)
    LaunchedEffect(shareUrl) {
        shareUrl?.let { url ->
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url),
                    shareChooserTitle,
                ),
            )
            onShareHandled()
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
