<template>
  <section class="task-planner" aria-label="配送任务配置" data-tutorial-id="mission-planner">
    <header class="planner-header">
      <div class="title-group">
        <span class="instrument-kicker">DELIVERY TASK SETUP</span>
        <h2>{{ taskTitle }}</h2>
        <div class="mission-tags"><span>车机协同</span><span v-if="preview">{{ riskLabel }}</span></div>
      </div>
      <div class="header-actions">
        <span class="readiness" :class="{ 'is-busy': runtime.busy.value, 'is-ready': preview && !runtime.busy.value }"><i></i>{{ readinessLabel }}</span>
        <button type="button" class="close" aria-label="关闭配送任务配置" @click="runtime.closePlanner">×</button>
      </div>
    </header>

    <div class="planner-scroll">
      <section class="area-section" aria-labelledby="area-heading">
        <div class="section-heading"><span id="area-heading">运营区域</span><small v-if="routeEndpointLabel">{{ routeEndpointLabel }}</small></div>
        <div class="area-picker">
          <select v-model="form.scenarioTemplateId" data-tutorial-id="mission-area" aria-label="选择物流运营区" @change="applyTemplateDefaults">
            <option v-for="item in runtime.scenarioTemplates.value" :key="item.id" :value="item.id">{{ item.name }}</option>
          </select>
          <span aria-hidden="true">⌄</span>
        </div>
      </section>

      <section class="zone-count-section" aria-labelledby="zone-count-heading">
        <div class="section-heading">
          <span id="zone-count-heading">禁飞区数量</span>
          <small>每局从红、黄、紫、橙空域中抽取</small>
        </div>
        <div class="zone-count-picker" role="radiogroup" aria-label="选择本局禁飞区数量" data-tutorial-id="mission-zone-count">
          <label v-for="count in [2, 3, 4]" :key="count" :class="{ selected: form.parameters.airspaceThemeCount === count }" :data-tutorial-id="count === 4 ? 'mission-zone-count-four' : undefined">
            <input v-model.number="form.parameters.airspaceThemeCount" type="radio" name="airspace-theme-count" :value="count">
            <strong>{{ count }}</strong><span>个</span>
          </label>
        </div>
      </section>

      <div v-if="preview" :key="preview.taskId" class="briefing-content" data-tutorial-id="mission-preview" aria-live="polite">
        <section class="mission-metrics" aria-label="任务核心指标">
          <article class="metric metric-ground">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 7h11v9H3zM14 10h3l3 3v3h-6zM6 19a2 2 0 1 0 0-4 2 2 0 0 0 0 4Zm11 0a2 2 0 1 0 0-4 2 2 0 0 0 0 4Z" /></svg>
            <strong>{{ groundRewardCount }}</strong><span>地面目标</span>
          </article>
          <article class="metric metric-air">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m3 11 18-8-7.5 18-2.4-7.1L3 11Zm8.1 2.9L21 3" /></svg>
            <strong>{{ airRewardCount }}</strong><span>空中目标</span>
          </article>
          <article class="metric metric-airspace">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3 21 20H3L12 3Zm0 5v6m0 3v.2" /></svg>
            <strong>{{ airspaceCount }}</strong><span>互动空域</span>
          </article>
          <article class="metric metric-time">
            <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="13" r="8" /><path d="M12 9v4l3 2M9 3h6" /></svg>
            <strong>{{ durationClock }}</strong><span>预计时间</span>
          </article>
        </section>

        <section class="reward-section" aria-labelledby="reward-heading">
          <div class="reward-heading-row">
            <div><span class="section-kicker">REWARD</span><h3 id="reward-heading">本次任务收益</h3></div>
            <div class="reward-hero"><small>预计可得</small><strong>{{ formatMoney(estimatedGrossRewardMinor) }}</strong></div>
          </div>
          <div class="reward-breakdown">
            <div><span>配送收益</span><b>{{ formatMoney(deliveryRewardMinor) }}</b></div>
            <div><span>预计时效</span><b>+{{ formatMoney(timelinessRewardMinor) }}</b></div>
            <div class="diamond-reward"><span><i>◆</i> 挑战潜力</span><b>+{{ formatMoney(diamondPotentialMinor) }}</b></div>
          </div>
        </section>

        <section class="challenge-section" :class="{ 'has-challenge': diamondCount }" aria-labelledby="challenge-heading">
          <div class="challenge-copy">
            <span class="section-kicker">CHALLENGE</span>
            <h3 id="challenge-heading">{{ diamondCount ? '本局粉钻目标' : '常规空域任务' }}</h3>
            <p v-if="diamondCount">{{ challengeDescription }}</p>
            <p v-else>本局没有额外粉钻目标，按规划航线完成配送即可。</p>
          </div>
          <div v-if="diamondCount" class="risk-reward">
            <div class="challenge-gain"><span>成功挑战</span><strong>+{{ formatMoney(diamondPotentialMinor) }}</strong></div>
            <div class="challenge-risk"><span>空域规则</span><strong>{{ fineEntryLabel }}</strong><small>{{ airspaceThemeLabel }}</small></div>
          </div>
        </section>

        <div v-if="!groundBatterySufficient || !airBatterySufficient" class="readiness-warning" role="alert">
          <strong>编组续航不足</strong><span>{{ batteryWarningLabel }}；仍可开始，但设备可能因电量耗尽导致任务失败。</span>
        </div>

        <section class="fleet-summary" aria-labelledby="fleet-summary-heading">
          <span id="fleet-summary-heading" class="advanced-title">本局编组</span>
          <div class="vehicle-detail">
            <div><span>地面车辆</span><b>{{ groundVehicle?.name || '—' }}</b></div>
            <small>当前电量 {{ percentLabel(groundVehicle?.batteryPercent) }} · 预计耗电 {{ percentLabel(economyQuote.estimatedGroundBatteryUsePercent ?? economyQuote.estimatedBatteryUsePercent) }} · {{ numberLabel(groundVehicle?.speedKph, 'km/h') }}</small>
            <small>满电续航 {{ numberLabel(groundVehicle?.fullRangeKm, 'km') }} · 运载倍率 {{ numberLabel(groundVehicle?.cargoMultiplier, '×') }}</small>
          </div>
          <div class="vehicle-detail">
            <div><span>空中设备</span><b>{{ airVehicle?.name || '—' }}</b></div>
            <small>当前电量 {{ percentLabel(airVehicle?.batteryPercent) }} · 预计耗电 {{ airBatteryRangeLabel }} · {{ numberLabel(airVehicle?.speedKph, 'km/h') }}</small>
            <small v-if="Number(economyQuote.yellowRiskExtraBatteryUsePercent || 0) > 0" class="risk-energy">黄色风险直穿额外耗电约 +{{ percentLabel(economyQuote.yellowRiskExtraBatteryUsePercent) }}</small>
            <small>满电航程 {{ numberLabel(airVehicle?.fullRangeKm, 'km') }} · 运载倍率 {{ numberLabel(airVehicle?.cargoMultiplier, '×') }} · 机动响应 {{ numberLabel(airVehicle?.maneuverDelaySeconds, '秒') }}</small>
          </div>
        </section>
      </div>

      <div v-else class="empty-briefing">
        <div class="route-symbol" aria-hidden="true"><i></i><span></span><i></i></div>
        <strong>选择区域，生成一局配送任务</strong>
        <p>路线、配送目标、奖励点与空域挑战会同步出现在地图上。</p>
        <button class="empty-generate" type="button" data-tutorial-id="generate-mission" :disabled="runtime.busy.value || !form.scenarioTemplateId" @click="generate">{{ runtime.busy.value ? '正在规划路线…' : '生成本局任务' }}</button>
      </div>
    </div>

    <footer v-if="preview" class="planner-footer">
      <div class="secondary-actions"><button type="button" data-tutorial-id="generate-mission" :disabled="runtime.busy.value || !form.scenarioTemplateId" @click="generate"><span aria-hidden="true">↻</span> {{ runtime.busy.value ? '正在规划…' : '换一个任务' }}</button></div>
      <div class="maximum-reward"><span>最高可得</span><strong>{{ formatMoney(maximumGrossRewardMinor) }}</strong></div>
      <button class="start" type="button" data-tutorial-id="start-mission" :disabled="runtime.busy.value || !previewMatchesForm" @click="start">{{ previewMatchesForm ? '开始配送' : '请先重新生成' }} <span aria-hidden="true">→</span></button>
    </footer>
  </section>
