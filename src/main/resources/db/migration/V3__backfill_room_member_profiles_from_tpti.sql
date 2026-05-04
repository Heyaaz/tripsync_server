-- Backfill room member profiles for users who completed TPTI outside the room-join path.
-- This restores host/OAuth users whose latest TPTI result exists but room_member_profiles was never created.

WITH latest_tpti AS (
    SELECT DISTINCT ON (user_id)
        id,
        user_id,
        mobility_score,
        photo_score,
        budget_score,
        theme_score,
        character_name
    FROM tpti_results
    WHERE del_yn = 'N'
    ORDER BY user_id, created_at DESC, id DESC
)
INSERT INTO room_member_profiles (
    room_id,
    user_id,
    tpti_result_id,
    mobility_score,
    photo_score,
    budget_score,
    theme_score,
    character_name,
    del_yn,
    created_at
)
SELECT
    rm.room_id,
    rm.user_id,
    lt.id,
    lt.mobility_score,
    lt.photo_score,
    lt.budget_score,
    lt.theme_score,
    lt.character_name,
    'N',
    NOW()
FROM room_members rm
JOIN latest_tpti lt ON lt.user_id = rm.user_id
WHERE rm.del_yn = 'N'
ON CONFLICT (room_id, user_id) DO UPDATE SET
    tpti_result_id = EXCLUDED.tpti_result_id,
    mobility_score = EXCLUDED.mobility_score,
    photo_score = EXCLUDED.photo_score,
    budget_score = EXCLUDED.budget_score,
    theme_score = EXCLUDED.theme_score,
    character_name = EXCLUDED.character_name,
    del_yn = 'N';

WITH completed_counts AS (
    SELECT room_id, COUNT(*) AS completed_count
    FROM room_member_profiles
    WHERE del_yn = 'N'
    GROUP BY room_id
)
UPDATE trip_rooms tr
SET status = CASE WHEN COALESCE(cc.completed_count, 0) >= 2 THEN 'READY' ELSE 'WAITING' END
FROM completed_counts cc
WHERE cc.room_id = tr.id
  AND tr.del_yn = 'N';
