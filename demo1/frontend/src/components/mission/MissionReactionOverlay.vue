<template>
  <div class="mission-reaction-layer" aria-label="任务演出反馈">
    <section
      v-if="state.active && activePresentation"
      :key="state.active.id"
      class="mission-reaction"
      :class="`is-${activeTone}`"
      role="status"
      aria-live="polite"
      aria-atomic="true"
    >
      <div class="reaction-atmosphere" aria-hidden="true">
        <i v-for="index in 8" :key="index" :class="`spark spark-${index}`"></i>
      </div>

      <div class="character-dialogue reaction-dialogue dialogue-left" :aria-label="`阿南说：${activePresentation.dialogue.anan}`">
        <span>ANAN // 阿南</span>
        <p>{{ activePresentation.dialogue.anan }}</p>
      </div>

      <figure class="reaction-character character-anan">
        <img
          v-if="assetVisible(activeAssets.anan)"
          :src="activeAssets.anan"
          :alt="state.active.kind === 'diamond' ? '阿南竖起拇指赞赏' : '阿南遗憾地挠头'"
          draggable="false"
          @error="markAssetFailed(activeAssets.anan)"
        >
      </figure>

      <div class="reaction-banner">
        <span>{{ activePresentation.eyebrow }}</span>
        <strong>{{ activePresentation.title }}</strong>
        <small>{{ activePresentation.subtitle }}</small>
        <b v-if="state.active.kind === 'diamond'" class="reward-amount">+{{ formatMoney(state.active.amountMinor) }}</b>
        <div v-else-if="state.active.kind === 'fine-entry'" class="fine-entry-amount">
          <span>当前预估罚款</span>
          <b>-{{ formatMoney(state.active.estimatedFineMinor) }}</b>
        </div>
        <dl v-else class="fine-amounts">
          <div><dt>应罚</dt><dd>{{ formatMoney(state.active.assessedAmountMinor) }}</dd></div>
          <div><dt>实扣</dt><dd>-{{ formatMoney(state.active.amountMinor) }}</dd></div>
        </dl>
      </div>

      <div class="character-dialogue reaction-dialogue dialogue-right" :aria-label="`程昱说：${activePresentation.dialogue.cheng}`">
        <span>CHENG YU // 程昱</span>
        <p>{{ activePresentation.dialogue.cheng }}</p>
      </div>

      <figure class="reaction-character character-cheng">
        <img
          v-if="assetVisible(activeAssets.cheng)"
          :src="activeAssets.cheng"
          :alt="state.active.kind === 'diamond' ? '程昱托起粉钻赞赏' : '程昱扶额表示遗憾'"
          draggable="false"
          @error="markAssetFailed(activeAssets.cheng)"
        >
      </figure>
    </section>

    <section
      v-if="state.settlement"
      ref="settlementRef"
      class="mission-settlement"
      role="dialog"
      aria-modal="true"
      aria-labelledby="mission-settlement-title"
      aria-describedby="mission-settlement-hint"
      tabindex="-1"
      @click.stop="dismissSettlement"
    >
      <div class="settlement-atmosphere" aria-hidden="true">
        <i class="settlement-beam beam-left"></i>
        <i class="settlement-beam beam-right"></i>
        <span>MAX</span>
      </div>

      <div class="character-dialogue settlement-dialogue dialogue-left" :aria-label="`程昱说：${MISSION_SETTLEMENT_DIALOGUE.cheng}`">
        <span>CHENG YU // 程昱</span>
        <p>{{ MISSION_SETTLEMENT_DIALOGUE.cheng }}</p>
      </div>

      <figure class="settlement-character settlement-left">
        <img
          v-if="assetVisible(settlementAssets.cheng)"
          :src="settlementAssets.cheng"
          alt="程昱背面展示双臂肌肉庆祝任务完成"
          draggable="false"
          @error="markAssetFailed(settlementAssets.cheng)"
        >
      </figure>

      <div class="settlement-card">
        <span class="settlement-kicker">TASK COMPLETE</span>
        <h2 id="mission-settlement-title">任务完成</h2>
        <p>这趟配送，肌肉记住了。</p>
        <dl class="settlement-summary">
          <div>
            <dt>运载收益</dt>
            <dd>{{ formatMoney(state.settlement.groundCargoRewardMinor) }}</dd>
          </div>
          <div>
            <dt>时效奖励</dt>
            <dd>{{ formatMoney(state.settlement.timelinessRewardMinor) }}</dd>
          </div>
          <div>
            <dt>空中配送</dt>
            <dd>{{ formatMoney(state.settlement.airCoinRewardMinor) }}</dd>
          </div>
          <div>
            <dt>粉钻奖励</dt>
            <dd class="is-diamond">{{ formatMoney(state.settlement.diamondRewardMinor) }}</dd>
          </div>
          <div>
            <dt>禁飞罚款</dt>
            <dd class="is-fine">-{{ formatMoney(state.settlement.penaltyMinor) }}</dd>
          </div>
          <div class="net-row">
            <dt>本次净额</dt>
            <dd :class="{ 'is-negative': state.settlement.netMinor < 0 }">{{ signedMoney(state.settlement.netMinor) }}</dd>
          </div>
        </dl>
        <small id="mission-settlement-hint" class="settlement-hint"><i></i> 点击任意位置或按 Enter 返回任务地图</small>
      </div>

      <div class="character-dialogue settlement-dialogue dialogue-right" :aria-label="`阿南说：${MISSION_SETTLEMENT_DIALOGUE.anan}`">
        <span>ANAN // 阿南</span>
        <p>{{ MISSION_SETTLEMENT_DIALOGUE.anan }}</p>
      </div>

      <figure class="settlement-character settlement-right">
        <img
          v-if="assetVisible(settlementAssets.anan)"
          :src="settlementAssets.anan"
          alt="阿南正面展示肌肉庆祝任务完成"
          draggable="false"
          @error="markAssetFailed(settlementAssets.anan)"
        >
      </figure>
    </section>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useMissionContext } from '../../mission/context/useMissionContext'
