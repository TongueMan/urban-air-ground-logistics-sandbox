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
  mediaBase: trimTrailingSlash(raw.mediaBase || devEnv.VITE_MEDIA_BASE || '/media'),
  mediaPathPrefix: String(raw.mediaPathPrefix || '').replace(/^\/+|\/+$/g, ''),
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

export function mediaUrl(path) {
  const normalizedPath = String(path).replace(/^\/+/, '')
  const prefix = runtimeConfig.mediaPathPrefix ? `${runtimeConfig.mediaPathPrefix}/` : ''
  return `${runtimeConfig.mediaBase}/${prefix}${normalizedPath}`
}

export function smartCityAssetUrl(path) {
  return `${runtimeConfig.smartCityAssetBase}/${String(path).replace(/^\/+/, '')}`
}

export function staticAssetUrl(path) {
  const value = String(path || '')
  if (/^(?:https?:)?\/\//i.test(value) || /^(?:data|blob):/i.test(value)) return value
  return `${runtimeConfig.staticAssetBase}/${value.replace(/^\/+/, '')}`
}

export function parkingImageUrl(imageKey) {
  return `${runtimeConfig.parkingImageBase}/${String(imageKey).replace(/^\/+/, '')}.jpg`
}

export function websocketUrl(path) {
  const base = runtimeConfig.wsBase || `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}`
  return `${base}/${String(path).replace(/^\/+/, '')}`
}

export function mediaAuthHeaders(token, headers = {}) {
  if (runtimeConfig.environment === 'dev' || !token) return headers
  return { ...headers, Authorization: `Bearer ${token}` }
}
