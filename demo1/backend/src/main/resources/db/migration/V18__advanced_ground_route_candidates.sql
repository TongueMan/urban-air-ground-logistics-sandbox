ALTER TABLE demo_task_instance
  MODIFY route_artifact_id VARCHAR(40) NULL,
  ADD COLUMN planning_mode VARCHAR(16) NOT NULL DEFAULT 'BASIC' AFTER ruleset_version,
  ADD COLUMN tutorial_id VARCHAR(64) NULL AFTER planning_mode,
  ADD COLUMN selected_baseline_route_candidate_id VARCHAR(64) NULL AFTER route_artifact_id,
  ADD COLUMN baseline_selected_at TIMESTAMP(3) NULL AFTER selected_baseline_route_candidate_id;

CREATE TABLE demo_task_route_candidate (
  task_instance_id VARCHAR(40) NOT NULL,
  candidate_id VARCHAR(64) NOT NULL,
  candidate_order INT NOT NULL,
  label VARCHAR(64) NOT NULL,
  color VARCHAR(16) NOT NULL,
  route_artifact_id VARCHAR(40) NOT NULL,
  route_hash CHAR(64) NOT NULL,
  distance_meters DECIMAL(12,3) NOT NULL,
  source VARCHAR(32) NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (task_instance_id, candidate_id),
  CONSTRAINT fk_task_candidate_task FOREIGN KEY (task_instance_id) REFERENCES demo_task_instance(id) ON DELETE CASCADE,
  CONSTRAINT fk_task_candidate_artifact FOREIGN KEY (route_artifact_id) REFERENCES demo_route_artifact(id),
  UNIQUE KEY uk_task_candidate_order (task_instance_id, candidate_order),
  INDEX idx_task_candidate_artifact (route_artifact_id)
);

