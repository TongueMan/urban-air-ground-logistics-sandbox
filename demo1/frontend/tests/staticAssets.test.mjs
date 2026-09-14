import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

const frontendRoot = join(dirname(fileURLToPath(import.meta.url)), '..')

test('MapV build copies only the runtime asset whitelist', () => {
  const viteConfig = readFileSync(join(frontendRoot, 'vite.config.js'), 'utf8')
  for (const asset of [
    'textures/water/foam_noise.webp',
    'models/effect/diamond.glb',
    'workers/BaiduVectorParser.worker-c827f410.js'
  ]) assert.match(viteConfig, new RegExp(asset.replaceAll('.', '\\.')))
  assert.doesNotMatch(viteConfig, /cpSync\(mapvAssetsSource,[\s\S]*recursive:\s*true/)
  assert.doesNotMatch(viteConfig, /fallback-media|VITE_MEDIA_TARGET/)
})

test('OSS static asset URLs keep the local public path as a fallback', async () => {
  globalThis.window = {
    __SKYFLEET_CONFIG__: {
      staticAssetBase: 'https://java-tongueman.oss-cn-beijing.aliyuncs.com/project/'
    }
  }
  const runtime = await import('../src/config/runtime.js?oss-test')
  delete globalThis.window

  assert.equal(
    runtime.staticAssetUrl('/models/library/rewards/gold-coin.glb'),
    'https://java-tongueman.oss-cn-beijing.aliyuncs.com/project/models/library/rewards/gold-coin.glb'
  )
  assert.equal(
    runtime.staticAssetUrl('/models/library/rewards/trophy-low-poly-game-ready.glb'),
    'https://java-tongueman.oss-cn-beijing.aliyuncs.com/project/models/library/rewards/trophy-low-poly-game-ready.glb'
  )
  assert.equal(
    runtime.staticAssetUrl('/models/library/operations/highway-patrol-cruiser.glb'),
    'https://java-tongueman.oss-cn-beijing.aliyuncs.com/project/models/library/operations/highway-patrol-cruiser.glb'
  )
  assert.deepEqual(runtime.staticAssetCandidates('/tutorial/characters/anan-default.png', 'characters/anan-default.png'), [
    'https://java-tongueman.oss-cn-beijing.aliyuncs.com/project/characters/anan-default.png',
    '/tutorial/characters/anan-default.png'
  ])
  assert.equal(
    runtime.staticAssetUrl('/mission/reactions/anan-airspace-fine.png', '/reactions/anan-airspace-fine.png'),
    'https://java-tongueman.oss-cn-beijing.aliyuncs.com/project/reactions/anan-airspace-fine.png'
  )
})

test('local static assets remain the only candidate without an OSS base', async () => {
  const runtime = await import('../src/config/runtime.js?local-test')
  assert.equal(runtime.staticAssetUrl('mission/reactions/anan-airspace-fine.png'), '/mission/reactions/anan-airspace-fine.png')
  assert.equal(
    runtime.staticAssetUrl('mission/reactions/anan-airspace-fine.png', 'reactions/anan-airspace-fine.png'),
    '/mission/reactions/anan-airspace-fine.png'
  )
  assert.deepEqual(runtime.staticAssetCandidates('models/smart-city/traffic/ford-f350-utility.glb'), [
    '/models/smart-city/traffic/ford-f350-utility.glb'
  ])
})
