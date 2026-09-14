import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { snapTimelineProgress } from '../src/mission/presentation/timelineSnapping.mjs'
import { resolveAirspaceActivation, visibleAirspaceConflicts } from '../src/utils/airspaceVisibility.mjs'

const api = readFileSync(new URL('../src/api/demo.js', import.meta.url), 'utf8')
const planner = readFileSync(new URL('../src/components/mission/TaskPlanner.vue', import.meta.url), 'utf8')
const missionState = readFileSync(new URL('../src/components/mission/MissionState.vue', import.meta.url), 'utf8')
const actionLayer = readFileSync(new URL('../src/components/action/ActionLayer.vue', import.meta.url), 'utf8')
const history = readFileSync(new URL('../src/components/mission/TaskHistory.vue', import.meta.url), 'utf8')
const airspaceInspector = readFileSync(new URL('../src/components/mission/AirspacePanel.vue', import.meta.url), 'utf8')
const missionMap = readFileSync(new URL('../src/components/LogisticsMissionMap.vue', import.meta.url), 'utf8')
const spatialScreen = readFileSync(new URL('../src/screens/SpatialMissionScreen.vue', import.meta.url), 'utf8')
const missionRuntime = readFileSync(new URL('../src/mission/runtime/useLogisticsMissionRuntime.js', import.meta.url), 'utf8')
const missionTimeline = readFileSync(new URL('../src/components/mission/MissionTimeline.vue', import.meta.url), 'utf8')
const missionWorldBridge = readFileSync(new URL('../src/components/world/MissionWorldBridge.vue', import.meta.url), 'utf8')

test('task instance API separates generation, start and replay', () => {
  assert.match(api, /generateTaskInstance/)
  assert.match(api, /startTaskRun/)
  assert.match(api, /getRunReplay/)
})

