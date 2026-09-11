export const MISSION_REACTION_HOLD_MS = 2500
export const MISSION_REACTION_DURATION_MS = 3400

export const MISSION_REACTION_ASSETS = Object.freeze({
  diamond: Object.freeze({
    anan: '/mission/reactions/anan-diamond-praise.png',
    cheng: '/mission/reactions/cheng-diamond-praise.png'
  }),
  fine: Object.freeze({
    anan: '/mission/reactions/anan-airspace-fine.png',
    cheng: '/mission/reactions/cheng-airspace-fine.png'
  }),
  settlement: Object.freeze({
    anan: '/mission/reactions/anan-settlement-pose.png',
    cheng: '/mission/reactions/cheng-settlement-pose.png'
  })
})

export const MISSION_REACTION_ASSET_PATHS = Object.freeze(
  Object.values(MISSION_REACTION_ASSETS).flatMap(group => Object.values(group))
)

export const MISSION_REACTION_PRESENTATION = Object.freeze({
  diamond: Object.freeze({
    eyebrow: 'DIAMOND REWARD',
    title: '粉钻到手！',
    subtitle: '完美操作',
    dialogue: Object.freeze({
      anan: '漂亮！这颗粉钻稳稳拿下！',
      cheng: '路线够准，奖励当然要拉满！'
    })
  }),
  fine: Object.freeze({
    eyebrow: 'AIRSPACE PENALTY',
    title: '误入禁飞区',
    subtitle: '这次罚单比无人机先落地',
    dialogue: Object.freeze({
      anan: '嘶……这一下钱包有点疼。',
      cheng: '记住这片空域，下次绕着飞！'
    })
  }),
  'fine-entry': Object.freeze({
    eyebrow: 'AIRSPACE BREACH',
    title: '已进入禁飞区',
    subtitle: '正在计费，请立即离开',
    dialogue: Object.freeze({
      anan: '等等——已经闯进禁飞区了！',
      cheng: '马上撤离，罚款正在累计！'
    })
  })
})

export const MISSION_SETTLEMENT_DIALOGUE = Object.freeze({
  cheng: '任务拿下，背阔肌也得有镜头！',
  anan: '收工！这才叫满分造型！'
})

function finiteNumber(value) {
  const numeric = Number(value)
  return Number.isFinite(numeric) ? numeric : 0
}

export function formatMissionMoney(minor) {
  return new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency: 'CNY',
    maximumFractionDigits: 0
  }).format(finiteNumber(minor) / 100)
}

export function transactionToMissionReaction(transaction = {}) {
  const entryType = String(transaction.entryType || '').toUpperCase()
  if (!['DIAMOND_REWARD', 'AIRSPACE_FINE'].includes(entryType)) return null
  const key = String(transaction.id || transaction.entryKey || '')
  if (!key) return null
  const amountMinor = finiteNumber(transaction.amountMinor)
  if (entryType === 'DIAMOND_REWARD') {
    return {
      id: key,
      kind: 'diamond',
      entryType,
      runId: String(transaction.runId || ''),
      amountMinor: Math.max(0, amountMinor)
    }
  }
  const chargedMinor = Math.abs(amountMinor)
  const assessedMinor = transaction.assessedAmountMinor == null
    ? chargedMinor
    : Math.abs(finiteNumber(transaction.assessedAmountMinor))
  return {
    id: key,
    kind: 'fine',
    entryType,
    runId: String(transaction.runId || ''),
    amountMinor: chargedMinor,
    assessedAmountMinor: assessedMinor
  }
}

export function incursionToMissionReaction(incursion = {}, runId = '') {
  const incursionId = String(incursion.id || '')
  const normalizedRunId = String(runId || '')
  if (!incursionId || !normalizedRunId) return null
  return {
    id: `AIRSPACE_ENTRY:${incursionId}`,
    kind: 'fine-entry',
    entryType: 'AIRSPACE_ENTRY',
    runId: normalizedRunId,
    volumeId: String(incursion.volumeId || ''),
    estimatedFineMinor: Math.abs(finiteNumber(incursion.estimatedFineMinor))
  }
}

export function createMissionReactionState() {
  return {
    seenKeys: [],
    queue: [],
    active: null,
    pendingSettlement: null,
    settlement: null,
    displayedSettlementRunIds: []
  }
}

export function enqueueMissionReaction(state, transaction) {
  const reaction = transactionToMissionReaction(transaction)
  if (!reaction || state.seenKeys.includes(reaction.id)) return state
  return {
    ...state,
    seenKeys: [...state.seenKeys, reaction.id],
    queue: [...state.queue, reaction]
  }
}

export function enqueueAirspaceEntryReaction(state, incursion, runId) {
  const reaction = incursionToMissionReaction(incursion, runId)
  if (!reaction || state.seenKeys.includes(reaction.id)) return state
  return {
    ...state,
    seenKeys: [...state.seenKeys, reaction.id],
    queue: [...state.queue, reaction]
  }
}

export function startNextMissionReaction(state) {
  if (state.active || state.settlement || !state.queue.length) return state
  return {
    ...state,
    active: state.queue[0],
    queue: state.queue.slice(1)
  }
}

export function finishActiveMissionReaction(state) {
  if (!state.active) return state
  return { ...state, active: null }
}

export function buildMissionSettlement(runId, economy = {}) {
  const normalizedRunId = String(runId || '')
  if (!normalizedRunId) return null
  return {
    runId: normalizedRunId,
    totalRewardMinor: Math.max(0, finiteNumber(economy.collectedRewardMinor)),
    groundCargoRewardMinor: Math.max(0, finiteNumber(economy.groundCargoRewardMinor)),
    timelinessRewardMinor: Math.max(0, finiteNumber(economy.timelinessRewardMinor)),
    airCoinRewardMinor: Math.max(0, finiteNumber(economy.airCoinRewardMinor)),
    diamondRewardMinor: Math.max(0, finiteNumber(economy.collectedDiamondMinor)),
    penaltyMinor: Math.max(0, finiteNumber(economy.penaltyChargedMinor)),
    netMinor: finiteNumber(economy.netMinor)
  }
}

export function requestMissionSettlement(state, settlement) {
  if (!settlement?.runId || state.displayedSettlementRunIds.includes(settlement.runId)) return state
  return {
    ...state,
    pendingSettlement: settlement,
    displayedSettlementRunIds: [...state.displayedSettlementRunIds, settlement.runId]
  }
}

export function revealPendingMissionSettlement(state) {
  if (state.active || state.queue.length || state.settlement || !state.pendingSettlement) return state
  return {
    ...state,
    settlement: state.pendingSettlement,
    pendingSettlement: null
  }
}

export function dismissMissionSettlement(state) {
  if (!state.settlement) return state
  return { ...state, settlement: null }
}

export function isLiveMissionCompletion(previous, current, { replay = false } = {}) {
  if (replay || !current?.id || !current?.taskInstanceId) return false
  return String(previous?.id || '') === String(current.id)
    && String(previous?.status || '').toUpperCase() === 'RUNNING'
    && String(current.status || '').toUpperCase() === 'COMPLETED'
}
