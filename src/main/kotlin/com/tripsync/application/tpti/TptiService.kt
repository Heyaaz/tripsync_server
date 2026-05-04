package com.tripsync.application.tpti

import com.tripsync.common.dto.ApiResponse
import com.tripsync.common.exception.DomainException
import com.tripsync.domain.entity.AxisScores
import com.tripsync.domain.entity.RoomMemberProfile
import com.tripsync.domain.entity.TptiResult
import com.tripsync.domain.entity.User
import com.tripsync.domain.enums.YnFlag
import com.tripsync.domain.repository.RoomMemberProfileRepository
import com.tripsync.domain.repository.RoomMemberQueryRepository
import com.tripsync.domain.repository.RoomMemberRepository
import com.tripsync.domain.repository.TptiResultRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TptiService(
    private val tptiResultRepository: TptiResultRepository,
    private val roomMemberQueryRepository: RoomMemberQueryRepository,
    private val roomMemberRepository: RoomMemberRepository,
    private val roomMemberProfileRepository: RoomMemberProfileRepository,
) {

    fun getQuestions(): ApiResponse<Map<String, Any>> {
        return ApiResponse.ok(
            mapOf(
                "version" to "v1",
                "questions" to QUESTIONS,
            )
        )
    }

    @Transactional
    fun submitResult(answers: List<Int>, manualAdjustments: AxisScores?, user: User): ApiResponse<Map<String, Any>> {
        validateAnswers(answers)

        val calculated = calculateScores(answers)
        val finalScores = manualAdjustments?.also { validateManualAdjustments(it) } ?: calculated
        val characterName = buildCharacterName(finalScores)

        val result = tptiResultRepository.save(
            TptiResult(
                user = user,
                mobilityScore = finalScores.mobility,
                photoScore = finalScores.photo,
                budgetScore = finalScores.budget,
                themeScore = finalScores.theme,
                characterName = characterName,
                sourceAnswers = answers,
                isManuallyAdjusted = manualAdjustments != null,
            )
        )

        syncExistingRoomProfiles(user, result)

        return ApiResponse.ok(
            mapOf(
                "resultId" to result.id,
                "userId" to user.id,
                "scores" to mapOf(
                    "mobility" to result.mobilityScore,
                    "photo" to result.photoScore,
                    "budget" to result.budgetScore,
                    "theme" to result.themeScore,
                ),
                "characterName" to characterName,
            )
        )
    }

    @Transactional(readOnly = true)
    fun getLatestResult(userId: Long, requester: User): ApiResponse<Map<String, Any>> {
        if (requester.id != userId) {
            validateSharedRoom(requester.id, userId)
        }

        val result = tptiResultRepository.findTopByUserIdAndDelYnOrderByCreatedAtDesc(userId, YnFlag.N)
            ?: throw DomainException(HttpStatus.NOT_FOUND, "TPTI_INCOMPLETE", "TPTI 결과를 찾을 수 없습니다.")

        return ApiResponse.ok(resultResponse(result))
    }

    @Transactional(readOnly = true)
    fun getPublicShareResult(resultId: Long): ApiResponse<Map<String, Any>> {
        val result = tptiResultRepository.findById(resultId).orElse(null)
        if (result == null || result.delYn != YnFlag.N) {
            throw DomainException(HttpStatus.NOT_FOUND, "RESOURCE_DELETED", "공유할 TPTI 결과를 찾을 수 없습니다.")
        }
        return ApiResponse.ok(resultResponse(result) + mapOf("nickname" to result.user.nickname))
    }

    private fun validateAnswers(answers: List<Int>) {
        if (answers.size != QUESTIONS.size) {
            throw DomainException(HttpStatus.BAD_REQUEST, "INVALID_ANSWERS", "8개 문항 모두 응답해야 합니다.")
        }
        if (answers.any { it !in 1..5 }) {
            throw DomainException(HttpStatus.BAD_REQUEST, "INVALID_ANSWERS", "TPTI 응답은 1부터 5 사이의 값이어야 합니다.")
        }
    }

    private fun validateManualAdjustments(scores: AxisScores) {
        if (listOf(scores.mobility, scores.photo, scores.budget, scores.theme).any { it !in 0..100 }) {
            throw DomainException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "수동 보정 점수는 0부터 100 사이의 값이어야 합니다.")
        }
    }

    private fun syncExistingRoomProfiles(user: User, result: TptiResult) {
        roomMemberRepository.findAllByUserIdAndDelYn(user.id, YnFlag.N).forEach { member ->
            val profile = roomMemberProfileRepository.findByRoomIdAndUserId(member.room.id, user.id)
            if (profile == null) {
                roomMemberProfileRepository.save(
                    RoomMemberProfile(
                        room = member.room,
                        user = user,
                        tptiResult = result,
                        mobilityScore = result.mobilityScore,
                        photoScore = result.photoScore,
                        budgetScore = result.budgetScore,
                        themeScore = result.themeScore,
                        characterName = result.characterName,
                    )
                )
            } else {
                profile.tptiResult = result
                profile.mobilityScore = result.mobilityScore
                profile.photoScore = result.photoScore
                profile.budgetScore = result.budgetScore
                profile.themeScore = result.themeScore
                profile.characterName = result.characterName
                profile.delYn = YnFlag.N
            }
        }
    }

    private fun resultResponse(result: TptiResult): Map<String, Any> = mapOf(
        "resultId" to result.id,
        "userId" to result.user.id,
        "scores" to mapOf(
            "mobility" to result.mobilityScore,
            "photo" to result.photoScore,
            "budget" to result.budgetScore,
            "theme" to result.themeScore,
        ),
        "characterName" to result.characterName,
        "createdAt" to result.createdAt.toString(),
    )

    private fun validateSharedRoom(requesterId: Long, targetUserId: Long) {
        val sharedRoom = roomMemberQueryRepository.findSharedRoomMember(requesterId, targetUserId)
        if (sharedRoom == null) {
            throw DomainException(HttpStatus.FORBIDDEN, "FORBIDDEN", "본인 또는 같은 방 멤버만 조회할 수 있습니다.")
        }
    }

    private fun calculateScores(answers: List<Int>): AxisScores {
        val totals = mutableMapOf(
            "mobility" to mutableListOf<Int>(),
            "photo" to mutableListOf<Int>(),
            "budget" to mutableListOf<Int>(),
            "theme" to mutableListOf<Int>(),
        )

        QUESTIONS.forEachIndexed { index, question ->
            val answer = answers[index]
            val normalized = ((answer - 1) * 100) / 4
            val score = if (question.reverseScored) 100 - normalized else normalized
            totals[question.axis]?.add(score)
        }

        return AxisScores(
            mobility = totals["mobility"]!!.average().toInt(),
            photo = totals["photo"]!!.average().toInt(),
            budget = totals["budget"]!!.average().toInt(),
            theme = totals["theme"]!!.average().toInt(),
        )
    }

    private fun buildCharacterName(scores: AxisScores): String {
        val mobility = if (scores.mobility >= 60) "뚜벅이" else "힐링"
        val theme = if (scores.theme >= 60) "도심" else "자연"
        val photo = if (scores.photo >= 60) "아티스트" else "실속형"
        return "$mobility $theme $photo"
    }

    data class QuestionDefinition(
        val id: Int,
        val axis: String,
        val reverseScored: Boolean,
        val text: String,
    )

    companion object {
        private val QUESTIONS = listOf(
            QuestionDefinition(1, "mobility", false, "여행 가면 많이 걷고 여러 장소를 도는 편이 좋다."),
            QuestionDefinition(2, "mobility", true, "여행에서는 이동보다 숙소나 카페에서 오래 쉬는 편이 좋다."),
            QuestionDefinition(3, "photo", false, "예쁜 포토스팟과 사진 기록이 일정에서 중요하다."),
            QuestionDefinition(4, "photo", true, "사진보다 현장에서 눈으로 보는 경험이 더 중요하다."),
            QuestionDefinition(5, "budget", false, "특별한 경험이라면 예산을 더 써도 괜찮다."),
            QuestionDefinition(6, "budget", true, "여행에서는 지출을 최대한 아끼는 편이 좋다."),
            QuestionDefinition(7, "theme", true, "도심 핫플보다 자연 풍경과 힐링 장소가 더 끌린다."),
            QuestionDefinition(8, "theme", false, "자연보다 도심 분위기와 핫플 탐방이 더 좋다."),
        )
    }
}
