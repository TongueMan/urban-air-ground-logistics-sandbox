<template>
  <aside class="tutorial-manual" :class="{ 'is-new': selected?.status === 'new' }" aria-label="新手任务手册">
    <header class="manual-heading">
      <span>MISSION MANUAL / ONBOARDING FILE</span>
      <button type="button" data-tutorial-control aria-label="收起新手任务手册" @click="$emit('close')">×</button>
    </header>

    <nav class="chapter-list" aria-label="教程章节">
      <button
        v-for="chapter in chapters"
        :key="chapter.id"
        type="button"
        data-tutorial-control
        :class="{ selected: chapter.id === selectedChapterId, locked: !chapter.unlocked }"
        :disabled="!chapter.unlocked"
        :aria-pressed="chapter.id === selectedChapterId"
        @click="$emit('select', chapter.id)"
      >
        <span class="chapter-index">{{ chapter.index }}</span>
        <span class="chapter-name"><small>{{ chapter.eyebrow }}</small><b>{{ chapter.title }}</b></span>
        <span class="chapter-status">{{ statusLabel(chapter) }}</span>
      </button>
    </nav>

    <section v-if="selected" class="manual-copy">
      <div class="manual-title">
        <span>{{ selected.eyebrow }} {{ selected.index }}</span>
        <h2>{{ selected.title }}</h2>
      </div>
      <p>{{ selected.description }}</p>
      <p v-if="!ready" class="manual-note">任务状态正在同步，手册很快就绪。</p>
      <p v-else-if="!selected.unlocked" class="manual-note" role="status">
        教程 02 尚未解锁。请先完成第 01 章“认识你的车队”。
      </p>
      <p v-else-if="!replayAllowed" class="manual-note">当前任务结束后可重新体验。</p>
      <p v-if="error" class="manual-error" role="alert">{{ error }}</p>
      <div class="manual-actions">
        <button
          type="button"
          class="manual-primary"
          data-tutorial-control
          :disabled="!ready || !replayAllowed || preparing || !selected.unlocked"
          @click="$emit('start', selected.id)"
        >
          {{ preparing ? '正在准备章节…' : selected.unlocked ? startLabel(selected) : '尚未解锁' }}
        </button>
        <button
          v-if="selected.status === 'new' && selected.unlocked"
          type="button"
          class="manual-secondary"
          data-tutorial-control
          :disabled="preparing"
          @click="$emit('skip', selected.id)"
        >跳过本章</button>
        <button v-else type="button" class="manual-secondary" data-tutorial-control @click="$emit('close')">收起手册</button>
      </div>
    </section>
  </aside>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  chapters: { type: Array, default: () => [] },
  selectedChapterId: { type: String, default: '' },
  ready: Boolean,
  replayAllowed: Boolean,
  preparing: Boolean,
})
defineEmits(['select', 'start', 'skip', 'close'])

const selected = computed(() => props.chapters.find(chapter => chapter.id === props.selectedChapterId) || props.chapters[0])

function statusLabel(chapter) {
  if (!chapter.unlocked) return chapter.index === '02' ? '完成 01 后解锁' : 'LOCKED'
  return ({ new: 'NEW', in_progress: 'IN PROGRESS', completed: 'COMPLETE', skipped: 'SKIPPED' })[chapter.status] || 'NEW'
}

function startLabel(chapter) {
  if (chapter.status === 'new') return chapter.index === '00' ? '开始序章' : `开始第 ${chapter.index} 章`
  if (chapter.status === 'in_progress') return '继续当前章节'
  return chapter.index === '00' ? '重新体验序章' : `重新体验第 ${chapter.index} 章`
}
</script>

