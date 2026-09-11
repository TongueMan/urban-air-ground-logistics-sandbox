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
      <InstrumentCluster v-if="context.mission.value.actors.length" :fleet-runtime="fleetRuntime" />
      <ActionLayer :runtime="runtime" />
      <TaskPlanner v-if="runtime.plannerOpen.value" :runtime="runtime" />
      <TaskHistory v-if="runtime.historyOpen.value" :runtime="runtime" />
      <AirspacePanel v-if="runtime.selectedAirspaceId.value" :runtime="runtime" />
      <MissionTimeline :runtime="runtime" />
      <BatteryRecoveryBanner :fleet-runtime="fleetRuntime" />
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
import BatteryRecoveryBanner from '../components/fleet/BatteryRecoveryBanner.vue'
import MissionReactionOverlay from '../components/mission/MissionReactionOverlay.vue'

defineProps({
  runtime: { type: Object, required: true },
  fleetRuntime: { type: Object, required: true }
})
const context = useMissionContext()
</script>

<style scoped>
.spatial-mission-screen { position:relative; width:100%; height:100%; overflow:hidden; color:var(--text-primary); background:var(--world-void); isolation:isolate; }
.system-notice { position:absolute; left:50%; top:132px; z-index:var(--layer-system); display:flex; align-items:center; gap:10px; max-width:min(680px,calc(100vw - 500px)); box-sizing:border-box; margin:0; padding:11px 17px 11px 13px; border:1px solid rgba(255,197,111,.34); border-left:4px solid var(--signal-warning); border-radius:3px; color:#fff0cf; background:linear-gradient(100deg,rgba(62,41,10,.96),rgba(38,27,11,.94)); box-shadow:0 10px 30px rgba(0,0,0,.36),0 0 22px rgba(255,197,111,.09); font-size:clamp(.875rem,.82rem + .18vw,1rem); font-weight:650; line-height:1.45; text-align:left; letter-spacing:.01em; pointer-events:none; transform:translateX(-50%); animation:system-notice-enter var(--motion-standard) var(--motion-ease-out) both; }
.system-notice::before { display:grid; flex:0 0 22px; width:22px; height:22px; place-items:center; border:1px solid rgba(255,218,156,.72); border-radius:50%; color:#2d1c04; background:var(--signal-warning); box-shadow:0 0 14px rgba(255,197,111,.28); font:800 .75rem/1 sans-serif; content:"!"; }
.battery-recovery-banner + .system-notice{top:184px}
.spatial-mission-screen :deep(.mission-spine),
.spatial-mission-screen :deep(.fleet-dock),
.spatial-mission-screen :deep(.instrument-cluster) { transition:opacity var(--motion-standard) var(--motion-ease-out),filter var(--motion-standard) var(--motion-ease-out); }
.spatial-mission-screen.is-planning :deep(.mission-spine),
.spatial-mission-screen.is-planning :deep(.fleet-dock),
.spatial-mission-screen.is-planning :deep(.instrument-cluster) { opacity:.13; filter:saturate(.4); pointer-events:none; }
@media(max-width:1300px){.system-notice{left:46%;max-width:min(520px,calc(100vw - 48px))}}
@media(max-width:700px){.system-notice{left:50%;top:112px;max-width:calc(100vw - 28px);padding:10px 13px;font-size:.8125rem}.system-notice::before{flex-basis:20px;width:20px;height:20px}}
@keyframes system-notice-enter{from{opacity:0;transform:translate(-50%,-8px)}to{opacity:1;transform:translate(-50%,0)}}
@media(prefers-reduced-motion:reduce){.system-notice{animation:none}.spatial-mission-screen :deep(.mission-spine),.spatial-mission-screen :deep(.fleet-dock),.spatial-mission-screen :deep(.instrument-cluster){transition:none}}
</style>
