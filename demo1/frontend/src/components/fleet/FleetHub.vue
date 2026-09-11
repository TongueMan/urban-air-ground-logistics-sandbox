<template>
  <div class="fleet-hub-backdrop" @mousedown.self="close">
    <section
      ref="dialog"
      class="fleet-hub"
      :class="{ 'has-guidance': runtime.guidance.value }"
      data-tutorial-id="fleet-hub"
      role="dialog"
      aria-modal="true"
      aria-labelledby="fleet-hub-title"
      tabindex="-1"
    >
      <header class="hub-header">
        <div class="hub-identity">
          <span>物流资产终端 / 01</span>
          <h2 id="fleet-hub-title">车队中心 <small>FLEET HUB</small></h2>
        </div>
        <nav class="category-tabs" aria-label="车队分类" data-tutorial-id="fleet-categories">
          <button
            v-for="item in FLEET_CATEGORIES"
            :key="item.id"
            type="button"
            :class="{ active: runtime.category.value === item.id }"
            :aria-pressed="runtime.category.value === item.id"
            @click="runtime.selectFirstForCategory(item.id)"
          ><small>{{ item.english }}</small>{{ item.label }}</button>
        </nav>
        <div class="company-readout" aria-label="公司资产摘要" data-tutorial-id="fleet-balance">
          <span><small>可用资金</small><b>{{ formatCny(runtime.company.value.balanceMinor) }}</b></span>
          <span><small>公司资产</small><b>{{ runtime.counts.value.total }} 台</b></span>
        </div>
        <button class="close-hub" type="button" aria-label="关闭车队中心" @click="close">×<small>ESC</small></button>
      </header>

      <div v-if="runtime.loading.value && !runtime.initialized.value" class="hub-loading" role="status">
        <b>正在连接车队数据</b><span>正在读取公司资产，请稍候…</span>
      </div>
      <div v-else-if="runtime.error.value && !runtime.catalog.value.length" class="hub-loading error" role="alert">
        <b>车队数据连接失败</b><span>{{ runtime.error.value }}</span>
        <button class="spatial-button" type="button" @click="runtime.initialize({ force: true })">重新连接</button>
      </div>
      <template v-else>
        <div v-if="runtime.guidance.value" class="tutorial-guidance" role="alert" aria-live="assertive">
          <span class="guidance-badge">教程 00 引导</span>
          <div class="guidance-copy">
            <strong>{{ runtime.guidance.value.title }}</strong>
            <p>{{ runtime.guidance.value.message }}</p>
            <small><b>下一步</b>{{ runtime.guidance.value.action }}</small>
          </div>
          <button type="button" aria-label="关闭教程跳转提示" @click="runtime.dismissGuidance">知道了</button>
        </div>
        <div class="hub-body">
          <FleetModelViewer :model-asset-id="selectedType?.modelAssetId || ''" :active="true" />

          <aside class="asset-detail" aria-live="polite" data-tutorial-id="fleet-detail">
            <div v-if="selectedType" class="detail-content">
              <p class="detail-code">{{ categoryLabel(selectedType.category) }} · 车型编号 {{ selectedType.typeId }}</p>
              <h3>{{ selectedType.name }}</h3>
              <p class="english-name">{{ selectedType.englishName }}</p>
              <p class="summary">{{ selectedType.summary }}</p>
              <div class="load-hint"><span>模型资源</span><b>{{ selectedType.loadHint }}</b></div>

              <dl class="rating-grid">
                <div v-for="rating in ratings" :key="rating.key">
                  <dt>{{ rating.label }}</dt>
                  <dd><i v-for="level in 5" :key="level" :class="{ active: level <= rating.value }"></i></dd>
                </div>
              </dl>
              <dl v-if="selectedType.gameplayStats" class="gameplay-grid" aria-label="实际运输参数">
                <div><dt>{{ selectedType.category === 'AIR' ? '飞行速度' : '道路速度' }}</dt><dd>{{ selectedType.gameplayStats.speedKph }} km/h</dd></div>
                <div><dt>{{ selectedType.category === 'AIR' ? '满电航程' : '满电续航' }}</dt><dd>{{ selectedType.gameplayStats.fullRangeKm }} km</dd></div>
                <div><dt>运载收益</dt><dd>{{ selectedType.gameplayStats.cargoMultiplier }}×</dd></div>
                <div v-if="selectedType.category === 'AIR'"><dt>机动响应</dt><dd>{{ selectedType.gameplayStats.maneuverDelaySeconds }} 秒</dd></div>
              </dl>

              <div class="price-block">
                <span>{{ runtime.devMode.value ? '开发者覆盖价' : '采购价格' }}</span>
                <strong>{{ formatCny(effectivePrice) }}</strong>
                <small v-if="runtime.devMode.value">标准价格 {{ formatCny(selectedType.priceMinor) }}</small>
                <small v-else>采购后余额 {{ formatCny(Math.max(0, runtime.company.value.balanceMinor - effectivePrice)) }}</small>
              </div>

              <div v-if="runtime.confirmingTypeId.value === selectedType.typeId" class="purchase-confirm" role="alertdialog" aria-label="确认采购">
                <p>确认采购一台 {{ selectedType.name }}？本次将扣除 {{ formatCny(effectivePrice) }}。</p>
                <div><button type="button" @click="runtime.confirmingTypeId.value = ''">取消</button><button type="button" class="primary" @click="confirmPurchase">确认采购</button></div>
              </div>
              <button
                v-else
                class="purchase-button"
                type="button"
                data-tutorial-id="fleet-purchase"
                :disabled="runtime.busyKey.value || insufficientFunds"
                @click="requestPurchase"
              >
                <span>{{ runtime.busyKey.value === `purchase:${selectedType.typeId}` ? '正在处理…' : selectedInstances.length ? '增购一台' : '采购并入库' }}</span>
                <b>{{ runtime.devMode.value ? '开发者价 ¥0' : formatCny(selectedType.priceMinor) }}</b>
              </button>
              <p v-if="insufficientFunds" class="funds-warning">余额不足，还需 {{ formatCny(effectivePrice - runtime.company.value.balanceMinor) }}</p>

              <section class="instance-list" aria-label="具体设备实例" data-tutorial-id="fleet-instances">
                <header><span>设备实例</span><b>{{ selectedInstances.length }} 台</b></header>
                <div v-if="selectedInstances.length" class="instance-scroll">
                  <article v-for="asset in selectedInstances" :key="asset.assetId" :class="{ selected: runtime.selectedAssetId.value === asset.assetId }" @click="runtime.selectedAssetId.value = asset.assetId">
                    <div>
                      <b>{{ compactAssetId(asset.assetId) }}</b>
                      <small>{{ asset.acquisitionSource === 'INITIAL' ? '初始配备' : '采购入库' }}<em v-if="runtime.activeSceneAssetIds.value.has(asset.assetId)">地图使用中</em></small>
                    </div>
                    <div class="instance-energy" :class="`battery-${batteryTone(projectedBattery(asset))}`">
                      <b>{{ projectedBattery(asset).toFixed(0) }}%</b>
                      <small>{{ chargeLabel(asset) }}</small>
                    </div>
                    <span :class="asset.status.toLowerCase()">{{ assetStatusLabel(asset) }}</span>
                    <div class="instance-actions">
                      <button
                        type="button"
                        :disabled="!!runtime.busyKey.value || !!asset.activeRunId"
                        :title="asset.activeRunId ? '设备正在执行任务，请先结束任务' : ''"
                        @click.stop="runtime.setAssetStatus(asset, asset.status === 'GARAGED' ? 'DEPLOYED' : 'GARAGED')"
                      >{{ asset.status === 'GARAGED' ? '出站' : '召回' }}</button>
                      <button
                        class="sell-action"
                        type="button"
                        :disabled="!!runtime.busyKey.value || asset.status !== 'GARAGED' || !!asset.activeRunId"
                        :title="asset.status === 'GARAGED' ? `按原价出售 ${selectedType.name}` : '请先召回车库后再出售'"
                        @click.stop="requestSale(asset)"
                      >出售</button>
                    </div>
                  </article>
                </div>
                <p v-else class="no-instance">尚未拥有该车型。采购后设备将直接进入车库。</p>
                <div v-if="sellingAsset" ref="saleConfirm" class="sale-confirm" role="alertdialog" aria-label="确认出售">
                  <p>确认按原价 <b>{{ formatCny(selectedType.priceMinor) }}</b> 出售 {{ selectedType.name }}？</p>
                  <small>售出后，该设备将从当前车队移除，款项立即转入公司账户。</small>
                  <div>
                    <button type="button" :disabled="!!runtime.busyKey.value" @click="runtime.sellingAssetId.value = ''">取消</button>
                    <button type="button" class="danger" :disabled="!!runtime.busyKey.value" @click="confirmSale">
                      {{ runtime.busyKey.value === `sale:${sellingAsset.assetId}` ? '正在出售…' : '确认出售' }}
                    </button>
                  </div>
                </div>
              </section>
            </div>
          </aside>
        </div>

        <div class="catalog-deck" data-tutorial-id="fleet-catalog">
          <FleetCardRail eyebrow="01" title="我的车队" :items="sections.owned" :selected-id="runtime.selectedTypeId.value" empty-text="该分类暂无公司资产" @select="runtime.selectType" />
          <FleetCardRail eyebrow="02" title="可采购车型" :items="sections.market" :selected-id="runtime.selectedTypeId.value" empty-text="该分类车型已全部拥有，可在详情中继续增购" @select="runtime.selectType" />
        </div>

        <footer class="hub-footer" data-tutorial-id="fleet-deployment">
          <div class="fleet-state-summary"><span><i class="garaged"></i>车库中 {{ runtime.counts.value.garaged }}</span><span><i class="deployed"></i>已出站 {{ runtime.counts.value.deployed }}</span><small>地面与空中各限一台出站；切换时自动召回同类设备</small></div>
          <p v-if="runtime.error.value" class="operation-message error" role="alert">{{ runtime.error.value }}</p>
          <p v-else-if="runtime.notice.value" class="operation-message" role="status">{{ runtime.notice.value }}</p>
          <label v-if="runtime.snapshot.value.devModeAllowed" class="dev-toggle">
            <input v-model="runtime.devMode.value" type="checkbox">
            <span>开发者免费模式</span>
          </label>
        </footer>
        <div v-if="runtime.devMode.value" class="dev-ribbon" role="status">开发者模式已开启 · 采购价格覆盖为 ¥0</div>
      </template>
    </section>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { FLEET_CATEGORIES, effectiveFleetPrice, fleetSections, formatCny } from '../../fleet/fleetCatalog.mjs'