</template>

<script setup>
import { computed, reactive, watch } from 'vue'

const props = defineProps({ runtime: { type: Object, required: true } })
const runtime = props.runtime
const form = reactive({ scenarioTemplateId: '', parameters: { airspaceThemeCount: 2 } })
const preview = computed(() => runtime.taskPreview.value)
const selectedTemplate = computed(() => runtime.scenarioTemplates.value.find(item => item.id === form.scenarioTemplateId))
const previewMatchesForm = computed(() => {
  const current = preview.value
  if (!current || current.scenarioTemplateId !== form.scenarioTemplateId) return false
  const resolved = current.resolvedParameters || current.plan?.resolvedParameters || {}
  return Number(resolved.airspaceThemeCount) === Number(form.parameters.airspaceThemeCount)
})
const economyQuote = computed(() => preview.value?.plan?.economyQuote || {})
const groundVehicle = computed(() => preview.value?.plan?.groundVehicle || null)
const airVehicle = computed(() => preview.value?.plan?.airVehicle || null)
const deliveryPoints = computed(() => preview.value?.plan?.deliveryPoints || [])
const groundRewardCount = computed(() => deliveryPoints.value.filter(item => item.kind === 'GROUND').length)
const airRewardCount = computed(() => deliveryPoints.value.filter(item => item.kind === 'AIR').length)
const airspaceCount = computed(() => Number(preview.value?.plan?.airspaceVolumeCount || preview.value?.plan?.airspace?.volumes?.length || preview.value?.plan?.noFlyZoneCount || 0))
const blockingAirspaceCount = computed(() => Number(preview.value?.plan?.noFlyZoneCount || 0))
const diamondCount = computed(() => preview.value?.plan?.rewardDiamonds?.length || 0)
const primaryChallenge = computed(() => preview.value?.plan?.rewardDiamonds?.find(item => item.challengeType === 'AIRSPACE') || preview.value?.plan?.rewardDiamonds?.[0] || null)
const finePolicy = computed(() => economyQuote.value.airspaceFine || {})
const airspaceProfile = computed(() => preview.value?.plan?.airspaceProfile || preview.value?.plan?.airspace?.airspaceProfile || {})
const airspaceThemeLabel = computed(() => (airspaceProfile.value.themeLabels || []).join(' · ') || '常规空域')
const airBatteryRangeLabel = computed(() => {
  const minimum = Number(economyQuote.value.estimatedAirBatteryUsePercentMin)
  const maximum = Number(economyQuote.value.estimatedAirBatteryUsePercentMax ?? economyQuote.value.estimatedAirBatteryUsePercent)
  if (Number.isFinite(minimum) && Number.isFinite(maximum)) return `${minimum.toFixed(1)}%—${maximum.toFixed(1)}%`
  return percentLabel(maximum)
})
const groundBatterySufficient = computed(() => economyQuote.value.groundBatterySufficient ?? economyQuote.value.batterySufficient ?? true)
const airBatterySufficient = computed(() => economyQuote.value.airBatterySufficient !== false)
const deliveryRewardMinor = computed(() => Number(economyQuote.value.groundCargoRewardMinor ?? economyQuote.value.baseGroundRewardMinor ?? 0) + Number(economyQuote.value.airCargoRewardMinor ?? economyQuote.value.airCoinRewardMinor ?? 0))
const timelinessRewardMinor = computed(() => Number(economyQuote.value.estimatedTimelinessRewardMinor || 0))
const diamondPotentialMinor = computed(() => Number(economyQuote.value.diamondPotentialMinor || primaryChallenge.value?.rewardMinor || 0))
const estimatedGrossRewardMinor = computed(() => Number(economyQuote.value.estimatedGrossRewardMinor ?? (deliveryRewardMinor.value + timelinessRewardMinor.value + diamondPotentialMinor.value)))
const maximumGrossRewardMinor = computed(() => Number(economyQuote.value.maximumGrossRewardMinor ?? economyQuote.value.grossRewardMinor ?? estimatedGrossRewardMinor.value))
const taskTitle = computed(() => {
  const area = String(selectedTemplate.value?.name || preview.value?.plan?.name || '联合配送任务').replace(/·动态车机协同配送$/, '').replace(/物流运营区/g, '').replace(/·/g, ' · ')
  return area.includes('联合配送') ? area : `${area}联合配送`
})
const routeEndpointLabel = computed(() => selectedTemplate.value?.fixedGroundStart ? `${selectedTemplate.value.startLabel} → ${selectedTemplate.value.endLabel}` : '')
const riskLabel = computed(() => airspaceProfile.value.themeCount ? `${airspaceProfile.value.themeCount} 种空域主题` : diamondCount.value ? '奖励航线' : blockingAirspaceCount.value ? '空域需谨慎' : '常规航线')
const readinessLabel = computed(() => runtime.busy.value ? 'PLANNING' : preview.value ? 'READY' : 'SETUP')
const durationClock = computed(() => {
  const seconds = Math.max(0, Math.round(Number(preview.value?.plan?.estimatedDurationSeconds || preview.value?.plan?.durationSeconds || 0)))
  return seconds ? `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}` : '—'
})
const challengeDescription = computed(() => {
  const challenge = primaryChallenge.value
  const action = challenge?.linkedVolumeLabel && challenge?.actionLabel
    ? `${challenge.linkedVolumeLabel}要求“${challenge.actionLabel}”并真实命中对应粉钻；` : ''
  return `本局 ${diamondCount.value} 枚粉钻总值 ${formatMoney(diamondPotentialMinor.value)}。${action}每个互动空域优先安排操纵挑战，剩余粉钻随机分布在安全的正常航段。`
})
const fineEntryLabel = computed(() => {
  const themes = new Set(airspaceProfile.value.themes || [])
  const labels = []
  if (themes.has('RED')) labels.push('红 ¥8,000/次')
  if (themes.has('PURPLE')) labels.push('紫 ¥3,000/局')
  if (themes.has('YELLOW')) labels.push('黄 ×2.5 耗电')
  if (themes.has('ORANGE')) labels.push('橙按时长')
  return labels.join(' · ') || (finePolicy.value.baseMinor == null ? '按类型处置' : `${formatMoney(finePolicy.value.baseMinor)} + ${formatMoney(finePolicy.value.perSecondMinor)}/秒`)
})
const batteryWarningLabel = computed(() => {
  if (!groundBatterySufficient.value && !airBatterySufficient.value) return '地面车辆与空中设备的当前电量均不足以覆盖预计路线'
  return groundBatterySufficient.value ? '空中设备当前电量不足以覆盖预计航线' : '地面车辆当前电量不足以覆盖预计路线'
})

