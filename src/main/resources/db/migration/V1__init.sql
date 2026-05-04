-- PostgreSQL Migration: MySQL Prisma Schema → PostgreSQL
-- Generated for TripSync Spring Boot Migration

CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    nickname VARCHAR(50) NOT NULL,
    email VARCHAR(255),
    password_hash VARCHAR(255),
    auth_provider VARCHAR(20) NOT NULL,
    provider_user_id VARCHAR(100),
    profile_image_url TEXT,
    admin_yn VARCHAR(1) NOT NULL DEFAULT 'N',
    is_guest BOOLEAN NOT NULL DEFAULT FALSE,
    del_yn VARCHAR(1) NOT NULL DEFAULT 'N',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_users_provider UNIQUE (auth_provider, provider_user_id)
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_del_yn ON users(del_yn);

CREATE TABLE tpti_results (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    mobility_score SMALLINT NOT NULL CHECK (mobility_score BETWEEN 0 AND 100),
    photo_score SMALLINT NOT NULL CHECK (photo_score BETWEEN 0 AND 100),
    budget_score SMALLINT NOT NULL CHECK (budget_score BETWEEN 0 AND 100),
    theme_score SMALLINT NOT NULL CHECK (theme_score BETWEEN 0 AND 100),
    character_name VARCHAR(100) NOT NULL,
    source_answers JSONB NOT NULL,
    is_manually_adjusted BOOLEAN NOT NULL DEFAULT FALSE,
    del_yn VARCHAR(1) NOT NULL DEFAULT 'N',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tpti_results_user_id ON tpti_results(user_id);

CREATE TABLE trip_rooms (
    id BIGSERIAL PRIMARY KEY,
    host_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    share_code VARCHAR(12) NOT NULL UNIQUE,
    destination VARCHAR(100) NOT NULL,
    trip_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'waiting',
    del_yn VARCHAR(1) NOT NULL DEFAULT 'N',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_trip_rooms_host_user_id ON trip_rooms(host_user_id);

CREATE TABLE room_members (
    id BIGSERIAL PRIMARY KEY,
    room_id BIGINT NOT NULL REFERENCES trip_rooms(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    role VARCHAR(20) NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    del_yn VARCHAR(1) NOT NULL DEFAULT 'N',

    CONSTRAINT uq_room_members_room_user UNIQUE (room_id, user_id)
);

CREATE INDEX idx_room_members_user_id ON room_members(user_id);

CREATE TABLE room_member_profiles (
    id BIGSERIAL PRIMARY KEY,
    room_id BIGINT NOT NULL REFERENCES trip_rooms(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    tpti_result_id BIGINT NOT NULL REFERENCES tpti_results(id) ON DELETE RESTRICT,
    mobility_score SMALLINT NOT NULL CHECK (mobility_score BETWEEN 0 AND 100),
    photo_score SMALLINT NOT NULL CHECK (photo_score BETWEEN 0 AND 100),
    budget_score SMALLINT NOT NULL CHECK (budget_score BETWEEN 0 AND 100),
    theme_score SMALLINT NOT NULL CHECK (theme_score BETWEEN 0 AND 100),
    character_name VARCHAR(100) NOT NULL,
    del_yn VARCHAR(1) NOT NULL DEFAULT 'N',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_room_member_profiles_room_user UNIQUE (room_id, user_id)
);

CREATE INDEX idx_room_member_profiles_tpti_result_id ON room_member_profiles(tpti_result_id);

CREATE TABLE conflict_maps (
    id BIGSERIAL PRIMARY KEY,
    room_id BIGINT NOT NULL REFERENCES trip_rooms(id) ON DELETE CASCADE,
    common_axes JSONB NOT NULL,
    conflict_axes JSONB NOT NULL,
    summary_text TEXT NOT NULL,
    del_yn VARCHAR(1) NOT NULL DEFAULT 'N',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_conflict_maps_room_id ON conflict_maps(room_id);

CREATE TABLE schedules (
    id BIGSERIAL PRIMARY KEY,
    room_id BIGINT NOT NULL REFERENCES trip_rooms(id) ON DELETE CASCADE,
    version INT NOT NULL,
    option_type VARCHAR(20) NOT NULL,
    is_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    generation_input JSONB NOT NULL,
    summary TEXT,
    group_satisfaction SMALLINT NOT NULL CHECK (group_satisfaction BETWEEN 0 AND 100),
    persona_validation JSONB,
    llm_provider VARCHAR(50),
    del_yn VARCHAR(1) NOT NULL DEFAULT 'N',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_schedules_room_version_option UNIQUE (room_id, version, option_type)
);

CREATE INDEX idx_schedules_room_id ON schedules(room_id);

CREATE TABLE schedule_slots (
    id BIGSERIAL PRIMARY KEY,
    schedule_id BIGINT NOT NULL REFERENCES schedules(id) ON DELETE CASCADE,
    start_time TIMESTAMPTZ NOT NULL,
    end_time TIMESTAMPTZ NOT NULL,
    place_id BIGINT NOT NULL,
    slot_type VARCHAR(20) NOT NULL,
    target_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    reason_axis VARCHAR(20) NOT NULL,
    reason_text VARCHAR(100),
    order_index INT NOT NULL,
    del_yn VARCHAR(1) NOT NULL DEFAULT 'N'
);

CREATE INDEX idx_schedule_slots_schedule_id ON schedule_slots(schedule_id);
CREATE INDEX idx_schedule_slots_schedule_order ON schedule_slots(schedule_id, order_index);
CREATE INDEX idx_schedule_slots_target_user_id ON schedule_slots(target_user_id);

CREATE TABLE places (
    id BIGSERIAL PRIMARY KEY,
    tour_api_id VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    address VARCHAR(255) NOT NULL,
    latitude NUMERIC(10, 7) NOT NULL,
    longitude NUMERIC(10, 7) NOT NULL,
    category VARCHAR(100) NOT NULL,
    image_url TEXT,
    operating_hours JSONB,
    admission_fee VARCHAR(100),
    mobility_score SMALLINT NOT NULL CHECK (mobility_score BETWEEN 0 AND 100),
    photo_score SMALLINT NOT NULL CHECK (photo_score BETWEEN 0 AND 100),
    budget_score SMALLINT NOT NULL CHECK (budget_score BETWEEN 0 AND 100),
    theme_score SMALLINT NOT NULL CHECK (theme_score BETWEEN 0 AND 100),
    metadata_tags JSONB,
    del_yn VARCHAR(1) NOT NULL DEFAULT 'N',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_places_category ON places(category);
CREATE INDEX idx_places_lat_lng ON places(latitude, longitude);

CREATE TABLE satisfaction_scores (
    id BIGSERIAL PRIMARY KEY,
    schedule_id BIGINT NOT NULL REFERENCES schedules(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    score SMALLINT NOT NULL CHECK (score BETWEEN 0 AND 100),
    breakdown JSONB NOT NULL,
    del_yn VARCHAR(1) NOT NULL DEFAULT 'N',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_satisfaction_scores_schedule_user UNIQUE (schedule_id, user_id)
);

CREATE INDEX idx_satisfaction_scores_user_id ON satisfaction_scores(user_id);

CREATE TABLE persona_vectors (
    id BIGSERIAL PRIMARY KEY,
    uuid UUID NOT NULL UNIQUE,
    mobility SMALLINT NOT NULL CHECK (mobility BETWEEN 0 AND 100),
    photo SMALLINT NOT NULL CHECK (photo BETWEEN 0 AND 100),
    budget SMALLINT NOT NULL CHECK (budget BETWEEN 0 AND 100),
    theme SMALLINT NOT NULL CHECK (theme BETWEEN 0 AND 100),
    persona_summary TEXT,
    del_yn VARCHAR(1) NOT NULL DEFAULT 'N',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_persona_vectors_uuid ON persona_vectors(uuid);

ALTER TABLE schedule_slots
    ADD CONSTRAINT fk_schedule_slots_place
    FOREIGN KEY (place_id) REFERENCES places(id) ON DELETE RESTRICT;
