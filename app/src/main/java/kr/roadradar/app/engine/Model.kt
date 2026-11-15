package kr.roadradar.app.engine

/**
 * 노면레이더 판단 엔진의 데이터 모델.
 * 이 패키지(engine)는 Android 의존성이 없는 순수 Kotlin이라 단위 테스트로 검증할 수 있다.
 */

/** 위험 단계. reductionPercent = 도로교통법 시행규칙 제19조 기준 권장 감속률(최고속도 대비). */
enum class RiskLevel(val label: String, val reductionPercent: Int) {
    NORMAL("정상", 0),
    CAUTION("주의", 20),
    ALERT("경계", 20),   // 20~50% (블랙아이스 가능): 화면에는 "20~50%"로 표시
    DANGER("위험", 50);

    fun raised(): RiskLevel = entries[minOf(ordinal + 1, entries.lastIndex)]
}

/** 와이퍼 강도. 차량 신호(AAOS WINDSHIELD_WIPERS_SWITCH 또는 Pleos Wiper)를 이 4단계로 정규화한다. */
enum class WiperLevel { OFF, INTERMITTENT, LOW, HIGH;
    val isActive get() = this != OFF
}

/** 하루를 4개 시간대로 나눈다. 개인화 규칙의 '같은 시간대' 판단에 쓴다. */
enum class TimeBand(val label: String) {
    DAWN("새벽·아침(5~10시)"), DAY("낮(10~17시)"), EVENING("저녁(17~22시)"), NIGHT("밤(22~5시)");

    companion object {
        fun of(hour: Int): TimeBand = when (hour) {
            in 5..9 -> DAWN
            in 10..16 -> DAY
            in 17..21 -> EVENING
            else -> NIGHT
        }
    }
}

/**
 * 한 시점의 차량 신호 스냅샷. 차종에 따라 지원되지 않는 신호는 null.
 * null인 신호는 판단에서 제외된다(나머지 신호로 판단 지속).
 */
data class VehicleSignals(
    val timestampMs: Long,
    val hourOfDay: Int,
    val outsideTempC: Float? = null,
    val wiper: WiperLevel? = null,
    /** ABS·TCS·ESC 중 하나라도 작동 중이면 true. */
    val slipControlActive: Boolean? = null,
    val speedKmh: Float? = null,
    val brakePressed: Boolean? = null,
    val steeringAngleDeg: Float? = null,
    val tirePressureLow: Boolean? = null,
    val parked: Boolean? = null,
)

/** 판단 근거. 화면 칩과 음성 안내에 쓰인다. */
enum class Reason(val text: String) {
    SLIP_CONTROL("미끄럼 제어(ABS·TCS·ESC) 작동"),
    HEAVY_PRECIP_FREEZING("강한 강수 + 영하"),
    COLD_AFTER_PRECIP("결빙 가능 온도 + 최근 강수"),
    FREEZING_DAWN("영하의 새벽·아침"),
    WET_COLD("강수 + 결빙 가능 온도"),
    WET_ROAD("강수로 젖은 노면"),
    MY_SLIP_HISTORY("예전에 미끄러졌던 조건과 비슷함"),
}

data class Assessment(
    val level: RiskLevel,
    val reasons: List<Reason>,
    /** 개인화 규칙으로 한 단계 올라갔는지. */
    val personalized: Boolean = false,
)

/** 내 차가 미끄러졌던 조건. 차량 내부에만 저장된다(위치 정보 없음). */
data class SlipCondition(
    val timestampMs: Long,
    val outsideTempC: Float,
    val timeBand: TimeBand,
    val recentPrecipitation: Boolean,
    val speedKmh: Float?,
)

/**
 * 판단 기준값. 기획서에 밝힌 대로 초기 가정값이며 에뮬레이터 시뮬레이션으로 조정한다.
 */
data class EngineConfig(
    val freezeRiskTempC: Float = 4f,        // 이하면 결빙 가능
    val freezingTempC: Float = 0f,          // 이하면 결빙 유력
    val recentPrecipWindowMs: Long = 6 * 60 * 60 * 1000L,  // 최근 6시간 강수 기록
    val personalTempToleranceC: Float = 2f, // 개인화: 기온 ±2℃
    val downgradeHoldMs: Long = 60_000L,    // 단계 하향은 60초 유지 후
    val harshBrakeDropKmhPerSec: Float = 10f,
    val harshSteerDegPerSec: Float = 90f,
    val harshSteerMinSpeedKmh: Float = 30f,
)
