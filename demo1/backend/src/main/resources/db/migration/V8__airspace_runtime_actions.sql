CREATE TABLE demo_airspace_action (
  id VARCHAR(48) PRIMARY KEY,
  session_id VARCHAR(40) NOT NULL,
  volume_id VARCHAR(64) NOT NULL,
  actor_id VARCHAR(64) NOT NULL,
  action_type VARCHAR(40) NOT NULL,
  simulation_time_ms BIGINT NOT NULL,
  route_override_json JSON NULL,
  response_json JSON NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_airspace_action_session FOREIGN KEY (session_id) REFERENCES demo_session(id) ON DELETE CASCADE,
  UNIQUE KEY uk_airspace_action_once (session_id, volume_id),
  INDEX idx_airspace_action_session (session_id, created_at)
);
