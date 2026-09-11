<template>
  <Transition name="motion-instrument">
    <aside v-if="volume" class="airspace-inspector" :class="[`rule-${ruleKey}`, `threat-${threatKey}`]" data-tutorial-id="airspace-inspector" aria-label="空域详情">
      <button class="airspace-close" type="button" aria-label="关闭空域详情" @click="runtime.closeAirspace">×</button>
      <span class="airspace-kicker">DIGITAL AIRSPACE / {{ volume.id }}</span>
      <strong>{{ volume.label || ruleLabel }}</strong>
      <div class="status-row"><span>{{ ruleLabel }}</span><b>{{ stateLabel }}</b></div>
      <dl>
        <div v-if="isCorridor"><dt>合法高度带</dt><dd>{{ Math.round(Number(volume.corridorFloorMeters || 0)) }}—{{ Math.round(Number(volume.corridorCeilingMeters || 0)) }} m AGL</dd></div>
        <div v-else><dt>高度范围</dt><dd>{{ Math.round(Number(volume.floorMeters || 0)) }}—{{ Math.round(Number(volume.ceilingMeters || 0)) }} m AGL</dd></div>
        <div v-if="isCorridor"><dt>当前 / 目标高度</dt><dd>{{ Math.round(Number(volume.currentAltitudeMeters || 0)) }} m / {{ Math.round(Number(volume.targetAltitudeMeters || 0)) }} m</dd></div>
        <div><dt>生效状态</dt><dd>{{ lifecycleText }}</dd></div>
        <div><dt>设立原因</dt><dd>{{ reasonLabel }}</dd></div>
        <div><dt>影响航线</dt><dd>{{ conflict ? '当前无人机航线' : '无当前冲突' }}</dd></div>
      </dl>
      <section v-if="conflict" class="conflict-card" aria-label="航线冲突预测">
        <span>{{ conflict.predictive ? '预测空域冲突' : '空域冲突' }}</span>
        <b>{{ Math.round(Number(conflict.distanceMeters || 0)) }} m</b>
        <small>预计 {{ Math.round(Number(conflict.estimatedEntrySeconds || 0)) }} 秒后到达冲突点 · {{ conflictPhaseText }}</small>
      </section>
      <section v-if="isRisk" class="risk-card" aria-label="风险区耗电规则">
        <span>额外能耗</span><b>区内耗电 ×{{ Number(volume.energyMultiplier || 2.5).toFixed(1) }}</b>
        <small>实际位移不变，电池按等效飞行距离加速扣减；耗尽会导致任务失败</small>
      </section>
      <section v-if="isFineable" class="fine-card" aria-label="禁飞罚款规则">
        <span>侵入罚款</span>
        <b>{{ finePolicyText }}</b>
        <small v-if="activeIncursion" class="incursion-live">正在侵入 {{ incursionSeconds }} 秒 · 当前预计 {{ money(activeIncursion.estimatedFineMinor) }}</small>
        <small v-else-if="latestFine">最近结算：应罚 {{ money(Math.abs(latestFine.assessedAmountMinor)) }} · 实扣 {{ money(Math.abs(latestFine.amountMinor)) }}</small>
        <small v-else>{{ fineAvoidanceText }}</small>
      </section>
      <section v-if="linkedDiamond" class="diamond-card" aria-label="粉钻挑战奖励">
        <span>粉钻挑战</span>
        <b>{{ money(linkedDiamond.rewardMinor) }}</b>
        <small v-if="diamondForfeited" class="diamond-forfeited">已因进入紫色禁入层永久失去本枚奖励</small>
        <small v-else-if="linkedDiamond.requiredAction === 'CONTINUE_DIRECT'">粉钻位于原航线上；真实经过即可入账，若橙区当时生效也会同时计罚</small>
        <small v-else>粉钻位于“{{ linkedDiamond.actionLabel }}”航线上；选择对应处置并真实经过后立即入账</small>
      </section>
      <p v-if="volume.selectedAction" class="action-result">{{ volume.selectedAction.message }}</p>
      <div v-else-if="actions.length" class="action-grid" aria-label="空域处置方案">
        <button v-if="isAbsoluteNoFly" class="keep-course-button" type="button" data-tutorial-id="airspace-keep-course" :disabled="runtime.airspaceActionBusy.value" @click="runtime.closeAirspace">
          暂不处置，保持原航线
        </button>
        <button v-for="action in actions" :key="action" type="button" :data-tutorial-id="action === 'DETOUR' && volume.ruleType === 'ABSOLUTE_NO_FLY' ? 'airspace-detour' : undefined" :disabled="runtime.airspaceActionBusy.value" @click="apply(action)">
          {{ runtime.airspaceActionBusy.value ? '应用中…' : actionLabels[action] || action }}
        </button>
      </div>
      <small class="scope-note">三维空域扫掠判定已启用 · 建筑物轮廓碰撞仍不在本阶段验收范围</small>
    </aside>
  </Transition>
