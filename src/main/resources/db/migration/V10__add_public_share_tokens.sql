ALTER TABLE schedules
    ADD COLUMN share_token VARCHAR(64);

ALTER TABLE tpti_results
    ADD COLUMN share_token VARCHAR(64);

UPDATE schedules
SET share_token = md5(random()::text || clock_timestamp()::text || id::text)
WHERE share_token IS NULL;

UPDATE tpti_results
SET share_token = md5(random()::text || clock_timestamp()::text || id::text)
WHERE share_token IS NULL;

ALTER TABLE schedules
    ALTER COLUMN share_token SET NOT NULL;

ALTER TABLE tpti_results
    ALTER COLUMN share_token SET NOT NULL;

CREATE UNIQUE INDEX uq_schedules_share_token ON schedules(share_token);
CREATE UNIQUE INDEX uq_tpti_results_share_token ON tpti_results(share_token);
