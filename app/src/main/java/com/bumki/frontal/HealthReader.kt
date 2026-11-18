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
            val total = result[StepsRecord.COUNT_TOTAL]
            if (total != null) out.put("steps", total) else out.put("stepsMissing", true)
            // 오늘 걸음이 헬스 커넥트에 마지막으로 들어온 시각 (동기화 지연 확인용)
            runCatching {
                val recs = client.readRecords(
                    ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(start, end))
                ).records
                val latest = recs.maxOfOrNull { it.endTime }
                if (latest != null) {
                    val t = latest.atZone(zone).toLocalTime()
                    out.put("stepsLatest", "%02d:%02d".format(t.hour, t.minute))
                }
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

    private fun originCounts(names: List<String>): JSONObject {
        val o = JSONObject()
        names.groupingBy { it }.eachCount().forEach { (k, v) -> o.put(k, v) }
        return o
    }
}
