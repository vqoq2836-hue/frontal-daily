package com.bumki.frontal

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/** 헬스 커넥트 권한 화면의 '개인정보 처리방침' 링크로 열리는 안내 화면 */
class PermissionsRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            textSize = 16f
            setPadding(pad, pad * 2, pad, pad)
            text = """
                범키데일리가 건강 데이터를 쓰는 방법

                • 읽는 것: 오늘 걸음 수, 어젯밤 수면 시간
                • 쓰는 곳: 오늘의 미션 점수 계산과 기록 화면
                • 저장 위치: 이 휴대폰 안에만 저장되며, 다른 곳으로 보내지 않습니다.
                • 권한은 헬스 커넥트 설정에서 언제든 끌 수 있습니다.
            """.trimIndent()
        }
        setContentView(ScrollView(this).apply { addView(text) })
    }
}
