export const TUTORIAL_VERSION = 3
export const FLEET_TUTORIAL_VERSION = 1
export const TUTORIAL_ATTENTION_VERSION = 1
export const TUTORIAL_CHAPTER = 'prologue'
export const TUTORIAL_STORAGE_KEY = 'skyfleet.tutorial.prologue.v3'
export const TUTORIAL_ATTENTION_STORAGE_KEY = 'skyfleet.tutorial.manual-attention.v1'
export const FLEET_TUTORIAL_CHAPTER = 'fleet-center'
export const FLEET_TUTORIAL_STORAGE_KEY = 'skyfleet.tutorial.fleet-center.v1'
export const PROLOGUE_TUTORIAL_SEED = '1204'
export const GROUND_COOP_TUTORIAL_CHAPTER = 'TUTORIAL-02-GROUND-COOP'
export const GROUND_COOP_TUTORIAL_VERSION = '1.0'
export const GROUND_COOP_TUTORIAL_STORAGE_KEY = 'skyfleet.tutorial.ground-coop.v1'
export const GROUND_COOP_TUTORIAL_SEED = '2026091202'

export const PROLOGUE_STEPS = Object.freeze([
  { id: 'D01', mode: 'dialogue', speaker: 'anan', expression: 'greeting', text: '欢迎来到合肥城市空地协同物流运营中心。城市订单持续涌入，地面道路与低空航线必须协同，配送才能准时完成。', highlights: [{ text: '城市空地协同物流运营中心', tone: 'cyan' }, { text: '地面道路与低空航线', tone: 'mint' }], advance: 'dialogue' },
  { id: 'D02', mode: 'dialogue', speaker: 'cheng', expression: 'default', text: '从现在起，你是这里的新任运营调度负责人。你不直接驾驶设备，而是通过这套平台组织订单、车辆、无人机与数字空域。', highlights: [{ text: '新任运营调度负责人', tone: 'amber' }, { text: '订单、车辆、无人机与数字空域', tone: 'cyan' }], advance: 'dialogue' },
  { id: 'D03', mode: 'dialogue', speaker: 'anan', expression: 'default', text: '眼前的城市地图就是你的指挥台：路线反映地面通行，彩色空域标出低空限制，任务编组显示正在执行配送的设备。', highlights: [{ text: '城市地图就是你的指挥台', tone: 'mint' }, { text: '路线、空域和任务编组', tone: 'cyan' }], advance: 'dialogue' },
  { id: 'D04', mode: 'dialogue', speaker: 'cheng', expression: 'confident', text: '我是程昱，负责风险与数据分析；阿南负责一线引导。你做调度决策，我们帮你看清每个选择的后果。', highlights: [{ text: '风险与数据分析', tone: 'cyan' }, { text: '调度决策', tone: 'amber' }, { text: '选择的后果', tone: 'mint' }], advance: 'dialogue' },
  { id: 'D05', mode: 'dialogue', speaker: 'anan', expression: 'greeting', text: '初始车队已经准备好：满电的城市货运三轮车和配送无人机均已出站，可以直接投入你的第一项任务。', highlights: [{ text: '满电', tone: 'mint' }, { text: '均已出站', tone: 'cyan' }, { text: '第一项任务', tone: 'amber' }], advance: 'dialogue' },
  { id: 'D06', mode: 'dialogue', speaker: 'cheng', expression: 'analysis', text: '你的目标是在时效、电量、资金与空域安全之间做出选择。先完成一单教学配送，熟悉平台如何规划、运行和纠错。', highlights: [{ text: '时效、电量、资金与空域安全', tone: 'cyan' }, { text: '规划、运行和纠错', tone: 'mint' }], advance: 'dialogue' },
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
  { id: 'D12', mode: 'dialogue', speaker: 'cheng', expression: 'analysis', text: '前方是红色绝对禁飞区。第一次训练会暂时锁定提前处置，让无人机保持原航线；先亲眼看看侵入警告和罚款。', highlights: [{ text: '红色绝对禁飞区', tone: 'amber' }, { text: '锁定提前处置', tone: 'cyan' }, { text: '侵入警告和罚款', tone: 'mint' }], advance: 'dialogue' },
  { id: 'WAIT-RED-VIOLATION', mode: 'system', speaker: 'anan', expression: 'guide', text: '视角已切到无人机正后方。请观察它沿原航线接近红色禁飞区；你仍可缩放或返回总览。红区处置入口会保持隐藏，系统触发真实警告与罚款后将自动暂停。', highlights: [{ text: '无人机正后方', tone: 'cyan' }, { text: '沿原航线', tone: 'cyan' }, { text: '缩放或返回总览', tone: 'amber' }, { text: '真实警告与罚款', tone: 'mint' }], advance: 'condition', completionCondition: 'red-fine-and-checkpoint-ready' },
  { id: 'D13-REWIND', mode: 'dialogue', speaker: 'cheng', expression: 'analysis', text: '刚才的禁飞警告和罚款就是错误分支。看下方回溯条：黄色关键节点保存了进入红区前的任务、设备电量和资金状态。', highlights: [{ text: '错误分支', tone: 'amber' }, { text: '黄色关键节点', tone: 'cyan' }, { text: '进入红区前', tone: 'mint' }], targetId: 'mission-timeline', spotlight: true, advance: 'dialogue' },
  { id: 'A07-CHECKPOINT', mode: 'action', speaker: 'anan', expression: 'guide', text: '点击回溯条上的黄色关键节点，先预览当时的任务状态。', highlights: [{ text: '黄色关键节点', tone: 'amber' }, { text: '预览当时的任务状态', tone: 'cyan' }], targetId: 'mission-rewind-checkpoint', spotlight: true, advance: 'condition', completionCondition: 'red-checkpoint-previewed' },
  { id: 'A08-RESTORE', mode: 'action', speaker: 'anan', expression: 'guide', text: '确认时间点无误后，点击“回到这里重新选择”。节点之后的罚款、轨迹和设备消耗会被撤销。', highlights: [{ text: '回到这里重新选择', tone: 'mint' }, { text: '罚款、轨迹和设备消耗', tone: 'amber' }, { text: '被撤销', tone: 'cyan' }], targetId: 'mission-rewind-restore', spotlight: true, advance: 'condition', completionCondition: 'red-checkpoint-restored' },
  { id: 'D14-RETRY', mode: 'dialogue', speaker: 'cheng', expression: 'confident', text: '回溯完成。我们已经回到警告前，刚才的错误分支不再计入当前时间线。接下来地图会重新给出“空域冲突”卡片，它是查看风险与处置方案的入口。', highlights: [{ text: '回到警告前', tone: 'mint' }, { text: '错误分支不再计入', tone: 'cyan' }, { text: '空域冲突', tone: 'amber' }, { text: '查看风险与处置方案的入口', tone: 'cyan' }], advance: 'dialogue' },
  { id: 'A09-CONFLICT', mode: 'action', speaker: 'anan', expression: 'guide', text: '点击正在呼吸闪动的“空域冲突”卡片，打开右侧禁飞区详情。', highlights: [{ text: '空域冲突', tone: 'amber' }, { text: '右侧禁飞区详情', tone: 'cyan' }], targetId: 'red-airspace-conflict', spotlight: true, advance: 'condition', completionCondition: 'red-airspace-inspected' },
  { id: 'D14-INSPECT', mode: 'dialogue', speaker: 'cheng', expression: 'analysis', text: '右侧详情会说明空域类型、生效状态、侵入罚款、关联粉钻与可用处置方案。以后遇到冲突，先点卡片看清规则，再决定航线。', highlights: [{ text: '空域类型、生效状态、侵入罚款', tone: 'cyan' }, { text: '关联粉钻', tone: 'amber' }, { text: '先点卡片看清规则', tone: 'mint' }], targetId: 'airspace-inspector', spotlight: true, advance: 'dialogue' },
  { id: 'A09-DETOUR', mode: 'action', speaker: 'anan', expression: 'guide', text: '这一次选择“从侧面绕飞”。系统会重算可执行航线，并恢复回溯前的仿真速度。', highlights: [{ text: '从侧面绕飞', tone: 'mint' }, { text: '重算可执行航线', tone: 'cyan' }, { text: '恢复', tone: 'amber' }], targetId: 'airspace-detour', spotlight: true, advance: 'condition', completionCondition: 'red-detour-applied' },
  { id: 'D15-DETOUR', mode: 'dialogue', speaker: 'anan', expression: 'greeting', text: '绕飞航线已经切换，仿真也恢复了。粉钻不是点按钮领取的，让无人机沿新路线真正飞过去。', highlights: [{ text: '绕飞航线已经切换', tone: 'mint' }, { text: '真正飞过去', tone: 'cyan' }], advance: 'dialogue' },
  { id: 'WAIT-DIAMOND', mode: 'system', speaker: 'anan', expression: 'guide', text: '无人机正在沿绕飞路线接近粉钻。你可以继续观察地图，也可以从任务控制调节仿真速度。', highlights: [{ text: '沿绕飞路线接近粉钻', tone: 'mint' }, { text: '任务控制', tone: 'cyan' }], advance: 'condition', completionCondition: 'red-diamond-collected' },
  { id: 'D16-REWARD', mode: 'dialogue', speaker: 'anan', expression: 'greeting', text: '粉钻到手！每颗固定价值 ¥4,800，通常比单个普通配送奖励更值钱。', highlights: [{ text: '粉钻到手', tone: 'mint' }, { text: '¥4,800', tone: 'amber' }, { text: '更值钱', tone: 'cyan' }], advance: 'dialogue' },
  { id: 'D17-BATTERY', mode: 'dialogue', speaker: 'anan', expression: 'default', text: '右侧卡片同时显示地面和空中设备电量。电量不足时，等任务结束后去车队中心召回设备，它会在车库自动充电。', highlights: [{ text: '地面和空中设备电量', tone: 'cyan' }, { text: '车队中心召回设备', tone: 'amber' }, { text: '自动充电', tone: 'mint' }], targetId: 'mission-battery', spotlight: true, advance: 'dialogue' },
  { id: 'D18-COMPLETE', mode: 'dialogue', speaker: 'cheng', expression: 'confident', text: '生成、启动、错误体验、关键节点回溯、空域处置和奖励领取都已完成。序章结束，当前配送任务会继续运行。', highlights: [{ text: '关键节点回溯', tone: 'amber' }, { text: '空域处置和奖励领取', tone: 'cyan' }, { text: '当前配送任务会继续运行', tone: 'mint' }], advance: 'dialogue' }
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
  { id: 'F01-D08', mode: 'dialogue', speaker: 'anan', expression: 'default', text: '拥有的设备可以出站或召回。地面与空中设备各限一台出站；切换同类设备时，原设备会自动召回。', highlights: [{ text: '出站或召回', tone: 'cyan' }, { text: '各限一台出站', tone: 'amber' }, { text: '自动召回', tone: 'mint' }], targetId: 'fleet-instances', spotlight: true, advance: 'dialogue' },
  { id: 'F01-D09', mode: 'dialogue', speaker: 'cheng', expression: 'analysis', text: '只有车库中的设备能够出售；已出站设备必须先召回。出售也要确认，款项按目录原价退回。', highlights: [{ text: '只有车库中的设备', tone: 'mint' }, { text: '必须先召回', tone: 'amber' }, { text: '按目录原价退回', tone: 'cyan' }], targetId: 'fleet-instances', spotlight: true, advance: 'dialogue' },
  { id: 'F01-D10', mode: 'dialogue', speaker: 'anan', expression: 'greeting', text: '记住就好：先看需求，再看资金，最后决定谁出站。现在不用买，也不用卖。', highlights: [{ text: '需求', tone: 'cyan' }, { text: '资金', tone: 'amber' }, { text: '不用买，也不用卖', tone: 'mint' }], targetId: 'fleet-deployment', spotlight: true, advance: 'dialogue' },
  { id: 'F01-D11', mode: 'dialogue', speaker: 'cheng', expression: 'confident', text: '车队中心的基本控制已经讲完。界面会继续留着，你可以自由查看车型和资产。', highlights: [{ text: '自由查看', tone: 'mint' }], advance: 'dialogue' }
])

