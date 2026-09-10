ALTER TABLE fleet_asset
  ADD COLUMN sale_price_minor BIGINT NULL AFTER acquisition_price_minor,
  ADD COLUMN sold_at DATETIME(3) NULL AFTER acquired_at,
  ADD INDEX idx_fleet_asset_active_owner (visitor_hash, sold_at, type_id, asset_status);
