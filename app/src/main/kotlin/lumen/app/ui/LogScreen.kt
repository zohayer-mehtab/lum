package lumen.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lumen.app.MainViewModel

/** Last 200 CamGuard events: every probe, open, skip and refusal. */
@Composable
fun LogScreen(vm: MainViewModel, onBack: () -> Unit) {
    val events by vm.log.events.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val state = rememberLazyListState()
    LaunchedEffect(events.size) { if (events.isNotEmpty()) state.scrollToItem(events.size - 1) }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Back", onClick = onBack)
            Btn("Copy") { Share.copy(ctx, vm.log.asText()) }
            Btn("Share") { Share.text(ctx, vm.log.asText(), "CamGuard log") }
        }
        Text("CamGuard · ${events.size}/200 events", color = Dim, fontSize = 12.sp)
        LazyColumn(Modifier.weight(1f), state = state) {
            itemsIndexed(events) { _, line ->
                val c = when {
                    "REFUSE" in line || "QUARANTINE" in line || "FAIL" in line || "FAILURE" in line -> Danger
                    " ok " in line || "OPENED" in line -> Good
                    else -> Color.White
                }
                Text(line, color = c, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
