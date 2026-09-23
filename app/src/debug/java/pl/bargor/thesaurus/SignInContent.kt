package pl.bargor.thesaurus

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.Composable

@Composable
internal fun SignInContent(
    context: Context,
    onSignIn: (Activity) -> Unit,
    onEmailSignIn: (String, String) -> Unit,
) = GoogleSignInContent(context, onSignIn)
