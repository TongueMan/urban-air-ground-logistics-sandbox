ALTER TABLE fleet_account_ledger
  ADD COLUMN balance_before_minor BIGINT NULL AFTER amount_minor,
  ADD COLUMN assessed_amount_minor BIGINT NULL AFTER balance_after_minor,
  ADD COLUMN run_id VARCHAR(40) NULL AFTER asset_id,
  ADD COLUMN reference_id VARCHAR(96) NULL AFTER run_id,
  ADD COLUMN actor_id VARCHAR(64) NULL AFTER reference_id,
  ADD COLUMN simulation_time_ms BIGINT NULL AFTER actor_id,
  ADD COLUMN rule_version VARCHAR(40) NULL AFTER simulation_time_ms,
  ADD COLUMN metadata_json JSON NULL AFTER rule_version,
  ADD INDEX idx_fleet_ledger_run (visitor_hash, run_id, created_at);

UPDATE fleet_account_ledger
SET balance_before_minor = balance_after_minor - amount_minor
WHERE balance_before_minor IS NULL;

CREATE TABLE demo_airspace_incursion (
  id VARCHAR(64) PRIMARY KEY,
  session_id VARCHAR(40) NOT NULL,
  volume_id VARCHAR(64) NOT NULL,
  actor_id VARCHAR(64) NOT NULL,
  sequence_no INT NOT NULL,
  entry_simulation_ms BIGINT NOT NULL,
  exposure_ms BIGINT NOT NULL DEFAULT 0,
  exit_simulation_ms BIGINT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  assessed_amount_minor BIGINT NULL,
  charged_amount_minor BIGINT NULL,
  ledger_entry_key VARCHAR(96) NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_airspace_incursion_session FOREIGN KEY (session_id) REFERENCES demo_session(id) ON DELETE CASCADE,
  CONSTRAINT chk_airspace_incursion_status CHECK (status IN ('ACTIVE', 'SETTLED')),
  UNIQUE KEY uk_airspace_incursion_sequence (session_id, volume_id, actor_id, sequence_no),
  INDEX idx_airspace_incursion_active (session_id, status)
);
