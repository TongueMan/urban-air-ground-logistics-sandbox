<template>
  <aside class="task-history" aria-label="历史配送任务">
    <header><div><span class="instrument-kicker">DELIVERY ARCHIVE</span><h2>历史配送任务</h2></div><button type="button" @click="runtime.closeHistory">×</button></header>
    <p class="retention">仅显示当前访客最近 30 天已执行任务</p>
    <div v-if="!runtime.historyItems.value.length" class="history-empty">暂无可复盘任务</div>
    <button v-for="item in runtime.historyItems.value" :key="item.runId" class="history-item" type="button" :disabled="runtime.busy.value" @click="runtime.openHistoricalRun(item.runId)">
      <span><b>{{ logisticsPresentationText(item.name) }}</b><small>{{ formatTime(item.createdAt) }}</small></span>
      <span class="meta">
        <em :class="{ failed: item.status === 'FAILED' }">{{ statusLabel(item) }}</em>
        <small>Seed {{ item.seed }}</small>
        <small class="economy-line">运载 {{ money(item.economy?.groundCargoRewardMinor) }} · 时效 {{ money(item.economy?.timelinessRewardMinor) }}</small>
        <small class="economy-line">空中 {{ money(airReward(item)) }} · 罚款 {{ money(item.economy?.penaltyChargedMinor ?? item.penaltyChargedMinor) }}</small>
        <strong :class="Number(item.economy?.netMinor ?? item.netMinor ?? 0) < 0 ? 'negative' : ''">净额 {{ signedMoney(item.economy?.netMinor ?? item.netMinor) }}</strong>
      </span>
    </button>
  </aside>
</template>

<script setup>
import { logisticsPresentationText } from '../../mission/presentation/logisticsVocabulary.mjs'

const props = defineProps({ runtime: { type: Object, required: true } })
const runtime = props.runtime
function formatTime(value) { try { return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) } catch { return value } }
function money(minor) { return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', maximumFractionDigits: 0 }).format(Number(minor || 0) / 100) }
function signedMoney(minor) { const value = Number(minor || 0); return `${value >= 0 ? '+' : '-'}${money(Math.abs(value))}` }
function airReward(item) { return Number(item.economy?.airCoinRewardMinor || 0) + Number(item.economy?.collectedDiamondMinor || 0) }
function statusLabel(item) {
  if (item.terminalReason === 'GROUND_BATTERY_DEPLETED') return '失败 · 车辆电量耗尽'
  if (item.terminalReason === 'AIR_BATTERY_DEPLETED') return '失败 · 空中设备电量耗尽'
  return ({ COMPLETED: '已完成', FAILED: '失败', STOPPED: '已中止', EXPIRED: '已释放', RUNNING: '执行中', QUEUED: '排队中' })[item.status] || item.status
}
</script>

<style scoped>
.task-history{position:absolute;top:88px;right:22px;bottom:24px;z-index:var(--layer-system);width:min(410px,calc(100vw - 44px));padding:17px;border:1px solid rgba(83,221,255,.25);background:rgba(2,13,22,.96);box-shadow:0 24px 80px rgba(0,0,0,.5);pointer-events:auto;overflow:auto}.task-history header{display:flex;justify-content:space-between;align-items:flex-start}.task-history h2{margin:4px 0;font-size:1.2rem}.task-history header button{border:0;background:transparent;color:var(--text-secondary);font-size:1.7rem}.retention{margin:12px 0;color:var(--text-tertiary);font-size:.72rem}.history-item{display:flex;justify-content:space-between;gap:14px;width:100%;padding:13px 10px;border:0;border-top:1px solid rgba(83,221,255,.13);color:var(--text-primary);background:transparent;text-align:left}.history-item:hover{background:rgba(83,221,255,.07)}.history-item span{display:grid;gap:5px}.history-item small{color:var(--text-tertiary)}.history-item .meta{text-align:right}.history-item em{color:var(--signal-mint);font-size:.68rem;font-style:normal}.history-empty{display:grid;place-content:center;height:180px;color:var(--text-tertiary)}
.history-item .economy-line{max-width:220px;color:#b9cbd3}.history-item em.failed{color:#ff8598}.history-item strong{color:#8fffd8;font-size:.72rem;font-variant-numeric:tabular-nums}.history-item strong.negative{color:#ff8598}
</style>
