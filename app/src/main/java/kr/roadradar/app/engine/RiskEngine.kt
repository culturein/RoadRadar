package kr.roadradar.app.engine

/**
 * 3단계 노면 위험 판단 규칙 (기획서 4장).
 *
 * 위에서부터 차례로 확인해 처음 '예'가 나오는 단계로 정한다.
 *  1) 위험: ABS·TCS·ESC 작동 / 와이퍼 고속 + 0℃ 이하
 *  2) 경계: 4℃ 이하 + 최근 6시간 강수 기록 / 0℃ 이하 + 새벽(5~10시) / 와이퍼 작동 + 4℃ 이하
 *  3) 주의: 와이퍼 작동 + 4℃ 초과
 *  4) 정상
 * 개인화: 예전에 미끄러졌던 조건(기온 ±2℃, 같은 시간대)과 맞으면 한 단계 올린다.
 */
class RiskEngine(private val config: EngineConfig = EngineConfig()) {

    /**
     * @param lastPrecipitationAtMs 마지막으로 와이퍼가 작동한 시각(차량 내 기록). 없으면 null.
     * @param slipMemory 내 차의 과거 미끄럼 조건 목록.
     */
    fun evaluate(
        s: VehicleSignals,
        lastPrecipitationAtMs: Long?,
        slipMemory: List<SlipCondition> = emptyList(),
    ): Assessment {
        val reasons = mutableListOf<Reason>()
        val temp = s.outsideTempC
        val wiperOn = s.wiper?.isActive == true
        val recentPrecip = wiperOn || isRecentPrecipitation(s.timestampMs, lastPrecipitationAtMs)
        val coldRisk = temp != null && temp <= config.freezeRiskTempC
        val freezing = temp != null && temp <= config.freezingTempC

        val base: RiskLevel = when {
            s.slipControlActive == true -> { reasons += Reason.SLIP_CONTROL; RiskLevel.DANGER }
            s.wiper == WiperLevel.HIGH && freezing -> { reasons += Reason.HEAVY_PRECIP_FREEZING; RiskLevel.DANGER }
            wiperOn && coldRisk -> { reasons += Reason.WET_COLD; RiskLevel.ALERT }
            coldRisk && recentPrecip -> { reasons += Reason.COLD_AFTER_PRECIP; RiskLevel.ALERT }
            freezing && TimeBand.of(s.hourOfDay) == TimeBand.DAWN -> { reasons += Reason.FREEZING_DAWN; RiskLevel.ALERT }
            wiperOn -> { reasons += Reason.WET_ROAD; RiskLevel.CAUTION }
            else -> RiskLevel.NORMAL
        }

        val matchesHistory = temp != null && slipMemory.any { past ->
            kotlin.math.abs(past.outsideTempC - temp) <= config.personalTempToleranceC &&
                past.timeBand == TimeBand.of(s.hourOfDay) &&
                // 과거에 강수 후 미끄러졌다면 지금도 강수 기록이 있을 때만 맞는 것으로 본다
                (!past.recentPrecipitation || recentPrecip)
        }

        return if (matchesHistory && base != RiskLevel.DANGER) {
            Assessment(base.raised(), reasons + Reason.MY_SLIP_HISTORY, personalized = true)
        } else {
            Assessment(base, reasons)
        }
    }

    fun isRecentPrecipitation(nowMs: Long, lastPrecipitationAtMs: Long?): Boolean =
        lastPrecipitationAtMs != null && nowMs - lastPrecipitationAtMs in 0..config.recentPrecipWindowMs
}

/**
 * 알림이 자주 바뀌지 않게 하는 상태 기계.
 * 단계는 올라갈 때 즉시, 내려갈 때는 downgradeHoldMs 동안 낮은 판단이 유지되어야 바뀐다.
 */
class RiskStateMachine(private val config: EngineConfig = EngineConfig()) {
    var current: Assessment = Assessment(RiskLevel.NORMAL, emptyList())
        private set
    private var lowerSinceMs: Long? = null

    /** @return 단계가 올라갔으면 true (음성 안내 트리거). */
    fun update(next: Assessment, nowMs: Long): Boolean {
        val prev = current.level
        when {
            next.level > prev -> { current = next; lowerSinceMs = null; return true }
            next.level == prev -> { current = next; lowerSinceMs = null }
            else -> {
                val since = lowerSinceMs ?: nowMs.also { lowerSinceMs = it }
                if (nowMs - since >= config.downgradeHoldMs) { current = next; lowerSinceMs = null }
            }
        }
        return false
    }
}
