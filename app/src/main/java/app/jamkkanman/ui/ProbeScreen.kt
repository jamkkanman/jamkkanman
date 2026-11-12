package app.jamkkanman.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jamkkanman.probe.ProbeRunner

@Composable
fun ProbeScreen(runner: ProbeRunner, permissionLines: List<String>, onTestRoute: () -> Unit, onBack: () -> Unit) {
    val items by runner.items.collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .padding(32.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("신호 점검", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(32.dp))
            Button(onClick = onTestRoute) { Text("내비 경로 변경 시험", fontSize = 22.sp) }
            Spacer(Modifier.width(16.dp))
            Button(onClick = onBack) { Text("돌아가기", fontSize = 22.sp) }
        }
        Spacer(Modifier.size(16.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            LazyColumn(Modifier.weight(2f)) {
                items(items, key = { it.key }) { item -> ProbeRow(item) }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(permissionLines) { line ->
                    Text(line, color = Color(0xFFB0B8C4), fontSize = 16.sp, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun ProbeRow(item: ProbeRunner.Item) {
    val color = when (item.status) {
        ProbeRunner.Status.OK -> Color(0xFF3DDC84)
        ProbeRunner.Status.FAIL -> Color(0xFFFF5A5A)
        ProbeRunner.Status.WAIT -> Color(0xFFFFB020)
        ProbeRunner.Status.INFO -> Color(0xFF6CA8FF)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(16.dp).background(color, CircleShape))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(item.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("${item.detail}  (${item.atMs} ms)", color = Color(0xFFB0B8C4), fontSize = 18.sp)
        }
    }
}