export const GROUND_COOP_STEPS = Object.freeze([
  { id: '02-D01', mode: 'dialogue', speaker: 'anan', expression: 'greeting', text: '欢迎参加联合配送资格认证。今天要学的不是沿线行驶，而是在计划变化后重新组织车与无人机。', highlights: [{ text: '联合配送资格认证', tone: 'mint' }, { text: '重新组织车与无人机', tone: 'cyan' }], advance: 'dialogue' },
  { id: '02-D02', mode: 'dialogue', speaker: 'cheng', expression: 'analysis', text: '本次使用固定教学设备，不计收益或损失。考核基线选择、临时绕行、返回基线和空地会合。', highlights: [{ text: '不计收益或损失', tone: 'amber' }, { text: '基线选择、临时绕行、返回基线和空地会合', tone: 'cyan' }], advance: 'dialogue' },
  { id: '02-A01', mode: 'action', speaker: 'anan', expression: 'guide', text: '点击“生成认证路线”，载入本次固定种子的教学任务。', highlights: [{ text: '生成认证路线', tone: 'mint' }, { text: '固定种子', tone: 'cyan' }], targetId: 'generate-mission', spotlight: true, advance: 'condition', completionCondition: 'ground-task-generated' },
  { id: '02-D03', mode: 'dialogue', speaker: 'cheng', expression: 'analysis', text: 'A、B、C 是三条不同的执行基线。距离最短，不等于所有临时目标都顺路；先选一条，运行中再判断偏离成本。', highlights: [{ text: 'A、B、C', tone: 'cyan' }, { text: '距离最短，不等于所有临时目标都顺路', tone: 'amber' }], targetId: 'mission-route-candidates', spotlight: true, advance: 'dialogue' },
  { id: '02-A02', mode: 'action', speaker: 'anan', expression: 'guide', text: '从 A、B、C 中自由选择任意一条执行基线。', highlights: [{ text: '任意一条执行基线', tone: 'mint' }], targetId: 'mission-route-candidates', spotlight: true, advance: 'condition', completionCondition: 'ground-baseline-selected' },
  { id: '02-A03', mode: 'action', speaker: 'anan', expression: 'guide', text: '点击“开始配送”。任务启动后会立即暂停，给你留出判断临时目标的时间。', highlights: [{ text: '开始配送', tone: 'mint' }, { text: '立即暂停', tone: 'amber' }], targetId: 'start-mission', spotlight: true, advance: 'condition', completionCondition: 'ground-task-started-and-paused' },
  { id: '02-W00-PACE', mode: 'system', speaker: 'cheng', expression: 'analysis', text: '恢复 5×，等待合同监管车结束倒计时。它真正出发时，系统会立即暂停并切换到监管车视角。', highlights: [{ text: '等待合同监管车结束倒计时', tone: 'amber' }, { text: '监管车视角', tone: 'cyan' }], advance: 'condition', completionCondition: 'ground-pace-departed' },
  { id: '02-D03-PACE', mode: 'dialogue', speaker: 'cheng', expression: 'analysis', text: '注意，合同监管车已经出发。它会沿选定基线驶向同一终点，不参与配送，只作为本次任务的时限标尺。', highlights: [{ text: '合同监管车已经出发', tone: 'amber' }, { text: '时限标尺', tone: 'mint' }], targetId: 'mission-pace-vehicle', spotlight: true, advance: 'dialogue' },
  { id: '02-D03-DEADLINE', mode: 'dialogue', speaker: 'anan', expression: 'guide', text: '你的车辆需要先完成配送并抵达终点。若让监管车抢先到达，正式任务中会影响任务评价，并可能触发惩罚。这次认证不实际结算，但规则照常演示。', highlights: [{ text: '先完成配送并抵达终点', tone: 'mint' }, { text: '监管车抢先到达', tone: 'amber' }, { text: '认证不实际结算', tone: 'cyan' }], targetId: 'mission-pace-vehicle', spotlight: true, advance: 'dialogue' },
  { id: '02-D04', mode: 'dialogue', speaker: 'anan', expression: 'default', text: '地图上有几处地面奖励。找一个不在当前基线上的目标，让车辆真正改一次道。', highlights: [{ text: '不在当前基线上的目标', tone: 'amber' }, { text: '真正改一次道', tone: 'mint' }], targetId: 'mission-map-interaction', spotlight: true, advance: 'dialogue' },
  { id: '02-A04', mode: 'action', speaker: 'anan', expression: 'guide', text: '在高亮地图区域选择一处地面奖励。系统不会标出答案；若它就在当前基线上，可以继续重试。', highlights: [{ text: '地面奖励', tone: 'cyan' }, { text: '不会标出答案', tone: 'amber' }, { text: '继续重试', tone: 'mint' }], targetId: 'mission-map-interaction', spotlight: true, allowSelector: '[data-tutorial-ground-reward]', advance: 'condition', completionCondition: 'ground-route-outside-reward' },
  { id: '02-D05', mode: 'dialogue', speaker: 'cheng', expression: 'confident', text: '调度命令已接受。基线不是枷锁，它的意义是让你在偏离之后仍知道该回到哪里。', highlights: [{ text: '基线不是枷锁', tone: 'cyan' }, { text: '回到哪里', tone: 'mint' }], advance: 'dialogue' },
  { id: '02-A05', mode: 'action', speaker: 'anan', expression: 'guide', text: '点击“返回计划路线”，让车辆结束临时绕行并重新追踪基线。', highlights: [{ text: '返回计划路线', tone: 'mint' }, { text: '重新追踪基线', tone: 'cyan' }], targetId: 'return-ground-baseline', spotlight: true, advance: 'condition', completionCondition: 'ground-returned-to-baseline' },
  { id: '02-W01', mode: 'system', speaker: 'anan', expression: 'guide', text: '已切换为 5×。观察车辆与无人机编组，检测到无人机离舱起飞后会自动暂停。', highlights: [{ text: '5×', tone: 'cyan' }, { text: '离舱起飞', tone: 'mint' }], advance: 'condition', completionCondition: 'ground-uav-takeoff' },
  { id: '02-D06-TAKEOFF', mode: 'dialogue', speaker: 'anan', expression: 'greeting', text: '看，无人机已经从车辆离舱。它会独立完成空中配送，但之后仍要回到移动中的车辆。', highlights: [{ text: '从车辆离舱', tone: 'mint' }, { text: '回到移动中的车辆', tone: 'cyan' }], advance: 'dialogue' },
  { id: '02-W02', mode: 'system', speaker: 'cheng', expression: 'analysis', text: '恢复 5×，继续观察空地会合。无人机被车辆回收时会再次暂停。', highlights: [{ text: '空地会合', tone: 'cyan' }, { text: '再次暂停', tone: 'amber' }], advance: 'condition', completionCondition: 'ground-uav-recovered' },
  { id: '02-D06-RECOVERY', mode: 'dialogue', speaker: 'cheng', expression: 'confident', text: '回收完成。刚才的临时绕行没有破坏会合约束，车辆和无人机仍在正确的时间与位置重新编组。', highlights: [{ text: '没有破坏会合约束', tone: 'mint' }, { text: '重新编组', tone: 'cyan' }], advance: 'dialogue' },
  { id: '02-W03', mode: 'system', speaker: 'anan', expression: 'guide', text: '恢复 5×并完成剩余配送。系统正在同步七项认证证据。', highlights: [{ text: '完成剩余配送', tone: 'mint' }, { text: '七项认证证据', tone: 'cyan' }], advance: 'condition', completionCondition: 'ground-mission-completed' },
  { id: '02-D06', mode: 'dialogue', speaker: 'anan', expression: 'greeting', text: '认证通过！你已经在监管时限内完成基线选择、临时绕行、返回计划路线和空地会合。', highlights: [{ text: '认证通过', tone: 'mint' }, { text: '监管时限内', tone: 'amber' }, { text: '空地会合', tone: 'cyan' }], advance: 'dialogue' },
  { id: '02-D07', mode: 'dialogue', speaker: 'cheng', expression: 'confident', text: '七项运行证据已经全部确认。进阶规划权限开放，之后你可以在普通任务中自由制定地面执行基线。', highlights: [{ text: '七项运行证据', tone: 'cyan' }, { text: '进阶规划权限开放', tone: 'mint' }], advance: 'dialogue' }
])

