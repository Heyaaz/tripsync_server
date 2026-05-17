package com.tripsync.infrastructure.tourapi

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.tripsync.domain.entity.Place
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

class TourApiClientTest {
    private val objectMapper = ObjectMapper()

    @Test
    fun `parse place creates api response snapshot every time`() {
        val client = TourApiClient(
            webClient = WebClient.create(),
            objectMapper = objectMapper,
            apiKey = "",
            baseUrl = "https://example.invalid",
            mobileOs = "ETC",
            mobileApp = "TripSyncTest",
            responseType = "json",
        )
        val first = invokeParsePlace(client, item(title = "기존 이름"))
        val second = invokeParsePlace(client, item(title = "변경 이름", modifiedTime = "20260517090000"))

        assertNotSame(first, second)
        assertEquals("기존 이름", first.name)
        assertEquals("변경 이름", second.name)
        assertEquals("20260517090000", second.metadataTags?.get("sourceModifiedTime"))
    }

    private fun invokeParsePlace(client: TourApiClient, item: JsonNode): Place {
        val method = TourApiClient::class.java.getDeclaredMethod("parsePlace", JsonNode::class.java)
        method.isAccessible = true
        return method.invoke(client, item) as Place
    }

    private fun item(title: String, modifiedTime: String = "20260516090000"): JsonNode {
        return objectMapper.readTree(
            """
            {
              "contentid": "tour-1",
              "title": "$title",
              "addr1": "충청남도 테스트군",
              "mapy": "36.5000000",
              "mapx": "126.5000000",
              "contenttypeid": "12",
              "firstimage": "https://example.invalid/image.jpg",
              "areacode": "34",
              "sigungucode": "1",
              "createdtime": "20260515090000",
              "modifiedtime": "$modifiedTime",
              "cat1": "A01",
              "cat2": "A0101",
              "cat3": "A01010100"
            }
            """.trimIndent()
        )
    }
}
