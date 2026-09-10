ALTER TABLE fleet_asset
  ADD COLUMN battery_basis_points DECIMAL(9,4) NOT NULL DEFAULT 10000.0000 AFTER asset_status,
  ADD COLUMN charging_from_basis_points DECIMAL(9,4) NULL AFTER battery_basis_points,
  ADD COLUMN charging_started_at DATETIME(3) NULL AFTER charging_from_basis_points,
  ADD COLUMN active_run_id VARCHAR(40) NULL AFTER charging_started_at,
  ADD COLUMN state_version BIGINT NOT NULL DEFAULT 0 AFTER active_run_id,
  ADD INDEX idx_fleet_asset_active_run (visitor_hash, active_run_id);

ALTER TABLE demo_session
  ADD COLUMN ground_asset_id VARCHAR(48) NULL AFTER task_instance_id,
  ADD COLUMN ground_service_elapsed_ms BIGINT NOT NULL DEFAULT 0 AFTER simulation_elapsed_ms,
  ADD COLUMN terminal_reason VARCHAR(64) NULL AFTER mission_phase,
  ADD INDEX idx_demo_session_ground_asset (ground_asset_id);

UPDATE fleet_asset
SET battery_basis_points = 10000.0000,
    charging_from_basis_points = NULL,
    charging_started_at = NULL,
    active_run_id = NULL,
    state_version = 0
WHERE sold_at IS NULL;
