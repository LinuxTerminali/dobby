package app.dobby

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import app.dobby.chat.ChatScreen
import app.dobby.chat.ChatViewModel
import app.dobby.ui.AutomateScreen
import app.dobby.ui.ConnectorsScreen
import app.dobby.ui.DobbyTheme
import app.dobby.ui.MemoryScreen
import app.dobby.ui.ModelsScreen
import app.dobby.ui.SettingsScreen

private data class Tab(val title: String, val icon: ImageVector)

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DobbyTheme {
                val vm: ChatViewModel = viewModel()
                var tab by rememberSaveable { mutableIntStateOf(0) }
                var menu by remember { mutableStateOf(false) }
                val tabs = listOf(
                    Tab("Chat", Icons.Filled.ChatBubble), Tab("Tools", Icons.Filled.Extension),
                    Tab("Automate", Icons.Filled.Schedule), Tab("Memory", Icons.Filled.Memory))
                val titles = listOf("Dobby", "Tools", "Automate", "Memory", "Models", "Settings")
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Text(titles[tab], style = MaterialTheme.typography.titleLarge)
                                    if (tab == 0) Text(vm.status.value, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                            },
                            actions = {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Models") }, onClick = { menu = false; tab = 4 })
                                    DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; tab = 5 })
                                    DropdownMenuItem(text = { Text("New chat") }, onClick = { menu = false; vm.clear(); tab = 0 })
                                    DropdownMenuItem(text = { Text("Unload model") }, onClick = { menu = false; vm.unload() })
                                }
                            })
                    },
                    bottomBar = {
                        NavigationBar {
                            tabs.forEachIndexed { i, t ->
                                NavigationBarItem(selected = tab == i, onClick = { tab = i },
                                    icon = { Icon(t.icon, contentDescription = t.title) }, label = { Text(t.title) })
                            }
                        }
                    }) { pad ->
                    Surface(Modifier.padding(pad)) {
                        when (tab) {
                            0 -> ChatScreen(vm)
                            1 -> ConnectorsScreen()
                            2 -> AutomateScreen()
                            3 -> MemoryScreen()
                            4 -> ModelsScreen()
                            else -> SettingsScreen()
                        }
                    }
                }
            }
        }
    }
}