export const PLANNER_DEPENDENT_STEPS = new Set(['D07', 'D08', 'A02', 'A03', 'D09', 'A04'])
export const AIRSPACE_PANEL_DEPENDENT_STEPS = new Set(['D14-INSPECT', 'A09-DETOUR'])
export const RUN_DEPENDENT_STEPS = new Set(['D10', 'D11', 'D11-DEVICE', 'A04-FOLLOW', 'D11-FOLLOW', 'A04-OVERVIEW', 'D11-MOUSE', 'D12', 'WAIT-RED-VIOLATION', 'D13-REWIND', 'A07-CHECKPOINT', 'A08-RESTORE', 'D14-RETRY', 'A09-CONFLICT', 'D14-INSPECT', 'A09-DETOUR', 'D15-DETOUR', 'WAIT-DIAMOND', 'D16-REWARD', 'D17-BATTERY', 'D18-COMPLETE'])
export const FLEET_HUB_DEPENDENT_STEPS = new Set(FLEET_CENTER_STEPS.slice(3).map(step => step.id))
export const GROUND_COOP_PLANNER_STEPS = new Set(['02-A01', '02-D03', '02-A02', '02-A03'])
export const GROUND_COOP_RUN_STEPS = new Set(GROUND_COOP_STEPS.slice(6).map(step => step.id))

