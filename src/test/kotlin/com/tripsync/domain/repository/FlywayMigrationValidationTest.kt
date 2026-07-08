package com.tripsync.domain.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest(
    properties = [
        "spring.profiles.active=migration-test",
        "spring.datasource.url=jdbc:tc:postgresql:15:///tripsync_migration",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.format_sql=false",
        "spring.flyway.enabled=true",
        "logging.level.org.hibernate.SQL=INFO",
    ]
)
class FlywayMigrationValidationTest(
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {
    @Test
    fun `flyway applies optimization indexes on clean postgres database`() {
        val expectedIndexes = listOf(
            "idx_room_members_user_active_room",
            "idx_room_members_room_active",
            "idx_schedules_room_active_version",
            "idx_schedules_room_active_confirmed",
            "idx_room_member_profiles_room_active_created",
            "idx_tpti_results_user_active_created",
            "idx_conflict_maps_room_active_created",
            "uq_conflict_maps_active_room",
        )

        val placeholders = expectedIndexes.joinToString(",") { "?" }
        val existingCount = jdbcTemplate.queryForObject(
            """
            select count(*)
            from pg_indexes
            where schemaname = 'public'
              and indexname in ($placeholders)
            """.trimIndent(),
            Int::class.java,
            *expectedIndexes.toTypedArray(),
        )

        assertEquals(expectedIndexes.size, existingCount)
    }
}
