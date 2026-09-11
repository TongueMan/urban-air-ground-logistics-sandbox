const raw = typeof window === 'undefined' ? {} : (window.__SKYFLEET_CONFIG__ || {})
const devEnv = import.meta.env?.DEV ? import.meta.env : {}

function trimTrailingSlash(value) {
  return String(value || '').replace(/\/+$/, '')
}

const smartCityAssetBase = trimTrailingSlash(
  raw.smartCityAssetBase || devEnv.VITE_SMART_CITY_ASSET_BASE || '/images/smart-city'
)
const staticAssetBase = trimTrailingSlash(raw.staticAssetBase || devEnv.VITE_STATIC_ASSET_BASE || '')

export const runtimeConfig = Object.freeze({
  environment: raw.environment || 'dev',
  apiBase: trimTrailingSlash(raw.apiBase),
  wsBase: trimTrailingSlash(raw.wsBase),
  visionBase: trimTrailingSlash(raw.visionBase),
  staticAssetBase,
  smartCityAssetBase,
  parkingImageBase: trimTrailingSlash(
    raw.parkingImageBase || devEnv.VITE_PARKING_IMAGE_BASE || `${smartCityAssetBase}/parking`
  ),
  baiduMapAk: String(raw.baiduMapAk || devEnv.VITE_BAIDU_MAP_AK || ''),
  amapKey: String(raw.amapKey || devEnv.VITE_AMAP_KEY || ''),
  amapSecurityCode: String(raw.amapSecurityCode || devEnv.VITE_AMAP_SECURITY_CODE || ''),
  cesiumIonToken: String(raw.cesiumIonToken || devEnv.VITE_CESIUM_ION_TOKEN || '')
})

export function apiUrl(path) {
  return `${runtimeConfig.apiBase}/${String(path).replace(/^\/+/, '')}`
}

export function visionUrl(path) {
  return `${runtimeConfig.visionBase}/${String(path).replace(/^\/+/, '')}`
}

export function smartCityAssetUrl(path) {
  return `${runtimeConfig.smartCityAssetBase}/${String(path).replace(/^\/+/, '')}`
}

export function staticAssetUrl(path, remotePath = path) {
  const value = String(path || '')
  if (/^(?:https?:)?\/\//i.test(value) || /^(?:data|blob):/i.test(value)) return value
  const normalizedPath = String(remotePath || value).replace(/^\/+/, '')
  return runtimeConfig.staticAssetBase
    ? `${runtimeConfig.staticAssetBase}/${normalizedPath}`
    : localStaticAssetUrl(value)
}

export function localStaticAssetUrl(path) {
  const value = String(path || '')
  if (/^(?:data|blob):/i.test(value)) return value
  return `/${value.replace(/^\/+/, '')}`
}

export function staticAssetCandidates(path, remotePath = path) {
  const primary = staticAssetUrl(path, remotePath)
  const fallback = localStaticAssetUrl(path)
  return primary === fallback ? [primary] : [primary, fallback]
}

export function parkingImageUrl(imageKey) {
  return `${runtimeConfig.parkingImageBase}/${String(imageKey).replace(/^\/+/, '')}.jpg`
}

export function websocketUrl(path) {
  const base = runtimeConfig.wsBase || `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}`
  return `${base}/${String(path).replace(/^\/+/, '')}`
}
