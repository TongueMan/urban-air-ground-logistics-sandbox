<template>
  <nav class="mission-spine spatial-instrument" aria-label="任务阶段">
    <div class="spine-heading"><span class="instrument-kicker">MISSION SPINE</span><b>{{ completedCount }}/{{ mission.phases.length }}</b></div>
    <ol>
      <MissionNode v-for="(phase, index) in mission.phases" :key="phase.id" :phase="phase" :index="index" @select="selectPhase" />
    </ol>
  </nav>
</template>

<script setup>
import { computed } from 'vue'
import { useMissionContext } from '../../mission/context/useMissionContext'
import MissionNode from '../spatial/MissionNode.vue'

const context = useMissionContext()
const mission = computed(() => context.mission.value)
const completedCount = computed(() => mission.value.phases.filter(phase => phase.state === 'COMPLETE').length)
function selectPhase(phaseId) {
  if (!['COMPLETED', 'STOPPED', 'EXPIRED', 'FAILED'].includes(mission.value.status)) return
  const phase = mission.value.phases.find(item => item.id === phaseId)
  if (phase) context.enterReplay(phase.range[0])
}
</script>

<style scoped>
.mission-spine { position:absolute; top:112px; left:18px; z-index:var(--layer-instrument); width:194px; padding:13px 15px 14px; pointer-events:auto; clip-path:polygon(0 0,calc(100% - 16px) 0,100% 16px,100% 100%,12px 100%,0 calc(100% - 12px)); }
.spine-heading { display:flex; justify-content:space-between; align-items:center; margin-bottom:6px; }
.spine-heading b { color:var(--signal-primary); font-size:var(--type-telemetry); font-weight:500; }
ol { margin:0; padding:0; list-style:none; }
@media (max-height:760px) { .mission-spine { top:92px; transform:scale(.9); transform-origin:top left; } }
</style>
