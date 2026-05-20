package com.tripsync.application.consensus

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import com.tripsync.common.exception.DomainException
import com.tripsync.domain.entity.AxisScores
import com.tripsync.infrastructure.llm.OpenAiClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.ZoneId

class ConsensusServiceTest {
    private val consensusService = ConsensusService(
        LlmService(
            OpenAiClient(
                webClient = WebClient.create(),
                objectMapper = ObjectMapper(),
                apiKey = "",
                model = "gpt-4o-mini",
                meterRegistry = SimpleMeterRegistry(),
            )
        )
    )

    @Test
    fun `schedule generation supports non MVP destination when matching place candidates exist`() = runBlocking {
        val options = consensusService.buildScheduleOptions(
            context(
                destination = "전북",
                startTime = "10:30",
                endTime = "15:30",
                members = members(6),
                places = places("전라북도 전주시 완산구"),
            )
        )

        assertEquals(3, options.size)
        options.forEach { option ->
            assertTrue(option.slots.isNotEmpty())
            assertEquals("10:30", option.slots.first().startTime.toSeoulTime())
            assertEquals("15:30", option.slots.last().endTime.toSeoulTime())
            assertTrue(option.slots.all { it.placeAddress.contains("전라북도") })
        }
        assertTrue(options.last().summary.contains("전북"))
        options.forEach { option ->
            assertEquals("deterministic-consensus", option.llmProvider)
            assertEquals("openai/gpt-4o-mini", option.llmAttemptedProvider)
            assertTrue(option.fallbackUsed)
            assertEquals("api_key_missing", option.llmFallbackReason)
        }
    }

    @Test
    fun `llm refinement runs recommendation options in parallel`() = runBlocking {
        val slowWebClient = WebClient.builder()
            .exchangeFunction {
                Mono.delay(Duration.ofSeconds(3))
                    .thenReturn(ClientResponse.create(HttpStatus.OK).body("{}").build())
            }
            .build()
        val parallelConsensusService = ConsensusService(
            LlmService(
                OpenAiClient(
                    webClient = slowWebClient,
                    objectMapper = ObjectMapper(),
                    apiKey = "test-key",
                    model = "gpt-test",
                    timeoutSeconds = 1,
                    meterRegistry = SimpleMeterRegistry(),
                )
            )
        )

        val startNanos = System.nanoTime()
        val options = parallelConsensusService.buildScheduleOptions(
            context(
                destination = "공주시",
                startTime = "09:00",
                endTime = "12:00",
                members = members(2),
                places = places("충청남도 공주시"),
            )
        )
        val elapsedMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis()

        assertEquals(3, options.size)
        assertTrue(options.all { it.fallbackUsed })
        assertTrue(elapsedMillis < 2_500, "three LLM refinements should wait once in parallel instead of timing out sequentially")
    }

    @Test
    fun `schedule generation uses requested time window instead of fixed nine to twenty one`() = runBlocking {
        val options = consensusService.buildScheduleOptions(
            context(
                destination = "충남",
                startTime = "08:00",
                endTime = "12:00",
                members = members(3),
                places = places("충청남도 공주시"),
            )
        )

        options.forEach { option ->
            assertEquals("08:00", option.slots.first().startTime.toSeoulTime())
            assertEquals("12:00", option.slots.last().endTime.toSeoulTime())
            assertEquals(3, option.slots.size)
        }
    }

    @Test
    fun `broad destination keeps each option inside one primary locality`() = runBlocking {
        val options = consensusService.buildScheduleOptions(
            context(
                destination = "충남",
                startTime = "09:00",
                endTime = "18:00",
                members = members(3),
                places = mixedLocalityPlaces(),
            )
        )

        options.forEach { option ->
            val localities = option.slots.map { primaryLocality(it.placeAddress) }.toSet()
            assertEquals(1, localities.size, "option ${option.optionType} crossed localities: $localities")
        }
        val optionLocalities = options.map { option -> primaryLocality(option.slots.first().placeAddress) }.toSet()
        assertEquals(3, optionLocalities.size, "three recommendation options must not collapse into the same locality")
        val optionPlaceSets = options.map { option -> option.slots.map { it.placeId }.toSet() }
        assertEquals(3, optionPlaceSets.toSet().size, "three recommendation options must not return the same place set")
    }

    @Test
    fun `same time slot does not repeat the same place across recommendation options`() = runBlocking {
        val options = consensusService.buildScheduleOptions(
            context(
                destination = "공주시",
                startTime = "09:00",
                endTime = "18:00",
                members = members(3),
                places = places("충청남도 공주시"),
            )
        )

        val slotsByOrder = options.flatMap { option -> option.slots }.groupBy { it.orderIndex }
        slotsByOrder.forEach { (orderIndex, slots) ->
            assertEquals(
                slots.size,
                slots.map { it.placeId }.toSet().size,
                "slot $orderIndex repeated the same place across recommendation options",
            )
            assertEquals(
                slots.size,
                slots.map { "${it.placeName}|${it.placeAddress}" }.toSet().size,
                "slot $orderIndex repeated a semantically identical place across recommendation options",
            )
        }
    }

