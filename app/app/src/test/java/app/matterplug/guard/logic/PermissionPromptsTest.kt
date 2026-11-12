package app.matterplug.guard.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPromptsTest {
    private val both = listOf(PermissionPrompt.Notifications, PermissionPrompt.Exemption)

    @Test fun `first enable on Android 14 asks for both, notifications first`() {
        assertEquals(both, PermissionPrompts.due(34, false, false, false, false))
    }

    @Test fun `Android 12 has no notification prompt`() {
        assertEquals(listOf(PermissionPrompt.Exemption), PermissionPrompts.due(32, false, false, false, false))
        assertEquals(listOf(PermissionPrompt.Exemption), PermissionPrompts.due(28, false, false, false, false))
    }

    @Test fun `Android 13 is the first with the notification prompt`() {
        assertEquals(both, PermissionPrompts.due(33, false, false, false, false))
    }

    @Test fun `granted permissions are not asked`() {
        assertEquals(emptyList<PermissionPrompt>(), PermissionPrompts.due(34, true, false, true, false))
        assertEquals(listOf(PermissionPrompt.Exemption), PermissionPrompts.due(34, true, false, false, false))
        assertEquals(listOf(PermissionPrompt.Notifications), PermissionPrompts.due(34, false, false, true, false))
    }

    @Test fun `each prompt is asked once even when it was refused`() {
        assertEquals(listOf(PermissionPrompt.Exemption), PermissionPrompts.due(34, false, true, false, false))
        assertEquals(listOf(PermissionPrompt.Notifications), PermissionPrompts.due(34, false, false, false, true))
        assertEquals(emptyList<PermissionPrompt>(), PermissionPrompts.due(34, false, true, false, true))
    }

    @Test fun `dialog only for a plug that was read as off`() {
        assertTrue(PermissionPrompts.needsTurnOnDialog(false))
        assertFalse(PermissionPrompts.needsTurnOnDialog(true))
        assertFalse(PermissionPrompts.needsTurnOnDialog(null))
    }
}
