<template>
  <figure class="tutorial-character" :class="[`side-${character.side}`, { 'is-speaking': active, 'is-muted': !active, 'is-action': actionMode, 'is-planner': plannerAware, 'is-fleet': fleetAware, 'is-inspector': inspectorAware }]">
    <img :src="imageSource" :alt="`${character.name}${active ? '正在说话' : ''}`" draggable="false" @error="useLocalImage">
  </figure>
</template>

<script setup>
import { computed } from 'vue'
import { localStaticAssetUrl, staticAssetUrl } from '../config/runtime'
import { CHARACTER_MANIFEST, getCharacterExpression } from './characterManifest.mjs'

const props = defineProps({ actor: { type: String, required: true }, expression: { type: String, default: 'default' }, active: Boolean, actionMode: Boolean, plannerAware: Boolean, fleetAware: Boolean, inspectorAware: Boolean })
const character = computed(() => CHARACTER_MANIFEST[props.actor])
const imagePath = computed(() => getCharacterExpression(props.actor, props.active ? props.expression : 'default'))
const remoteImagePath = computed(() => imagePath.value.replace(/^\/?tutorial\/characters\//, 'characters/'))
const imageSource = computed(() => staticAssetUrl(imagePath.value, remoteImagePath.value))

function useLocalImage(event) {
  const fallback = localStaticAssetUrl(imagePath.value)
  if (event.currentTarget.getAttribute('src') !== fallback) event.currentTarget.src = fallback
}
</script>

<style scoped>
.tutorial-character {
  position: absolute;
  bottom: 82px;
  z-index: calc(var(--layer-tutorial) + 1);
  width: clamp(320px, 26vw, 460px);
  height: clamp(380px, 56vh, 600px);
  margin: 0;
  pointer-events: none;
  filter: brightness(.64) saturate(.72);
  opacity: .72;
  transform: scale(.97);
  transform-origin: 50% 100%;
  transition: filter .42s ease, opacity .42s ease, transform .42s ease;
}

.tutorial-character img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
  object-position: center bottom;
  filter: drop-shadow(0 22px 28px rgba(0, 0, 0, .48));
  user-select: none;
}

.side-left { left: max(36px, calc(50% - 620px)); }
.side-right { right: max(36px, calc(50% - 620px)); }
.side-right.is-inspector { right: clamp(352px, 26vw, 510px); }

.tutorial-character.is-speaking {
  z-index: calc(var(--layer-tutorial) + 2);
  filter: brightness(1) saturate(1);
  opacity: 1;
  transform: translateY(-6px) scale(1);
}

.tutorial-character.is-action {
  filter: brightness(.55) saturate(.55);
  opacity: .22;
  transform: translateY(12px) scale(.97);
}

.tutorial-character.is-planner {
  bottom: 82px;
  width: clamp(280px, 21vw, 370px);
  height: clamp(360px, 52vh, 550px);
}

.side-left.is-planner {
  right: auto;
  left: clamp(728px, 51vw, 1120px);
}

.side-right.is-planner {
  right: clamp(12px, 5vw, 80px);
}

.tutorial-character.is-fleet {
  bottom: clamp(374px, 42.5vh, 458px);
  width: clamp(205px, 15vw, 288px);
  height: clamp(285px, 43vh, 470px);
}

.side-left.is-fleet {
  right: auto;
  left: clamp(28px, 4vw, 84px);
}

.side-right.is-fleet {
  right: auto;
  left: clamp(305px, 25vw, 500px);
}

.tutorial-character.is-fleet.is-muted { opacity: .42; }
.tutorial-character.is-fleet.is-action { opacity: .16; }

@media (max-width: 1450px) {
  .tutorial-character.is-planner.is-muted { opacity: .18; }
  .tutorial-character.is-fleet { bottom:clamp(374px,48.5vh,400px);width:clamp(190px,15vw,224px);height:clamp(265px,40vh,350px) }
  .side-right.is-fleet { left:25vw; }
}

@media (prefers-reduced-motion: reduce) {
  .tutorial-character { transition: opacity .12s linear, filter .12s linear; }
  .tutorial-character.is-speaking,
  .tutorial-character.is-action { transform: none; }
}
</style>
