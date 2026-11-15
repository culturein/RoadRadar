package kr.roadradar.app.signals

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Build
import android.util.Log
import kr.roadradar.app.engine.WiperLevel

/**
 * AAOS 표준 차량 속성 수집원.
 * Pleos Connect SDK는 AAOS 표준에 같은 기능이 있으면 표준 API 사용을 권장하므로
 * 외기온도·차속·ABS/TCS/ESC·와이퍼·기어·주차 브레이크는 CarPropertyManager로 읽는다.
 * 지원되지 않거나 권한이 없는 속성은 건너뛰고, 나머지 신호로 판단을 계속한다.
 */
class CarPropertySource(private val context: Context) : SignalSource {
    override val name = "AAOS 표준"
    private var car: Car? = null
    private var manager: CarPropertyManager? = null
    private val callbacks = mutableListOf<CarPropertyManager.CarPropertyEventCallback>()

    override fun start(hub: SignalHub) {
        val c = runCatching { Car.createCar(context) }.getOrNull() ?: run {
            Log.w(TAG, "Car 서비스에 연결할 수 없습니다 (차량/에뮬레이터가 아님)"); return
        }
        car = c
        val m = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: return
        manager = m

        fun subscribe(propId: Int, type: SignalType, rateHz: Float, map: (Any?) -> Any?) {
            val available = runCatching { m.getCarPropertyConfig(propId) != null }.getOrDefault(false)
            if (!available) return
            val cb = object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>) {
                    if (value.status == CarPropertyValue.STATUS_AVAILABLE) hub.put(type, map(value.value))
                }
                override fun onErrorEvent(propertyId: Int, areaId: Int) {
                    Log.w(TAG, "속성 오류: ${VehiclePropertyIds.toString(propertyId)}")
                }
            }
            try {
                @Suppress("DEPRECATION")
                if (m.registerCallback(cb, propId, rateHz)) {
                    callbacks += cb
                    hub.markSupported(type, name)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "권한 없음: ${VehiclePropertyIds.toString(propId)}")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "미지원: ${VehiclePropertyIds.toString(propId)}")
            }
        }

        subscribe(VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE, SignalType.OUTSIDE_TEMP, 1f) { (it as? Float) }
        subscribe(VehiclePropertyIds.PERF_VEHICLE_SPEED, SignalType.SPEED, 5f) { (it as? Float)?.times(3.6f) }  // m/s → km/h
        subscribe(VehiclePropertyIds.ABS_ACTIVE, SignalType.ABS, 0f) { it as? Boolean }
        subscribe(VehiclePropertyIds.TRACTION_CONTROL_ACTIVE, SignalType.TCS, 0f) { it as? Boolean }
        subscribe(VehiclePropertyIds.GEAR_SELECTION, SignalType.PARKED, 0f) { (it as? Int)?.let { g -> g == VehicleGear.GEAR_PARK } }
        if (Build.VERSION.SDK_INT >= 34) {
            // 이 두 속성은 컴파일용 라이브러리에 공개돼 있지 않아 실행 시점에 이름으로 찾는다
            propId("ELECTRONIC_STABILITY_CONTROL_STATE")?.let { id ->
                subscribe(id, SignalType.ESC, 0f) { (it as? Int)?.let { s -> s == 2 } }
            }
            propId("WINDSHIELD_WIPERS_SWITCH")?.let { id ->
                subscribe(id, SignalType.WIPER, 0f) { (it as? Int)?.let(::wiperFromSwitch) }
            }
        }
    }

    override fun stop() {
        manager?.let { m -> callbacks.forEach { runCatching { m.unregisterCallback(it) } } }
        callbacks.clear()
        car?.disconnect()
        car = null; manager = null
    }

    companion object {
        private const val TAG = "RoadRadar/AAOS"

        /** VehiclePropertyIds의 상수를 이름으로 찾는다. 이 기기에 없으면 null(해당 신호 건너뜀). */
        private fun propId(name: String): Int? =
            runCatching { VehiclePropertyIds::class.java.getField(name).getInt(null) }.getOrNull()
        /**
         * WindshieldWipersSwitch: OFF=1, MIST=2, INTERMITTENT_LEVEL_1..5=3..7,
         * CONTINUOUS_LEVEL_1..5=8..12, AUTO=13. 연속 3단계 이상을 '고속'으로 본다.
         */
        fun wiperFromSwitch(v: Int): WiperLevel = when (v) {
            in 2..7, 13 -> WiperLevel.INTERMITTENT
            in 8..9 -> WiperLevel.LOW
            in 10..12 -> WiperLevel.HIGH
            else -> WiperLevel.OFF
        }
    }
}
