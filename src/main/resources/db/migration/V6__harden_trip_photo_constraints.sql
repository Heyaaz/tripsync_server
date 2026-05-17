ALTER TABLE trip_photos
    ADD CONSTRAINT chk_trip_photos_status CHECK (status IN ('ACTIVE', 'HIDDEN', 'DELETED')),
    ADD CONSTRAINT chk_trip_photos_del_yn CHECK (del_yn IN ('Y', 'N')),
    ADD CONSTRAINT chk_trip_photos_file_size CHECK (file_size > 0 AND file_size <= 10485760);

CREATE INDEX idx_trip_photos_album_active ON trip_photos(schedule_id, del_yn, status, schedule_slot_id, created_at);
CREATE INDEX idx_trip_photos_slot_active ON trip_photos(schedule_slot_id, del_yn, status);
CREATE INDEX idx_trip_photos_schedule_uploader_active ON trip_photos(schedule_id, uploader_user_id, del_yn, status);
