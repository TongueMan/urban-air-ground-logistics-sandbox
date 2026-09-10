CREATE TABLE demo_mission_definition_version (
  scenario_key VARCHAR(64) NOT NULL,
  version_no VARCHAR(16) NOT NULL,
  coordinate_system VARCHAR(16) NOT NULL,
  definition_json JSON NOT NULL,
  source_label VARCHAR(160) NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (scenario_key, version_no)
);

INSERT IGNORE INTO demo_mission_definition_version(
  scenario_key, version_no, coordinate_system, definition_json, source_label
)
SELECT scenario_key, version_no, coordinate_system, definition_json, source_label
FROM demo_mission_definition;

ALTER TABLE demo_session
  ADD COLUMN definition_id VARCHAR(64) NOT NULL DEFAULT 'urban-logistics-operation' AFTER visitor_hash,
  ADD COLUMN definition_version VARCHAR(16) NOT NULL DEFAULT '1.4.0' AFTER definition_id;

CREATE TABLE demo_signal (
  id VARCHAR(120) PRIMARY KEY,
  session_id VARCHAR(40) NOT NULL,
  signal_key VARCHAR(80) NOT NULL,
  signal_type VARCHAR(48) NOT NULL,
  severity VARCHAR(16) NOT NULL,
  status VARCHAR(24) NOT NULL,
  phase_id VARCHAR(32) NULL,
  actor_id VARCHAR(32) NULL,
  progress DECIMAL(6,2) NOT NULL,
  detected_at TIMESTAMP(3) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL,
  payload_json JSON NOT NULL,
  CONSTRAINT fk_signal_session FOREIGN KEY (session_id) REFERENCES demo_session(id) ON DELETE CASCADE,
  UNIQUE KEY uk_session_signal (session_id, signal_key),
  INDEX idx_signal_attention (session_id, status, severity)
);

CREATE TABLE demo_workflow_command (
  id VARCHAR(80) PRIMARY KEY,
  session_id VARCHAR(40) NOT NULL,
  signal_id VARCHAR(120) NOT NULL,
  command_type VARCHAR(32) NOT NULL,
  expected_signal_status VARCHAR(24) NOT NULL,
  result_signal_status VARCHAR(24) NOT NULL,
  status VARCHAR(24) NOT NULL,
  requested_at TIMESTAMP(3) NOT NULL,
  acknowledged_at TIMESTAMP(3) NOT NULL,
  message VARCHAR(240) NOT NULL,
  CONSTRAINT fk_command_session FOREIGN KEY (session_id) REFERENCES demo_session(id) ON DELETE CASCADE,
  CONSTRAINT fk_command_signal FOREIGN KEY (signal_id) REFERENCES demo_signal(id) ON DELETE CASCADE,
  INDEX idx_command_signal (session_id, signal_id, requested_at)
);
