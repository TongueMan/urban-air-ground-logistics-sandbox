<template>
  <section class="fleet-card-rail" :aria-labelledby="headingId">
    <header>
      <div><span>{{ eyebrow }}</span><b :id="headingId">{{ title }}</b><small>{{ items.length }} 种</small></div>
      <div class="rail-controls" aria-label="横向浏览">
        <button type="button" aria-label="向左浏览" @click="scroll(-1)">‹</button>
        <button type="button" aria-label="向右浏览" @click="scroll(1)">›</button>
      </div>
    </header>
    <div
      ref="track"
      class="fleet-card-track"
      tabindex="0"
      @wheel.prevent="onWheel"
      @pointerdown="onPointerDown"
      @pointermove="onPointerMove"
      @pointerup="onPointerUp"
      @pointercancel="onPointerUp"
      @keydown="onKeydown"
    >
      <button
        v-for="item in items"
        :key="item.typeId"
        type="button"
        class="fleet-type-card"
        :class="{ selected: item.typeId === selectedId }"
        :aria-pressed="item.typeId === selectedId"
        @click="choose(item, $event)"
      >
        <span class="tech-preview" :class="item.category.toLowerCase()" aria-hidden="true">
          <i></i><i></i><strong>{{ item.category === 'AIR' ? '▲' : '◆' }}</strong>
        </span>
        <span class="card-copy">
          <small>{{ item.englishName }}</small>
          <b>{{ item.name }}</b>
          <em v-if="item.count">已拥有 × {{ item.count }}</em>
          <em v-else>{{ formatCny(item.priceMinor) }}</em>
        </span>
      </button>
      <p v-if="!items.length" class="empty-rail">{{ emptyText }}</p>
    </div>
  </section>
</template>

<script setup>
import { ref } from 'vue'
import { formatCny } from '../../fleet/fleetCatalog.mjs'

const props = defineProps({
  title: { type: String, required: true },
  eyebrow: { type: String, default: '' },
  items: { type: Array, default: () => [] },
  selectedId: { type: String, default: '' },
  emptyText: { type: String, default: '暂无设备' }
})
const emit = defineEmits(['select'])
const track = ref(null)
const headingId = `fleet-rail-${Math.random().toString(36).slice(2)}`
let drag = { active: false, pointerId: null, startX: 0, scrollLeft: 0, moved: false, captured: false }
let suppressClick = false

