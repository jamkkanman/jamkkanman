package app.jamkkanman.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.jamkkanman.core.DataSet
import app.jamkkanman.core.Scenario
import app.jamkkanman.core.ScenarioRunner
import app.jamkkanman.core.metrics.Suites
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MetricsResult(
    val scenarioRows: List<List<String>>,
    val suiteRows: List<List<String>>,
    val timingRows: List<List<String>>,
    val elapsedMs: Long,
)

/** Runs the same replay suites as `./gradlew :core:metrics`, but on this device. */
fun runDeviceMetrics(data: DataSet, scenarios: List<Scenario>): MetricsResult {
    val t0 = System.currentTimeMillis()
    val scenarioRows = scenarios.map { sc ->
        val r = ScenarioRunner.run(sc, data)
        listOf(sc.title, "${sc.expectAlerts ?: "-"}", "${r.alerts.size}", if (r.routed) "예" else "아니오", if (r.passed) "통과" else "실패")
    }
    val waits = Suites.run("신호 대기 100", Suites.signalWaits(data), data)
    val stops = Suites.run("금지 구역 정차 100", Suites.intentStops(data), data)
    val suiteRows = listOf(waits, stops).map {
        listOf(it.name, "${it.passed}/${it.scenarios}", "${it.stops}", "${it.alerts}", "%.2f".format(it.alertsPerStop), "${it.maxAlertsPerStop}")
    }
    val (m1, p1, x1) = Suites.judgeTiming(data.facilities, 5_000)
    val (m2, p2, x2) = Suites.judgeTiming(Suites.cityScale(60_000), 5_000)
    val timingRows = listOf(
        listOf("내장 시연 데이터 ${data.facilities.size}곳", "%.1f us".format(m1), "%.1f us".format(p1), "%.1f us".format(x1)),
        listOf("서울 규모 가상 시설 60000곳", "%.1f us".format(m2), "%.1f us".format(p2), "%.1f us".format(x2)),
    )
    return MetricsResult(scenarioRows, suiteRows, timingRows, System.currentTimeMillis() - t0)
}

@Composable
fun MetricsScreen(data: DataSet, scenarios: List<Scenario>, onBack: () -> Unit, onResult: (MetricsResult) -> Unit) {
    var result by remember { mutableStateOf<MetricsResult?>(null) }
    LaunchedEffect(Unit) {
        val r = withContext(Dispatchers.Default) { runDeviceMetrics(data, scenarios) }
        result = r
        onResult(r)
    }
    Column(Modifier.fillMaxSize().background(Palette.bg).padding(32.dp)) {
        Row {
            Text("재생기 지표 (이 기기에서 실행)", color = Palette.text, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Chip("돌아가기", false, onBack)
        }
        Spacer(Modifier.height(20.dp))
        val r = result
        if (r == null) {
            Text("시나리오 ${scenarios.size + 200}개를 가상 시계로 재생하는 중...", color = Palette.muted, fontSize = 28.sp)
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { Table("시연 시나리오", listOf("시나리오", "예상 안내", "실제 안내", "주차장 안내", "결과"), r.scenarioRows) }
            item { Table("생성 시나리오", listOf("묶음", "기대대로", "정차", "안내", "정차당 평균", "정차당 최대"), r.suiteRows) }
            item { Table("판정 시간", listOf("데이터", "평균", "p99", "최대"), r.timingRows) }
            item { Text("전체 ${r.elapsedMs} ms, 실행 중 네트워크와 LLM 호출 없음", color = Palette.muted, fontSize = 22.sp) }
        }
    }
}

@Composable
private fun Table(title: String, header: List<String>, rows: List<List<String>>) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Palette.card).padding(24.dp)) {
        Text(title, color = Palette.amber, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        TableRow(header, Palette.muted, FontWeight.SemiBold)
        rows.forEach { TableRow(it, Palette.text, FontWeight.Normal) }
    }
}

@Composable
private fun TableRow(cells: List<String>, color: androidx.compose.ui.graphics.Color, weight: FontWeight) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        cells.forEachIndexed { i, c ->
            Text(c, color = if (c == "실패") Palette.red else if (c == "통과") Palette.green else color, fontSize = 24.sp, fontWeight = weight,
                modifier = if (i == 0) Modifier.weight(2.2f) else Modifier.weight(1f))
            if (i < cells.size - 1) Spacer(Modifier.width(8.dp))
        }
    }
}
