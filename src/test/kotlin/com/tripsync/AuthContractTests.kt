package com.tripsync

import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.net.URLDecoder
import java.time.LocalDate
import java.nio.charset.StandardCharsets

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthContractTests(
    @Autowired private val mockMvc: MockMvc,
) {
    @Test
    fun `guest session returns Nest-compatible user payload and session cookie`() {
        mockMvc.post("/auth/guest") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"nickname":"게스트"}"""
        }
            .andExpect {
                status { isCreated() }
                cookie { exists("ts_access_token") }
                jsonPath("$.success") { value(true) }
                jsonPath("$.data.user.id") { value(notNullValue()) }
                jsonPath("$.data.user.nickname") { value("게스트") }
                jsonPath("$.data.user.isGuest") { value(true) }
                jsonPath("$.data.expiresIn") { value(604800) }
            }
    }

    @Test
    fun `cookie session authenticates me and tpti submit then public share`() {
        val guest = mockMvc.post("/auth/guest") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"nickname":"테스터"}"""
        }.andReturn().response
        val session = guest.getCookie("ts_access_token")!!

        mockMvc.get("/auth/me") { cookie(session) }
            .andExpect {
                status { isOk() }
                jsonPath("$.data.user.nickname") { value("테스터") }
            }

        val submit = mockMvc.post("/tpti/submit") {
            cookie(session)
            contentType = MediaType.APPLICATION_JSON
            content = """{"answers":[1,2,3,4,5,1,2,3]}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.data.resultId") { value(notNullValue()) }
        }.andReturn().response.contentAsString

        val resultId = Regex("\\\"result_id\\\":(\\d+)|\\\"resultId\\\":(\\d+)")
            .find(submit)!!
            .groupValues
            .drop(1)
            .first { it.isNotBlank() }

        mockMvc.get("/share/tpti/$resultId")
            .andExpect {
                status { isOk() }
                jsonPath("$.data.nickname") { value("테스터") }
                jsonPath("$.data.scores.mobility") { value(notNullValue()) }
            }
    }



    @Test
    fun `tpti submit rejects out of range answers before database constraints`() {
        val session = mockMvc.post("/auth/guest") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"nickname":"검증게스트"}"""
        }.andReturn().response.getCookie("ts_access_token")!!

        mockMvc.post("/tpti/submit") {
            cookie(session)
            contentType = MediaType.APPLICATION_JSON
            content = """{"answers":[999,2,3,4,5,1,2,3]}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.success") { value(false) }
            jsonPath("$.error.code") { value("INVALID_ANSWERS") }
        }
    }

    @Test
    fun `tpti submit supports legacy manual adjustment field names and validates score range`() {
        val validSession = mockMvc.post("/auth/guest") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"nickname":"수동보정"}"""
        }.andReturn().response.getCookie("ts_access_token")!!

        mockMvc.post("/tpti/submit") {
            cookie(validSession)
            contentType = MediaType.APPLICATION_JSON
            content = """{"answers":[1,2,3,4,5,1,2,3],"manualAdjustments":{"mobilityScore":85,"photoScore":70,"budgetScore":40,"themeScore":55}}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.data.scores.mobility") { value(85) }
            jsonPath("$.data.scores.photo") { value(70) }
            jsonPath("$.data.scores.budget") { value(40) }
            jsonPath("$.data.scores.theme") { value(55) }
        }

        val invalidSession = mockMvc.post("/auth/guest") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"nickname":"수동오류"}"""
        }.andReturn().response.getCookie("ts_access_token")!!

        mockMvc.post("/tpti/submit") {
            cookie(invalidSession)
            contentType = MediaType.APPLICATION_JSON
            content = """{"answers":[1,2,3,4,5,1,2,3],"manualAdjustments":{"mobilityScore":101,"photoScore":70,"budgetScore":40,"themeScore":55}}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.success") { value(false) }
            jsonPath("$.error.code") { value("INVALID_REQUEST") }
        }
    }

    @Test
    fun `host tpti is attached to room members when submitted before or after room creation`() {
        val hostSession = registerSession("host-before@example.com", "방장-before")

        mockMvc.post("/tpti/submit") {
            cookie(hostSession)
            contentType = MediaType.APPLICATION_JSON
            content = """{"answers":[5,5,5,5,5,1,1,5]}"""
        }.andExpect {
            status { isCreated() }
        }

        val firstRoomId = createRoom(hostSession)

        mockMvc.get("/rooms/$firstRoomId/members") { cookie(hostSession) }
            .andExpect {
                status { isOk() }
                jsonPath("$.data.members[0].role") { value("host") }
                jsonPath("$.data.members[0].tptiCompleted") { value(true) }
                jsonPath("$.data.members[0].scores.mobility") { value(notNullValue()) }
                jsonPath("$.data.members[0].characterName") { value(notNullValue()) }
            }

        val afterHostSession = registerSession("host-after@example.com", "방장-after")
        val secondRoomId = createRoom(afterHostSession)

        mockMvc.post("/tpti/submit") {
            cookie(afterHostSession)
            contentType = MediaType.APPLICATION_JSON
            content = """{"answers":[5,5,5,5,5,1,1,5]}"""
        }.andExpect {
            status { isCreated() }
        }

        mockMvc.get("/rooms/$secondRoomId/members") { cookie(afterHostSession) }
            .andExpect {
                status { isOk() }
                jsonPath("$.data.members[0].role") { value("host") }
                jsonPath("$.data.members[0].tptiCompleted") { value(true) }
                jsonPath("$.data.members[0].scores.mobility") { value(notNullValue()) }
                jsonPath("$.data.members[0].characterName") { value(notNullValue()) }
            }
    }

    @Test
    fun `oauth start sets state cookie and local callback creates session`() {
        val start = mockMvc.get("/auth/google") {
            param("redirectPath", "/rooms/new")
        }.andExpect {
            status { is3xxRedirection() }
            cookie { exists("ts_oauth_state") }
            header { string("Location", startsWith("http://localhost:8080/api/auth/google/callback")) }
        }.andReturn().response

        val stateCookie = start.getCookie("ts_oauth_state")!!
        val location = start.getHeader("Location")!!
        val state = location.substringAfter("state=").substringBefore("&")
            .let { URLDecoder.decode(it, StandardCharsets.UTF_8) }

        mockMvc.get("/auth/google/callback") {
            cookie(stateCookie)
            param("code", "local-google-code")
            param("state", state)
            param("redirectPath", "/rooms/new")
        }.andExpect {
            status { is3xxRedirection() }
            cookie { exists("ts_access_token") }
            header { string("Location", "http://localhost:3001/rooms/new?login=success&provider=google") }
        }
    }

    private fun registerSession(email: String, nickname: String) = mockMvc.post("/auth/register") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"email":"$email","password":"password123","nickname":"$nickname"}"""
    }.andExpect {
        status { isCreated() }
        cookie { exists("ts_access_token") }
    }.andReturn().response.getCookie("ts_access_token")!!

    private fun createRoom(session: jakarta.servlet.http.Cookie): Long {
        val response = mockMvc.post("/rooms") {
            cookie(session)
            contentType = MediaType.APPLICATION_JSON
            content = """{"destination":"충청남도","tripDate":"${LocalDate.now().plusDays(7)}"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.data.roomId") { value(notNullValue()) }
        }.andReturn().response.contentAsString

        return Regex("""\"roomId\":(\d+)""").find(response)!!.groupValues[1].toLong()
    }
}
