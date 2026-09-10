export const TUTORIAL_VERSION = 2
export const FLEET_TUTORIAL_VERSION = 1
export const TUTORIAL_ATTENTION_VERSION = 1
export const TUTORIAL_CHAPTER = 'prologue'
export const TUTORIAL_STORAGE_KEY = 'skyfleet.tutorial.prologue.v2'
export const TUTORIAL_ATTENTION_STORAGE_KEY = 'skyfleet.tutorial.manual-attention.v1'
export const FLEET_TUTORIAL_CHAPTER = 'fleet-center'
export const FLEET_TUTORIAL_STORAGE_KEY = 'skyfleet.tutorial.fleet-center.v1'
export const PROLOGUE_TUTORIAL_SEED = '1204'

export const PROLOGUE_STEPS = Object.freeze([
  { id: 'D01', mode: 'dialogue', speaker: 'anan', expression: 'greeting', text: '终于等到你了。今天这片物流运营区，就交给我们三个。', highlights: [{ text: '物流运营区', tone: 'cyan' }, { text: '我们三个', tone: 'mint' }], advance: 'dialogue' },
  { id: 'D02', mode: 'dialogue', speaker: 'cheng', expression: 'default', text: '纠正一下。真正听指令的是配送车和配送无人机。', highlights: [{ text: '配送车和配送无人机', tone: 'cyan' }], advance: 'dialogue' },
  { id: 'D03', mode: 'dialogue', speaker: 'anan', expression: 'awkward', text: '……第一分钟就拆台？', advance: 'dialogue' },
  { id: 'D04', mode: 'dialogue', speaker: 'cheng', expression: 'confident', text: '避免新负责人误把我算进设备清单。', highlights: [{ text: '设备清单', tone: 'amber' }], advance: 'dialogue' },
  { id: 'D05', mode: 'dialogue', speaker: 'anan', expression: 'default', text: '看地图。路线、空域和编组都在这里，先别急着背下每个读数。', highlights: [{ text: '路线、空域和编组', tone: 'cyan' }], advance: 'dialogue' },
  { id: 'D06', mode: 'dialogue', speaker: 'cheng', expression: 'analysis', text: '你的第一件事，是生成一份经过校验的配送方案。', highlights: [{ text: '经过校验的配送方案', tone: 'mint' }], advance: 'dialogue' },
  { id: 'A01', mode: 'action', speaker: 'anan', expression: 'guide', text: '右上角。打开配送任务规划器，我们从真实订单开始。', highlights: [{ text: '配送任务规划器', tone: 'cyan' }, { text: '真实订单', tone: 'mint' }], targetId: 'create-mission', spotlight: true, advance: 'target-click', completionCondition: 'planner-open' },
  { id: 'D07', mode: 'dialogue', speaker: 'anan', expression: 'default', text: '物流运营区已经替你选好，也可以换成另外两个校核区域。', highlights: [{ text: '物流运营区', tone: 'cyan' }, { text: '校核区域', tone: 'amber' }], targetId: 'mission-area', spotlight: true, advance: 'dialogue' },
  { id: 'D08', mode: 'dialogue', speaker: 'cheng', expression: 'analysis', text: '这次训练需要同时识别四种空域。把禁飞区数量选成 4，红、黄、紫、橙会一起出现。', highlights: [{ text: '禁飞区数量选成 4', tone: 'amber' }, { text: '红、黄、紫、橙', tone: 'cyan' }], targetId: 'mission-zone-count', spotlight: true, advance: 'dialogue' },
  { id: 'A02', mode: 'action', speaker: 'anan', expression: 'guide', text: '先把本局禁飞区数量改成 4 个。四种空域都认全，才算完成今天的训练。', highlights: [{ text: '4 个', tone: 'amber' }, { text: '四种空域', tone: 'cyan' }], targetId: 'mission-zone-count-four', spotlight: true, advance: 'condition', completionCondition: 'zone-count-four' },
  { id: 'A03', mode: 'action', speaker: 'anan', expression: 'guide', text: '现在生成教学任务。系统会校验车辆路线、无人机配送点、奖励和四种空域。', highlights: [{ text: '生成教学任务', tone: 'mint' }, { text: '奖励和四种空域', tone: 'cyan' }], targetId: 'generate-mission', spotlight: true, advance: 'condition', completionCondition: 'task-generated' },
  { id: 'D09', mode: 'dialogue', speaker: 'cheng', expression: 'analysis', text: '校验通过。地图已经收拢到本次配送范围，路线、配送点、粉钻和空域都在视野里。', highlights: [{ text: '校验通过', tone: 'mint' }, { text: '配送范围', tone: 'cyan' }, { text: '粉钻和空域', tone: 'amber' }], targetId: 'mission-preview', spotlight: true, advance: 'dialogue' },
  { id: 'A04', mode: 'action', speaker: 'anan', expression: 'guide', text: '摘要确认好了，就点击开始配送。接下来我们会进入真实运行中的任务。', highlights: [{ text: '开始配送', tone: 'mint' }, { text: '真实运行中的任务', tone: 'cyan' }], targetId: 'start-mission', spotlight: true, advance: 'condition', completionCondition: 'task-started-and-paused' },
  { id: 'D10', mode: 'dialogue', speaker: 'cheng', expression: 'analysis', text: '任务已经启动。为了不让你在阅读时错过空域处置，我先暂停了仿真；完成绕飞后会恢复。', highlights: [{ text: '暂停了仿真', tone: 'amber' }, { text: '完成绕飞后会恢复', tone: 'mint' }], advance: 'dialogue' },
  { id: 'D11', mode: 'dialogue', speaker: 'cheng', expression: 'confident', text: '任务控制可以暂停，或切换 0.5×、1×、2×、5× 节奏，也能中止当前任务。这次知道位置就够了。', highlights: [{ text: '0.5×、1×、2×、5×', tone: 'cyan' }, { text: '中止当前任务', tone: 'amber' }, { text: '知道位置就够了', tone: 'mint' }], targetId: 'mission-control', spotlight: true, advance: 'dialogue' },
  { id: 'D11-DEVICE', mode: 'dialogue', speaker: 'anan', expression: 'default', text: '地图模型和左下任务车队里的配送车、无人机都可以点击。选中后，镜头会跟着它移动，方便观察真实状态。', highlights: [{ text: '配送车、无人机', tone: 'cyan' }, { text: '镜头会跟着它移动', tone: 'mint' }], targetId: 'mission-device-list', spotlight: true, advance: 'dialogue' },
  { id: 'A04-FOLLOW', mode: 'action', speaker: 'anan', expression: 'guide', text: '点击高亮区域里的配送车或无人机，任选一台进入跟随视角；地图上的车辆和无人机模型也有相同功能。', highlights: [{ text: '配送车或无人机', tone: 'mint' }, { text: '跟随视角', tone: 'cyan' }, { text: '地图上的车辆和无人机模型', tone: 'amber' }], targetId: 'mission-device-list', spotlight: true, advance: 'condition', completionCondition: 'device-following' },
  { id: 'D11-FOLLOW', mode: 'dialogue', speaker: 'cheng', expression: 'analysis', text: '跟随视角里，滚动鼠标滚轮可以拉近或拉远观察距离。点击“返回总览”或按 Esc，会回到本次配送任务范围。', highlights: [{ text: '鼠标滚轮', tone: 'cyan' }, { text: '返回总览', tone: 'mint' }, { text: '本次配送任务范围', tone: 'amber' }], targetId: 'return-mission-overview', spotlight: true, advance: 'dialogue' },
  { id: 'A04-OVERVIEW', mode: 'action', speaker: 'anan', expression: 'guide', text: '现在点击“返回总览”，确认镜头重新收拢到本局路线、配送点和空域。', highlights: [{ text: '返回总览', tone: 'mint' }, { text: '本局路线、配送点和空域', tone: 'cyan' }], targetId: 'return-mission-overview', spotlight: true, advance: 'condition', completionCondition: 'mission-overview-restored' },
  { id: 'D11-MOUSE', mode: 'dialogue', speaker: 'cheng', expression: 'confident', text: '还有三项基础操作：按住鼠标左键拖动可平移地图，滚轮可缩放地图；按住滚轮拖动，则能调整地图朝向与俯视角度。', highlights: [{ text: '鼠标左键拖动', tone: 'cyan' }, { text: '滚轮可缩放', tone: 'mint' }, { text: '按住滚轮拖动', tone: 'amber' }], targetId: 'mission-map-interaction', spotlight: true, advance: 'dialogue' },
  { id: 'A05', mode: 'action', speaker: 'anan', expression: 'guide', text: '看地图上的红色“空域冲突”标记。点击它，打开绝对禁飞区的处置面板。', highlights: [{ text: '红色“空域冲突”', tone: 'amber' }, { text: '绝对禁飞区', tone: 'cyan' }], targetId: 'red-airspace-conflict', spotlight: true, advance: 'condition', completionCondition: 'red-airspace-selected' },
  { id: 'D12', mode: 'dialogue', speaker: 'cheng', expression: 'analysis', text: '红色代表绝对禁飞，闯入会立刻产生罚款。它绑定的粉钻只在安全绕飞航线上，选对处置并真实经过才能领取。', highlights: [{ text: '绝对禁飞', tone: 'amber' }, { text: '立刻产生罚款', tone: 'amber' }, { text: '安全绕飞航线', tone: 'cyan' }, { text: '真实经过', tone: 'mint' }], targetId: 'airspace-detour', spotlight: true, advance: 'dialogue' },
  { id: 'A06', mode: 'action', speaker: 'anan', expression: 'guide', text: '选择“从侧面绕飞”。系统会立即重算并切换无人机的可执行航线。', highlights: [{ text: '从侧面绕飞', tone: 'mint' }, { text: '可执行航线', tone: 'cyan' }], targetId: 'airspace-detour', spotlight: true, advance: 'condition', completionCondition: 'red-detour-applied' },
  { id: 'D13', mode: 'dialogue', speaker: 'anan', expression: 'greeting', text: '绕飞航线已经切换，仿真也恢复了。粉钻不是点按钮领取的，让无人机沿新路线真正飞过去。', highlights: [{ text: '绕飞航线已经切换', tone: 'mint' }, { text: '真正飞过去', tone: 'cyan' }], advance: 'dialogue' },
  { id: 'WAIT-DIAMOND', mode: 'system', speaker: 'anan', expression: 'guide', text: '无人机正在沿绕飞路线接近粉钻。你可以继续观察地图，也可以从任务控制调节仿真速度。', highlights: [{ text: '沿绕飞路线接近粉钻', tone: 'mint' }, { text: '任务控制', tone: 'cyan' }], advance: 'condition', completionCondition: 'red-diamond-collected' },
  { id: 'D14', mode: 'dialogue', speaker: 'anan', expression: 'greeting', text: '粉钻到手！每颗固定价值 ¥4,800，通常比单个普通配送奖励更值钱。', highlights: [{ text: '粉钻到手', tone: 'mint' }, { text: '¥4,800', tone: 'amber' }, { text: '更值钱', tone: 'cyan' }], advance: 'dialogue' },
  { id: 'D15', mode: 'dialogue', speaker: 'anan', expression: 'default', text: '右侧卡片同时显示地面和空中设备电量。电量不足时，等任务结束后去车队中心召回设备，它会在车库自动充电。', highlights: [{ text: '地面和空中设备电量', tone: 'cyan' }, { text: '车队中心召回设备', tone: 'amber' }, { text: '自动充电', tone: 'mint' }], targetId: 'mission-battery', spotlight: true, advance: 'dialogue' },
  { id: 'D16', mode: 'dialogue', speaker: 'cheng', expression: 'confident', text: '生成、启动、空域处置和奖励领取都已完成。序章结束，当前配送任务会继续运行。', highlights: [{ text: '空域处置和奖励领取', tone: 'cyan' }, { text: '当前配送任务会继续运行', tone: 'mint' }], advance: 'dialogue' }
])