function formatMoney(minor) { return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', maximumFractionDigits: 0 }).format(Number(minor || 0) / 100) }
function numberLabel(value, unit) { const number = Number(value); return Number.isFinite(number) ? `${number.toLocaleString('zh-CN')} ${unit}` : '—' }
function percentLabel(value) { const number = Number(value); return Number.isFinite(number) ? `${number.toFixed(1)}%` : '—' }
function applyTemplateDefaults() {
  if (preview.value?.scenarioTemplateId && preview.value.scenarioTemplateId !== form.scenarioTemplateId) {
    runtime.discardTaskPreview()
  }
  const defaults = selectedTemplate.value?.parameterDefaults
  if (!defaults) return
  form.parameters.airspaceThemeCount = Number(defaults.airspaceThemeCount || 2)
}
async function generate() {
  try {
    await runtime.generateTask({
      scenarioTemplateId: form.scenarioTemplateId,
      seed: runtime.tutorialGenerationPreset.value?.seed || null,
      parameters: {
        ...form.parameters,
        tutorialBatteryProtected: Boolean(runtime.tutorialGenerationPreset.value)
      }
    })
  } catch {
    // Runtime notice remains the single user-facing error; the action stays available for retry.
  }
}
async function start() {
  if (!previewMatchesForm.value) return
  try {
    await runtime.startPreview()
  } catch {
    // Runtime notice remains visible and the tutorial keeps this action retryable.
  }
}
watch(() => runtime.scenarioTemplates.value, items => {
  if (!form.scenarioTemplateId && items.length) { form.scenarioTemplateId = items[0].id; applyTemplateDefaults() }
}, { immediate: true })
</script>

<style scoped>
.task-planner select{color-scheme:dark}.task-planner select option{color:#effcff;background:#071b28}.task-planner select option:checked{color:#021017;background:#7ef0c4}
.task-planner{position:absolute;inset:92px auto 26px 24px;z-index:var(--layer-system);width:clamp(410px,28.5vw,478px);display:flex;flex-direction:column;border:1px solid rgba(169,217,226,.18);border-radius:16px 3px 16px 3px;color:var(--text-primary);background:linear-gradient(155deg,rgba(8,20,28,.95),rgba(3,12,20,.9));box-shadow:0 30px 90px rgba(0,0,0,.5),inset 0 1px rgba(255,255,255,.025);backdrop-filter:blur(18px) saturate(1.08);pointer-events:auto;overflow:hidden;animation:planner-arrive var(--motion-emphasis) var(--motion-ease-state)}
.task-planner::before{content:"";position:absolute;inset:0 auto 0 0;width:2px;background:linear-gradient(180deg,var(--signal-mint),rgba(126,240,196,.12) 38%,transparent 78%);pointer-events:none}.planner-header{position:relative;display:flex;justify-content:space-between;gap:18px;padding:20px 20px 17px 22px}.planner-header::after{content:"";position:absolute;left:22px;right:20px;bottom:0;border-bottom:1px solid rgba(172,217,227,.12)}.title-group{min-width:0}.title-group h2{max-width:330px;margin:5px 0 10px;font-size:clamp(1.1rem,1.25vw,1.3rem);font-weight:650;line-height:1.35;letter-spacing:.025em}.mission-tags{display:flex;gap:7px}.mission-tags span{padding:3px 7px;border-radius:999px;color:#a8c1c9;background:rgba(142,187,198,.09);font-size:.65rem;letter-spacing:.04em}.header-actions{display:flex;align-items:flex-start;gap:8px}.readiness{display:flex;align-items:center;gap:6px;margin-top:3px;color:var(--text-tertiary);font-size:.62rem;font-weight:800;letter-spacing:.13em}.readiness i{width:6px;height:6px;border-radius:50%;background:currentColor}.readiness.is-ready{color:var(--signal-mint)}.readiness.is-ready i{box-shadow:0 0 10px rgba(126,240,196,.7)}.readiness.is-busy{color:var(--signal-warning)}.readiness.is-busy i{animation:status-breathe 1s ease-in-out infinite}.close{width:28px;height:28px;display:grid;place-items:center;padding:0;border:0;border-radius:50%;color:var(--text-secondary);background:rgba(169,217,226,.06);font-size:1.25rem;line-height:1;transition:color var(--motion-fast),background var(--motion-fast),transform var(--motion-fast)}.close:hover,.close:focus-visible{color:#fff;background:rgba(169,217,226,.14);outline:none;transform:rotate(4deg)}
.planner-scroll{min-height:0;flex:1;padding:16px 20px 22px 22px;overflow:auto;scrollbar-width:thin;scrollbar-color:rgba(124,231,238,.3) transparent}.area-section{margin-bottom:19px}.section-heading{display:flex;justify-content:space-between;align-items:center;margin-bottom:7px;color:var(--text-secondary);font-size:.69rem}.section-heading small{max-width:65%;overflow:hidden;color:#75b6c2;font-size:.62rem;text-overflow:ellipsis;white-space:nowrap}.area-picker{position:relative}.area-picker select{width:100%;min-height:41px;appearance:none;padding:0 34px 0 0;border:0;border-bottom:1px solid rgba(172,217,227,.19);border-radius:0;color:var(--text-primary);background:transparent;font-size:.76rem;outline:none}.area-picker select:focus{border-bottom-color:var(--signal-mint)}.area-picker>span{position:absolute;right:5px;top:8px;color:var(--signal-primary);pointer-events:none}.briefing-content{animation:content-refresh 360ms var(--motion-ease-out)}
.zone-count-section{margin:-2px 0 20px}.zone-count-picker{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}.zone-count-picker label{min-height:42px;display:flex;align-items:center;justify-content:center;gap:3px;border:1px solid rgba(154,205,224,.14);border-radius:var(--radius-control);color:var(--text-secondary);background:rgba(8,28,39,.55);cursor:pointer;transition:border-color var(--motion-fast),background var(--motion-fast),color var(--motion-fast)}.zone-count-picker label:hover{border-color:rgba(126,240,196,.35)}.zone-count-picker label.selected{border-color:rgba(126,240,196,.7);color:var(--signal-mint);background:rgba(43,126,102,.18);box-shadow:inset 0 0 18px rgba(126,240,196,.05)}.zone-count-picker input{position:absolute;opacity:0;pointer-events:none}.zone-count-picker strong{font-size:1rem;font-weight:650}.zone-count-picker span{font-size:.62rem}.zone-count-picker label:focus-within{outline:2px solid rgba(126,240,196,.55);outline-offset:2px}
.mission-metrics{display:grid;grid-template-columns:repeat(4,1fr);gap:8px;padding:2px 0 20px;border-bottom:1px solid rgba(172,217,227,.1)}.metric{position:relative;min-width:0;display:grid;grid-template-columns:17px 1fr;align-items:center;gap:1px 7px;padding-left:2px}.metric:not(:last-child)::after{content:"";position:absolute;right:-4px;top:7px;bottom:7px;border-right:1px solid rgba(172,217,227,.09)}.metric svg{grid-row:1/3;width:16px;fill:none;stroke:var(--metric-color,var(--signal-primary));stroke-width:1.6;stroke-linecap:round;stroke-linejoin:round}.metric-ground svg{fill:rgba(124,231,238,.16)}.metric strong{overflow:hidden;font-size:1.25rem;font-weight:520;line-height:1.1;font-variant-numeric:tabular-nums;text-overflow:ellipsis}.metric span{color:var(--text-tertiary);font-size:.58rem;white-space:nowrap}.metric-ground{--metric-color:#75deea}.metric-air{--metric-color:var(--signal-mint)}.metric-airspace{--metric-color:#f48bb5}.metric-time{--metric-color:#c9d9dc}
.section-kicker{color:var(--text-tertiary);font-size:.57rem;font-weight:800;letter-spacing:.18em}.reward-section{padding:20px 0 18px;border-bottom:1px solid rgba(172,217,227,.1)}.reward-heading-row{display:flex;justify-content:space-between;align-items:flex-end;gap:16px}.reward-heading-row h3,.challenge-copy h3{margin:4px 0 0;font-size:.82rem;font-weight:620}.reward-hero{text-align:right}.reward-hero small{display:block;margin-bottom:3px;color:#ad9b69;font-size:.6rem}.reward-hero strong{display:block;color:#ffe494;font-size:1.85rem;font-weight:620;line-height:1;font-variant-numeric:tabular-nums;text-shadow:0 0 24px rgba(255,207,91,.13)}.reward-breakdown{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-top:16px}.reward-breakdown>div{display:grid;gap:4px}.reward-breakdown span{color:var(--text-tertiary);font-size:.61rem}.reward-breakdown b{color:#d7e6e8;font-size:.75rem;font-weight:570;font-variant-numeric:tabular-nums}.reward-breakdown .diamond-reward span,.reward-breakdown .diamond-reward b{color:#f18dcc}.diamond-reward i{font-style:normal;font-size:.57rem}
.challenge-section{display:grid;grid-template-columns:minmax(0,1.35fr) minmax(138px,.65fr);gap:16px;padding:20px 0}.challenge-section.has-challenge{background:radial-gradient(circle at 92% 35%,rgba(226,68,153,.09),transparent 40%)}.challenge-copy p{max-width:270px;margin:9px 0 0;color:#a7bac0;font-size:.68rem;line-height:1.65}.risk-reward{display:grid;gap:9px;padding-left:13px;border-left:1px solid rgba(240,117,180,.2)}.risk-reward>div{display:grid;gap:2px}.risk-reward span{color:var(--text-tertiary);font-size:.58rem}.risk-reward strong{font-size:.74rem;font-weight:650;font-variant-numeric:tabular-nums}.risk-reward small{color:#b98494;font-size:.57rem}.challenge-gain strong{color:#f18dcc}.challenge-risk strong{color:#efc4cb}.readiness-warning{display:grid;gap:5px;margin:0 0 16px;padding:10px 12px;border-left:2px solid var(--signal-critical);color:#e7bfc3;background:rgba(100,28,37,.18);font-size:.64rem;line-height:1.5}.readiness-warning strong{color:#ffd8dc;font-size:.69rem}
.advanced-section{border-top:1px solid rgba(172,217,227,.1)}.advanced-toggle{width:100%;min-height:42px;display:flex;justify-content:space-between;align-items:center;padding:0;border:0;color:var(--text-secondary);background:transparent;font-size:.68rem;text-align:left}.advanced-toggle>span{font-weight:650}.advanced-toggle i{margin-right:6px;color:var(--signal-primary);font-style:normal}.advanced-toggle b{color:var(--text-tertiary);font-size:.59rem;font-weight:500}.advanced-toggle b span{display:inline-block;margin-left:4px;transition:transform var(--motion-fast)}.advanced-toggle[aria-expanded="true"] b span{transform:rotate(180deg)}.advanced-fields{display:grid;gap:18px;padding:8px 0 4px;animation:advanced-open var(--motion-standard) var(--motion-ease-out)}.advanced-group{display:grid;gap:10px}.advanced-title{color:#73909a;font-size:.59rem;font-weight:750;letter-spacing:.14em;text-transform:uppercase}.advanced-group label,.developer-details label{display:grid;gap:6px;color:var(--text-secondary);font-size:.67rem}.advanced-group select,.seed-field input{box-sizing:border-box;width:100%;min-height:35px;padding:0 9px;border:1px solid rgba(154,205,224,.16);border-radius:var(--radius-control);color:var(--text-primary);background:rgba(8,28,39,.82)}.range-field>span{display:flex;justify-content:space-between}.range-field b{color:var(--signal-primary);font-weight:550}.range-field input{width:100%;height:16px;accent-color:var(--signal-mint)}.switch-field{grid-template-columns:1fr auto;align-items:center}.switch-field>span{display:grid;gap:3px}.switch-field small,.developer-details label small{color:var(--text-tertiary);font-size:.58rem}.switch-field input{width:17px;height:17px;accent-color:var(--signal-mint)}.vehicle-detail{display:grid;gap:5px;padding:9px 0;border-top:1px solid rgba(172,217,227,.08)}.vehicle-detail>div{display:flex;justify-content:space-between;gap:10px}.vehicle-detail span{color:var(--text-tertiary);font-size:.62rem}.vehicle-detail b{overflow:hidden;font-size:.67rem;font-weight:580;text-overflow:ellipsis;white-space:nowrap}.vehicle-detail small{color:#8ea5ad;font-size:.59rem;line-height:1.45}.route-summary dl,.developer-details dl{display:grid;gap:8px;margin:0}.route-summary dl>div,.developer-details dl>div{display:grid;grid-template-columns:80px 1fr;gap:12px}.route-summary dt,.developer-details dt{color:var(--text-tertiary);font-size:.61rem}.route-summary dd,.developer-details dd{min-width:0;margin:0;overflow:hidden;color:#c6d7da;font-size:.61rem;text-align:right;text-overflow:ellipsis;white-space:nowrap}.fallback-note{margin:0;color:#e0b97e;font-size:.6rem;line-height:1.5}.developer-details{padding:10px 12px;border-radius:var(--radius-control);background:rgba(1,8,13,.25)}.developer-details summary{color:var(--text-tertiary);font-size:.61rem;cursor:pointer}.developer-details[open]{display:grid;gap:12px}.seed-field{display:grid;grid-template-columns:1fr auto}.seed-field button{min-width:56px;border:1px solid rgba(124,231,238,.2);border-radius:0 var(--radius-control) var(--radius-control) 0;color:var(--signal-primary);background:rgba(9,34,45,.9);font-size:.62rem}
.fleet-summary{display:grid;gap:2px;padding-top:15px;border-top:1px solid rgba(172,217,227,.1)}
.empty-briefing{min-height:310px;display:grid;place-content:center;justify-items:center;padding:34px 18px;text-align:center}.route-symbol{width:118px;display:flex;align-items:center;margin-bottom:21px}.route-symbol i{width:11px;height:11px;border:2px solid var(--signal-mint);border-radius:50%;box-shadow:0 0 12px rgba(126,240,196,.25)}.route-symbol span{height:1px;flex:1;background:linear-gradient(90deg,var(--signal-mint),var(--signal-primary));opacity:.58}.empty-briefing strong{font-size:.9rem;font-weight:620}.empty-briefing p{max-width:285px;margin:9px 0 20px;color:var(--text-tertiary);font-size:.68rem;line-height:1.65}.empty-generate{min-width:172px;min-height:40px;border:0;border-radius:var(--radius-control);color:#031710;background:var(--signal-mint);font-size:.72rem;font-weight:780;box-shadow:0 9px 24px rgba(77,219,168,.12)}
.planner-footer{display:grid;grid-template-columns:auto 1fr auto;align-items:center;gap:14px;padding:13px 20px 14px 22px;border-top:1px solid rgba(172,217,227,.13);background:rgba(3,12,19,.92);box-shadow:0 -15px 32px rgba(1,8,13,.22)}.secondary-actions button{min-height:36px;padding:0;border:0;color:var(--text-secondary);background:transparent;font-size:.65rem}.secondary-actions button span{margin-right:4px;color:var(--signal-primary);font-size:.9rem}.maximum-reward{display:grid;gap:2px;text-align:right}.maximum-reward span{color:var(--text-tertiary);font-size:.55rem}.maximum-reward strong{color:#f4d47c;font-size:.77rem;font-weight:640;font-variant-numeric:tabular-nums}.start{min-width:142px;min-height:43px;padding:0 17px;border:0;border-radius:7px 2px 7px 2px;color:#031710;background:linear-gradient(135deg,#9af4d2,var(--signal-mint));box-shadow:0 9px 24px rgba(77,219,168,.18);font-size:.74rem;font-weight:850;letter-spacing:.04em;transition:transform var(--motion-fast),box-shadow var(--motion-fast)}.start span{margin-left:9px;font-size:1rem}.start:hover,.start:focus-visible{outline:none;transform:translateY(-1px);box-shadow:0 12px 30px rgba(77,219,168,.26)}button:disabled{opacity:.48;cursor:not-allowed}
@keyframes planner-arrive{from{opacity:0;transform:translate3d(-14px,0,0) scale(.985)}}@keyframes content-refresh{from{opacity:.2;transform:translateY(5px)}}@keyframes advanced-open{from{opacity:0;transform:translateY(-5px)}}@keyframes status-breathe{50%{opacity:.35;transform:scale(.72)}}
@media(max-width:1350px){.task-planner{inset:82px auto 16px 16px;width:420px}.planner-header{padding:17px 17px 15px 19px}.planner-scroll{padding:14px 17px 18px 19px}.planner-footer{padding:11px 17px 12px 19px}.title-group h2{font-size:1.03rem}.metric{grid-template-columns:15px 1fr;gap:1px 5px}.metric strong{font-size:1.08rem}.reward-hero strong{font-size:1.58rem}.challenge-section{gap:12px}.start{min-width:130px}}
@media(max-height:820px) and (min-width:1100px){.task-planner{inset:78px auto 12px 14px;width:406px}.planner-header{padding-top:14px;padding-bottom:12px}.mission-tags{display:none}.planner-scroll{padding-top:12px}.area-section{margin-bottom:12px}.mission-metrics{padding-bottom:12px}.reward-section{padding:13px 0 12px}.reward-breakdown{margin-top:12px}.challenge-section{padding:12px 0}.challenge-copy p{margin-top:6px;line-height:1.5}.advanced-toggle{min-height:36px}.planner-footer{padding-top:9px;padding-bottom:9px}.start{min-height:39px}}
@media(prefers-reduced-motion:reduce){.task-planner,.briefing-content,.advanced-fields,.readiness.is-busy i{animation:none}.start{transition:none}}
.vehicle-detail .risk-energy{color:#d9bc68}
</style>