</template>

<script setup>
import { computed } from 'vue'
import { useMissionContext } from '../../mission/context/useMissionContext'

const props = defineProps({ runtime: { type: Object, required: true } })
const runtime = props.runtime
const context = useMissionContext()
const airspace = computed(() => context.mission.value.source.mission?.airspace || {})
const volumes = computed(() => airspace.value.runtimeVolumes || airspace.value.volumes || [])
const volume = computed(() => volumes.value.find(item => String(item.id) === String(runtime.selectedAirspaceId.value)) || null)
const conflict = computed(() => (airspace.value.conflicts || []).find(item => String(item.volumeId) === String(volume.value?.id)) || null)
const actions = computed(() => conflict.value?.availableActions || [])
const economy = computed(() => context.mission.value.source.mission?.economy || {})
const rewardDiamonds = computed(() => context.mission.value.source.mission?.rewardDiamonds || [])
const linkedDiamond = computed(() => rewardDiamonds.value.find(item => String(item.linkedVolumeId) === String(volume.value?.id)) || null)
const ruleKey = computed(() => String(volume.value?.ruleType || '').toLowerCase())
const threatKey = computed(() => String(volume.value?.threatLevel || 'NORMAL').toLowerCase())
const ruleLabel = computed(() => ({ ABSOLUTE_NO_FLY: '绝对禁飞', TEMPORARY_NO_FLY: '临时禁飞', DANGER_AIRSPACE: '危险空域', RISK_AIRSPACE: '风险空域', ALTITUDE_RESTRICTED: '高度限制', ALTITUDE_CORRIDOR: '高度通行走廊' })[volume.value?.ruleType] || '受限空域')
const stateLabel = computed(() => ({ SCHEDULED: '暂时解除', ACTIVATING: '正在展开', ACTIVE: '已生效', CLEARING: '正在收缩', EXPIRED: '已解除' })[volume.value?.state] || volume.value?.state || '已冻结')
const threatLabel = computed(() => ({ NORMAL: '常态', NEAR: '接近', CONFLICT: '存在冲突', IMMINENT: '即将进入', VIOLATION: '已经侵入' })[volume.value?.threatLevel] || '常态')
const reasonLabel = computed(() => ({ PROTECTED_FACILITY: '核心设施保护', TEMPORARY_RESTRICTION: '临时作业管制', WIND_DISTURBANCE: '低空风扰风险', LOW_ALTITUDE_OPERATION: '低空作业限高' })[volume.value?.reason] || volume.value?.reason || '任务规则')
const lifecycleText = computed(() => {
  if (volume.value?.clearanceActive) return '已解除，等待无人机通过'
  if (volume.value?.state === 'SCHEDULED') return `${Math.ceil(Number(volume.value.startsInMs || 0) / 1000)} 秒后生效`
  if (volume.value?.state === 'ACTIVATING') return '边界正在展开'
  if (volume.value?.state === 'CLEARING') return '边界正在收缩，即将解除'
  if (volume.value?.state === 'ACTIVE' && volume.value.remainingMs != null) return `${Math.ceil(Number(volume.value.remainingMs) / 1000)} 秒后解除`
  return stateLabel.value
})
const conflictPhaseText = computed(() => {
  if (volume.value?.ruleType !== 'TEMPORARY_NO_FLY') return threatLabel.value
  if (conflict.value?.clearanceActive) return '通行保护中'
  return conflict.value?.currentlyActive ? `橙区当前生效 · ${threatLabel.value}` : `橙区当前未生效 · ${threatLabel.value}`
})
const isCorridor = computed(() => volume.value?.ruleType === 'ALTITUDE_CORRIDOR')
const isRisk = computed(() => volume.value?.ruleType === 'RISK_AIRSPACE')
const isAbsoluteNoFly = computed(() => volume.value?.ruleType === 'ABSOLUTE_NO_FLY')
const penaltyPolicy = computed(() => volume.value?.penaltyPolicy || {})
const isFineable = computed(() => !isRisk.value && (penaltyPolicy.value.type !== 'NONE') && ['ABSOLUTE_NO_FLY', 'TEMPORARY_NO_FLY', 'DANGER_AIRSPACE', 'ALTITUDE_CORRIDOR'].includes(String(volume.value?.ruleType || '')))
const activeIncursion = computed(() => (economy.value.activeIncursions || []).find(item => String(item.volumeId) === String(volume.value?.id)) || null)
const incursionSeconds = computed(() => Math.max(1, Math.ceil(Number(activeIncursion.value?.exposureMs || 0) / 1000)))
const latestFine = computed(() => [...(economy.value.recentTransactions || [])].reverse().find(item => item.entryType === 'AIRSPACE_FINE' && String(item.referenceId) === String(volume.value?.id)) || null)
const diamondForfeited = computed(() => (economy.value.forfeitedDiamondIds || []).map(String).includes(String(linkedDiamond.value?.id || '')))
const finePolicyText = computed(() => {
  const policy = penaltyPolicy.value
  if (policy.type === 'FIXED_ON_ENTRY') return `${money(policy.amountMinor)} · 进入时立即计罚${policy.repeatMode === 'ONCE_PER_VOLUME' ? ' · 本局最多一次' : ''}`
  const fallback = economy.value.finePolicy || {}
  return `${money(policy.baseMinor ?? fallback.baseMinor)} + ${money(policy.perSecondMinor ?? fallback.perSecondMinor)}/仿真秒 · 单次上限 ${money(policy.maximumMinor ?? fallback.maximumPerIncursionMinor)}`
})
const fineAvoidanceText = computed(() => isCorridor.value ? '保持在合法高度带或从侧面绕行可避免罚款' : '按面板中的安全动作提前处置可避免罚款')
function money(minor) { return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', maximumFractionDigits: 0 }).format(Number(minor || 0) / 100) }
const actionLabels = { ACCEPT_RISK: '接受风险继续', CONTINUE_DIRECT: '保持原航线', DETOUR: '从侧面绕飞', CLIMB_OVER: '爬升越过', TRANSIT_CORRIDOR: '调整高度穿廊', WAIT_UNTIL_CLEAR: '等待解除', RETURN_TO_RECOVERY: '立即返航' }
async function apply(action) { try { await runtime.executeAirspaceAction(volume.value.id, action) } catch (_) {} }
</script>

<style scoped>
.airspace-inspector{--zone:#ff5572;position:absolute;right:24px;top:94px;z-index:var(--layer-action);width:min(328px,calc(100vw - 48px));padding:15px 16px;border-left:2px solid var(--zone);color:var(--text-primary);background:linear-gradient(135deg,rgba(8,17,30,.96),rgba(17,11,25,.94));box-shadow:0 22px 55px rgba(0,0,0,.42),-8px 0 26px color-mix(in srgb,var(--zone),transparent 88%);pointer-events:auto}.rule-temporary_no_fly{--zone:#ff8a47}.rule-risk_airspace{--zone:#ffd166}.rule-altitude_restricted,.rule-altitude_corridor{--zone:#8d7aff}.threat-imminent,.threat-violation{box-shadow:0 22px 55px rgba(0,0,0,.42),-8px 0 34px color-mix(in srgb,var(--zone),transparent 62%)}
.airspace-close{position:absolute;right:8px;top:7px;border:0;color:var(--text-secondary);background:transparent;font-size:20px}.airspace-kicker{display:block;color:var(--zone);font-size:var(--type-micro);letter-spacing:.11em}.airspace-inspector>strong{display:block;margin-top:7px;padding-right:25px;font-size:var(--type-section)}.status-row{display:flex;justify-content:space-between;margin:10px 0;padding:7px 0;border-block:1px solid var(--surface-line);color:var(--text-secondary);font-size:var(--type-telemetry)}.status-row b{color:var(--zone)}dl{display:grid;gap:7px;margin:0}dl div{display:flex;justify-content:space-between;gap:16px}dt{color:var(--text-tertiary);font-size:var(--type-micro)}dd{margin:0;color:var(--text-secondary);font-size:var(--type-telemetry);text-align:right}.conflict-card{display:grid;grid-template-columns:1fr auto;gap:3px;margin-top:12px;padding:10px;border:1px solid color-mix(in srgb,var(--zone),transparent 36%);background:color-mix(in srgb,var(--zone),transparent 91%)}.conflict-card span{color:var(--zone);font-size:var(--type-micro);letter-spacing:.1em}.conflict-card b{grid-row:span 2;color:#fff;font-size:24px}.conflict-card small{color:var(--text-secondary)}.action-grid{display:grid;grid-template-columns:1fr 1fr;gap:7px;margin-top:10px}.action-grid button{min-height:34px;border:1px solid color-mix(in srgb,var(--zone),transparent 50%);color:#f4fbff;background:rgba(12,34,47,.82);font-size:var(--type-telemetry)}.action-grid button:disabled{opacity:.5}.action-result{margin:10px 0 0;padding:8px;border:1px solid rgba(82,239,197,.44);color:#9fffe3;background:rgba(14,73,62,.36);font-size:var(--type-telemetry)}.scope-note{display:block;margin-top:10px;padding-top:8px;border-top:1px solid var(--surface-line);color:var(--text-tertiary);line-height:1.4}@media(max-width:700px){.airspace-inspector{right:12px;top:82px;width:calc(100vw - 24px)}}
.fine-card{display:grid;gap:5px;margin-top:11px;padding:9px 10px;border:1px solid rgba(255,102,126,.36);background:rgba(72,12,26,.28)}.fine-card>span{color:#ff8799;font-size:var(--type-micro);letter-spacing:.1em}.fine-card>b{font-size:var(--type-telemetry);font-weight:650}.fine-card>small{color:var(--text-tertiary);line-height:1.45}.fine-card .incursion-live{color:#ffb2bd;font-weight:700}
.action-grid .keep-course-button{grid-column:1/-1;border-color:rgba(255,193,90,.42);color:#ffe0a8;background:rgba(76,47,12,.54)}
.diamond-card{display:grid;grid-template-columns:1fr auto;gap:5px;margin-top:11px;padding:9px 10px;border:1px solid rgba(255,75,190,.48);background:rgba(78,8,58,.34);box-shadow:inset 0 0 18px rgba(255,42,173,.08)}.diamond-card>span{color:#ff72ca;font-size:var(--type-micro);letter-spacing:.1em}.diamond-card>b{color:#ffd0ef;font-size:var(--type-telemetry)}.diamond-card>small{grid-column:1/-1;color:#d9a8ca;line-height:1.45}
.risk-card{display:grid;gap:5px;margin-top:11px;padding:9px 10px;border:1px solid rgba(255,209,102,.45);background:rgba(75,55,8,.3)}.risk-card>span{color:#ffd166;font-size:var(--type-micro);letter-spacing:.1em}.risk-card>b{color:#fff0b8}.risk-card>small{color:#d7c999;line-height:1.45}.diamond-card .diamond-forfeited{color:#ff8fa9;font-weight:700}
</style>
