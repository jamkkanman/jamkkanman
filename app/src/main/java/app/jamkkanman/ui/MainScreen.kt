package app.jamkkanman.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jamkkanman.EngineHost
import app.jamkkanman.Mode
import app.jamkkanman.UiLog
import app.jamkkanman.UiState
import app.jamkkanman.core.EngineSnapshot
import app.jamkkanman.core.Gear
import app.jamkkanman.core.Phase
import app.jamkkanman.core.Verdict
import app.jamkkanman.core.ZoneType
import java.time.LocalDateTime

object Palette {
    val bg = Color(0xFF0E1116)
    val card = Color(0xFF171B22)
    val card2 = Color(0xFF1F242D)
    val line = Color(0xFF2A303A)
    val text = Color(0xFFF2F4F7)
    val muted = Color(0xFF98A2B3)
    val amber = Color(0xFFFFB020)
    val red = Color(0xFFFF5A4F)
    val green = Color(0xFF3DDC84)
    val blue = Color(0xFF6CA8FF)
}

data class ScenarioButton(val asset: String, val label: String)

@Composable
fun MainScreen(
    host: EngineHost,
    scenarios: List<ScenarioButton>,
    onOpenMetrics: () -> Unit,
    onOpenProbe: () -> Unit,
) {
    val state by host.state.collectAsStateWithLifecycle()
    val snap = state.snapshot
    Column(Modifier.fillMaxSize().background(Palette.bg).padding(24.dp)) {
        Header(state)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1.25f).fillMaxHeight()) {
                if (snap == null || snap.phase == Phase.DRIVING || snap.phase == Phase.UNKNOWN) {
                    DrivingCard(snap, state)
                } else {
                    StoppedCard(snap, state, onReply = { text -> host.reply(text, "button") })
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SignalPanel(snap, state)
                LogPanel(state.logs, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("실차 신호", state.mode == Mode.LIVE) { host.backToLive() }
            scenarios.forEach { s -> Chip(s.label, state.mode == Mode.REPLAY && state.scenarioTitle == s.label) { host.startReplay(s.asset) } }
            Spacer(Modifier.weight(1f))
            Chip("지표", false, onOpenMetrics)
            Chip("신호 점검", false, onOpenProbe)
        }
    }
}

@Composable
private fun Header(state: UiState) {
    val phase = state.snapshot?.phase ?: Phase.UNKNOWN
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("잠깐만", color = Palette.text, fontSize = 44.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(20.dp))
        Pill(phase.label, phaseColor(phase), 26.sp)
        Spacer(Modifier.width(12.dp))
        val modeText = if (state.mode == Mode.REPLAY) "재생: ${state.scenarioTitle ?: ""}" else "실차 신호"
        Pill(modeText, if (state.mode == Mode.REPLAY) Palette.blue else Palette.muted, 22.sp)
        state.replayProgress?.let {
            Spacer(Modifier.width(12.dp))
            Box(Modifier.width(220.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Palette.card2)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(it).background(Palette.blue))
            }
        }
        Spacer(Modifier.weight(1f))
        Text("실행 중 LLM 호출 0회, 위치는 차 밖으로 보내지 않음", color = Palette.muted, fontSize = 20.sp)
    }
}

fun phaseColor(p: Phase) = when (p) {
    Phase.DRIVING -> Palette.green
    Phase.STOPPED -> Palette.blue
    Phase.INTENT -> Palette.amber
    Phase.ALIGHTING -> Palette.red
    Phase.UNKNOWN -> Palette.muted
}

@Composable
private fun DrivingCard(snap: EngineSnapshot?, state: UiState) {
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(Palette.card).padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val kmh = snap?.speedKmh
        Text(if (kmh != null) "%.0f".format(kmh) else "--", color = Palette.text, fontSize = 140.sp, fontWeight = FontWeight.Bold)
        Text("km/h", color = Palette.muted, fontSize = 32.sp)
        Spacer(Modifier.height(36.dp))
        Text("주행 중에는 화면 대신 음성으로만 알려 드려요", color = Palette.text, fontSize = 32.sp)
        Spacer(Modifier.height(12.dp))
        Text("신호 대기처럼 세우려는 신호가 없으면 말하지 않습니다", color = Palette.muted, fontSize = 24.sp)
        state.speaking?.let {
            Spacer(Modifier.height(28.dp))
            SpeechBubble(it, true)
        }
    }
}

