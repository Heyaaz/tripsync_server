package com.tripsync.application.schedule

import com.tripsync.application.consensus.*
import com.tripsync.application.persona.PersonaValidationService
import com.tripsync.common.dto.ApiResponse
import com.tripsync.common.exception.DomainException
import com.tripsync.domain.entity.*
import com.tripsync.domain.enums.ScheduleOptionType
import com.tripsync.domain.enums.YnFlag
import com.tripsync.domain.repository.*
import com.tripsync.web.dto.GenerateScheduleDto
import com.tripsync.web.dto.RegenerateScheduleDto
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

@Service
class ScheduleService(
    private val scheduleRepository: ScheduleRepository,
    private val scheduleSlotRepository: ScheduleSlotRepository,
    private val placeQueryRepository: PlaceQueryRepository,
    private val placeRepository: PlaceRepository,
    private val consensusService: ConsensusService,
    private val generationPersistenceService: ScheduleGenerationPersistenceService,
    private val personaValidationService: PersonaValidationService,
    private val accessPolicy: ScheduleAccessPolicy,
    private val responseMapper: ScheduleResponseMapper,
) {
    private val logger = KotlinLogging.logger {}

    fun generateSchedule(roomId: Long, hostId: Long, dto: GenerateScheduleDto): ApiResponse<Map<String, Any?>> {
        val generationContext = generationPersistenceService.loadGenerationContext(roomId, hostId, dto.destination)
        val members = generationContext.members
        val placesById = generationContext.placesById

        val context = OptionContext(
            roomId = roomId,
            destination = dto.destination,
            tripDate = dto.tripStartDate ?: dto.tripDate,
            tripEndDate = dto.tripEndDate ?: dto.tripStartDate ?: dto.tripDate,
            startTime = dto.startTime,
            endTime = dto.endTime,
            members = members,
            places = generationContext.places,
        )

        val options = runBlocking { consensusService.buildScheduleOptions(context) }
        val personaValidationByType = safePersonaValidationByType(options, members, placesById)
        val savedGeneration = generationPersistenceService.saveGeneratedOptions(
            roomId = roomId,
            dto = dto,
            options = options,
            personaValidationByType = personaValidationByType,
        )

        val memberNicknames = members.associate { it.userId to it.nickname }
        return ApiResponse.ok(
            mapOf(
                "roomId" to roomId,
                "version" to savedGeneration.version,
                "options" to savedGeneration.options.map { saved ->
                    responseMapper.formatGeneratedOption(
                        scheduleId = saved.scheduleId,
                        option = saved.option,
                        personaValidation = saved.personaValidation,
                        memberNicknames = memberNicknames,
                        placesById = placesById,
                    )
                },
            )
        )
    }

    @Transactional(readOnly = true)
    fun getSchedule(scheduleId: Long, userId: Long): ApiResponse<Map<String, Any?>> {
        val schedule = accessPolicy.getActiveSchedule(scheduleId)
        accessPolicy.validateRoomMember(schedule.room.id, userId)
        return ApiResponse.ok(responseMapper.formatStoredSchedule(schedule))
    }

    @Transactional(readOnly = true)
    fun getConfirmedSchedule(roomId: Long, userId: Long): ApiResponse<Map<String, Any?>> {
        accessPolicy.validateRoomMember(roomId, userId)
        val schedule = scheduleRepository.findConfirmedByRoomId(roomId, YnFlag.N).firstOrNull()
            ?: throw DomainException(HttpStatus.NOT_FOUND, "SCHEDULE_NOT_FOUND", "확정된 일정이 없습니다.")
        return ApiResponse.ok(responseMapper.formatStoredSchedule(schedule))
    }


    @Transactional(readOnly = true)
    fun searchPlacesForSchedule(scheduleId: Long, userId: Long, query: String): ApiResponse<Map<String, Any?>> {
        val schedule = accessPolicy.getActiveSchedule(scheduleId)
        accessPolicy.validateHost(schedule.room.id, userId)
        accessPolicy.validateConfirmedSchedule(schedule)

        val usedPlaceIds = scheduleSlotRepository.findActivePlaceIdsByScheduleId(schedule.id).toSet()
        val places = placeQueryRepository.searchActivePlaces(query.trim())
            .asSequence()
            .sortedWith(
                compareByDescending<Place> { responseMapper.isDepopulationArea(it.metadataTags) }
                    .thenBy { it.name }
            )
            .take(30)
            .toList()
        val formattedPlaces = responseMapper.formatPlaces(places)
            .map { place ->
                place + mapOf("alreadyAdded" to usedPlaceIds.contains(place["id"]))
            }

        return ApiResponse.ok(
            mapOf(
                "places" to formattedPlaces,
                "query" to query.trim(),
            )
        )
    }

    @Transactional
    fun addScheduleSlot(scheduleId: Long, userId: Long, placeId: Long): ApiResponse<Map<String, Any?>> {
        val schedule = accessPolicy.getActiveSchedule(scheduleId)
        accessPolicy.validateHost(schedule.room.id, userId)
        accessPolicy.validateConfirmedSchedule(schedule)

        val place = placeRepository.findById(placeId)
            .orElseThrow { DomainException(HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND", "장소를 찾을 수 없습니다.") }
        if (place.delYn != YnFlag.N) {
            throw DomainException(HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND", "장소를 찾을 수 없습니다.")
        }

        val slots = scheduleSlotRepository.findAllByScheduleIdAndDelYn(schedule.id, YnFlag.N)
        if (slots.any { it.place.id == place.id }) {
            throw DomainException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "이미 일정에 포함된 장소입니다.")
        }

        val orderedSlots = slots.sortedBy { it.orderIndex }
        val nextOrderIndex = (orderedSlots.maxOfOrNull { it.orderIndex } ?: 0) + 1
        val startTime = orderedSlots.maxByOrNull { it.orderIndex }?.endTime ?: defaultSlotStart(schedule)
        val endTime = startTime.plus(Duration.ofHours(2))

        val newSlot = scheduleSlotRepository.save(
            ScheduleSlot(
                schedule = schedule,
                startTime = startTime,
                endTime = endTime,
                place = place,
                slotType = com.tripsync.domain.enums.SlotType.COMMON,
                targetUser = null,
                reasonAxis = com.tripsync.domain.enums.ReasonAxis.COMMON,
                reasonText = "직접 추가한 장소입니다.",
                orderIndex = nextOrderIndex,
            )
        )
        redistributeSlotsWithinScheduleWindow(schedule, orderedSlots + newSlot)

        return ApiResponse.ok(responseMapper.formatStoredSchedule(schedule))
    }

    @Transactional
    fun reorderScheduleSlots(scheduleId: Long, userId: Long, slotIds: List<Long>): ApiResponse<Map<String, Any?>> {
        val schedule = accessPolicy.getActiveSchedule(scheduleId)
        accessPolicy.validateHost(schedule.room.id, userId)

        val slots = scheduleSlotRepository.findAllByScheduleIdAndDelYn(schedule.id, YnFlag.N)
        val slotsById = slots.associateBy { it.id }
        val uniqueSlotIds = slotIds.distinct()
        if (uniqueSlotIds.size != slotIds.size || uniqueSlotIds.size != slots.size || uniqueSlotIds.any { slotsById[it] == null }) {
            throw DomainException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "일정에 포함된 모든 슬롯 ID를 중복 없이 전달해야 합니다.")
        }

        uniqueSlotIds.forEachIndexed { index, slotId ->
            slotsById.getValue(slotId).orderIndex = index + 1
        }

        return ApiResponse.ok(responseMapper.formatStoredSchedule(schedule))
    }

    @Transactional
    fun confirmSchedule(roomId: Long, hostId: Long, optionType: String): ApiResponse<Map<String, Any?>> {
        accessPolicy.validateHost(roomId, hostId)
        val type = parseOptionType(optionType)
        val latest = scheduleRepository.findTopByRoomIdAndDelYnOrderByVersionDesc(roomId, YnFlag.N)
            ?: throw DomainException(HttpStatus.NOT_FOUND, "SCHEDULE_NOT_FOUND", "확정할 일정 옵션이 없습니다.")
        val target = scheduleRepository.findByRoomIdAndDelYn(roomId, YnFlag.N)
            .firstOrNull { it.version == latest.version && it.optionType == type }
            ?: throw DomainException(HttpStatus.NOT_FOUND, "SCHEDULE_NOT_FOUND", "선택한 일정 옵션을 찾을 수 없습니다.")

        scheduleRepository.findByRoomIdAndDelYn(roomId, YnFlag.N).forEach { it.isConfirmed = false }
        target.isConfirmed = true
        target.room.status = com.tripsync.domain.enums.TripRoomStatus.COMPLETED

        return ApiResponse.ok(
            mapOf(
                "scheduleId" to target.id,
                "roomId" to roomId,
                "optionType" to target.optionType.name.lowercase(),
                "status" to "confirmed",
            )
        )
    }

    fun regenerateSchedule(scheduleId: Long, userId: Long, dto: RegenerateScheduleDto): ApiResponse<Map<String, Any?>> {
        val roomId = generationPersistenceService.getRoomIdForRegeneration(scheduleId, userId)
        val generated = generateSchedule(
            roomId,
            userId,
            GenerateScheduleDto(
                destination = dto.destination,
                tripDate = dto.tripDate,
                startTime = dto.startTime,
                endTime = dto.endTime,
                tripStartDate = dto.tripStartDate,
                tripEndDate = dto.tripEndDate,
            )
        )
        return ApiResponse.ok(
            mapOf(
                "scheduleId" to scheduleId,
                "reason" to (dto.reason ?: ""),
                "tripDate" to dto.tripDate,
                "startTime" to dto.startTime,
                "endTime" to dto.endTime,
                "regenerated" to generated.data,
                "message" to "새 버전 일정이 생성되었습니다.",
            )
        )
    }

    @Transactional(readOnly = true)
    fun getPublicShareSchedule(scheduleId: Long): ApiResponse<Map<String, Any?>> {
        val schedule = accessPolicy.getActiveSchedule(scheduleId)
        return ApiResponse.ok(responseMapper.formatPublicShareSchedule(schedule))
    }

    private fun safePersonaValidationByType(
        options: List<ScheduleOptionDraft>,
        members: List<MemberSnapshot>,
        placesById: Map<Long, Place>,
    ): Map<ScheduleOptionType, Map<String, Any>> {
        return try {
            val personaMembers = members.map {
                PersonaValidationService.MemberSnapshot(
                    userId = it.userId,
                    scores = it.scores,
                )
            }
            val validationOptions = options.map { option ->
                PersonaValidationService.ScheduleOptionForValidation(
                    optionType = option.optionType,
                    groupSatisfaction = option.groupSatisfaction,
                    slots = option.slots.map { slot ->
                        val place = placesById[slot.placeId]
                            ?: throw DomainException(HttpStatus.NOT_FOUND, "PLACE_NOT_FOUND", "장소를 찾을 수 없습니다.")
                        PersonaValidationService.ScheduleSlotForValidation(
                            slotType = slot.slotType,
                            reasonText = slot.reasonText,
                            scores = AxisScores(
                                mobility = place.mobilityScore,
                                photo = place.photoScore,
                                budget = place.budgetScore,
                                theme = place.themeScore,
                            ),
                        )
                    },
                    satisfactionByUser = option.satisfactionByUser.map {
                        PersonaValidationService.SatisfactionForValidation(
                            userId = it.userId,
                            score = it.score,
                        )
                    },
                )
            }

            personaValidationService.validateOptions(validationOptions, personaMembers)
                .filterValues { it.matchedPersonaCount > 0 }
                .mapValues { (_, result) -> result.toPersonaValidationMap() }
        } catch (error: Exception) {
            logger.warn(error) { "Persona validation failed. Schedule generation will continue with personaValidation=null." }
            emptyMap()
        }
    }

    private fun PersonaValidationService.ValidationResult.toPersonaValidationMap(): Map<String, Any> {
        return mapOf(
            "source" to source,
            "dataset" to dataset,
            "summary" to "비슷한 여행자 참고군 ${matchedPersonaCount}명 기준 수용도 ${personaAcceptanceScore}점",
            "personaAcceptanceScore" to personaAcceptanceScore,
            "matchedPersonaCount" to matchedPersonaCount,
            "matchedPersonas" to matchedPersonas.map { persona ->
                mapOf(
                    "matchedUserId" to persona.matchedUserId,
                    "similarity" to persona.similarity,
                    "personaSummary" to persona.personaSummary,
                    "scores" to mapOf(
                        "mobility" to persona.scores.mobility,
                        "photo" to persona.scores.photo,
                        "budget" to persona.scores.budget,
                        "theme" to persona.scores.theme,
                    ),
                )
            },
            "topPositiveSignals" to topPositiveSignals,
            "objectionReasons" to objectionReasons,
            "persuasionPoints" to persuasionPoints,
        )
    }

    private fun redistributeSlotsWithinScheduleWindow(schedule: Schedule, slots: List<ScheduleSlot>) {
        val activeSlots = slots.sortedBy { it.orderIndex }
        if (activeSlots.isEmpty()) return

        val window = scheduleTimeWindow(schedule)
        var cursor = window.first

        activeSlots.forEachIndexed { index, slot ->
            val remainingSlots = activeSlots.size - index
            val remainingMinutes = Duration.between(cursor, window.second).toMinutes().coerceAtLeast(remainingSlots.toLong())
            val duration = if (index == activeSlots.lastIndex) {
                remainingMinutes
            } else {
                (remainingMinutes / remainingSlots).coerceAtLeast(1)
            }
            slot.startTime = cursor
            slot.endTime = if (index == activeSlots.lastIndex) window.second else cursor.plus(Duration.ofMinutes(duration))
            cursor = slot.endTime
        }
    }

    private fun scheduleTimeWindow(schedule: Schedule): Pair<java.time.Instant, java.time.Instant> {
        val input = schedule.generationInput
        val startText = input["startTime"]?.toString()?.takeIf { it.isNotBlank() } ?: "09:00"
        val endText = input["endTime"]?.toString()?.takeIf { it.isNotBlank() } ?: "21:00"
        val tripDate = LocalDate.parse(schedule.room.tripStartDate.toString())
        val zone = ZoneId.of("Asia/Seoul")
        val startParts = startText.split(":")
        val endParts = endText.split(":")
        val start = tripDate
            .atTime(startParts.getOrNull(0)?.toIntOrNull() ?: 9, startParts.getOrNull(1)?.toIntOrNull() ?: 0)
            .atZone(zone)
            .toInstant()
        var end = tripDate
            .atTime(endParts.getOrNull(0)?.toIntOrNull() ?: 21, endParts.getOrNull(1)?.toIntOrNull() ?: 0)
            .atZone(zone)
            .toInstant()
        if (!end.isAfter(start)) {
            end = start.plus(Duration.ofHours(12))
        }
        return start to end
    }

    private fun defaultSlotStart(schedule: Schedule): java.time.Instant {
        return scheduleTimeWindow(schedule).first
    }

    private fun parseOptionType(optionType: String): ScheduleOptionType {
        return runCatching { ScheduleOptionType.valueOf(optionType.uppercase()) }
            .getOrElse { throw DomainException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "지원하지 않는 일정 옵션입니다.") }
    }
}
