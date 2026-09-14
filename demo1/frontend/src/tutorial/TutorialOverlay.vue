<template>
  <div
    class="tutorial-presentation"
    :class="[
      {
        'is-active': engine.isActive.value,
        'is-planner-aware': engine.plannerAware.value,
        'is-fleet-aware': engine.fleetAware.value,
        'is-live-aware': liveAware,
        'is-inspector-aware': inspectorAware,
        'is-duo-cast': duoCast,
        'is-solo-cast': !duoCast,
        'is-action-mode': engine.isActionMode.value,
        'is-device-follow-step': ['D11-DEVICE', 'A04-FOLLOW'].includes(engine.currentStep.value.id),
        'is-timeline-step': ['WAIT-RED-VIOLATION', 'D13-REWIND', 'A07-CHECKPOINT', 'A08-RESTORE'].includes(engine.currentStep.value.id),
        'is-dialogue-avoiding-target': dialogueAvoidsTarget,
        'is-reduced-motion': engine.reducedMotion.value
      },
      `phase-${engine.phase.value.toLowerCase()}`
    ]"
  >
    <template v-if="!engine.isActive.value">
      <TutorialManualEntry
        v-if="engine.manualExpanded.value"
        :chapters="engine.chapterCards.value"
        :selected-chapter-id="engine.selectedChapterId.value"
        :ready="engine.ready.value"
        :replay-allowed="engine.replayAllowed.value"
        :preparing="engine.chapterPreparing.value"
        @select="engine.selectChapter"
        @start="engine.startTutorial"
        @skip="engine.skipTutorial"
        @close="engine.toggleManual"
      />
      <TutorialBeacon
        v-else
        :chapter-index="engine.beaconChapterIndex.value"
        :attention="engine.beaconAttention.value"
        @open="engine.toggleManual"
      />
      <section v-if="engine.skipConfirmOpen.value" class="tutorial-skip-confirm" data-tutorial-control role="dialog" aria-modal="true" aria-labelledby="tutorial-skip-title-idle">
        <span>MISSION MANUAL / CHAPTER 02</span>
        <strong id="tutorial-skip-title-idle">确认跳过联合配送资格认证？</strong>
        <p>教学预览会被丢弃，然后记录为已跳过并解锁进阶规划。</p>
        <p v-if="engine.skipError.value" class="skip-error" role="alert">{{ engine.skipError.value }}</p>
        <div>
          <button type="button" :disabled="engine.skipInFlight.value" @click="engine.cancelSkipTutorial">取消</button>
          <button type="button" class="confirm" :disabled="engine.skipInFlight.value" @click="engine.confirmSkipTutorial">{{ engine.skipInFlight.value ? '正在处理…' : '确认跳过并解锁' }}</button>
        </div>
      </section>
    </template>

    <template v-else>
      <svg v-if="showFocusLayer" class="tutorial-focus" :width="viewport.width" :height="viewport.height" aria-hidden="true">
        <defs>
          <mask id="tutorial-focus-mask" maskUnits="userSpaceOnUse" x="0" y="0" :width="viewport.width" :height="viewport.height">
            <rect x="0" y="0" :width="viewport.width" :height="viewport.height" fill="white" />
            <rect
              v-if="engine.spotlightRect.value"
              :x="engine.spotlightRect.value.left"
              :y="engine.spotlightRect.value.top"
              :width="engine.spotlightRect.value.width"
              :height="engine.spotlightRect.value.height"
              rx="7"
              fill="black"
            />
            <rect
              v-if="relationSourceSpotlightRect"
              :x="relationSourceSpotlightRect.left"
              :y="relationSourceSpotlightRect.top"
              :width="relationSourceSpotlightRect.width"
              :height="relationSourceSpotlightRect.height"
              rx="7"
              fill="black"
            />
          </mask>
        </defs>
        <rect x="0" y="0" :width="viewport.width" :height="viewport.height" fill="rgba(0, 8, 14, .28)" mask="url(#tutorial-focus-mask)" />
      </svg>

      <svg v-if="connectorPath" class="tutorial-connector" :width="viewport.width" :height="viewport.height" aria-hidden="true">
        <path :d="connectorPath" pathLength="1" />
      </svg>

      <svg v-if="relationConnectorPath" class="tutorial-relation-connector" :width="viewport.width" :height="viewport.height" aria-hidden="true">
        <defs>
          <marker id="tutorial-relation-arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse">
            <path d="M 0 0 L 10 5 L 0 10 z" />
          </marker>
        </defs>
        <path class="relation-path" :d="relationConnectorPath" marker-end="url(#tutorial-relation-arrow)" />
      </svg>

      <div
        v-if="relationSourceSpotlightRect"
        class="relation-source-frame"
        :style="relationSourceFrameStyle"
        aria-hidden="true"
      ></div>

      <div
        v-if="engine.spotlightRect.value && engine.phase.value !== TUTORIAL_PHASES.INTRO"
        :key="engine.wrongPulseKey.value"
        class="target-frame"
        :class="{ 'is-success': engine.phase.value === TUTORIAL_PHASES.ACTION_SUCCESS, 'is-wrong': engine.wrongPulseKey.value > 0 }"
        :style="targetFrameStyle"
        aria-hidden="true"
      ></div>

      <button
        v-if="engine.phase.value !== TUTORIAL_PHASES.COMPLETE"
        class="tutorial-skip"
        type="button"
        data-tutorial-control
        @click="engine.skipTutorial()"
      >{{ skipLabel }}</button>

      <section v-if="engine.skipConfirmOpen.value" class="tutorial-skip-confirm" data-tutorial-control role="dialog" aria-modal="true" aria-labelledby="tutorial-skip-title">
        <span>MISSION MANUAL / CHAPTER 02</span>
        <strong id="tutorial-skip-title">确认跳过联合配送资格认证？</strong>
        <p>{{ activeGroundRun ? '当前教学任务会先被中止，然后记录为已跳过并解锁进阶规划。' : '教学预览会被丢弃，然后记录为已跳过并解锁进阶规划。' }}</p>
        <p v-if="engine.skipError.value" class="skip-error" role="alert">{{ engine.skipError.value }}</p>
        <div>
          <button type="button" :disabled="engine.skipInFlight.value" @click="engine.cancelSkipTutorial">取消</button>
          <button type="button" class="confirm" :disabled="engine.skipInFlight.value" @click="engine.confirmSkipTutorial">{{ engine.skipInFlight.value ? '正在处理…' : '确认跳过并解锁' }}</button>
        </div>
      </section>

      <section v-if="engine.phase.value === TUTORIAL_PHASES.INTRO" class="tutorial-intro" :aria-label="`${engine.currentChapter.value.title}开始`">
        <span>{{ engine.currentChapter.value.introKicker }}</span>
        <strong>{{ engine.currentChapter.value.title }}</strong>
        <small>{{ engine.currentChapter.value.introSubtitle }}</small>
      </section>

      <section v-else-if="engine.phase.value === TUTORIAL_PHASES.COMPLETE" class="tutorial-complete" role="status">
        <span>{{ engine.currentChapter.value.completeKicker }}</span>
        <strong>{{ engine.currentChapter.value.completeTitle }}</strong>
        <small>{{ engine.currentChapter.value.completeSubtitle }}</small>
      </section>

      <div v-else-if="showTutorialCast" class="tutorial-stage">
        <TutorialCharacter
          v-if="showAnan && !engine.isActionMode.value"
          actor="anan"
          :expression="engine.speaker.value === 'anan' ? engine.expression.value : 'default'"
          :active="engine.speaker.value === 'anan'"
          :action-mode="engine.isActionMode.value"
          :planner-aware="engine.plannerAware.value"
          :fleet-aware="engine.fleetAware.value"
          :inspector-aware="inspectorAware"
        />
        <TutorialCharacter
          v-if="showCheng && !engine.isActionMode.value"
          actor="cheng"
          :expression="engine.speaker.value === 'cheng' ? engine.expression.value : 'default'"
          :active="engine.speaker.value === 'cheng'"
          :action-mode="engine.isActionMode.value"
          :planner-aware="engine.plannerAware.value"
          :fleet-aware="engine.fleetAware.value"
          :inspector-aware="inspectorAware"
        />
        <TutorialDialogue
          ref="dialogueRef"
          :speaker="engine.speaker.value || 'anan'"
          :expression="engine.expression.value"
          :text="engine.displayedText.value"
          :highlights="engine.currentStep.value.highlights || []"
          :action-mode="engine.isActionMode.value"
          :planner-aware="engine.plannerAware.value"
          :fleet-aware="engine.fleetAware.value"
          :inspector-aware="inspectorAware"
          :waiting="engine.state.value.waiting"
          :sync-delayed="engine.targetSyncDelayed.value"
          :typing-complete="engine.typingComplete.value"
          @advance="engine.advanceDialogue"
        />
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import TutorialBeacon from './TutorialBeacon.vue'
import TutorialCharacter from './TutorialCharacter.vue'
import TutorialDialogue from './TutorialDialogue.vue'
import TutorialManualEntry from './TutorialManualEntry.vue'
import { calculateConnectorPath, expandRect, shouldAvoidDialogueTarget } from './tutorialGeometry.mjs'
import { TUTORIAL_PHASES } from './tutorialMachine.mjs'
import { useTutorialEngine } from './useTutorialEngine'

