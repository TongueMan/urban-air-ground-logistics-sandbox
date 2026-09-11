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
    <span class="callsign">
      <span v-if="actionMode" class="dialogue-avatar" aria-hidden="true">
        <img :src="imageSource" alt="" draggable="false" @error="useLocalImage">
      </span>
      <span class="callsign-copy"><b>{{ character.name }}</b><small>{{ character.callsign }}</small></span>
    </span>
    <span class="dialogue-copy" aria-live="polite">
      <template v-for="(segment, index) in dialogueSegments" :key="`${index}-${segment.text}`">
        <mark v-if="segment.tone" :class="`tone-${segment.tone}`">{{ segment.text }}</mark>
        <template v-else>{{ segment.text }}</template>
      </template>
    </span>
    <span class="dialogue-hint">
      <template v-if="waiting"><i></i> 正在处理</template>
      <template v-else-if="syncDelayed">界面准备中，准备好后自动继续</template>
      <template v-else-if="actionMode">点击高亮目标</template>
      <template v-else>{{ typingComplete ? '回车 / 点击继续' : '点击显示全文' }}</template>
    </span>
  </button>
</template>

<script setup>
import { computed } from 'vue'
import { localStaticAssetUrl, staticAssetUrl } from '../config/runtime'
import { CHARACTER_MANIFEST, getCharacterExpression } from './characterManifest.mjs'
import { buildDialogueSegments } from './tutorialText.mjs'

