# Urban Air-Ground Cooperative Inspection Platform

[English](README.md) | [简体中文](README.zh-CN.md)

> 智巡联翼｜城市车机协同巡检平台

An urban inspection platform for coordinated ground vehicles and UAVs,
featuring 3D geospatial visualization, real-time telemetry, MQTT messaging,
mission orchestration, and video monitoring.

The application presents two vehicle-UAV teams in a browser-based 3D scene.
Each visitor receives an isolated inspection mission with a complete lifecycle:
departure, UAV launch, cooperative scanning, return and telemetry replay.

## Highlights

- 3D urban scene and BD-09 inspection routes rendered with Baidu MapV Three.
- Four-device simulation coordinated by a Spring Boot mission state machine.
- MQTT telemetry, MySQL persistence and resumable SSE updates.
- Two UAV feeds and one vehicle feed with WHEP playback and MP4 fallback.
- Signed HttpOnly visitor sessions, queueing and per-session data isolation.
- Docker Compose deployment for local development and a 2-core/4-GB server.

## Architecture

```text
Browser / 3D Map / Video
          │ HTTP + SSE
          ▼
   Spring Boot Service ─── MQTT ─── Mosquitto
          │
          ├── MySQL
          └── MediaMTX / FFmpeg
```

More details are available in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Quick Start

Requirements: Docker Engine with Docker Compose and a Baidu Maps browser AK.

```powershell
Set-Location .\demo1
Copy-Item .env.example .env.local
# Set FRONTEND_BAIDU_MAP_AK in .env.local.
docker compose --env-file .env.local up -d --build
```

Open <http://127.0.0.1:8088>. See [`demo1/README.md`](demo1/README.md) for
configuration, verification and production deployment notes.

## Repository Layout

```text
demo1/frontend/    Vue 3, Vite and MapV Three client
demo1/backend/     Spring Boot mission and telemetry service
demo1/database/    Flyway schema and fixed route data
demo1/media/       Shared demonstration video sources
demo1/deploy/      Mosquitto, MediaMTX and media publisher configuration
docs/              Architecture, provenance and asset records
```

## Asset and Data Attribution

Map data and route capabilities are provided by Baidu Maps Platform. Models,
videos and fixed route data have separate provenance records in
[`docs/ASSET_SOURCES.md`](docs/ASSET_SOURCES.md). Assets without verified public
distribution terms are intentionally excluded from this repository.

## License

Source code is licensed under the [Apache License 2.0](LICENSE). Map data,
videos, models and other third-party assets are governed by their respective
terms and are not relicensed by the source-code license.