const props = defineProps({
  runtime: { type: Object, required: true },
  fleetRuntime: { type: Object, required: true }
})
const engine = useTutorialEngine(props.runtime, props.fleetRuntime)
const viewport = reactive({ width: window.innerWidth, height: window.innerHeight })
const dialogueRef = ref(null)
const dialogueRect = ref(null)
const relationSourceRect = ref(null)
const relationTargetRect = ref(null)
const dialogueAvoidsTarget = ref(false)
let dialogueObserver = null

const showFocusLayer = computed(() => engine.currentStep.value?.spotlight && ![
  TUTORIAL_PHASES.INTRO,
  TUTORIAL_PHASES.COMPLETE
].includes(engine.phase.value))
const showTutorialCast = computed(() => [
  TUTORIAL_PHASES.DIALOGUE,
  TUTORIAL_PHASES.ACTION
].includes(engine.phase.value))
const showAirspaceRelation = computed(() => engine.currentStep.value.id === 'D14-INSPECT'
  && Boolean(props.runtime.selectedAirspaceId.value))
const relationConnectorPath = computed(() => showAirspaceRelation.value && relationSourceRect.value && relationTargetRect.value
  ? calculateConnectorPath(relationTargetRect.value, relationSourceRect.value, viewport)
  : '')
const relationSourceSpotlightRect = computed(() => showAirspaceRelation.value
  ? expandRect(relationSourceRect.value, 8, viewport)
  : null)
