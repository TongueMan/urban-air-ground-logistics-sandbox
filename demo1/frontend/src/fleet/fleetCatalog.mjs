export const FLEET_CATEGORIES = Object.freeze([
  Object.freeze({ id: 'GROUND', label: '地面运输', english: 'GROUND' }),
  Object.freeze({ id: 'AIR', label: '空中运输', english: 'AIR' })
])

export function assetsByType(assets = []) {
  return assets.reduce((groups, asset) => {
    const typeId = String(asset.typeId || '')
    if (!groups[typeId]) groups[typeId] = []
    groups[typeId].push(asset)
    return groups
  }, {})
}

export function fleetCounts(assets = []) {
  return assets.reduce((counts, asset) => {
    counts.total += 1
    if (asset.status === 'GARAGED') counts.garaged += 1
    if (asset.status === 'DEPLOYED') counts.deployed += 1
    return counts
  }, { total: 0, garaged: 0, deployed: 0 })
}

function deploymentTimestamp(asset = {}) {
  const value = Date.parse(asset.updatedAt || asset.acquiredAt || '')
  return Number.isFinite(value) ? value : 0
}

export function deployedFleetAssignments(snapshot = {}) {
  const catalog = Array.isArray(snapshot.catalog) ? snapshot.catalog : []
  const assets = Array.isArray(snapshot.assets) ? snapshot.assets : []
  const typeById = new Map(catalog.map(type => [type.typeId, type]))
  const assignmentFor = (category, role) => {
    const candidates = assets
      .filter(asset => asset.status === 'DEPLOYED' && typeById.get(asset.typeId)?.category === category)
      .sort((left, right) => deploymentTimestamp(right) - deploymentTimestamp(left)
        || String(right.updatedAt || '').localeCompare(String(left.updatedAt || ''))
        || String(right.assetId || '').localeCompare(String(left.assetId || '')))
    const asset = candidates[0]
    const type = asset ? typeById.get(asset.typeId) : null
    return asset && type ? {
      role,
      category,
      assetId: asset.assetId,
      typeId: type.typeId,
      modelAssetId: type.modelAssetId,
      name: type.name,
      independentRoute: type.typeId === 'vtol-air-taxi'
    } : null
  }
  return {
    ground_vehicle: assignmentFor('GROUND', 'ground_vehicle'),
    smart_drone: assignmentFor('AIR', 'smart_drone')
  }
}

export function fleetSections(snapshot = {}, category = 'GROUND') {
  const grouped = assetsByType(snapshot.assets || [])
  const catalog = (snapshot.catalog || []).filter(type => type.category === category)
  const decorate = type => ({ ...type, instances: grouped[type.typeId] || [], count: (grouped[type.typeId] || []).length })
  return {
    owned: catalog.filter(type => grouped[type.typeId]?.length).map(decorate),
    market: catalog.filter(type => !grouped[type.typeId]?.length).map(decorate)
  }
}

export function effectiveFleetPrice(type, devMode) {
  return devMode ? 0 : Number(type?.priceMinor || 0)
}

export function formatCny(minor = 0) {
  return new Intl.NumberFormat('zh-CN', {
    style: 'currency', currency: 'CNY', minimumFractionDigits: 0, maximumFractionDigits: 0
  }).format(Number(minor || 0) / 100)
}
