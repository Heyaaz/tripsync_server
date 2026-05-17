CREATE TABLE trip_photos (
    id BIGSERIAL PRIMARY KEY,
    room_id BIGINT NOT NULL REFERENCES trip_rooms(id) ON DELETE CASCADE,
    schedule_id BIGINT NOT NULL REFERENCES schedules(id) ON DELETE CASCADE,
    schedule_slot_id BIGINT NOT NULL REFERENCES schedule_slots(id) ON DELETE CASCADE,
    place_id BIGINT NOT NULL REFERENCES places(id) ON DELETE RESTRICT,
    uploader_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL,
    content BYTEA NOT NULL,
    width INT,
    height INT,
    caption VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    deleted_by_user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    deleted_at TIMESTAMPTZ,
    del_yn VARCHAR(1) NOT NULL DEFAULT 'N',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_trip_photos_status CHECK (status IN ('ACTIVE', 'HIDDEN', 'DELETED')),
    CONSTRAINT chk_trip_photos_del_yn CHECK (del_yn IN ('Y', 'N')),
    CONSTRAINT chk_trip_photos_file_size CHECK (file_size > 0 AND file_size <= 10485760)
);

CREATE INDEX idx_trip_photos_schedule_slot ON trip_photos(schedule_id, schedule_slot_id);
CREATE INDEX idx_trip_photos_room ON trip_photos(room_id);
CREATE INDEX idx_trip_photos_uploader ON trip_photos(uploader_user_id);
CREATE INDEX idx_trip_photos_album_active ON trip_photos(schedule_id, del_yn, status, schedule_slot_id, created_at);
CREATE INDEX idx_trip_photos_slot_active ON trip_photos(schedule_slot_id, del_yn, status);
CREATE INDEX idx_trip_photos_schedule_uploader_active ON trip_photos(schedule_id, uploader_user_id, del_yn, status);