import {
  MISSION_REACTION_ASSETS,
  MISSION_REACTION_ASSET_PATHS,
  MISSION_REACTION_DURATION_MS,
  MISSION_REACTION_PRESENTATION,
  MISSION_SETTLEMENT_DIALOGUE,
  buildMissionSettlement,
  createMissionReactionState,
  dismissMissionSettlement,
  enqueueAirspaceEntryReaction,
  enqueueMissionReaction,
  finishActiveMissionReaction,
  formatMissionMoney,
  isLiveMissionCompletion,
  requestMissionSettlement,
  revealPendingMissionSettlement,
  startNextMissionReaction
} from '../../mission/presentation/missionReactions.mjs'

const props = defineProps({ runtime: { type: Object, required: true } })
const runtime = props.runtime
const context = useMissionContext()
const state = reactive(createMissionReactionState())
const failedAssets = reactive({})
const settlementRef = ref(null)
const activePresentation = computed(() => MISSION_REACTION_PRESENTATION[state.active?.kind] || null)
const activeTone = computed(() => state.active?.kind === 'diamond' ? 'diamond' : 'fine')
const activeAssets = computed(() => MISSION_REACTION_ASSETS[activeTone.value] || {})
const settlementAssets = MISSION_REACTION_ASSETS.settlement
const missionEconomy = computed(() => context.mission.value.source.mission?.economy || {})
let reactionTimer = 0
let preloadTimer = 0
let preloadIdleHandle = 0
let preloadedImages = []
let previousFocus = null
let sessionPrimed = false
let previousSession = null

function replaceState(next) {
  Object.assign(state, next)
}

function clearReactionTimer() {
  if (reactionTimer) window.clearTimeout(reactionTimer)
  reactionTimer = 0
}

function resetPresentation() {
  clearReactionTimer()
  replaceState(createMissionReactionState())
}

function pumpPresentation() {
  if (!state.active && !state.settlement && state.queue.length) {
    replaceState(startNextMissionReaction(state))
    reactionTimer = window.setTimeout(() => {
      reactionTimer = 0
      replaceState(finishActiveMissionReaction(state))
      pumpPresentation()
    }, MISSION_REACTION_DURATION_MS)
    return
  }
  if (!state.active && !state.queue.length && !state.settlement && state.pendingSettlement) {
    replaceState(revealPendingMissionSettlement(state))
  }
}

function markAssetFailed(path) {
  if (path) failedAssets[path] = true
}

function assetVisible(path) {
  return Boolean(path && !failedAssets[path])
}

function formatMoney(minor) {
  return formatMissionMoney(minor)
}

function signedMoney(minor) {
  const amount = Number(minor || 0)
  if (amount === 0) return formatMoney(0)
  return `${amount > 0 ? '+' : '-'}${formatMoney(Math.abs(amount))}`
}

function dismissSettlement() {
  replaceState(dismissMissionSettlement(state))
}

function handleKeydown(event) {
  if (!state.settlement || event.key !== 'Enter') return
  event.preventDefault()
  event.stopPropagation()
  dismissSettlement()
}

function sessionDescriptor(session) {
  return {
    id: String(session?.id || ''),
    status: String(session?.status || '').toUpperCase(),
    taskInstanceId: String(session?.taskInstanceId || '')
  }
}

watch(() => runtime.latestEconomyTransaction?.value, transaction => {
  if (!transaction) return
  const currentRunId = String(runtime.session.value?.id || '')
  if (transaction.runId && String(transaction.runId) !== currentRunId) return
  replaceState(enqueueMissionReaction(state, transaction))
  pumpPresentation()
})

