package kr.roadradar.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kr.roadradar.app.data.LocalStore
import kr.roadradar.app.engine.Assessment
import kr.roadradar.app.engine.EngineConfig
import kr.roadradar.app.engine.Messages
import kr.roadradar.app.engine.RiskEngine
import kr.roadradar.app.engine.RiskLevel
import kr.roadradar.app.engine.RiskStateMachine
import kr.roadradar.app.engine.TripRecorder
import kr.roadradar.app.engine.VehicleSignals
import kr.roadradar.app.signals.CarPropertySource
import kr.roadradar.app.signals.DemoSource
import kr.roadradar.app.signals.PleosVehicleSource
import kr.roadradar.app.signals.SignalHub
import kr.roadradar.app.signals.SignalSource
import kr.roadradar.app.signals.SignalType
import kr.roadradar.app.voice.Announcer
import java.util.Calendar

data class UiState(
    val assessment: Assessment = Assessment(RiskLevel.NORMAL, emptyList()),
    val signals: VehicleSignals? = null,
    val supported: Map<SignalType, String> = emptyMap(),
    val parked: Boolean = true,
    val briefing: String? = null,
    val lastReport: LocalStore.SavedReport? = null,
    val slipMemoryCount: Int = 0,
    val voiceEnabled: Boolean = true,
    val demoEnabled: Boolean = false,
    val demoMode: DemoSource.Mode = DemoSource.Mode.SCENARIO,
    val demoSecond: Int = 0,
    val demoManual: DemoSource.Manual = DemoSource.Manual(),
)

/** 신호 수집 → 판단 → 안내 → 기록을 1초 주기로 돌린다. 모든 처리는 차량 안에서 끝난다. */
class RoadRadarViewModel(app: Application) : AndroidViewModel(app) {
    private val config = EngineConfig()
    private val engine = RiskEngine(config)
    private var stateMachine = RiskStateMachine(config)
    private val hub = SignalHub()
    private val announcer = Announcer(app)
    private val realStore = LocalStore(app)
    private val demoStore = LocalStore(app, "roadradar-demo.json")
    private var store = realStore

    private val demo = DemoSource()
    private var sources: List<SignalSource> = emptyList()

    private var recorder: TripRecorder? = null
    /** 진행 중인 주행의 시작 시각. 개인화에는 이전 주행의 미끄럼 기록만 쓴다. */
    private var tripStartMs: Long? = null
    private var wasParked = true
    private var briefingDue = true
    private var loop: Job? = null

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    init {
        startSources(demoEnabled = false)
        viewModelScope.launch { hub.supported.collect { s -> _ui.update { it.copy(supported = s) } } }
        viewModelScope.launch { demo.mode.collect { m -> _ui.update { it.copy(demoMode = m) } } }
        viewModelScope.launch { demo.scenarioSecond.collect { t -> _ui.update { it.copy(demoSecond = t) } } }
        viewModelScope.launch { demo.manual.collect { m -> _ui.update { it.copy(demoManual = m) } } }
    }

    private fun startSources(demoEnabled: Boolean) {
        loop?.cancel()
        sources.forEach { it.stop() }
        hub.clear()
        // 데모는 70초로 압축된 시나리오라 단계 하향 유지 시간을 10초로 줄인다 (실차: 60초)
        stateMachine = RiskStateMachine(if (demoEnabled) config.copy(downgradeHoldMs = 10_000) else config)
        recorder = null; tripStartMs = null; wasParked = true; briefingDue = true
        store = if (demoEnabled) demoStore else realStore
        val app = getApplication<Application>()
        sources = if (demoEnabled) listOf(demo)
        else listOf(CarPropertySource(app), runCatching { PleosVehicleSource(app) }.getOrNull()).filterNotNull()
        sources.forEach { runCatching { it.start(hub) } }
        _ui.update { it.copy(demoEnabled = demoEnabled, briefing = null, lastReport = store.lastReport(),
            slipMemoryCount = store.slipConditions().size, assessment = Assessment(RiskLevel.NORMAL, emptyList())) }
        loop = viewModelScope.launch { while (isActive) { tick(); delay(1000) } }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val hour = if (_ui.value.demoEnabled) demo.demoHour else Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val s = hub.snapshot(now, hour)

        // 와이퍼가 작동하면 '강수 기록'을 남긴다 (1분 간격으로 갱신)
        if (s.wiper?.isActive == true) {
            val last = store.lastPrecipitationAtMs
            if (last == null || now - last > 60_000) store.lastPrecipitationAtMs = now
        }
        val lastPrecip = store.lastPrecipitationAtMs
        val tripStart = tripStartMs
        val memory = store.slipConditions().filter { tripStart == null || it.timestampMs < tripStart }
        val raised = stateMachine.update(engine.evaluate(s, lastPrecip, memory), now)
        val current = stateMachine.current
        val parked = s.parked ?: true

        var briefing = _ui.value.briefing
        if (parked && briefingDue && s.outsideTempC != null) {
            val historyMatch = engine.evaluate(s, lastPrecip, memory).personalized
            briefing = Messages.briefing(now, hour, s.outsideTempC, lastPrecip, s.tirePressureLow, historyMatch, config)
            announcer.speak(briefing)
            briefingDue = false
        }

        val tripStarted = !parked && wasParked
        if (tripStarted) { recorder = TripRecorder(config); tripStartMs = now; briefing = null }
        // 출발 순간 이미 위험 단계라면 한 번 안내하고, 주행 중에는 단계가 올라갈 때만 안내한다
        if (tripStarted && current.level != RiskLevel.NORMAL || !parked && raised) {
            Messages.announcement(current)?.let(announcer::speak)
        }

        recorder?.let { rec ->
            val recentPrecip = s.wiper?.isActive == true || engine.isRecentPrecipitation(now, lastPrecip)
            rec.record(s, current.level, recentPrecip)?.let { store.addSlip(it) }
        }

        if (parked && !wasParked) {
            recorder?.let { rec ->
                val r = rec.report()
                if (r.durationMs > 0) {
                    store.saveReport(r, now)
                    announcer.speak("주차가 확인됐습니다. 이번 주행 리포트를 확인해 보세요.")
                }
            }
            recorder = null
            tripStartMs = null
        }
        wasParked = parked

        _ui.update {
            it.copy(assessment = current, signals = s, parked = parked, briefing = briefing,
                lastReport = store.lastReport(), slipMemoryCount = store.slipConditions().size)
        }
    }

    // --- 사용자 조작 ---
    fun setDemoEnabled(enabled: Boolean) {
        if (enabled == _ui.value.demoEnabled) return
        if (enabled) restartScenario() else startSources(demoEnabled = false)
    }

    /** @param keepHistory true면 '다음 날 아침' 재생: 앞 주행의 미끄럼 기록을 남겨 개인화 경고를 보여준다. */
    fun restartScenario(keepHistory: Boolean = false) {
        if (!keepHistory) demoStore.deleteAll()
        demoStore.lastPrecipitationAtMs = System.currentTimeMillis() - demo.seedPrecipitationAgoMs
        startSources(demoEnabled = true)
        demo.playScenario()
    }

    fun setManual(update: (DemoSource.Manual) -> DemoSource.Manual) = demo.setManual(update)

    fun setVoice(enabled: Boolean) {
        announcer.enabled = enabled
        _ui.update { it.copy(voiceEnabled = enabled) }
    }

    fun deleteRecords() {
        store.deleteAll()
        _ui.update { it.copy(lastReport = null, slipMemoryCount = 0) }
    }

    override fun onCleared() {
        sources.forEach { it.stop() }
        announcer.shutdown()
    }
}
