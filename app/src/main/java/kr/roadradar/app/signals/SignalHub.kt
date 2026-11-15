package kr.roadradar.app.signals

import kr.roadradar.app.engine.VehicleSignals
import kr.roadradar.app.engine.WiperLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/** 앱이 쓰는 차량 신호 종류. */
enum class SignalType(val label: String) {
    OUTSIDE_TEMP("외기온도"),
    WIPER("와이퍼"),
    ABS("ABS"),
    TCS("트랙션 제어"),
    ESC("자세 제어"),
    SPEED("차속"),
    BRAKE("브레이크 페달"),
    STEERING("조향각"),
    TIRE_LOW("타이어 공기압"),
    PARKED("정차·주차 상태"),
}

/** 신호 수집원. 차종마다 지원 신호가 다르므로 start() 후 지원된 신호만 hub에 표시한다. */
interface SignalSource {
    val name: String
    fun start(hub: SignalHub)
    fun stop()
}

/**
 * 여러 수집원(AAOS 표준 속성, Pleos Vehicle SDK, 데모)의 값을 하나로 모은다.
 * 같은 신호를 여러 수집원이 주면 마지막 값이 쓰인다.
 */
class SignalHub {
    private val values = ConcurrentHashMap<SignalType, Any>()
    private val _supported = MutableStateFlow<Map<SignalType, String>>(emptyMap())
    /** 신호 → 제공한 수집원 이름. 화면의 '신호 상태'에 표시된다. */
    val supported: StateFlow<Map<SignalType, String>> = _supported

    fun markSupported(type: SignalType, source: String) = _supported.update { it + (type to source) }

    fun put(type: SignalType, value: Any?) {
        if (value == null) values.remove(type) else values[type] = value
    }

    fun clear() { values.clear(); _supported.value = emptyMap() }

    fun snapshot(nowMs: Long, hourOfDay: Int): VehicleSignals {
        val slipFlags = listOf(SignalType.ABS, SignalType.TCS, SignalType.ESC).mapNotNull { values[it] as? Boolean }
        val speed = values[SignalType.SPEED] as? Float
        return VehicleSignals(
            timestampMs = nowMs,
            hourOfDay = hourOfDay,
            outsideTempC = values[SignalType.OUTSIDE_TEMP] as? Float,
            wiper = values[SignalType.WIPER] as? WiperLevel,
            slipControlActive = if (slipFlags.isEmpty()) null else slipFlags.any { it },
            speedKmh = speed,
            brakePressed = values[SignalType.BRAKE] as? Boolean,
            steeringAngleDeg = values[SignalType.STEERING] as? Float,
            tirePressureLow = values[SignalType.TIRE_LOW] as? Boolean,
            parked = (values[SignalType.PARKED] as? Boolean) ?: speed?.let { it < 1f },
        )
    }
}
