<template>
  <section v-if="visible" class="mission-timeline" :class="{ 'is-previewing': previewing, 'is-paused': paused, 'is-rewind-locked': rewindLocked }" data-tutorial-id="mission-timeline" aria-label="任务时间轴">
    <div class="timeline-status">
      <span class="instrument-kicker">TIME CONTROL</span>
      <b>{{ statusText }}</b>
    </div>

    <button v-if="running" class="transport-button" type="button" :disabled="runtime.timeControlBusy.value" @click="toggleMission">
      {{ paused ? '继续任务' : '暂停任务' }}
    </button>

    <div class="timeline-main">
      <div class="timeline-labels">
        <span>{{ formatTime(viewTimeMs) }}</span>
        <span>实时头 {{ liveProgress.toFixed(1) }}% · {{ formatTime(liveTimeMs) }}</span>
      </div>
      <div class="timeline-track">
        <div class="timeline-live" :style="{ width: `${liveProgress}%` }"></div>
        <div class="timeline-viewed" :style="{ width: `${cursor}%` }"></div>
        <button
          v-for="checkpoint in checkpoints"
          :key="checkpoint.id"
          class="checkpoint-marker"
          :class="[`status-${String(checkpoint.status || '').toLowerCase()}`, { selected: checkpoint.id === selectedCheckpointId, snapped: String(checkpoint.id) === snappedCheckpointId }]"
          type="button"
          :style="{ left: `${markerPosition(checkpoint)}%` }"
          :title="checkpointTitle(checkpoint)"
          :aria-label="checkpointTitle(checkpoint)"
          :disabled="rewindLocked"
          :data-checkpoint-id="checkpoint.id"
          :data-checkpoint-volume-id="checkpoint.volumeId"
          :data-tutorial-id="!rewindLocked && checkpoint.available && checkpoint.id === latestCheckpoint?.id ? 'mission-rewind-checkpoint' : undefined"
          @click="selectCheckpoint(checkpoint)"
        ></button>
        <input
          :value="cursor"
          type="range"
          min="0"
          :max="Math.max(0.1, liveProgress)"
          step="0.1"
          aria-label="查看历史任务进度"
          :disabled="rewindLocked"
          @input="scrub"
        >
      </div>
    </div>

    <button class="secondary-button" type="button" :class="{ active: replayPlaying }" :disabled="runtime.timeControlBusy.value || rewindLocked" @click="toggleReplay">
      {{ replayPlaying ? '停止回看' : '回看播放' }}
    </button>
    <button v-if="previewing" class="secondary-button" type="button" @click="returnToLive">返回当前</button>
    <button v-if="canRestore" class="rewind-button" type="button" data-tutorial-id="mission-rewind-restore" :disabled="runtime.rewindBusy.value || rewindLocked" @click="restoreSelected">
      {{ runtime.rewindBusy.value ? '正在回溯…' : '回到这里重新选择' }}
    </button>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useMissionContext } from '../../mission/context/useMissionContext'
import { snapTimelineProgress } from '../../mission/presentation/timelineSnapping.mjs'

const props = defineProps({ runtime: { type: Object, required: true } })
const runtime = props.runtime
const context = useMissionContext()
const replayPlaying = ref(false)
const selectedCheckpointId = ref('')
const snappedCheckpointId = ref('')
let replayFrame = 0
let replayUpdatedAt = 0
let scrubPending = false

