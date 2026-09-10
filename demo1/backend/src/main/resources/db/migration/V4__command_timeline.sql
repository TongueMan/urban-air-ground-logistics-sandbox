ALTER TABLE demo_workflow_command
  ADD COLUMN mission_progress DECIMAL(6,2) NOT NULL DEFAULT 0 AFTER result_signal_status;
