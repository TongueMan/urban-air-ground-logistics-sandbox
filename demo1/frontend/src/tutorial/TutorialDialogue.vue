<template>
  <button
    class="tutorial-dialogue"
    :class="[{ 'is-action': actionMode, 'is-planner': plannerAware, 'is-fleet': fleetAware, 'is-inspector': inspectorAware, 'is-waiting': waiting }, `speaker-${speaker}`]"
    type="button"
    data-tutorial-control
    :aria-label="actionMode ? '当前操作指引' : '继续对话'"
    @click="!actionMode && $emit('advance')"
  >
    <span class="dialogue-edge" aria-hidden="true"></span>
    <span class="callsign"><b>{{ character.name }}</b><small>{{ character.callsign }}</small></span>
    <span class="dialogue-copy" aria-live="polite">
      <template v-for="(segment, index) in dialogueSegments" :key="`${index}-${segment.text}`">
        <mark v-if="segment.tone" :class="`tone-${segment.tone}`">{{ segment.text }}</mark>
        <template v-else>{{ segment.text }}</template>
      </template>
    </span>
    <span class="dialogue-hint">
      <template v-if="waiting"><i></i> VALIDATING</template>
      <template v-else-if="syncDelayed">SYNCING TARGET</template>
      <template v-else-if="actionMode">ACTIVATE HIGHLIGHTED CONTROL</template>
      <template v-else>{{ typingComplete ? 'ENTER / CLICK TO CONTINUE' : 'CLICK TO REVEAL' }}</template>
    </span>
  </button>
</template>

<script setup>
import { computed } from 'vue'
import { CHARACTER_MANIFEST } from './characterManifest.mjs'
import { buildDialogueSegments } from './tutorialText.mjs'

const props = defineProps({ speaker: { type: String, required: true }, text: { type: String, default: '' }, highlights: { type: Array, default: () => [] }, actionMode: Boolean, plannerAware: Boolean, fleetAware: Boolean, inspectorAware: Boolean, waiting: Boolean, syncDelayed: Boolean, typingComplete: Boolean })
defineEmits(['advance'])
const character = computed(() => CHARACTER_MANIFEST[props.speaker] || CHARACTER_MANIFEST.anan)
const dialogueSegments = computed(() => buildDialogueSegments(props.text, props.highlights))
</script>

<style scoped>
.tutorial-dialogue{position:absolute;left:50%;bottom:18px;z-index:calc(var(--layer-tutorial) + 4);display:grid;grid-template-columns:174px minmax(0,1fr);grid-template-rows:auto auto;width:min(1260px,calc(100vw - 64px));min-height:128px;padding:20px 28px 16px;border:1px solid rgba(124,231,238,.28);border-left:2px solid var(--signal-primary);border-radius:2px;color:var(--text-primary);background:linear-gradient(100deg,rgba(2,13,22,.985),rgba(5,24,35,.97));box-shadow:0 20px 65px rgba(0,0,0,.52);pointer-events:auto;text-align:left;transform:translateX(-50%);clip-path:polygon(0 0,calc(100% - 14px) 0,100% 14px,100% 100%,12px 100%,0 calc(100% - 12px));transition:left .42s ease,width .42s ease,transform .42s ease,opacity .2s ease}
.dialogue-edge{position:absolute;left:0;top:20px;width:2px;height:50px;background:var(--signal-primary);box-shadow:0 0 16px rgba(124,231,238,.5)}.callsign{grid-row:1/3;align-self:center;padding-right:22px}.callsign b,.callsign small{display:block}.callsign b{font-size:1.08rem;font-weight:720;letter-spacing:.09em}.callsign small{margin-top:7px;color:var(--signal-primary);font:500 .57rem/1.35 monospace;letter-spacing:.11em}.dialogue-copy{align-self:center;min-height:3em;color:#f2fdff;font-size:clamp(1rem,1.2vw,1.15rem);font-weight:540;line-height:1.68;letter-spacing:.018em}.dialogue-copy mark{padding:0 .06em;color:inherit;background:transparent;font-weight:760;text-shadow:0 0 14px currentColor}.dialogue-copy mark.tone-cyan{color:#8ae6ff}.dialogue-copy mark.tone-mint{color:var(--signal-mint)}.dialogue-copy mark.tone-amber{color:#ffd38a}.dialogue-hint{justify-self:end;align-self:end;margin-top:5px;color:var(--text-tertiary);font:500 .53rem/1 monospace;letter-spacing:.12em}.dialogue-hint i{display:inline-block;width:5px;height:5px;margin-right:5px;border-radius:50%;background:var(--signal-primary);box-shadow:0 0 9px rgba(124,231,238,.6);animation:validation-pulse 1s ease-in-out infinite}
.tutorial-dialogue.is-action{grid-template-columns:158px minmax(0,1fr);width:min(1120px,calc(100vw - 112px));min-height:108px;padding-top:16px;padding-bottom:13px;border-left-color:var(--signal-mint);background:linear-gradient(100deg,rgba(3,17,25,.985),rgba(5,29,38,.97));cursor:default}.is-action .dialogue-copy{min-height:2.5em}.is-action .dialogue-edge{height:40px;background:var(--signal-mint)}
.tutorial-dialogue.is-planner{right:24px;left:auto;width:calc(100vw - 760px);transform:none}.tutorial-dialogue.is-planner.is-action{width:calc(100vw - 760px)}
.tutorial-dialogue.is-inspector{right:auto;left:24px;width:calc(100vw - 400px);transform:none}
.tutorial-dialogue.is-fleet{right:auto;left:clamp(28px,3vw,58px);bottom:clamp(276px,32.5vh,352px);width:min(720px,48vw);min-height:116px;grid-template-columns:148px minmax(0,1fr);padding:17px 22px 14px;transform:none;background:linear-gradient(100deg,rgba(2,13,22,.99),rgba(5,29,38,.985));box-shadow:0 18px 55px rgba(0,0,0,.68)}.tutorial-dialogue.is-fleet.is-action{width:min(680px,46vw);min-height:104px}.tutorial-dialogue.is-fleet .dialogue-copy{font-size:clamp(.96rem,1.08vw,1.12rem)}
@keyframes validation-pulse{50%{opacity:.35;transform:scale(.72)}}
@media(max-width:1250px){.tutorial-dialogue.is-planner{width:calc(100vw - 664px)}.tutorial-dialogue{grid-template-columns:148px 1fr}.tutorial-dialogue.is-action{grid-template-columns:138px 1fr}.tutorial-dialogue.is-fleet{left:22px;bottom:276px;width:52vw;min-height:108px;padding:14px 18px 12px;grid-template-columns:128px 1fr}.tutorial-dialogue.is-fleet.is-action{width:50vw}.tutorial-dialogue.is-fleet .callsign{padding-right:13px}.tutorial-dialogue.is-fleet .dialogue-copy{font-size:.92rem;line-height:1.55}}
@media(prefers-reduced-motion:reduce){.tutorial-dialogue{transition:opacity .12s linear}.dialogue-hint i{animation:none}}
</style>
