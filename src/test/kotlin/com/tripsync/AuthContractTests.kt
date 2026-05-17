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
    fun `host can see created rooms from home entry list`() {
        val hostSession = registerSession("host-rooms@example.com", "방목록")
        val firstRoomId = createRoom(hostSession)
        val secondRoomId = createRoom(hostSession)

        mockMvc.get("/rooms/my") { cookie(hostSession) }
            .andExpect {
                status { isOk() }
                jsonPath("$.data.rooms[0].roomId") { value(secondRoomId.toInt()) }
                jsonPath("$.data.rooms[1].roomId") { value(firstRoomId.toInt()) }
                jsonPath("$.data.rooms[0].destination") { value("충청남도") }
                jsonPath("$.data.rooms[0].roomName") { value("충남 봄 여행") }
                jsonPath("$.data.rooms[0].memberCount") { value(1) }
            }
    }

    @Test
    fun `my rooms includes rooms joined as member`() {
        val hostSession = registerSession("host-member-list@example.com", "방장목록")
        val shareCode = createRoomShareCode(hostSession)
        val memberSession = registerSession("member-list@example.com", "동행목록")

        mockMvc.post("/rooms/$shareCode/join") {
            cookie(memberSession)
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            status { isCreated() }
            jsonPath("$.data.status") { value("joined") }
        }

        mockMvc.get("/rooms/my") { cookie(memberSession) }
            .andExpect {
                status { isOk() }
                jsonPath("$.data.rooms[0].shareCode") { value(shareCode) }
                jsonPath("$.data.rooms[0].memberCount") { value(2) }
            }
    }

    @Test
    fun `joined member can see room from membership-based home entry list`() {
        val hostSession = registerSession("host-member-room@example.com", "방장-member")
        val memberSession = registerSession("member-rooms@example.com", "참여자")
        val createResponse = mockMvc.post("/rooms") {
            cookie(hostSession)
            contentType = MediaType.APPLICATION_JSON
            content = """{"destination":"충청남도","tripDate":"${LocalDate.now().plusDays(7)}","roomName":"충남 봄 여행"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.data.roomId") { value(notNullValue()) }
            jsonPath("$.data.shareCode") { value(notNullValue()) }
        }.andReturn().response.contentAsString
        val roomId = Regex("""\"roomId\":(\d+)""").find(createResponse)!!.groupValues[1].toLong()
        val shareCode = Regex("""\"shareCode\":\"([^\"]+)\"""").find(createResponse)!!.groupValues[1]

        mockMvc.post("/rooms/$shareCode/join") {
            cookie(memberSession)
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            status { isCreated() }
        }

        mockMvc.get("/rooms/my") { cookie(memberSession) }
            .andExpect {
                status { isOk() }
                jsonPath("$.data.rooms[0].roomId") { value(roomId.toInt()) }
                jsonPath("$.data.rooms[0].hostUserId") { value(notNullValue()) }
                jsonPath("$.data.rooms[0].memberCount") { value(2) }
            }
    }


    @Test
    fun `room name fallback preserves suffix within database limit`() {
        val hostSession = registerSession("host-room-name-fallback@example.com", "방이름-fallback")
        val destination = "가".repeat(100)
        val expectedRoomName = "가".repeat(94) + " 여행 계획"

        mockMvc.post("/rooms") {
            cookie(hostSession)
            contentType = MediaType.APPLICATION_JSON
            content = """{"destination":"$destination","tripDate":"${LocalDate.now().plusDays(7)}"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.data.roomName") { value(expectedRoomName) }
        }
    }

    @Test
    fun `explicit room name over database limit is rejected`() {
        val hostSession = registerSession("host-room-name-too-long@example.com", "방이름-long")
        val roomName = "나".repeat(101)

        mockMvc.post("/rooms") {
            cookie(hostSession)
            contentType = MediaType.APPLICATION_JSON
            content = """{"destination":"충청남도","tripDate":"${LocalDate.now().plusDays(7)}","roomName":"$roomName"}"""
        }.andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.success") { value(false) }
            jsonPath("$.error.code") { value("INVALID_REQUEST") }
        }
    }

    @Test
    fun `oauth start sets state cookie and local callback creates session`() {
        assertOAuthRedirectPath("/rooms/new", "http://localhost:3001/rooms/new?login=success&provider=google")
    }

    @Test
    fun `oauth callback preserves join redirect path`() {
        assertOAuthRedirectPath("/join/ABC123", "http://localhost:3001/join/ABC123?login=success&provider=google")
    }

    @Test
    fun `guest join is forbidden for share code join endpoint`() {
        val hostSession = registerSession("host-join-guest-block@example.com", "호스트-금지")
        val room = mockMvc.post("/rooms") {
            cookie(hostSession)
            contentType = MediaType.APPLICATION_JSON
            content = """{"destination":"충청남도","tripDate":"${LocalDate.now().plusDays(7)}","roomName":"동행자금지"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.data.roomId") { value(notNullValue()) }
            jsonPath("$.data.shareCode") { value(notNullValue()) }
        }.andReturn().response.contentAsString
        val shareCode = Regex("""\"shareCode\":\"([^\"]+)\"""").find(room)!!.groupValues[1]

        val guestSession = mockMvc.post("/auth/guest") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"nickname":"게스트금지"}"""
        }.andReturn().response.getCookie("ts_access_token")!!

        mockMvc.post("/rooms/$shareCode/join") {
            cookie(guestSession)
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.success") { value(false) }
            jsonPath("$.error.code") { value("FORBIDDEN") }
            jsonPath("$.error.message") { value("로그인한 계정으로만 방에 참여할 수 있습니다.") }
        }

        mockMvc.post("/rooms/join/$shareCode") {
            cookie(guestSession)
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.success") { value(false) }
            jsonPath("$.error.code") { value("FORBIDDEN") }
            jsonPath("$.error.message") { value("로그인한 계정으로만 방에 참여할 수 있습니다.") }
        }
    }

    @Test
    fun `guest cannot access my rooms list`() {
        val guestSession = mockMvc.post("/auth/guest") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"nickname":"게스트홈목록"}"""
        }.andReturn().response.getCookie("ts_access_token")!!

        mockMvc.get("/rooms/my") { cookie(guestSession) }
            .andExpect {
                status { isForbidden() }
                jsonPath("$.success") { value(false) }
                jsonPath("$.error.code") { value("FORBIDDEN") }
                jsonPath("$.error.message") { value("로그인한 계정으로만 내 여행 계획을 확인할 수 있습니다.") }
            }
    }

    private fun assertOAuthRedirectPath(redirectPath: String, expectedLocation: String) {
        val start = mockMvc.get("/auth/google") {
            param("redirectPath", redirectPath)
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
            param("redirectPath", redirectPath)
        }.andExpect {
            status { is3xxRedirection() }
            cookie { exists("ts_access_token") }
            header { string("Location", expectedLocation) }
        }
    }

    private fun registerSession(email: String, nickname: String): jakarta.servlet.http.Cookie {
        val uniqueEmail = email.replace("@", "+${System.nanoTime()}@")
        return mockMvc.post("/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$uniqueEmail","password":"password123","nickname":"$nickname"}"""
        }.andExpect {
            status { isCreated() }
            cookie { exists("ts_access_token") }
        }.andReturn().response.getCookie("ts_access_token")!!
    }

    private fun createRoom(session: jakarta.servlet.http.Cookie): Long = createRoomPayload(session).roomId

    private fun createRoomShareCode(session: jakarta.servlet.http.Cookie): String = createRoomPayload(session).shareCode

    private data class CreatedRoomPayload(val roomId: Long, val shareCode: String)

    private fun createRoomPayload(session: jakarta.servlet.http.Cookie): CreatedRoomPayload {
        val response = mockMvc.post("/rooms") {
            cookie(session)
            contentType = MediaType.APPLICATION_JSON
            content = """{"destination":"충청남도","tripDate":"${LocalDate.now().plusDays(7)}","roomName":"충남 봄 여행"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.data.roomId") { value(notNullValue()) }
            jsonPath("$.data.roomName") { value("충남 봄 여행") }
            jsonPath("$.data.shareCode") { value(notNullValue()) }
        }.andReturn().response.contentAsString

        val roomId = Regex("""\"roomId\":(\d+)""").find(response)!!.groupValues[1].toLong()
        val shareCode = Regex("""\"shareCode\":\"([^\"]+)\"""").find(response)!!.groupValues[1]
        return CreatedRoomPayload(roomId, shareCode)
    }
}
