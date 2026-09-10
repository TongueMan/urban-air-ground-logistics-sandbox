<template>
  <section class="world-layer" aria-label="物流配送任务空间">
    <MissionWorldBridge :runtime="runtime" :fleet-runtime="fleetRuntime" />
    <div class="world-provenance" aria-label="地图与路线来源">
      <b>{{ mission.world.coordinateSystem }}</b>
      <span>百度路线规划 · 人工校核</span>
      <span>V{{ mission.definitionVersion }}</span>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import { useMissionContext } from '../../mission/context/useMissionContext'
import MissionWorldBridge from './MissionWorldBridge.vue'

defineProps({
  runtime: { type: Object, required: true },
  fleetRuntime: { type: Object, required: true }
})
const context = useMissionContext()
const mission = computed(() => context.mission.value)
</script>

<style scoped>
.world-layer { position:absolute; inset:0; z-index:var(--layer-world); overflow:hidden; background:var(--world-void); }
.world-layer :deep(.mission-map) { min-height:0; border:0; border-radius:0; }
.world-provenance { position:absolute; left:18px; bottom:14px; z-index:var(--layer-world-ui); display:flex; gap:9px; align-items:center; color:var(--text-tertiary); font-size:var(--type-micro); pointer-events:none; }
.world-provenance b { color:var(--signal-primary); }
@media (max-width:1350px) { .world-provenance span:nth-of-type(1) { display:none; } }
</style>
