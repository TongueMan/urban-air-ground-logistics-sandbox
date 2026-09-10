import { deployedFleetAssignments } from '../fleet/fleetCatalog.mjs'

function assignedAsset(snapshot, assignment) {
  if (!assignment?.assetId) return null
  return (snapshot?.assets || []).find(asset => String(asset.assetId) === String(assignment.assetId)) || null
}

function batteryDepleted(asset) {
  const battery = Number(asset?.batteryPercent)
  return Number.isFinite(battery) && battery <= 0
}

export function tutorialFleetIssue(snapshot = {}, preview = null) {
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
  if (batteryDepleted(groundAsset)) return {
    code: 'GROUND_BATTERY_DEPLETED',
    category: 'GROUND',
    message: '因为当前出站地面车辆电量已耗尽，教程任务无法继续。',
    action: '请先点击“召回”让车辆进入车库充电，电量恢复后重新出站，再开始教程 00。'
  }
  if (batteryDepleted(airAsset)) return {
    code: 'AIR_BATTERY_DEPLETED',
    category: 'AIR',
    message: '因为当前出站空中设备电量已耗尽，教程任务无法继续。',
    action: '请先点击“召回”让空中设备进入车库充电，电量恢复后重新出站，再开始教程 00。'
  }

  const quote = preview?.plan?.economyQuote || preview?.economyQuote || {}
  const groundInsufficient = quote.groundBatterySufficient === false || quote.batterySufficient === false
  const airInsufficient = quote.airBatterySufficient === false
  if (groundInsufficient) return {
    code: 'GROUND_BATTERY_INSUFFICIENT',
    category: 'GROUND',
    message: '因为当前地面车辆电量不足以完成教程路线，继续任务会中途停驶。',
    action: '请先点击“召回”让车辆进入车库充电，电量恢复后重新出站，再开始教程 00。'
  }
  if (airInsufficient) return {
    code: 'AIR_BATTERY_INSUFFICIENT',
    category: 'AIR',
    message: '因为当前空中设备电量不足以完成教程航线，继续任务会中途停飞。',
    action: '请先点击“召回”让空中设备进入车库充电，电量恢复后重新出站，再开始教程 00。'
  }
  return null
}