function scroll(direction) {
  track.value?.scrollBy({ left: direction * Math.max(220, track.value.clientWidth * .72), behavior: 'smooth' })
}
function onWheel(event) {
  track.value.scrollLeft += Math.abs(event.deltaX) > Math.abs(event.deltaY) ? event.deltaX : event.deltaY
}
function onPointerDown(event) {
  if (event.button !== 0) return
  drag = { active: true, pointerId: event.pointerId, startX: event.clientX, scrollLeft: track.value.scrollLeft, moved: false, captured: false }
}
function onPointerMove(event) {
  if (!drag.active || drag.pointerId !== event.pointerId) return
  const distance = event.clientX - drag.startX
  if (Math.abs(distance) > 5 && !drag.moved) {
    drag.moved = true
    drag.captured = true
    track.value.setPointerCapture?.(event.pointerId)
  }
  if (drag.moved) track.value.scrollLeft = drag.scrollLeft - distance
}
function onPointerUp(event) {
  if (!drag.active || drag.pointerId !== event.pointerId) return
  suppressClick = drag.moved
  drag.active = false
  if (drag.captured) track.value.releasePointerCapture?.(event.pointerId)
  setTimeout(() => { suppressClick = false }, 0)
}
function choose(item, event) {
  if (suppressClick) { event.preventDefault(); return }
  emit('select', item.typeId)
}
function onKeydown(event) {
  const cards = [...track.value.querySelectorAll('.fleet-type-card')]
  if (!cards.length || !['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const current = Math.max(0, cards.indexOf(document.activeElement))
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? cards.length - 1
    : Math.max(0, Math.min(cards.length - 1, current + (event.key === 'ArrowRight' ? 1 : -1)))
  cards[next].focus({ preventScroll: true })
  cards[next].scrollIntoView({ behavior: 'smooth', inline: 'nearest', block: 'nearest' })
  emit('select', props.items[next].typeId)
}
</script>

<style scoped>
.fleet-card-rail { min-width:0; }
header { display:flex; align-items:end; justify-content:space-between; gap:12px; margin-bottom:10px; }
header > div:first-child { display:flex; align-items:baseline; gap:9px; }
header span,header small { color:var(--text-secondary); font:700 12px/1 monospace; letter-spacing:.08em; }
header b { color:var(--text-primary); font:700 15px/1 sans-serif; letter-spacing:.05em; }
.rail-controls { display:flex; gap:4px; }
.rail-controls button { width:34px; height:30px; border:1px solid var(--surface-line); color:var(--text-secondary); background:rgba(7,26,37,.82); font:22px/1 sans-serif; }
.rail-controls button:hover,.rail-controls button:focus-visible { border-color:var(--signal-primary); color:var(--signal-primary); outline:none; }
.fleet-card-track { display:flex; gap:9px; min-height:112px; overflow-x:auto; overscroll-behavior:contain; scrollbar-width:none; cursor:grab; outline:none; touch-action:pan-y; }
.fleet-card-track:active { cursor:grabbing; }
.fleet-card-track::-webkit-scrollbar { display:none; }
.fleet-card-track:focus-visible { box-shadow:inset 0 0 0 1px rgba(124,231,238,.4); }
.fleet-type-card { flex:0 0 240px; height:110px; display:grid; grid-template-columns:76px 1fr; align-items:center; gap:12px; padding:10px; border:1px solid rgba(154,205,224,.17); border-radius:2px; color:var(--text-primary); text-align:left; background:linear-gradient(135deg,rgba(10,31,43,.88),rgba(3,13,21,.94)); }
.fleet-type-card:hover,.fleet-type-card:focus-visible,.fleet-type-card.selected { border-color:rgba(124,231,238,.72); outline:none; background:linear-gradient(135deg,rgba(11,53,65,.94),rgba(3,19,28,.96)); }
.fleet-type-card.selected { box-shadow:inset 3px 0 0 var(--signal-primary); }
.tech-preview { position:relative; height:72px; overflow:hidden; border:1px solid rgba(124,231,238,.22); background:linear-gradient(rgba(124,231,238,.05) 1px,transparent 1px),linear-gradient(90deg,rgba(124,231,238,.05) 1px,transparent 1px); background-size:10px 10px; }
.tech-preview::after { content:""; position:absolute; left:8px; right:8px; bottom:9px; border-top:1px solid rgba(124,231,238,.46); }
.tech-preview i { position:absolute; border:1px solid rgba(124,231,238,.42); transform:skewX(-16deg); }
.tech-preview i:first-child { left:13px; top:22px; width:38px; height:17px; }
.tech-preview i:nth-child(2) { left:19px; top:16px; width:22px; height:12px; }
.tech-preview.air i:first-child { left:11px; top:28px; width:44px; height:5px; }
.tech-preview.air i:nth-child(2) { left:27px; top:15px; width:12px; height:26px; }
.tech-preview strong { position:absolute; right:4px; top:3px; color:rgba(124,231,238,.72); font:9px monospace; }
.card-copy { min-width:0; display:grid; gap:7px; }
.card-copy small,.card-copy b,.card-copy em { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.card-copy small { color:var(--text-secondary); font:12px/1 monospace; letter-spacing:.03em; text-transform:uppercase; }
.card-copy b { color:#eafdff; font:600 15px/1.3 sans-serif; }
.card-copy em { color:var(--signal-primary); font:700 14px/1 monospace; font-style:normal; }
.empty-rail { height:110px; flex:1; display:grid; place-items:center; margin:0; border:1px dashed rgba(154,205,224,.16); color:var(--text-secondary); font-size:14px; }
@media (max-width:1350px), (max-height:820px) {
  header{margin-bottom:7px}.fleet-card-track{min-height:102px}.fleet-type-card{flex-basis:210px;height:98px;grid-template-columns:64px 1fr;gap:9px;padding:8px}.tech-preview{height:64px}.card-copy{gap:5px}.card-copy b{font-size:14px}.empty-rail{height:98px}
}
</style>
