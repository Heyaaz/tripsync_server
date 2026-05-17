ALTER TABLE trip_rooms ADD COLUMN room_name VARCHAR(100);

UPDATE trip_rooms
SET room_name = LEFT(TRIM(destination), 100 - CHAR_LENGTH(' 여행 계획')) || ' 여행 계획'
WHERE room_name IS NULL;

ALTER TABLE trip_rooms ALTER COLUMN room_name SET NOT NULL;
