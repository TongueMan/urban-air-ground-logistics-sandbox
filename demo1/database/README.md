# 数据库说明

数据库结构由 Spring Boot 启动时通过 Flyway 自动建立。迁移脚本的唯一维护位置是
`backend/src/main/resources/db/migration`，固定 BD-09 场景数据位于
`backend/src/main/resources/mission/patrol-mission.json`。

包含访客任务与排队、固定场景版本、逐设备遥测以及任务事件四类持久化数据。