@Composable
private fun StoppedCard(snap: EngineSnapshot, state: UiState, onReply: (String) -> Unit) {
    val hit = snap.judgment?.top
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(Palette.card).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("정차 중 확인 화면", color = Palette.muted, fontSize = 24.sp)
        if (hit != null) {
            val color = if (hit.verdict == Verdict.INSIDE) Palette.red else Palette.amber
            Row(verticalAlignment = Alignment.Bottom) {
                Text(hit.facility.type.label, color = color, fontSize = 64.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(20.dp))
                Text("%.1fm".format(hit.edgeDistanceM), color = Palette.text, fontSize = 64.sp, fontWeight = FontWeight.Bold)
            }
            val edge = if (hit.facility.type == ZoneType.CROSSWALK) "가장자리에서 " else ""
            val legal = if (hit.facility.type == ZoneType.CHILD_ZONE) "구역 안" else
                "%s법정 %.0fm %s".format(edge, hit.facility.type.legalM, if (hit.verdict == Verdict.INSIDE) "안" else "가까이일 수 있음 (오차 여유 %.0fm)".format(hit.marginM))
            Text(legal, color = color, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "승용차 과태료 ${hit.facility.type.fineText}" + if (hit.facility.name.isNotEmpty()) ", ${hit.facility.name}" else "",
                color = Palette.muted, fontSize = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        } else {
            val near = snap.judgment?.nearest
            Text("정차 금지 구역이 아닙니다", color = Palette.green, fontSize = 52.sp, fontWeight = FontWeight.Bold)
            Text(
                if (near != null) "가장 가까운 ${near.first.type.label} %.1fm".format(near.second) else "주변 시설 정보 없음",
                color = Palette.muted, fontSize = 28.sp,
            )
        }
        (state.speaking ?: snap.lastSpoken)?.let { SpeechBubble(it, state.speaking != null) }
        if (snap.awaitingReply) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BigButton("응", Palette.amber, Modifier.weight(1f)) { onReply("응") }
                BigButton("금방 가", Palette.card2, Modifier.weight(1f)) { onReply("금방 가") }
                BigButton("괜찮아", Palette.card2, Modifier.weight(1f)) { onReply("괜찮아") }
            }
        }
        if (snap.parking.isNotEmpty()) {
            Text("근처 공영주차장", color = Palette.muted, fontSize = 24.sp)
            val today = LocalDateTime.now()
            snap.parking.forEach { o ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.card2).padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(o.lot.name, color = Palette.text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("%.0fm".format(o.distanceM), color = Palette.amber, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(20.dp))
                    val hours = o.lot.hours.rangeFor(today.dayOfWeek)?.toString() ?: "운영시간 정보 없음"
                    Text(hours, color = Palette.muted, fontSize = 22.sp)
                }
            }
        }
    }
}

@Composable
private fun SpeechBubble(text: String, live: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if (live) Color(0xFF2B2416) else Palette.card2)
            .border(2.dp, if (live) Palette.amber else Palette.line, RoundedCornerShape(20.dp)).padding(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(if (live) Palette.amber else Palette.muted))
        Spacer(Modifier.width(18.dp))
        Text(text, color = Palette.text, fontSize = 30.sp, lineHeight = 40.sp)
    }
}

@Composable
private fun SignalPanel(snap: EngineSnapshot?, state: UiState) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Palette.card).padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("차량 신호", color = Palette.muted, fontSize = 22.sp)
        val src = state.sources.associateBy { it.name }
        val replay = state.mode == Mode.REPLAY
        fun srcText(key: String, fallback: String) = if (replay) "재생기" else src[key]?.let { if (it.ok) it.api else it.state } ?: fallback
        SignalRow("속도", snap?.speedKmh?.let { "%.0f km/h".format(it) } ?: "-", srcText("속도", "AAOS"), src["속도"]?.ok != false || replay)
        SignalRow("기어", snap?.gear?.takeIf { it != Gear.UNKNOWN }?.label ?: "-", srcText("기어", "AAOS"), src["기어"]?.ok != false || replay)
        SignalRow("비상등", snap?.hazard?.let { if (it) "켜짐" else "꺼짐" } ?: "읽을 수 없음", srcText("비상등", "AAOS"), snap?.hazard != null)
        SignalRow("운전석 문", if (snap?.doorOpen == true) "열림" else "닫힘", srcText("운전석 문", "Vehicle SDK"), src["운전석 문"]?.ok != false || replay)
        SignalRow("운전석 벨트", snap?.belt?.let { if (it) "착용" else "해제" } ?: "-", srcText("운전석 벨트", "Vehicle SDK"), src["운전석 벨트"]?.ok != false || replay)
        SignalRow("위치", snap?.location?.let { "%.5f, %.5f".format(it.lat, it.lon) } ?: "-", srcText("위치", "FusedLocation"), src["위치"]?.ok != false || replay)
        val reactions = state.reactions
        val reactionText = if (reactions.isEmpty()) "-" else "최근 ${reactions.last()} ms, 평균 ${reactions.average().toInt()} ms (${reactions.size}회)"
        SignalRow("반응 시간", reactionText, "신호에서 첫 음성까지", true)
        SignalRow("판정 시간", snap?.judgment?.let { "%.3f ms".format(it.elapsedNanos / 1e6) } ?: "-", "격자 조회와 거리 계산", true)
        if (state.voiceEvent.isNotEmpty()) Text(state.voiceEvent, color = Palette.muted, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SignalRow(name: String, value: String, source: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(if (ok) Palette.green else Palette.red))
        Spacer(Modifier.width(12.dp))
        Text(name, color = Palette.muted, fontSize = 22.sp, modifier = Modifier.width(130.dp))
        Text(value, color = Palette.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(250.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(source, color = Palette.muted, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LogPanel(logs: List<UiLog>, modifier: Modifier) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Palette.card).padding(20.dp)) {
        Text("판정 로그", color = Palette.muted, fontSize = 22.sp)
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(logs) { l ->
                Row {
                    Text("%5.1f".format(l.t), color = Palette.muted, fontSize = 18.sp, modifier = Modifier.width(64.dp))
                    Column {
                        Text(l.event, color = Palette.text, fontSize = 19.sp)
                        if (l.decision.isNotEmpty()) Text(l.decision, color = phaseColor(l.phase), fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(18.dp)).background(if (selected) Palette.amber else Palette.card)
            .border(2.dp, if (selected) Palette.amber else Palette.line, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(horizontal = 26.dp, vertical = 16.dp)
    ) {
        Text(label, color = if (selected) Color.Black else Palette.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Pill(text: String, color: Color, size: TextUnit) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.18f)).border(2.dp, color, RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 6.dp)) {
        Text(text, color = color, fontSize = size, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BigButton(label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(96.dp).clip(RoundedCornerShape(22.dp)).background(color).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (color == Palette.amber) Color.Black else Palette.text, fontSize = 38.sp, fontWeight = FontWeight.Bold)
    }
}
