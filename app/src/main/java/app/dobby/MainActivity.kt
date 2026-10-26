package app.dobby

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import app.dobby.chat.ChatScreen
import app.dobby.ui.ConnectorsScreen
import app.dobby.ui.ModelsScreen
import app.dobby.ui.AutomateScreen
import app.dobby.ui.MemoryScreen
import app.dobby.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val tabs = listOf("Chat", "Tools", "Models", "Automate", "Memory", "Settings")
                Scaffold(bottomBar = {
                    NavigationBar {
                        tabs.forEachIndexed { i, t ->
                            NavigationBarItem(selected = tab == i, onClick = { tab = i }, label = { Text(t) }, icon = {})
                        }
                    }
                }) { pad ->
                    Surface(Modifier.padding(pad)) {
                        when (tab) {
                            0 -> ChatScreen()
                            1 -> ConnectorsScreen()
                            2 -> ModelsScreen()
                            3 -> AutomateScreen()
                            4 -> MemoryScreen()
                            else -> SettingsScreen()
                        }
                    }
                }
            }
        }
    }
}
