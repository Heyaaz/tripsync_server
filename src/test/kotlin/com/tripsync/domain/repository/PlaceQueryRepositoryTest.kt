package com.tripsync.domain.repository

import com.tripsync.domain.entity.Place
import com.tripsync.domain.enums.YnFlag
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal

@SpringBootTest
@ActiveProfiles("test")
class PlaceQueryRepositoryTest(
    @Autowired private val placeRepository: PlaceRepository,
    @Autowired private val placeQueryRepository: PlaceQueryRepository,
) {
    @Test
    fun `schedule candidates are filtered by destination alias in database query`() {
        val suffix = System.nanoTime().toString()
        val jeonbuk = placeRepository.save(place("jeonbuk-$suffix", "전주 한옥마을 $suffix", "전라북도 전주시 완산구"))
        val chungnam = placeRepository.save(place("chungnam-$suffix", "태안 꽃지 $suffix", "충청남도 태안군"))
        val metadataRegion = placeRepository.save(
            place(
                tourApiId = "gyeongbuk-$suffix",
                name = "청도 와인터널 $suffix",
                address = "상세 주소 미분류 $suffix",
                metadataTags = mapOf("region" to "경상북도", "area" to "청도군"),
            )
        )

        val jeonbukResults = placeQueryRepository.findScheduleCandidates("전북")
        val gyeongbukResults = placeQueryRepository.findScheduleCandidates("경북")

        assertTrue(jeonbukResults.any { it.id == jeonbuk.id })
        assertFalse(jeonbukResults.any { it.id == chungnam.id })
        assertTrue(gyeongbukResults.any { it.id == metadataRegion.id })
    }

    @Test
    fun `active place search pushes keyword and deletion filters to database query`() {
        val suffix = System.nanoTime().toString()
        val active = placeRepository.save(place("active-$suffix", "꽃지 해수욕장 $suffix", "충청남도 태안군"))
        val deleted = placeRepository.save(place("deleted-$suffix", "꽃지 삭제장소 $suffix", "충청남도 태안군").also { it.delYn = YnFlag.Y })
        val unrelated = placeRepository.save(place("unrelated-$suffix", "궁남지 $suffix", "충청남도 부여군"))

        val results = placeQueryRepository.searchActivePlaces("꽃지")
        val spacedQueryResults = placeQueryRepository.searchActivePlaces("꽃지 해수욕장")

        assertTrue(results.any { it.id == active.id })
        assertTrue(spacedQueryResults.any { it.id == active.id })
        assertFalse(results.any { it.id == deleted.id })
        assertFalse(results.any { it.id == unrelated.id })
    }

    private fun place(
        tourApiId: String,
        name: String,
        address: String,
        metadataTags: Map<String, Any>? = null,
    ): Place {
        return Place(
            tourApiId = tourApiId,
            name = name,
            address = address,
            latitude = BigDecimal("36.5000000"),
            longitude = BigDecimal("126.5000000"),
            category = "tourist_attraction",
            mobilityScore = 50,
            photoScore = 80,
            budgetScore = 60,
            themeScore = 40,
            metadataTags = metadataTags,
        )
    }
}
