# 城市空地协同巡检平台

[English](README.md) | [简体中文](README.zh-CN.md)

> 智巡联翼｜城市车机协同巡检平台

这是一个面向城市巡检场景的巡检车—无人机协同平台，融合三维地理空间可视化、实时遥测、MQTT 消息通信、任务编排和视频监控能力。

平台在浏览器三维场景中展示两组“巡检车 + 无人机”编组。每位访客拥有相互隔离的巡检任务，可以体验编组出发、无人机放飞、协同扫描、返航会合、任务完成和历史轨迹回放的完整流程。

## 核心能力

- 使用百度 MapV Three 渲染城市三维场景和 BD-09 巡检路线。
- 通过 Spring Boot 任务状态机协调四台设备的运行仿真。
- 使用 MQTT 传输遥测数据，并通过 MySQL 完成任务、事件和轨迹持久化。
- 使用 SSE 向浏览器实时推送任务状态、设备遥测和异常事件。
- 提供两路无人机视频和一路车载视频，支持 WHEP 播放与 MP4 自动降级。
- 使用签名 HttpOnly Cookie 隔离访客任务，并提供并发控制和排队机制。
- 提供适用于本地环境和 2 核 4 GB Linux 服务器的 Docker Compose 配置。

## 系统架构

```text
浏览器 / 三维地图 / 视频监控
             │ HTTP + SSE
             ▼
      Spring Boot 服务 ─── MQTT ─── Mosquitto
             │
             ├── MySQL
             └── MediaMTX / FFmpeg
```

完整的数据流和运行服务说明请参阅 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

## 技术栈

| 模块 | 主要技术 |
| --- | --- |
| 前端 | Vue 3、Vite、Three.js、Baidu MapV Three |
| 后端 | Java 17、Spring Boot、Flyway |
| 实时通信 | MQTT、SSE |
| 数据存储 | MySQL 8.4 |
| 视频链路 | MediaMTX、WHEP、FFmpeg |
| 部署 | Docker、Docker Compose、Nginx |

## 快速开始

### 环境要求

- Docker Engine
- Docker Compose
- 可用的百度地图浏览器端 AK

### 启动步骤

在 PowerShell 中执行：

```powershell
Set-Location .\demo1
Copy-Item .env.example .env.local
```

编辑不会被 Git 跟踪的 `.env.local`，设置百度地图浏览器端 AK：

```dotenv
FRONTEND_BAIDU_MAP_AK=你的百度地图AK
```

构建并启动全部服务：

```powershell
docker compose --env-file .env.local up -d --build
```

等待容器健康后访问：<http://127.0.0.1:8088>

停止服务：

```powershell
docker compose --env-file .env.local down
```

更多配置、测试和生产部署说明请参阅 [`demo1/README.md`](demo1/README.md)。

## 项目目录

```text
demo1/
├─ frontend/     Vue 3、Vite 与 MapV Three 前端
├─ backend/      Spring Boot 任务与遥测服务
├─ database/     Flyway 表结构和固定路线数据
├─ media/        三路共享演示视频
├─ deploy/       Mosquitto、MediaMTX 和媒体发布配置
├─ docker-compose.yml
└─ docker-compose.prod.yml

docs/             架构、数据来源和资源校验记录
```

## 地图与资源说明

地图数据和路径能力由百度地图开放平台提供。浏览器端 AK 通过 `.env.local` 在运行时注入，不应写入源码、Docker 镜像或 Git 历史。

模型、视频和固定路线数据具有独立的来源记录，详见 [`docs/ASSET_SOURCES.md`](docs/ASSET_SOURCES.md)。未完成公开发布条款核验的资源不会提交到本仓库。

## 许可证

源代码基于 [Apache License 2.0](LICENSE) 开源。地图数据、视频、模型及其他第三方资源遵循各自的授权和服务条款，不因源代码许可证而被重新许可。
