# Urban Air-Ground Cooperative Logistics Sandbox

[English](README.md) | [简体中文](README.zh-CN.md)

> A strategy sandbox for operating urban ground vehicles and delivery aircraft on a real 3D city map.

The project turns Hefei into an interactive logistics operating area. Each visitor starts with an isolated company account, a small fleet, and dynamically generated delivery missions. Players deploy vehicles, coordinate ground and air routes, respond to airspace constraints, collect revenue, control operating risk, and reinvest in fleet capacity.

## What is playable

- Baidu MapV Three scene with BD-09 ground and air routes.
- Seeded mission generation with frozen, replayable task artifacts.
- Ground and air delivery points, vehicle-specific speed, range, battery, and cargo multipliers.
- Interactive no-fly, risk, altitude-corridor, and temporary airspace rules.
- Company balance, an idempotent ledger, fleet purchasing, selling, deployment, recall, and charging.
- Server-authoritative simulation delivered through REST, SSE, MQTT, and MySQL.
- Mission records, telemetry replay, and checkpoint rewind.
- Optional DeepSeek explanations for server-computed dispatch alternatives.
- Three WHEP video feeds with MP4 fallback.

## Architecture

```text
Vue 3 / MapV Three / Three.js
          │ REST + SSE
          ▼
      Nginx gateway
          │
          ├── Spring Boot simulation ── MQTT ── Mosquitto
          │             │
          │             └── MySQL / Flyway
          └── MediaMTX / FFmpeg ── WHEP or MP4 fallback
```

The backend is the authority for missions, movement, battery use, airspace outcomes, rewards, penalties, and fleet ownership. The browser renders and controls that state but does not settle business results locally.

See [Architecture](docs/ARCHITECTURE.md) for the service and data boundaries.

## Quick start

Requirements: Docker Engine with Docker Compose and a Baidu Maps browser AK.

```powershell
Set-Location .\demo1
Copy-Item .env.example .env.local
# Set FRONTEND_BAIDU_MAP_AK in .env.local.
docker compose --env-file .env.local up -d --build
```

Open <http://127.0.0.1:8088> after all containers become healthy. A server-side Baidu route AK and a DeepSeek API key are optional; deterministic fallback behavior remains available when they are unset.

Detailed configuration, development, API, and verification instructions are in [demo1/README.md](demo1/README.md).

## Technology

| Area | Stack |
| --- | --- |
| Frontend | Vue 3, Vite, Three.js, Baidu MapV Three |
| Backend | Java 17, Spring Boot, Flyway |
| Realtime | SSE, MQTT |
| Storage | MySQL 8.4 |
| Media | MediaMTX, WHEP, FFmpeg |
| Delivery | Docker Compose, Nginx |

## Repository layout

```text
demo1/frontend/    Web application, 3D world, fleet and mission UI
demo1/backend/     Authoritative simulation, task, fleet and economy services
demo1/database/    Database maintenance notes
demo1/media/       Shared demonstration video sources
demo1/deploy/      Gateway, MQTT and media-publisher configuration
demo1/docs/        Product strategy, ADRs and 3D asset governance
docs/              Repository architecture and source records
```

## Documentation

- [Product strategy](demo1/docs/strategy-sandbox/README.md)
- [Implementation guide](demo1/README.md)
- [Architecture](docs/ARCHITECTURE.md)
- [3D model library](demo1/docs/3d-model-library.md)
- [Asset sources](docs/ASSET_SOURCES.md)

## Scope and safety boundaries

This is a deterministic logistics operations sandbox, not a live autonomous-vehicle control system. Real device command transport and acknowledgements are intentionally unavailable. AI output can explain backend-computed options but cannot change eligibility, ranking, numeric outcomes, or execute equipment commands.

Never commit real Baidu or DeepSeek credentials. Keep them in the untracked `demo1/.env.local` file.

## License

Source code is licensed under the [Apache License 2.0](LICENSE). Map data, videos, models, and other third-party assets remain subject to their own licenses and service terms.
