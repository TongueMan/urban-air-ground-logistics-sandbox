import { deployedFleetAssignments } from '../fleet/fleetCatalog.mjs'

function assignedAsset(snapshot, assignment) {
  if (!assignment?.assetId) return null
  return (snapshot?.assets || []).find(asset => String(asset.assetId) === String(assignment.assetId)) || null
}

export function tutorialFleetIssue(snapshot = {}) {
  const assignments = deployedFleetAssignments(snapshot)
  const groundAsset = assignedAsset(snapshot, assignments.ground_vehicle)
  const airAsset = assignedAsset(snapshot, assignments.smart_drone)

  if (!groundAsset) return {
    code: 'GROUND_NOT_DEPLOYED',
    category: 'GROUND',
    message: '因为当前没有地面车辆出站，教程任务无法生成。',
    action: '请在下方设备实例中找到地面车辆，点击“出站”，然后重新开始教程 00。'
  }
  if (!airAsset) return {
    code: 'AIR_NOT_DEPLOYED',
    category: 'AIR',
    message: '因为当前没有空中运输设备出站，教程任务无法生成。',
    action: '请在下方设备实例中找到无人机或其他空中设备，点击“出站”，然后重新开始教程 00。'
  }
  return null
}