watch(() => runtime.lastRewindAck?.value?.rewindId, rewindId => {
  if (rewindId) resetPresentation()
})

watch(() => (missionEconomy.value.activeIncursions || []).map(incursion => ({
  id: String(incursion.id || ''),
  volumeId: String(incursion.volumeId || ''),
  actorId: String(incursion.actorId || ''),
  estimatedFineMinor: Number(incursion.estimatedFineMinor || 0)
})), incursions => {
  const session = runtime.session.value
  const replay = context.timeMode.value === 'REPLAY' || Boolean(runtime.replayBundle?.value)
  if (replay || session?.status !== 'RUNNING' || !session?.taskInstanceId) return
  for (const incursion of incursions) {
    replaceState(enqueueAirspaceEntryReaction(state, incursion, session.id))
  }
  pumpPresentation()
}, { immediate: true, flush: 'post' })

watch(() => sessionDescriptor(runtime.session.value), current => {
  if (!sessionPrimed) {
    sessionPrimed = true
    previousSession = current
    return
  }
  if (current.id !== previousSession?.id) {
    resetPresentation()
    previousSession = current
    return
  }
  const replay = context.timeMode.value === 'REPLAY' || Boolean(runtime.replayBundle?.value)
  if (isLiveMissionCompletion(previousSession, current, { replay })) {
    const economy = context.mission.value.source.mission?.economy || {}
    replaceState(requestMissionSettlement(state, buildMissionSettlement(current.id, economy)))
    pumpPresentation()
  }
  previousSession = current
}, { immediate: true, flush: 'post' })

watch(() => state.settlement, async (settlement, previous) => {
  if (settlement) {
    previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
    await nextTick()
    settlementRef.value?.focus({ preventScroll: true })
  } else if (previous && previousFocus?.isConnected) {
    await nextTick()
    previousFocus.focus({ preventScroll: true })
    previousFocus = null
  }
})

function preloadReactionAssets() {
  preloadedImages = MISSION_REACTION_ASSET_PATHS.map(path => {
    const image = new Image()
    image.decoding = 'async'
    image.src = path
    return image
  })
}

onMounted(() => {
  window.addEventListener('keydown', handleKeydown, true)
  if (typeof window.requestIdleCallback === 'function') {
    preloadIdleHandle = window.requestIdleCallback(preloadReactionAssets, { timeout: 1800 })
  } else {
    preloadTimer = window.setTimeout(preloadReactionAssets, 450)
  }
})

onBeforeUnmount(() => {
  clearReactionTimer()
  window.removeEventListener('keydown', handleKeydown, true)
  if (preloadTimer) window.clearTimeout(preloadTimer)
  if (preloadIdleHandle && typeof window.cancelIdleCallback === 'function') window.cancelIdleCallback(preloadIdleHandle)
  preloadedImages = []
})
</script>

