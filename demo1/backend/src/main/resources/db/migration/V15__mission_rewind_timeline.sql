ALTER TABLE demo_session
  ADD COLUMN timeline_epoch INT NOT NULL DEFAULT 0 AFTER ground_service_elapsed_ms;

ALTER TABLE demo_telemetry
  ADD COLUMN timeline_epoch INT NOT NULL DEFAULT 0 AFTER simulation_time_ms,
  ADD COLUMN superseded_by_rewind_id VARCHAR(64) NULL AFTER timeline_epoch;

ALTER TABLE demo_event
  DROP INDEX uk_session_event_instance,
  ADD COLUMN timeline_epoch INT NOT NULL DEFAULT 0 AFTER simulation_time_ms,
  ADD COLUMN superseded_by_rewind_id VARCHAR(64) NULL AFTER timeline_epoch,
  ADD UNIQUE KEY uk_session_epoch_event (session_id, timeline_epoch, event_instance_id);

ALTER TABLE demo_signal
  DROP INDEX uk_session_signal,
  ADD COLUMN timeline_epoch INT NOT NULL DEFAULT 0 AFTER progress,
  ADD COLUMN superseded_by_rewind_id VARCHAR(64) NULL AFTER timeline_epoch,
  ADD UNIQUE KEY uk_session_epoch_signal (session_id, timeline_epoch, signal_key);

ALTER TABLE demo_workflow_command
  ADD COLUMN timeline_epoch INT NOT NULL DEFAULT 0 AFTER mission_progress,
  ADD COLUMN superseded_by_rewind_id VARCHAR(64) NULL AFTER timeline_epoch;

ALTER TABLE demo_ai_advisory
  ADD COLUMN timeline_epoch INT NOT NULL DEFAULT 0 AFTER status,
  ADD COLUMN superseded_by_rewind_id VARCHAR(64) NULL AFTER timeline_epoch;

ALTER TABLE demo_ai_decision
  ADD COLUMN timeline_epoch INT NOT NULL DEFAULT 0 AFTER execution_status,
  ADD COLUMN superseded_by_rewind_id VARCHAR(64) NULL AFTER timeline_epoch;

ALTER TABLE demo_airspace_action
  DROP INDEX uk_airspace_action_once,
  ADD COLUMN timeline_epoch INT NOT NULL DEFAULT 0 AFTER simulation_time_ms,
  ADD COLUMN superseded_by_rewind_id VARCHAR(64) NULL AFTER timeline_epoch,
  ADD UNIQUE KEY uk_airspace_action_epoch (session_id, timeline_epoch, volume_id);

ALTER TABLE demo_airspace_incursion
  DROP INDEX uk_airspace_incursion_sequence,
  ADD COLUMN timeline_epoch INT NOT NULL DEFAULT 0 AFTER exposure_ms,
  ADD COLUMN superseded_by_rewind_id VARCHAR(64) NULL AFTER timeline_epoch,
  ADD UNIQUE KEY uk_airspace_incursion_epoch (session_id, timeline_epoch, volume_id, actor_id, sequence_no);

ALTER TABLE fleet_account_ledger
  ADD COLUMN timeline_epoch INT NOT NULL DEFAULT 0 AFTER simulation_time_ms,
  ADD COLUMN superseded_by_rewind_id VARCHAR(64) NULL AFTER timeline_epoch;

CREATE TABLE demo_rewind_checkpoint (
  id VARCHAR(64) PRIMARY KEY,
  session_id VARCHAR(40) NOT NULL,
  volume_id VARCHAR(64) NOT NULL,
  timeline_epoch INT NOT NULL,
  simulation_time_ms BIGINT NOT NULL,
  mission_progress DECIMAL(6,2) NOT NULL,
  state_json JSON NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE',
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  used_at TIMESTAMP(3) NULL,
  rewind_id VARCHAR(64) NULL,
  CONSTRAINT fk_rewind_checkpoint_session FOREIGN KEY (session_id) REFERENCES demo_session(id) ON DELETE CASCADE,
  CONSTRAINT chk_rewind_checkpoint_status CHECK (status IN ('AVAILABLE', 'SUPERSEDED', 'USED')),
  UNIQUE KEY uk_rewind_checkpoint_volume (session_id, volume_id),
  INDEX idx_rewind_checkpoint_latest (session_id, status, simulation_time_ms)
);
