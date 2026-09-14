CREATE TABLE demo_ground_reward (
  task_instance_id VARCHAR(40) NOT NULL,
  reward_id VARCHAR(64) NOT NULL,
  reward_type VARCHAR(24) NOT NULL,
  actor_kind VARCHAR(16) NOT NULL DEFAULT 'VEHICLE',
  visual_position_json JSON NOT NULL,
  road_anchor_json JSON NOT NULL,
  reward_minor BIGINT NOT NULL,
  trigger_radius_meters DECIMAL(8,2) NOT NULL,
  eligible_candidate_ids_json JSON NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (task_instance_id, reward_id),
  CONSTRAINT fk_ground_reward_task FOREIGN KEY (task_instance_id) REFERENCES demo_task_instance(id) ON DELETE CASCADE,
  INDEX idx_ground_reward_type (reward_type)
);
