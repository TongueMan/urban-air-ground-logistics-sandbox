<template>
  <button
    class="tutorial-beacon"
    :class="{ 'is-attention': attention }"
    type="button"
    data-tutorial-control
    :data-attention="attention ? 'active' : 'seen'"
    :aria-label="`打开新手任务手册，第 ${chapterIndex} 章`"
    title="打开新手任务手册"
    @click="$emit('open')"
  >
    <span v-if="attention" class="beacon-pointer" aria-hidden="true">
      <svg viewBox="0 0 54 34" focusable="false">
        <path class="pointer-signal" d="M2 17H17" />
        <path class="pointer-hand" d="M17 17l7-7c1.7-1.7 4.7.7 3 2.5l-2.5 2.7h20.7c5.1 0 6.8 8.1.7 10.8H26.3c-3.5 0-6.1-1.4-8.8-4.2L17 21.3" />
        <path class="pointer-joint" d="M31 15.2v4.6m5-4.6v4.6m5-4.6v4.6" />
      </svg>
      <small>点击查看</small>
    </span>
    <span class="beacon-mark" aria-hidden="true">{{ chapterIndex }}</span>
    <span class="beacon-copy" aria-hidden="true">
      <b>MISSION MANUAL</b>
      <small>新手任务手册</small>
    </span>
  </button>
</template>

<script setup>
defineProps({
  chapterIndex: { type: String, default: '00' },
  attention: { type: Boolean, default: false }
})
defineEmits(['open'])
</script>

<style scoped>
.tutorial-beacon{position:absolute;right:18px;bottom:18px;z-index:var(--layer-tutorial);display:grid;grid-template-columns:44px 1fr;align-items:center;width:190px;height:52px;box-sizing:border-box;padding:0;border:1px solid rgba(124,231,238,.36);border-left:2px solid var(--signal-primary);border-radius:3px;color:var(--text-primary);background:linear-gradient(135deg,rgba(3,17,27,.96),rgba(8,31,42,.92));box-shadow:0 10px 28px rgba(0,0,0,.32);pointer-events:auto;text-align:left;isolation:isolate}.tutorial-beacon::before,.tutorial-beacon::after{position:absolute;inset:-1px;z-index:-1;border-radius:4px;pointer-events:none;content:''}.tutorial-beacon.is-attention{border-color:rgba(126,240,196,.82);box-shadow:0 12px 34px rgba(0,0,0,.42),0 0 22px rgba(124,231,238,.28),inset 0 0 18px rgba(126,240,196,.07);animation:beacon-attention 1.8s ease-in-out infinite}.tutorial-beacon.is-attention::before{inset:-6px;border:1px solid rgba(124,231,238,.34);animation:beacon-ring 1.8s ease-out infinite}.tutorial-beacon.is-attention::after{z-index:0;inset:1px;overflow:hidden;background:linear-gradient(105deg,transparent 26%,rgba(168,255,232,0) 38%,rgba(168,255,232,.2) 48%,rgba(168,255,232,0) 58%,transparent 72%);background-size:230% 100%;mix-blend-mode:screen;animation:beacon-scan 2.4s ease-in-out infinite}.beacon-mark,.beacon-copy{position:relative;z-index:1}.beacon-mark{display:grid;align-self:stretch;place-items:center;border-right:1px solid rgba(124,231,238,.14);color:var(--signal-primary);font:700 .72rem/1 monospace;letter-spacing:.08em}.is-attention .beacon-mark{color:var(--signal-mint);text-shadow:0 0 12px rgba(126,240,196,.52)}.beacon-copy{display:grid;gap:5px;padding:0 12px}.beacon-copy b{font:700 .7rem/1 monospace;letter-spacing:.09em}.beacon-copy small{color:var(--text-secondary);font-size:.56rem;line-height:1;letter-spacing:.08em}.beacon-pointer{position:absolute;right:calc(100% + 15px);top:50%;display:grid;justify-items:center;width:70px;color:var(--signal-mint);filter:drop-shadow(0 0 7px rgba(126,240,196,.46));transform:translateY(-50%);pointer-events:none;animation:pointer-nudge 1.2s cubic-bezier(.45,0,.25,1) infinite}.beacon-pointer svg{display:block;width:54px;height:34px;overflow:visible}.pointer-hand{fill:rgba(4,23,31,.94);stroke:currentColor;stroke-width:1.5;stroke-linecap:round;stroke-linejoin:round}.pointer-signal{fill:none;stroke:rgba(124,231,238,.54);stroke-width:1.2;stroke-linecap:round;stroke-dasharray:2.5 3}.pointer-joint{fill:none;stroke:rgba(124,231,238,.55);stroke-width:1;stroke-linecap:round}.beacon-pointer small{margin-top:-2px;color:rgba(190,239,230,.86);font:600 .5rem/1 monospace;letter-spacing:.12em;white-space:nowrap}.tutorial-beacon:hover,.tutorial-beacon:focus-visible{border-color:rgba(124,231,238,.72);outline:none;background:linear-gradient(135deg,rgba(5,26,38,.98),rgba(9,43,55,.96))}@keyframes beacon-attention{0%,100%{box-shadow:0 12px 34px rgba(0,0,0,.42),0 0 13px rgba(124,231,238,.18),inset 0 0 12px rgba(126,240,196,.04)}50%{box-shadow:0 12px 34px rgba(0,0,0,.42),0 0 29px rgba(124,231,238,.46),inset 0 0 22px rgba(126,240,196,.11)}}@keyframes beacon-ring{0%{opacity:.7;transform:scale(1)}70%,100%{opacity:0;transform:scale(1.045,1.16)}}@keyframes beacon-scan{0%,24%{background-position:145% 0}72%,100%{background-position:-35% 0}}@keyframes pointer-nudge{0%,100%{transform:translate(-8px,-50%)}50%{transform:translate(2px,-50%)}}@media(prefers-reduced-motion:reduce){.tutorial-beacon.is-attention,.tutorial-beacon.is-attention::before,.tutorial-beacon.is-attention::after,.beacon-pointer{animation:none}.tutorial-beacon.is-attention::before{opacity:.7}.beacon-pointer{transform:translateY(-50%)}}
</style>
