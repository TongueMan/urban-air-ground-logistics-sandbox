# Urban Air-Ground Cooperative Logistics Sandbox

> 城市空地协同物流运营沙盘 · 实现与运行说明

这是一个运行在合肥真实三维地理底座上的物流经营与调度沙盘。玩家拥有独立的公司账户和车队，通过生成任务、选择运力、执行地面与空中配送、处理交通及空域约束、结算收益并继续投资，形成可操作、可复现、可回放的经营闭环。

当前实现基线：2026-09-10。

## 核心体验

1. 新访客获得 ¥100,000 启动资金、一辆城市货运三轮车和一架轻型配送无人机。
2. 在车队中心采购、出售、出站或召回设备，地图任务始终绑定具体资产实例。
3. 选择合肥运营区域与 2—4 个互动空域主题，生成一份冻结的配送任务。
4. 执行地面和空中配送，处理信号灯、续航、禁飞区、风险区和高度走廊。
5. 收取配送收益、时效奖励和操作挑战奖励，并承担违规罚款。
6. 查看结算、遥测与配送记录，必要时从检查点回退任务。

## 已实现能力

| 领域 | 当前能力 |
| --- | --- |
| 三维城市 | 百度 MapV Three、Three.js 模型、BD-09 坐标、车辆/无人机跟随视角 |
| 动态任务 | Seed 驱动生成、服务端百度驾车路线、模板保底路线、冻结任务制品 |
| 车队经营 | 7 种地面/空中运力、采购、出售、出站、召回、现实时间充电 |
| 设备玩法 | 速度、满电航程、运载倍率、电量消耗、空中机动响应 |
| 空域规则 | 绝对禁飞、风险耗电、高度走廊、临时禁飞、绕飞与爬升 |
| 经济系统 | 公司余额、幂等账本、配送收益、时效奖励、挑战奖励、空域罚款 |
| 实时链路 | REST、SSE 增量事件、MQTT 遥测、断线恢复 |
| 复盘能力 | 配送记录、实际轨迹、检查点回退、确定性重现 |
| 智能解释 | DeepSeek Provider Adapter；未配置时使用确定性规则方案 |
| 视频 | 两路无人机与一路车辆视频，WHEP 播放、MP4 降级 |

## 权威规则

- 后端是任务、资产、移动、电量、奖励与处罚的唯一权威来源。
- 前端动画只呈现服务端状态，不直接完成订单或修改余额。
- 相同模板版本、生成器版本、Seed、参数和路线制品得到相同计划摘要。
- 任务开始前会冻结地面与空中设备、车型、属性版本和当前电量；资产状态变化后必须重新生成。
- 行驶或飞行距离按车型满电航程扣除电量，耗尽时设备在精确可达位置停止并使任务失败。
- 排队或运行任务绑定的设备不能召回或出售。
- 所有收入、罚款、采购和出售均写入带幂等键的公司账本。

## 系统架构

```text
浏览器
├─ Vue 3 操作界面
├─ 百度 MapV Three / Three.js 三维任务空间
├─ REST + SSE
└─ WHEP / MP4
        │
        ▼
Nginx（唯一业务 HTTP 入口）
├─ Spring Boot 权威仿真
│  ├─ 任务生成、路线校验与状态推进
│  ├─ 车队、经济、空域与交通规则
│  ├─ MQTT 遥测
│  └─ MySQL / Flyway
└─ MediaMTX ← FFmpeg 媒体发布器
```

完整边界见仓库级[架构说明](../docs/ARCHITECTURE.md)。

## 容器启动

### 环境要求

- Docker Engine 与 Docker Compose
- 可用的百度地图浏览器端 AK
- 建议至少 2 核 CPU、4 GB 内存

### 启动步骤

```powershell
Copy-Item .env.example .env.local
```

编辑不会提交到 Git 的 `.env.local`：

```dotenv
FRONTEND_BAIDU_MAP_AK=你的浏览器端百度地图AK
```

构建并启动全部服务：

```powershell
docker compose --env-file .env.local up -d --build
```

所有容器健康后访问 <http://127.0.0.1:8088>。这是唯一业务 HTTP 入口；WebRTC 另需放行 TCP/UDP 8189。

停止服务：

```powershell
docker compose --env-file .env.local down
```

该命令不会删除 MySQL 与 MQTT 命名卷。

## 环境变量

完整模板见 [`.env.example`](.env.example)。常用配置如下：

| 变量 | 必需 | 说明 |
| --- | --- | --- |
| `FRONTEND_BAIDU_MAP_AK` | 是 | 浏览器端地图 AK，Referer 白名单需覆盖访问地址 |
| `FRONTEND_STATIC_ASSET_BASE` | 否 | 人物图片、任务反馈图片与 GLB 的 OSS 基址；远程加载失败时回退到容器内副本 |
| `BAIDU_ROUTE_AK` | 否 | 服务端路线规划 AK；留空时固化模板保底路线 |
| `DEEPSEEK_API_KEY` | 否 | 仅服务端使用的方案解释密钥 |
| `DEMO_COOKIE_SECRET` | 生产必需 | 访客 Cookie HMAC 密钥，至少 32 个随机字符 |
| `MYSQL_PASSWORD` | 生产必需 | MySQL 业务用户密码 |
| `MYSQL_ROOT_PASSWORD` | 生产必需 | MySQL root 密码 |
| `MEDIA_WEBRTC_ADDITIONAL_HOSTS` | 公网部署必需 | MediaMTX 对外可达域名或 IP |
| `FLEET_DEV_PRICING_ENABLED` | 否 | 本地免费采购开关；生产覆盖配置默认关闭 |

