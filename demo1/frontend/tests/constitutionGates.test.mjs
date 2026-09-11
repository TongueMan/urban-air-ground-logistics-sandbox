import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import { dirname, extname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

const root = join(dirname(fileURLToPath(import.meta.url)), '..', 'src')
const frontendRoot = join(root, '..')
const projectRoot = join(frontendRoot, '..')

function filesUnder(directory) {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = join(directory, entry.name)
    return entry.isDirectory() ? filesUnder(path) : [path]
  })
}

const sourceFiles = filesUnder(root).filter(path => ['.vue', '.js', '.mjs', '.css'].includes(extname(path)))
const source = sourceFiles.map(path => readFileSync(path, 'utf8')).join('\n')

test('spatial shell excludes fixed rails and universal panel classes', () => {
  assert.doesNotMatch(source, /left-rail|right-rail|class=["'][^"']*\bpanel\b/)
})

test('world renderer no longer owns mission KPI, evidence, video, or replay UI', () => {
  const map = readFileSync(join(root, 'components', 'LogisticsMissionMap.vue'), 'utf8')
  assert.doesNotMatch(map, /mission-kpis|selected-card|evidence-card|replay-bar|open-monitor/)
})

test('drone scan range follows the same per-frame ground position as its model', () => {
  const map = readFileSync(join(root, 'components', 'LogisticsMissionMap.vue'), 'utf8')
  assert.match(map, /scanRange\.position\.fromArray\(groundPosition\)/)
  assert.doesNotMatch(map, /scanPoints|pointLayerSignatures\.scan|let hitLayer = null, pickLayer = null, scanLayer/)
})

test('traffic signals use zoom-gated DOM countdown pills', () => {
  const map = readFileSync(join(root, 'components', 'LogisticsMissionMap.vue'), 'utf8')
  assert.match(map, /TRAFFIC_LIGHT_MAX_RANGE = 3000/)
  assert.match(map, /new mapvthree\.DOMPoint/)
  assert.match(map, /traffic-signal-marker/)
  assert.match(map, /range <= TRAFFIC_LIGHT_MAX_RANGE/)
  assert.match(map, /function syncTrafficLightNodes\(\)/)
  assert.match(map, /nextTrafficStructureSignature/)
  assert.doesNotMatch(map, /nextTrafficStructureSignature[^\n]*countdown/)
  assert.doesNotMatch(map, /new mapvthree\.Label\(\{\s*type: 'text'[\s\S]*红\$\{/)
})

test('world renderer resolves deployed fleet models with logistics fallbacks and presentation metadata', () => {
  const map = readFileSync(join(root, 'components', 'LogisticsMissionMap.vue'), 'utf8')
  const assets = readFileSync(join(root, 'config', 'modelAssets.mjs'), 'utf8')
  assert.match(map, /modelAssignmentForDevice\(device\)/)
  assert.match(map, /assignment\?\.modelAssetId \|\| ACTIVE_MODEL_ROLES\[role\]/)
  assert.match(map, /getModelAsset\(assetId\)/)
  assert.match(map, /function ensureModelTemplate\(assetId, role\)/)
  assert.match(assets, /ground_vehicle: 'tricycle'/)
  assert.match(assets, /models\/smart-city\/traffic\/ford-f350-utility\.glb/)
  assert.match(assets, /MODEL_MAP_PRESENTATION/)
  assert.match(assets, /tricycle:[\s\S]*size: 3\.2[\s\S]*forwardAxis: '\+X'/)
  assert.match(assets, /'ford-f350-utility':[\s\S]*forwardAxis: '\+Y'[\s\S]*warningBeacon: true/)
  assert.match(assets, /'peterbilt-379-optimus-prime':[\s\S]*size: 16\.5[\s\S]*forwardAxis: '-Y'/)
  assert.match(assets, /'vtol-air-taxi':[\s\S]*size: 11\.5[\s\S]*forwardAxis: '-Y'/)
  assert.match(map, /modelYawRadians\(record\.forwardAxis, record\.currentHeading\)/)
  assert.match(map, /independentAir: role === 'smart_drone' && assignment\?\.independentRoute === true/)
  assert.match(map, /independentRouteState = independentAir \? sampler\.locate\(routeProgress\) : null/)
  assert.match(map, /record\.type !== 'smart_drone' \|\| record\.independentAir/)
  assert.match(map, /multiplyScalar\(carrier\.displayScale\)/)
  assert.doesNotMatch(map, /vehicle\.coordinate\[2\] \+ 2\.55/)
})

test('vehicle warning bar is gated by model presentation configuration', () => {
  const map = readFileSync(join(root, 'components', 'LogisticsMissionMap.vue'), 'utf8')
  assert.match(map, /new THREE\.BoxGeometry\(\.62, \.34, \.17\)/)
  assert.match(map, /left\.position\.set\(-\.33, 0, roofHeight \+ \.13\)/)
  assert.match(map, /right\.position\.set\(\.33, 0, roofHeight \+ \.13\)/)
  assert.match(map, /modelPresentation\.warningBeacon/)
})

test('mission instrument card shows both ground and air battery in Chinese', () => {
  const instrument = readFileSync(join(root, 'components', 'instruments', 'InstrumentCluster.vue'), 'utf8')
  for (const label of ['地面与空中设备仪表', '配送进度', '地面电量', '空中电量', '电量耗尽']) assert.match(instrument, new RegExp(label))
  assert.match(instrument, /class="cluster-progress-value"/)
  assert.match(instrument, /\.cluster-progress-value\s*\{[^}]*display:flex[^}]*gap:4px/)
  assert.match(instrument, /\.battery-reading :deep\(\.instrument-value\)\{[^}]*display:flex[^}]*gap:4px/)
  assert.doesNotMatch(instrument, /\.cluster-arc small\s*\{[^}]*position:absolute/)
  for (const removed of ['链路', '偏差', 'coverage', 'linkQuality', 'routeDeviationMeters']) assert.doesNotMatch(instrument, new RegExp(removed))
  assert.doesNotMatch(instrument, /MISSION INSTRUMENT|ACTOR INSTRUMENT|FLEET STATUS|COVERAGE|POWER|LINK|OFFSET/)
})

test('logistics mission adapter does not infer relationships with string suffix rules', () => {
  const adapter = readFileSync(join(root, 'mission', 'adapters', 'logisticsMissionAdapter.mjs'), 'utf8')
  assert.doesNotMatch(adapter, /\.endsWith\(|\.includes\(['"]UAV/)
})

test('design and motion token gates remain present', () => {
  const tokens = readFileSync(join(root, 'styles', 'tokens.css'), 'utf8')
  for (const token of ['--type-display', '--signal-warning', '--layer-world', '--layer-action', '--motion-instant', '--motion-cinematic']) {
    assert.match(tokens, new RegExp(token))
  }
})

test('application enters through MissionContext and the spatial screen', () => {
  const app = readFileSync(join(root, 'App.vue'), 'utf8')
  assert.match(app, /MissionContextProvider/)
  assert.match(app, /SpatialMissionScreen/)
})

test('mission action layer exposes mission control', () => {
  const action = readFileSync(join(root, 'components', 'action', 'ActionLayer.vue'), 'utf8')
  assert.match(action, /ACTION \/ MISSION CONTROL/)
})

test('mission side drawers are mutually exclusive and stale formation members are filtered', () => {
  const state = readFileSync(join(root, 'components', 'mission', 'MissionState.vue'), 'utf8')
  const runtime = readFileSync(join(root, 'mission', 'runtime', 'useLogisticsMissionRuntime.js'), 'utf8')
  const dock = readFileSync(join(root, 'components', 'fleet', 'FleetDock.vue'), 'utf8')
  assert.match(state, /function openMissionControl\(\)[\s\S]*runtime\.closeAirspace\(\)[\s\S]*context\.openActionMode\('CONTROL'\)/)
  assert.match(runtime, /function selectAirspace\(volumeId\)[\s\S]*context\.closeActionMode\(\)/)
  assert.match(dock, /visibleFormations[\s\S]*filter\(member => Boolean\(mission\.value\.actorsById\?\.\[member\.actorId\]\)\)/)
})

test('production ingress preserves proxy identity and stays bound to localhost', () => {
  const containerNginx = readFileSync(join(frontendRoot, 'deploy', 'nginx.conf'), 'utf8')
  const publicNginx = readFileSync(join(projectRoot, 'deploy', 'nginx', 'skyfleet.conf.example'), 'utf8')
  const compose = readFileSync(join(projectRoot, 'docker-compose.yml'), 'utf8')
  const productionCompose = readFileSync(join(projectRoot, 'docker-compose.prod.yml'), 'utf8')
  const envExample = readFileSync(join(projectRoot, '.env.example'), 'utf8')

  assert.match(containerNginx, /proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for/)
  assert.match(containerNginx, /\$http_x_forwarded_proto \$skyfleet_forwarded_proto/)
  assert.match(compose, /127\.0\.0\.1:\$\{DEMO_HTTP_PORT:-8088\}:8080/)
  assert.match(publicNginx, /listen 443 ssl/)
  assert.match(publicNginx, /limit_req zone=skyfleet_api/)
  assert.match(publicNginx, /proxy_pass http:\/\/skyfleet_app/)
  assert.match(publicNginx, /proxy_buffering off/)
  assert.match(productionCompose, /MYSQL_PASSWORD:\s*\$\{MYSQL_PASSWORD:\?/)
  assert.match(productionCompose, /DEMO_COOKIE_SECRET:\s*\$\{DEMO_COOKIE_SECRET:\?/)
  assert.match(productionCompose, /FLEET_DEV_PRICING_ENABLED:\s*"false"/)
  for (const key of ['MYSQL_PASSWORD', 'MYSQL_ROOT_PASSWORD', 'DEMO_COOKIE_SECRET']) {
    assert.match(envExample, new RegExp(`^${key}=$`, 'm'))
  }
})