export const FLEET_CENTER_STEPS = Object.freeze([
  { id: 'F01-D01', mode: 'dialogue', speaker: 'anan', expression: 'greeting', text: '欢迎来到第一章。接下来带你认识车队中心，以及公司现有的地面与空中运力。', highlights: [{ text: '车队中心', tone: 'mint' }, { text: '地面与空中运力', tone: 'cyan' }], advance: 'dialogue' },
  { id: 'F01-D02', mode: 'dialogue', speaker: 'cheng', expression: 'default', text: '这里展示的资金和设备都来自真实公司账户。采购、出售和出站状态在刷新后都会保留。', highlights: [{ text: '真实公司账户', tone: 'cyan' }, { text: '刷新后都会保留', tone: 'mint' }], advance: 'dialogue' },
  { id: 'F01-A01', mode: 'action', speaker: 'anan', expression: 'guide', text: '看左下角的任务车队。点击车队中心，我们进去认认路。', highlights: [{ text: '车队中心', tone: 'mint' }], targetId: 'fleet-hub-entry', spotlight: true, advance: 'target-click', completionCondition: 'fleet-hub-open' },
  { id: 'F01-D03', mode: 'dialogue', speaker: 'cheng', expression: 'analysis', text: '右上角是可用资金和公司资产总数。所有采购与出售都会立刻更新这里。', highlights: [{ text: '可用资金', tone: 'mint' }, { text: '公司资产总数', tone: 'cyan' }], targetId: 'fleet-balance', spotlight: true, advance: 'dialogue' },
  { id: 'F01-D04', mode: 'dialogue', speaker: 'anan', expression: 'default', text: '车队分为地面运输和空中运输。切换分类只是在看不同目录，不会改变任务。', highlights: [{ text: '地面运输', tone: 'cyan' }, { text: '空中运输', tone: 'cyan' }, { text: '不会改变任务', tone: 'mint' }], targetId: 'fleet-categories', spotlight: true, advance: 'dialogue' },
  { id: 'F01-D05', mode: 'dialogue', speaker: 'anan', expression: 'guide', text: '下方左侧是我的车队，右侧是尚未拥有的可采购车型。点车型卡片只会查看详情。', highlights: [{ text: '我的车队', tone: 'mint' }, { text: '可采购车型', tone: 'amber' }, { text: '只会查看详情', tone: 'cyan' }], targetId: 'fleet-catalog', spotlight: true, advance: 'dialogue' },
  { id: 'F01-D06', mode: 'dialogue', speaker: 'cheng', expression: 'analysis', text: '车型详情会展示模型、运载能力、速度、续航和目录价格，先比较用途，再考虑扩编。', highlights: [{ text: '运载能力、速度、续航', tone: 'cyan' }, { text: '目录价格', tone: 'amber' }], targetId: 'fleet-detail', spotlight: true, advance: 'dialogue' },
  { id: 'F01-D07', mode: 'dialogue', speaker: 'cheng', expression: 'confident', text: '采购会先要求确认，扣除公司资金后直接进入车库。这一章不要求你真的购买。', highlights: [{ text: '要求确认', tone: 'amber' }, { text: '直接进入车库', tone: 'mint' }, { text: '不要求你真的购买', tone: 'cyan' }], targetId: 'fleet-purchase', spotlight: true, advance: 'dialogue' },
  { id: 'F01-D08', mode: 'dialogue', speaker: 'anan', expression: 'default', text: '拥有的设备可以出站或召回。每类最后出站的一台，会成为当前地图任务使用的载具。', highlights: [{ text: '出站或召回', tone: 'cyan' }, { text: '最后出站的一台', tone: 'amber' }, { text: '当前地图任务', tone: 'mint' }], targetId: 'fleet-instances', spotlight: true, advance: 'dialogue' },
  { id: 'F01-D09', mode: 'dialogue', speaker: 'cheng', expression: 'analysis', text: '只有车库中的设备能够出售；已出站设备必须先召回。出售也要确认，款项按目录原价退回。', highlights: [{ text: '只有车库中的设备', tone: 'mint' }, { text: '必须先召回', tone: 'amber' }, { text: '按目录原价退回', tone: 'cyan' }], targetId: 'fleet-instances', spotlight: true, advance: 'dialogue' },
  { id: 'F01-D10', mode: 'dialogue', speaker: 'anan', expression: 'greeting', text: '记住就好：先看需求，再看资金，最后决定谁出站。现在不用买，也不用卖。', highlights: [{ text: '需求', tone: 'cyan' }, { text: '资金', tone: 'amber' }, { text: '不用买，也不用卖', tone: 'mint' }], targetId: 'fleet-deployment', spotlight: true, advance: 'dialogue' },
  { id: 'F01-D11', mode: 'dialogue', speaker: 'cheng', expression: 'confident', text: '车队中心的基本控制已经讲完。界面会继续留着，你可以自由查看车型和资产。', highlights: [{ text: '自由查看', tone: 'mint' }], advance: 'dialogue' }
])

