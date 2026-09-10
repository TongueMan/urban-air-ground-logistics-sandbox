ALTER TABLE demo_session
  ADD COLUMN air_asset_id VARCHAR(48) NULL AFTER ground_asset_id,
  ADD INDEX idx_demo_session_air_asset (air_asset_id);