const relationSourceFrameStyle = computed(() => {
  const rect = relationSourceSpotlightRect.value
  return rect ? { left: `${rect.left}px`, top: `${rect.top}px`, width: `${rect.width}px`, height: `${rect.height}px` } : {}
})
const connectorPath = computed(() => !relationConnectorPath.value && showFocusLayer.value && engine.targetRect.value && dialogueRect.value
  ? calculateConnectorPath(engine.targetRect.value, dialogueRect.value, viewport)
  : '')
const targetFrameStyle = computed(() => {
  const rect = engine.spotlightRect.value
  return rect ? { left: `${rect.left}px`, top: `${rect.top}px`, width: `${rect.width}px`, height: `${rect.height}px` } : {}
})
const skipLabel = computed(() => engine.currentChapter.value.index === '00' ? '跳过序章' : `跳过第 ${engine.currentChapter.value.index} 章`)
const inspectorAware = computed(() => Boolean(props.runtime.selectedAirspaceId.value) && ['A09-CONFLICT', 'D14-INSPECT', 'A09-DETOUR', 'D15-DETOUR', 'WAIT-DIAMOND'].includes(engine.currentStep.value.id))
const liveAware = computed(() => ['00', '02'].includes(engine.currentChapter.value.index) && Boolean(props.runtime.hasSession.value))
const activeGroundRun = computed(() => engine.currentChapter.value.index === '02'
  && props.runtime.session.value?.status === 'RUNNING'
  && props.runtime.context?.rawSnapshot?.value?.mission?.tutorialId === 'TUTORIAL-02-GROUND-COOP')
const duoCast = computed(() => !engine.isActionMode.value
  && !engine.plannerAware.value
  && !engine.fleetAware.value
  && !liveAware.value
  && !inspectorAware.value
  && !engine.currentStep.value.targetId)
