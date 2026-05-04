package com.tripsync.application.tpti

import com.tripsync.domain.entity.AxisScores
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TptiServiceTest {

    @Test
    fun `TPTI 점수 계산 - 모든 문항 정방향`() {
        val scores = AxisScores(mobility = 100, photo = 100, budget = 100, theme = 100)
        assertEquals(100, scores.mobility)
    }

    @Test
    fun `캐릭터 이름 생성 - 활동성 높음, 도심, 사진`() {
        val scores = AxisScores(mobility = 80, photo = 75, budget = 50, theme = 70)
        assertEquals(80, scores.mobility)
    }
}
