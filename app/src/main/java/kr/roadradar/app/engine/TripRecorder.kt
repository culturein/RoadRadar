package kr.roadradar.app.engine

/**
 * 한 번의 주행을 기록한다: 위험 단계별 시간, 단계별 평균 속도, 급제동·급조향, 미끄럼 제어 작동.
 * 결과는 주차 후 '악천후 운전 리포트'로만 보여준다.
 */
class TripRecorder(private val config: EngineConfig = EngineConfig()) {

    data class Report(
        val durationMs: Long,
        val msByLevel: Map<RiskLevel, Long>,
        val avgSpeedByLevel: Map<RiskLevel, Float>,
        val harshBrakes: Int,
        val harshSteers: Int,
        val slipEvents: Int,
        val newSlipConditions: List<SlipCondition>,
    ) {
        val riskyMs: Long get() = (msByLevel - RiskLevel.NORMAL).values.sum()

        /** 위험 구간 평균 속도가 정상 구간보다 몇 % 낮았는지. 비교할 데이터가 없으면 null. */
        val reductionPercentInRisk: Int? get() {
            val normal = avgSpeedByLevel[RiskLevel.NORMAL] ?: return null
            val riskEntries = msByLevel.filterKeys { it != RiskLevel.NORMAL && avgSpeedByLevel[it] != null }
            val riskMs = riskEntries.values.sum()
            if (normal <= 0f || riskMs == 0L) return null
            val risky = riskEntries.entries.sumOf { (lvl, ms) -> (avgSpeedByLevel.getValue(lvl) * ms).toDouble() } / riskMs
            return (((normal - risky) / normal) * 100).toInt()
        }
    }

    private var startMs: Long? = null
    private var lastSample: VehicleSignals? = null
    private var lastLevel = RiskLevel.NORMAL
    private val ms = mutableMapOf<RiskLevel, Long>()
    private val speedWeighted = mutableMapOf<RiskLevel, Double>()
    private val speedMs = mutableMapOf<RiskLevel, Long>()
    private var harshBrakes = 0
    private var harshSteers = 0
    private var slipEvents = 0
    private var slipActivePrev = false
    private val newSlips = mutableListOf<SlipCondition>()

    /** @return 이번 샘플에서 새 미끄럼 이벤트가 시작됐으면 그 조건. */
    fun record(s: VehicleSignals, level: RiskLevel, recentPrecip: Boolean): SlipCondition? {
        if (startMs == null) startMs = s.timestampMs
        val prev = lastSample
        if (prev != null) {
            val dt = (s.timestampMs - prev.timestampMs).coerceAtLeast(0)
            ms[lastLevel] = (ms[lastLevel] ?: 0) + dt
            prev.speedKmh?.let {
                speedWeighted[lastLevel] = (speedWeighted[lastLevel] ?: 0.0) + it * dt
                speedMs[lastLevel] = (speedMs[lastLevel] ?: 0) + dt
            }
            if (dt in 1..5_000) detectHarsh(prev, s, dt)
        }
        lastSample = s
        lastLevel = level

        val slipNow = s.slipControlActive == true
        var created: SlipCondition? = null
        if (slipNow && !slipActivePrev) {
            slipEvents++
            s.outsideTempC?.let { t ->
                created = SlipCondition(s.timestampMs, t, TimeBand.of(s.hourOfDay), recentPrecip, s.speedKmh)
                newSlips += created!!
            }
        }
        slipActivePrev = slipNow
        return created
    }

    private fun detectHarsh(prev: VehicleSignals, cur: VehicleSignals, dtMs: Long) {
        if (lastLevel == RiskLevel.NORMAL) return  // 위험 단계에서의 급조작만 센다
        val sec = dtMs / 1000f
        val v0 = prev.speedKmh; val v1 = cur.speedKmh
        if (v0 != null && v1 != null && (v0 - v1) / sec >= config.harshBrakeDropKmhPerSec) harshBrakes++
        val a0 = prev.steeringAngleDeg; val a1 = cur.steeringAngleDeg
        if (a0 != null && a1 != null && (v1 ?: 0f) >= config.harshSteerMinSpeedKmh &&
            kotlin.math.abs(a1 - a0) / sec >= config.harshSteerDegPerSec) harshSteers++
    }

    fun report(): Report = Report(
        durationMs = (lastSample?.timestampMs ?: 0) - (startMs ?: 0),
        msByLevel = ms.toMap(),
        avgSpeedByLevel = speedMs.filterValues { it > 0 }
            .mapValues { (lvl, t) -> (speedWeighted.getValue(lvl) / t).toFloat() },
        harshBrakes = harshBrakes,
        harshSteers = harshSteers,
        slipEvents = slipEvents,
        newSlipConditions = newSlips.toList(),
    )
}