export const TUTORIAL_CHAPTERS = Object.freeze([
  Object.freeze({
    id: TUTORIAL_CHAPTER,
    index: '00',
    eyebrow: 'PROLOGUE',
    title: '新任调度员报到',
    description: '作为城市空地物流的新任运营调度负责人，和阿南、程昱完成第一局配送，认识平台并掌握地图、设备跟随、空域处置与关键节点回溯。',
    storageKey: TUTORIAL_STORAGE_KEY,
    version: TUTORIAL_VERSION,
    steps: PROLOGUE_STEPS,
    introKicker: 'MISSION MANUAL / PROLOGUE 00',
    introSubtitle: 'CITY AIR-GROUND LOGISTICS COMMAND',
    completeKicker: '调度员已就绪',
    completeTitle: '教程 00 完成',
    completeSubtitle: '关键节点回溯、红色空域绕飞与粉钻领取已完成'
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
    completeKicker: '车队权限已开放',
    completeTitle: '教程 01 完成',
    completeSubtitle: '车队中心已开放自由查看'
  }),
  Object.freeze({
    id: GROUND_COOP_TUTORIAL_CHAPTER,
    index: '02',
    eyebrow: 'CHAPTER',
    title: '联合配送资格认证',
    description: '在固定教学任务中选择执行基线，判断并执行临时绕行，返回基线后观察无人机离舱、会合与回收。',
    storageKey: GROUND_COOP_TUTORIAL_STORAGE_KEY,
    version: GROUND_COOP_TUTORIAL_VERSION,
    steps: GROUND_COOP_STEPS,
    introKicker: 'MISSION MANUAL / CHAPTER 02',
    introSubtitle: 'DYNAMIC ROUTING & RENDEZVOUS',
    completeKicker: '进阶规划已就绪',
    completeTitle: '教程 02 完成',
    completeSubtitle: '进阶规划已解锁 · 空地会合认证通过'
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
