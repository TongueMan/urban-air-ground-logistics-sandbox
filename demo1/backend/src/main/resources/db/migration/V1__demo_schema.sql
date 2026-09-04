CREATE TABLE demo_session (
  id VARCHAR(40) PRIMARY KEY,
  visitor_hash CHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  time_scale DECIMAL(4,1) NOT NULL DEFAULT 1.0,
  progress DECIMAL(6,2) NOT NULL DEFAULT 0,
  mission_phase VARCHAR(16) NOT NULL DEFAULT 'DOCKED',
  queue_position INT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  started_at TIMESTAMP(3) NULL,
  completed_at TIMESTAMP(3) NULL,
  last_seen_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_session_visitor (visitor_hash, created_at),
  INDEX idx_session_status (status, created_at)
);

CREATE TABLE demo_telemetry (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id VARCHAR(40) NOT NULL,
  device_id VARCHAR(32) NOT NULL,
  device_type VARCHAR(24) NOT NULL,
  event_time TIMESTAMP(3) NOT NULL,
  longitude DECIMAL(12,7) NOT NULL,
  latitude DECIMAL(12,7) NOT NULL,
  altitude DECIMAL(8,2) NOT NULL,
  heading DECIMAL(6,2) NOT NULL,
  metrics_json JSON NOT NULL,
  CONSTRAINT fk_telemetry_session FOREIGN KEY (session_id) REFERENCES demo_session(id) ON DELETE CASCADE,
  INDEX idx_telemetry_track (session_id, device_id, id)
);

CREATE TABLE demo_mission_definition (
  scenario_key VARCHAR(64) PRIMARY KEY,
  version_no VARCHAR(16) NOT NULL,
  coordinate_system VARCHAR(16) NOT NULL,
  definition_json JSON NOT NULL,
  source_label VARCHAR(160) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

