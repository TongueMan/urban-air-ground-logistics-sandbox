<template>
  <header class="mission-state" :class="{ 'is-planning': planning }">
    <div class="mission-identity">
      <span class="instrument-kicker">MISSION / {{ mission.definitionId }}</span>
      <h1>{{ displayMissionName }}</h1>
    </div>
    <div class="mission-phase">
      <span class="phase-number">{{ phaseNumber }}</span>
      <div><b>{{ phaseLabel }}</b><small>{{ phaseContext }}</small></div>
    </div>
    <div class="mission-runtime">
      <div class="runtime-status"><StatusPulse :status="mission.status" :animated="mission.status === 'RUNNING'" /><span>{{ statusLabel }}</span></div>
      <strong>{{ runtimeClock }}</strong>
      <small>{{ runtimeCaption }}</small>
    </div>
    <div class="mission-balance" aria-live="polite">
      <span>可用资金</span>
      <strong>{{ formatMoney(displayBalanceMinor) }}</strong>
      <small v-if="balanceDelta" :key="balanceDelta.key" :class="balanceDelta.positive ? 'is-income' : 'is-fine'">
        {{ balanceDelta.positive ? '+' : '-' }}{{ formatMoney(Math.abs(balanceDelta.amountMinor)) }} · {{ balanceDelta.label }}
      </small>
    </div>
    <div class="mission-trigger">
      <button v-if="!missionInProgress && !runtime.advancedRoutingUnlocked.value" class="start-button" :class="{ 'has-preview': runtime.taskPreview.value }" type="button" data-tutorial-id="create-mission" :disabled="runtime.busy.value" @click="runtime.openPlanner">
        {{ runtime.taskPreview.value ? '继续配置配送任务' : '生成配送任务' }}
      </button>
      <div v-else-if="!missionInProgress" class="delivery-split">
        <button class="start-button split-main" type="button" data-tutorial-id="create-mission" :disabled="runtime.busy.value" aria-label="生成基础配送任务" @click="runtime.openPlannerMode('BASIC')">{{ runtime.taskPreview.value ? '继续配置配送任务' : '生成配送任务' }}</button>
        <button class="start-button split-arrow" type="button" :aria-expanded="splitOpen" aria-label="选择配送玩法" @click="splitOpen = !splitOpen">⌄</button>
        <div v-if="splitOpen" class="split-menu" role="menu"><button type="button" role="menuitem" @click="chooseMode('BASIC')">基础配送</button><button type="button" role="menuitem" @click="chooseMode('ADVANCED')">进阶规划</button></div>
      </div>
      <button v-else class="control-button" type="button" data-tutorial-id="mission-control" @click="openMissionControl">任务控制</button>
      <button class="history-button" type="button" :disabled="runtime.busy.value" @click="runtime.openHistory">历史任务</button>
    </div>
  </header>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useMissionContext } from '../../mission/context/useMissionContext'
import StatusPulse from '../spatial/StatusPulse.vue'

const props = defineProps({
  runtime: { type: Object, required: true },
  fleetRuntime: { type: Object, required: true }
})
const runtime = props.runtime
const fleetRuntime = props.fleetRuntime
const context = useMissionContext()
const splitOpen = ref(false)
const mission = computed(() => context.mission.value)
const activePhase = computed(() => context.activePhase.value)
const missionInProgress = computed(() => ['QUEUED', 'RUNNING'].includes(String(mission.value.status || '').toUpperCase()))
const planning = computed(() => runtime.plannerOpen.value && context.timeMode.value !== 'REPLAY')
const phaseLabel = computed(() => planning.value ? 'PLANNING' : context.timeMode.value === 'REPLAY' ? activePhase.value?.shortLabel || 'REPLAY' : mission.value.status === 'COMPLETED' ? 'COMPLETE' : activePhase.value?.shortLabel || 'STANDBY')
const phaseNumber = computed(() => {
  if (planning.value) return '00'
  const index = mission.value.phases.findIndex(item => item.id === mission.value.activePhaseId)
  return index < 0 ? '00' : String(index + 1).padStart(2, '0')
})
const statusLabel = computed(() => planning.value ? '任务规划' : context.timeMode.value === 'REPLAY' ? 'MISSION REPLAY' : ({ STANDBY: '等待任务', QUEUED: '排队中', RUNNING: 'MISSION ACTIVE', COMPLETED: '任务完成', STOPPED: '任务已中止', EXPIRED: '任务已释放', FAILED: '任务异常' })[mission.value.status] || mission.value.status)
const displayMissionName = computed(() => String(mission.value.name || '联合配送任务').replace(/·动态车机协同配送$/, ' · 联合配送任务'))
const terminalReasonLabel = computed(() => ({
  GROUND_BATTERY_DEPLETED: '车辆电量耗尽',
  AIR_BATTERY_DEPLETED: '空中设备电量耗尽',
  ECONOMY_PROCESSING_FAILED: '收益结算异常'
})[mission.value.terminalReason] || '')
const statusDetail = computed(() => terminalReasonLabel.value ? `${statusLabel.value} · ${terminalReasonLabel.value}` : statusLabel.value)
const phaseContext = computed(() => planning.value
  ? runtime.taskPreview.value ? '路线预览 · 任务已就绪' : '选择区域 · 生成任务'
  : `${activePhase.value?.label || '等待任务'} · ${statusDetail.value}`)
