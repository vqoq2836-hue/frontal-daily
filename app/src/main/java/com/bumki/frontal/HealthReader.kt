package com.bumki.frontal

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 헬스 커넥트에서 하루치 걸음 수와 수면 시간을 읽습니다.
 * 삼성헬스 → 설정 → 헬스 커넥트에서 걸음·수면 '쓰기'를 허용해 두어야 값이 들어옵니다.
 */
class HealthReader(private val context: Context) {

    companion object {
        private val STEPS = HealthPermission.getReadPermission(StepsRecord::class)
        private val SLEEP = HealthPermission.getReadPermission(SleepSessionRecord::class)
        val PERMISSIONS: Set<String> = setOf(STEPS, SLEEP)
        private const val SHEALTH = "com.sec.android.app.shealth"
    }

    fun status(): String = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> "available"
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "needs_update"
        else -> "unavailable"
    }

    suspend fun readDay(date: String): String {
        val out = JSONObject().put("date", date)
        if (status() != "available") return out.put("error", status()).toString()

        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        if (STEPS !in granted && SLEEP !in granted) return out.put("error", "no_permission").toString()

        val zone = ZoneId.systemDefault()
        val day = LocalDate.parse(date)

        if (STEPS in granted) {
            val start = day.atStartOfDay(zone).toInstant()
            val end = day.plusDays(1).atStartOfDay(zone).toInstant()
            val result = client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
            val agg = result[StepsRecord.COUNT_TOTAL]
            val recs = readAllSteps(client, start, end)
            // 삼성헬스가 넣은 기록이 있으면 그것만 씁니다. 삼성헬스는 폰·워치 걸음을 이미 합쳐 두기 때문에,
            // 다른 앱(구글 피트니스, 폰 자체 만보기 등)이 몇 시간 단위로 뭉쳐 넣은 기록까지 섞으면 걸음이 부풀어 납니다.
            val shRecs = recs.filter { it.metadata.dataOrigin.packageName == SHEALTH }
            if (recs.isEmpty() && agg == null) {
                out.put("stepsMissing", true)
            } else {
                val steps = when {
                    shRecs.isNotEmpty() -> dedupSteps(shRecs, start, end)
                    agg != null -> agg
                    else -> dedupSteps(recs, start, end)
                }
                out.put("steps", steps)
                out.put("stepsSource", if (shRecs.isNotEmpty()) "shealth" else if (agg != null) "healthconnect" else "all")
                if (agg != null) out.put("stepsSum", agg)
                out.put("stepsAllDedup", dedupSteps(recs, start, end))
                out.put("stepsDevices", deviceTotals(recs))
            }
            // 오늘 걸음이 헬스 커넥트에 마지막으로 들어온 시각 (동기화 지연 확인용)
            (shRecs.ifEmpty { recs }).maxOfOrNull { it.endTime }?.let {
                val t = it.atZone(zone).toLocalTime()
                out.put("stepsLatest", "%02d:%02d".format(t.hour, t.minute))
            }
        }

        if (SLEEP in granted) {
            // 전날 저녁 6시 ~ 오늘 오후 2시 사이에 시작하거나 끝난 수면 = '어젯밤 수면'
            val start = day.minusDays(1).atTime(18, 0).atZone(zone).toInstant()
            val end = day.atTime(14, 0).atZone(zone).toInstant()
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
            var minutes = 0L
            for (session in response.records) {
                val total = Duration.between(session.startTime, session.endTime).toMinutes()
                val awake = session.stages
                    .filter {
                        it.stage == SleepSessionRecord.STAGE_TYPE_AWAKE ||
                            it.stage == SleepSessionRecord.STAGE_TYPE_OUT_OF_BED
                    }
                    .sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
                minutes += (total - awake).coerceAtLeast(0)
            }
            if (response.records.isNotEmpty()) out.put("sleepMinutes", minutes)
        }
        return out.toString()
    }

    /**
     * 연결 점검: 헬스 커넥트 상태, 허용된 권한, 최근 7일 동안 어떤 앱이 걸음·수면을 넣었는지 셉니다.
     * '삼성헬스가 헬스 커넥트에 쓰고 있는지'를 확인하는 용도입니다.
     */
    suspend fun diagnose(): String {
        val out = JSONObject()
        val st = status()
        out.put("status", st)
        out.put("sdk", android.os.Build.VERSION.SDK_INT)
        if (st != "available") return out.toString()
        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        out.put("stepsPermission", STEPS in granted)
        out.put("sleepPermission", SLEEP in granted)

        val zone = ZoneId.systemDefault()
        val now = java.time.Instant.now()
        val weekAgo = LocalDate.now().minusDays(6).atStartOfDay(zone).toInstant()
        val todayStart = LocalDate.now().atStartOfDay(zone).toInstant()

        if (STEPS in granted) {
            try {
                val todayRecords = client.readRecords(
                    ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(todayStart, now))
                ).records
                val weekRecords = client.readRecords(
                    ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(weekAgo, now), pageSize = 1000)
                ).records
                out.put("stepsTodayRecords", todayRecords.size)
                out.put("stepsTodaySum", todayRecords.sumOf { it.count })
                out.put("stepsTodayByDevice", deviceTotals(todayRecords))
                out.put("stepsWeekRecords", weekRecords.size)
                out.put("stepsWeekLatest", weekRecords.maxOfOrNull { it.endTime }?.atZone(zone)?.toLocalDateTime()?.toString() ?: "")
                out.put("stepsOrigins", originCounts(weekRecords.map { it.metadata.dataOrigin.packageName }))
            } catch (e: Exception) {
                out.put("stepsError", e.javaClass.simpleName + ": " + (e.message ?: ""))
            }
        }
        if (SLEEP in granted) {
            try {
                val sleep = client.readRecords(
                    ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(weekAgo, now))
                ).records
                out.put("sleepWeekSessions", sleep.size)
                out.put("sleepWeekLatest", sleep.maxOfOrNull { it.endTime }?.atZone(zone)?.toLocalDateTime()?.toString() ?: "")
                out.put("sleepOrigins", originCounts(sleep.map { it.metadata.dataOrigin.packageName }))
            } catch (e: Exception) {
                out.put("sleepError", e.javaClass.simpleName + ": " + (e.message ?: ""))
            }
        }
        return out.toString()
    }

    private suspend fun readAllSteps(client: HealthConnectClient, start: Instant, end: Instant): List<StepsRecord> {
        val all = mutableListOf<StepsRecord>()
        var token: String? = null
        do {
            val resp = client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageSize = 1000,
                    pageToken = token
                )
            )
            all += resp.records
            token = resp.pageToken
        } while (token != null && all.size < 20000)
        return all
    }

    private fun deviceKey(r: StepsRecord): String {
        val d = r.metadata.device
        val name = listOfNotNull(d?.manufacturer, d?.model).joinToString(" ").ifBlank {
            when (d?.type) {
                androidx.health.connect.client.records.metadata.Device.TYPE_WATCH -> "워치"
                androidx.health.connect.client.records.metadata.Device.TYPE_PHONE -> "폰"
                else -> "기기 미상"
            }
        }
        return name + " (" + r.metadata.dataOrigin.packageName.substringAfterLast('.') + ")"
    }

    private fun deviceTotals(recs: List<StepsRecord>): JSONObject {
        val o = JSONObject()
        recs.groupBy { deviceKey(it) }.forEach { (k, v) -> o.put(k, v.sumOf { it.count }) }
        return o
    }

    /** 1분 칸마다 기기별 걸음을 나눠 담고, 칸마다 가장 큰 기기 값만 더합니다. */
    private fun dedupSteps(recs: List<StepsRecord>, start: Instant, end: Instant): Long {
        val perMinute = HashMap<Long, HashMap<String, Double>>()
        val s0 = start.epochSecond
        val e0 = end.epochSecond
        for (r in recs) {
            val key = deviceKey(r)
            val rs = r.startTime.epochSecond
            val re = r.endTime.epochSecond
            if (re <= rs) {
                if (rs in s0 until e0) perMinute.getOrPut(rs / 60) { HashMap() }.merge(key, r.count.toDouble()) { a, b -> a + b }
                continue
            }
            val s = maxOf(rs, s0)
            val e = minOf(re, e0)
            if (e <= s) continue
            val perSec = r.count.toDouble() / (re - rs)
            var m = s / 60
            while (m * 60 < e) {
                val ms = maxOf(s, m * 60)
                val me = minOf(e, (m + 1) * 60)
                if (me > ms) perMinute.getOrPut(m) { HashMap() }.merge(key, perSec * (me - ms)) { a, b -> a + b }
                m++
            }
        }
        return Math.round(perMinute.values.sumOf { it.values.maxOrNull() ?: 0.0 })
    }

    private fun originCounts(names: List<String>): JSONObject {
        val o = JSONObject()
        names.groupingBy { it }.eachCount().forEach { (k, v) -> o.put(k, v) }
        return o
    }
}