export const PLANNER_DEPENDENT_STEPS = new Set(['D07', 'D08', 'A02', 'A03', 'D09', 'A04'])
export const RUN_DEPENDENT_STEPS = new Set(['D10', 'D11', 'D11-DEVICE', 'A04-FOLLOW', 'D11-FOLLOW', 'A04-OVERVIEW', 'D11-MOUSE', 'A05', 'D12', 'A06', 'D13', 'WAIT-DIAMOND', 'D14', 'D15', 'D16'])
export const FLEET_HUB_DEPENDENT_STEPS = new Set(FLEET_CENTER_STEPS.slice(3).map(step => step.id))

export const TUTORIAL_CHAPTERS = Object.freeze([
  Object.freeze({
    id: TUTORIAL_CHAPTER,
    index: '00',
    eyebrow: 'PROLOGUE',
    title: '新任调度员报到',
    description: '和阿南、程昱一起生成并启动第一局配送任务，掌握地图视角与设备跟随，完成红色空域绕飞并领取粉钻。',
    storageKey: TUTORIAL_STORAGE_KEY,
    version: TUTORIAL_VERSION,
    steps: PROLOGUE_STEPS,
    introKicker: 'MISSION MANUAL / PROLOGUE 00',
    introSubtitle: 'FIRST DELIVERY PLAN',
    completeKicker: 'OPERATOR READY',
    completeTitle: 'PROLOGUE COMPLETE',
    completeSubtitle: '红色空域绕飞与粉钻领取已完成'
  }),
  Object.freeze({
    id: FLEET_TUTORIAL_CHAPTER,
    index: '01',
    eyebrow: 'CHAPTER',
    title: '认识你的车队',
    description: '进入真实车队中心，认识资金、车型目录、采购、出站、召回与出售规则；本章不会替你执行买卖。',
    storageKey: FLEET_TUTORIAL_STORAGE_KEY,
    version: FLEET_TUTORIAL_VERSION,
    steps: FLEET_CENTER_STEPS,
    introKicker: 'MISSION MANUAL / CHAPTER 01',
    introSubtitle: 'FLEET CENTER ORIENTATION',
    completeKicker: 'FLEET ACCESS READY',
    completeTitle: 'CHAPTER 01 COMPLETE',
    completeSubtitle: '车队中心已开放自由查看'
  })
])

export function getTutorialChapter(chapterId = TUTORIAL_CHAPTER) {
  return TUTORIAL_CHAPTERS.find(chapter => chapter.id === chapterId) || TUTORIAL_CHAPTERS[0]
}

export function getTutorialStep(stepId, chapterId = TUTORIAL_CHAPTER) {
  const chapter = getTutorialChapter(chapterId)
  return chapter.steps.find(step => step.id === stepId) || chapter.steps[0]
}

export function getTutorialStepIndex(stepId, chapterId = TUTORIAL_CHAPTER) {
  const chapter = getTutorialChapter(chapterId)
  return Math.max(0, chapter.steps.findIndex(step => step.id === stepId))
}
