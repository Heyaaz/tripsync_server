package com.tripsync.application.schedule

import com.tripsync.application.consensus.ScheduleOptionDraft
import com.tripsync.domain.entity.Place
import com.tripsync.domain.entity.Schedule
import com.tripsync.domain.enums.YnFlag
import com.tripsync.domain.repository.RoomMemberProfileRepository
import org.springframework.stereotype.Component

@Component
class ScheduleResponseMapper(
    private val roomMemberProfileRepository: RoomMemberProfileRepository,
) {
    fun formatGeneratedOption(
        scheduleId: Long,
        option: ScheduleOptionDraft,
        personaValidation: Map<String, Any>?,
        memberNicknames: Map<Long, String>,
        placesById: Map<Long, Place>,
    ): Map<String, Any?> = mapOf(
        "scheduleId" to scheduleId,
        "optionType" to option.optionType.name.lowercase(),
        "label" to option.label,
        "summary" to option.summary,
        "groupSatisfaction" to option.groupSatisfaction,
        "personaValidation" to personaValidation,
        "llmProvider" to option.llmProvider,
        "llmLatencyMs" to option.llmLatencyMs,
        "fallbackUsed" to option.fallbackUsed,
        "slots" to option.slots.sortedBy { it.orderIndex }.map { slot ->
            val place = placesById[slot.placeId]
            mapOf(
                "slotId" to null,
                "orderIndex" to slot.orderIndex,
                "startTime" to slot.startTime.toString(),
                "endTime" to slot.endTime.toString(),
                "slotType" to slot.slotType.name.lowercase(),
                "targetUserId" to slot.targetUserId,
                "targetNickname" to slot.targetUserId?.let { memberNicknames[it] },
                "reasonAxis" to slot.reasonAxis.name.lowercase(),
                "reasonText" to slot.reasonText,
                "reason" to slot.reasonText,
                "place" to formatPlace(place, slot.placeId, slot.placeName, slot.placeAddress),
            )
        },
        "satisfactionByUser" to option.satisfactionByUser.map {
            mapOf(
                "userId" to it.userId,
                "nickname" to memberNicknames[it.userId],
                "score" to it.score,
            )
        },
    )

    fun formatStoredSchedule(schedule: Schedule): Map<String, Any?> {
        val memberNicknames = roomMemberProfileRepository.findAllByRoomIdAndDelYn(schedule.room.id, YnFlag.N)
            .associate { it.user.id to it.user.nickname }
        return mapOf(
            "id" to schedule.id,
            "roomId" to schedule.room.id,
            "destination" to schedule.room.destination,
            "tripDate" to schedule.room.tripDate.toString(),
            "version" to schedule.version,
            "optionType" to schedule.optionType.name.lowercase(),
            "isConfirmed" to schedule.isConfirmed,
            "groupSatisfaction" to schedule.groupSatisfaction,
            "summary" to (schedule.summary ?: ""),
            "personaValidation" to schedule.personaValidation,
            "slots" to schedule.slots.filter { it.delYn == YnFlag.N }.sortedBy { it.orderIndex }.map { slot ->
                mapOf(
                    "slotId" to slot.id,
                    "orderIndex" to slot.orderIndex,
                    "startTime" to slot.startTime.toString(),
                    "endTime" to slot.endTime.toString(),
                    "slotType" to slot.slotType.name.lowercase(),
                    "targetUserId" to slot.targetUser?.id,
                    "targetNickname" to slot.targetUser?.id?.let { memberNicknames[it] },
                    "reasonAxis" to slot.reasonAxis.name.lowercase(),
                    "reasonText" to slot.reasonText,
                    "reason" to slot.reasonText,
                    "place" to formatPlace(slot.place, slot.place.id, slot.place.name, slot.place.address),
                )
            },
            "satisfactionByUser" to schedule.satisfactionScores.filter { it.delYn == YnFlag.N }.map { score ->
                mapOf(
                    "userId" to score.user.id,
                    "nickname" to memberNicknames[score.user.id],
                    "score" to score.score,
                )
            },
        )
    }

    fun formatPlace(place: Place?, id: Long, name: String, address: String): Map<String, Any?> = mapOf(
        "id" to id,
        "name" to name,
        "address" to address,
        "category" to place?.category,
        "latitude" to place?.latitude?.toDouble(),
        "longitude" to place?.longitude?.toDouble(),
        "isDepopulationArea" to isDepopulationArea(place?.metadataTags),
    )

    fun isDepopulationArea(metadataTags: Map<String, Any>?): Boolean {
        return metadataTags?.get("populationDeclineArea") == true || metadataTags?.get("regionType") == "population_decline"
    }
}