密钥不得添加 `VITE_` 或 `FRONTEND_` 前缀，除明确设计为浏览器公开值的地图 AK 外，也不得进入前端变量、源码、数据库或日志。

## OSS 静态资源

人物图片、任务反馈图片和三维模型可由阿里云 OSS 提供，本地文件继续保留为自动回退。默认对象前缀为 `城市空地协同物流运营沙盘/`，远程目录约定如下：

- `characters/`：教程人物图片，对应本地 `frontend/public/tutorial/characters/`。
- `reactions/`：任务反馈图片，对应本地 `frontend/public/mission/reactions/`。
- `models/`：三维模型，对应本地 `frontend/public/models/`。

OSS 必须允许跨域 `GET` 和 `HEAD`；公开静态资源可暂时使用来源 `*`。演示视频不进入 OSS。

## 本地开发

后端依赖运行在容器中，Vite 在宿主机提供热更新：

```powershell
docker compose -f docker-compose.yml -f docker-compose.dev.yml --env-file .env.local up -d --build mysql mqtt mediamtx media-publisher backend
Set-Location frontend
npm ci
npm run dev
```

打开 <http://127.0.0.1:5173>。开发覆盖配置默认映射：

- 后端：`8095`
- MediaMTX HTTP：`8889`
- MySQL：`3306`
- MQTT：`1883`

结束开发依赖：

```powershell
docker compose -f docker-compose.yml -f docker-compose.dev.yml --env-file .env.local down
```

## 验证

```powershell
Set-Location frontend
npm test
npm run build
npm run models:verify

Set-Location ..\backend
mvn test

Set-Location ..
docker compose --env-file .env.local ps
```

生产覆盖配置：

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.local up -d --build
```

生产默认最多运行 12 个访客任务并允许 100 人排队。正式部署前必须更换数据库密码和 Cookie 密钥，并设置正确的百度 AK 白名单与 WebRTC 外部地址。

## 主要接口

| 方法与路径 | 用途 |
| --- | --- |
| `GET /api/demo/scenario-templates` | 获取合肥任务模板 |
| `POST /api/demo/task-instances` | 生成并冻结配送任务 |
| `POST /api/demo/task-instances/{taskId}/runs` | 开始一次任务运行 |
| `GET /api/demo/runs/{runId}/events` | 订阅 SSE 增量事件 |
| `GET /api/demo/runs/{runId}/replay` | 获取实际遥测回放 |
| `POST /api/demo/runs/{runId}/airspace-conflicts/{volumeId}/actions` | 提交空域处置动作 |
| `POST /api/demo/runs/{runId}/rewind-checkpoints/{checkpointId}/restore` | 从检查点恢复 |
| `GET /api/demo/fleet` | 获取公司、目录与车队快照 |
| `POST /api/demo/fleet/purchases` | 采购设备 |
| `POST /api/demo/fleet/sales` | 出售设备 |
| `PATCH /api/demo/fleet/assets/{assetId}/status` | 出站或召回设备 |

所有访客接口通过签名 HttpOnly Cookie 隔离公司与任务数据。

## 项目结构

```text
backend/                   Spring Boot 权威仿真与 Flyway 迁移
database/                  数据库维护说明
deploy/                    MQTT、MediaMTX 与媒体发布配置
docs/                      产品规划、ADR、三维资产说明
frontend/                  Vue 3、MapV Three 与 Three.js 前端
media/                     配送视角演示视频
docker-compose.yml         基础容器编排
docker-compose.dev.yml     本地开发端口覆盖
docker-compose.prod.yml    生产资源与安全默认值
```

## 当前边界

- 仅建设合肥试点区域。
- 建筑轮廓与高度碰撞当前标记为 `NOT_EVALUATED`。
- 真实设备命令 Transport 与 ACK 未接入，人工批准不会直接控制现实设备。
- DeepSeek 只解释服务端计算结果，不能修改方案数值、资格或排名。
- 未明确授权的第三方资产不得进入公开交付。

## 相关文档

- [产品规划入口](docs/strategy-sandbox/README.md)
- [长期建设指引](docs/strategy-sandbox/城市空地协同物流运营沙盘_长期建设指引_v2.0.md)
- [Fleet Hub ADR](docs/strategy-sandbox/ADR-001-fleet-hub-foundation.md)
- [3D 模型资产库](docs/3d-model-library.md)
- [资源与数据来源](../docs/ASSET_SOURCES.md)
