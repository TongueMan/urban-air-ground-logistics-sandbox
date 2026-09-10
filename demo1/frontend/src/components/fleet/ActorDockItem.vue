<template>
  <button class="actor-dock-item" type="button" :aria-pressed="focused" @click="$emit('focus', actor.id)">
    <span class="actor-copy"><b>{{ deployment?.name || actor.name }}</b><small>{{ activityLabel }}</small></span>
    <StatusPulse :status="actor.activity === 'STANDBY' ? 'NEUTRAL' : 'ACTIVE'" :animated="focused" />
  </button>
</template>

<script setup>
import { computed } from 'vue'
import StatusPulse from '../spatial/StatusPulse.vue'

const props = defineProps({
  actor: { type: Object, required: true },
  deployment: { type: Object, default: null },
  focused: { type: Boolean, default: false }
})
defineEmits(['focus'])
const activityLabel = computed(() => {
  let activity
  if (props.deployment?.independentRoute && props.actor.activity === 'ON_CARRIER') activity = '独立航线待命'
  else if (props.deployment?.independentRoute && props.actor.activity === 'DOCKED') activity = '已完成独立航线'
  else activity = ({ ON_CARRIER: '车载待命', LAUNCHING: '正在起飞', DELIVERING: '执行配送', RETURNING: '交付后返航', DOCKED: '已回收入舱', MOVING: '前往分拨点', DEPLOY_SUPPORT: '起飞保障', GROUND_SUPPORT: '干线配送', RENDEZVOUS: '前往会合', RENDEZVOUS_WAIT: '等待无人机返航', SIGNAL_WAIT: '等待路口放行', RECOVERING: '回收作业', COMPLETE: '配送完成', STANDBY: '待命' })[props.actor.activity] || props.actor.activity
  const battery = Number(props.actor.telemetry?.battery)
  if (props.actor.kind !== 'UAV' || !Number.isFinite(battery)) return activity
  const multiplier = Number(props.actor.telemetry?.energyMultiplier || 1)
  const risk = multiplier > 1.01 ? ` · 风险耗电 ×${multiplier.toFixed(1)}` : ''
  return `${activity} · 电量 ${Math.max(0, battery).toFixed(0)}%${risk}`
})
</script>

<style scoped>
.actor-dock-item { min-width:0; display:grid; grid-template-columns:minmax(0,1fr) 10px; gap:8px; align-items:center; padding:7px 8px; border:0; border-left:1px solid var(--surface-line); color:var(--text-secondary); background:transparent; text-align:left; cursor:pointer; }
.actor-dock-item:hover,.actor-dock-item:focus-visible { color:var(--text-primary); background:rgba(124,231,238,.06); outline:none; }
.actor-dock-item[aria-pressed="true"] { border-left-color:var(--signal-primary); color:var(--text-primary); background:linear-gradient(90deg,rgba(124,231,238,.14),transparent); }
.actor-copy { min-width:0; }
.actor-copy b,.actor-copy small { display:block; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.actor-copy b { font-size:var(--type-telemetry); font-weight:650; }
.actor-copy small { margin-top:2px; color:var(--text-tertiary); font-size:var(--type-micro); }
</style>
