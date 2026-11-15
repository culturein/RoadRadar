package kr.roadradar.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.roadradar.app.engine.Messages
import kr.roadradar.app.engine.RiskLevel
import kr.roadradar.app.engine.WiperLevel
import kr.roadradar.app.signals.DemoSource
import kr.roadradar.app.signals.SignalType

private val Bg = Color(0xFF101418)
private val Panel = Color(0xFF1B2128)
private val TextMain = Color(0xFFF1F3F4)
private val TextSub = Color(0xFF9AA0A6)

fun levelColor(l: RiskLevel): Color = when (l) {
    RiskLevel.NORMAL -> Color(0xFF34A853)
    RiskLevel.CAUTION -> Color(0xFFF9D648)
    RiskLevel.ALERT -> Color(0xFFF29900)
    RiskLevel.DANGER -> Color(0xFFEA4335)
}

@Composable
fun MainScreen(state: UiState, vm: RoadRadarViewModel) {
    Column(Modifier.fillMaxSize().background(Bg).padding(24.dp)) {
        Header(state, vm)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            RoadLight(state, Modifier.weight(1.1f).fillMaxHeight())
            // 주행 중에는 신호 요약만, 정차·주차 중에만 브리핑·리포트·설정을 보여준다 (주행 중 주의 분산 방지)
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.parked) {
                    state.briefing?.let { InfoCard("출발 전 브리핑", it) }
                    ReportCard(state)
                    SettingsCard(state, vm)
                }
                SignalCard(state)
            }
        }
        if (state.demoEnabled) { Spacer(Modifier.height(16.dp)); DemoPanel(state, vm) }
    }
}

@Composable
private fun Header(state: UiState, vm: RoadRadarViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("노면레이더", color = TextMain, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(16.dp))
        Text("내 차 데이터로, 나에게만", color = TextSub, fontSize = 20.sp)
        Spacer(Modifier.weight(1f))
        Text(if (state.parked) "정차 중" else "주행 중", color = TextSub, fontSize = 20.sp)
        if (state.parked) {
            Spacer(Modifier.width(24.dp))
            Text("데모", color = TextSub, fontSize = 20.sp)
            Spacer(Modifier.width(8.dp))
            Switch(checked = state.demoEnabled, onCheckedChange = vm::setDemoEnabled)
        }
    }
}

