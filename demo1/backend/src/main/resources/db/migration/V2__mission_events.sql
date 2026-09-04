CREATE TABLE demo_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id VARCHAR(40) NOT NULL,
  event_type VARCHAR(48) NOT NULL,
  event_time TIMESTAMP(3) NOT NULL,
  progress DECIMAL(6,2) NOT NULL,
  payload_json JSON NOT NULL,
  CONSTRAINT fk_event_session FOREIGN KEY (session_id) REFERENCES demo_session(id) ON DELETE CASCADE,
  UNIQUE KEY uk_session_event (session_id, event_type),
  INDEX idx_event_timeline (session_id, id)
);
