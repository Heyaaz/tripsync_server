-- Hot-path indexes for current repository queries.
-- Align existing V1 string flags with Hibernate's EnumType.STRING + length=1 mapping.
ALTER TABLE users
    ALTER COLUMN admin_yn TYPE CHAR(1) USING admin_yn::CHAR(1),
    ALTER COLUMN del_yn TYPE CHAR(1) USING del_yn::CHAR(1);

ALTER TABLE tpti_results
    ALTER COLUMN del_yn TYPE CHAR(1) USING del_yn::CHAR(1);

ALTER TABLE trip_rooms
    ALTER COLUMN del_yn TYPE CHAR(1) USING del_yn::CHAR(1);

ALTER TABLE room_members
    ALTER COLUMN del_yn TYPE CHAR(1) USING del_yn::CHAR(1);

ALTER TABLE room_member_profiles
    ALTER COLUMN del_yn TYPE CHAR(1) USING del_yn::CHAR(1);

ALTER TABLE conflict_maps
    ALTER COLUMN del_yn TYPE CHAR(1) USING del_yn::CHAR(1);

ALTER TABLE schedules
    ALTER COLUMN del_yn TYPE CHAR(1) USING del_yn::CHAR(1);

ALTER TABLE schedule_slots
    ALTER COLUMN del_yn TYPE CHAR(1) USING del_yn::CHAR(1);

ALTER TABLE places
    ALTER COLUMN del_yn TYPE CHAR(1) USING del_yn::CHAR(1);

ALTER TABLE satisfaction_scores
    ALTER COLUMN del_yn TYPE CHAR(1) USING del_yn::CHAR(1);

ALTER TABLE persona_vectors
    ALTER COLUMN del_yn TYPE CHAR(1) USING del_yn::CHAR(1);

ALTER TABLE trip_photos
    ALTER COLUMN del_yn TYPE CHAR(1) USING del_yn::CHAR(1);

-- V1 used SMALLINT for bounded scores, while the entities map these fields as Kotlin Int.
-- Widening preserves existing CHECK constraints and avoids Hibernate validate drift.
ALTER TABLE tpti_results
    ALTER COLUMN mobility_score TYPE INTEGER USING mobility_score::INTEGER,
    ALTER COLUMN photo_score TYPE INTEGER USING photo_score::INTEGER,
    ALTER COLUMN budget_score TYPE INTEGER USING budget_score::INTEGER,
    ALTER COLUMN theme_score TYPE INTEGER USING theme_score::INTEGER;

ALTER TABLE room_member_profiles
    ALTER COLUMN mobility_score TYPE INTEGER USING mobility_score::INTEGER,
    ALTER COLUMN photo_score TYPE INTEGER USING photo_score::INTEGER,
    ALTER COLUMN budget_score TYPE INTEGER USING budget_score::INTEGER,
    ALTER COLUMN theme_score TYPE INTEGER USING theme_score::INTEGER;

ALTER TABLE schedules
    ALTER COLUMN group_satisfaction TYPE INTEGER USING group_satisfaction::INTEGER;

ALTER TABLE places
    ALTER COLUMN mobility_score TYPE INTEGER USING mobility_score::INTEGER,
    ALTER COLUMN photo_score TYPE INTEGER USING photo_score::INTEGER,
    ALTER COLUMN budget_score TYPE INTEGER USING budget_score::INTEGER,
    ALTER COLUMN theme_score TYPE INTEGER USING theme_score::INTEGER;

ALTER TABLE satisfaction_scores
    ALTER COLUMN score TYPE INTEGER USING score::INTEGER;

ALTER TABLE persona_vectors
    ALTER COLUMN mobility TYPE INTEGER USING mobility::INTEGER,
    ALTER COLUMN photo TYPE INTEGER USING photo::INTEGER,
    ALTER COLUMN budget TYPE INTEGER USING budget::INTEGER,
    ALTER COLUMN theme TYPE INTEGER USING theme::INTEGER;

-- RoomService.getMyRooms / getRoom member counts.
CREATE INDEX IF NOT EXISTS idx_room_members_user_active_room
    ON room_members (user_id, del_yn, room_id);

CREATE INDEX IF NOT EXISTS idx_room_members_room_active
    ON room_members (room_id, del_yn);

-- RoomService/ScheduleService latest schedule summary and confirmation queries.
CREATE INDEX IF NOT EXISTS idx_schedules_room_active_version
    ON schedules (room_id, del_yn, version DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_schedules_room_active_confirmed
    ON schedules (room_id, del_yn, is_confirmed, version DESC, id DESC);

-- ScheduleReadAssembler member nickname lookup.
CREATE INDEX IF NOT EXISTS idx_room_member_profiles_room_active_created
    ON room_member_profiles (room_id, del_yn, created_at);

-- RoomService.createRoom latest TPTI result lookup.
CREATE INDEX IF NOT EXISTS idx_tpti_results_user_active_created
    ON tpti_results (user_id, del_yn, created_at DESC);

-- ConflictService latest active conflict map lookup.
CREATE INDEX IF NOT EXISTS idx_conflict_maps_room_active_created
    ON conflict_maps (room_id, del_yn, created_at DESC);

-- Keep one active conflict map per room. Older active duplicates are soft-deleted first.
WITH ranked_conflict_maps AS (
    SELECT
        id,
        row_number() OVER (
            PARTITION BY room_id
            ORDER BY created_at DESC, id DESC
        ) AS active_rank
    FROM conflict_maps
    WHERE del_yn = 'N'
)
UPDATE conflict_maps
SET del_yn = 'Y'
WHERE id IN (
    SELECT id
    FROM ranked_conflict_maps
    WHERE active_rank > 1
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_conflict_maps_active_room
    ON conflict_maps (room_id)
    WHERE del_yn = 'N';