test('advanced ground reward markers remain keyboard and pointer actionable after planning', () => {
  assert.match(missionMap, /document\.createElement\(dispatchable \? 'button' : 'div'\)/)
  assert.match(missionMap, /mission-point-marker\[role="button"\][^{]*\{[^}]*pointer-events:auto/)
  assert.match(missionMap, /item\.dispatchable === true/)
  assert.match(missionMap, /emit\('dispatch-ground-reward'/)
  assert.match(missionMap, /Date\.now\(\) - lastMapOverlayInteractionAt < 500/)
})

test('airspace interaction wins over advanced ground map dispatch', () => {
  assert.match(missionMap, /node\.addEventListener\('pointerdown', markMapOverlayInteraction\)/)
  assert.match(missionMap, /visibleAirspaceAtCoordinate\(longitude, latitude\)/)
  assert.match(missionMap, /if \(selectedAirspace\)[\s\S]{0,160}emit\('select-airspace'/)
  assert.match(missionMap, /coordinateInsideRing/)
  assert.match(missionMap, /object\.addEventListener\('click', selectVisualAirspace\)/)
  assert.match(missionMap, /projection\.addEventListener\?\.\('click', selectVisualAirspace\)/)
})

test('map drag cannot leave MapView frozen when pointerup propagation is intercepted', () => {
  assert.doesNotMatch(missionMap, /mapView\.freezeUpdate/)
  assert.match(missionMap, /window\.addEventListener\('pointerup', onMapPointerUp, true\)/)
  assert.match(missionMap, /window\.addEventListener\('pointercancel', onMapPointerUp, true\)/)
})

test('running mission recovers when the simulation clock stops advancing', () => {
  assert.match(missionRuntime, /RUNTIME_STALL_THRESHOLD_MS = 8000/)
  assert.match(missionRuntime, /session\.value\?\.status !== 'RUNNING' \|\| Number\(timeScale\.value\) <= 0/)
  assert.match(missionRuntime, /context\.timeMode\.value !== 'LIVE'/)
  assert.match(missionRuntime, /Date\.now\(\) - lastRuntimeAdvanceAt < RUNTIME_STALL_THRESHOLD_MS/)
  assert.match(missionRuntime, /await refreshMission\(\)[\s\S]{0,120}connectEvents\(\)/)
  assert.match(missionRuntime, /FALLBACK_POLL_INTERVAL_MS = 4000/)
  assert.match(missionRuntime, /stopRuntimeWatchdog\(\)/)
})

test('running mission status keeps mission control visible and consumes its preview', () => {
  assert.match(missionState, /missionInProgress = computed/)
  assert.match(missionState, /\['QUEUED', 'RUNNING'\]/)
  assert.match(missionState, /v-else-if="!missionInProgress"/)
  assert.match(missionRuntime, /startTaskRun[\s\S]{0,260}taskPreview\.value = null/)
})

test('advanced map clicks use MapV geographic points and runtime hides unselected candidates', () => {
  assert.match(missionMap, /const source = event\?\.point \|\| event\?\.coordinate/)
  assert.match(missionMap, /const visibleCandidates = props\.planningPreview \? availableCandidates : \[\]/)
  assert.match(missionMap, /const candidateOverlay = route\.kind === 'GROUND_CANDIDATE'/)
  assert.match(missionMap, /route\.kind !== 'PACE'/)
  assert.match(missionMap, /active-ground-dispatch/)
})

test('selected advanced baseline changes from a dashed candidate to a solid highlight', () => {
  assert.match(missionMap, /visual\.planned\.dashed = candidateOverlay \? !candidateSelected : route\.selectedBaseline !== true/)
  assert.match(missionMap, /candidateSelected \? \.98 : \.28/)
})

test('running map avoids duplicate candidate lines and permanent reward cards', () => {
  assert.match(missionMap, /runtimeGroundColor = !props\.planningPreview && route\.kind === 'GROUND' \? '#46dff2'/)
  assert.match(missionMap, /if \(kind === 'pace'\) \{[\s\S]{0,260}node\.append\(label\)/)
  assert.match(missionMap, /if \(coordinate && dispatchable\) missionPoints\.push/)
})

test('reward visuals enforce air diamonds and ground trophies', () => {
  assert.match(missionMap, /isDiamond = rewardActor === 'AIR' && rewardType === 'DIAMOND'/)
  assert.match(missionMap, /isTrophy = rewardActor === 'GROUND' && rewardType === TROPHY_REWARD_TYPE/)
  assert.match(missionMap, /金币（空中 \/ 地面）/)
  assert.match(missionMap, /奖杯（仅地面）/)
  assert.match(missionMap, /粉钻（仅空中）/)
})

test('contract pace vehicles use a dedicated patrol model and runtime red-blue lightbar', () => {
  assert.match(missionMap, /device\.deviceType === 'pace_vehicle'/)
  assert.match(missionMap, /role === 'pace_vehicle' \? null : props\.modelAssignments/)
  assert.match(missionMap, /modelPresentation\.emergencyLightbar/)
  assert.match(missionMap, /createEmergencyLightbar\(object\)/)
  assert.match(missionMap, /updateEmergencyLightbar\(record, now, reducedMotion\)/)
})

test('active ground dispatch draws a solid vehicle-to-target connector until arrival', () => {
  assert.match(missionMap, /dispatchConnectorLayer = engine\.add\(new mapvthree\.Polyline/)
  assert.match(missionMap, /authoritativePosition/)
  assert.match(missionMap, /temporaryTarget\.reached !== true/)
  assert.match(missionMap, /dashed: false/)
  assert.match(missionMap, /active-ground-dispatch-connector/)
  assert.match(missionMap, /remainingRouteToTarget\(activeGroundRoute/)
  assert.match(missionMap, /collectedDeliveryTargetIds\.has\(targetId\)/)
  assert.match(missionMap, /temporaryCoordinate && temporaryTarget\?\.reached !== true/)
})

test('tutorial 02 is replayable, refreshes its unlock at completion, and explains its fixed no-reward sandbox', () => {
  assert.match(missionRuntime, /tutorial02Available = computed\([\s\S]{0,220}availability === 'AVAILABLE'/)
  assert.doesNotMatch(missionRuntime, /tutorial02Available = computed\([\s\S]{0,260}status !== 'COMPLETED'/)
  assert.match(missionRuntime, /session-end[\s\S]{0,260}loadTutorialState\(\)/)
  assert.doesNotMatch(missionState, /tutorial-02-button|重玩教程 02/)
  assert.match(missionRuntime, /syncTutorialStatus/)
  assert.match(planner, /教学环境已固定/)
  assert.match(planner, /关闭空域干扰和经营结算/)
  assert.match(planner, /教程练习不计经营收益/)
  assert.match(planner, /data-tutorial-id="mission-route-candidates"/)
  assert.match(missionMap, /data-tutorial-id="return-ground-baseline"/)
  assert.match(missionMap, /dataset\.tutorialGroundReward/)
})

test('tutorial and normal planner modes cannot reuse each other\'s task preview', () => {
  assert.match(missionRuntime, /const previewIsTutorial = Boolean\(taskPreview\.value\?\.plan\?\.tutorialId\)/)
  assert.match(missionRuntime, /previewMode !== nextMode \|\| previewIsTutorial/)
  assert.match(missionRuntime, /previewTutorialId !== tutorialId/)
  assert.match(missionRuntime, /selectedBaselineRouteCandidateId\.value = ''/)
  assert.match(planner, /current\.plan\?\.tutorialId \|\| ''\)[\s\S]{0,120}tutorialGenerationPreset\.value\?\.tutorialId/)
})

test('timeline separates pause, read-only replay, return-to-current and checkpoint restore', () => {
  assert.match(api, /restoreRewindCheckpoint/)
  assert.match(api, /rewind-checkpoints/)
  assert.match(missionRuntime, /changeSpeed\(session\.value\.id, 0\)/)
  assert.match(missionRuntime, /expectedRevision/)
  assert.match(missionRuntime, /context\.enterReplay\(progress\)/)
  assert.match(missionTimeline, /暂停任务/)
  assert.match(missionTimeline, /回看播放/)
  assert.match(missionTimeline, /返回当前/)
  assert.match(missionTimeline, /回到这里重新选择/)
  assert.match(missionTimeline, /selectedCheckpointId\.value === latestCheckpoint\.value\.id/)
})

test('timeline enters replay before the asynchronous pause so rapid scrubbing cannot jump back', () => {
  const previewAt = missionRuntime.match(/async function previewAt\(progress\) \{[\s\S]*?\n  \}/)?.[0] || ''
  const enterReplayAt = previewAt.indexOf('context.enterReplay(progress)')
  const pauseAt = previewAt.indexOf('await pauseMission()')
  assert.ok(enterReplayAt >= 0, 'previewAt should enter replay immediately')
  assert.ok(pauseAt > enterReplayAt, 'the replay cursor must be established before waiting for pause')
  assert.equal(previewAt.lastIndexOf('context.enterReplay(progress)'), enterReplayAt, 'a stale cursor must not be reapplied after pause')
  assert.match(previewAt, /if \(!wasReplaying\) context\.returnToLive\(\)/)
})

test('checkpoint restore refreshes the settled server revision before its first request', () => {
  const restoreCheckpoint = missionRuntime.match(/async function restoreCheckpoint\(checkpointId\) \{[\s\S]*?\n  \}/)?.[0] || ''
  const refreshAt = restoreCheckpoint.indexOf('await getMission(runId)')
  const restoreAt = restoreCheckpoint.indexOf('await postRestoreRewindCheckpoint(runId')
  assert.ok(refreshAt >= 0, 'restore should fetch the latest paused snapshot')
  assert.ok(restoreAt > refreshAt, 'restore should use the settled revision after refresh')
  assert.match(restoreCheckpoint, /expectedRevision: Number\(latestSnapshot\?\.revision \|\| 0\)/)
})

test('timeline scrubber snaps to decision checkpoints with hysteresis', () => {
  const checkpoints = [
    { id: 'RWC-1', progress: 24, available: false },
    { id: 'RWC-2', progress: 62, available: true }
  ]
  assert.deepEqual(
    snapTimelineProgress(60.7, checkpoints, { liveProgress: 80 }),
    { value: 62, checkpointId: 'RWC-2', checkpoint: checkpoints[1], snapped: true }
  )
  assert.equal(snapTimelineProgress(59.8, checkpoints, { liveProgress: 80, activeCheckpointId: 'RWC-2' }).value, 62)
  assert.deepEqual(
    snapTimelineProgress(59.4, checkpoints, { liveProgress: 80, activeCheckpointId: 'RWC-2' }),
    { value: 59.4, checkpointId: '', checkpoint: null, snapped: false }
  )
  assert.equal(snapTimelineProgress(79, [{ id: 'FUTURE', progress: 90 }], { liveProgress: 80 }).checkpointId, '')
})

test('mission briefing exposes only the player-facing airspace count and fleet summary', () => {
  assert.match(planner, /airspaceThemeCount/)
  assert.match(planner, /禁飞区数量/)
  assert.match(planner, /\[2, 3, 4\]/)
  assert.match(planner, /\.zone-count-picker label\{[^}]*align-items:center[^}]*justify-content:center/)
  assert.doesNotMatch(planner, /高级设置|开发者信息|订单密度|飞行高度|最长时限|返航余量/)
  assert.match(planner, /开始配送/)
  assert.match(planner, /换一个任务/)
  assert.doesNotMatch(planner, /form\.seed/)
  assert.match(planner, /runtime\.discardTaskPreview\(\)/)
  assert.match(missionRuntime, /function discardTaskPreview\(\)/)
  assert.match(planner, /previewMatchesForm/)
  assert.match(planner, /请先重新生成/)
  for (const label of ['地面车辆', '当前电量', '满电续航', '预计耗电', '运载倍率']) assert.match(planner, new RegExp(label))
  for (const label of ['空中设备', '满电航程', '机动响应']) assert.match(planner, new RegExp(label))
  assert.match(planner, /groundBatterySufficient/)
  assert.match(planner, /airBatterySufficient/)
  assert.match(planner, /仍可开始/)
})

test('starting another delivery returns to a clean task setup instead of restarting the previous run', () => {
  assert.match(actionLayer, /runtime\.prepareNewDelivery/)
  assert.match(missionRuntime, /function prepareNewDelivery\(\)/)
  assert.match(missionRuntime, /taskPreview\.value = null/)
  assert.match(missionRuntime, /context\.ingestSnapshot\(null\)/)
  assert.match(missionRuntime, /plannerOpen\.value = true/)
  assert.doesNotMatch(missionRuntime, /restartSession/)
  assert.match(planner, /配送任务配置/)
  assert.match(missionState, /生成配送任务/)
  assert.doesNotMatch(missionState, /查看任务简报/)
})

test('history states the visitor retention boundary', () => {
  assert.match(history, /当前访客最近 30 天/)
})

test('task preview renders targets, launch and recovery anchors on the map', () => {
  assert.match(missionMap, /deliveryPoints/)
  assert.match(missionMap, /deliveryTargets/)
  assert.match(missionMap, /无人机起飞点/)
  assert.match(missionMap, /无人机返航点/)
})

test('task preview keeps every planned airspace volume visible before mission start', () => {
  assert.match(missionWorldBridge, /planning-preview/)
  assert.match(missionMap, /planningPreview/)
  assert.match(missionMap, /state: 'PLANNED', activationRatio: 1/)
  assert.match(missionMap, /planning \|\| !\['SCHEDULED', 'EXPIRED'\]/)
  assert.match(missionMap, /规划空域已显示/)
  assert.match(missionMap, /sideLines/)
})

test('static active airspace is visible at simulation zero while dynamic airspace keeps its lifecycle', () => {
  assert.equal(resolveAirspaceActivation({ state: 'ACTIVE', dynamic: false, activationRatio: 0 }), 1)
  assert.equal(resolveAirspaceActivation({ state: 'ACTIVE', dynamic: true, activationRatio: 0 }), 0)
  assert.equal(resolveAirspaceActivation({ state: 'SCHEDULED', dynamic: true, activationRatio: 1 }), 0)
  assert.equal(resolveAirspaceActivation({ state: 'ACTIVATING', dynamic: true, activationRatio: 0 }), .05)
  assert.match(missionMap, /if \(!flyToMissionOverview\(viewportPoints\)\)/)
  assert.match(missionMap, /AIRSPACE_LABEL_ALTITUDE_METERS = 12/)
  assert.match(missionMap, /CONFLICT_LABEL_ALTITUDE_METERS = 18/)
  assert.match(missionMap, /cycleAnchorSimulationMs/)
  assert.match(missionMap, /state === 'CLEARING'/)
})

test('temporary airspace keeps predictive conflict UI while its visual cycle is inactive', () => {
  assert.match(airspaceInspector, /conflict\.predictive \? '预测空域冲突' : '空域冲突'/)
  assert.match(airspaceInspector, /CONTINUE_DIRECT: '保持原航线'/)
  assert.match(airspaceInspector, /已解除，等待无人机通过/)
  assert.match(missionMap, /predictive: conflict\.predictive === true/)
  assert.match(missionMap, /predictive \? '◇  预测冲突' : '×  空域冲突'/)
})

test('tutorial hides only the red conflict action before rewind', () => {
  const conflicts = [
    { id: 'C-RED', volumeId: 'V-RED', ruleType: 'ABSOLUTE_NO_FLY' },
    { id: 'C-YELLOW', volumeId: 'V-YELLOW', ruleType: 'TEMPORARY_NO_FLY' },
    { id: 'C-PURPLE', volumeId: 'V-PURPLE' }
  ]
  const volumes = [{ id: 'V-PURPLE', ruleType: 'ALTITUDE_CORRIDOR' }]
  assert.deepEqual(visibleAirspaceConflicts(conflicts, volumes, false), conflicts)
  assert.deepEqual(visibleAirspaceConflicts(conflicts, volumes, true).map(item => item.id), ['C-YELLOW', 'C-PURPLE'])
  assert.match(missionWorldBridge, /tutorial-red-conflict-locked/)
  assert.match(missionMap, /visibleAirspaceConflicts\(sourceConflicts, volumes, props\.tutorialRedConflictLocked\)/)
  assert.match(missionRuntime, /tutorialRedConflictLocked\.value && isAbsoluteNoFlyVolume\(volumeId\)/)
})

test('device follow can return to the active delivery bounds instead of the fixed city overview', () => {
  assert.match(missionMap, /data-tutorial-id="mission-map-interaction"/)
  assert.match(missionMap, /data-tutorial-id="return-mission-overview"/)
  assert.match(missionMap, /currentMissionViewportPoints = viewportPoints\.map/)
  assert.match(missionMap, /flyToMissionOverview\(points = currentMissionViewportPoints/)
  assert.match(missionMap, /engine\.map\.flyTo\(camera\.center/)
  assert.match(missionWorldBridge, /@follow-change="runtime\.setMapFollowingDevice"/)
})

test('airspace volumes render as an unobstructed translucent information layer', () => {
  assert.match(missionMap, /const AIRSPACE_RENDER_ORDER = 30/)
  assert.match(missionMap, /depthTest: false,[\s\S]{0,80}depthWrite: false[\s\S]{0,80}projection\.renderOrder = AIRSPACE_RENDER_ORDER/)
  assert.match(missionMap, /new THREE\.MeshBasicMaterial\(\{ color, transparent: true, opacity: \.18, side: THREE\.DoubleSide, depthTest: false/)
  assert.match(missionMap, /object\.frustumCulled = false/)
})

test('coins, trophies and diamonds use cached GLB templates and the shared render loop', () => {
  assert.match(missionMap, /COIN_ASSET_ID = 'gold-coin'/)
  assert.match(missionMap, /DIAMOND_ASSET_ID = 'pink-diamond'/)
  assert.match(missionMap, /TROPHY_ASSET_ID = 'trophy-low-poly-game-ready'/)
  assert.match(missionMap, /ensureRewardTemplate/)
  assert.match(missionMap, /rewardDiamonds/)
  assert.match(missionMap, /DIAMOND_REWARD/)
  assert.match(missionMap, /coinRecords/)
  assert.match(missionMap, /animateCoins\(now\)/)
  assert.match(missionMap, /COIN_SPIN_RADIANS_PER_SECOND/)
  assert.match(missionMap, /TROPHY_SPIN_RADIANS_PER_SECOND = Math\.PI \* 2 \* \.25/)
  assert.match(missionMap, /simulationTimeMs/)
  assert.match(missionMap, /coin-reward-popup/)
  assert.match(missionMap, /if \(kind !== 'target'\) node\.append\(icon\)/)
  assert.match(missionMap, /TROPHY_TARGET_PIXELS = 84/)
  assert.match(missionMap, /TROPHY_WORLD_CAP = 14\.4/)
  assert.match(missionMap, /tier === 'SMALL' \? 28 : tier === 'LARGE' \? 44 : 36/)
  assert.match(missionMap, /record\.point\.kind === 'AIR' \? 0 : 1\.15/)
  assert.match(missionMap, /record\.point\.kind === 'AIR' \? record\.coordinate\[2\] : \.42/)
  assert.doesNotMatch(missionMap, /requestAnimationFrame\([^)]*animateCoins/)
})

test('economy quote presents concise reward and risk choices while history keeps settlement detail', () => {
  for (const label of ['配送收益', '奖杯收益', '预计时效', '挑战潜力', '预计可得', '最高可得']) assert.match(planner, new RegExp(label))
  assert.match(planner, /selectedGroundRewards/)
  assert.match(planner, /eligibleCandidateIds/)
  assert.match(planner, /selectedTrophyRewardMinor/)
  assert.match(planner, /deliveryRewardMinor\.value \+ trophyRewardMinor\.value/)
  assert.match(planner, /本局粉钻目标/)
  assert.match(planner, /空域规则/)
  assert.match(planner, /每个互动空域优先安排操纵挑战/)
  assert.match(planner, /剩余粉钻随机分布/)
  assert.match(planner, /yellowRiskExtraBatteryUsePercent/)
  assert.doesNotMatch(planner, /计划摘要|本阶段未评估/)
  assert.match(history, /penaltyChargedMinor/)
  assert.match(history, /groundCargoRewardMinor/)
  assert.match(history, /timelinessRewardMinor/)
  assert.match(history, /车辆电量耗尽/)
  assert.match(history, /空中设备电量耗尽/)
  assert.match(history, /净额/)
  assert.match(airspaceInspector, /activeIncursions/)
  assert.match(airspaceInspector, /最近结算/)
  assert.match(airspaceInspector, /粉钻挑战/)
})

test('planned UAV route uses the frozen executable polyline without logistics incident markers', () => {
  assert.doesNotMatch(missionMap, /densifyCatmullRom/)
  assert.match(missionMap, /noFlyZones/)
  assert.match(missionMap, /new mapvthree\.Polygon/)
  assert.doesNotMatch(missionMap, /randomized === true|kind:\s*['"]INCIDENT['"]|is-incident/)
  assert.match(planner, /互动空域/)
  assert.doesNotMatch(planner, /随机(?:任务|运营)情况|randomSituationCount/)
})

test('digital airspace renders multi-volume corridors, threat styling and runtime actions', () => {
  assert.match(missionMap, /runtimeVolumes/)
  assert.match(missionMap, /ABSOLUTE_NO_FLY/)
  assert.match(missionMap, /THREAT_OPACITY/)
  assert.match(missionMap, /conflictSegments/)
  assert.match(missionMap, /ALTITUDE_CORRIDOR/)
  assert.match(missionMap, /corridorFloorMeters/)
  assert.match(airspaceInspector, /预测空域冲突/)
  assert.doesNotMatch(airspaceInspector, /AIRSPACE CONFLICT/)
  assert.match(airspaceInspector, /从侧面绕飞/)
  assert.match(airspaceInspector, /爬升越过/)
  assert.match(airspaceInspector, /调整高度穿廊/)
  assert.match(airspaceInspector, /等待解除/)
  assert.match(airspaceInspector, /立即返航/)
})

test('airspace action confirmation stays in its panel and does not cover follow controls', () => {
  assert.match(airspaceInspector, /volume\.selectedAction/)
  assert.doesNotMatch(missionRuntime, /notice\.value = result\.action\?\.message/)
  assert.match(spatialScreen, /\.system-notice[^}]*top:132px/)
  assert.match(spatialScreen, /\.system-notice[^}]*font-size:clamp\(\.875rem/)
  assert.match(spatialScreen, /\.system-notice::before[^}]*content:"!"/)
  assert.match(spatialScreen, /@keyframes system-notice-enter/)
  assert.match(spatialScreen, /\.system-notice[^}]*pointer-events:none/)
})

test('route sampler invalidates when any detour waypoint changes', () => {
  assert.match(missionMap, /const samplerKey = plannedSignature/)
  assert.match(missionMap, /polylineGeometryKey\(points\)/)
})

test('imported fleet models and aircraft effects share the route anchor', () => {
  assert.match(missionMap, /const center = box\.getCenter\(new THREE\.Vector3\(\)\)/)
  assert.match(missionMap, /centerSceneForTransform\(root, center\)/)
  assert.match(missionMap, /type === 'smart_drone'[\s\S]*groundedBox\.getCenter\(new THREE\.Vector3\(\)\)\.z/)
  assert.doesNotMatch(missionMap, /effects\.halo\.position\.z \+=/)
})

test('reward models spin around a centred transform root', () => {
  assert.match(missionMap, /centerSceneForTransform\(scene, center\)/)
  assert.match(missionMap, /spinRoot\.add\(object\)/)
  assert.match(missionMap, /if \(isDiamond\) object\.rotation\.x = DIAMOND_UPRIGHT_ROTATION_X/)
  assert.match(missionMap, /if \(isTrophy\) object\.rotation\.x = TROPHY_UPRIGHT_ROTATION_X/)
  assert.match(missionMap, /record\.spinRoot\.rotation\.z \+=/)
  assert.doesNotMatch(missionMap, /record\.pivot\.rotation\.z \+=/)
  assert.doesNotMatch(missionMap, /function createTrophyGeometry/)
})

test('trophy preserves its silver star material and uses a stronger dedicated highlight', () => {
  assert.match(missionMap, /material\\\.002\|star/)
  assert.match(missionMap, /material\.color\?\.set\('#f4f7ff'\)/)
  assert.match(missionMap, /material\.emissive\?\.set\('#dbe7ff'\)/)
  assert.match(missionMap, /record\.isTrophy \? \.7/)
  assert.match(missionMap, /record\.isTrophy \? \.44/)
})

test('planning preview renders ground, air and diamond reward models before mission start', () => {
  assert.match(missionMap, /const planning = props\.planningPreview === true/)
  assert.match(missionMap, /!active && !replaying && !planning/)
  assert.match(missionMap, /const points = \[\.\.\.deliveryPoints, \.\.\.diamonds\]/)
})

test('map rotation is remapped from the right button to the middle button', () => {
  assert.match(missionMap, /remapMapRotationPointerDown/)
  assert.match(missionMap, /event\.button === 2/)
  assert.match(missionMap, /event\.button !== 1/)
  assert.match(missionMap, /new PointerEvent\('pointerdown'/)
  assert.match(missionMap, /preventMapContextMenu/)
  assert.match(missionMap, /preventMapAuxiliaryClick/)
})