<style scoped>
.mission-reaction-layer{position:absolute;inset:0;z-index:var(--layer-celebration);overflow:hidden;pointer-events:none}.mission-reaction{position:absolute;inset:78px 0 62px;overflow:hidden;pointer-events:none;isolation:isolate}.reaction-atmosphere{position:absolute;inset:0;z-index:0;overflow:hidden}.reaction-atmosphere::before,.reaction-atmosphere::after{position:absolute;inset:-20%;content:'';opacity:0;animation:atmosphere-flash 2.6s ease both}.is-diamond .reaction-atmosphere::before{background:radial-gradient(circle at 50% 50%,rgba(255,57,190,.26),transparent 38%),linear-gradient(112deg,transparent 18%,rgba(255,102,211,.13) 49%,transparent 76%)}.is-diamond .reaction-atmosphere::after{background:repeating-linear-gradient(115deg,transparent 0 46px,rgba(255,177,232,.08) 47px 49px,transparent 50px 96px);transform:translateX(-8%)}.is-fine .reaction-atmosphere::before{background:radial-gradient(circle at 50% 48%,rgba(255,91,80,.23),transparent 42%),linear-gradient(180deg,rgba(91,10,17,.18),transparent 55%)}.is-fine .reaction-atmosphere::after{background:repeating-linear-gradient(128deg,transparent 0 72px,rgba(255,122,71,.08) 73px 76px,transparent 77px 142px)}
.reaction-character{position:absolute;bottom:-5%;z-index:2;width:clamp(330px,34vw,570px);height:min(78vh,760px);margin:0;filter:drop-shadow(0 28px 32px rgba(0,0,0,.5));transform-origin:50% 100%}.reaction-character img{display:block;width:100%;height:100%;object-fit:contain;object-position:center bottom;user-select:none}.character-anan{left:-2.5%;animation:reaction-left-praise 2.6s both cubic-bezier(.2,.8,.2,1)}.character-cheng{right:-2.5%;animation:reaction-right-praise 2.6s both cubic-bezier(.2,.8,.2,1)}.is-fine .reaction-character{filter:drop-shadow(0 28px 32px rgba(0,0,0,.55)) saturate(.86)}.is-fine .character-anan{animation-name:reaction-left-fine}.is-fine .character-cheng{animation-name:reaction-right-fine}
.reaction-banner{position:absolute;left:50%;top:42%;z-index:5;display:grid;justify-items:center;width:min(420px,39vw);padding:17px 22px 19px;border:1px solid rgba(255,255,255,.2);border-left-width:3px;color:#fff;background:linear-gradient(115deg,rgba(5,12,24,.94),rgba(20,8,28,.9));box-shadow:0 24px 64px rgba(0,0,0,.48);text-align:center;transform:translate(-50%,-50%);clip-path:polygon(0 0,calc(100% - 13px) 0,100% 13px,100% 100%,13px 100%,0 calc(100% - 13px));animation:reaction-banner 2.6s both cubic-bezier(.2,.8,.2,1)}.reaction-banner>span{font:700 .6rem/1 monospace;letter-spacing:.2em}.reaction-banner>strong{margin-top:8px;font-size:clamp(1.45rem,2.6vw,2.5rem);font-weight:880;letter-spacing:.08em}.reaction-banner>small{margin-top:5px;font-size:clamp(.72rem,1vw,.88rem);letter-spacing:.08em}.is-diamond .reaction-banner{border-color:rgba(255,84,196,.52);border-left-color:#ff52c1;background:linear-gradient(115deg,rgba(15,7,28,.96),rgba(57,8,50,.91));box-shadow:0 24px 64px rgba(0,0,0,.48),0 0 42px rgba(255,51,183,.2)}.is-diamond .reaction-banner>span,.is-diamond .reaction-banner>small{color:#ff9cdb}.is-fine .reaction-banner{border-color:rgba(255,106,81,.5);border-left-color:#ff6a51;background:linear-gradient(115deg,rgba(24,8,14,.96),rgba(61,16,17,.92));animation-name:reaction-banner-fine}.is-fine .reaction-banner>span,.is-fine .reaction-banner>small{color:#ffac91}.reward-amount{margin-top:12px;color:#ffd0ed;font:800 clamp(1.15rem,2vw,1.75rem)/1 monospace;text-shadow:0 0 16px rgba(255,76,195,.6)}.fine-amounts{display:flex;gap:22px;margin:13px 0 0}.fine-amounts div{display:grid;gap:4px}.fine-amounts dt{color:#d9a799;font-size:.62rem;letter-spacing:.12em}.fine-amounts dd{margin:0;color:#ffd0c2;font:800 clamp(.9rem,1.3vw,1.18rem)/1 monospace}
.spark{position:absolute;z-index:1;width:9px;height:9px;background:#fff;clip-path:polygon(50% 0,60% 39%,100% 50%,60% 61%,50% 100%,40% 61%,0 50%,40% 39%);opacity:0;animation:spark-pop 2.6s ease both}.is-fine .spark{background:#ff9b67;clip-path:polygon(44% 0,62% 0,57% 38%,82% 38%,36% 100%,45% 55%,20% 55%)}.spark-1{left:16%;top:18%;animation-delay:.05s}.spark-2{left:31%;top:30%;animation-delay:.14s}.spark-3{left:43%;top:17%;animation-delay:.2s}.spark-4{right:42%;top:31%;animation-delay:.1s}.spark-5{right:29%;top:18%;animation-delay:.22s}.spark-6{right:14%;top:36%;animation-delay:.16s}.spark-7{left:22%;top:57%;animation-delay:.28s}.spark-8{right:21%;top:60%;animation-delay:.24s}
.mission-settlement{position:absolute;inset:0;overflow:hidden;color:#f4fbff;background:radial-gradient(circle at 50% 44%,rgba(16,48,67,.32),transparent 38%),linear-gradient(120deg,rgba(0,4,10,.56),rgba(1,13,23,.52),rgba(0,4,10,.56));cursor:pointer;pointer-events:auto;isolation:isolate;animation:settlement-fade .48s ease-out both;outline:0}.mission-settlement::after{position:absolute;inset:0;z-index:7;border:1px solid rgba(102,230,255,.12);box-shadow:inset 0 0 110px rgba(0,0,0,.42);content:'';pointer-events:none}.settlement-atmosphere{position:absolute;inset:0;z-index:0;overflow:hidden}.settlement-atmosphere::before{position:absolute;left:50%;top:50%;width:70vw;height:70vw;border:1px solid rgba(105,229,255,.12);border-radius:50%;box-shadow:0 0 0 34px rgba(105,229,255,.025),0 0 0 88px rgba(255,80,198,.025);content:'';transform:translate(-50%,-50%);animation:settlement-ring 14s linear infinite}.settlement-atmosphere>span{position:absolute;left:50%;top:10%;color:rgba(141,239,255,.055);font:900 clamp(7rem,18vw,18rem)/1 monospace;letter-spacing:-.1em;transform:translateX(-52%)}.settlement-beam{position:absolute;top:-20%;width:34%;height:140%;opacity:.22;filter:blur(4px)}.beam-left{left:4%;background:linear-gradient(105deg,transparent,rgba(64,222,255,.32),transparent);transform:rotate(-8deg)}.beam-right{right:4%;background:linear-gradient(75deg,transparent,rgba(255,69,191,.27),transparent);transform:rotate(8deg)}
.settlement-character{position:absolute;bottom:-5%;z-index:2;width:clamp(390px,39vw,700px);height:min(92vh,920px);margin:0;filter:drop-shadow(0 30px 38px rgba(0,0,0,.56));transform-origin:50% 100%}.settlement-character img{display:block;width:100%;height:100%;object-fit:contain;object-position:center bottom;user-select:none}
.settlement-card{position:absolute;left:50%;top:49%;z-index:5;width:min(430px,39vw);padding:24px 27px 20px;border:1px solid rgba(111,228,255,.34);border-left:3px solid #70e6ff;color:#f4fbff;background:linear-gradient(125deg,rgba(2,13,23,.93),rgba(8,27,39,.89));box-shadow:0 28px 80px rgba(0,0,0,.54),0 0 42px rgba(67,216,255,.1);text-align:center;transform:translate(-50%,-50%);clip-path:polygon(0 0,calc(100% - 16px) 0,100% 16px,100% 100%,16px 100%,0 calc(100% - 16px));animation:settlement-card-in .62s .24s cubic-bezier(.18,.84,.24,1) both}.settlement-kicker{color:#72e6ff;font:700 .62rem/1 monospace;letter-spacing:.22em}.settlement-card h2{margin:10px 0 0;font-size:clamp(2rem,3.2vw,3.2rem);font-weight:880;letter-spacing:.11em;text-shadow:0 0 28px rgba(96,229,255,.24)}.settlement-card>p{margin:7px 0 18px;color:#bed4dd;font-size:.88rem;letter-spacing:.09em}.settlement-summary{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin:0;text-align:left}.settlement-summary>div{display:grid;gap:6px;padding:10px 11px;border:1px solid rgba(127,219,235,.13);background:rgba(5,24,35,.58)}.settlement-summary dt{color:#88aab5;font-size:.58rem;letter-spacing:.1em}.settlement-summary dd{margin:0;color:#dffaff;font:800 clamp(.88rem,1.2vw,1.08rem)/1 monospace}.settlement-summary dd.is-diamond{color:#ff9ad8}.settlement-summary dd.is-fine,.settlement-summary dd.is-negative{color:#ff9e8f}.settlement-summary .net-row{grid-column:1/-1;grid-template-columns:1fr auto;align-items:center;border-color:rgba(121,238,202,.3);background:rgba(10,58,55,.44)}.net-row dd{color:#93f8d5;font-size:clamp(1.05rem,1.6vw,1.35rem)}.settlement-hint{display:flex;justify-content:center;align-items:center;gap:8px;margin-top:17px;color:#8eacb7;font:600 .56rem/1.4 monospace;letter-spacing:.08em}.settlement-hint i{width:6px;height:6px;border-radius:50%;background:#79e9ff;box-shadow:0 0 12px rgba(89,229,255,.72);animation:hint-pulse 1.35s ease-in-out infinite}
@keyframes atmosphere-flash{0%,100%{opacity:0}15%,76%{opacity:1}}@keyframes reaction-left-praise{0%{opacity:0;transform:translate(-26%,28%) rotate(-8deg) scale(.74)}17%{opacity:1;transform:translate(3%,-2%) rotate(2deg) scale(1.05)}24%,75%{opacity:1;transform:none}100%{opacity:0;transform:translate(-13%,9%) rotate(-3deg) scale(.92)}}@keyframes reaction-right-praise{0%{opacity:0;transform:translate(26%,28%) rotate(8deg) scale(.74)}17%{opacity:1;transform:translate(-3%,-2%) rotate(-2deg) scale(1.05)}24%,75%{opacity:1;transform:none}100%{opacity:0;transform:translate(13%,9%) rotate(3deg) scale(.92)}}@keyframes reaction-left-fine{0%{opacity:0;transform:translate(-18%,-22%) rotate(-7deg) scale(.86)}17%{opacity:1;transform:translate(2%,3%) rotate(2deg)}22%{transform:translate(-1%,0) rotate(-1deg)}27%,75%{opacity:1;transform:none}100%{opacity:0;transform:translate(-8%,17%) rotate(-4deg) scale(.94)}}@keyframes reaction-right-fine{0%{opacity:0;transform:translate(18%,-24%) rotate(7deg) scale(.86)}17%{opacity:1;transform:translate(-2%,3%) rotate(-2deg)}22%{transform:translate(1%,0) rotate(1deg)}27%,75%{opacity:1;transform:none}100%{opacity:0;transform:translate(8%,17%) rotate(4deg) scale(.94)}}@keyframes reaction-banner{0%{opacity:0;transform:translate(-50%,-42%) scale(.76)}16%{opacity:1;transform:translate(-50%,-52%) scale(1.06)}23%,76%{opacity:1;transform:translate(-50%,-50%) scale(1)}100%{opacity:0;transform:translate(-50%,-58%) scale(.92)}}@keyframes reaction-banner-fine{0%{opacity:0;transform:translate(-50%,-64%) scale(.92)}16%{opacity:1;transform:translate(-49%,-47%) rotate(1deg)}20%{transform:translate(-51%,-51%) rotate(-1deg)}24%,76%{opacity:1;transform:translate(-50%,-50%)}100%{opacity:0;transform:translate(-50%,-39%) scale(.94)}}@keyframes spark-pop{0%,8%,82%,100%{opacity:0;transform:scale(.2) rotate(0)}18%,66%{opacity:.9;transform:scale(1.35) rotate(90deg)}}@keyframes settlement-fade{from{opacity:0}to{opacity:1}}@keyframes settlement-left-in{from{opacity:0;transform:translate(-26%,22%) rotate(-8deg) scale(.8)}to{opacity:1;transform:none}}@keyframes settlement-right-in{from{opacity:0;transform:translate(28%,20%) rotate(8deg) scale(.8)}to{opacity:1;transform:none}}@keyframes settlement-card-in{from{opacity:0;transform:translate(-50%,-44%) scale(.85)}to{opacity:1;transform:translate(-50%,-50%) scale(1)}}@keyframes settlement-ring{to{transform:translate(-50%,-50%) rotate(360deg)}}@keyframes hint-pulse{50%{opacity:.35;transform:scale(.72)}}
@media(max-width:1250px){.mission-reaction{top:72px}.reaction-character{width:clamp(310px,36vw,450px);height:min(75vh,640px)}.reaction-banner{width:min(370px,40vw);padding-inline:17px}.settlement-character{width:clamp(370px,40vw,510px);height:min(88vh,720px)}.settlement-card{width:min(390px,40vw);padding:20px 22px 17px}}
@media(max-height:760px){.mission-reaction{bottom:54px}.reaction-character{height:72vh}.reaction-banner{top:44%;padding-top:13px;padding-bottom:14px}.settlement-character{height:87vh}.settlement-card{top:50%;padding-top:17px}.settlement-card h2{margin-top:7px}.settlement-card>p{margin-bottom:12px}.settlement-summary>div{padding:8px 9px}.settlement-hint{margin-top:12px}}
@media(prefers-reduced-motion:reduce){.reaction-atmosphere::before,.reaction-atmosphere::after,.reaction-character,.reaction-banner,.spark{animation-name:reduced-reaction-fade!important;animation-duration:2.6s!important;animation-delay:0s!important;animation-iteration-count:1!important}.mission-settlement,.settlement-character,.settlement-card{animation-name:reduced-fade!important;animation-duration:.18s!important;animation-delay:0s!important;animation-iteration-count:1!important}.settlement-atmosphere::before,.settlement-hint i{animation:none}@keyframes reduced-reaction-fade{0%,100%{opacity:0}8%,92%{opacity:1}}@keyframes reduced-fade{from{opacity:0}to{opacity:1}}}

/* Reactions use wall-clock time: 450 ms in, 2.5 s fully visible, 450 ms out. */
.reaction-atmosphere::before,.reaction-atmosphere::after,.reaction-character,.reaction-banner,.spark{animation-duration:3.4s}
.fine-entry-amount{display:grid;justify-items:center;gap:6px;margin-top:13px}.fine-entry-amount span{color:#d9a799;font-size:.62rem;letter-spacing:.12em}.fine-entry-amount b{color:#ffd0c2;font:800 clamp(.95rem,1.5vw,1.28rem)/1 monospace}
.settlement-left{left:-3%;animation:settlement-left-in .82s .08s cubic-bezier(.16,.88,.24,1.12) both}.settlement-right{right:-3%;animation:settlement-right-in .78s cubic-bezier(.16,.88,.24,1.12) both}
@keyframes atmosphere-flash{0%,100%{opacity:0}13.2%,86.8%{opacity:1}}
@keyframes reaction-left-praise{0%{opacity:0;transform:translate(-26%,28%) rotate(-8deg) scale(.74)}10%{opacity:1;transform:translate(3%,-2%) rotate(2deg) scale(1.05)}13.2%,86.8%{opacity:1;transform:none}100%{opacity:0;transform:translate(-13%,9%) rotate(-3deg) scale(.92)}}
@keyframes reaction-right-praise{0%{opacity:0;transform:translate(26%,28%) rotate(8deg) scale(.74)}10%{opacity:1;transform:translate(-3%,-2%) rotate(-2deg) scale(1.05)}13.2%,86.8%{opacity:1;transform:none}100%{opacity:0;transform:translate(13%,9%) rotate(3deg) scale(.92)}}
@keyframes reaction-left-fine{0%{opacity:0;transform:translate(-18%,-22%) rotate(-7deg) scale(.86)}9%{opacity:1;transform:translate(2%,3%) rotate(2deg)}11%{transform:translate(-1%,0) rotate(-1deg)}13.2%,86.8%{opacity:1;transform:none}100%{opacity:0;transform:translate(-8%,17%) rotate(-4deg) scale(.94)}}
@keyframes reaction-right-fine{0%{opacity:0;transform:translate(18%,-24%) rotate(7deg) scale(.86)}9%{opacity:1;transform:translate(-2%,3%) rotate(-2deg)}11%{transform:translate(1%,0) rotate(1deg)}13.2%,86.8%{opacity:1;transform:none}100%{opacity:0;transform:translate(8%,17%) rotate(4deg) scale(.94)}}
@keyframes reaction-banner{0%{opacity:0;transform:translate(-50%,-42%) scale(.76)}10%{opacity:1;transform:translate(-50%,-52%) scale(1.06)}13.2%,86.8%{opacity:1;transform:translate(-50%,-50%) scale(1)}100%{opacity:0;transform:translate(-50%,-58%) scale(.92)}}
@keyframes reaction-banner-fine{0%{opacity:0;transform:translate(-50%,-64%) scale(.92)}9%{opacity:1;transform:translate(-49%,-47%) rotate(1deg)}11%{transform:translate(-51%,-51%) rotate(-1deg)}13.2%,86.8%{opacity:1;transform:translate(-50%,-50%)}100%{opacity:0;transform:translate(-50%,-39%) scale(.94)}}
@keyframes spark-pop{0%,5%,92%,100%{opacity:0;transform:scale(.2) rotate(0)}13.2%,86.8%{opacity:.9;transform:scale(1.35) rotate(90deg)}}
@media(prefers-reduced-motion:reduce){.reaction-atmosphere::before,.reaction-atmosphere::after,.reaction-character,.reaction-banner,.spark{animation-duration:3.4s!important}@keyframes reduced-reaction-fade{0%,100%{opacity:0}13.2%,86.8%{opacity:1}}}

/* Character dialogue is staged as part of the scene, not attached as an image caption. */
.character-dialogue{
  --dialogue-accent:#70e6ff;
  --dialogue-glow:rgba(89,225,255,.2);
  --dialogue-panel:rgba(3,17,29,.95);
  --dialogue-tail:rgb(4,18,30);
  position:absolute;
  z-index:4;
  width:clamp(238px,20vw,348px);
  min-height:96px;
  padding:17px 21px 18px;
  border:1px solid color-mix(in srgb,var(--dialogue-accent) 72%,transparent);
  border-radius:24px;
  color:#f4fbff;
  background:
    linear-gradient(90deg,var(--dialogue-accent),transparent 55%) left top/58% 2px no-repeat,
    linear-gradient(135deg,var(--dialogue-glow),transparent 56%),
    var(--dialogue-panel);
  box-shadow:inset 0 0 0 1px rgba(255,255,255,.025),0 18px 46px rgba(0,0,0,.45),0 0 28px var(--dialogue-glow);
  backdrop-filter:blur(10px);
  isolation:isolate;
}
.character-dialogue::before,.character-dialogue::after{position:absolute;content:'';pointer-events:none}
.character-dialogue::before{bottom:-43px;z-index:-2;width:82px;height:52px;background:var(--dialogue-accent);filter:drop-shadow(0 8px 7px rgba(0,0,0,.24))}
.character-dialogue::after{bottom:-39px;z-index:-1;width:76px;height:48px;background:var(--dialogue-tail)}
.character-dialogue.dialogue-left{border-bottom-left-radius:8px;transform-origin:18% 100%}
.character-dialogue.dialogue-left::before{left:27px;clip-path:polygon(100% 0,0 100%,72% 52%)}
.character-dialogue.dialogue-left::after{left:30px;clip-path:polygon(100% 0,0 100%,72% 52%)}
.character-dialogue.dialogue-right{border-bottom-right-radius:8px;transform-origin:82% 100%}
.character-dialogue.dialogue-right::before{right:27px;clip-path:polygon(0 0,100% 100%,28% 52%)}
.character-dialogue.dialogue-right::after{right:30px;clip-path:polygon(0 0,100% 100%,28% 52%)}
.character-dialogue>span{display:block;color:var(--dialogue-accent);font:750 .6rem/1 monospace;letter-spacing:.18em;text-transform:uppercase}
.character-dialogue>p{margin:9px 0 0;color:#f7fbff;font-size:clamp(.96rem,1.1vw,1.22rem);font-weight:850;line-height:1.45;letter-spacing:.055em;text-shadow:0 2px 14px rgba(0,0,0,.48)}
.is-diamond .character-dialogue{--dialogue-accent:#ff68c7;--dialogue-glow:rgba(255,76,190,.22);--dialogue-panel:rgba(29,7,35,.95);--dialogue-tail:rgb(29,7,35)}
.is-fine .character-dialogue{--dialogue-accent:#ff795e;--dialogue-glow:rgba(255,92,65,.2);--dialogue-panel:rgba(35,8,15,.95);--dialogue-tail:rgb(35,8,15)}
.reaction-dialogue{top:24%;animation-duration:3.4s;animation-fill-mode:both;animation-timing-function:cubic-bezier(.18,.82,.22,1)}
.reaction-dialogue.dialogue-left{left:clamp(110px,17vw,350px);animation-name:reaction-dialogue-left}
.reaction-dialogue.dialogue-right{right:clamp(110px,17vw,350px);animation-name:reaction-dialogue-right}
.settlement-dialogue{top:23%;width:clamp(250px,19vw,344px)}
.settlement-dialogue.dialogue-left{left:clamp(92px,18vw,360px);--dialogue-accent:#70e6ff;--dialogue-glow:rgba(89,225,255,.2);animation:settlement-dialogue-left-in .52s .44s cubic-bezier(.18,.82,.22,1.08) both}
.settlement-dialogue.dialogue-right{right:clamp(92px,18vw,360px);--dialogue-accent:#ff68c7;--dialogue-glow:rgba(255,76,190,.2);animation:settlement-dialogue-right-in .52s .5s cubic-bezier(.18,.82,.22,1.08) both}
@keyframes reaction-dialogue-left{0%,8%{opacity:0;transform:translate(-24px,16px) scale(.84)}13.2%{opacity:1;transform:none}86.8%{opacity:1;transform:none}100%{opacity:0;transform:translate(-12px,-10px) scale(.94)}}
@keyframes reaction-dialogue-right{0%,8%{opacity:0;transform:translate(24px,16px) scale(.84)}13.2%{opacity:1;transform:none}86.8%{opacity:1;transform:none}100%{opacity:0;transform:translate(12px,-10px) scale(.94)}}
@keyframes settlement-dialogue-left-in{from{opacity:0;transform:translate(-22px,14px) scale(.86)}to{opacity:1;transform:none}}
@keyframes settlement-dialogue-right-in{from{opacity:0;transform:translate(22px,14px) scale(.86)}to{opacity:1;transform:none}}
@media(max-width:1500px){
  .reaction-dialogue{top:20%;width:clamp(224px,20vw,292px)}
  .reaction-dialogue.dialogue-left{left:clamp(68px,12vw,180px)}
  .reaction-dialogue.dialogue-right{right:clamp(68px,12vw,180px)}
  .settlement-dialogue{width:clamp(226px,19vw,282px)}
  .settlement-dialogue.dialogue-left{left:clamp(64px,11vw,164px)}
  .settlement-dialogue.dialogue-right{right:clamp(64px,11vw,164px)}
}
@media(max-width:1250px){
  .character-dialogue{min-height:86px;padding:14px 17px 15px;border-radius:20px}
  .character-dialogue>p{margin-top:7px;font-size:clamp(.88rem,1.35vw,1rem)}
  .reaction-dialogue{top:18%;width:clamp(214px,21vw,252px)}
  .reaction-dialogue.dialogue-left{left:clamp(42px,7vw,88px)}
  .reaction-dialogue.dialogue-right{right:clamp(42px,7vw,88px)}
  .settlement-dialogue{width:clamp(214px,21vw,250px)}
  .settlement-dialogue.dialogue-left{left:clamp(42px,7vw,88px)}
  .settlement-dialogue.dialogue-right{right:clamp(42px,7vw,88px)}
}
@media(max-height:820px){
  .reaction-dialogue{top:18%}
  .settlement-dialogue{top:21%}
}
@media(prefers-reduced-motion:reduce){
  .reaction-dialogue{animation-name:reduced-reaction-fade!important;animation-duration:3.4s!important;animation-delay:0s!important;animation-iteration-count:1!important}
  .settlement-dialogue{animation-name:reduced-fade!important;animation-duration:.18s!important;animation-delay:0s!important;animation-iteration-count:1!important}
}
</style>
