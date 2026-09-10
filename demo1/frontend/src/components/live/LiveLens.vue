<template>
  <section class="live-lens" :class="{ 'is-expanded': expanded, 'has-focus': context.focusedActorId.value }" aria-label="实时视觉窗口">
    <header>
      <div><span class="instrument-kicker">LIVE LENS</span><b>{{ displayActor?.name || '任务视觉' }}</b></div>
      <button type="button" :aria-expanded="expanded" @click="expanded = !expanded">{{ expanded ? '收起' : '展开' }}</button>
    </header>
    <div class="lens-frame">
      <WhepVideoPlayer
        v-if="mediaSource"
        :key="mediaSource.id"
        :fallback-path="`${mediaSource.streamKey}/whep`"
        :fallback-video="`/fallback-media/${mediaSource.fallbackFile}`"
        auto-start
      />
      <div v-else class="lens-empty"><span>NO VISUAL SOURCE</span><small>当前 Actor 未配置视觉能力</small></div>
      <span class="lens-source">{{ mediaSource?.label || '未选择视觉源' }}</span>
    </div>
    <div class="lens-switcher" aria-label="可用视觉源">
      <button
        v-for="actor in mediaActors"
        :key="actor.id"
        type="button"
        :aria-pressed="actor.id === displayActor?.id"
        @click="context.focusActor(actor.id)"
      >{{ actor.kind }} {{ actor.name.replace(/一号|二号|城市|配送|无人机/g, '').trim() || actor.id.slice(-2) }}</button>
    </div>
  </section>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useMissionContext } from '../../mission/context/useMissionContext'
import { preferredMediaSource } from '../../mission/presentation/missionSelectors.mjs'
import WhepVideoPlayer from '../WhepVideoPlayer.vue'

const context = useMissionContext()
const expanded = ref(false)
const mediaActors = computed(() => context.mission.value.actors
  .filter(actor => actor.mediaSources.length)
  .sort((a, b) => Number(b.kind === 'UAV') - Number(a.kind === 'UAV')))
const displayActor = computed(() => context.focusedActor.value?.mediaSources?.length ? context.focusedActor.value : mediaActors.value[0] || null)
const mediaSource = computed(() => preferredMediaSource(displayActor.value))
</script>

<style scoped>
.live-lens { position:absolute; top:104px; right:18px; z-index:var(--layer-instrument); width:clamp(260px,22vw,360px); color:var(--text-primary); pointer-events:auto; transition:width var(--motion-emphasis) var(--motion-ease-state), transform var(--motion-emphasis) var(--motion-ease-state); }
.live-lens.is-expanded { width:min(520px,42vw); z-index:var(--layer-focus); }
header { display:flex; justify-content:space-between; align-items:end; padding:0 7px 7px 12px; border-left:1px solid var(--surface-line-strong); }
header b { display:block; margin-top:3px; font-size:var(--type-body); font-weight:600; }
header button { border:0; color:var(--text-secondary); background:transparent; font-size:var(--type-micro); }
.lens-frame { position:relative; padding:6px; background:linear-gradient(135deg,rgba(8,30,42,.92),rgba(2,8,14,.9)); clip-path:polygon(0 0,calc(100% - 18px) 0,100% 18px,100% 100%,18px 100%,0 calc(100% - 18px)); }
.lens-frame::before,.lens-frame::after { content:""; position:absolute; z-index:3; width:32px; height:32px; pointer-events:none; }
.lens-frame::before { top:0; left:0; border-top:2px solid var(--signal-primary); border-left:2px solid var(--signal-primary); }
.lens-frame::after { right:0; bottom:0; border-right:1px solid var(--surface-line-strong); border-bottom:1px solid var(--surface-line-strong); }
.lens-frame :deep(.video-frame) { border:0; border-radius:0; }
.lens-source { position:absolute; left:12px; bottom:10px; z-index:4; padding:3px 6px; color:var(--signal-ice); background:rgba(1,7,12,.76); font-size:var(--type-micro); }
.lens-empty { aspect-ratio:16/9; display:grid; place-content:center; gap:5px; color:var(--text-tertiary); background:var(--world-void); text-align:center; }
.lens-empty span { font-size:var(--type-telemetry); letter-spacing:.14em; }.lens-empty small{font-size:var(--type-micro)}
.lens-switcher { display:flex; justify-content:flex-end; gap:2px; padding-top:6px; }
.lens-switcher button { padding:5px 8px; border:0; border-bottom:1px solid var(--surface-line); color:var(--text-tertiary); background:rgba(2,10,17,.7); font-size:var(--type-micro); }
.lens-switcher button[aria-pressed="true"] { border-color:var(--signal-primary); color:var(--signal-primary); }
@media (max-width:1350px) { .live-lens { width:280px; }.live-lens.is-expanded{width:420px} }
</style>
