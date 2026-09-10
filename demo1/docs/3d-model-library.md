# 3D 模型资产库

## 位置与接入方式

- 资产文件：`frontend/public/models/`
- 随包署名：`frontend/public/models/ATTRIBUTIONS.md`
- 统一注册表：`frontend/src/config/modelAssets.mjs`
- 完整性校验：`frontend/scripts/verify-model-library.mjs`
- 地图默认角色通过 `ACTIVE_MODEL_ROLES` 映射到资产 ID。
- 车队中心依据服务端目录按需加载具体车型，地图任务使用已经冻结的 `modelAssetId`。

## 当前资产

| 资产 ID | 分类 | 状态 | 大小 | 许可 | 当前用途 |
| --- | --- | --- | ---: | --- | --- |
| `tricycle` | 地面车辆 | active | 1.17 MiB | CC BY 4.0 | 初始短途配送车、默认地面 Actor |
| `ford-f350-utility` | 地面车辆 | active | 0.94 MiB | CC BY 4.0 | 多用途快速运输 |
| `ural-truck-vehicle-only` | 地面车辆 | active | 0.71 MiB | CC BY 4.0 | 复杂路况重载运输 |
| `cybertruck-fun-size` | 地面车辆 | active | 2.85 MiB | CC BY-NC 4.0 | 高性能电动运输平台 |
| `peterbilt-379-optimus-prime` | 地面车辆 | active | 3.44 MiB | CC BY 4.0 | 干线大宗运输 |
| `smart-city-drone` | 航空器 | active | 3.26 MiB | 待核验 | 初始轻型配送无人机、默认空中 Actor |
| `vtol-air-taxi` | 航空器 | active | 23.73 MiB | CC BY 4.0 | 重载高速空中运输，按需加载 |
| `gold-coin` | 奖励 | active | 0.05 MiB | CC BY 4.0 | 配送收益反馈 |
| `pink-diamond` | 奖励 | active | 0.01 MiB | CC BY 4.0 | 空域操作挑战奖励 |

作者、原始链接、SHA-256、节点、网格、材质、纹理与动画数量以模型注册表和 `ATTRIBUTIONS.md` 为准。任何 CC BY 或 CC BY-NC 资产在产品与交付物中都必须保留对应署名和许可证链接。

## 目录约定

```text
frontend/public/models/
├─ smart-city/
│  ├─ devices/                 # 轻型配送无人机
│  └─ traffic/                 # 物流车辆
└─ library/
   ├─ aircraft/                # VTOL 等航空器
   ├─ ground-vehicles/         # 地面运输车辆
   └─ rewards/                 # 金币、挑战奖励
```

新文件统一使用小写 kebab-case 名称。任务数据只保存稳定的资产 ID 和业务角色，不直接保存磁盘路径。

## 入库与上线门禁

1. 使用 GLB 2.0 单文件格式，并能被 Three.js `GLTFLoader` 读取。
2. 注册表填写稳定 ID、分类、生命周期、字节数、SHA-256、场景统计、作者、来源和许可证。
3. 执行 `npm run models:verify`，确认文件、哈希、GLB 头和场景统计一致。
4. 标定真实尺寸、前向轴、原点、贴地高度、材质表现与动画名称。
5. 浏览器交付建议单个模型不超过 10 MiB；超限模型必须按需加载并评估减面、WebP/KTX2 和 Meshopt/Draco。
6. 完成桌面端性能、显存、弱网、加载失败降级和多车型切换验证。
7. 未完成作者或许可核验的资产不得进入公开提交包。

## 已完成优化

### Peterbilt

- 文件体积：53.81 MB → 3.60 MB，减少约 93.3%。
- 三角面：757,638 → 186,949，减少约 75.3%。
- 21 张 1024 px PNG 调整为 512 px WebP。
- 几何启用 Meshopt 压缩并完成浏览器解码接入。

### Ural

- 删除地台、草木、岩石和树木等 15 个环境网格。
- 保留 20 个车辆网格、3 个材质与 30,326 个三角面。
- 文件体积：4.95 MB → 0.74 MB，减少约 85.0%。
- 重新设置根节点，使车辆水平居中并贴地。

### Cybertruck

- 删除碰撞展示附带的石块、碎玻璃、辅助灯位与无关动画。
- 保留车身、内饰、车灯与轮胎，共 20 个网格、120,662 个三角面。
- 重新居中贴地并启用 Meshopt 压缩。
- 文件体积：6.22 MB → 2.99 MB，减少约 51.9%。
