package pl.bargor.thesaurus

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource

@Composable
internal fun DeveloperToolsContent(onSignOut: () -> Unit) {
    TextButton(modifier = Modifier.testTag("dev-sign-out"), onClick = onSignOut) {
        Text(stringResource(R.string.dev_sign_out))
    }
}