const liveMission = computed(() => context.liveMission.value)
const session = computed(() => liveMission.value.source.session)
const rawTimeline = computed(() => liveMission.value.source.mission?.timeline || {})
const visible = computed(() => Boolean(session.value?.id) && !runtime.plannerOpen.value && session.value?.status !== 'QUEUED')
const running = computed(() => session.value?.status === 'RUNNING')
const paused = computed(() => running.value && Number(runtime.timeScale.value) === 0)
const previewing = computed(() => context.timeMode.value === 'REPLAY')
const rewindLocked = computed(() => Boolean(runtime.tutorialRewindLocked?.value))
const liveProgress = computed(() => Math.max(0, Math.min(100, Number(rawTimeline.value.liveProgress ?? liveMission.value.progress ?? 0))))
const cursor = computed(() => previewing.value ? Math.min(liveProgress.value, Number(context.timeCursor.value || 0)) : liveProgress.value)
const liveTimeMs = computed(() => Math.max(0, Number(rawTimeline.value.liveSimulationTimeMs ?? session.value?.simulationElapsedMs ?? 0)))
const viewTimeMs = computed(() => liveProgress.value > 0 ? liveTimeMs.value * cursor.value / liveProgress.value : 0)
const checkpoints = computed(() => Array.isArray(rawTimeline.value.checkpoints) ? rawTimeline.value.checkpoints : [])
const latestCheckpoint = computed(() => checkpoints.value.find(item => String(item.id) === String(rawTimeline.value.latestCheckpointId || '')) || null)
const canRestore = computed(() => Boolean(
  latestCheckpoint.value?.available
  && selectedCheckpointId.value === latestCheckpoint.value.id
  && rawTimeline.value.rewindEligible
  && ['RUNNING', 'FAILED'].includes(String(session.value?.status || ''))
))
const statusText = computed(() => {
  if (previewing.value) return '历史回看'
  if (paused.value) return '任务已暂停'
  if (session.value?.status === 'FAILED' && rawTimeline.value.rewindEligible) return '失败·可回溯'
  if (session.value?.status === 'COMPLETED') return '任务回放'
  return '实时推进'
})

async function toggleMission() {
  stopReplay()
  if (paused.value) await runtime.resumeMission()
  else await runtime.pauseMission()
}

async function scrub(event) {
  if (rewindLocked.value) return
  const snap = snapTimelineProgress(event.target.value, checkpoints.value, {
    liveProgress: liveProgress.value,
    activeCheckpointId: snappedCheckpointId.value
  })
  const value = snap.value
  stopReplay()
  snappedCheckpointId.value = snap.checkpointId
  selectedCheckpointId.value = snap.checkpoint?.available ? snap.checkpointId : ''
  if (snap.snapped) event.target.value = String(value)
  if (previewing.value) { context.setTimeCursor(value); return }
  if (scrubPending) return
  scrubPending = true
  try { await runtime.previewAt(value) } finally { scrubPending = false }
}

async function selectCheckpoint(checkpoint) {
  if (rewindLocked.value) return
  stopReplay()
  snappedCheckpointId.value = String(checkpoint.id || '')
  selectedCheckpointId.value = checkpoint.available ? checkpoint.id : ''
  await runtime.previewAt(Number(checkpoint.progress || 0))
}

async function toggleReplay() {
  if (rewindLocked.value) return
  if (replayPlaying.value) { stopReplay(); return }
  if (!previewing.value || cursor.value >= liveProgress.value - 0.05) {
    if (await runtime.previewAt(0)) startReplay()
    return
  }
  startReplay()
}

function startReplay() {
  snappedCheckpointId.value = ''
  selectedCheckpointId.value = ''
  replayPlaying.value = true
  replayUpdatedAt = performance.now()
  replayFrame = requestAnimationFrame(animateReplay)
}

function animateReplay(now) {
  if (!replayPlaying.value) return
  const elapsed = Math.max(0, now - replayUpdatedAt)
  replayUpdatedAt = now
  const next = Math.min(liveProgress.value, cursor.value + elapsed / 120)
  context.setTimeCursor(next)
  if (next >= liveProgress.value - 0.001) { stopReplay(); return }
  replayFrame = requestAnimationFrame(animateReplay)
}

function stopReplay() {
  replayPlaying.value = false
  if (replayFrame) cancelAnimationFrame(replayFrame)
  replayFrame = 0
}

function returnToLive() {
  stopReplay()
  snappedCheckpointId.value = ''
  selectedCheckpointId.value = ''
  context.returnToLive()
}

async function restoreSelected() {
  if (rewindLocked.value || !canRestore.value) return
  stopReplay()
  try {
    await runtime.restoreCheckpoint(selectedCheckpointId.value)
    selectedCheckpointId.value = ''
  } catch (_) {}
}

