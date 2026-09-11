<template>
  <aside v-if="visibleRecovery" class="battery-recovery-banner" role="alert" aria-live="assertive">
    <span class="recovery-icon" aria-hidden="true">!</span>
    <div>
      <strong>{{ visibleRecovery.title }}</strong>
      <small>{{ visibleRecovery.message }}</small>
    </div>
    <button type="button" :disabled="opening" @click="openRecovery">
      {{ opening ? '正在打开…' : visibleRecovery.actionLabel }}<b aria-hidden="true">→</b>
    </button>
  </aside>
</template>

<script setup>
import { computed, ref } from 'vue'
import { batteryRecoveryForMission } from '../../fleet/batteryRecovery.mjs'
import { useMissionContext } from '../../mission/context/useMissionContext'

const props = defineProps({ fleetRuntime: { type: Object, required: true } })
const context = useMissionContext()
const opening = ref(false)
const recovery = computed(() => batteryRecoveryForMission(context.mission.value))
const recoveryAsset = computed(() => props.fleetRuntime.assets.value.find(asset => (
  recovery.value?.assetId && String(asset.assetId) === recovery.value.assetId
)) || null)
const visibleRecovery = computed(() => {
  if (!recovery.value) return null
  return recoveryAsset.value?.status === 'GARAGED' ? null : recovery.value
})

async function openRecovery() {
  if (!visibleRecovery.value || opening.value) return
  opening.value = true
  try { await props.fleetRuntime.openForRecovery(visibleRecovery.value) }
  finally { opening.value = false }
}
</script>

<style scoped>
.battery-recovery-banner{position:absolute;left:50%;top:112px;z-index:calc(var(--layer-system) + 8);display:grid;grid-template-columns:auto minmax(0,1fr) auto;align-items:center;gap:12px;width:min(720px,calc(100vw - 420px));box-sizing:border-box;padding:11px 12px 11px 13px;border:1px solid rgba(255,197,111,.5);border-left:4px solid var(--signal-warning);border-radius:4px;color:#fff0cf;background:linear-gradient(100deg,rgba(73,47,8,.97),rgba(43,30,10,.96));box-shadow:0 12px 34px rgba(0,0,0,.4),0 0 24px rgba(255,197,111,.12);pointer-events:auto;transform:translateX(-50%);animation:recovery-banner-enter var(--motion-standard) var(--motion-ease-out) both}.recovery-icon{display:grid;width:24px;height:24px;place-items:center;border:1px solid rgba(255,226,171,.74);border-radius:50%;color:#2d1c04;background:var(--signal-warning);box-shadow:0 0 16px rgba(255,197,111,.32);font:850 .78rem/1 sans-serif}.battery-recovery-banner div{min-width:0}.battery-recovery-banner strong,.battery-recovery-banner small{display:block}.battery-recovery-banner strong{overflow:hidden;color:#fff7df;font-size:.9rem;text-overflow:ellipsis;white-space:nowrap}.battery-recovery-banner small{margin-top:3px;color:#e9d5a8;font-size:.72rem;line-height:1.4}.battery-recovery-banner button{min-height:36px;padding:0 12px;border:1px solid rgba(255,218,145,.5);border-radius:3px;color:#2a1b03;background:linear-gradient(135deg,#ffdc93,var(--signal-warning));box-shadow:0 7px 18px rgba(0,0,0,.18);font-size:.72rem;font-weight:800;white-space:nowrap}.battery-recovery-banner button:hover,.battery-recovery-banner button:focus-visible{outline:2px solid rgba(255,225,164,.48);outline-offset:2px;filter:brightness(1.06)}.battery-recovery-banner button:disabled{opacity:.65}.battery-recovery-banner button b{margin-left:7px;font-size:.9rem}@keyframes recovery-banner-enter{from{opacity:0;transform:translate(-50%,-10px)}to{opacity:1;transform:translate(-50%,0)}}@media(max-width:1300px){.battery-recovery-banner{width:min(650px,calc(100vw - 330px))}}@media(max-width:760px){.battery-recovery-banner{top:100px;width:calc(100vw - 24px);grid-template-columns:auto 1fr;padding:10px}.battery-recovery-banner button{grid-column:2;justify-self:start}.battery-recovery-banner strong{white-space:normal}}@media(prefers-reduced-motion:reduce){.battery-recovery-banner{animation:none}}
</style>
