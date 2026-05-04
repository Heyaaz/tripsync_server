package com.tripsync.domain.repository

import com.tripsync.domain.entity.ScheduleSlot
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ScheduleSlotRepository : JpaRepository<ScheduleSlot, Long>
