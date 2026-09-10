UPDATE fleet_asset AS starter
SET starter.asset_status = 'DEPLOYED',
    starter.updated_at = CURRENT_TIMESTAMP(3)
WHERE starter.acquisition_source = 'INITIAL'
  AND starter.asset_status = 'GARAGED'
  AND starter.sold_at IS NULL
  AND (
    (starter.initial_key = 'GROUND_STARTER' AND starter.type_id = 'tricycle')
    OR (starter.initial_key = 'AIR_STARTER' AND starter.type_id = 'smart-city-drone')
  )
  AND NOT EXISTS (
    SELECT 1
    FROM fleet_command AS command_history
    WHERE command_history.visitor_hash = starter.visitor_hash
      AND command_history.target_asset_id = starter.id
      AND command_history.command_type = 'STATUS'
  );
