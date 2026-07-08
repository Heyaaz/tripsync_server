ALTER TABLE external_popularity_metrics
    ALTER COLUMN naver_search_trend_score TYPE INTEGER USING naver_search_trend_score::INTEGER,
    ALTER COLUMN normalized_popularity_score TYPE INTEGER USING normalized_popularity_score::INTEGER;