const showAnan = computed(() => duoCast.value || engine.speaker.value === 'anan')
const showCheng = computed(() => duoCast.value || engine.speaker.value === 'cheng')

function updateLayout() {
  viewport.width = window.innerWidth
  viewport.height = window.innerHeight
  const element = dialogueRef.value?.$el
  const conflict = showAirspaceRelation.value ? document.querySelector('[data-tutorial-id="red-airspace-conflict"]') : null
  const inspector = showAirspaceRelation.value ? document.querySelector('[data-tutorial-id="airspace-inspector"]') : null
  const toRect = node => {
    if (!node?.getBoundingClientRect) return null
    const rect = node.getBoundingClientRect()
    return { left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom, width: rect.width, height: rect.height }
  }
  dialogueRect.value = toRect(element)
  if (!dialogueAvoidsTarget.value && shouldAvoidDialogueTarget(engine.targetRect.value, dialogueRect.value, viewport)) {
    dialogueAvoidsTarget.value = true
  }
  relationSourceRect.value = toRect(conflict)
  relationTargetRect.value = toRect(inspector)
}

function observeDialogue() {
  dialogueObserver?.disconnect()
  dialogueObserver = null
  nextTick(() => {
    updateLayout()
    const element = dialogueRef.value?.$el
    dialogueObserver = new ResizeObserver(updateLayout)
    ;[
      element,
      document.querySelector('[data-tutorial-id="red-airspace-conflict"]'),
      document.querySelector('[data-tutorial-id="airspace-inspector"]')
    ].filter(Boolean).forEach(node => dialogueObserver.observe(node))
  })
}

watch(() => engine.currentStep.value.id, () => { dialogueAvoidsTarget.value = false })
watch(() => [engine.phase.value, engine.currentStep.value.id, engine.plannerAware.value, engine.fleetAware.value, props.runtime.selectedAirspaceId.value, engine.targetRect.value], observeDialogue)
onMounted(() => {
  window.addEventListener('resize', updateLayout)
  observeDialogue()
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', updateLayout)
  dialogueObserver?.disconnect()
})
</script>