    @Test
    fun `same time slot does not repeat semantically duplicated places with different ids`() = runBlocking {
        val options = consensusService.buildScheduleOptions(
            context(
                destination = "공주시",
                startTime = "09:00",
                endTime = "18:00",
                members = members(3),
                places = placesWithDuplicatedNames("충청남도 공주시"),
            )
        )

        val slotsByOrder = options.flatMap { option -> option.slots }.groupBy { it.orderIndex }
        slotsByOrder.forEach { (orderIndex, slots) ->
            assertEquals(
                slots.size,
                slots.map { normalizePlaceName(it.placeName) }.toSet().size,
                "slot $orderIndex repeated the same visible place name across recommendation options",
            )
        }
    }

    @Test
    fun `schedule generation reuses cross option candidates instead of failing when candidates are scarce`() = runBlocking {
        val options = consensusService.buildScheduleOptions(
            context(
                destination = "공주시",
                startTime = "09:00",
                endTime = "10:00",
                members = members(2),
                places = places("충청남도 공주시").take(2),
            )
        )

        assertEquals(3, options.size)
        options.forEach { option ->
            assertEquals(1, option.slots.size)
            assertTrue(option.slots.first().placeAddress.contains("공주시"))
        }
    }

    @Test
    fun `schedule generation rejects a single option when unique places are fewer than slots`() {
        val error = assertThrows(DomainException::class.java) {
            runBlocking {
                consensusService.buildScheduleOptions(
                    context(
                        destination = "공주시",
                        startTime = "09:00",
                        endTime = "12:00",
                        members = members(2),
                        places = places("충청남도 공주시").take(2),
                    )
                )
            }
        }

        assertEquals("PLACE_CANDIDATE_EMPTY", error.code)
    }

    @Test
    fun `multi day schedule creates separate slots for each requested date`() = runBlocking {
        val options = consensusService.buildScheduleOptions(
            context(
                destination = "충남",
                startTime = "09:00",
                endTime = "12:00",
                members = members(2),
                places = places("충청남도 공주시"),
                tripDate = "2026-06-01",
                tripEndDate = "2026-06-02",
            )
        )

        options.forEach { option ->
            val dates = option.slots.map { it.startTime.atZone(ZoneId.of("Asia/Seoul")).toLocalDate() }.toSet()
            assertEquals(setOf(java.time.LocalDate.parse("2026-06-01"), java.time.LocalDate.parse("2026-06-02")), dates)
        }
    }

    @Test
    fun `multi day schedule can expand to nearby localities when one locality lacks enough unique places`() = runBlocking {
        val options = consensusService.buildScheduleOptions(
            context(
                destination = "충남",
                startTime = "09:00",
                endTime = "12:00",
                members = members(2),
                places = nearbyMultiLocalityPlaces(),
                tripDate = "2026-06-01",
                tripEndDate = "2026-06-03",
            )
        )

        options.forEach { option ->
            assertEquals(9, option.slots.size)
            assertEquals(
                option.slots.size,
                option.slots.map { it.placeId }.toSet().size,
                "option ${option.optionType} repeated a place instead of expanding to nearby localities",
            )
            assertTrue(
                option.slots.map { primaryLocality(it.placeAddress) }.toSet().size > 1,
                "option ${option.optionType} should be allowed to cross nearby localities across trip days",
            )
        }
    }

    @Test
    fun `schedule generation reports missing candidates instead of rejecting non Chungnam destination`() {
        val error = assertThrows(DomainException::class.java) {
            runBlocking {
                consensusService.buildScheduleOptions(
                    context(
                        destination = "부산",
                        startTime = "09:00",
                        endTime = "21:00",
                        members = members(2),
                        places = places("충청남도 태안군"),
                    )
                )
            }
        }

        assertEquals("PLACE_CANDIDATE_EMPTY", error.code)
    }

    private fun context(
        destination: String,
        startTime: String,
        endTime: String,
        members: List<MemberSnapshot>,
        places: List<PlaceCandidate>,
        tripDate: String = "2026-06-01",
        tripEndDate: String? = null,
    ) = OptionContext(
        roomId = 1L,
        destination = destination,
        tripDate = tripDate,
        tripEndDate = tripEndDate,
        startTime = startTime,
        endTime = endTime,
        members = members,
        places = places,
    )

    private fun members(count: Int): List<MemberSnapshot> {
        return (1..count).map { index ->
            MemberSnapshot(
                userId = index.toLong(),
                nickname = "member-$index",
                scores = AxisScores(
                    mobility = if (index % 2 == 0) 90 else 20,
                    photo = 40 + index,
                    budget = if (index % 3 == 0) 85 else 35,
                    theme = 55,
                ),
                joinedOrder = index - 1,
            )
        }
    }

