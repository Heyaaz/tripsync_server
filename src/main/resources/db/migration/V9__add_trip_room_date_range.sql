ALTER TABLE trip_rooms
    ADD COLUMN trip_start_date DATE,
    ADD COLUMN trip_end_date DATE;

UPDATE trip_rooms
SET trip_start_date = trip_date,
    trip_end_date = trip_date
WHERE trip_start_date IS NULL
   OR trip_end_date IS NULL;

ALTER TABLE trip_rooms
    ALTER COLUMN trip_start_date SET NOT NULL,
    ALTER COLUMN trip_end_date SET NOT NULL;