<style scoped>
.tutorial-relation-connector{position:absolute;inset:0;z-index:calc(var(--layer-tutorial) + 4);overflow:visible;pointer-events:none}.tutorial-relation-connector .relation-path{fill:none;stroke:rgba(126,240,196,.98);stroke-width:2;stroke-linecap:round;stroke-dasharray:10 7;filter:drop-shadow(0 0 7px rgba(126,240,196,.7));animation:relation-flow 1s linear infinite}.tutorial-relation-connector marker path{fill:var(--signal-mint);filter:drop-shadow(0 0 4px rgba(126,240,196,.8))}.relation-source-frame{position:absolute;z-index:calc(var(--layer-tutorial) + 4);box-sizing:border-box;border:1px solid rgba(126,240,196,.9);border-radius:7px;box-shadow:0 0 0 2px rgba(126,240,196,.13),0 0 24px rgba(126,240,196,.32);pointer-events:none;animation:relation-source-pulse 1.4s ease-in-out infinite}
.tutorial-presentation{position:absolute;inset:0;z-index:var(--layer-tutorial);overflow:hidden;pointer-events:none}.tutorial-presentation.is-active{z-index:calc(var(--layer-tutorial) + 20)}.tutorial-focus,.tutorial-connector{position:absolute;inset:0;pointer-events:none}.tutorial-focus{z-index:var(--layer-tutorial)}.tutorial-connector{z-index:calc(var(--layer-tutorial) + 3)}.tutorial-connector path{fill:none;stroke:rgba(126,240,196,.92);stroke-width:1.4;stroke-linecap:round;stroke-dasharray:.035 .02;filter:drop-shadow(0 0 6px rgba(124,231,238,.48));animation:connector-flow 1.1s linear infinite}.target-frame{position:absolute;z-index:calc(var(--layer-tutorial) + 3);box-sizing:border-box;border:1px solid rgba(126,240,196,.82);border-radius:7px;box-shadow:0 0 0 1px rgba(126,240,196,.1),0 0 22px rgba(124,231,238,.2);pointer-events:none}.target-frame::before,.target-frame::after{position:absolute;width:10px;height:10px;border-color:var(--signal-mint);content:''}.target-frame::before{left:-2px;top:-2px;border-left:2px solid;border-top:2px solid}.target-frame::after{right:-2px;bottom:-2px;border-right:2px solid;border-bottom:2px solid}.target-frame.is-wrong{animation:wrong-pulse .28s ease-out}.target-frame.is-success{animation:success-ripple .42s ease-out both}.tutorial-skip{position:absolute;right:25px;top:84px;z-index:calc(var(--layer-tutorial) + 6);padding:8px 10px;border:1px solid rgba(124,231,238,.16);border-radius:2px;color:rgba(211,232,237,.78);background:rgba(3,15,24,.82);pointer-events:auto;font:650 .64rem/1 sans-serif;letter-spacing:.06em}.tutorial-skip:hover,.tutorial-skip:focus-visible{border-color:rgba(124,231,238,.5);color:var(--text-primary);outline:none}.is-fleet-aware .tutorial-skip{right:auto;left:min(45vw,850px);top:auto;bottom:calc(clamp(276px,32.5vh,352px) + 124px)}.tutorial-intro,.tutorial-complete{position:absolute;left:50%;top:50%;z-index:calc(var(--layer-tutorial) + 5);display:grid;justify-items:center;width:min(510px,calc(100vw - 80px));padding:30px 35px;border:1px solid rgba(124,231,238,.28);border-left:2px solid var(--signal-primary);color:var(--text-primary);background:linear-gradient(120deg,rgba(3,15,24,.96),rgba(8,31,42,.93));box-shadow:0 25px 80px rgba(0,0,0,.46);transform:translate(-50%,-50%);clip-path:polygon(0 0,calc(100% - 16px) 0,100% 16px,100% 100%,16px 100%,0 calc(100% - 16px));animation:dossier-open .55s cubic-bezier(.16,.82,.22,1) both}.tutorial-intro span,.tutorial-complete span{color:var(--signal-primary);font:500 .63rem/1 monospace;letter-spacing:.18em}.tutorial-intro strong,.tutorial-complete strong{margin-top:13px;font-size:1.65rem;font-weight:560;letter-spacing:.11em}.tutorial-intro small,.tutorial-complete small{margin-top:11px;color:var(--text-tertiary);font:500 .58rem/1 monospace;letter-spacing:.16em}.tutorial-complete{border-left-color:var(--signal-mint);animation:complete-enter .42s ease-out both}.tutorial-complete span{color:var(--signal-mint)}
.tutorial-stage{position:absolute;inset:0;z-index:calc(var(--layer-tutorial) + 1);overflow:visible;pointer-events:none;transition:left .32s ease,right .32s ease}.tutorial-presentation.is-planner-aware .tutorial-stage{left:clamp(448px,30vw,530px)}.tutorial-presentation.is-live-aware:not(.is-planner-aware) .tutorial-stage{left:clamp(440px,32vw,620px);right:clamp(292px,19vw,380px)}.tutorial-presentation.is-live-aware.is-inspector-aware .tutorial-stage{right:clamp(360px,21vw,400px)}
.tutorial-presentation.is-solo-cast :deep(.tutorial-dialogue){width:min(960px,calc(100% - 24px))}.tutorial-presentation.is-solo-cast :deep(.tutorial-character){width:clamp(260px,22vw,390px);height:clamp(310px,48vh,520px)}.tutorial-presentation.is-solo-cast :deep(.side-left){right:auto;left:12px}.tutorial-presentation.is-solo-cast :deep(.side-right){right:12px;left:auto}.tutorial-presentation.is-action-mode :deep(.tutorial-character.is-speaking){filter:brightness(.58) saturate(.6);opacity:.2;transform:translateY(12px) scale(.96)}
.tutorial-presentation.is-timeline-step :deep(.tutorial-dialogue){right:auto;bottom:84px;left:50%;top:auto;width:min(920px,calc(100% - 12px));transform:translateX(-50%)}.tutorial-presentation.is-timeline-step :deep(.tutorial-character){bottom:86px}
.tutorial-presentation.is-dialogue-avoiding-target :deep(.tutorial-dialogue){top:72px;bottom:auto;max-height:190px}.tutorial-presentation.is-dialogue-avoiding-target :deep(.tutorial-character){bottom:12px;opacity:.32}
.tutorial-skip-confirm{position:absolute;left:50%;top:50%;z-index:calc(var(--layer-tutorial) + 12);display:grid;width:min(500px,calc(100vw - 40px));padding:25px 28px;border:1px solid rgba(255,209,102,.48);border-left:3px solid #ffd166;color:var(--text-primary);background:linear-gradient(125deg,rgba(4,16,25,.99),rgba(33,27,16,.98));box-shadow:0 28px 90px rgba(0,0,0,.7);pointer-events:auto;transform:translate(-50%,-50%);clip-path:polygon(0 0,calc(100% - 14px) 0,100% 14px,100% 100%,14px 100%,0 calc(100% - 14px))}.tutorial-skip-confirm>span{color:#ffd166;font:600 .58rem/1 monospace;letter-spacing:.16em}.tutorial-skip-confirm>strong{margin-top:12px;font-size:1.18rem}.tutorial-skip-confirm>p{margin:12px 0 0;color:var(--text-secondary);font-size:.8rem;line-height:1.65}.tutorial-skip-confirm .skip-error{color:#ff9b8f}.tutorial-skip-confirm>div{display:flex;justify-content:flex-end;gap:9px;margin-top:20px}.tutorial-skip-confirm button{min-height:38px;padding:0 14px;border:1px solid rgba(124,231,238,.25);color:var(--text-secondary);background:transparent}.tutorial-skip-confirm button.confirm{border-color:#ffd166;color:#1d1605;background:#ffd166;font-weight:750}.tutorial-skip-confirm button:disabled{opacity:.55}
@keyframes relation-flow{to{stroke-dashoffset:-17}}@keyframes relation-source-pulse{50%{border-color:#c9ffe9;box-shadow:0 0 0 4px rgba(126,240,196,.1),0 0 30px rgba(126,240,196,.5)}}@keyframes connector-flow{to{stroke-dashoffset:-.055}}@keyframes wrong-pulse{35%{border-color:var(--signal-warning);box-shadow:0 0 0 5px rgba(255,197,111,.13)}}@keyframes success-ripple{0%{box-shadow:0 0 0 0 rgba(126,240,196,.52)}100%{border-color:rgba(126,240,196,0);box-shadow:0 0 0 24px rgba(126,240,196,0)}}@keyframes dossier-open{from{opacity:0;clip-path:polygon(49% 0,51% 0,51% 100%,49% 100%,49% 100%,49% 100%)}to{opacity:1}}@keyframes complete-enter{from{opacity:0;transform:translate(-50%,-47%)}to{opacity:1;transform:translate(-50%,-50%)}}
@media(max-width:1366px){.tutorial-presentation.is-planner-aware .tutorial-stage{left:448px}.tutorial-presentation.is-live-aware:not(.is-planner-aware) .tutorial-stage{left:440px;right:292px}.tutorial-presentation.is-live-aware.is-inspector-aware .tutorial-stage{left:360px;right:360px}.tutorial-presentation.is-solo-cast :deep(.tutorial-character){width:clamp(244px,21vw,330px);height:clamp(292px,45vh,450px)}}
@media(max-width:1600px) and (max-height:900px){.tutorial-presentation.is-live-aware :deep(.tutorial-dialogue){bottom:88px}.tutorial-presentation.is-live-aware :deep(.tutorial-character){bottom:88px}}
@media(max-height:760px){.tutorial-presentation.is-duo-cast :deep(.tutorial-character){height:calc(100% - 140px)}.tutorial-presentation.is-solo-cast :deep(.tutorial-character){height:min(43vh,390px)}.tutorial-presentation.is-solo-cast :deep(.tutorial-dialogue){min-height:104px}}
@media(prefers-reduced-motion:reduce){.tutorial-stage{transition:none}.tutorial-connector path,.tutorial-relation-connector .relation-path,.relation-source-frame,.target-frame,.tutorial-intro,.tutorial-complete{animation:none}.tutorial-connector path,.tutorial-relation-connector .relation-path{stroke-dasharray:none}}
</style>
