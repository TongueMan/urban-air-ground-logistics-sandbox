CREATE TABLE demo_tutorial_progress (
  visitor_hash CHAR(64) NOT NULL,
  tutorial_id VARCHAR(64) NOT NULL,
  tutorial_version VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL,
  source VARCHAR(24) NOT NULL,
  task_instance_id VARCHAR(40) NULL,
  run_id VARCHAR(40) NULL,
  evidence_json JSON NULL,
  completed_at TIMESTAMP(3) NULL,
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (visitor_hash, tutorial_id, tutorial_version),
  CONSTRAINT fk_tutorial_task FOREIGN KEY (task_instance_id) REFERENCES demo_task_instance(id) ON DELETE SET NULL,
  CONSTRAINT fk_tutorial_run FOREIGN KEY (run_id) REFERENCES demo_session(id) ON DELETE SET NULL,
  INDEX idx_tutorial_unlock (visitor_hash, status, tutorial_id)
);

