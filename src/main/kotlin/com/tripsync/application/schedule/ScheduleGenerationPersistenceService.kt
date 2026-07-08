package com.tripsync.application.schedule

import com.tripsync.domain.entity.AxisScores
import com.tripsync.application.consensus.MemberSnapshot
import com.tripsync.application.consensus.PlaceCandidate
import com.tripsync.application.consensus.ScheduleOptionDraft
import com.tripsync.common.exception.DomainException
import com.tripsync.domain.entity.Place
import com.tripsync.domain.entity.SatisfactionScore
import com.tripsync.domain.entity.Schedule
import com.tripsync.domain.entity.ScheduleSlot
import com.tripsync.domain.enums.ScheduleOptionType
import com.tripsync.domain.enums.TripRoomStatus
import com.tripsync.domain.enums.YnFlag
import com.tripsync.domain.repository.PlaceQueryRepository
import com.tripsync.domain.repository.PlaceRepository
import com.tripsync.domain.repository.RoomMemberProfileRepository
import com.tripsync.domain.repository.SatisfactionScoreRepository
import com.tripsync.domain.repository.ScheduleRepository
import com.tripsync.domain.repository.ScheduleSlotRepository
import com.tripsync.domain.repository.UserRepository
import com.tripsync.web.dto.GenerateScheduleDto
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ScheduleGenerationPersistenceService(
    private val scheduleRepository: ScheduleRepository,
    private val scheduleSlotRepository: ScheduleSlotRepository,
    private val satisfactionScoreRepository: SatisfactionScoreRepository,
    private val roomMemberProfileRepository: RoomMemberProfileRepository,
    private val placeRepository: PlaceRepository,
    private val placeQueryRepository: PlaceQueryRepository,
    private val userRepository: UserRepository,
    private val accessPolicy: ScheduleAccessPolicy,
) {
    @Transactional(readOnly = true)
    fun loadGenerationContext(roomId: Long, hostId: Long, destination: String): ScheduleGenerationContext {
        accessPolicy.validateHost(roomId, hostId)
        accessPolicy.getActiveRoom(roomId)
        val profiles = roomMemberProfileRepository.findAllByRoomIdAndDelYn(roomId, YnFlag.N)
            .sortedBy { it.createdAt }
        if (profiles.size < 2) {
            throw DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "ROOM_NOT_READY", "일정 생성 가능한 상태가 아닙니다.")
        }

        val members = profiles.mapIndexed { index, profile ->
            MemberSnapshot(
                userId = profile.user.id,
                nickname = profile.user.nickname,
                scores = AxisScores(
                    mobility = profile.mobilityScore,
                    photo = profile.photoScore,
                    budget = profile.budgetScore,
                    theme = profile.themeScore,
                ),
                joinedOrder = index,
            )
        }

        val places = placeQueryRepository.findScheduleCandidates(destination)
        val placeCandidates = places.map {
            PlaceCandidate(
                id = it.id,
                name = it.name,
                address = it.address,
                category = it.category,
                mobilityScore = it.mobilityScore,
                photoScore = it.photoScore,
                budgetScore = it.budgetScore,
                themeScore = it.themeScore,
                metadataTags = it.metadataTags,
                operatingHours = it.operatingHours,
            )
        }

        return ScheduleGenerationContext(
            roomId = roomId,
            members = members,
            places = placeCandidates,
            placesById = places.associateBy { it.id },
        )
    }

    @Transactional
    fun saveGeneratedOptions(
        roomId: Long,
        dto: GenerateScheduleDto,
        options: List<ScheduleOptionDraft>,
        personaValidationByType: Map<ScheduleOptionType, Map<String, Any>>,
    ): SavedScheduleGeneration {
        val room = accessPolicy.getActiveRoom(roomId)
        val version = (scheduleRepository.findTopByRoomIdAndDelYnOrderByVersionDescIdDesc(room.id, YnFlag.N)?.version ?: 0) + 1
        val saved = options.map { option ->
            val personaValidation = personaValidationByType[option.optionType]
            val schedule = scheduleRepository.save(
                Schedule(
                    room = room,
                    version = version,
                    optionType = option.optionType,
                    generationInput = mapOf(
                        "destination" to dto.destination,
                        "tripDate" to dto.tripDate,
                        "startTime" to dto.startTime,
                        "endTime" to dto.endTime,
                        "tripStartDate" to (dto.tripStartDate ?: dto.tripDate),
                        "tripEndDate" to (dto.tripEndDate ?: dto.tripDate),
                        "llm" to mapOf(
                            "provider" to option.llmProvider,
                            "attemptedProvider" to option.llmAttemptedProvider,
                            "latencyMs" to option.llmLatencyMs,
                            "fallbackUsed" to option.fallbackUsed,
                            "fallbackReason" to option.llmFallbackReason,
                        ),
                    ),
                    summary = option.summary,
                    groupSatisfaction = option.groupSatisfaction,
                    personaValidation = personaValidation,
                    llmProvider = option.llmProvider,
                )
            )

            option.slots.forEach { slot ->
                val place = placeRepository.findById(slot.placeId)
                    .orElseThrow { DomainException(HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND", "장소를 찾을 수 없습니다.") }
                if (place.delYn != YnFlag.N) {
                    throw DomainException(HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND", "장소를 찾을 수 없습니다.")
                }
                val targetUser = slot.targetUserId?.let { userRepository.findById(it).orElse(null) }
                scheduleSlotRepository.save(
                    ScheduleSlot(
                        schedule = schedule,
                        startTime = slot.startTime,
                        endTime = slot.endTime,
                        place = place,
                        slotType = slot.slotType,
                        targetUser = targetUser,
                        reasonAxis = slot.reasonAxis,
                        reasonText = slot.reasonText,
                        orderIndex = slot.orderIndex,
                    )
                )
            }

            option.satisfactionByUser.forEach { sat ->
                val user = userRepository.findById(sat.userId)
                    .orElseThrow { DomainException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다: ${sat.userId}") }
                satisfactionScoreRepository.save(
                    SatisfactionScore(
                        schedule = schedule,
                        user = user,
                        score = sat.score,
                        breakdown = mapOf(
                            "overall" to sat.score,
                            "byAxis" to sat.breakdown.byAxis.mapKeys { it.key.name.lowercase() },
                        ),
                    )
                )
            }

            SavedScheduleOption(
                scheduleId = schedule.id,
                option = option,
                personaValidation = personaValidation,
            )
        }
        return SavedScheduleGeneration(version = version, options = saved)
    }

    @Transactional(readOnly = true)
    fun getRoomIdForRegeneration(scheduleId: Long, userId: Long): Long {
        val existing = accessPolicy.getActiveSchedule(scheduleId)
        val roomId = existing.room.id
        accessPolicy.validateHost(roomId, userId)
        return roomId
    }
}

data class ScheduleGenerationContext(
    val roomId: Long,
    val members: List<MemberSnapshot>,
    val places: List<PlaceCandidate>,
    val placesById: Map<Long, Place>,
)

data class SavedScheduleGeneration(
    val version: Int,
    val options: List<SavedScheduleOption>,
)

data class SavedScheduleOption(
    val scheduleId: Long,
    val option: ScheduleOptionDraft,
    val personaValidation: Map<String, Any>?,
)
