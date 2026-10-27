package app.dobby.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AutomateScreen() {
    var which by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            SegmentedButton(selected = which == 0, onClick = { which = 0 }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Reminders") }
            SegmentedButton(selected = which == 1, onClick = { which = 1 }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Places") }
        }
        if (which == 0) RemindersScreen() else PlacesScreen()
    }
}
