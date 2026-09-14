ALTER TABLE demo_session
  ADD COLUMN planning_mode VARCHAR(16) NOT NULL DEFAULT 'BASIC' AFTER engine_version,
  ADD COLUMN baseline_route_candidate_id VARCHAR(64) NULL AFTER planning_mode,
  ADD COLUMN active_ground_route_version INT NOT NULL DEFAULT 0 AFTER baseline_route_candidate_id,
  ADD COLUMN latest_ground_command_sequence BIGINT NOT NULL DEFAULT 0 AFTER active_ground_route_version,
  ADD COLUMN ground_runtime_state_json JSON NULL AFTER latest_ground_command_sequence,
  ADD COLUMN pace_plan_json JSON NULL AFTER ground_runtime_state_json;

CREATE TABLE demo_ground_route_command (
  id VARCHAR(72) PRIMARY KEY,
  session_id VARCHAR(40) NOT NULL,
  timeline_epoch INT NOT NULL DEFAULT 0,
  command_sequence BIGINT NOT NULL,
  command_type VARCHAR(32) NOT NULL,
  source_type VARCHAR(32) NULL,
  target_id VARCHAR(64) NULL,
  requested_position_json JSON NULL,
  resolved_road_anchor_json JSON NULL,
  status VARCHAR(24) NOT NULL,
  failure_code VARCHAR(64) NULL,
  route_version INT NULL,
  requested_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  completed_at TIMESTAMP(3) NULL,
  superseded_by_command_id VARCHAR(72) NULL,
  superseded_by_rewind_id VARCHAR(64) NULL,
  response_json JSON NULL,
  CONSTRAINT fk_ground_command_session FOREIGN KEY (session_id) REFERENCES demo_session(id) ON DELETE CASCADE,
  UNIQUE KEY uk_ground_command_sequence (session_id, timeline_epoch, command_sequence),
  INDEX idx_ground_command_latest (session_id, timeline_epoch, status, command_sequence)
);

CREATE TABLE demo_ground_route_version (
  session_id VARCHAR(40) NOT NULL,
  timeline_epoch INT NOT NULL DEFAULT 0,
  route_version INT NOT NULL,
  source_command_id VARCHAR(72) NULL,
  route_hash CHAR(64) NOT NULL,
  distance_meters DECIMAL(12,3) NOT NULL,
  route_json JSON NOT NULL,
  activated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  activated_simulation_ms BIGINT NOT NULL,
  superseded_by_rewind_id VARCHAR(64) NULL,
  PRIMARY KEY (session_id, timeline_epoch, route_version),
  CONSTRAINT fk_ground_version_session FOREIGN KEY (session_id) REFERENCES demo_session(id) ON DELETE CASCADE,
  CONSTRAINT fk_ground_version_command FOREIGN KEY (source_command_id) REFERENCES demo_ground_route_command(id)
);

