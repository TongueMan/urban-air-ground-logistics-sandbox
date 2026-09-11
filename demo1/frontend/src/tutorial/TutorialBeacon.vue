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
.tutorial-beacon{position:absolute;right:24px;bottom:22px;z-index:var(--layer-tutorial);display:grid;grid-template-columns:56px 1fr;align-items:center;width:232px;height:68px;box-sizing:border-box;padding:0;border:1px solid rgba(124,231,238,.42);border-left:3px solid var(--signal-primary);border-radius:4px;color:var(--text-primary);background:linear-gradient(135deg,rgba(3,17,27,.97),rgba(8,31,42,.95));box-shadow:0 12px 34px rgba(0,0,0,.4);pointer-events:auto;text-align:left;isolation:isolate}.tutorial-beacon::before,.tutorial-beacon::after{position:absolute;inset:-1px;z-index:-1;border-radius:5px;pointer-events:none;content:''}.tutorial-beacon.is-attention{border-color:rgba(126,240,196,.82);box-shadow:0 14px 38px rgba(0,0,0,.46),0 0 25px rgba(124,231,238,.3),inset 0 0 20px rgba(126,240,196,.08);animation:beacon-attention 1.8s ease-in-out infinite}.tutorial-beacon.is-attention::before{inset:-6px;border:1px solid rgba(124,231,238,.34);animation:beacon-ring 1.8s ease-out infinite}.tutorial-beacon.is-attention::after{z-index:0;inset:1px;overflow:hidden;background:linear-gradient(105deg,transparent 26%,rgba(168,255,232,0) 38%,rgba(168,255,232,.2) 48%,rgba(168,255,232,0) 58%,transparent 72%);background-size:230% 100%;mix-blend-mode:screen;animation:beacon-scan 2.4s ease-in-out infinite}.beacon-mark,.beacon-copy{position:relative;z-index:1}.beacon-mark{display:grid;align-self:stretch;place-items:center;border-right:1px solid rgba(124,231,238,.18);color:var(--signal-primary);font:700 .875rem/1 monospace;letter-spacing:.08em}.is-attention .beacon-mark{color:var(--signal-mint);text-shadow:0 0 12px rgba(126,240,196,.52)}.beacon-copy{display:grid;gap:7px;padding:0 15px}.beacon-copy b{font:700 .78rem/1 monospace;letter-spacing:.085em}.beacon-copy small{color:#bad0d8;font-size:.75rem;font-weight:650;line-height:1;letter-spacing:.055em}.beacon-pointer{position:absolute;right:calc(100% + 17px);top:50%;display:grid;justify-items:center;width:78px;color:var(--signal-mint);filter:drop-shadow(0 0 8px rgba(126,240,196,.48));transform:translateY(-50%);pointer-events:none;animation:pointer-nudge 1.2s cubic-bezier(.45,0,.25,1) infinite}.beacon-pointer svg{display:block;width:60px;height:38px;overflow:visible}.pointer-hand{fill:rgba(4,23,31,.94);stroke:currentColor;stroke-width:1.5;stroke-linecap:round;stroke-linejoin:round}.pointer-signal{fill:none;stroke:rgba(124,231,238,.54);stroke-width:1.2;stroke-linecap:round;stroke-dasharray:2.5 3}.pointer-joint{fill:none;stroke:rgba(124,231,238,.55);stroke-width:1;stroke-linecap:round}.beacon-pointer small{margin-top:0;color:rgba(210,250,242,.92);font:650 .625rem/1 monospace;letter-spacing:.1em;white-space:nowrap}.tutorial-beacon:hover,.tutorial-beacon:focus-visible{border-color:rgba(124,231,238,.8);outline:none;background:linear-gradient(135deg,rgba(5,26,38,.99),rgba(9,43,55,.97));box-shadow:0 14px 38px rgba(0,0,0,.46),0 0 20px rgba(124,231,238,.2)}@keyframes beacon-attention{0%,100%{box-shadow:0 14px 38px rgba(0,0,0,.46),0 0 14px rgba(124,231,238,.18),inset 0 0 13px rgba(126,240,196,.04)}50%{box-shadow:0 14px 38px rgba(0,0,0,.46),0 0 31px rgba(124,231,238,.48),inset 0 0 24px rgba(126,240,196,.12)}}@keyframes beacon-ring{0%{opacity:.7;transform:scale(1)}70%,100%{opacity:0;transform:scale(1.045,1.16)}}@keyframes beacon-scan{0%,24%{background-position:145% 0}72%,100%{background-position:-35% 0}}@keyframes pointer-nudge{0%,100%{transform:translate(-8px,-50%)}50%{transform:translate(2px,-50%)}}@media(max-width:700px){.tutorial-beacon{right:14px;bottom:14px;grid-template-columns:50px 1fr;width:214px;height:62px}.beacon-copy{gap:6px;padding:0 12px}.beacon-copy b{font-size:.72rem}.beacon-copy small{font-size:.6875rem}.beacon-pointer{right:calc(100% + 11px);width:68px}.beacon-pointer svg{width:54px;height:34px}}@media(prefers-reduced-motion:reduce){.tutorial-beacon.is-attention,.tutorial-beacon.is-attention::before,.tutorial-beacon.is-attention::after,.beacon-pointer{animation:none}.tutorial-beacon.is-attention::before{opacity:.7}.beacon-pointer{transform:translateY(-50%)}}
</style>
