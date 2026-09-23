package pl.bargor.thesaurus

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun SignInContent(
    context: Context,
    onSignIn: (Activity) -> Unit,
    onEmailSignIn: (String, String) -> Unit,
) {
    var email by remember { mutableStateOf("owner@example.test") }
    var password by remember { mutableStateOf("dev-password-123") }
    Text(stringResource(R.string.dev_login_description))
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        modifier = Modifier.testTag("dev-email"),
        value = email,
        onValueChange = { email = it },
        singleLine = true,
        label = { Text(stringResource(R.string.dev_email)) },
    )
    OutlinedTextField(
        modifier = Modifier.testTag("dev-password"),
        value = password,
        onValueChange = { password = it },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        label = { Text(stringResource(R.string.dev_password)) },
    )
    Spacer(Modifier.height(12.dp))
    Button(
        modifier = Modifier.testTag("dev-sign-in"),
        enabled = email.isNotBlank() && password.isNotBlank(),
        onClick = { onEmailSignIn(email, password) },
    ) { Text(stringResource(R.string.dev_sign_in)) }
}
