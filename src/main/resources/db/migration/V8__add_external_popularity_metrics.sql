CREATE TABLE external_popularity_metrics (
    id BIGSERIAL PRIMARY KEY,
    place_id BIGINT NOT NULL REFERENCES places(id) ON DELETE CASCADE,
    naver_search_trend_score SMALLINT CHECK (naver_search_trend_score BETWEEN 0 AND 100),
    google_place_id VARCHAR(255),
    google_rating NUMERIC(2, 1),
    google_user_rating_count INTEGER,
    google_photo_reference TEXT,
    normalized_popularity_score SMALLINT CHECK (normalized_popularity_score BETWEEN 0 AND 100),
    collected_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    last_error TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_external_popularity_place ON external_popularity_metrics(place_id);
CREATE INDEX idx_external_popularity_score ON external_popularity_metrics(normalized_popularity_score);
