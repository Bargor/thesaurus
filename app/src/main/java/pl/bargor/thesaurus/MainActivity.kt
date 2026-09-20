package pl.bargor.thesaurus

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var invitationLink: FamilyInvitationLink? by mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        invitationLink = FamilyInvitationLink.fromIntent(intent)
        enableEdgeToEdge()
        setContent { ThesaurusTheme { ThesaurusApp(invitationLink = invitationLink) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        invitationLink = FamilyInvitationLink.fromIntent(intent)
    }
}
