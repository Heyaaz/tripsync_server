package com.tripsync.domain.entity

import com.tripsync.domain.enums.YnFlag
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant

@MappedSuperclass
abstract class BaseEntity {
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @Enumerated(EnumType.STRING)
    @Column(name = "del_yn", nullable = false, length = 1)
    var delYn: YnFlag = YnFlag.N
}