import { batteryTone, chargeRemainingSeconds, projectedBatteryPercent } from '../../fleet/vehicleGameplay.mjs'
import FleetCardRail from './FleetCardRail.vue'
import FleetModelViewer from './FleetModelViewer.vue'

const props = defineProps({ runtime: { type: Object, required: true } })
const runtime = props.runtime
const dialog = ref(null)
const saleConfirm = ref(null)
const clockNow = ref(Date.now())
let clockTimer = 0
const previousFocus = document.activeElement
const sections = computed(() => fleetSections(runtime.snapshot.value, runtime.category.value))
const selectedType = computed(() => runtime.selectedType.value)
const selectedInstances = computed(() => runtime.selectedInstances.value)
const sellingAsset = computed(() => selectedInstances.value.find(asset => asset.assetId === runtime.sellingAssetId.value) || null)
const effectivePrice = computed(() => effectiveFleetPrice(selectedType.value, runtime.devMode.value))
const insufficientFunds = computed(() => effectivePrice.value > Number(runtime.company.value.balanceMinor || 0))
const ratingLabels = { capacity: '运载', agility: '灵活', speed: '速度', endurance: '续航' }
const ratings = computed(() => Object.entries(selectedType.value?.demoRatings || {}).map(([key, value]) => ({ key, label: ratingLabels[key] || key, value })))