const props = defineProps({ speaker: { type: String, required: true }, expression: { type: String, default: 'default' }, text: { type: String, default: '' }, highlights: { type: Array, default: () => [] }, actionMode: Boolean, plannerAware: Boolean, fleetAware: Boolean, inspectorAware: Boolean, waiting: Boolean, syncDelayed: Boolean, typingComplete: Boolean })
defineEmits(['advance'])
const character = computed(() => CHARACTER_MANIFEST[props.speaker] || CHARACTER_MANIFEST.anan)
const dialogueSegments = computed(() => buildDialogueSegments(props.text, props.highlights))
const imagePath = computed(() => getCharacterExpression(props.speaker, props.expression))
const remoteImagePath = computed(() => imagePath.value.replace(/^\/?tutorial\/characters\//, 'characters/'))
const imageSource = computed(() => staticAssetUrl(imagePath.value, remoteImagePath.value))

function useLocalImage(event) {
  const fallback = localStaticAssetUrl(imagePath.value)
  if (event.currentTarget.getAttribute('src') !== fallback) event.currentTarget.src = fallback
}
</script>

<style scoped>
.tutorial-dialogue{position:absolute;left:50%;bottom:18px;z-index:calc(var(--layer-tutorial) + 4);display:grid;grid-template-columns:164px minmax(0,1fr);grid-template-rows:auto auto;box-sizing:border-box;width:min(1180px,calc(100% - 64px));min-height:132px;padding:20px 27px 16px;border:1px solid rgba(124,231,238,.3);border-left:2px solid var(--signal-primary);border-radius:2px;color:var(--text-primary);background:linear-gradient(100deg,rgba(2,13,22,.985),rgba(5,24,35,.97));box-shadow:0 20px 65px rgba(0,0,0,.52);pointer-events:auto;text-align:left;transform:translateX(-50%);clip-path:polygon(0 0,calc(100% - 14px) 0,100% 14px,100% 100%,12px 100%,0 calc(100% - 12px));transition:left .32s ease,width .32s ease,transform .32s ease,opacity .2s ease}
.dialogue-edge{position:absolute;left:0;top:20px;width:2px;height:50px;background:var(--signal-primary);box-shadow:0 0 16px rgba(124,231,238,.5)}.callsign{grid-row:1/3;display:flex;align-items:center;align-self:center;gap:12px;padding-right:20px}.callsign-copy{min-width:0}.callsign b,.callsign small{display:block}.callsign b{font-size:1.08rem;font-weight:720;letter-spacing:.09em}.callsign small{margin-top:7px;color:var(--signal-primary);font:500 .57rem/1.35 monospace;letter-spacing:.11em}.dialogue-avatar{position:relative;flex:0 0 54px;width:54px;height:54px;overflow:hidden;border:1px solid rgba(126,240,196,.5);border-radius:2px;background:radial-gradient(circle at 50% 28%,rgba(36,104,116,.5),rgba(2,13,21,.96) 72%);box-shadow:inset 0 0 18px rgba(124,231,238,.12),0 0 14px rgba(126,240,196,.08);clip-path:polygon(0 0,calc(100% - 8px) 0,100% 8px,100% 100%,0 100%)}.dialogue-avatar img{position:absolute;left:50%;top:2px;width:72px;height:86px;object-fit:contain;object-position:center top;transform:translateX(-50%);filter:drop-shadow(0 5px 8px rgba(0,0,0,.45))}.dialogue-copy{align-self:center;min-height:2.8em;color:#f2fdff;font-size:clamp(1rem,1.2vw,1.15rem);font-weight:540;line-height:1.62;letter-spacing:.018em}.dialogue-copy mark{padding:0 .06em;color:inherit;background:transparent;font-weight:760;text-shadow:0 0 12px currentColor}.dialogue-copy mark.tone-cyan{color:#8ae6ff}.dialogue-copy mark.tone-mint{color:var(--signal-mint)}.dialogue-copy mark.tone-amber{color:#ffd38a}.dialogue-copy mark:nth-of-type(n+3){color:inherit;font-weight:620;text-shadow:none}.dialogue-hint{justify-self:end;align-self:end;margin-top:5px;color:#78939d;font:600 .58rem/1.2 sans-serif;letter-spacing:.05em}.dialogue-hint i{display:inline-block;width:5px;height:5px;margin-right:5px;border-radius:50%;background:var(--signal-primary);box-shadow:0 0 9px rgba(124,231,238,.6);animation:validation-pulse 1s ease-in-out infinite}
.tutorial-dialogue.is-action{grid-template-columns:210px minmax(0,1fr);width:min(900px,calc(100% - 40px));min-height:96px;padding:12px 20px 11px;border-left-color:var(--signal-mint);background:linear-gradient(100deg,rgba(3,17,25,.985),rgba(5,29,38,.97));cursor:default}.is-action .callsign{padding-right:14px}.is-action .dialogue-copy{min-height:2.35em;font-size:clamp(.96rem,1.08vw,1.08rem)}.is-action .dialogue-edge{height:42px;background:var(--signal-mint)}
.tutorial-dialogue.is-planner,.tutorial-dialogue.is-inspector{right:auto;left:50%;width:min(960px,calc(100% - 24px));transform:translateX(-50%)}.tutorial-dialogue.is-planner.is-action{width:min(820px,calc(100% - 32px))}
.tutorial-dialogue.is-fleet{right:auto;left:50%;bottom:clamp(276px,32.5vh,352px);width:min(720px,48vw);min-height:116px;grid-template-columns:148px minmax(0,1fr);padding:17px 22px 14px;transform:translateX(-50%);background:linear-gradient(100deg,rgba(2,13,22,.99),rgba(5,29,38,.985));box-shadow:0 18px 55px rgba(0,0,0,.68)}.tutorial-dialogue.is-fleet.is-action{width:min(680px,46vw);min-height:104px}.tutorial-dialogue.is-fleet .dialogue-copy{font-size:clamp(.96rem,1.08vw,1.12rem)}
@keyframes validation-pulse{50%{opacity:.35;transform:scale(.72)}}
@media(max-width:1250px){.tutorial-dialogue{grid-template-columns:132px 1fr}.tutorial-dialogue.is-action{grid-template-columns:186px 1fr}.tutorial-dialogue.is-fleet{left:50%;bottom:276px;width:52%;min-height:108px;padding:14px 18px 12px;grid-template-columns:118px 1fr}.tutorial-dialogue.is-fleet.is-action{width:50%}.tutorial-dialogue.is-fleet .callsign{padding-right:13px}.tutorial-dialogue.is-fleet .dialogue-copy{font-size:.92rem;line-height:1.55}.dialogue-avatar{flex-basis:48px;width:48px;height:48px}.dialogue-avatar img{width:64px;height:78px}}
@media(prefers-reduced-motion:reduce){.tutorial-dialogue{transition:opacity .12s linear}.dialogue-hint i{animation:none}}
</style>
