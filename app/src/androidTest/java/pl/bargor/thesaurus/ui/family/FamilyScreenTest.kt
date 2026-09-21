package pl.bargor.thesaurus.ui.family

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import pl.bargor.thesaurus.ThesaurusTheme
import pl.bargor.thesaurus.data.model.Member
import pl.bargor.thesaurus.data.model.MemberRole

class FamilyScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun ownerCanCreateInvitationAndMustConfirmRemoval() {
        var invitedEmail: String? = null
        val removed = mutableListOf<String>()
        composeRule.setContent {
            ThesaurusTheme {
                FamilyScreen(
                    state = FamilyUiState(
                        loading = false,
                        isOwner = true,
                        members = listOf(
                            Member("owner", "owner@example.test", null, MemberRole.OWNER),
                            Member("member", "member@example.test", null, MemberRole.MEMBER),
                        ),
                    ),
                    onCreateInvitation = { invitedEmail = it },
                    onRevokeInvitation = {},
                    onRemoveMember = removed::add,
                    onClearError = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("family-invite-email").performTextInput("guest@example.test")
        composeRule.onNodeWithTag("family-create-invite").performClick()
        assertEquals("guest@example.test", invitedEmail)

        composeRule.onNodeWithTag("family-list").performScrollToNode(hasTestTag("family-remove-member"))
        composeRule.onNodeWithTag("family-remove-member").performClick()
        composeRule.onNodeWithText("Usunąć członka?").assertIsDisplayed()
        assertEquals(emptyList<String>(), removed)
        composeRule.onNodeWithText("Usuń").performClick()
        assertEquals(listOf("member"), removed)
    }

    @Test
    fun regularMemberCannotSeeOwnerOnlyInviteOrRemovalActions() {
        composeRule.setContent {
            ThesaurusTheme {
                FamilyScreen(
                    state = FamilyUiState(
                        loading = false,
                        isOwner = false,
                        members = listOf(Member("member", "member@example.test", null, MemberRole.MEMBER)),
                    ),
                    onCreateInvitation = {}, onRevokeInvitation = {}, onRemoveMember = {}, onClearError = {}, onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("family-invite-form").assertDoesNotExist()
        composeRule.onNodeWithTag("family-remove-member").assertDoesNotExist()
    }
}