function compactAssetId(value) { return String(value || '').replace(/^FLT-/, '').slice(0, 13) }
function categoryLabel(value) { return value === 'AIR' ? '空中运输' : '地面运输' }
function assetStatusLabel(asset) { return asset.activeRunId ? '任务中' : asset.status === 'DEPLOYED' ? '已出站' : asset.charging ? '充电中' : '车库中' }
function projectedBattery(asset) { return Math.max(0, Math.min(100, Number(projectedBatteryPercent(asset, clockNow.value)) || 0)) }
function chargeLabel(asset) {
  if (!asset.charging) return Number(asset.batteryPercent) <= 0 ? '电量耗尽' : '当前电量'
  const remaining = chargeRemainingSeconds(asset, clockNow.value)
  return remaining > 0 ? `充满还需 ${remaining} 秒` : '充电完成'
}
function close() { runtime.close() }
function requestPurchase() {
  runtime.error.value = ''
  if (insufficientFunds.value) { runtime.error.value = '公司余额不足，无法执行本次采购。'; return }
  if (runtime.devMode.value || effectivePrice.value === 0) runtime.purchase(selectedType.value.typeId)
  else runtime.confirmingTypeId.value = selectedType.value.typeId
}
function confirmPurchase() { runtime.purchase(selectedType.value.typeId) }
async function requestSale(asset) {
  runtime.error.value = ''
  runtime.notice.value = ''
  runtime.confirmingTypeId.value = ''
  runtime.selectedAssetId.value = asset.assetId
  runtime.sellingAssetId.value = asset.assetId
  await nextTick()
  saleConfirm.value?.scrollIntoView({ block: 'nearest' })
}
function confirmSale() { if (sellingAsset.value) runtime.sell(sellingAsset.value) }
function focusable() {
  return [...(dialog.value?.querySelectorAll('button:not(:disabled),input:not(:disabled),[tabindex]:not([tabindex="-1"])') || [])]
}
function onKeydown(event) {
  if (event.key === 'Escape') { event.preventDefault(); close(); return }
  if (event.key !== 'Tab') return
  const nodes = focusable()
  if (!nodes.length) { event.preventDefault(); dialog.value?.focus(); return }
  const first = nodes[0], last = nodes[nodes.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
}
onMounted(async () => { document.addEventListener('keydown', onKeydown); clockTimer = window.setInterval(() => { clockNow.value = Date.now() }, 1000); await nextTick(); dialog.value?.focus() })
onBeforeUnmount(() => { document.removeEventListener('keydown', onKeydown); if (clockTimer) window.clearInterval(clockTimer); previousFocus?.focus?.() })
</script>

<style scoped>
.fleet-hub-backdrop { position:absolute; inset:0; z-index:calc(var(--layer-tutorial) + 10); display:grid; place-items:center; padding:3vh 2.5vw; pointer-events:auto; background:rgba(1,6,10,.7); backdrop-filter:brightness(.42) saturate(.68) blur(2px); }
.fleet-hub { position:relative; width:min(1760px,95vw); height:min(920px,94vh); min-height:620px; display:grid; grid-template-rows:80px minmax(0,1fr) 210px 48px; overflow:hidden; border:1px solid rgba(124,231,238,.38); outline:none; color:var(--text-primary); background:linear-gradient(150deg,rgba(5,19,28,.99),rgba(2,8,14,.99)); box-shadow:0 28px 100px rgba(0,0,0,.62),inset 0 1px 0 rgba(201,249,255,.1); clip-path:polygon(0 0,calc(100% - 22px) 0,100% 22px,100% 100%,22px 100%,0 calc(100% - 22px)); }
.fleet-hub.has-guidance{grid-template-rows:80px auto minmax(0,1fr) 210px 48px}
.fleet-hub::before { content:""; position:absolute; z-index:8; left:0; top:0; width:110px; border-top:3px solid var(--signal-primary); pointer-events:none; }
.hub-header { display:grid; grid-template-columns:minmax(260px,1fr) auto minmax(310px,1fr) 54px; align-items:center; gap:18px; padding:10px 16px 9px 22px; border-bottom:1px solid rgba(154,205,224,.2); background:rgba(6,21,31,.92); }
.hub-identity span { color:var(--text-secondary); font:12px/1 monospace; letter-spacing:.12em; }
.hub-identity h2 { margin:7px 0 0; font:600 25px/1 sans-serif; letter-spacing:.1em; }
.hub-identity h2 small { margin-left:8px; color:var(--signal-primary); font:700 12px/1 monospace; }
.category-tabs { align-self:stretch; display:flex; }
.category-tabs button { min-width:150px; display:grid; place-content:center; gap:5px; border:0; border-left:1px solid rgba(154,205,224,.13); color:var(--text-secondary); background:transparent; font:600 15px/1.2 sans-serif; }
.category-tabs button:last-child { border-right:1px solid rgba(154,205,224,.13); }
.category-tabs button small { font:700 12px/1 monospace; letter-spacing:.12em; }
.category-tabs button:hover,.category-tabs button:focus-visible,.category-tabs button.active { color:var(--text-primary); outline:none; background:linear-gradient(to top,rgba(57,230,255,.13),transparent); box-shadow:inset 0 -2px 0 var(--signal-primary); }
.company-readout { display:flex; justify-content:end; gap:26px; }
.company-readout span { display:grid; gap:5px; text-align:right; }
.company-readout small { color:var(--text-secondary); font:12px/1 monospace; letter-spacing:.06em; }
.company-readout b { color:#eaffff; font:600 16px/1 monospace; }
.close-hub { height:44px; display:grid; grid-template-columns:1fr; place-content:center; border:1px solid rgba(154,205,224,.24); color:var(--text-secondary); background:rgba(3,13,20,.7); font:300 23px/17px sans-serif; }
.close-hub small { color:var(--text-secondary); font:12px/1 monospace; }
.close-hub:hover,.close-hub:focus-visible { border-color:var(--signal-critical); color:var(--signal-critical); outline:none; }
.tutorial-guidance{z-index:7;display:grid;grid-template-columns:auto minmax(0,1fr) auto;align-items:center;gap:16px;padding:11px 18px;border-bottom:1px solid rgba(255,200,103,.42);color:#fff2cc;background:linear-gradient(90deg,rgba(91,57,11,.98),rgba(42,31,14,.97) 62%,rgba(18,29,31,.96));box-shadow:0 10px 30px rgba(0,0,0,.28)}
.guidance-badge{padding:6px 9px;border:1px solid rgba(255,215,137,.55);color:#ffd783;background:rgba(255,185,62,.1);font:750 12px/1 monospace;letter-spacing:.08em;white-space:nowrap}.guidance-copy{min-width:0}.guidance-copy strong{display:block;color:#fff5d9;font-size:15px}.guidance-copy p{margin:3px 0 0;color:#efdcb3;font-size:13px;line-height:1.4}.guidance-copy small{display:block;margin-top:4px;color:#fff1c8;font-size:13px;line-height:1.4}.guidance-copy small b{margin-right:7px;color:#ffc75e}.tutorial-guidance>button{min-height:36px;padding:0 13px;border:1px solid rgba(255,215,137,.38);color:#ffe9b4;background:rgba(7,18,22,.5);font-size:13px;white-space:nowrap}.tutorial-guidance>button:hover,.tutorial-guidance>button:focus-visible{border-color:#ffd783;color:#fff;outline:none}
.hub-body { min-height:0; display:grid; grid-template-columns:minmax(0,1.75fr) minmax(370px,.78fr); gap:14px; padding:14px 16px 10px; }
.asset-detail { min-width:0; overflow:hidden; border-left:1px solid rgba(154,205,224,.2); background:linear-gradient(90deg,rgba(9,29,39,.7),rgba(3,13,20,.34)); }
.detail-content { height:100%; overflow-y:auto; padding:8px 15px 12px 20px; scrollbar-width:thin; scrollbar-color:rgba(124,231,238,.3) transparent; }
.detail-code { margin:0; color:var(--signal-primary); font:12px/1.35 monospace; letter-spacing:.06em; }
.detail-content h3 { margin:9px 0 3px; font:600 24px/1.2 sans-serif; }
.english-name { margin:0; color:var(--text-secondary); font:12px/1.35 monospace; text-transform:uppercase; }
.summary { margin:13px 0; color:var(--text-secondary); font-size:16px; line-height:1.55; }
.load-hint { display:flex; justify-content:space-between; gap:12px; padding:9px 0; border-top:1px solid var(--surface-line); border-bottom:1px solid var(--surface-line); }
.load-hint span { color:var(--text-secondary); font:12px/1.3 monospace; }.load-hint b{color:var(--text-secondary);font-size:14px;line-height:1.3;text-align:right}
.rating-grid { display:grid; grid-template-columns:1fr 1fr; gap:10px 20px; margin:14px 0; }
.rating-grid div { display:flex; align-items:center; justify-content:space-between; gap:8px; }
.rating-grid dt { color:var(--text-secondary); font-size:14px; }
.rating-grid dd { display:flex; gap:3px; margin:0; }
.rating-grid i { width:15px; height:5px; background:rgba(124,231,238,.16); }.rating-grid i.active{background:var(--signal-primary);box-shadow:0 0 5px rgba(124,231,238,.42)}
.gameplay-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(92px,1fr));gap:7px;margin:0 0 12px}.gameplay-grid div{display:grid;gap:4px;padding:8px 9px;border:1px solid rgba(124,231,238,.14);background:rgba(8,34,44,.48)}.gameplay-grid dt{color:var(--text-secondary);font-size:12px}.gameplay-grid dd{margin:0;color:#dffcff;font:700 13px/1.2 monospace}
.price-block { display:grid; grid-template-columns:1fr auto; align-items:end; padding:11px 12px; border-left:3px solid var(--signal-warning); background:rgba(57,40,11,.36); }
.price-block span { color:var(--text-secondary); font:12px/1.2 monospace; }.price-block strong{color:#ffe2a8;font:600 24px/1 monospace}.price-block small{grid-column:1/-1;margin-top:6px;color:var(--text-secondary);font-size:13px}
.purchase-button { width:100%; min-height:46px; display:flex; align-items:center; justify-content:space-between; gap:12px; margin-top:10px; padding:0 13px; border:1px solid rgba(124,231,238,.52); color:var(--text-primary); background:linear-gradient(90deg,rgba(13,72,82,.85),rgba(6,41,51,.86)); font-size:15px; }
.purchase-button span { font-weight:700; }.purchase-button b{color:var(--signal-primary);font:700 14px/1 monospace}
.purchase-button:hover:not(:disabled),.purchase-button:focus-visible:not(:disabled){border-color:#dffcff;background:rgba(21,92,103,.9);outline:none}
.purchase-confirm { margin-top:10px; padding:11px; border:1px solid rgba(255,197,111,.48); background:rgba(46,31,10,.86); }
.purchase-confirm p { margin:0 0 10px; color:#ffe8bf; font-size:14px; line-height:1.5; }.purchase-confirm div{display:flex;justify-content:end;gap:8px}.purchase-confirm button{min-height:36px;padding:7px 12px;border:1px solid rgba(255,220,164,.28);color:var(--text-secondary);background:rgba(5,15,21,.7);font-size:14px}.purchase-confirm .primary{border-color:var(--signal-warning);color:#fff0ce}
.funds-warning { margin:7px 0 0; color:var(--signal-critical); font-size:13px; }
.instance-list { margin-top:13px; }.instance-list>header{display:flex;justify-content:space-between;padding-bottom:7px;border-bottom:1px solid var(--surface-line);color:var(--text-secondary);font:12px/1.2 monospace;letter-spacing:.06em}
.instance-scroll { max-height:132px; overflow-y:auto; }
.instance-scroll article { display:grid; grid-template-columns:minmax(88px,1fr) 80px auto auto; align-items:center; gap:9px; padding:8px 0; border-bottom:1px solid rgba(154,205,224,.1); cursor:default; }
.instance-scroll article.selected { background:linear-gradient(90deg,rgba(124,231,238,.08),transparent); }
.instance-scroll article div { min-width:0; display:grid; gap:3px; }.instance-scroll article div b{overflow:hidden;color:var(--text-secondary);font:12px/1.2 monospace;text-overflow:ellipsis}.instance-scroll article div small{color:var(--text-secondary);font-size:12px}.instance-scroll article div em{margin-left:6px;color:#8fffd0;font-style:normal;font-weight:700}
.instance-scroll .instance-energy{padding-left:8px;border-left:2px solid var(--battery-color,rgba(154,205,224,.35))}.instance-scroll .instance-energy b{color:var(--battery-color,#b9cbd3)}.instance-scroll .instance-energy small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.instance-energy.battery-green{--battery-color:#62ef9b}.instance-energy.battery-yellow{--battery-color:#f2df66}.instance-energy.battery-orange{--battery-color:#ff9f43}.instance-energy.battery-red,.instance-energy.battery-depleted{--battery-color:#ff5f62}
.instance-scroll article>span { padding:4px 7px; border:1px solid currentColor; font:12px/1 monospace; }.instance-scroll .garaged{color:var(--signal-primary)}.instance-scroll .deployed{color:var(--signal-mint)}
.instance-actions { display:flex!important; grid-auto-flow:column; gap:6px!important; }
.instance-scroll button { min-height:34px; padding:6px 9px; border:1px solid var(--surface-line-strong); color:var(--text-secondary); background:rgba(7,25,34,.8); font-size:13px; }
.instance-scroll button:hover:not(:disabled),.instance-scroll button:focus-visible:not(:disabled){border-color:var(--signal-primary);color:var(--text-primary);outline:none}
.instance-scroll .sell-action{border-color:rgba(255,126,94,.46);color:#ffb19d;background:rgba(65,20,14,.46)}
.instance-scroll .sell-action:hover:not(:disabled),.instance-scroll .sell-action:focus-visible:not(:disabled){border-color:#ff8568;color:#ffe1d9;background:rgba(91,28,18,.7)}
.instance-scroll button:disabled{cursor:not-allowed;opacity:.46}
.sale-confirm{margin-top:9px;padding:10px 11px;border:1px solid rgba(255,126,94,.56);background:linear-gradient(100deg,rgba(72,22,15,.8),rgba(37,16,13,.72))}
.sale-confirm p{margin:0;color:#ffe0d7;font-size:14px;line-height:1.45}.sale-confirm p b{color:#ffb19d;font-family:monospace}.sale-confirm small{display:block;margin-top:4px;color:var(--text-secondary);font-size:12px;line-height:1.45}.sale-confirm>div{display:flex!important;justify-content:flex-end;gap:8px!important;margin-top:9px}.sale-confirm button{min-height:36px;padding:7px 12px;border:1px solid rgba(255,226,219,.24);color:var(--text-secondary);background:rgba(5,15,21,.72);font-size:14px}.sale-confirm .danger{border-color:#ff8568;color:#ffe3dc;background:rgba(102,30,19,.7)}
.no-instance { color:var(--text-secondary); font-size:14px; line-height:1.55; }
.catalog-deck { min-width:0; display:grid; grid-template-columns:1fr 1fr; gap:20px; padding:12px 16px 14px; border-top:1px solid rgba(154,205,224,.18); background:rgba(3,12,18,.72); }
.hub-footer { display:grid; grid-template-columns:minmax(0,1fr) minmax(260px,.7fr) auto; align-items:center; gap:16px; padding:0 16px; border-top:1px solid rgba(154,205,224,.14); background:#040e15; }
.fleet-state-summary { min-width:0; display:flex; align-items:center; gap:16px; color:var(--text-secondary); font:13px/1 monospace; }.fleet-state-summary span{display:flex;align-items:center;gap:7px;white-space:nowrap}.fleet-state-summary i{width:8px;height:8px;border-radius:50%;background:var(--signal-primary)}.fleet-state-summary i.deployed{background:var(--signal-mint)}.fleet-state-summary small{overflow:hidden;color:var(--text-secondary);font-size:12px;text-overflow:ellipsis;white-space:nowrap}
.operation-message { grid-column:2; overflow:hidden; margin:0; color:var(--signal-mint); font-size:13px; text-align:right; text-overflow:ellipsis; white-space:nowrap; }.operation-message.error{color:var(--signal-critical)}
.dev-toggle { grid-column:3; display:flex; align-items:center; gap:8px; color:#ffe0a2; font:14px/1 sans-serif; cursor:pointer; white-space:nowrap; }.dev-toggle input{width:18px;height:18px;accent-color:var(--signal-warning)}
.dev-ribbon { position:absolute; z-index:10; right:70px; top:76px; padding:7px 11px; border:1px solid rgba(255,197,111,.66); color:#ffe0a2; background:rgba(61,39,7,.96); font:700 13px/1.2 sans-serif; letter-spacing:.03em; }
.hub-loading { grid-row:2/5; display:grid; place-content:center; gap:10px; text-align:center; color:var(--text-secondary);font-size:15px }.hub-loading b{color:var(--signal-primary);font:700 16px/1.3 sans-serif;letter-spacing:.08em}.hub-loading.error b{color:var(--signal-critical)}.hub-loading button{justify-self:center}
@media (max-width:1350px), (max-height:820px) {
  .fleet-hub-backdrop{padding:1.5vh 1vw}.fleet-hub{width:98vw;height:97vh;min-height:600px;grid-template-rows:72px minmax(0,1fr) 190px 46px}.fleet-hub.has-guidance{grid-template-rows:72px auto minmax(0,1fr) 190px 46px}.hub-header{grid-template-columns:minmax(190px,1fr) auto minmax(245px,1fr) 46px;gap:9px;padding:8px 12px 7px 15px}.hub-identity span{display:none}.hub-identity h2{margin:0;font-size:20px}.hub-identity h2 small{font-size:12px}.category-tabs button{min-width:120px;font-size:14px}.category-tabs button small{font-size:12px}.company-readout{gap:13px}.company-readout small{font-size:12px}.company-readout b{font-size:14px}.close-hub{height:42px}.tutorial-guidance{gap:10px;padding:9px 13px}.guidance-copy p,.guidance-copy small{font-size:12px}.hub-body{grid-template-columns:minmax(0,1.55fr) minmax(330px,.8fr);gap:10px;padding:10px 12px 8px}.detail-content{padding:5px 11px 8px 15px}.detail-code,.english-name{font-size:12px}.detail-content h3{margin-top:6px;font-size:20px}.summary{margin:8px 0;font-size:14px;line-height:1.45}.load-hint{padding:6px 0}.load-hint b{font-size:13px}.rating-grid{gap:7px 13px;margin:8px 0}.rating-grid dt{font-size:13px}.rating-grid i{width:12px;height:4px}.price-block{padding:8px 9px}.price-block strong{font-size:21px}.purchase-button{min-height:40px;margin-top:7px}.instance-list{margin-top:8px}.instance-scroll{max-height:88px}.catalog-deck{gap:12px;padding:9px 12px 10px}.fleet-state-summary{gap:11px;font-size:12px}.fleet-state-summary small{display:none}.operation-message{font-size:12px}.dev-toggle{font-size:13px}.dev-ribbon{right:62px;top:68px;font-size:12px}
}
</style>