<style scoped>
.tutorial-manual{position:absolute;right:24px;top:98px;z-index:var(--layer-tutorial);width:min(560px,calc(100vw - 48px));border:1px solid rgba(124,231,238,.28);border-left:2px solid var(--signal-primary);color:var(--text-primary);background:linear-gradient(125deg,rgba(3,16,26,.98),rgba(8,30,42,.96));box-shadow:0 24px 70px rgba(0,0,0,.44);pointer-events:auto;clip-path:polygon(0 0,calc(100% - 13px) 0,100% 13px,100% 100%,13px 100%,0 calc(100% - 13px));animation:manual-enter .55s cubic-bezier(.2,.8,.2,1) both}.manual-heading{display:flex;align-items:center;justify-content:space-between;padding:14px 17px 12px;border-bottom:1px solid rgba(124,231,238,.14)}.manual-heading span{color:var(--signal-primary);font:600 .61rem/1 monospace;letter-spacing:.16em}.manual-heading button{width:30px;height:30px;border:1px solid rgba(124,231,238,.18);color:var(--text-secondary);background:transparent;font-size:1.15rem}.chapter-list{display:grid;grid-template-columns:1fr 1fr;gap:8px;padding:12px 14px;background:rgba(124,231,238,.025)}.chapter-list button{display:grid;grid-template-columns:42px minmax(0,1fr);grid-template-rows:auto auto;gap:3px 9px;min-height:66px;padding:10px 11px;border:1px solid rgba(124,231,238,.16);color:var(--text-secondary);text-align:left;background:rgba(2,13,21,.64)}.chapter-list button.selected{border-color:rgba(126,240,196,.52);color:var(--text-primary);background:linear-gradient(110deg,rgba(14,62,69,.6),rgba(4,23,31,.72));box-shadow:inset 2px 0 0 var(--signal-mint)}.chapter-list button.locked{cursor:not-allowed;filter:saturate(.2);opacity:.48}.chapter-index{grid-row:1/3;align-self:center;color:var(--signal-primary);font:300 1.75rem/1 monospace}.chapter-name{display:grid;gap:4px;min-width:0}.chapter-name small{color:var(--text-tertiary);font:600 .52rem/1 monospace;letter-spacing:.12em}.chapter-name b{overflow:hidden;color:inherit;font-size:.82rem;text-overflow:ellipsis;white-space:nowrap}.chapter-status{color:var(--signal-mint);font:600 .48rem/1 monospace;letter-spacing:.1em}.manual-copy{padding:17px 21px 20px}.manual-title span{color:var(--signal-primary);font:600 .56rem/1 monospace;letter-spacing:.16em}.manual-title h2{margin:7px 0 9px;font-size:1.25rem;font-weight:650;letter-spacing:.04em}.manual-copy>p{margin:0;color:var(--text-secondary);font-size:.78rem;line-height:1.7}.manual-copy .manual-note{margin-top:10px;color:var(--signal-warning);font-size:.7rem}.manual-actions{display:flex;gap:9px;margin-top:16px}.manual-actions button{min-height:38px;padding:0 15px;border-radius:2px;font-weight:720}.manual-primary{border:0;color:#021017;background:var(--signal-mint)}.manual-primary:disabled,.manual-secondary:disabled{cursor:not-allowed;filter:saturate(.25);opacity:.55}.manual-secondary{border:1px solid rgba(124,231,238,.22);color:var(--text-secondary);background:transparent}.tutorial-manual.is-new::after{position:absolute;right:54px;top:24px;width:5px;height:5px;border-radius:50%;background:var(--signal-primary);box-shadow:0 0 16px 4px rgba(124,231,238,.35);content:'';animation:manual-attention 2.8s ease-in-out infinite}@keyframes manual-enter{from{opacity:0;transform:translate3d(18px,-8px,0)}to{opacity:1;transform:none}}@keyframes manual-attention{0%,100%{opacity:.45;transform:scale(.85)}50%{opacity:1;transform:scale(1.15)}}@media(max-height:800px){.tutorial-manual{top:80px}.chapter-list button{min-height:58px}.manual-copy{padding-top:13px;padding-bottom:15px}.manual-copy>p{line-height:1.55}.manual-actions{margin-top:12px}}@media(prefers-reduced-motion:reduce){.tutorial-manual,.tutorial-manual.is-new::after{animation:none}}
</style>
