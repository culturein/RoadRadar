package kr.roadradar.app.data

import android.content.Context
import kr.roadradar.app.engine.RiskLevel
import kr.roadradar.app.engine.SlipCondition
import kr.roadradar.app.engine.TimeBand
import kr.roadradar.app.engine.TripRecorder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 차량 내부(앱 전용 저장소)에만 기록한다. 네트워크 권한이 없어 외부로 나갈 수 없다.
 * 저장 항목: 마지막 강수(와이퍼 작동) 시각, 내 미끄럼 조건, 마지막 주행 리포트.
 * 위치 정보는 저장하지 않는다(기본 버전). 데모 모드는 별도 파일을 써서 실제 기록과 섞이지 않는다.
 */
class LocalStore(context: Context, fileName: String = "roadradar.json") {
    private val file = File(context.filesDir, fileName)
    private var root: JSONObject = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }

    var lastPrecipitationAtMs: Long?
        get() = if (root.has(K_PRECIP)) root.getLong(K_PRECIP) else null
        set(v) { if (v == null) root.remove(K_PRECIP) else root.put(K_PRECIP, v); save() }

    fun slipConditions(): List<SlipCondition> {
        val arr = root.optJSONArray(K_SLIPS) ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SlipCondition(
                timestampMs = o.getLong("t"),
                outsideTempC = o.getDouble("temp").toFloat(),
                timeBand = TimeBand.valueOf(o.getString("band")),
                recentPrecipitation = o.getBoolean("precip"),
                speedKmh = if (o.has("speed")) o.getDouble("speed").toFloat() else null,
            )
        }
    }

    fun addSlip(c: SlipCondition) {
        val arr = root.optJSONArray(K_SLIPS) ?: JSONArray().also { root.put(K_SLIPS, it) }
        arr.put(JSONObject().apply {
            put("t", c.timestampMs); put("temp", c.outsideTempC.toDouble()); put("band", c.timeBand.name)
            put("precip", c.recentPrecipitation); c.speedKmh?.let { put("speed", it.toDouble()) }
        })
        // 최근 50건만 보관
        while (arr.length() > 50) arr.remove(0)
        save()
    }

    fun saveReport(r: TripRecorder.Report, endedAtMs: Long) {
        root.put(K_REPORT, JSONObject().apply {
            put("ended", endedAtMs); put("duration", r.durationMs); put("risky", r.riskyMs)
            put("slips", r.slipEvents); put("brakes", r.harshBrakes); put("steers", r.harshSteers)
            r.reductionPercentInRisk?.let { put("reduction", it) }
            put("byLevel", JSONObject().apply { r.msByLevel.forEach { (k, v) -> put(k.name, v) } })
        })
        save()
    }

    data class SavedReport(
        val endedAtMs: Long, val durationMs: Long, val riskyMs: Long, val slipEvents: Int,
        val harshBrakes: Int, val harshSteers: Int, val reductionPercent: Int?, val msByLevel: Map<RiskLevel, Long>,
    )

    fun lastReport(): SavedReport? {
        val o = root.optJSONObject(K_REPORT) ?: return null
        val by = o.optJSONObject("byLevel")
        return SavedReport(
            o.getLong("ended"), o.getLong("duration"), o.getLong("risky"), o.getInt("slips"),
            o.getInt("brakes"), o.getInt("steers"), if (o.has("reduction")) o.getInt("reduction") else null,
            RiskLevel.entries.associateWith { by?.optLong(it.name, 0L) ?: 0L },
        )
    }

    /** 설정 > 기록 전체 삭제. */
    fun deleteAll() { root = JSONObject(); file.delete() }

    private fun save() { runCatching { file.writeText(root.toString()) } }

    private companion object {
        const val K_PRECIP = "lastPrecipitationAt"
        const val K_SLIPS = "slipConditions"
        const val K_REPORT = "lastReport"
    }
}