@Composable
private fun RoadLight(state: UiState, modifier: Modifier) {
    val a = state.assessment
    val color by animateColorAsState(levelColor(a.level), label = "level")
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(220.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                Text(a.level.label, color = if (a.level == RiskLevel.CAUTION) Color.Black else Color.White,
                    fontSize = 64.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(28.dp))
            Text(Messages.recommendation(a.level), color = TextMain, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            if (a.reasons.isNotEmpty()) {
                Text(a.reasons.joinToString("  ·  ") { it.text }, color = TextSub, fontSize = 22.sp)
            }
            if (a.personalized) {
                Spacer(Modifier.height(8.dp))
                Text("내 미끄럼 기록을 반영해 한 단계 올렸습니다", color = color, fontSize = 20.sp)
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(title, color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(body, color = TextMain, fontSize = 20.sp)
        }
    }
}

@Composable
private fun ReportCard(state: UiState) {
    val r = state.lastReport
    val body = if (r == null) "아직 기록된 주행이 없습니다." else buildString {
        append("주행 ${r.durationMs / 60_000}분 ${(r.durationMs / 1000) % 60}초 중 위험 구간 ${r.riskyMs / 1000}초\n")
        r.reductionPercent?.let { append("위험 구간에서 평소보다 ${it}% 감속") ; append("\n") }
        append("미끄럼 제어 작동 ${r.slipEvents}회 · 위험 구간 급제동 ${r.harshBrakes}회 · 급조향 ${r.harshSteers}회\n")
        append("저장된 내 미끄럼 조건 ${state.slipMemoryCount}건 (차량 안에만 저장)")
    }
    InfoCard("악천후 운전 리포트", body)
}

@Composable
private fun SettingsCard(state: UiState, vm: RoadRadarViewModel) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("음성 안내", color = TextMain, fontSize = 20.sp)
            Spacer(Modifier.width(8.dp))
            Switch(checked = state.voiceEnabled, onCheckedChange = vm::setVoice)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = vm::deleteRecords) { Text("내 기록 전체 삭제", fontSize = 18.sp) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SignalCard(state: UiState) {
    val s = state.signals
    fun value(t: SignalType): String = when (t) {
        SignalType.OUTSIDE_TEMP -> s?.outsideTempC?.let { "%.1f℃".format(it) }
        SignalType.WIPER -> s?.wiper?.let { when (it) { WiperLevel.OFF -> "꺼짐"; WiperLevel.INTERMITTENT -> "간헐"; WiperLevel.LOW -> "저속"; WiperLevel.HIGH -> "고속" } }
        SignalType.SPEED -> s?.speedKmh?.let { "%.0f km/h".format(it) }
        SignalType.ABS, SignalType.TCS, SignalType.ESC -> s?.slipControlActive?.let { if (it) "작동" else "대기" }
        SignalType.TIRE_LOW -> s?.tirePressureLow?.let { if (it) "낮음" else "정상" }
        SignalType.PARKED -> s?.parked?.let { if (it) "정차" else "주행" }
        else -> null
    } ?: "-"
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("내 차 신호", color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            val shown = listOf(SignalType.OUTSIDE_TEMP, SignalType.WIPER, SignalType.TCS, SignalType.SPEED, SignalType.TIRE_LOW)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                shown.forEach { t ->
                    val ok = state.supported.containsKey(t) ||
                        (t == SignalType.TCS && (SignalType.ABS in state.supported || SignalType.ESC in state.supported))
                    val label = if (t == SignalType.TCS) "미끄럼 제어" else t.label
                    AssistChip(onClick = {}, label = {
                        Text(if (ok) "$label  ${value(t)}" else "$label  미지원", fontSize = 18.sp,
                            color = if (ok) TextMain else TextSub)
                    })
                }
            }
            if (state.supported.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("이 차량(에뮬레이터)에서 읽을 수 있는 신호가 없습니다. 정차 중 상단의 '데모'를 켜면 시나리오로 체험할 수 있습니다.",
                    color = TextSub, fontSize = 16.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DemoPanel(state: UiState, vm: RoadRadarViewModel) {
    val m = state.demoManual
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF232B33)), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.demoMode == DemoSource.Mode.SCENARIO)
                        "데모 시나리오: 12월 새벽 출근길 (${state.demoSecond}/${DemoSource.SCENARIO_END}초)"
                    else "데모 수동 조작 (가정: 오전 6시, 3시간 전 강수 기록)",
                    color = TextMain, fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
                Button(onClick = { vm.restartScenario(keepHistory = false) }) { Text("시나리오 처음부터", fontSize = 16.sp) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.restartScenario(keepHistory = true) }) { Text("다음 날 아침 다시 달리기", fontSize = 16.sp) }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("기온 %.1f℃".format(m.tempC), color = TextMain, fontSize = 16.sp)
                OutlinedButton(onClick = { vm.setManual { it.copy(tempC = it.tempC - 1f) } }) { Text("-") }
                OutlinedButton(onClick = { vm.setManual { it.copy(tempC = it.tempC + 1f) } }) { Text("+") }
                Spacer(Modifier.width(12.dp))
                WiperLevel.entries.forEach { w ->
                    FilterChip(selected = m.wiper == w, onClick = { vm.setManual { it.copy(wiper = w) } },
                        label = { Text("와이퍼 ${w.name.lowercase()}", fontSize = 14.sp) })
                }
                Spacer(Modifier.width(12.dp))
                FilterChip(selected = m.slip, onClick = { vm.setManual { it.copy(slip = !it.slip) } },
                    label = { Text("미끄럼(TCS)", fontSize = 14.sp) })
                FilterChip(selected = !m.parked, onClick = {
                    vm.setManual { it.copy(parked = !it.parked, speedKmh = if (it.parked) 50f else 0f) }
                }, label = { Text(if (m.parked) "출발" else "주차", fontSize = 14.sp) })
            }
        }
    }
}
