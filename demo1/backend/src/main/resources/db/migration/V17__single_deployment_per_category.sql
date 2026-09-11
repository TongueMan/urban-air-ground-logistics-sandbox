-- Keep at most one deployed asset in each fleet category for existing accounts.
-- An asset bound to a running mission wins; otherwise the most recently
-- deployed asset wins. Active mission assets are never recalled by migration.
UPDATE fleet_asset asset
JOIN (
  SELECT id
  FROM (
    SELECT id,
           ROW_NUMBER() OVER (
             PARTITION BY visitor_hash,
               CASE
                 WHEN type_id IN ('tricycle','ford-f350-utility','ural-truck-vehicle-only','cybertruck-fun-size','peterbilt-379-optimus-prime') THEN 'GROUND'
                 WHEN type_id IN ('smart-city-drone','vtol-air-taxi') THEN 'AIR'
                 ELSE CONCAT('OTHER:', type_id)
               END
             ORDER BY (active_run_id IS NOT NULL) DESC, updated_at DESC, id DESC
           ) AS deployment_rank
    FROM fleet_asset
    WHERE asset_status='DEPLOYED' AND sold_at IS NULL
  ) ranked
  WHERE deployment_rank > 1
) duplicate ON duplicate.id=asset.id
SET asset.asset_status='GARAGED',
    asset.charging_from_basis_points=CASE WHEN asset.battery_basis_points < 10000 THEN asset.battery_basis_points ELSE NULL END,
    asset.charging_started_at=CASE WHEN asset.battery_basis_points < 10000 THEN CURRENT_TIMESTAMP(3) ELSE NULL END,
    asset.state_version=asset.state_version+1,
    asset.updated_at=CURRENT_TIMESTAMP(3)
WHERE asset.active_run_id IS NULL;
