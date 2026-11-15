package kr.roadradar.app.signals

import ai.pleos.playground.vehicle.Vehicle
import android.content.Context
import android.util.Log
import kr.roadradar.app.engine.WiperLevel
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Pleos Vehicle SDK 수집원: 와이퍼, 타이어 공기압, 조향각, 브레이크 페달.
 *
 * Vehicle SDK의 등록 API는 `domain.registerXxx(area?, listener)` 형태이고 결과는 비동기 리스너로 온다.
 * 기능 이름은 SDK 버전·차종마다 다를 수 있어, 공식 샘플 앱(pleos-sample-vehicle-app)의 API 카탈로그와
 * 같은 방식(리플렉션 + Proxy 리스너)으로 키워드에 맞는 register 메서드를 찾아 구독한다.
 * 실제 이름을 확인한 뒤에는 타입이 지정된 API 호출로 바꾸는 것을 권장한다. (README '확인 필요' 참고)
 */
class PleosVehicleSource(context: Context) : SignalSource {
    override val name = "Pleos Vehicle SDK"
    private val vehicle = Vehicle(context.applicationContext)
    private var initialized = false
    private val unsubscribers = mutableListOf<() -> Unit>()
    private val tirePressures = mutableMapOf<String, Float>()

    override fun start(hub: SignalHub) {
        initialized = runCatching { vehicle.initialize() }.onFailure { Log.w(TAG, "Vehicle SDK 초기화 실패", it) }.isSuccess
        if (!initialized) return

        // 와이퍼: Wiper 도메인에서 'Wiper' 상태/스위치 등록 메서드
        sub(hub, SignalType.WIPER, runCatching { vehicle.getWiper() }.getOrNull(),
            listOf("Switch", "State", "Mode", "")) { value, _ -> wiperFromAny(value) }

        // 타이어 공기압: 바퀴별로 구독해 하나라도 기준 미만이면 '낮음'
        sub(hub, SignalType.TIRE_LOW, runCatching { vehicle.getTire() }.getOrNull(),
            listOf("TirePressure")) { value, area ->
            (value as? Number)?.toFloat()?.let { tirePressures[area] = it }
            tirePressures.values.any { it < LOW_TIRE_KPA }
        }

        // 조향각, 브레이크 페달 (급조향·급제동 감지용)
        sub(hub, SignalType.STEERING, runCatching { vehicle.getSteeringWheel() }.getOrNull(),
            listOf("SteeringWheelAngle")) { value, _ -> (value as? Number)?.toFloat() }
        sub(hub, SignalType.BRAKE, runCatching { vehicle.getBrake() }.getOrNull(),
            listOf("BrakePedalStatus", "BrakePedal")) { value, _ ->
            when (value) { is Boolean -> value; is Number -> value.toInt() != 0; else -> null }
        }
    }

    override fun stop() {
        unsubscribers.forEach { runCatching { it() } }
        unsubscribers.clear()
        if (initialized) runCatching { vehicle.release() }
        initialized = false
    }

    /** 키워드 순서대로 register 메서드를 찾아 첫 번째로 성공한 것을 쓴다. */
    private fun sub(
        hub: SignalHub, type: SignalType, domain: Any?, keywords: List<String>,
        map: (value: Any?, area: String) -> Any?,
    ) {
        domain ?: return
        val registers = domain.javaClass.methods.filter { it.name.startsWith("register") }
        val method = keywords.firstNotNullOfOrNull { kw ->
            registers.firstOrNull { m -> m.name.contains(kw) && m.parameterTypes.lastOrNull()?.isInterface == true &&
                (type != SignalType.WIPER || m.name.contains("Wiper")) }
        } ?: run { Log.i(TAG, "${type.label}: 등록 메서드 없음 (${domain.javaClass.simpleName})"); return }

        val ok = registerAllAreas(domain, method) { value, area -> hub.put(type, map(value, area)) }
        if (ok) { hub.markSupported(type, name); Log.i(TAG, "${type.label} ← ${method.name}") }
    }

    /** area 파라미터가 있으면 해당 Area(XxxValue) 열거값마다 등록한다. */
    private fun registerAllAreas(domain: Any, register: Method, onValue: (Any?, String) -> Unit): Boolean {
        val params = register.parameterTypes
        val areaArgs: List<Pair<String, Any?>> = if (params.size == 2) {
            areaInstances(params[0])
        } else listOf("ALL" to null)
        if (areaArgs.isEmpty()) return false

        var any = false
        for ((areaName, area) in areaArgs) {
            val listener = proxyListener(params.last()) { onValue(it, areaName) }
            val args: Array<Any?> = if (area == null) arrayOf(listener) else arrayOf(area, listener)
            val ok = runCatching { register.invoke(domain, *args) }.onFailure { Log.w(TAG, "${register.name} 실패", it) }.isSuccess
            if (ok) {
                any = true
                val unregister = domain.javaClass.methods.firstOrNull {
                    it.name == "un" + register.name && it.parameterTypes.size == args.size
                }
                if (unregister != null) unsubscribers += { unregister.invoke(domain, *args) }
            }
        }
        return any
    }

    /** XxxArea(XxxValue) 형태: 단일 enum 인자 생성자를 찾아 모든 값으로 만든다. */
    private fun areaInstances(areaClass: Class<*>): List<Pair<String, Any?>> {
        if (areaClass.isEnum) return areaClass.enumConstants.orEmpty().map { it.toString() to it }
        val ctor = areaClass.constructors.firstOrNull { it.parameterTypes.size == 1 && it.parameterTypes[0].isEnum }
            ?: return emptyList()
        return ctor.parameterTypes[0].enumConstants.orEmpty().mapNotNull { v ->
            runCatching { v.toString() to ctor.newInstance(v) }.getOrNull()
        }
    }

    private fun proxyListener(listenerType: Class<*>, onValue: (Any?) -> Unit): Any =
        Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "RoadRadarListener(${listenerType.simpleName})"
                else -> {
                    if (method.name.contains("fail", ignoreCase = true)) {
                        Log.w(TAG, "${listenerType.simpleName}.${method.name}: ${args?.firstOrNull()}")
                    } else {
                        onValue(args?.firstOrNull())
                    }
                    Unit
                }
            }
        }

    companion object {
        private const val TAG = "RoadRadar/Pleos"
        /** 타이어 공기압 '낮음' 기준(kPa). 초기 가정값: 일반 승용차 권장 압력(약 230~250kPa)보다 충분히 낮은 값. */
        const val LOW_TIRE_KPA = 200f

        /** SDK가 Int/enum/문자열 중 무엇으로 주든 4단계로 정규화한다. */
        fun wiperFromAny(v: Any?): WiperLevel? {
            if (v is Number) return CarPropertySource.wiperFromSwitch(v.toInt())
            val s = v?.toString()?.uppercase() ?: return null
            return when {
                "OFF" in s -> WiperLevel.OFF
                listOf("HIGH", "FAST", "CONTINUOUS_LEVEL_3", "CONTINUOUS_LEVEL_4", "CONTINUOUS_LEVEL_5").any { it in s } -> WiperLevel.HIGH
                listOf("LOW", "SLOW", "CONTINUOUS").any { it in s } -> WiperLevel.LOW
                listOf("INT", "MIST", "AUTO", "ON").any { it in s } -> WiperLevel.INTERMITTENT
                else -> null
            }
        }
    }
}
