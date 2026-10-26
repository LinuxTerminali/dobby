package app.dobby.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AutomateScreen() {
    var which by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            SegmentedButton(selected = which == 0, onClick = { which = 0 }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Reminders") }
            SegmentedButton(selected = which == 1, onClick = { which = 1 }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Places") }
        }
        if (which == 0) RemindersScreen() else PlacesScreen()
    }
}
