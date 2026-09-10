CREATE TABLE fleet_company (
  visitor_hash CHAR(64) PRIMARY KEY,
  balance_minor BIGINT NOT NULL,
  currency CHAR(3) NOT NULL DEFAULT 'CNY',
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT chk_fleet_company_balance CHECK (balance_minor >= 0)
);

CREATE TABLE fleet_asset (
  id VARCHAR(48) PRIMARY KEY,
  visitor_hash CHAR(64) NOT NULL,
  type_id VARCHAR(64) NOT NULL,
  asset_status VARCHAR(16) NOT NULL DEFAULT 'GARAGED',
  acquisition_source VARCHAR(24) NOT NULL,
  acquisition_price_minor BIGINT NOT NULL DEFAULT 0,
  initial_key VARCHAR(32) NULL,
  acquired_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_fleet_asset_company FOREIGN KEY (visitor_hash) REFERENCES fleet_company(visitor_hash) ON DELETE CASCADE,
  CONSTRAINT chk_fleet_asset_status CHECK (asset_status IN ('GARAGED', 'DEPLOYED')),
  UNIQUE KEY uk_fleet_initial_asset (visitor_hash, initial_key),
  INDEX idx_fleet_asset_owner (visitor_hash, type_id, asset_status)
);

CREATE TABLE fleet_account_ledger (
  id VARCHAR(48) PRIMARY KEY,
  visitor_hash CHAR(64) NOT NULL,
  entry_key VARCHAR(96) NOT NULL,
  entry_type VARCHAR(32) NOT NULL,
  amount_minor BIGINT NOT NULL,
  balance_after_minor BIGINT NOT NULL,
  asset_id VARCHAR(48) NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_fleet_ledger_company FOREIGN KEY (visitor_hash) REFERENCES fleet_company(visitor_hash) ON DELETE CASCADE,
  UNIQUE KEY uk_fleet_ledger_entry (visitor_hash, entry_key),
  INDEX idx_fleet_ledger_owner (visitor_hash, created_at)
);

CREATE TABLE fleet_command (
  visitor_hash CHAR(64) NOT NULL,
  command_id VARCHAR(72) NOT NULL,
  command_type VARCHAR(24) NOT NULL,
  request_fingerprint VARCHAR(180) NOT NULL,
  target_asset_id VARCHAR(48) NULL,
  amount_minor BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (visitor_hash, command_id),
  CONSTRAINT fk_fleet_command_company FOREIGN KEY (visitor_hash) REFERENCES fleet_company(visitor_hash) ON DELETE CASCADE
);
