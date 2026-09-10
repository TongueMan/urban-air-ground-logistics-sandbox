CREATE TABLE demo_ai_advisory (
  id VARCHAR(80) PRIMARY KEY,
  session_id VARCHAR(40) NOT NULL,
  signal_id VARCHAR(120) NOT NULL,
  provider VARCHAR(24) NOT NULL,
  model_name VARCHAR(80) NOT NULL,
  status VARCHAR(32) NOT NULL,
  requested_at TIMESTAMP(3) NOT NULL,
  completed_at TIMESTAMP(3) NOT NULL,
  input_snapshot_json JSON NOT NULL,
  response_json JSON NOT NULL,
  CONSTRAINT fk_advisory_session FOREIGN KEY (session_id) REFERENCES demo_session(id) ON DELETE CASCADE,
  CONSTRAINT fk_advisory_signal FOREIGN KEY (signal_id) REFERENCES demo_signal(id) ON DELETE CASCADE,
  INDEX idx_advisory_signal (session_id, signal_id, requested_at)
);
