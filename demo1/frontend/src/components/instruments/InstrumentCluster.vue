<template>
  <section class="instrument-cluster" :class="`battery-${groundTone}`" aria-label="地面与空中设备仪表" data-tutorial-id="mission-battery">
    <div class="cluster-caption">
      <span class="instrument-kicker">地面车辆</span>
      <b>{{ ground.name }}</b>
    </div>
    <div class="cluster-core">
      <div class="cluster-arc" :style="{ '--cluster-progress': `${ground.progress * 3.6}deg` }">
        <div class="cluster-progress-value"><strong>{{ value(ground.progress, 0) }}</strong><small>%</small></div>
        <span>配送进度</span>
      </div>
      <div class="cluster-readings">
        <div class="device-battery ground-battery" :class="`reading-${groundTone}`">
          <InstrumentValue class="battery-reading" label="地面电量" :value="value(ground.battery, 0)" unit="%" />
          <span v-if="ground.depleted" class="battery-warning">电量耗尽 · 车辆已停驶</span>
          <span v-else class="battery-state">{{ batteryLabel(groundTone) }}</span>
        </div>
        <div class="device-battery air-battery" :class="`reading-${airTone}`">
          <div class="air-name">{{ air.name }}</div>
          <InstrumentValue class="battery-reading" label="空中电量" :value="value(air.battery, 0)" unit="%" />
          <span v-if="air.depleted" class="battery-warning">电量耗尽 · 空中设备已停飞</span>
          <span v-else class="battery-state">{{ batteryLabel(airTone) }}</span>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useMissionContext } from '../../mission/context/useMissionContext'
import { airVehicleSummary, groundVehicleSummary } from '../../mission/presentation/missionSelectors.mjs'
import { batteryTone } from '../../fleet/vehicleGameplay.mjs'
import { instrumentFleetVehicleState } from '../../fleet/instrumentFleetState.mjs'
import InstrumentValue from '../spatial/InstrumentValue.vue'

const props = defineProps({ fleetRuntime: { type: Object, required: true } })
const context = useMissionContext()
const clockNow = ref(Date.now())
let clockTimer = 0

function synchronizedSummary(base, category) {
  const fleetState = instrumentFleetVehicleState(
    context.mission.value,
    props.fleetRuntime.snapshot.value,
    category,
    clockNow.value
  )
  if (!fleetState) return base
  return {
    ...base,
    name: fleetState.name || base.name,
    battery: fleetState.batteryPercent,
    depleted: fleetState.batteryPercent <= 0
  }
}

const ground = computed(() => synchronizedSummary(groundVehicleSummary(context.mission.value), 'GROUND'))
const air = computed(() => synchronizedSummary(airVehicleSummary(context.mission.value), 'AIR'))
const groundTone = computed(() => batteryTone(ground.value.battery))
const airTone = computed(() => batteryTone(air.value.battery))
const batteryLabel = tone => ({ green: '电量充足', yellow: '请关注电量', orange: '建议尽快补能', red: '低电量警告', depleted: '电量耗尽', unknown: '等待遥测' })[tone]
function value(input, digits) { const numeric = Number(input); return Number.isFinite(numeric) ? numeric.toFixed(digits) : '—' }

onMounted(() => { clockTimer = window.setInterval(() => { clockNow.value = Date.now() }, 1000) })
onBeforeUnmount(() => { if (clockTimer) window.clearInterval(clockTimer) })
</script>

<style scoped>
.instrument-cluster { position:absolute; right:24px; top:52%; z-index:var(--layer-instrument); width:310px; color:var(--text-primary); pointer-events:none; transform:translateY(-10%); }
.cluster-caption { display:flex; justify-content:space-between; align-items:end; padding:0 4px 7px 24px; border-bottom:1px solid var(--surface-line); }
.cluster-caption b { color:var(--text-secondary); font-size:var(--type-telemetry); font-weight:500; }
.cluster-core { display:grid; grid-template-columns:118px 1fr; align-items:center; gap:16px; padding:14px 4px 14px 14px; background:linear-gradient(90deg,rgba(3,12,20,.78),rgba(3,12,20,.28),transparent); }
.cluster-arc { position:relative; width:112px; height:112px; display:grid; place-content:center; text-align:center; }
.cluster-arc::before { content:""; position:absolute; inset:0; border:1px solid var(--surface-line-strong); border-right-color:var(--signal-primary); border-bottom-color:transparent; border-radius:50%; transform:rotate(-35deg); }
.cluster-arc::after { content:""; position:absolute; inset:9px; border:1px solid rgba(124,231,238,.1); border-radius:50%; }
.cluster-progress-value { display:flex; justify-content:center; align-items:baseline; gap:4px; white-space:nowrap; }
.cluster-arc strong { font-size:2.8rem; font-weight:300; line-height:.85; }
.cluster-arc small { flex:0 0 auto; color:var(--signal-primary); font-size:var(--type-telemetry); }
.cluster-arc span{margin-top:4px;color:var(--text-tertiary);font-size:var(--type-micro);letter-spacing:.12em}
.cluster-readings { display:grid; gap:10px; }
.device-battery{display:grid;gap:3px;padding-bottom:8px;border-bottom:1px solid rgba(172,217,227,.09);--reading-color:var(--signal-primary)}.device-battery:last-child{padding-bottom:0;border-bottom:0}.air-name{max-width:150px;overflow:hidden;color:var(--text-tertiary);font-size:var(--type-micro);text-overflow:ellipsis;white-space:nowrap}.reading-green{--reading-color:#62ef9b}.reading-yellow{--reading-color:#f2df66}.reading-orange{--reading-color:#ff9f43}.reading-red,.reading-depleted{--reading-color:#ff5f62}.device-battery .battery-reading :deep(.instrument-value),.device-battery .battery-reading :deep(.instrument-value small){color:var(--reading-color)}
.battery-state,.battery-warning{font-size:var(--type-micro);letter-spacing:.06em}.battery-state{color:var(--text-secondary)}.battery-warning{color:#ff6b67;font-weight:750}.battery-reading :deep(.instrument-value){display:flex;align-items:baseline;gap:4px;white-space:nowrap}.battery-reading :deep(.instrument-value),.battery-reading :deep(.instrument-value small){color:var(--battery-color,var(--signal-primary))}.battery-reading :deep(.instrument-value small){margin-left:0;flex:0 0 auto}
.battery-green{--battery-color:#62ef9b}.battery-yellow{--battery-color:#f2df66}.battery-orange{--battery-color:#ff9f43}.battery-red,.battery-depleted{--battery-color:#ff5f62}.battery-depleted .cluster-core{background:linear-gradient(90deg,rgba(55,8,12,.82),rgba(28,5,8,.38),transparent)}
@media (max-width:1350px) { .instrument-cluster { width:270px; right:18px; }.cluster-core{grid-template-columns:98px 1fr}.cluster-arc{width:94px;height:94px}.cluster-arc strong{font-size:2.3rem} }
</style>
