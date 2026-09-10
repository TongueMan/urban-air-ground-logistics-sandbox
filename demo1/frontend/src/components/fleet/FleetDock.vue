<template>
  <section class="fleet-dock spatial-instrument" aria-label="任务编组">
    <header>
      <div><span class="instrument-kicker">任务车队</span><b>{{ mission.actors.length }} 个任务设备</b></div>
      <div class="company-summary" aria-label="公司车队摘要">
        <span>{{ fleetRuntime.counts.value.total }} 台公司资产</span>
        <small>已出站 {{ fleetRuntime.counts.value.deployed }} / 车库中 {{ fleetRuntime.counts.value.garaged }}</small>
      </div>
      <button class="fleet-hub-entry" type="button" data-tutorial-id="fleet-hub-entry" @click="fleetRuntime.open">
        <span>车队中心</span><small>FLEET HUB</small><b>→</b>
      </button>
    </header>
    <div class="formation-strip" data-tutorial-id="mission-device-list">
      <article v-for="formation in visibleFormations" :key="formation.id" class="formation">
        <div class="formation-name"><span>{{ formation.label }}</span><small>{{ formation.members.length }} 个设备</small></div>
        <div class="formation-members">
          <ActorDockItem
            v-for="(member, memberIndex) in formation.members"
            :key="member.actorId"
            :actor="mission.actorsById[member.actorId]"
            :deployment="deploymentForActor(mission.actorsById[member.actorId])"
            :focused="context.focusedActorId.value === member.actorId"
            :data-device-id="member.actorId"
            :data-tutorial-id="memberIndex === 0 ? 'mission-device' : undefined"
            @focus="context.focusActor"
          />
        </div>
      </article>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import { useMissionContext } from '../../mission/context/useMissionContext'
import ActorDockItem from './ActorDockItem.vue'

const props = defineProps({ fleetRuntime: { type: Object, required: true } })
const context = useMissionContext()
const mission = computed(() => context.mission.value)
const fleetRuntime = props.fleetRuntime
const visibleFormations = computed(() => (mission.value.formations || []).map(formation => ({
  ...formation,
  members: (formation.members || []).filter(member => Boolean(mission.value.actorsById?.[member.actorId]))
})).filter(formation => formation.members.length > 0))
function deploymentForActor(actor) {
  return actor?.kind === 'UAV'
    ? mission.value.airVehicle
      ? { ...mission.value.airVehicle, role: 'smart_drone', category: 'AIR' }
      : fleetRuntime.activeDeployments.value.smart_drone
    : actor?.kind === 'VEHICLE' && mission.value.groundVehicle
      ? { ...mission.value.groundVehicle, role: 'ground_vehicle', category: 'GROUND' }
      : actor?.kind === 'VEHICLE' ? fleetRuntime.activeDeployments.value.ground_vehicle : null
}
</script>

<style scoped>
.fleet-dock { position:absolute; left:18px; bottom:42px; z-index:var(--layer-instrument); width:min(520px,34.5vw); min-width:390px; padding:11px 13px 13px; pointer-events:auto; clip-path:polygon(0 0,100% 0,100% calc(100% - 13px),calc(100% - 13px) 100%,0 100%); }
header { display:grid; grid-template-columns:1fr auto auto; align-items:center; gap:12px; margin-bottom:8px; }
header>div:first-child { display:grid; gap:3px; }
.instrument-kicker { font-size:12px; }
header b { color:var(--text-secondary); font-size:12px; font-weight:500; }
.company-summary { display:grid; gap:2px; padding-left:10px; border-left:1px solid var(--surface-line); text-align:right; }
.company-summary span { color:var(--signal-primary); font:700 12px/1 monospace; }.company-summary small{color:var(--text-secondary);font:12px/1.2 sans-serif}
.fleet-hub-entry { min-width:118px; height:40px; display:grid; grid-template-columns:1fr auto; align-items:center; gap:2px 8px; padding:5px 9px 5px 11px; border:1px solid rgba(124,231,238,.42); color:var(--text-primary); text-align:left; background:rgba(8,43,54,.82); }
.fleet-hub-entry span { font-size:13px; font-weight:700; }.fleet-hub-entry small{grid-column:1;color:var(--text-secondary);font:12px/1 monospace;letter-spacing:.04em}.fleet-hub-entry b{grid-column:2;grid-row:1/3;color:var(--signal-primary);font-size:17px}.fleet-hub-entry:hover,.fleet-hub-entry:focus-visible{border-color:var(--signal-primary);outline:none;background:rgba(13,64,76,.94)}
.formation-strip { display:flex; gap:8px; overflow-x:auto; scrollbar-width:none; }
.formation { min-width:230px; display:grid; grid-template-columns:74px 1fr; border-top:1px solid var(--surface-line); }
.formation-name { padding:8px 8px 0 0; }
.formation-name span,.formation-name small { display:block; }
.formation-name span { color:var(--text-secondary); font-size:var(--type-telemetry); }
.formation-name small { margin-top:3px; color:var(--text-secondary); font-size:12px; }
.formation-members { min-width:0; }
@media (max-width:1350px) { .fleet-dock{width:min(590px,45vw)}header{gap:7px}.formation{min-width:200px}.company-summary{padding-left:6px}.company-summary span,.company-summary small{font-size:12px}.fleet-hub-entry{min-width:108px;padding-left:8px} }
</style>
