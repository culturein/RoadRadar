package kr.roadradar.app.engine

/** 음성 안내와 출발 전 브리핑 문구. 주행 중 안내는 한 문장으로 짧게 유지한다. */
object Messages {

    fun announcement(a: Assessment): String? = when (a.level) {
        RiskLevel.NORMAL -> null
        RiskLevel.CAUTION -> "노면이 젖었습니다. 평소보다 20퍼센트 줄여 주세요."
        RiskLevel.ALERT ->
            if (a.personalized) "예전에 미끄러졌던 조건과 비슷합니다. 블랙아이스에 주의하고 급제동을 피하세요."
            else "블랙아이스 주의. 속도를 줄이고 차간거리를 두 배로 두세요."
        RiskLevel.DANGER ->
            if (Reason.SLIP_CONTROL in a.reasons) "미끄럼이 감지됐습니다. 절반 속도로 줄이고 급제동과 급조향을 피하세요."
            else if (a.personalized) "예전에 미끄러졌던 조건과 비슷합니다. 절반 속도로 줄이고 급제동을 피하세요."
            else "노면이 얼었을 수 있습니다. 절반 속도로 줄이세요."
    }

    fun recommendation(level: RiskLevel): String = when (level) {
        RiskLevel.NORMAL -> "안내 없음"
        RiskLevel.CAUTION -> "최고속도의 20% 감속"
        RiskLevel.ALERT -> "20~50% 감속 · 차간거리 2배"
        RiskLevel.DANGER -> "50% 감속 · 급제동·급조향 금지"
    }

    /** 시동 직후(정차 중) 1분 브리핑. */
    fun briefing(
        nowMs: Long,
        hourOfDay: Int,
        outsideTempC: Float?,
        lastPrecipitationAtMs: Long?,
        tirePressureLow: Boolean?,
        historyMatch: Boolean,
        config: EngineConfig = EngineConfig(),
    ): String {
        val parts = mutableListOf<String>()
        if (outsideTempC != null) parts += "외기온도 ${"%.0f".format(outsideTempC)}도"
        val recent = lastPrecipitationAtMs != null && nowMs - lastPrecipitationAtMs in 0..config.recentPrecipWindowMs
        if (recent) {
            val h = ((nowMs - lastPrecipitationAtMs!!) / 3_600_000L).coerceAtLeast(0)
            parts += if (h == 0L) "최근 1시간 안에 강수 기록" else "${h}시간 전 강수 기록"
        }
        val cold = outsideTempC != null && outsideTempC <= config.freezeRiskTempC
        val freezingDawn = outsideTempC != null && outsideTempC <= config.freezingTempC &&
            TimeBand.of(hourOfDay) == TimeBand.DAWN
        var verdict = when {
            cold && recent || freezingDawn -> "오늘은 결빙 주의입니다. 교량과 터널 출입구에서 특히 천천히 달려 주세요."
            cold -> "기온이 낮습니다. 그늘진 곳의 노면을 조심하세요."
            historyMatch -> "천천히 출발하세요."
            else -> "노면 위험 신호는 없습니다."
        }
        if (historyMatch) verdict = "예전에 내 차가 미끄러졌던 날과 조건이 비슷합니다. " + verdict
        if (tirePressureLow == true) parts += "타이어 공기압이 낮아졌습니다"
        return (if (parts.isEmpty()) "" else parts.joinToString(", ") + ". ") + verdict
    }
}
