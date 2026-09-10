<template>
  <div
    class="tutorial-presentation"
    :class="[
      {
        'is-active': engine.isActive.value,
        'is-planner-aware': engine.plannerAware.value,
        'is-fleet-aware': engine.fleetAware.value,
        'is-device-follow-step': ['D11-DEVICE', 'A04-FOLLOW'].includes(engine.currentStep.value.id),
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
          </mask>
        </defs>
        <rect x="0" y="0" :width="viewport.width" :height="viewport.height" fill="rgba(0, 8, 14, .2)" mask="url(#tutorial-focus-mask)" />
      </svg>

      <svg v-if="connectorPath" class="tutorial-connector" :width="viewport.width" :height="viewport.height" aria-hidden="true">
        <path :d="connectorPath" pathLength="1" />
      </svg>

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

      <template v-else>
        <TutorialCharacter
          actor="anan"
          :expression="engine.speaker.value === 'anan' ? engine.expression.value : 'default'"
          :active="engine.speaker.value === 'anan'"
          :action-mode="engine.isActionMode.value"
          :planner-aware="engine.plannerAware.value"
          :fleet-aware="engine.fleetAware.value"
          :inspector-aware="inspectorAware"
        />
        <TutorialCharacter
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
      </template>
    </template>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import TutorialBeacon from './TutorialBeacon.vue'
import TutorialCharacter from './TutorialCharacter.vue'
import TutorialDialogue from './TutorialDialogue.vue'
import TutorialManualEntry from './TutorialManualEntry.vue'
import { calculateConnectorPath } from './tutorialGeometry.mjs'
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
let dialogueObserver = null

const showFocusLayer = computed(() => engine.currentStep.value?.spotlight && ![
  TUTORIAL_PHASES.INTRO,
  TUTORIAL_PHASES.COMPLETE
].includes(engine.phase.value))
const connectorPath = computed(() => showFocusLayer.value && engine.targetRect.value && dialogueRect.value
  ? calculateConnectorPath(engine.targetRect.value, dialogueRect.value, viewport)
  : '')
const targetFrameStyle = computed(() => {
  const rect = engine.spotlightRect.value
  return rect ? { left: `${rect.left}px`, top: `${rect.top}px`, width: `${rect.width}px`, height: `${rect.height}px` } : {}
})
const skipLabel = computed(() => engine.currentChapter.value.index === '00' ? 'SKIP PROLOGUE' : `SKIP CHAPTER ${engine.currentChapter.value.index}`)
const inspectorAware = computed(() => Boolean(props.runtime.selectedAirspaceId.value) && ['D12', 'A06', 'D13', 'WAIT-DIAMOND'].includes(engine.currentStep.value.id))

function updateLayout() {
  viewport.width = window.innerWidth
  viewport.height = window.innerHeight
  const element = dialogueRef.value?.$el
  if (!element?.getBoundingClientRect) {
    dialogueRect.value = null
    return
  }
  const rect = element.getBoundingClientRect()
  dialogueRect.value = { left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom, width: rect.width, height: rect.height }
}

function observeDialogue() {
  dialogueObserver?.disconnect()
  dialogueObserver = null
  nextTick(() => {
    updateLayout()
    const element = dialogueRef.value?.$el
    if (!element) return
    dialogueObserver = new ResizeObserver(updateLayout)
    dialogueObserver.observe(element)
  })
}

watch(() => [engine.phase.value, engine.currentStep.value.id, engine.plannerAware.value, engine.fleetAware.value], observeDialogue)
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
.tutorial-presentation{position:absolute;inset:0;z-index:var(--layer-tutorial);overflow:hidden;pointer-events:none}.tutorial-presentation.is-active{z-index:calc(var(--layer-tutorial) + 20)}.tutorial-focus,.tutorial-connector{position:absolute;inset:0;pointer-events:none}.tutorial-focus{z-index:var(--layer-tutorial)}.tutorial-connector{z-index:calc(var(--layer-tutorial) + 3)}.tutorial-connector path{fill:none;stroke:rgba(126,240,196,.9);stroke-width:1.25;stroke-linecap:round;stroke-dasharray:.035 .02;filter:drop-shadow(0 0 5px rgba(124,231,238,.38));animation:connector-flow 1.1s linear infinite}.target-frame{position:absolute;z-index:calc(var(--layer-tutorial) + 3);box-sizing:border-box;border:1px solid rgba(126,240,196,.74);border-radius:7px;box-shadow:0 0 0 1px rgba(126,240,196,.08),0 0 18px rgba(124,231,238,.12);pointer-events:none}.target-frame::before,.target-frame::after{position:absolute;width:10px;height:10px;border-color:var(--signal-mint);content:''}.target-frame::before{left:-2px;top:-2px;border-left:2px solid;border-top:2px solid}.target-frame::after{right:-2px;bottom:-2px;border-right:2px solid;border-bottom:2px solid}.target-frame.is-wrong{animation:wrong-pulse .28s ease-out}.target-frame.is-success{animation:success-ripple .42s ease-out both}.tutorial-skip{position:absolute;right:25px;top:84px;z-index:calc(var(--layer-tutorial) + 6);padding:7px 9px;border:0;border-bottom:1px solid rgba(124,231,238,.22);color:rgba(190,218,226,.64);background:rgba(3,15,24,.72);pointer-events:auto;font:500 .54rem/1 monospace;letter-spacing:.12em}.tutorial-skip:hover{color:var(--text-primary)}.is-fleet-aware .tutorial-skip{right:auto;left:min(45vw,850px);top:auto;bottom:calc(clamp(276px,32.5vh,352px) + 124px)}.tutorial-intro,.tutorial-complete{position:absolute;left:50%;top:50%;z-index:calc(var(--layer-tutorial) + 5);display:grid;justify-items:center;width:min(510px,calc(100vw - 80px));padding:30px 35px;border:1px solid rgba(124,231,238,.28);border-left:2px solid var(--signal-primary);color:var(--text-primary);background:linear-gradient(120deg,rgba(3,15,24,.96),rgba(8,31,42,.93));box-shadow:0 25px 80px rgba(0,0,0,.46);transform:translate(-50%,-50%);clip-path:polygon(0 0,calc(100% - 16px) 0,100% 16px,100% 100%,16px 100%,0 calc(100% - 16px));animation:dossier-open .55s cubic-bezier(.16,.82,.22,1) both}.tutorial-intro span,.tutorial-complete span{color:var(--signal-primary);font:500 .63rem/1 monospace;letter-spacing:.18em}.tutorial-intro strong,.tutorial-complete strong{margin-top:13px;font-size:1.65rem;font-weight:560;letter-spacing:.11em}.tutorial-intro small,.tutorial-complete small{margin-top:11px;color:var(--text-tertiary);font:500 .58rem/1 monospace;letter-spacing:.16em}.tutorial-complete{border-left-color:var(--signal-mint);animation:complete-enter .42s ease-out both}.tutorial-complete span{color:var(--signal-mint)}:global(.tutorial-target-active){position:relative;z-index:calc(var(--layer-tutorial) + 2)!important}@keyframes connector-flow{to{stroke-dashoffset:-.055}}@keyframes wrong-pulse{35%{border-color:var(--signal-warning);box-shadow:0 0 0 5px rgba(255,197,111,.13)}}@keyframes success-ripple{0%{box-shadow:0 0 0 0 rgba(126,240,196,.52)}100%{border-color:rgba(126,240,196,0);box-shadow:0 0 0 24px rgba(126,240,196,0)}}@keyframes dossier-open{from{opacity:0;clip-path:polygon(49% 0,51% 0,51% 100%,49% 100%,49% 100%,49% 100%)}to{opacity:1}}@keyframes complete-enter{from{opacity:0;transform:translate(-50%,-47%)}to{opacity:1;transform:translate(-50%,-50%)}}@media(prefers-reduced-motion:reduce){.tutorial-connector path,.target-frame,.tutorial-intro,.tutorial-complete{animation:none}.tutorial-connector path{stroke-dasharray:none}}
.tutorial-presentation.is-device-follow-step :deep(.tutorial-dialogue){right:24px;bottom:auto;left:auto;top:112px;width:min(760px,calc(100vw - 520px));transform:none}
@media(max-width:1366px){.tutorial-presentation.is-device-follow-step :deep(.tutorial-dialogue){right:20px;top:106px;width:min(720px,calc(100vw - 465px))}}
</style>