    private fun places(addressPrefix: String): List<PlaceCandidate> {
        val categories = listOf("tourist_attraction", "restaurant", "cultural_facility", "leisure_sports", "shopping", "festival", "accommodation")
        return (1..10).map { index ->
            PlaceCandidate(
                id = index.toLong(),
                name = "place-$index",
                address = "$addressPrefix $index",
                latitude = 36.0 + index * 0.001,
                longitude = 127.0 + index * 0.001,
                category = categories[(index - 1) % categories.size],
                mobilityScore = 30 + index * 3,
                photoScore = 80 - index,
                budgetScore = 50 + index,
                themeScore = 45 + index,
                metadataTags = mapOf("hiddenGem" to (index % 2 == 0)),
                operatingHours = mapOf("status" to "always"),
            )
        }
    }

    private fun placesWithDuplicatedNames(addressPrefix: String): List<PlaceCandidate> {
        val base = places(addressPrefix).toMutableList()
        base.addAll(
            listOf(
                base[0].copyCandidate(id = 101, address = "$addressPrefix 상세주소-중복-1"),
                base[1].copyCandidate(id = 102, address = "$addressPrefix 상세주소-중복-2"),
                base[2].copyCandidate(id = 103, address = "$addressPrefix 상세주소-중복-3"),
            )
        )
        return base
    }

    private fun PlaceCandidate.copyCandidate(id: Long, address: String): PlaceCandidate {
        return copy(
            id = id,
            address = address,
            latitude = (latitude ?: 36.0) + id * 0.00001,
            longitude = (longitude ?: 127.0) + id * 0.00001,
        )
    }

    private fun nearbyMultiLocalityPlaces(): List<PlaceCandidate> {
        val localities = listOf(
            Triple("충청남도 예산군 예산읍", 36.68, 126.85),
            Triple("충청남도 아산시 온천동", 36.78, 127.00),
            Triple("충청남도 천안시 동남구", 36.81, 127.15),
        )
        val categories = listOf("tourist_attraction", "restaurant", "cultural_facility")
        return localities.flatMapIndexed { localityIndex, (address, baseLat, baseLon) ->
            (1..3).map { index ->
                PlaceCandidate(
                    id = (localityIndex * 100 + index).toLong(),
                    name = "nearby-$localityIndex-$index",
                    address = "$address 테스트로 $index",
                    latitude = baseLat + index * 0.001,
                    longitude = baseLon + index * 0.001,
                    category = categories[(index - 1) % categories.size],
                    mobilityScore = 60 + index,
                    photoScore = 60 + index,
                    budgetScore = 60 + index,
                    themeScore = 60 + index,
                    metadataTags = mapOf("hiddenGem" to (index == 2)),
                    operatingHours = mapOf("status" to "always"),
                    externalPopularityScore = if (index == 1) 80 else 35,
                    externalSignalConfidence = 80,
                    isRegionalBenefit = index != 1,
                )
            }
        }
    }

    private fun mixedLocalityPlaces(): List<PlaceCandidate> {
        val localities = listOf(
            "충청남도 서천군 장항읍",
            "충청남도 금산군 금산읍",
            "충청남도 예산군 덕산면",
        )
        return localities.flatMapIndexed { localityIndex, locality ->
            (1..8).map { index ->
                PlaceCandidate(
                    id = (localityIndex * 100 + index).toLong(),
                    name = "place-$localityIndex-$index",
                    address = "$locality 테스트로 $index",
                    latitude = 36.0 + localityIndex * 0.4 + index * 0.001,
                    longitude = 126.5 + localityIndex * 0.4 + index * 0.001,
                    category = if (index % 4 == 0) "restaurant" else "tourist_attraction",
                    mobilityScore = 55,
                    photoScore = 55,
                    budgetScore = 55,
                    themeScore = 55,
                    metadataTags = mapOf("hiddenGem" to (index % 3 == 0)),
                    operatingHours = mapOf("status" to "always"),
                    externalPopularityScore = when (index) {
                        1 -> 85
                        2, 3 -> 30
                        else -> 50
                    },
                    externalSignalConfidence = 90,
                    isRegionalBenefit = index in listOf(2, 3),
                )
            }
        }
    }

    private fun primaryLocality(address: String): String {
        return Regex("([가-힣]+(?:시|군))").find(address)?.value ?: address
    }

    private fun normalizePlaceName(name: String): String {
        return name.trim().lowercase().replace(Regex("[\\s\\p{Punct}]+"), "")
    }

    private fun java.time.Instant.toSeoulTime(): String {
        return atZone(ZoneId.of("Asia/Seoul")).toLocalTime().toString().take(5)
    }
}