function markerPosition(checkpoint) {
  return liveProgress.value > 0 ? Math.max(0, Math.min(100, Number(checkpoint.progress || 0) / liveProgress.value * 100)) : 0
}
function checkpointTitle(checkpoint) {
  if (rewindLocked.value) return '关键节点将在教程提示后解锁'
  const state = checkpoint.available ? '可回溯' : checkpoint.status === 'USED' ? '已使用' : '已超过'
  return `${checkpoint.volumeId || '空域决策'} · ${Number(checkpoint.progress || 0).toFixed(1)}% · ${state}`
}
function formatTime(milliseconds) {
  const seconds = Math.max(0, Math.round(Number(milliseconds || 0) / 1000))
  return `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
}

watch(() => session.value?.id, () => { stopReplay(); snappedCheckpointId.value = ''; selectedCheckpointId.value = '' })
watch(() => context.timeMode.value, mode => { if (mode !== 'REPLAY') { stopReplay(); snappedCheckpointId.value = ''; selectedCheckpointId.value = '' } })
onBeforeUnmount(stopReplay)
</script>

<style scoped>
.mission-timeline{position:absolute;left:50%;bottom:18px;z-index:var(--layer-action);display:flex;align-items:center;gap:9px;width:min(920px,calc(100vw - 390px));min-height:52px;box-sizing:border-box;padding:8px 10px;border:1px solid rgba(83,221,255,.28);border-left:2px solid var(--signal-primary);color:var(--text-primary);background:linear-gradient(90deg,rgba(2,14,23,.95),rgba(5,25,35,.92));box-shadow:0 16px 42px rgba(0,0,0,.38);pointer-events:auto;transform:translateX(-50%);backdrop-filter:blur(12px)}
.mission-timeline.is-previewing{border-left-color:#b48cff}.mission-timeline.is-paused{box-shadow:0 16px 42px rgba(0,0,0,.38),inset 0 0 28px rgba(255,196,91,.04)}
.mission-timeline.is-rewind-locked .timeline-track input{cursor:not-allowed;opacity:.38}.mission-timeline.is-rewind-locked .checkpoint-marker{filter:saturate(.35);opacity:.38;cursor:not-allowed}
.timeline-status{display:grid;gap:2px;min-width:90px}.timeline-status b{color:var(--text-secondary);font-size:.67rem;white-space:nowrap}.transport-button,.secondary-button,.rewind-button{min-height:32px;padding:0 10px;border:1px solid rgba(112,207,228,.28);color:#dffaff;background:rgba(10,43,57,.74);font-size:.66rem;white-space:nowrap}.transport-button{border-color:rgba(83,221,255,.48);color:#aef7ff}.secondary-button.active{color:#d9c4ff;border-color:rgba(180,140,255,.55)}.rewind-button{border-color:rgba(255,179,80,.58);color:#ffe0a8;background:rgba(90,50,11,.65)}button:disabled{opacity:.45;cursor:not-allowed}
.timeline-main{display:grid;gap:3px;min-width:180px;flex:1}.timeline-labels{display:flex;justify-content:space-between;color:var(--text-tertiary);font-size:.56rem;font-variant-numeric:tabular-nums}.timeline-track{position:relative;height:22px}.timeline-live,.timeline-viewed{position:absolute;left:0;top:10px;height:2px;pointer-events:none}.timeline-live{background:rgba(102,143,154,.38)}.timeline-viewed{background:linear-gradient(90deg,var(--signal-primary),#b48cff)}.timeline-track input{position:absolute;inset:0;width:100%;height:22px;margin:0;opacity:.84;cursor:ew-resize;accent-color:var(--signal-primary)}
.checkpoint-marker{position:absolute;top:3px;z-index:3;width:10px;height:16px;padding:0;border:1px solid #ffc15a;background:#805417;clip-path:polygon(50% 0,100% 35%,72% 100%,28% 100%,0 35%);transform:translateX(-50%)}.checkpoint-marker.snapped{background:#ffc85f;box-shadow:0 0 10px rgba(255,193,90,.62)}.checkpoint-marker.selected{background:#ffe19c;box-shadow:0 0 12px rgba(255,193,90,.85)}.checkpoint-marker.status-used,.checkpoint-marker.status-superseded{border-color:#6e7881;background:#39434a;opacity:.62}
@media(max-width:1400px){.mission-timeline{width:min(760px,calc(100vw - 330px))}.timeline-status{display:none}.secondary-button{padding-inline:7px}}
</style>
