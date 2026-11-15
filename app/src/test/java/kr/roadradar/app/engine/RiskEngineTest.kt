package kr.roadradar.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskEngineTest {
    private val engine = RiskEngine()
    private val h = 3_600_000L
    private val now = 100 * h

    private fun sig(
        temp: Float? = 10f, wiper: WiperLevel? = WiperLevel.OFF, slip: Boolean? = false,
        hour: Int = 14, speed: Float? = 50f, t: Long = now,
    ) = VehicleSignals(timestampMs = t, hourOfDay = hour, outsideTempC = temp, wiper = wiper,
        slipControlActive = slip, speedKmh = speed)

    @Test fun dryWarmRoadIsNormal() {
        assertEquals(RiskLevel.NORMAL, engine.evaluate(sig(), null).level)
    }

    @Test fun wiperAboveFourDegreesIsCaution() {
        val a = engine.evaluate(sig(temp = 12f, wiper = WiperLevel.LOW), null)
        assertEquals(RiskLevel.CAUTION, a.level)
        assertEquals(listOf(Reason.WET_ROAD), a.reasons)
    }

    @Test fun slipControlIsAlwaysDanger() {
        assertEquals(RiskLevel.DANGER, engine.evaluate(sig(temp = 20f, slip = true), null).level)
    }

    @Test fun heavyWiperBelowZeroIsDanger() {
        assertEquals(RiskLevel.DANGER, engine.evaluate(sig(temp = -1f, wiper = WiperLevel.HIGH), null).level)
    }

    @Test fun coldAfterRecentPrecipitationIsAlert() {
        val a = engine.evaluate(sig(temp = 2f), lastPrecipitationAtMs = now - 3 * h)
        assertEquals(RiskLevel.ALERT, a.level)
        assertEquals(Reason.COLD_AFTER_PRECIP, a.reasons.first())
    }

    @Test fun precipitationOlderThanSixHoursDoesNotCount() {
        assertEquals(RiskLevel.NORMAL, engine.evaluate(sig(temp = 2f), now - 7 * h).level)
    }

    @Test fun freezingDawnIsAlertEvenWithoutPrecipitation() {
        assertEquals(RiskLevel.ALERT, engine.evaluate(sig(temp = -2f, hour = 6), null).level)
        assertEquals(RiskLevel.NORMAL, engine.evaluate(sig(temp = -2f, hour = 14), null).level)
    }

    @Test fun missingSignalsFallBackGracefully() {
        // 외기온도 미지원 차량: 와이퍼만으로 판단
        assertEquals(RiskLevel.CAUTION, engine.evaluate(sig(temp = null, wiper = WiperLevel.LOW), null).level)
        // 모든 신호 미지원: 정상
        assertEquals(RiskLevel.NORMAL, engine.evaluate(sig(temp = null, wiper = null, slip = null), null).level)
    }

    @Test fun personalHistoryRaisesOneLevel() {
        val memory = listOf(SlipCondition(0, outsideTempC = 1f, timeBand = TimeBand.DAWN,
            recentPrecipitation = false, speedKmh = 50f))
        val a = engine.evaluate(sig(temp = 2.5f, hour = 7), null, memory)
        assertEquals(RiskLevel.CAUTION, a.level)
        assertTrue(a.personalized)
        // 시간대가 다르면 적용하지 않음
        assertFalse(engine.evaluate(sig(temp = 2.5f, hour = 14), null, memory).personalized)
        // 기온 차이가 2℃를 넘으면 적용하지 않음
        assertFalse(engine.evaluate(sig(temp = 4f, hour = 7), null, memory).personalized)
    }

    @Test fun historyAfterPrecipitationNeedsPrecipitationNow() {
        val memory = listOf(SlipCondition(0, 5f, TimeBand.DAWN, recentPrecipitation = true, speedKmh = 50f))
        assertFalse(engine.evaluate(sig(temp = 6f, hour = 7), null, memory).personalized)
        val a = engine.evaluate(sig(temp = 6f, hour = 7, wiper = WiperLevel.LOW), null, memory)
        assertEquals(RiskLevel.ALERT, a.level)  // 주의 → 경계
        assertTrue(a.personalized)
    }

    @Test fun stateMachineRaisesImmediatelyAndLowersAfterHold() {
        val sm = RiskStateMachine()
        assertTrue(sm.update(Assessment(RiskLevel.DANGER, listOf(Reason.SLIP_CONTROL)), 0))
        assertFalse(sm.update(Assessment(RiskLevel.CAUTION, emptyList()), 1_000))
        assertEquals(RiskLevel.DANGER, sm.current.level)
        sm.update(Assessment(RiskLevel.CAUTION, emptyList()), 30_000)
        assertEquals(RiskLevel.DANGER, sm.current.level)
        sm.update(Assessment(RiskLevel.CAUTION, emptyList()), 61_000)
        assertEquals(RiskLevel.CAUTION, sm.current.level)
    }

    @Test fun tripRecorderCountsSlipAndHarshBraking() {
        val rec = TripRecorder()
        rec.record(sig(speed = 60f, t = 0), RiskLevel.NORMAL, false)
        rec.record(sig(speed = 60f, t = 10_000), RiskLevel.ALERT, true)
        rec.record(sig(speed = 40f, t = 20_000), RiskLevel.ALERT, true)
        val slip = rec.record(sig(temp = -1f, speed = 40f, slip = true, hour = 7, t = 21_000), RiskLevel.DANGER, true)
        assertNotNull(slip)
        assertEquals(TimeBand.DAWN, slip!!.timeBand)
        // 같은 미끄럼이 이어지는 동안은 새 이벤트로 세지 않음
        assertNull(rec.record(sig(temp = -1f, speed = 25f, slip = true, hour = 7, t = 22_000), RiskLevel.DANGER, true))
        val r = rec.report()
        assertEquals(1, r.slipEvents)
        assertEquals(1, r.harshBrakes)  // 40 → 25 km/h in 1s during DANGER
        assertEquals(22_000L, r.durationMs)
        assertNotNull(r.reductionPercentInRisk)
    }

    @Test fun briefingMentionsIceRiskOnColdMorningAfterRain() {
        val text = Messages.briefing(now, 6, 1f, now - 3 * h, tirePressureLow = true, historyMatch = false)
        assertTrue(text, text.contains("결빙 주의"))
        assertTrue(text, text.contains("3시간 전 강수"))
        assertTrue(text, text.contains("공기압"))
        // 강수가 10시간 전이면 '기온이 낮다'까지만 안내
        val old = Messages.briefing(now, 6, 1f, now - 10 * h, tirePressureLow = false, historyMatch = false)
        assertTrue(old, old.contains("기온이 낮습니다"))
    }
}
