-- Optimize active place filtering and keyword/destination lookup paths.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_places_del_yn_id ON places(del_yn, id);
CREATE INDEX IF NOT EXISTS idx_places_del_yn_category ON places(del_yn, category);
CREATE INDEX IF NOT EXISTS idx_places_name_trgm ON places USING GIN (lower(name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_places_address_trgm ON places USING GIN (lower(address) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_places_category_trgm ON places USING GIN (lower(category) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_places_metadata_region_trgm ON places USING GIN (lower(jsonb_extract_path_text(metadata_tags, 'region')) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_places_metadata_area_trgm ON places USING GIN (lower(jsonb_extract_path_text(metadata_tags, 'area')) gin_trgm_ops);
