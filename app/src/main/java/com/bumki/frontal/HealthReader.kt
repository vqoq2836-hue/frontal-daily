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
            out.put("steps", result[StepsRecord.COUNT_TOTAL] ?: 0L)
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
}
