<template>
  <Transition name="motion-instrument">
    <aside v-if="visible" class="action-layer" aria-label="任务操作">
      <button class="action-close" type="button" aria-label="关闭任务操作" @click="context.closeActionMode">×</button>
      <span class="instrument-kicker">ACTION / MISSION CONTROL</span>
      <strong>{{ runtime.isTerminal.value ? '任务已结束' : '运行控制' }}</strong>
      <div v-if="!runtime.isTerminal.value" class="speed-control">
        <label for="mission-speed">仿真节奏</label>
        <select id="mission-speed" v-model.number="runtime.timeScale.value" :disabled="runtime.session.value?.status !== 'RUNNING'" @change="runtime.updateSpeed">
          <option v-for="value in [0, 0.5, 1, 2, 5]" :key="value" :value="value">{{ value === 0 ? '暂停' : `${value}×` }}</option>
        </select>
      </div>
      <button v-if="runtime.isTerminal.value" class="action-primary" type="button" :disabled="runtime.busy.value" @click="runtime.prepareNewDelivery">重新配送</button>
      <button v-else class="action-danger" type="button" @click="runtime.endMission">中止当前任务</button>
    </aside>
  </Transition>
</template>

<script setup>
import { computed } from 'vue'
import { useMissionContext } from '../../mission/context/useMissionContext'

const props = defineProps({ runtime: { type: Object, required: true } })
const runtime = props.runtime
const context = useMissionContext()
const visible = computed(() => context.actionMode.value === 'CONTROL')
</script>

<style scoped>
.action-layer{position:absolute;right:24px;bottom:96px;z-index:var(--layer-action);width:min(460px,calc(100vw - 48px));padding:15px 16px;border-left:2px solid var(--signal-primary);color:var(--text-primary);background:var(--surface-action);box-shadow:0 20px 50px rgba(0,0,0,.36);pointer-events:auto}
.action-layer strong{display:block;margin-top:7px;padding-right:24px;font-size:var(--type-section)}
.action-close{position:absolute;top:7px;right:8px;border:0;color:var(--text-secondary);background:transparent;font-size:20px}
.speed-control{display:flex;justify-content:space-between;align-items:center;margin:14px 0;color:var(--text-secondary);font-size:var(--type-body)}
.speed-control select{padding:5px 8px;border:1px solid var(--surface-line-strong);color:var(--text-primary);background:var(--world-graphite)}
.action-primary,.action-danger{width:100%;min-height:36px;border:1px solid var(--surface-line-strong);color:var(--text-primary);background:rgba(13,44,57,.9)}
.action-danger{border-color:rgba(255,111,120,.52);color:#ffdadd;background:rgba(93,26,33,.56)}
button:disabled{opacity:.48;cursor:not-allowed}
@media (max-width:700px){.action-layer{right:12px;bottom:82px;width:calc(100vw - 24px)}}
</style>
