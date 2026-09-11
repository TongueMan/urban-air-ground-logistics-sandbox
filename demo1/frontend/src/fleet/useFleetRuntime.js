import { computed, ref } from 'vue'
import { changeFleetAssetStatus, getFleet, purchaseFleetAsset, sellFleetAsset } from '../api/demo'
import { deployedFleetAssignments, fleetCounts } from './fleetCatalog.mjs'

function commandId(prefix) {
  const suffix = globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(16).slice(2)}`
  return `${prefix}-${suffix}`
}

export function useFleetRuntime() {
  const snapshot = ref({ catalogVersion: '', devModeAllowed: false, company: { balanceMinor: 0, currency: 'CNY' }, assets: [], catalog: [] })
  const isOpen = ref(false)
  const loading = ref(false)
  const initialized = ref(false)
  const busyKey = ref('')
  const error = ref('')
  const notice = ref('')
  const guidance = ref(null)
  const recoveryAssetId = ref('')
  const category = ref('GROUND')
  const selectedTypeId = ref('')
  const selectedAssetId = ref('')
  const devMode = ref(false)
  const confirmingTypeId = ref('')
  const sellingAssetId = ref('')
  let initializationPromise = null

  const company = computed(() => snapshot.value.company || { balanceMinor: 0, currency: 'CNY' })
  const assets = computed(() => snapshot.value.assets || [])
  const catalog = computed(() => snapshot.value.catalog || [])
  const counts = computed(() => fleetCounts(assets.value))
  const activeDeployments = computed(() => deployedFleetAssignments(snapshot.value))
  const activeSceneAssetIds = computed(() => new Set([
    ...Object.values(activeDeployments.value).filter(Boolean).map(item => item.assetId),
    ...assets.value.filter(item => item.activeRunId).map(item => item.assetId)
  ]))
  const selectedType = computed(() => catalog.value.find(type => type.typeId === selectedTypeId.value) || null)
  const selectedInstances = computed(() => assets.value.filter(asset => asset.typeId === selectedTypeId.value))

  function replaceSnapshot(next) {
    if (!next) return
    const currentCompany = snapshot.value.company || {}
    const nextCompany = next.company || {}
    const currentUpdatedAt = currentCompany.updatedAt ? Date.parse(currentCompany.updatedAt) : NaN
    const nextUpdatedAt = nextCompany.updatedAt ? Date.parse(nextCompany.updatedAt) : NaN
    snapshot.value = Number.isFinite(currentUpdatedAt) && Number.isFinite(nextUpdatedAt) && nextUpdatedAt < currentUpdatedAt
      ? { ...next, company: currentCompany }
      : next
    const selectedExists = next.catalog?.some(type => type.typeId === selectedTypeId.value)
    if (!selectedExists) selectedTypeId.value = next.catalog?.[0]?.typeId || ''
    if (selectedAssetId.value && !next.assets?.some(asset => asset.assetId === selectedAssetId.value)) selectedAssetId.value = ''
  }

  function mergeMissionEconomy(economy) {
    if (!economy || !Number.isFinite(Number(economy.balanceMinor))) return false
    const current = snapshot.value.company || {}
    const incomingUpdatedAt = economy.companyUpdatedAt ? Date.parse(economy.companyUpdatedAt) : NaN
    const currentUpdatedAt = current.updatedAt ? Date.parse(current.updatedAt) : NaN
    if (Number.isFinite(incomingUpdatedAt) && Number.isFinite(currentUpdatedAt) && incomingUpdatedAt < currentUpdatedAt) return false
    snapshot.value = {
      ...snapshot.value,
      company: {
        ...current,
        balanceMinor: Number(economy.balanceMinor),
        currency: economy.currency || current.currency || 'CNY',
        updatedAt: economy.companyUpdatedAt || current.updatedAt
      }
    }
    return true
  }

  function initialize({ force = false } = {}) {
    if (initializationPromise) return initializationPromise
    if (initialized.value && !force) return Promise.resolve(snapshot.value)
    loading.value = true
    error.value = ''
    const request = getFleet()
      .then(next => {
        replaceSnapshot(next)
        initialized.value = true
        return next
      })
      .catch(failure => {
        error.value = failure.message || '车队资产加载失败'
        throw failure
      })
      .finally(() => {
        loading.value = false
        if (initializationPromise === request) initializationPromise = null
      })
    initializationPromise = request
    return request
  }

  async function open() {
    isOpen.value = true
    notice.value = ''
    guidance.value = null
    await initialize({ force: true }).catch(() => null)
    selectFirstForCategory(category.value)
  }

  function close() {
    isOpen.value = false
    devMode.value = false
    confirmingTypeId.value = ''
    sellingAssetId.value = ''
    notice.value = ''
    guidance.value = null
    recoveryAssetId.value = ''
  }

  function showGuidance(value) {
    guidance.value = value ? { ...value } : null
  }

  function dismissGuidance() { guidance.value = null }

  function selectFirstForCategory(nextCategory) {
    category.value = nextCategory
    const types = catalog.value.filter(type => type.category === nextCategory)
    if (!types.some(type => type.typeId === selectedTypeId.value)) {
      const owned = types.find(type => assets.value.some(asset => asset.typeId === type.typeId))
      selectedTypeId.value = (owned || types[0])?.typeId || ''
      selectedAssetId.value = ''
    }
    confirmingTypeId.value = ''
    sellingAssetId.value = ''
  }

  function selectType(typeId) {
    selectedTypeId.value = typeId
    selectedAssetId.value = assets.value.find(asset => asset.typeId === typeId)?.assetId || ''
    confirmingTypeId.value = ''
    sellingAssetId.value = ''
    notice.value = ''
  }

  async function openForRecovery(recovery = {}) {
    recoveryAssetId.value = ''
    category.value = recovery.category === 'AIR' ? 'AIR' : 'GROUND'
    await open()
    const requestedAssetId = String(recovery.assetId || '')
    const matchingAsset = assets.value.find(asset => String(asset.assetId) === requestedAssetId)
      || assets.value.find(asset => {
        const type = catalog.value.find(item => item.typeId === asset.typeId)
        return type?.category === category.value && asset.status === 'DEPLOYED'
      })
    if (matchingAsset) {
      selectType(matchingAsset.typeId)
      selectedAssetId.value = matchingAsset.assetId
      recoveryAssetId.value = matchingAsset.status === 'GARAGED' ? '' : matchingAsset.assetId
    }
    showGuidance({
      source: 'BATTERY_RECOVERY',
      badge: '续航恢复',
      title: matchingAsset?.status === 'GARAGED' ? '设备已经返回车库' : '请召回没电设备',
      message: matchingAsset?.status === 'GARAGED'
        ? '设备已经进入车库并开始自动充电。'
        : `${recovery.deviceName || '任务设备'}无法继续运输，需要先召回车库。`,
      action: matchingAsset?.status === 'GARAGED'
        ? '电量恢复后重新出站，即可开始下一次配送。'
        : '在下方设备实例中点击高亮的“召回”，设备入库后会自动充电。'
    })
    return matchingAsset || null
  }

  async function purchase(typeId = selectedTypeId.value) {
    if (!typeId || busyKey.value) return null
    busyKey.value = `purchase:${typeId}`
    sellingAssetId.value = ''
    error.value = ''
    notice.value = ''
    try {
      const result = await purchaseFleetAsset({
        commandId: commandId('BUY'), typeId, pricingMode: devMode.value ? 'DEV' : 'NORMAL'
      })
      replaceSnapshot(result.fleet)
      guidance.value = null
      selectedTypeId.value = typeId
      selectedAssetId.value = result.assetId || ''
      confirmingTypeId.value = ''
      notice.value = devMode.value ? '开发定价采购完成，未扣除公司资金。' : '采购完成，设备已进入车库。'
      return result
    } catch (failure) {
      error.value = failure.message || '采购失败'
      return null
    } finally {
      busyKey.value = ''
    }
  }

  async function setAssetStatus(asset, targetStatus) {
    if (!asset?.assetId || busyKey.value) return null
    busyKey.value = `status:${asset.assetId}`
    sellingAssetId.value = ''
    error.value = ''
    notice.value = ''
    try {
      const result = await changeFleetAssetStatus(asset.assetId, {
        commandId: commandId('STATE'), targetStatus
      })
      const recoveryRecall = targetStatus === 'GARAGED' && recoveryAssetId.value === asset.assetId
      replaceSnapshot(result.fleet)
      guidance.value = recoveryRecall ? {
        source: 'BATTERY_RECOVERY',
        badge: '正在充电',
        title: '召回成功',
        message: '设备已进入车库并开始自动充电。',
        action: '电量恢复后重新出站，即可继续执行配送任务。'
      } : null
      if (recoveryRecall) recoveryAssetId.value = ''
      selectedAssetId.value = asset.assetId
      const autoRecalledCount = Array.isArray(result.autoRecalledAssetIds) ? result.autoRecalledAssetIds.length : 0
      notice.value = targetStatus === 'DEPLOYED'
        ? `设备已按当前 ${Number(result.fleet?.assets?.find(item => item.assetId === asset.assetId)?.batteryPercent || 0).toFixed(0)}% 电量出站${autoRecalledCount ? `，并自动召回 ${autoRecalledCount} 台同类设备` : ''}。`
        : '设备已召回车库并自动充电，将在 60 秒内线性充满。'
      return result
    } catch (failure) {
      error.value = failure.message || '设备状态更新失败'
      return null
    } finally {
      busyKey.value = ''
    }
  }

  async function sell(asset) {
    if (!asset?.assetId || asset.status !== 'GARAGED' || busyKey.value) return null
    busyKey.value = `sale:${asset.assetId}`
    error.value = ''
    notice.value = ''
    try {
      const result = await sellFleetAsset({
        commandId: commandId('SELL'), assetId: asset.assetId
      })
      replaceSnapshot(result.fleet)
      const remaining = (result.fleet?.assets || []).find(item => item.typeId === asset.typeId)
      selectedAssetId.value = remaining?.assetId || ''
      selectedTypeId.value = asset.typeId
      sellingAssetId.value = ''
      notice.value = '设备已按目录原价出售，款项已转入公司账户。'
      return result
    } catch (failure) {
      error.value = failure.message || '出售失败'
      return null
    } finally {
      busyKey.value = ''
    }
  }

  return {
    snapshot, company, assets, catalog, counts, activeDeployments, activeSceneAssetIds, selectedType, selectedInstances,
    isOpen, loading, initialized, busyKey, error, notice, guidance, recoveryAssetId, category, selectedTypeId,
    selectedAssetId, devMode, confirmingTypeId, sellingAssetId,
    initialize, open, openForRecovery, close, showGuidance, dismissGuidance, selectFirstForCategory, selectType, purchase, setAssetStatus, sell,
    mergeMissionEconomy
  }
}
