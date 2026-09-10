# 城市空地协同物流运营沙盘

[English](README.md) | [简体中文](README.zh-CN.md)

> 在真实三维城市地图上经营、调度并优化地面车辆与低空运输设备。

项目以合肥为首个运营区域。每位访客拥有相互隔离的公司账户、配送任务和车队资产，从有限启动资金与基础运力出发，通过选择车辆、规划空地路线、处置空域约束、完成交付、控制成本和持续投资，逐步构建更高效的城市物流网络。

## 当前可玩能力

- 基于百度 MapV Three 的三维城市空间与 BD-09 空地路线。
- 由 Seed 驱动的动态任务生成，以及冻结、可复现的任务制品。
- 地面与空中配送点，车型速度、续航、电量和运载倍率均参与权威结算。
- 绝对禁飞区、风险空域、高度走廊和临时禁飞区的互动处置。
- 公司余额、幂等账本、车辆采购、出售、出站、召回与充电。
- 基于 REST、SSE、MQTT 和 MySQL 的服务端权威仿真。
- 配送记录、真实遥测回放与任务检查点回退。
- 可选 DeepSeek 解释服务，用于说明服务端计算出的调度备选方案。
- 三路 WHEP 视频以及 MP4 自动降级播放。

## 系统架构

```text
Vue 3 / MapV Three / Three.js
          │ REST + SSE
          ▼
       Nginx 网关
          │
          ├── Spring Boot 权威仿真 ── MQTT ── Mosquitto
          │              │
          │              └── MySQL / Flyway
          └── MediaMTX / FFmpeg ── WHEP 或 MP4 降级
```

任务、移动、电量、空域结果、收益、罚款和资产所有权均由后端判定。浏览器只负责呈现状态与提交操作，不在本地直接修改经营结果。

服务与数据边界详见[架构说明](docs/ARCHITECTURE.md)。

## 快速开始

环境要求：Docker Engine、Docker Compose，以及可用的百度地图浏览器端 AK。

```powershell
Set-Location .\demo1
Copy-Item .env.example .env.local
# 在 .env.local 中填写 FRONTEND_BAIDU_MAP_AK
docker compose --env-file .env.local up -d --build
```

全部容器健康后访问 <http://127.0.0.1:8088>。服务端百度路线 AK 与 DeepSeek API Key 均为可选项；未配置时系统仍可使用确定性的保底路线与规则解释。

完整配置、本地开发、接口和验证方式见 [demo1/README.md](demo1/README.md)。

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 前端 | Vue 3、Vite、Three.js、百度 MapV Three |
| 后端 | Java 17、Spring Boot、Flyway |
| 实时通信 | SSE、MQTT |
| 数据存储 | MySQL 8.4 |
| 视频链路 | MediaMTX、WHEP、FFmpeg |
| 部署 | Docker Compose、Nginx |

## 仓库结构

```text
demo1/frontend/    Web 应用、三维任务空间、车队与配送界面
demo1/backend/     权威仿真、任务、车队和经济服务
demo1/database/    数据库维护说明
demo1/media/       共享演示视频源
demo1/deploy/      网关、MQTT 与媒体发布配置
demo1/docs/        产品总纲、架构决策与三维资产治理
docs/              仓库级架构和资源来源说明
```

## 文档入口

- [产品规划与建设基线](demo1/docs/strategy-sandbox/README.md)
- [实现与运行说明](demo1/README.md)
- [系统架构](docs/ARCHITECTURE.md)
- [三维模型资产库](demo1/docs/3d-model-library.md)
- [资源与数据来源](docs/ASSET_SOURCES.md)

## 范围与安全边界

本项目是可复现的物流运营沙盘，不是现实自动驾驶或无人机控制系统。真实设备命令 Transport 与 ACK 尚未接入；AI 只能解释后端已经计算出的方案，不能修改资格、排名、数值结果或直接控制设备。

真实百度与 DeepSeek 密钥只能写入不会被 Git 跟踪的 `demo1/.env.local`，不得提交到仓库。

## 许可证

源代码基于 [Apache License 2.0](LICENSE) 开源。地图数据、视频、模型与其他第三方资产继续遵循各自的许可证和服务条款。
