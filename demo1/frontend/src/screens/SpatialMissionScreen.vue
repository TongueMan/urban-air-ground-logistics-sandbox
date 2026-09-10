<template>
  <main
    class="spatial-mission-screen"
    :class="[
      `view-${context.viewMode.value.toLowerCase()}`,
      `time-${context.timeMode.value.toLowerCase()}`,
      { 'is-planning': runtime.plannerOpen.value }
    ]"
  >
    <WorldLayer :runtime="runtime" :fleet-runtime="fleetRuntime" />
    <SpatialOverlayHost>
      <MissionState :runtime="runtime" :fleet-runtime="fleetRuntime" />
      <MissionSpine />
      <FleetDock :fleet-runtime="fleetRuntime" />
      <InstrumentCluster v-if="context.mission.value.actors.length" />
      <ActionLayer :runtime="runtime" />
      <TaskPlanner v-if="runtime.plannerOpen.value" :runtime="runtime" />
      <TaskHistory v-if="runtime.historyOpen.value" :runtime="runtime" />
      <AirspacePanel v-if="runtime.selectedAirspaceId.value" :runtime="runtime" />
      <MissionTimeline :runtime="runtime" />
      <p v-if="runtime.notice.value" class="system-notice" role="status">{{ runtime.notice.value }}</p>
      <TutorialOverlay :runtime="runtime" :fleet-runtime="fleetRuntime" />
      <FleetHub v-if="fleetRuntime.isOpen.value" :runtime="fleetRuntime" />
      <MissionReactionOverlay :runtime="runtime" />
    </SpatialOverlayHost>
  </main>
</template>

<script setup>
import { useMissionContext } from '../mission/context/useMissionContext'
import WorldLayer from '../components/world/WorldLayer.vue'
import MissionState from '../components/mission/MissionState.vue'
import MissionSpine from '../components/mission/MissionSpine.vue'
import FleetDock from '../components/fleet/FleetDock.vue'
import InstrumentCluster from '../components/instruments/InstrumentCluster.vue'
import ActionLayer from '../components/action/ActionLayer.vue'
import SpatialOverlayHost from '../components/spatial/SpatialOverlayHost.vue'
import TaskPlanner from '../components/mission/TaskPlanner.vue'
import TaskHistory from '../components/mission/TaskHistory.vue'
import AirspacePanel from '../components/mission/AirspacePanel.vue'
import MissionTimeline from '../components/mission/MissionTimeline.vue'
import TutorialOverlay from '../tutorial/TutorialOverlay.vue'
import FleetHub from '../components/fleet/FleetHub.vue'
import MissionReactionOverlay from '../components/mission/MissionReactionOverlay.vue'

defineProps({
  runtime: { type: Object, required: true },
  fleetRuntime: { type: Object, required: true }
})
const context = useMissionContext()
</script>

<style scoped>
.spatial-mission-screen { position:relative; width:100%; height:100%; overflow:hidden; color:var(--text-primary); background:var(--world-void); isolation:isolate; }
.system-notice { position:absolute; left:50%; top:132px; z-index:var(--layer-system); max-width:min(560px,calc(100vw - 500px)); box-sizing:border-box; margin:0; padding:7px 12px; border-left:2px solid var(--signal-warning); color:#ffe4b5; background:rgba(46,31,10,.9); box-shadow:0 8px 24px rgba(0,0,0,.28); font-size:var(--type-telemetry); line-height:1.4; text-align:center; pointer-events:none; transform:translateX(-50%); }
.spatial-mission-screen :deep(.mission-spine),
.spatial-mission-screen :deep(.fleet-dock),
.spatial-mission-screen :deep(.instrument-cluster) { transition:opacity var(--motion-standard) var(--motion-ease-out),filter var(--motion-standard) var(--motion-ease-out); }
.spatial-mission-screen.is-planning :deep(.mission-spine),
.spatial-mission-screen.is-planning :deep(.fleet-dock),
.spatial-mission-screen.is-planning :deep(.instrument-cluster) { opacity:.13; filter:saturate(.4); pointer-events:none; }
@media(max-width:1300px){.system-notice{left:46%;max-width:360px}}
@media(prefers-reduced-motion:reduce){.spatial-mission-screen :deep(.mission-spine),.spatial-mission-screen :deep(.fleet-dock),.spatial-mission-screen :deep(.instrument-cluster){transition:none}}
</style>
