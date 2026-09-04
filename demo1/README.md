# Urban Air-Ground Cooperative Inspection Platform

> 智巡联翼｜城市车机协同巡检平台

“智巡联翼”是一个城市巡检车—无人机协同巡检应用。浏览器无需登录，访客以签名 HttpOnly Cookie 获得独立任务；完整链路为百度 MapV Three、Spring Boot 仿真调度、MQTT、MySQL、SSE 与三路 MediaMTX 视频。

## 本地启动

1. 将 `.env.example` 复制为不会提交的 `.env.local`，填写百度地图浏览器端 AK。AK 的 Referer 白名单需包含本地地址。
2. 在本目录运行：

   ```powershell
   docker compose --env-file .env.local up -d --build
   ```

3. 等待所有容器健康后打开 <http://127.0.0.1:8088>。这是唯一的业务 HTTP 入口；WebRTC 媒体链路另需放行 TCP/UDP 8189。
4. 停止服务：

   ```powershell
   docker compose --env-file .env.local down
   ```

## 验证

```powershell
cd frontend
npm test
npm run build

cd ../backend
mvn test

cd ..
docker compose --env-file .env.local ps
```

生产服务器使用基础配置和资源覆盖配置共同启动：

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml --env-file .env.local up -d --build
```

2 核 4GB 配置默认最多运行 12 个访客任务、排队 100 人。正式部署前必须更换数据库密码与 Cookie 签名密钥，并把 `MEDIA_WEBRTC_ADDITIONAL_HOSTS`、AK Referer 白名单配置为正式域名或公网地址。
