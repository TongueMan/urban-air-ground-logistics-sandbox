# Demo 1 架构

```text
浏览器（签名 HttpOnly Cookie）
  ├─ 百度 MapV Three：百度矢量底图、建筑与 BD-09 路线
  ├─ REST / SSE ───────────────┐
  └─ WHEP / MP4 fallback ──┐   │
                            │   │
前端 Nginx（唯一 HTTP 入口）│   │
  ├─ MediaMTX ← FFmpeg × 3  │   │
  └─ Spring Boot ───────────┘   │
       ├─ 会话队列与 180 秒阶段状态机
       ├─ MQTT 发布/消费四设备遥测
       └─ MySQL / Flyway 持久化
```

## 隔离边界

- Cookie 中只保存随机访客标识及 HMAC 签名，不暴露数据库标识。
- 一个访客至多有一个非终态任务；所有任务 API 都执行所有权校验。
- MQTT Topic 为 `urban-air-ground/sessions/{sessionId}/{deviceType}/{deviceId}/telemetry`。
- 内存轨迹、SSE、数据库遥测和事件均以 `sessionId` 分区。
- 视频是只读公共素材，三路推流由所有会话共享。

## 容量策略

- 默认 12 个运行任务、100 个排队任务。
- 无 SSE 且长时间未访问的任务自动标记 `EXPIRED` 并释放名额。
- 终态历史保留 24 小时；回放读取已归档的实际轨迹，不启动新仿真。
- `docker-compose.prod.yml` 将六类服务约束在 1.8 CPU、约 2.2 GiB 容器内存上限内，为 2 核 4GB 主机预留系统空间。