const elapsed = computed(() => {
  const total = Math.max(0, Math.round(mission.value.clock.elapsedSeconds || 0))
  return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`
})
const previewDuration = computed(() => {
  const seconds = Math.max(0, Math.round(Number(runtime.taskPreview.value?.plan?.estimatedDurationSeconds || runtime.taskPreview.value?.plan?.durationSeconds || 0)))
  return seconds ? `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}` : '--:--'
})
const runtimeClock = computed(() => planning.value ? previewDuration.value : elapsed.value)
const runtimeCaption = computed(() => planning.value ? '预计时间 · ROUTE PREVIEW' : `${mission.value.progress.toFixed(1)}% · ELAPSED`)
const displayBalanceMinor = ref(Number(fleetRuntime.company.value?.balanceMinor || 0))
let balanceAnimationFrame = 0
const reduceMotion = typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
watch(() => Number(fleetRuntime.company.value?.balanceMinor || 0), target => {
  if (reduceMotion || typeof requestAnimationFrame !== 'function') { displayBalanceMinor.value = target; return }
  if (balanceAnimationFrame) cancelAnimationFrame(balanceAnimationFrame)
  const from = displayBalanceMinor.value
  const startedAt = performance.now()
  const animate = now => {
    const ratio = Math.min(1, (now - startedAt) / 500)
    const eased = 1 - Math.pow(1 - ratio, 3)
    displayBalanceMinor.value = Math.round(from + (target - from) * eased)
    if (ratio < 1) balanceAnimationFrame = requestAnimationFrame(animate)
    else balanceAnimationFrame = 0
  }
  balanceAnimationFrame = requestAnimationFrame(animate)
}, { immediate: true })
const balanceDelta = computed(() => {
  const transaction = runtime.latestEconomyTransaction?.value
  const amountMinor = Number(transaction?.amountMinor || 0)
  if (!transaction || !amountMinor) return null
  return {
    key: `${transaction.id || transaction.entryKey}:${transaction.receivedAt || ''}`,
    amountMinor,
    positive: amountMinor > 0,
    label: transactionLabel(transaction)
  }
})
function transactionLabel(transaction) {
  if (transaction.entryType === 'AIRSPACE_FINE') return '禁飞罚款'
  if (transaction.entryType === 'TIMELINESS_REWARD') return '时效奖励'
  if (transaction.entryType === 'DIAMOND_REWARD') return '粉钻奖励'
  if (transaction.entryType === 'DELIVERY_REWARD' && transaction.metadata?.kind === 'GROUND') return '运载收益'
  if (transaction.entryType === 'DELIVERY_REWARD' && transaction.metadata?.kind === 'AIR') return '空中配送奖励'
  return '配送收益'
}
function openMissionControl() {
  runtime.closeAirspace()
  context.openActionMode('CONTROL')
}
function chooseMode(mode) { splitOpen.value = false; runtime.openPlannerMode(mode) }
function formatMoney(minor) {
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', maximumFractionDigits: 0 }).format(Number(minor || 0) / 100)
}
onBeforeUnmount(() => { if (balanceAnimationFrame) cancelAnimationFrame(balanceAnimationFrame) })
</script>

<style scoped>
.mission-state { position:absolute; top:0; left:0; right:0; z-index:var(--layer-instrument); min-height:78px; display:grid; grid-template-columns:minmax(250px,1fr) auto auto auto auto; align-items:center; gap:clamp(14px,2.2vw,40px); padding:10px clamp(18px,2vw,34px); background:linear-gradient(180deg,rgba(2,9,15,.96),rgba(2,9,15,.72) 70%,transparent); pointer-events:none; }
.mission-state>* { pointer-events:auto; }
.mission-state.is-planning .phase-number { color:var(--signal-mint); }
.mission-state.is-planning .mission-runtime strong { color:var(--text-secondary); }
.mission-identity h1 { max-width:560px; margin:4px 0 0; overflow:hidden; font-size:clamp(1.05rem,1.45vw,1.4rem); font-weight:620; letter-spacing:.04em; text-overflow:ellipsis; white-space:nowrap; }
.mission-phase { display:flex; align-items:center; gap:10px; }
.phase-number { color:var(--signal-primary); font-size:clamp(2.15rem,3.2vw,3.7rem); font-weight:300; line-height:.85; }
.mission-phase b,.mission-phase small { display:block; }
.mission-phase b { font-size:var(--type-section); letter-spacing:.12em; }
.mission-phase small { margin-top:3px; color:var(--text-secondary); font-size:var(--type-micro); }
.mission-runtime { min-width:100px; text-align:right; }
.runtime-status { display:flex; justify-content:flex-end; align-items:center; gap:8px; color:var(--text-secondary); font-size:var(--type-micro); letter-spacing:.08em; }
.mission-runtime strong { display:block; margin-top:4px; font-size:1.45rem; font-weight:400; font-variant-numeric:tabular-nums; }
.mission-runtime>small { color:var(--text-tertiary); font-size:var(--type-micro); }
.mission-balance{position:relative;min-width:128px;text-align:right}.mission-balance>span{display:block;color:var(--text-tertiary);font-size:var(--type-micro);letter-spacing:.1em}.mission-balance>strong{display:block;margin-top:3px;color:#fff0a6;font-size:1.2rem;font-weight:620;font-variant-numeric:tabular-nums}.mission-balance>small{position:absolute;right:0;top:100%;white-space:nowrap;font-size:var(--type-micro);font-weight:750;animation:balance-delta 1.6s ease forwards}.mission-balance .is-income{color:var(--signal-mint)}.mission-balance .is-fine{color:#ff8194}@keyframes balance-delta{0%{opacity:0;transform:translateY(5px)}18%,70%{opacity:1;transform:translateY(0)}100%{opacity:0;transform:translateY(-5px)}}
.mission-trigger{display:flex;gap:7px}.start-button,.control-button,.history-button { min-height:40px; padding:0 14px; border-radius:2px; font-weight:750; letter-spacing:.04em; }
.start-button { border:0; color:var(--world-void); background:var(--signal-mint); }
.start-button.has-preview { border:1px solid rgba(126,240,196,.32); color:#a8e8d1; background:rgba(8,37,39,.62); }
.delivery-split{position:relative;display:flex}.delivery-split .split-main{border-radius:2px 0 0 2px}.delivery-split .split-arrow{min-width:34px;padding:0;border-left:1px solid rgba(3,23,16,.28);border-radius:0 2px 2px 0}.split-menu{position:absolute;right:0;top:calc(100% + 6px);z-index:20;display:grid;min-width:144px;padding:5px;border:1px solid rgba(126,240,196,.28);background:#061823;box-shadow:0 12px 30px rgba(0,0,0,.48)}.split-menu button{min-height:34px;border:0;color:var(--text-primary);background:transparent;text-align:left}.split-menu button:hover,.split-menu button:focus-visible{color:var(--signal-mint);background:rgba(126,240,196,.08)}
.control-button { border:1px solid var(--surface-line-strong); color:var(--text-primary); background:rgba(8,30,42,.75); }
.history-button { border:1px solid rgba(83,221,255,.22); color:var(--signal-primary); background:rgba(8,30,42,.75); }
@media (max-width:1300px) { .mission-state { grid-template-columns:minmax(210px,1fr) auto auto auto; gap:14px; }.mission-runtime { display:none; } }
@media (prefers-reduced-motion: reduce){.mission-balance>small{animation:none;opacity:1}}
</style>
