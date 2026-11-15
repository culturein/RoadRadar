package kr.roadradar.app.signals

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kr.roadradar.app.engine.WiperLevel

/**
 * 시연용 신호 수집원.
 * - 시나리오: 기획서 3장 '12월 새벽 출근길'을 약 70초로 압축해 재생한다.
 * - 수동: 화면의 데모 패널에서 기온·와이퍼·미끄럼·차속을 직접 바꾼다.
 * 에뮬레이터의 Virtual Console로 같은 신호를 넣을 수 있다면 데모 모드를 끄고 실제 수집원을 쓰면 된다.
 */
class DemoSource : SignalSource {
    override val name = "데모"

    data class Manual(
        val tempC: Float = 1f,
        val wiper: WiperLevel = WiperLevel.OFF,
        val slip: Boolean = false,
        val speedKmh: Float = 0f,
        val parked: Boolean = true,
        val tireLow: Boolean = false,
    )

    enum class Mode { SCENARIO, MANUAL }

    private val _mode = MutableStateFlow(Mode.SCENARIO)
    val mode: StateFlow<Mode> = _mode
    private val _manual = MutableStateFlow(Manual())
    val manual: StateFlow<Manual> = _manual
    private val _scenarioSecond = MutableStateFlow(0)
    val scenarioSecond: StateFlow<Int> = _scenarioSecond

    /** 시연 시 '현재 시각'으로 쓸 시(hour). 새벽 출근길 = 6시. */
    val demoHour = 6
    /** 시나리오 시작 시 '어제 저녁 비' 기록으로 넣을 시간(3시간 전). */
    val seedPrecipitationAgoMs = 3 * 60 * 60 * 1000L

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var hub: SignalHub? = null

    override fun start(hub: SignalHub) {
        this.hub = hub
        SignalType.entries.forEach { hub.markSupported(it, name) }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        if (_mode.value == Mode.SCENARIO) playScenario() else applyManual(_manual.value)
    }

    override fun stop() { scope?.cancel(); scope = null; job = null }

    fun playScenario() {
        _mode.value = Mode.SCENARIO
        job?.cancel()
        job = scope?.launch {
            var t = 0
            while (isActive && t <= SCENARIO_END) {
                _scenarioSecond.value = t
                applyManual(scenarioAt(t))
                delay(1000); t++
            }
            _mode.value = Mode.MANUAL
            _manual.value = scenarioAt(SCENARIO_END)
        }
    }

    fun setManual(update: (Manual) -> Manual) {
        job?.cancel()
        _mode.value = Mode.MANUAL
        _manual.value = update(_manual.value)
        applyManual(_manual.value)
    }

    private fun applyManual(m: Manual) {
        val h = hub ?: return
        h.put(SignalType.OUTSIDE_TEMP, m.tempC)
        h.put(SignalType.WIPER, m.wiper)
        h.put(SignalType.TCS, m.slip)
        h.put(SignalType.ABS, false)
        h.put(SignalType.ESC, false)
        h.put(SignalType.SPEED, m.speedKmh)
        h.put(SignalType.PARKED, m.parked)
        h.put(SignalType.TIRE_LOW, m.tireLow)
        h.put(SignalType.BRAKE, false)
        h.put(SignalType.STEERING, 0f)
    }

    companion object {
        const val SCENARIO_END = 70

        /** 새벽 출근길 시나리오: 정차(브리핑) → 출발(경계) → 다리 위 미끄럼(위험) → 약한 비 → 주차(리포트). */
        fun scenarioAt(t: Int): Manual = when {
            t < 8 -> Manual(tempC = 1f, parked = true, tireLow = true)
            t < 30 -> Manual(tempC = 1f - (t - 8) * 0.02f, speedKmh = minOf(55f, (t - 8) * 6f), parked = false, tireLow = true)
            t < 32 -> Manual(tempC = -0.5f, speedKmh = 55f, parked = false, tireLow = true)
            t < 35 -> Manual(tempC = -0.5f, speedKmh = 55f - (t - 31) * 5f, slip = true, parked = false, tireLow = true)
            t < 45 -> Manual(tempC = 0f, speedKmh = 40f, parked = false, tireLow = true)
            t < 56 -> Manual(tempC = 0.5f, speedKmh = 40f, wiper = WiperLevel.INTERMITTENT, parked = false, tireLow = true)
            t < 64 -> Manual(tempC = 0.5f, speedKmh = maxOf(0f, 40f - (t - 56) * 6f), parked = false, tireLow = true)
            else -> Manual(tempC = 0.5f, speedKmh = 0f, parked = true, tireLow = true)
        }
    }
}
