CREATE TABLE demo_route_artifact (
  id VARCHAR(40) PRIMARY KEY,
  request_hash CHAR(64) NOT NULL,
  scenario_template_id VARCHAR(64) NOT NULL,
  scenario_template_version VARCHAR(16) NOT NULL,
  provider_contract_version VARCHAR(24) NOT NULL,
  source VARCHAR(32) NOT NULL,
  request_json JSON NOT NULL,
  route_json JSON NOT NULL,
  route_hash CHAR(64) NOT NULL,
  provider_status VARCHAR(32) NOT NULL,
  provider_latency_ms BIGINT NOT NULL DEFAULT 0,
  failure_code VARCHAR(64) NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_route_artifact_request (request_hash),
  INDEX idx_route_artifact_template (scenario_template_id, scenario_template_version)
);

CREATE TABLE demo_task_instance (
  id VARCHAR(40) PRIMARY KEY,
  visitor_hash CHAR(64) NOT NULL,
  scenario_template_id VARCHAR(64) NOT NULL,
  scenario_template_version VARCHAR(16) NOT NULL,
  generator_version VARCHAR(32) NOT NULL,
  ruleset_version VARCHAR(32) NOT NULL,
  seed_value VARCHAR(20) NOT NULL,
  resolved_parameters_json JSON NOT NULL,
  plan_json JSON NOT NULL,
  validation_json JSON NOT NULL,
  generation_trace_json JSON NOT NULL,
  plan_hash CHAR(64) NOT NULL,
  route_artifact_id VARCHAR(40) NOT NULL,
  lifecycle_status VARCHAR(24) NOT NULL DEFAULT 'READY',
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  expires_at TIMESTAMP(3) NOT NULL,
  started_at TIMESTAMP(3) NULL,
  CONSTRAINT fk_task_route_artifact FOREIGN KEY (route_artifact_id) REFERENCES demo_route_artifact(id),
  INDEX idx_task_visitor_history (visitor_hash, created_at),
  INDEX idx_task_preview_cleanup (lifecycle_status, expires_at)
);

ALTER TABLE demo_session
  ADD COLUMN task_instance_id VARCHAR(40) NULL AFTER definition_version,
  ADD COLUMN run_no INT NOT NULL DEFAULT 1 AFTER task_instance_id,
  ADD COLUMN engine_version VARCHAR(32) NOT NULL DEFAULT 'demo-simulator/1.0.0' AFTER run_no,
  ADD COLUMN simulation_elapsed_ms BIGINT NOT NULL DEFAULT 0 AFTER engine_version,
  ADD CONSTRAINT fk_session_task_instance FOREIGN KEY (task_instance_id) REFERENCES demo_task_instance(id),
  ADD UNIQUE KEY uk_task_run (task_instance_id, run_no);

ALTER TABLE demo_telemetry
  ADD COLUMN simulation_time_ms BIGINT NULL AFTER event_time;

ALTER TABLE demo_event
  DROP INDEX uk_session_event,
  ADD COLUMN event_instance_id VARCHAR(120) NULL AFTER session_id,
  ADD COLUMN simulation_time_ms BIGINT NULL AFTER event_time;

UPDATE demo_event SET event_instance_id=CONCAT('BASELINE-', id) WHERE event_instance_id IS NULL;

ALTER TABLE demo_event
  MODIFY event_instance_id VARCHAR(120) NOT NULL,
  ADD UNIQUE KEY uk_session_event_instance (session_id, event_instance_id);
