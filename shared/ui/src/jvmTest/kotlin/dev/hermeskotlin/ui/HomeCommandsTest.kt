package dev.hermeskotlin.ui

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.hermeskotlin.ui.components.PaletteItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class)
class HomeCommandsTest {
    @Test
    fun desktopPaletteOpensInboxOnlyWithPushAvailable() = runComposeUiTest {
        var pushAvailable by mutableStateOf(false)
        var items = emptyList<PaletteItem>()
        var shortcut: Shortcut? = null
        val commands = HomeCommands(
            newChat = {},
            settings = {},
            gateways = {},
            openChat = {},
            openBot = {},
            chatMenu = null,
            usage = null,
            processes = null,
            checkpoints = null,
            theme = {},
            fire = { shortcut = it },
        )
        setContent {
            val entries = paletteItems(emptyList(), emptyList(), commands, pushAvailable = pushAvailable)
            SideEffect { items = entries }
        }
        runOnIdle {
            assertFalse(items.any { it.label == "Inbox" })
            pushAvailable = true
        }
        runOnIdle {
            items.single { it.label == "Inbox" }.run()
            assertEquals(Shortcut.Go(SidebarPage.Inbox), shortcut)
        }
    }
}
