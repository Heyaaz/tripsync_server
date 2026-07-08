package com.tripsync.application.popularity

import com.tripsync.domain.entity.User
import com.tripsync.domain.enums.AuthProvider
import com.tripsync.domain.enums.YnFlag
import com.tripsync.domain.repository.ExternalPopularityMetricRepository
import com.tripsync.domain.repository.PlaceRepository
import com.tripsync.infrastructure.popularity.ExternalPopularityProperties
import com.tripsync.infrastructure.popularity.GooglePlacesClient
import com.tripsync.infrastructure.popularity.NaverDataLabClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.data.domain.PageRequest
import org.springframework.web.reactive.function.client.WebClient

class ExternalPopularityBatchServiceTest {
    private val placeRepository = mock(PlaceRepository::class.java)
    private val metricRepository = mock(ExternalPopularityMetricRepository::class.java)
    private val properties = ExternalPopularityProperties(
        sync = ExternalPopularityProperties.Sync(batchLimit = 500),
    )
    private val service = ExternalPopularityBatchService(
        placeRepository = placeRepository,
        metricRepository = metricRepository,
        naverDataLabClient = NaverDataLabClient(WebClient.builder().build(), properties),
        googlePlacesClient = GooglePlacesClient(WebClient.builder().build(), properties),
        properties = properties,
    )

    @Test
    fun `manual sync passes bounded limit to repository query`() {
        val expectedPage = PageRequest.of(0, 7)
        `when`(placeRepository.findByDelYn(YnFlag.N, expectedPage)).thenReturn(emptyList())

        val response = service.syncManually(adminUser(), limit = 7).data!!

        assertEquals(0, response["scanned"])
        verify(placeRepository).findByDelYn(YnFlag.N, expectedPage)
        verify(placeRepository, never()).findByDelYn(YnFlag.N)
    }

    private fun adminUser(): User {
        return User(
            id = 1,
            nickname = "admin",
            email = "admin@example.com",
            authProvider = AuthProvider.LOCAL,
            passwordHash = "password",
            adminYn = YnFlag.Y,
        )
    }
}
