package com.skyfleet.logistics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class DemoSessionService {
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "STOPPED", "EXPIRED", "FAILED");
    private static final Set<Double> SPEEDS = Set.of(0.0, 0.5, 1.0, 2.0, 5.0);
    private static final String ECONOMY_RULE_VERSION = "delivery-economy/2.0.0";
    private static final long AIRSPACE_FINE_BASE_MINOR = 120_000;
    private static final long AIRSPACE_FINE_PER_SECOND_MINOR = 12_000;
    private static final long AIRSPACE_FINE_MAX_MINOR = 480_000;
    private static final long RED_FIXED_FINE_MINOR = 800_000;
    private static final long CORRIDOR_FIXED_FINE_MINOR = 300_000;
    private static final double REWIND_CHECKPOINT_LEAD_SECONDS = 60;
    static final double TEMPORARY_AIRSPACE_APPROACH_SECONDS = 60;
    static final long TEMPORARY_AIRSPACE_CYCLE_MS = 30_000;
    static final long TEMPORARY_AIRSPACE_ACTIVE_MS = 18_000;
    static final long TEMPORARY_AIRSPACE_EXPANSION_MS = 3_000;
    static final long TEMPORARY_AIRSPACE_CONTRACTION_MS = 3_000;
    static final long TEMPORARY_AIRSPACE_POST_PASS_GRACE_MS = 10_000;
    private final MissionCatalog catalog;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MqttBridge mqtt;
    private final TaskTrafficEngine trafficLights;
    private final TaskInstanceService taskInstances;
    private final FleetService fleet;
    private final int maxActive;
    private final int maxQueue;
    private final int durationSeconds;
    private final int disconnectExpirySeconds;
    private final Map<String, DemoSession> sessions = new ConcurrentHashMap<>();
    private final Deque<String> queue = new ArrayDeque<>();
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> startsBySource = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    public DemoSessionService(MissionCatalog catalog, JdbcTemplate jdbc, ObjectMapper mapper, MqttBridge mqtt,
                               TaskTrafficEngine trafficLights, TaskInstanceService taskInstances,
                               FleetService fleet,
                              @Value("${demo.max-active-sessions}") int maxActive,
                              @Value("${demo.max-queue-size}") int maxQueue,
                              @Value("${demo.session-duration-seconds}") int durationSeconds,
                              @Value("${demo.disconnect-expiry-seconds}") int disconnectExpirySeconds) {
        this.catalog = catalog; this.jdbc = jdbc; this.mapper = mapper; this.mqtt = mqtt; this.trafficLights = trafficLights; this.taskInstances = taskInstances; this.fleet = fleet;
        this.maxActive = maxActive; this.maxQueue = maxQueue; this.durationSeconds = durationSeconds;
        this.disconnectExpirySeconds = disconnectExpirySeconds;
    }

    @PostConstruct
    void initialize() {
        jdbc.update("UPDATE demo_session SET status='EXPIRED',completed_at=NOW(3) WHERE status IN ('RUNNING','QUEUED')");
        fleet.releaseInactiveBindings();
        restoreRecentSessions();
        mqtt.setListener(this::acceptTelemetry);
    }

    public Map<String, Object> current(String visitorHash) {
        DemoSession session = sessions.values().stream()
                .filter(item -> item.visitorHash.equals(visitorHash) && !TERMINAL.contains(item.status))
                .max(Comparator.comparing(item -> item.createdAt)).orElse(null);
        if (session != null) return snapshot(session, true);
        Map<String, Object> standby = new LinkedHashMap<>();
        standby.put("session", null);
        Map<String, Object> mission = catalog.standbySnapshot();
        addTrafficLightView(mission, null);
        standby.put("mission", mission);
        standby.put("devices", List.of());
        return standby;
    }

    public Map<String, Object> create(String visitorHash, String source) {
        synchronized (lock) {
            DemoSession existing = sessions.values().stream().filter(item -> item.visitorHash.equals(visitorHash) && !TERMINAL.contains(item.status)).findFirst().orElse(null);
            if (existing != null) { touch(existing); return snapshot(existing, true); }
            checkRate(source);
            long active = sessions.values().stream().filter(item -> "RUNNING".equals(item.status)).count();
            if (active >= maxActive && queue.size() >= maxQueue) throw new DemoException(HttpStatus.TOO_MANY_REQUESTS, "当前体验人数较多，请稍后再试");
            String id = "DEMO-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
            DemoSession session = new DemoSession(id, visitorHash, active < maxActive ? "RUNNING" : "QUEUED",
                    catalog.definitionId(), catalog.definitionVersion());
            session.independentAirRoute = fleet.usesIndependentAirRoute(visitorHash);
            if ("RUNNING".equals(session.status)) start(session); else { queue.addLast(id); session.queuePosition = queue.size(); }
            sessions.put(id, session);
            jdbc.update("INSERT INTO demo_session(id,visitor_hash,definition_id,definition_version,status,time_scale,progress,mission_phase,queue_position,started_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    id, visitorHash, session.definitionId, session.definitionVersion, session.status, session.timeScale, 0, session.missionPhase, session.queuePosition == 0 ? null : session.queuePosition,
                    session.startedAt == null ? null : java.sql.Timestamp.from(session.startedAt));
            return snapshot(session, true);
        }
    }

    @Transactional
    public Map<String, Object> createFromTask(String taskId, String visitorHash, String source) {
        synchronized (lock) {
            DemoSession existing = sessions.values().stream().filter(item -> item.visitorHash.equals(visitorHash) && !TERMINAL.contains(item.status)).findFirst().orElse(null);
            if (existing != null) throw new DemoException(HttpStatus.CONFLICT, "已有正在运行或排队的任务");
            checkRate(source);
            long active = sessions.values().stream().filter(item -> "RUNNING".equals(item.status)).count();
            if (active >= maxActive && queue.size() >= maxQueue) throw new DemoException(HttpStatus.TOO_MANY_REQUESTS, "当前体验人数较多，请稍后再试");
            Map<String, Object> plan = taskInstances.loadPlanOwned(taskId, visitorHash);
            String id = "RUN-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
            Map<String, Object> groundVehicle = castMap(plan.get("groundVehicle"));
            String groundAssetId = String.valueOf(groundVehicle.getOrDefault("assetId", ""));
            long groundStateVersion = groundVehicle.get("stateVersion") instanceof Number value ? value.longValue() : -1;
            if (groundAssetId.isBlank() || groundStateVersion < 0)
                throw new DemoException(HttpStatus.CONFLICT, "任务方案缺少有效的地面车辆，请重新生成");
            Map<String, Object> airVehicle = castMap(plan.get("airVehicle"));
            String airAssetId = String.valueOf(airVehicle.getOrDefault("assetId", ""));
            long airStateVersion = airVehicle.get("stateVersion") instanceof Number value ? value.longValue() : -1;
            if (airAssetId.isBlank() || airStateVersion < 0)
                throw new DemoException(HttpStatus.CONFLICT, "任务方案缺少有效的空中设备，请重新生成");
            fleet.bindGroundVehicle(visitorHash, id, groundAssetId, groundStateVersion);
            fleet.bindAirVehicle(visitorHash, id, airAssetId, airStateVersion);
            taskInstances.markStarted(taskId, visitorHash);
            String definitionId = String.valueOf(plan.getOrDefault("scenarioTemplateId", plan.getOrDefault("scenarioKey", "dynamic-logistics")));
            String definitionVersion = String.valueOf(plan.getOrDefault("scenarioTemplateVersion", plan.getOrDefault("version", "1.0.0")));
            DemoSession session = new DemoSession(id, visitorHash, active < maxActive ? "RUNNING" : "QUEUED",
                    definitionId, definitionVersion, taskId, 1, "demo-simulator/2.0.0", plan, Instant.now());
            if ("RUNNING".equals(session.status)) start(session); else { queue.addLast(id); session.queuePosition = queue.size(); }
            jdbc.update("INSERT INTO demo_session(id,visitor_hash,definition_id,definition_version,task_instance_id,ground_asset_id,air_asset_id,run_no,engine_version,simulation_elapsed_ms,ground_service_elapsed_ms,status,time_scale,progress,mission_phase,queue_position,started_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, visitorHash, definitionId, definitionVersion, taskId, groundAssetId, airAssetId, 1, session.engineVersion, 0, 0, session.status, session.timeScale, 0,
                    session.missionPhase, session.queuePosition == 0 ? null : session.queuePosition,
                    session.startedAt == null ? null : java.sql.Timestamp.from(session.startedAt));
            sessions.put(id, session);
            return snapshot(session, true);
        }
    }

    public Map<String, Object> mission(String id, String visitorHash) { DemoSession session = owned(id, visitorHash); touch(session); return snapshot(session, true); }

    public Map<String, Object> speed(String id, String visitorHash, double timeScale) {
        DemoSession session = owned(id, visitorHash);
        if (!SPEEDS.contains(timeScale)) throw new DemoException(HttpStatus.BAD_REQUEST, "仿真节奏仅支持暂停、0.5、1、2、5 倍");
        if (!"RUNNING".equals(session.status)) throw new DemoException(HttpStatus.CONFLICT, "只有运行中的任务可以改变倍速");
        session.timeScale = timeScale; touch(session);
        jdbc.update("UPDATE demo_session SET time_scale=?,last_seen_at=NOW(3) WHERE id=?", timeScale, id);
        return snapshot(session, true);
    }

    @Transactional
    public Map<String, Object> airspaceAction(String id, String visitorHash, String volumeId, String actionType) {
        DemoSession session = owned(id, visitorHash);
        if (!"RUNNING".equals(session.status)) throw new DemoException(HttpStatus.CONFLICT, "只有运行中的任务可以处置空域冲突");
        String action = String.valueOf(actionType == null ? "" : actionType).trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ACCEPT_RISK", "CONTINUE_DIRECT", "DETOUR", "CLIMB_OVER", "WAIT_UNTIL_CLEAR", "RETURN_TO_RECOVERY", "TRANSIT_CORRIDOR").contains(action))
            throw new DemoException(HttpStatus.BAD_REQUEST, "不支持的空域处置动作");
        Map<String, Object> airspace = airspaceRuntime(session, definition(session));
        Map<String, Object> volume = mapList(airspace.get("runtimeVolumes")).stream()
                .filter(value -> volumeId.equals(String.valueOf(value.get("id")))).findFirst()
                .orElseThrow(() -> new DemoException(HttpStatus.NOT_FOUND, "空域不存在"));
        Map<String, Object> conflict = mapList(airspace.get("conflicts")).stream()
                .filter(value -> volumeId.equals(String.valueOf(value.get("volumeId")))).findFirst()
                .orElseThrow(() -> new DemoException(HttpStatus.CONFLICT, "当前没有可处置的航线冲突"));
        List<String> availableActions = conflict.get("availableActions") instanceof List<?> values
                ? values.stream().map(String::valueOf).toList() : List.of();
        if (!availableActions.contains(action))
            throw new DemoException(HttpStatus.CONFLICT, "该动作在当前距离、空域规则或安全约束下不可用");
        if (session.airspaceActions.containsKey(volumeId)) return session.airspaceActions.get(volumeId);
        Map<String, Object> uav = actors(session).stream().filter(actor -> "UAV".equals(String.valueOf(actor.get("kind")))).findFirst()
                .orElseThrow(() -> new DemoException(HttpStatus.CONFLICT, "任务没有可处置的无人机"));
        String actorId = String.valueOf(uav.get("id")), routeId = String.valueOf(uav.get("routeId"));
        List<double[]> route = points(session, routeId);
        double current = session.routeProgress.getOrDefault(actorId, 0.0);
        double currentRouteProgress = executableAirRouteProgress(current);
        double[] currentPoint = MissionMath.sample(route, currentRouteProgress / 100);
        List<double[]> override = null;
        if ("ACCEPT_RISK".equals(action)) {
            if (AirspaceGeometry.blocking(volume)) throw new DemoException(HttpStatus.CONFLICT, "阻断空域不能接受风险后继续穿越");
        } else if ("CONTINUE_DIRECT".equals(action)) {
            if (!"TEMPORARY_NO_FLY".equals(volume.get("ruleType")))
                throw new DemoException(HttpStatus.CONFLICT, "只有临时禁飞区支持保持原航线");
        } else if ("DETOUR".equals(action)) override = AirspaceGeometry.detour(route, volume, currentRouteProgress);
        else if ("TRANSIT_CORRIDOR".equals(action)) {
            if (!"ALTITUDE_CORRIDOR".equals(volume.get("ruleType")))
                throw new DemoException(HttpStatus.CONFLICT, "只有高度走廊支持穿廊动作");
            override = AirspaceGeometry.transitCorridor(route, volume, currentRouteProgress);
            if (AirspaceGeometry.conflict(override, volume, currentRouteProgress) != null)
                throw new DemoException(HttpStatus.CONFLICT, "距离走廊过近，已无法完成 120 米平滑升降，请绕飞或返航");
        }
        else if ("CLIMB_OVER".equals(action)) {
            double targetAltitude = number(volume.get("ceilingMeters"), 0) + 12;
            if (targetAltitude > 100) throw new DemoException(HttpStatus.CONFLICT, "该空域上限过高，无法在任务合法高度内爬升绕越");
            override = AirspaceGeometry.climbOver(route, volume, targetAltitude, currentRouteProgress);
        } else if ("WAIT_UNTIL_CLEAR".equals(action)) {
            Object until = volume.get("activeUntilSimulationMs");
            if (!(until instanceof Number number)) throw new DemoException(HttpStatus.CONFLICT, "该空域没有明确解除时间，不能等待放行");
            session.uavWaitUntilMs.put(actorId, number.longValue());
            session.temporaryAirspaceClearanceUntilMs.put(volumeId, Long.MAX_VALUE);
        } else {
            double[] recovery = recoveryPoint(route(session, routeId), "recoveryPoint");
            double altitude = Math.max(currentPoint[2], recovery[2] + 18);
            override = safeReturnRoute(session, List.of(currentPoint,
                    new double[]{recovery[0], recovery[1], altitude}, recovery));
        }
        if (override != null) {
            double remappedRouteProgress = AirspaceGeometry.closestRouteProgress(override, currentPoint);
            session.airRouteOverrides.put(routeId, coordinateValues(override));
            double remappedSortieProgress = sortieProgressForAirRoute(remappedRouteProgress);
            session.routeProgress.put(actorId, remappedSortieProgress);
            synchronizeDeviceRouteProgress(session, actorId, remappedSortieProgress);
            session.previousActorPositions.put(actorId, currentPoint.clone());
        }
        double maneuverDelaySeconds = switch (action) {
            case "ACCEPT_RISK", "CONTINUE_DIRECT", "WAIT_UNTIL_CLEAR" -> 0;
            default -> Math.max(0, number(castMap(definition(session).get("airVehicle")).get("maneuverDelaySeconds"), 0));
        };
        if (maneuverDelaySeconds > 0) {
            long readyAt = session.simulationElapsedMs + Math.round(maneuverDelaySeconds * 1000);
            session.uavWaitUntilMs.merge(actorId, readyAt, Math::max);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        String actionId = "AIRACT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        response.put("id", actionId); response.put("runId", id); response.put("volumeId", volumeId); response.put("actorId", actorId);
        response.put("actionType", action); response.put("status", "APPLIED"); response.put("simulationTimeMs", session.simulationElapsedMs);
        response.put("maneuverDelaySeconds", maneuverDelaySeconds);
        if ("WAIT_UNTIL_CLEAR".equals(action)) {
            long waitUntil = session.uavWaitUntilMs.getOrDefault(actorId, session.simulationElapsedMs);
            response.put("waitUntilSimulationMs", waitUntil);
            response.put("waitSeconds", Math.max(0, Math.ceil((waitUntil - session.simulationElapsedMs) / 1000.0)));
        }
        String actionMessage = switch (action) { case "ACCEPT_RISK" -> "已记录风险接受，区内耗电将按 2.5 倍计算"; case "CONTINUE_DIRECT" -> "无人机将保持原航线；穿越时若橙区生效，将同时领取粉钻并按侵入计罚"; case "DETOUR" -> "已生成并切换为绕飞航线"; case "CLIMB_OVER" -> "已切换为合法高度爬升绕越";
            case "TRANSIT_CORRIDOR" -> "已切换为平滑升降的合法高度走廊航线";
            case "WAIT_UNTIL_CLEAR" -> "无人机将在安全位置短暂等待，解除后保留充足通行时间"; default -> "无人机已转入返航回收航线"; };
        response.put("message", maneuverDelaySeconds > 0
                ? actionMessage + "，机动响应 " + Math.round(maneuverDelaySeconds) + " 秒" : actionMessage);
        session.airspaceActions.put(volumeId, response);
        jdbc.update("INSERT INTO demo_airspace_action(id,session_id,volume_id,actor_id,action_type,simulation_time_ms,timeline_epoch,route_override_json,response_json) VALUES(?,?,?,?,?,?,?,?,?)",
                actionId, id, volumeId, actorId, action, session.simulationElapsedMs, session.timelineEpoch,
                override == null ? null : writeJson(coordinateValues(override)), writeJson(response));
        broadcastDelta(session, "airspace-delta", Map.of("airspace", airspaceRuntime(session, definition(session)), "action", response));
        return Map.of("action", response, "snapshot", snapshot(session, true));
    }

    @Transactional
    public Map<String, Object> restoreCheckpoint(String id, String visitorHash, String checkpointId,
                                                  String rewindIdValue, long expectedRevision) {
        synchronized (lock) {
            DemoSession session = owned(id, visitorHash);
            List<String> lockedSessions = jdbc.query(
                    "SELECT id FROM demo_session WHERE id=? AND visitor_hash=? FOR UPDATE",
                    (result, row) -> result.getString("id"), id, visitorHash);
            if (lockedSessions.isEmpty()) throw new DemoException(HttpStatus.NOT_FOUND, "任务不存在或已清理");
            String rewindId = String.valueOf(rewindIdValue == null ? "" : rewindIdValue).trim();
            if (!rewindId.matches("[A-Za-z0-9._:-]{1,64}"))
                throw new DemoException(HttpStatus.BAD_REQUEST, "rewindId 格式无效");
            DemoSession.RewindCheckpoint checkpoint = session.rewindCheckpoints.get(checkpointId);
            if (checkpoint == null) throw new DemoException(HttpStatus.NOT_FOUND, "回溯检查点不存在");
            if ("USED".equals(checkpoint.status)) {
                if (!rewindId.equals(checkpoint.rewindId))
                    throw new DemoException(HttpStatus.CONFLICT, "该决策检查点已使用");
                return Map.of("rewind", rewindView(checkpoint, true), "snapshot", snapshot(session, true));
            }
            if (!"AVAILABLE".equals(checkpoint.status))
                throw new DemoException(HttpStatus.CONFLICT, "该检查点已不是最近可回溯节点");
            DemoSession.RewindCheckpoint latest = latestAvailableCheckpoint(session);
            if (latest == null || !checkpoint.id.equals(latest.id))
                throw new DemoException(HttpStatus.CONFLICT, "只能恢复最近的决策检查点");
            if (session.revision.get() != expectedRevision)
                throw new DemoException(HttpStatus.CONFLICT, "任务状态已变化，请刷新后重试");
            if ("COMPLETED".equals(session.status))
                throw new DemoException(HttpStatus.CONFLICT, "已成功结算的任务不能回溯");
            if (!Set.of("RUNNING", "FAILED").contains(session.status))
                throw new DemoException(HttpStatus.CONFLICT, "当前任务状态不支持回溯");
            boolean otherActive = sessions.values().stream().anyMatch(item -> !item.id.equals(session.id)
                    && item.visitorHash.equals(visitorHash) && !TERMINAL.contains(item.status));
            if (otherActive) throw new DemoException(HttpStatus.CONFLICT, "已有另一项任务正在运行，无法恢复旧任务");

            int sourceEpoch = session.timelineEpoch;
            int targetEpoch = sourceEpoch + 1;
            Map<String, Object> state = checkpoint.state;
            double checkpointGroundBattery = number(state.get("groundBatteryPercent"), session.groundBatteryPercent);
            double checkpointAirBattery = number(state.get("airBatteryPercent"), session.airBatteryPercent);
            Map<String, Object> economyAdjustment = fleet.rewindMissionTransactions(visitorHash, session.id,
                    sourceEpoch, targetEpoch, checkpoint.simulationTimeMs, checkpoint.id, rewindId);
            fleet.restoreRunAssets(visitorHash, session.id, session.groundAssetId, checkpointGroundBattery,
                    session.airAssetId, checkpointAirBattery);
            supersedeTimelineAfter(session, checkpoint, rewindId, sourceEpoch);
            restoreCheckpointState(session, checkpoint, targetEpoch);

            Instant usedAt = Instant.now();
            checkpoint.status = "USED"; checkpoint.usedAt = usedAt; checkpoint.rewindId = rewindId;
            jdbc.update("UPDATE demo_rewind_checkpoint SET status='USED',used_at=?,rewind_id=? WHERE id=? AND status='AVAILABLE'",
                    java.sql.Timestamp.from(usedAt), rewindId, checkpoint.id);
            jdbc.update("UPDATE demo_session SET status='RUNNING',time_scale=0,progress=?,mission_phase=?,simulation_elapsed_ms=?,ground_service_elapsed_ms=?,timeline_epoch=?,terminal_reason=NULL,completed_at=NULL,last_seen_at=NOW(3) WHERE id=?",
                    session.progress, session.missionPhase, session.simulationElapsedMs, session.groundServiceElapsedMs,
                    session.timelineEpoch, session.id);
            reloadActiveTelemetry(session);
            Map<String, Object> response = new LinkedHashMap<>();
            Map<String, Object> rewind = rewindView(checkpoint, false);
            rewind.put("economyAdjustment", economyAdjustment);
            response.put("rewind", rewind);
            broadcastSnapshot(session, "snapshot");
            response.put("snapshot", snapshot(session, true));
            return response;
        }
    }

    public Map<String, Object> restart(String id, String visitorHash, String source) {
        DemoSession previous = owned(id, visitorHash);
        if (!TERMINAL.contains(previous.status)) throw new DemoException(HttpStatus.CONFLICT, "当前任务尚未结束");
        return create(visitorHash, source);
    }

    public Map<String, Object> stop(String id, String visitorHash) {
        DemoSession session = owned(id, visitorHash);
        finish(session, "STOPPED");
        return snapshot(session, true);
    }

    public Map<String, Object> history(String id, String visitorHash, String fromValue, String toValue, String actorId) {
        DemoSession session = owned(id, visitorHash);
        touch(session);
        Instant from = parseHistoryInstant(fromValue, session.createdAt.minusSeconds(1), "from");
        Instant to = parseHistoryInstant(toValue, Instant.now(), "to");
        if (to.isBefore(from)) throw new DemoException(HttpStatus.BAD_REQUEST, "历史查询结束时间不能早于开始时间");
        if (Duration.between(from, to).compareTo(Duration.ofHours(24)) > 0) throw new DemoException(HttpStatus.BAD_REQUEST, "单次历史查询范围不能超过24小时");
        java.sql.Timestamp fromTimestamp = java.sql.Timestamp.from(from);
        java.sql.Timestamp toTimestamp = java.sql.Timestamp.from(to);

        String telemetrySql = "SELECT * FROM demo_telemetry WHERE session_id=? AND event_time>=? AND event_time<=? AND superseded_by_rewind_id IS NULL";
        Object[] telemetryArgs;
        if (actorId != null && !actorId.isBlank()) {
            telemetrySql += " AND device_id=?";
            telemetryArgs = new Object[]{session.id, fromTimestamp, toTimestamp, actorId};
        } else telemetryArgs = new Object[]{session.id, fromTimestamp, toTimestamp};
        telemetrySql += " ORDER BY event_time,id LIMIT 5000";
        List<Map<String, Object>> telemetry = jdbc.query(telemetrySql, (result, row) -> {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("id", result.getLong("id")); point.put("actorId", result.getString("device_id"));
            point.put("actorType", result.getString("device_type")); point.put("eventTime", result.getTimestamp("event_time").toInstant().toString());
            point.put("simulationTimeMs", result.getObject("simulation_time_ms") == null ? null : result.getLong("simulation_time_ms"));
            point.put("longitude", result.getDouble("longitude")); point.put("latitude", result.getDouble("latitude"));
            point.put("altitude", result.getDouble("altitude")); point.put("heading", result.getDouble("heading"));
            try { point.put("metrics", mapper.readValue(result.getString("metrics_json"), new TypeReference<Map<String, Object>>() {})); }
            catch (Exception ignored) { point.put("metrics", Map.of()); }
            return point;
        }, telemetryArgs);

        List<Map<String, Object>> events = jdbc.query("SELECT * FROM demo_event WHERE session_id=? AND event_time>=? AND event_time<=? AND superseded_by_rewind_id IS NULL ORDER BY event_time,id",
                (result, row) -> {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("id", result.getLong("id")); event.put("type", result.getString("event_type"));
                    event.put("eventTime", result.getTimestamp("event_time").toInstant().toString()); event.put("progress", result.getDouble("progress"));
                    event.put("simulationTimeMs", result.getObject("simulation_time_ms") == null ? null : result.getLong("simulation_time_ms"));
                    try { event.put("payload", mapper.readValue(result.getString("payload_json"), new TypeReference<Map<String, Object>>() {})); }
                    catch (Exception ignored) { event.put("payload", Map.of()); }
                    return event;
                }, session.id, fromTimestamp, toTimestamp);

        List<Map<String, Object>> commands = jdbc.query("SELECT * FROM demo_workflow_command WHERE session_id=? AND requested_at>=? AND requested_at<=? AND superseded_by_rewind_id IS NULL ORDER BY requested_at,id",
                (result, row) -> commandView(result.getString("id"), result.getString("session_id"), result.getString("signal_id"),
                        result.getString("command_type"), result.getString("expected_signal_status"), result.getString("result_signal_status"),
                        result.getDouble("mission_progress"), result.getString("status"), result.getTimestamp("requested_at").toInstant(),
                        result.getTimestamp("acknowledged_at").toInstant(), result.getString("message")), session.id, fromTimestamp, toTimestamp);

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sessionId", session.id); value.put("definitionId", session.definitionId); value.put("definitionVersion", session.definitionVersion);
        value.put("from", from.toString()); value.put("to", to.toString()); value.put("actorId", actorId);
        value.put("telemetry", telemetry); value.put("events", events); value.put("commands", commands);
        value.put("signals", session.signals.values().stream().map(signal -> signal.publicView(session.id)).toList());
        return value;
    }

    @Transactional
    public Map<String, Object> signalCommand(String id, String visitorHash, String signalId, String commandId,
                                             String commandType, String expectedSignalStatus) {
        DemoSession session = owned(id, visitorHash);
        String normalizedId = String.valueOf(commandId == null ? "" : commandId).trim();
        String normalizedType = String.valueOf(commandType == null ? "" : commandType).trim().toUpperCase(Locale.ROOT);
        String expected = String.valueOf(expectedSignalStatus == null ? "" : expectedSignalStatus).trim().toUpperCase(Locale.ROOT);
        if (!normalizedId.matches("[A-Za-z0-9._:-]{1,80}")) throw new DemoException(HttpStatus.BAD_REQUEST, "commandId 格式无效");
        if (normalizedType.isBlank() || expected.isBlank()) throw new DemoException(HttpStatus.BAD_REQUEST, "命令类型和预期 Signal 状态不能为空");

        Map<String, Object> existing = existingCommand(normalizedId);
        if (existing != null) {
            if (!id.equals(existing.get("sessionId")) || !signalId.equals(existing.get("signalId")) || !normalizedType.equals(existing.get("type"))) {
                throw new DemoException(HttpStatus.CONFLICT, "commandId 已被其他操作使用");
            }
            DemoSignal signal = session.signals.get(signalId);
            return Map.of("command", existing, "signal", signal == null ? Map.of() : signal.publicView(session.id), "snapshot", snapshot(session, true));
        }

        DemoSignal signal = session.signals.get(signalId);
        if (signal == null) throw new DemoException(HttpStatus.NOT_FOUND, "Signal 尚未检出或不存在");
        synchronized (signal) {
            if (!expected.equals(signal.status)) throw new DemoException(HttpStatus.CONFLICT, "Signal 状态已变化，请刷新后重试");
            final String next;
            try { next = SignalStateMachine.transition(signal.status, normalizedType); }
            catch (IllegalArgumentException error) { throw new DemoException(HttpStatus.CONFLICT, error.getMessage()); }
            Instant now = Instant.now();
            int changed = jdbc.update("UPDATE demo_signal SET status=?,updated_at=? WHERE id=? AND session_id=? AND status=?",
                    next, java.sql.Timestamp.from(now), signal.id, session.id, expected);
            if (changed != 1) throw new DemoException(HttpStatus.CONFLICT, "Signal 状态已变化，请刷新后重试");
            String message = "工作流操作已由任务服务确认";
            jdbc.update("INSERT INTO demo_workflow_command(id,session_id,signal_id,command_type,expected_signal_status,result_signal_status,mission_progress,timeline_epoch,status,requested_at,acknowledged_at,message) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    normalizedId, session.id, signal.id, normalizedType, expected, next, session.progress, session.timelineEpoch, "ACKNOWLEDGED",
                    java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), message);
            signal.status = next;
            signal.updatedAt = now;
            signal.recordStatus(next, session.progress, now);
            Map<String, Object> command = commandView(normalizedId, session.id, signal.id, normalizedType, expected, next,
                    session.progress, "ACKNOWLEDGED", now, now, message);
            broadcastDelta(session, "signal-delta", Map.of("signal", signal.publicView(session.id)));
            broadcastDelta(session, "command-ack", Map.of("command", command));
            return Map.of("command", command, "signal", signal.publicView(session.id), "snapshot", snapshot(session, true));
        }
    }

    public SseEmitter events(String id, String visitorHash, String lastEventId) {
        DemoSession session = owned(id, visitorHash); touch(session);
        SseEmitter emitter = new SseEmitter(240_000L);
        emitters.computeIfAbsent(id, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable remove = () -> removeEmitter(id, emitter);
        emitter.onCompletion(remove); emitter.onTimeout(remove); emitter.onError(error -> remove.run());
        if (TERMINAL.contains(session.status)) {
            send(emitter, "session-end", snapshot(session, true), session.revision.get());
            return emitter;
        }
        List<DemoSession.DeltaEvent> replay = parseLastEventId(lastEventId)
                .map(session::deltasAfter).orElse(null);
        if (replay == null) {
            send(emitter, "snapshot", snapshot(session, true), session.revision.get());
        } else {
            for (DemoSession.DeltaEvent event : replay) {
                if (!send(emitter, event.eventType(), event.data(), event.revision())) break;
            }
        }
        return emitter;
    }

    @Scheduled(fixedRate = 1000)
    public void tick() {
        Instant now = Instant.now();
        for (DemoSession session : new ArrayList<>(sessions.values())) {
            if (!"RUNNING".equals(session.status)) continue;
            if (!emitters.containsKey(session.id) && Duration.between(session.lastSeenAt, now).toSeconds() > disconnectExpirySeconds) { finish(session, "EXPIRED"); continue; }
            if (session.timeScale == 0) continue;
            long simulationStepMs = Math.round(1000 * session.timeScale);
            session.simulationElapsedMs += simulationStepMs;
            if (session.taskInstanceId != null) advanceTaskSimulation(session, simulationStepMs / 1000.0, now);
            else {
                double progressStep = (100.0 / missionDurationSeconds(session)) * session.timeScale;
                advanceGroundVehicles(session, progressStep, now);
                session.missionPhase = phase(session, session.progress);
            }
            if (session.taskInstanceId != null) {
                processTemporaryAirspaceWarnings(session);
                processRedAirspaceWarnings(session);
                processRewindCheckpoints(session);
                try { processMissionEconomy(session, simulationStepMs); }
                catch (Exception error) { session.terminalReason = "ECONOMY_PROCESSING_FAILED"; finish(session, "FAILED"); continue; }
                if (session.progress >= 100 && !session.groundBatteryDepleted && !session.airBatteryDepleted) {
                    try { awardTimelinessReward(session); }
                    catch (Exception error) { session.terminalReason = "ECONOMY_PROCESSING_FAILED"; finish(session, "FAILED"); continue; }
                }
            }
            recordEvents(session, now);
            for (Map<String, Object> actor : actors(session)) publishTelemetry(session, actor, now);
            if (!"RUNNING".equals(session.status)) continue;
            if (session.groundBatteryDepleted) {
                session.terminalReason = "GROUND_BATTERY_DEPLETED";
                finish(session, "FAILED");
                continue;
            }
            if (session.airBatteryDepleted) {
                session.terminalReason = "AIR_BATTERY_DEPLETED";
                finish(session, "FAILED");
                continue;
            }
            jdbc.update("UPDATE demo_session SET progress=?,mission_phase=?,simulation_elapsed_ms=?,ground_service_elapsed_ms=?,status=?,last_seen_at=last_seen_at,completed_at=? WHERE id=?",
                    session.progress, session.missionPhase, session.simulationElapsedMs, session.groundServiceElapsedMs,
                    session.progress >= 100 ? "COMPLETED" : "RUNNING",
                    session.progress >= 100 ? java.sql.Timestamp.from(now) : null, session.id);
            if (session.progress >= 100) finish(session, "COMPLETED");
            else broadcastMissionDelta(session);
        }
        promoteQueue();
        cleanupRateLimits(now);
    }

    private void processRedAirspaceWarnings(DemoSession session) {
        Map<String, Object> runtime = airspaceRuntime(session, definition(session));
        for (Map<String, Object> conflict : mapList(runtime.get("conflicts"))) {
            if (!"ABSOLUTE_NO_FLY".equals(conflict.get("ruleType"))) continue;
            String volumeId = String.valueOf(conflict.get("volumeId"));
            if (session.airspaceActions.containsKey(volumeId)
                    || number(conflict.get("estimatedEntrySeconds"), Double.POSITIVE_INFINITY) > 60
                    || !session.airspaceWarningVolumeIds.add(volumeId)) continue;
            if (session.timeScale > 1) {
                session.timeScale = 1;
                jdbc.update("UPDATE demo_session SET time_scale=1 WHERE id=?", session.id);
            }
            Map<String, Object> warning = new LinkedHashMap<>();
            warning.put("volumeId", volumeId); warning.put("ruleType", "ABSOLUTE_NO_FLY");
            warning.put("estimatedEntrySeconds", conflict.get("estimatedEntrySeconds"));
            warning.put("level", number(conflict.get("estimatedEntrySeconds"), 60) <= 15 ? "CRITICAL" : "WARNING");
            warning.put("message", "绝对禁飞区已进入 60 秒处置范围，仿真倍速已降至 1 倍");
            broadcastDelta(session, "airspace-warning", Map.of("warning", warning,
                    "session", session.publicView(), "airspace", runtime));
        }
    }

    private void processTemporaryAirspaceWarnings(DemoSession session) {
        Map<String, Object> runtime = airspaceRuntime(session, definition(session));
        for (Map<String, Object> conflict : mapList(runtime.get("conflicts"))) {
            if (!"TEMPORARY_NO_FLY".equals(conflict.get("ruleType"))
                    || number(conflict.get("estimatedEntrySeconds"), Double.POSITIVE_INFINITY)
                    > TEMPORARY_AIRSPACE_APPROACH_SECONDS) continue;
            String volumeId = String.valueOf(conflict.get("volumeId"));
            if (!session.airspaceWarningVolumeIds.add(volumeId)) continue;
            if (session.timeScale > 1) {
                session.timeScale = 1;
                jdbc.update("UPDATE demo_session SET time_scale=1 WHERE id=?", session.id);
            }
            Map<String, Object> warning = new LinkedHashMap<>();
            warning.put("volumeId", volumeId); warning.put("ruleType", "TEMPORARY_NO_FLY");
            warning.put("estimatedEntrySeconds", conflict.get("estimatedEntrySeconds"));
            warning.put("level", "WARNING");
            warning.put("message", Boolean.TRUE.equals(conflict.get("currentlyActive"))
                    ? "临时禁飞区预测冲突：橙区当前生效，可直行承担罚款或选择安全路线；仿真倍速已降至 1 倍"
                    : "临时禁飞区预测冲突：橙区当前处于解除间隔，可直行或提前选择绕飞/爬升；仿真倍速已降至 1 倍");
            broadcastDelta(session, "airspace-warning", Map.of("warning", warning,
                    "session", session.publicView(), "airspace", runtime));
        }
    }

    private void processRewindCheckpoints(DemoSession session) {
        if (session.taskInstanceId == null || session.progress >= 100) return;
        Map<String, Object> runtime = airspaceRuntime(session, definition(session));
        List<Map<String, Object>> conflicts = mapList(runtime.get("conflicts"));
        Set<String> handledVolumeIds = new HashSet<>(session.airspaceActions.keySet());
        Set<String> checkpointedVolumeIds = session.rewindCheckpoints.values().stream()
                .map(checkpoint -> checkpoint.volumeId).collect(java.util.stream.Collectors.toSet());
        List<Map<String, Object>> candidates = rewindCheckpointCandidates(
                conflicts, handledVolumeIds, checkpointedVolumeIds);
        if (candidates.isEmpty()) return;

        DemoSession.RewindCheckpoint active = latestAvailableCheckpoint(session);
        if (active != null) {
            String activeVolumeId = active.volumeId;
            Map<String, Object> activeConflict = conflicts.stream()
                    .filter(item -> activeVolumeId.equals(String.valueOf(item.get("volumeId"))))
                    .findFirst().orElse(null);
            if (!handledVolumeIds.contains(activeVolumeId) && activeConflict != null) {
                if (!nearerCheckpointMustPreempt(activeConflict, candidates.get(0))) return;
                // A scheduled dynamic zone can appear in front of a checkpoint
                // that was prepared for a later static zone. The later state is
                // not a real decision boundary yet, so discard it and let that
                // volume receive a fresh checkpoint when it becomes the nearest
                // approach again.
                jdbc.update("DELETE FROM demo_rewind_checkpoint WHERE id=? AND status='AVAILABLE'", active.id);
                session.rewindCheckpoints.remove(active.id);
                active = null;
            }
        }

        createRewindCheckpoint(session, candidates.get(0), active);
    }

    static List<Map<String, Object>> rewindCheckpointCandidates(List<Map<String, Object>> conflicts,
                                                                 Set<String> handledVolumeIds,
                                                                 Set<String> checkpointedVolumeIds) {
        return conflicts.stream()
                .filter(item -> item.get("availableActions") instanceof List<?> actions && !actions.isEmpty())
                .filter(item -> number(item.get("estimatedEntrySeconds"), Double.POSITIVE_INFINITY)
                        <= REWIND_CHECKPOINT_LEAD_SECONDS)
                .filter(item -> !handledVolumeIds.contains(String.valueOf(item.get("volumeId"))))
                .filter(item -> !checkpointedVolumeIds.contains(String.valueOf(item.get("volumeId"))))
                .sorted(Comparator.comparingDouble(item -> number(item.get("estimatedEntrySeconds"), Double.POSITIVE_INFINITY)))
                .toList();
    }

    static boolean nearerCheckpointMustPreempt(Map<String, Object> activeConflict,
                                                Map<String, Object> candidate) {
        return number(candidate.get("estimatedEntrySeconds"), Double.POSITIVE_INFINITY)
                < number(activeConflict.get("estimatedEntrySeconds"), Double.POSITIVE_INFINITY);
    }

    private void createRewindCheckpoint(DemoSession session, Map<String, Object> candidate,
                                        DemoSession.RewindCheckpoint active) {
        Instant createdAt = Instant.now();
        String checkpointId = "RWC-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
        String volumeId = String.valueOf(candidate.get("volumeId"));
        Map<String, Object> state = checkpointState(session);
        if (active != null) {
            active.status = "SUPERSEDED";
            jdbc.update("UPDATE demo_rewind_checkpoint SET status='SUPERSEDED' WHERE id=? AND status='AVAILABLE'", active.id);
        }
        DemoSession.RewindCheckpoint checkpoint = new DemoSession.RewindCheckpoint(checkpointId, volumeId,
                session.timelineEpoch, session.simulationElapsedMs, session.progress, createdAt, state,
                "AVAILABLE", null, null);
        jdbc.update("INSERT INTO demo_rewind_checkpoint(id,session_id,volume_id,timeline_epoch,simulation_time_ms,mission_progress,state_json,status,created_at) VALUES(?,?,?,?,?,?,?,'AVAILABLE',?)",
                checkpoint.id, session.id, checkpoint.volumeId, checkpoint.timelineEpoch,
                checkpoint.simulationTimeMs, checkpoint.missionProgress, writeJson(state), java.sql.Timestamp.from(createdAt));
        session.rewindCheckpoints.put(checkpoint.id, checkpoint);
        broadcastDelta(session, "rewind-checkpoint", Map.of("timeline", timelineView(session), "checkpoint", checkpoint.publicView()));
    }

    private Map<String, Object> checkpointState(DemoSession session) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("progress", session.progress); state.put("missionPhase", session.missionPhase);
        state.put("simulationElapsedMs", session.simulationElapsedMs);
        state.put("groundServiceElapsedMs", session.groundServiceElapsedMs);
        state.put("groundBatteryPercent", session.groundBatteryPercent);
        state.put("airBatteryPercent", session.airBatteryPercent);
        state.put("routeProgress", new LinkedHashMap<>(session.routeProgress));
        state.put("uavStates", new LinkedHashMap<>(session.uavStates));
        state.put("trafficStops", new LinkedHashMap<>(session.trafficStops));
        state.put("triggeredEvents", new ArrayList<>(session.triggeredEvents));
        state.put("airRouteOverrides", new LinkedHashMap<>(session.airRouteOverrides));
        state.put("airspaceActions", new LinkedHashMap<>(session.airspaceActions));
        state.put("uavWaitUntilMs", new LinkedHashMap<>(session.uavWaitUntilMs));
        state.put("temporaryAirspaceClearanceUntilMs", new LinkedHashMap<>(session.temporaryAirspaceClearanceUntilMs));
        state.put("collectedDeliveryPointIds", new ArrayList<>(session.collectedDeliveryPointIds));
        state.put("collectedDiamondIds", new ArrayList<>(session.collectedDiamondIds));
        state.put("forfeitedDiamondIds", new ArrayList<>(session.forfeitedDiamondIds));
        state.put("airspaceWarningVolumeIds", new ArrayList<>(session.airspaceWarningVolumeIds));
        state.put("airEnergyMultipliers", new LinkedHashMap<>(session.airEnergyMultipliers));
        state.put("airRiskVolumeIds", new LinkedHashMap<>(session.airRiskVolumeIds));
        state.put("airExtraBatteryUsedPercent", new LinkedHashMap<>(session.airExtraBatteryUsedPercent));
        Map<String, List<Number>> positions = new LinkedHashMap<>();
        session.previousActorPositions.forEach((key, point) -> positions.put(key, List.of(point[0], point[1], point[2])));
        state.put("previousActorPositions", positions);
        state.put("airspaceIncursionSequences", new LinkedHashMap<>(session.airspaceIncursionSequences));
        List<Map<String, Object>> incursions = new ArrayList<>();
        session.activeAirspaceIncursions.forEach((key, incursion) -> incursions.add(Map.of(
                "key", key, "id", incursion.id, "volumeId", incursion.volumeId, "actorId", incursion.actorId,
                "sequence", incursion.sequence, "entrySimulationMs", incursion.entrySimulationMs,
                "exposureMs", incursion.exposureMs)));
        state.put("activeAirspaceIncursions", incursions);
        return mapper.convertValue(state, new TypeReference<>() {});
    }

    private void restoreCheckpointState(DemoSession session, DemoSession.RewindCheckpoint checkpoint, int targetEpoch) {
        Map<String, Object> state = checkpoint.state;
        session.progress = number(state.get("progress"), checkpoint.missionProgress);
        session.missionPhase = String.valueOf(state.getOrDefault("missionPhase", "DOCKED"));
        session.simulationElapsedMs = Math.round(number(state.get("simulationElapsedMs"), checkpoint.simulationTimeMs));
        session.groundServiceElapsedMs = Math.round(number(state.get("groundServiceElapsedMs"), 0));
        session.groundBatteryPercent = number(state.get("groundBatteryPercent"), session.groundBatteryPercent);
        session.airBatteryPercent = number(state.get("airBatteryPercent"), session.airBatteryPercent);
        session.groundBatteryDepleted = false; session.airBatteryDepleted = false;
        session.terminalReason = null; session.status = "RUNNING"; session.timeScale = 0; session.timelineEpoch = targetEpoch;
        replaceMap(session.routeProgress, state.get("routeProgress"), new TypeReference<Map<String, Double>>() {});
        replaceMap(session.uavStates, state.get("uavStates"), new TypeReference<Map<String, String>>() {});
        replaceMap(session.trafficStops, state.get("trafficStops"), new TypeReference<Map<String, Map<String, Object>>>() {});
        replaceSet(session.triggeredEvents, state.get("triggeredEvents"));
        replaceMap(session.airRouteOverrides, state.get("airRouteOverrides"), new TypeReference<Map<String, List<List<Number>>>>() {});
        replaceMap(session.airspaceActions, state.get("airspaceActions"), new TypeReference<Map<String, Map<String, Object>>>() {});
        replaceMap(session.uavWaitUntilMs, state.get("uavWaitUntilMs"), new TypeReference<Map<String, Long>>() {});
        replaceMap(session.temporaryAirspaceClearanceUntilMs, state.get("temporaryAirspaceClearanceUntilMs"), new TypeReference<Map<String, Long>>() {});
        replaceSet(session.collectedDeliveryPointIds, state.get("collectedDeliveryPointIds"));
        replaceSet(session.collectedDiamondIds, state.get("collectedDiamondIds"));
        replaceSet(session.forfeitedDiamondIds, state.get("forfeitedDiamondIds"));
        replaceSet(session.airspaceWarningVolumeIds, state.get("airspaceWarningVolumeIds"));
        replaceMap(session.airEnergyMultipliers, state.get("airEnergyMultipliers"), new TypeReference<Map<String, Double>>() {});
        replaceMap(session.airRiskVolumeIds, state.get("airRiskVolumeIds"), new TypeReference<Map<String, String>>() {});
        replaceMap(session.airExtraBatteryUsedPercent, state.get("airExtraBatteryUsedPercent"), new TypeReference<Map<String, Double>>() {});
        replaceMap(session.airspaceIncursionSequences, state.get("airspaceIncursionSequences"), new TypeReference<Map<String, Integer>>() {});
        session.previousActorPositions.clear();
        Map<String, List<Number>> positions = mapper.convertValue(state.getOrDefault("previousActorPositions", Map.of()), new TypeReference<>() {});
        positions.forEach((key, point) -> {
            if (point != null && point.size() >= 3) session.previousActorPositions.put(key,
                    new double[]{point.get(0).doubleValue(), point.get(1).doubleValue(), point.get(2).doubleValue()});
        });
        session.activeAirspaceIncursions.clear();
        for (Map<String, Object> value : mapList(state.get("activeAirspaceIncursions"))) {
            DemoSession.AirspaceIncursion incursion = new DemoSession.AirspaceIncursion(
                    String.valueOf(value.get("id")), String.valueOf(value.get("volumeId")), String.valueOf(value.get("actorId")),
                    (int) Math.round(number(value.get("sequence"), 1)), Math.round(number(value.get("entrySimulationMs"), 0)),
                    Math.round(number(value.get("exposureMs"), 0)));
            session.activeAirspaceIncursions.put(String.valueOf(value.get("key")), incursion);
        }
        session.signals.entrySet().removeIf(entry -> !session.triggeredEvents.contains(entry.getValue().key));
    }

    private <T> void replaceMap(Map<String, T> target, Object source, TypeReference<Map<String, T>> type) {
        target.clear();
        if (source != null) target.putAll(mapper.convertValue(source, type));
    }

    private static void replaceSet(Set<String> target, Object source) {
        target.clear();
        if (source instanceof Collection<?> values) values.stream().map(String::valueOf).forEach(target::add);
    }

    private void supersedeTimelineAfter(DemoSession session, DemoSession.RewindCheckpoint checkpoint,
                                        String rewindId, int sourceEpoch) {
        long at = checkpoint.simulationTimeMs;
        java.sql.Timestamp createdAt = java.sql.Timestamp.from(checkpoint.createdAt);
        jdbc.update("UPDATE demo_telemetry SET superseded_by_rewind_id=? WHERE session_id=? AND timeline_epoch=? AND simulation_time_ms>=? AND superseded_by_rewind_id IS NULL",
                rewindId, session.id, sourceEpoch, at);
        jdbc.update("UPDATE demo_event SET superseded_by_rewind_id=? WHERE session_id=? AND timeline_epoch=? AND simulation_time_ms>=? AND superseded_by_rewind_id IS NULL",
                rewindId, session.id, sourceEpoch, at);
        jdbc.update("UPDATE demo_signal SET superseded_by_rewind_id=? WHERE session_id=? AND timeline_epoch=? AND detected_at>=? AND superseded_by_rewind_id IS NULL",
                rewindId, session.id, sourceEpoch, createdAt);
        jdbc.update("UPDATE demo_workflow_command SET superseded_by_rewind_id=? WHERE session_id=? AND timeline_epoch=? AND requested_at>=? AND superseded_by_rewind_id IS NULL",
                rewindId, session.id, sourceEpoch, createdAt);
        jdbc.update("UPDATE demo_airspace_action SET superseded_by_rewind_id=? WHERE session_id=? AND timeline_epoch=? AND simulation_time_ms>=? AND superseded_by_rewind_id IS NULL",
                rewindId, session.id, sourceEpoch, at);
        jdbc.update("UPDATE demo_airspace_incursion SET superseded_by_rewind_id=? WHERE session_id=? AND timeline_epoch=? AND entry_simulation_ms>=? AND superseded_by_rewind_id IS NULL",
                rewindId, session.id, sourceEpoch, at);
    }

    private void reloadActiveTelemetry(DemoSession session) {
        session.tracks.clear(); session.devices.clear();
        jdbc.query("SELECT * FROM demo_telemetry WHERE session_id=? AND superseded_by_rewind_id IS NULL ORDER BY simulation_time_ms,event_time,id",
                result -> {
                    String deviceId = result.getString("device_id");
                    Map<String, Object> metrics;
                    try { metrics = mapper.readValue(result.getString("metrics_json"), new TypeReference<>() {}); }
                    catch (Exception ignored) { metrics = new LinkedHashMap<>(); }
                    Map<String, Object> point = new LinkedHashMap<>();
                    point.put("longitude", result.getDouble("longitude")); point.put("latitude", result.getDouble("latitude"));
                    point.put("altitude", result.getDouble("altitude")); point.put("eventTime", result.getTimestamp("event_time").toInstant().toString());
                    point.put("simulationTimeMs", result.getLong("simulation_time_ms")); point.put("metrics", metrics);
                    List<Map<String, Object>> track = session.tracks.computeIfAbsent(deviceId, ignored -> Collections.synchronizedList(new ArrayList<>()));
                    track.add(point); if (track.size() > 500) track.remove(0);
                    Map<String, Object> actor = actorMetadata(session, deviceId);
                    Map<String, Object> device = new LinkedHashMap<>();
                    device.put("deviceId", deviceId); device.put("deviceType", result.getString("device_type"));
                    device.put("deviceName", actor.getOrDefault("name", deviceId)); copyActorMetadata(actor, device);
                    device.putIfAbsent("commandTransport", unavailableCommandTransport());
                    device.put("longitude", point.get("longitude")); device.put("latitude", point.get("latitude"));
                    device.put("altitude", point.get("altitude")); device.put("sensorData", metrics);
                    session.devices.put(deviceId, device);
                }, session.id);
        for (Map<String, Object> actor : actors(session)) {
            String actorId = String.valueOf(actor.get("id"));
            Map<String, Object> device = new LinkedHashMap<>(session.devices.getOrDefault(actorId, Map.of()));
            double[] position = actorPosition(session, actor);
            Map<String, Object> metrics = new LinkedHashMap<>(castMap(device.get("sensorData")));
            boolean drone = "UAV".equals(String.valueOf(actor.get("kind")));
            metrics.put("routeProgress", session.routeProgress.getOrDefault(actorId, 0.0));
            metrics.put("missionPhase", session.missionPhase); metrics.put("speed", 0);
            metrics.put("battery", drone ? session.airBatteryPercent : session.groundBatteryPercent);
            if (drone) metrics.put("uavState", session.uavStates.getOrDefault(actorId, "ON_CARRIER"));
            device.put("deviceId", actorId); device.put("deviceType", actor.get("deviceType"));
            device.put("deviceName", actor.getOrDefault("name", actorId)); copyActorMetadata(actor, device);
            device.putIfAbsent("commandTransport", unavailableCommandTransport());
            device.put("longitude", position[0]); device.put("latitude", position[1]); device.put("altitude", position[2]);
            device.put("sensorData", metrics); session.devices.put(actorId, device);
        }
    }

    private DemoSession.RewindCheckpoint latestAvailableCheckpoint(DemoSession session) {
        return session.rewindCheckpoints.values().stream().filter(item -> "AVAILABLE".equals(item.status))
                .max(Comparator.comparingLong(item -> item.simulationTimeMs)).orElse(null);
    }

    private Map<String, Object> timelineView(DemoSession session) {
        List<Map<String, Object>> checkpoints = session.rewindCheckpoints.values().stream()
                .sorted(Comparator.comparingLong(item -> item.simulationTimeMs)).map(DemoSession.RewindCheckpoint::publicView).toList();
        DemoSession.RewindCheckpoint latest = latestAvailableCheckpoint(session);
        Map<String, Object> timeline = new LinkedHashMap<>();
        timeline.put("liveProgress", session.progress); timeline.put("liveSimulationTimeMs", session.simulationElapsedMs);
        timeline.put("timelineEpoch", session.timelineEpoch); timeline.put("paused", "RUNNING".equals(session.status) && session.timeScale == 0);
        timeline.put("checkpoints", checkpoints); timeline.put("latestCheckpointId", latest == null ? null : latest.id);
        timeline.put("rewindEligible", latest != null && Set.of("RUNNING", "FAILED").contains(session.status));
        return timeline;
    }

    private static Map<String, Object> rewindView(DemoSession.RewindCheckpoint checkpoint, boolean replayed) {
        Map<String, Object> value = new LinkedHashMap<>(checkpoint.publicView());
        value.put("rewindId", checkpoint.rewindId); value.put("replayed", replayed);
        value.put("message", replayed ? "该回溯请求已处理" : "已恢复到决策检查点，任务处于暂停状态");
        return value;
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void cleanupHistory() {
        jdbc.update("DELETE FROM demo_session WHERE status IN ('COMPLETED','STOPPED','EXPIRED','FAILED') AND completed_at < NOW() - INTERVAL 30 DAY");
        jdbc.update("DELETE FROM demo_task_instance WHERE lifecycle_status='READY' AND expires_at < NOW(3)");
        jdbc.update("DELETE t FROM demo_task_instance t LEFT JOIN demo_session s ON s.task_instance_id=t.id WHERE t.lifecycle_status='STARTED' AND t.created_at < NOW() - INTERVAL 30 DAY AND s.id IS NULL");
        Instant cutoff = Instant.now().minus(Duration.ofDays(30));
        sessions.entrySet().removeIf(entry -> TERMINAL.contains(entry.getValue().status) && entry.getValue().createdAt.isBefore(cutoff));
    }

    private void processMissionEconomy(DemoSession session, long simulationStepMs) {
        Map<String, Map<String, Object>> actorsById = new LinkedHashMap<>();
        Map<String, double[]> currentPositions = new LinkedHashMap<>();
        for (Map<String, Object> actor : actors(session)) {
            String actorId = String.valueOf(actor.get("id"));
            actorsById.put(actorId, actor); currentPositions.put(actorId, actorPosition(session, actor));
        }

        for (Map<String, Object> point : mapList(definition(session).get("deliveryPoints"))) {
            String pointId = String.valueOf(point.get("id"));
            String actorId = String.valueOf(point.get("actorId"));
            if (session.collectedDeliveryPointIds.contains(pointId) || !actorsById.containsKey(actorId)) continue;
            double[] current = currentPositions.get(actorId);
            double[] previous = session.previousActorPositions.getOrDefault(actorId, current);
            double[] target = coordinate(point.get("position"));
            boolean air = "AIR".equals(point.get("kind"));
            if (!AirspaceGeometry.segmentPassesPoint(previous, current, target,
                    number(point.get("triggerRadiusMeters"), air ? 20 : 14),
                    number(point.get("altitudeToleranceMeters"), air ? 18 : 0), air)) continue;
            long rewardMinor = point.get("rewardMinor") instanceof Number value ? value.longValue() : 0;
            Map<String, Object> applied = fleet.applyMissionTransaction(session.visitorHash,
                    "DELIVERY:" + session.id + ":E" + session.timelineEpoch + ":" + pointId, "DELIVERY_REWARD", rewardMinor,
                    session.id, pointId, actorId, session.simulationElapsedMs, ECONOMY_RULE_VERSION,
                    session.timelineEpoch,
                    Map.of("kind", point.get("kind"), "routeId", point.get("routeId"),
                            "routeProgress", point.get("routeProgress"), "visualTier", point.get("visualTier")));
            session.collectedDeliveryPointIds.add(pointId);
            broadcastDelta(session, "economy-delta", Map.of(
                    "economy", economyView(session), "transaction", applied.get("transaction")));
        }

        for (Map<String, Object> diamond : mapList(definition(session).get("rewardDiamonds"))) {
            String diamondId = String.valueOf(diamond.get("id"));
            String actorId = String.valueOf(diamond.get("actorId"));
            String volumeId = String.valueOf(diamond.get("linkedVolumeId"));
            Map<String, Object> selectedAction = session.airspaceActions.get(volumeId);
            if (session.collectedDiamondIds.contains(diamondId) || session.forfeitedDiamondIds.contains(diamondId)
                    || !actorsById.containsKey(actorId)
                    || !diamondActionEligible(diamond, actorId, selectedAction)) continue;
            double[] current = currentPositions.get(actorId);
            double[] previous = session.previousActorPositions.getOrDefault(actorId, current);
            double[] target = coordinate(diamond.get("position"));
            List<double[]> executableRoute = points(session, String.valueOf(actorsById.get(actorId).get("routeId")));
            if (!AirspaceGeometry.routeSectionPassesPoint(executableRoute, previous, current, target,
                    number(diamond.get("triggerRadiusMeters"), 14),
                    number(diamond.get("altitudeToleranceMeters"), 10), true)) continue;
            long rewardMinor = diamond.get("rewardMinor") instanceof Number value ? value.longValue() : 0;
            Map<String, Object> rewardMetadata = new LinkedHashMap<>();
            rewardMetadata.put("rewardType", "DIAMOND"); rewardMetadata.put("routeId", diamond.get("routeId"));
            rewardMetadata.put("challengeType", diamond.getOrDefault("challengeType", "AIRSPACE"));
            if (diamond.get("linkedVolumeId") != null) rewardMetadata.put("linkedVolumeId", diamond.get("linkedVolumeId"));
            if (diamond.get("requiredAction") != null) rewardMetadata.put("requiredAction", diamond.get("requiredAction"));
            rewardMetadata.put("routeProgress", diamond.get("routeProgress")); rewardMetadata.put("visualTier", diamond.get("visualTier"));
            Map<String, Object> applied = fleet.applyMissionTransaction(session.visitorHash,
                    "DIAMOND:" + session.id + ":E" + session.timelineEpoch + ":" + diamondId, "DIAMOND_REWARD", rewardMinor,
                    session.id, diamondId, actorId, session.simulationElapsedMs, ECONOMY_RULE_VERSION,
                    session.timelineEpoch,
                    rewardMetadata);
            session.collectedDiamondIds.add(diamondId);
            broadcastDelta(session, "economy-delta", Map.of(
                    "economy", economyView(session), "transaction", applied.get("transaction")));
        }

        Set<String> exposedKeys = new HashSet<>();
        Map<String, Object> airspace = castMap(definition(session).get("airspace"));
        for (Map<String, Object> actor : actorsById.values()) {
            if (!"UAV".equals(String.valueOf(actor.get("kind")))) continue;
            String actorId = String.valueOf(actor.get("id"));
            double[] current = currentPositions.get(actorId);
            double[] previous = session.previousActorPositions.getOrDefault(actorId, current);
            for (Map<String, Object> volume : mapList(airspace.get("volumes"))) {
                if (!AirspaceGeometry.blocking(volume)) continue;
                String volumeId = String.valueOf(volume.get("id"));
                String key = actorId + "|" + volumeId;
                double exposure = isAirspaceActive(session, volume)
                        ? AirspaceGeometry.segmentExposureFraction(previous, current, volume) : 0;
                if (exposure > 0) {
                    exposedKeys.add(key);
                    DemoSession.AirspaceIncursion incursion = session.activeAirspaceIncursions.get(key);
                    if (incursion == null) {
                        int sequence = session.airspaceIncursionSequences.merge(key, 1, Integer::sum);
                        String incursionId = "INC-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
                        incursion = new DemoSession.AirspaceIncursion(incursionId, volumeId, actorId, sequence,
                                Math.max(0, session.simulationElapsedMs - simulationStepMs), 0);
                        session.activeAirspaceIncursions.put(key, incursion);
                        jdbc.update("INSERT INTO demo_airspace_incursion(id,session_id,volume_id,actor_id,sequence_no,entry_simulation_ms,exposure_ms,timeline_epoch,status) VALUES(?,?,?,?,?,?,0,?,'ACTIVE')",
                                incursionId, session.id, volumeId, actorId, sequence, incursion.entrySimulationMs, session.timelineEpoch);
                        if ("ALTITUDE_CORRIDOR".equals(volume.get("ruleType"))) {
                            mapList(definition(session).get("rewardDiamonds")).stream()
                                    .filter(diamond -> volumeId.equals(String.valueOf(diamond.get("linkedVolumeId"))))
                                    .map(diamond -> String.valueOf(diamond.get("id")))
                                    .forEach(session.forfeitedDiamondIds::add);
                        }
                        assessFixedIncursion(session, volume, incursion);
                    }
                    incursion.exposureMs += Math.max(1, Math.round(simulationStepMs * exposure));
                    jdbc.update("UPDATE demo_airspace_incursion SET exposure_ms=? WHERE id=? AND status='ACTIVE'",
                            incursion.exposureMs, incursion.id);
                }
            }
        }
        for (Map.Entry<String, DemoSession.AirspaceIncursion> entry : new ArrayList<>(session.activeAirspaceIncursions.entrySet())) {
            if (!exposedKeys.contains(entry.getKey())) settleIncursion(session, entry.getKey(), entry.getValue());
        }
        currentPositions.forEach((actorId, point) -> session.previousActorPositions.put(actorId, point.clone()));
    }

    private void settleAllIncursions(DemoSession session) {
        for (Map.Entry<String, DemoSession.AirspaceIncursion> entry : new ArrayList<>(session.activeAirspaceIncursions.entrySet()))
            settleIncursion(session, entry.getKey(), entry.getValue());
    }

    private void settleIncursion(DemoSession session, String key, DemoSession.AirspaceIncursion incursion) {
        Map<String, Object> volume = mapList(castMap(definition(session).get("airspace")).get("volumes")).stream()
                .filter(item -> incursion.volumeId.equals(String.valueOf(item.get("id")))).findFirst().orElse(Map.of());
        Map<String, Object> policy = penaltyPolicy(volume);
        if ("FIXED_ON_ENTRY".equals(policy.get("type"))) {
            jdbc.update("UPDATE demo_airspace_incursion SET exit_simulation_ms=?,status='SETTLED' WHERE id=? AND status='ACTIVE'",
                    session.simulationElapsedMs, incursion.id);
            session.activeAirspaceIncursions.remove(key);
            return;
        }
        long seconds = Math.max(1, (long) Math.ceil(incursion.exposureMs / 1000.0));
        long base = policy.get("baseMinor") instanceof Number value ? value.longValue() : AIRSPACE_FINE_BASE_MINOR;
        long perSecond = policy.get("perSecondMinor") instanceof Number value ? value.longValue() : AIRSPACE_FINE_PER_SECOND_MINOR;
        long maximum = policy.get("maximumMinor") instanceof Number value ? value.longValue() : AIRSPACE_FINE_MAX_MINOR;
        long assessed = durationFineMinor(policy, incursion.exposureMs);
        String ledgerKey = "AIRSPACE_FINE:" + session.id + ":E" + session.timelineEpoch + ":" + incursion.volumeId + ":" + incursion.sequence;
        Map<String, Object> applied = fleet.applyMissionTransaction(session.visitorHash, ledgerKey, "AIRSPACE_FINE",
                -assessed, session.id, incursion.volumeId, incursion.actorId, session.simulationElapsedMs,
                ECONOMY_RULE_VERSION, session.timelineEpoch, Map.of("incursionId", incursion.id, "durationMs", incursion.exposureMs,
                        "durationSeconds", seconds, "penaltyType", "DURATION", "baseMinor", base,
                        "perSecondMinor", perSecond, "maximumMinor", maximum));
        Map<String, Object> transaction = castMap(applied.get("transaction"));
        long charged = Math.abs(transaction.get("amountMinor") instanceof Number value ? value.longValue() : 0);
        jdbc.update("UPDATE demo_airspace_incursion SET exit_simulation_ms=?,status='SETTLED',assessed_amount_minor=?,charged_amount_minor=?,ledger_entry_key=? WHERE id=? AND status='ACTIVE'",
                session.simulationElapsedMs, assessed, charged, ledgerKey, incursion.id);
        session.activeAirspaceIncursions.remove(key);
        broadcastDelta(session, "economy-delta", Map.of("economy", economyView(session), "transaction", transaction));
    }

    private void assessFixedIncursion(DemoSession session, Map<String, Object> volume,
                                      DemoSession.AirspaceIncursion incursion) {
        Map<String, Object> policy = penaltyPolicy(volume);
        if (!"FIXED_ON_ENTRY".equals(policy.get("type"))) return;
        long assessed = fixedFineForEntry(volume, incursion.sequence);
        if (assessed <= 0) return;
        String ledgerKey = "AIRSPACE_FINE:" + session.id + ":E" + session.timelineEpoch + ":" + incursion.volumeId + ":" + incursion.sequence;
        Map<String, Object> applied = fleet.applyMissionTransaction(session.visitorHash, ledgerKey, "AIRSPACE_FINE",
                -assessed, session.id, incursion.volumeId, incursion.actorId, session.simulationElapsedMs,
                ECONOMY_RULE_VERSION, session.timelineEpoch, Map.of("incursionId", incursion.id, "penaltyType", "FIXED_ON_ENTRY",
                        "amountMinor", assessed, "repeatMode", policy.getOrDefault("repeatMode", "PER_INCURSION")));
        Map<String, Object> transaction = castMap(applied.get("transaction"));
        long charged = Math.abs(transaction.get("amountMinor") instanceof Number value ? value.longValue() : 0);
        jdbc.update("UPDATE demo_airspace_incursion SET assessed_amount_minor=?,charged_amount_minor=?,ledger_entry_key=? WHERE id=? AND status='ACTIVE'",
                assessed, charged, ledgerKey, incursion.id);
        broadcastDelta(session, "economy-delta", Map.of("economy", economyView(session), "transaction", transaction));
    }

    private static Map<String, Object> penaltyPolicy(Map<String, Object> volume) {
        if (volume.get("penaltyPolicy") instanceof Map<?, ?> raw) return castMap(raw);
        return switch (String.valueOf(volume.get("ruleType"))) {
            case "ALTITUDE_CORRIDOR" -> Map.of("type", "FIXED_ON_ENTRY", "amountMinor", CORRIDOR_FIXED_FINE_MINOR,
                    "repeatMode", "ONCE_PER_VOLUME");
            case "RISK_AIRSPACE" -> Map.of("type", "NONE");
            // Frozen pre-2.0 tasks have no per-volume policy; retain their
            // former duration fine instead of silently re-pricing history.
            default -> Map.of("type", "DURATION", "baseMinor", AIRSPACE_FINE_BASE_MINOR,
                    "perSecondMinor", AIRSPACE_FINE_PER_SECOND_MINOR, "maximumMinor", AIRSPACE_FINE_MAX_MINOR);
        };
    }

    static long fixedFineForEntry(Map<String, Object> volume, int sequence) {
        Map<String, Object> policy = penaltyPolicy(volume);
        if (!"FIXED_ON_ENTRY".equals(policy.get("type"))
                || ("ONCE_PER_VOLUME".equals(policy.get("repeatMode")) && sequence > 1)) return 0;
        return policy.get("amountMinor") instanceof Number value ? value.longValue()
                : "ALTITUDE_CORRIDOR".equals(volume.get("ruleType")) ? CORRIDOR_FIXED_FINE_MINOR : RED_FIXED_FINE_MINOR;
    }

    static long durationFineMinor(Map<String, Object> policy, long exposureMs) {
        long seconds = Math.max(1, (long) Math.ceil(Math.max(0, exposureMs) / 1000.0));
        long base = policy.get("baseMinor") instanceof Number value ? value.longValue() : AIRSPACE_FINE_BASE_MINOR;
        long perSecond = policy.get("perSecondMinor") instanceof Number value ? value.longValue() : AIRSPACE_FINE_PER_SECOND_MINOR;
        long maximum = policy.get("maximumMinor") instanceof Number value ? value.longValue() : AIRSPACE_FINE_MAX_MINOR;
        return Math.min(maximum, base + seconds * perSecond);
    }

    private void awardTimelinessReward(DemoSession session) {
        Map<String, Object> quote = castMap(definition(session).get("economyQuote"));
        long baseGroundRewardMinor = quote.get("baseGroundRewardMinor") instanceof Number value ? value.longValue() : 0;
        if (baseGroundRewardMinor <= 0 || session.groundServiceElapsedMs <= 0) return;
        double referenceSeconds = Math.max(1, number(quote.get("referenceGroundSeconds"), 1));
        double actualSeconds = session.groundServiceElapsedMs / 1000.0;
        double factor = TaskInstanceService.timelinessFactor(referenceSeconds, actualSeconds);
        long rewardMinor = timelinessRewardMinor(baseGroundRewardMinor, referenceSeconds, actualSeconds);
        String actorId = actors(session).stream().filter(actor -> "VEHICLE".equals(String.valueOf(actor.get("kind"))))
                .map(actor -> String.valueOf(actor.get("id"))).findFirst().orElse(null);
        Map<String, Object> applied = fleet.applyMissionTransaction(session.visitorHash,
                "TIMELINESS:" + session.id + ":E" + session.timelineEpoch, "TIMELINESS_REWARD", rewardMinor,
                session.id, "GROUND-COMPLETION", actorId, session.simulationElapsedMs, ECONOMY_RULE_VERSION,
                session.timelineEpoch,
                Map.of("baseGroundRewardMinor", baseGroundRewardMinor, "referenceGroundSeconds", referenceSeconds,
                        "actualGroundSeconds", actualSeconds, "factor", factor));
        if (!Boolean.TRUE.equals(applied.get("replayed"))) {
            broadcastDelta(session, "economy-delta", Map.of(
                    "economy", economyView(session), "transaction", applied.get("transaction")));
        }
    }

    private Map<String, Object> economyView(DemoSession session) {
        List<Map<String, Object>> transactions = fleet.missionTransactions(session.visitorHash, session.id);
        long coinRewards = transactions.stream().filter(item -> "DELIVERY_REWARD".equals(item.get("entryType")))
                .mapToLong(item -> ((Number) item.getOrDefault("amountMinor", 0)).longValue()).sum();
        long groundCargoRewards = transactions.stream().filter(item -> "DELIVERY_REWARD".equals(item.get("entryType")))
                .filter(item -> "GROUND".equals(castMap(item.get("metadata")).get("kind")))
                .mapToLong(item -> ((Number) item.getOrDefault("amountMinor", 0)).longValue()).sum();
        long airCoinRewards = coinRewards - groundCargoRewards;
        long timelinessRewards = transactions.stream().filter(item -> "TIMELINESS_REWARD".equals(item.get("entryType")))
                .mapToLong(item -> ((Number) item.getOrDefault("amountMinor", 0)).longValue()).sum();
        long diamondRewards = transactions.stream().filter(item -> "DIAMOND_REWARD".equals(item.get("entryType")))
                .mapToLong(item -> ((Number) item.getOrDefault("amountMinor", 0)).longValue()).sum();
        long rewards = coinRewards + timelinessRewards + diamondRewards;
        long assessedFine = transactions.stream().filter(item -> "AIRSPACE_FINE".equals(item.get("entryType")))
                .mapToLong(item -> Math.abs(((Number) item.getOrDefault("assessedAmountMinor", 0)).longValue())).sum();
        long chargedFine = transactions.stream().filter(item -> "AIRSPACE_FINE".equals(item.get("entryType")))
                .mapToLong(item -> Math.abs(((Number) item.getOrDefault("amountMinor", 0)).longValue())).sum();
        transactions.stream().filter(item -> "DELIVERY_REWARD".equals(item.get("entryType")))
                .map(item -> String.valueOf(item.get("referenceId"))).filter(value -> !value.isBlank())
                .forEach(session.collectedDeliveryPointIds::add);
        transactions.stream().filter(item -> "DIAMOND_REWARD".equals(item.get("entryType")))
                .map(item -> String.valueOf(item.get("referenceId"))).filter(value -> !value.isBlank())
                .forEach(session.collectedDiamondIds::add);
        Set<String> violatedCorridors = transactions.stream().filter(item -> "AIRSPACE_FINE".equals(item.get("entryType")))
                .map(item -> String.valueOf(item.get("referenceId"))).filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.toSet());
        mapList(definition(session).get("rewardDiamonds")).stream()
                .filter(diamond -> "TRANSIT_CORRIDOR".equals(diamond.get("requiredAction")))
                .filter(diamond -> violatedCorridors.contains(String.valueOf(diamond.get("linkedVolumeId"))))
                .map(diamond -> String.valueOf(diamond.get("id"))).forEach(session.forfeitedDiamondIds::add);
        List<Map<String, Object>> active = session.activeAirspaceIncursions.values().stream()
                .map(incursion -> activeIncursionView(session, incursion)).toList();
        Map<String, Object> quote = castMap(definition(session).get("economyQuote"));
        Map<String, Object> company = fleet.companyView(session.visitorHash);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("currency", company.getOrDefault("currency", "CNY")); value.put("balanceMinor", company.get("balanceMinor"));
        value.put("companyUpdatedAt", company.get("updatedAt"));
        value.put("grossRewardMinor", quote.getOrDefault("grossRewardMinor", 0)); value.put("collectedRewardMinor", rewards);
        value.put("collectedCoinMinor", coinRewards); value.put("collectedDiamondMinor", diamondRewards);
        value.put("groundCargoRewardMinor", groundCargoRewards); value.put("airCoinRewardMinor", airCoinRewards);
        value.put("timelinessRewardMinor", timelinessRewards);
        value.put("groundServiceElapsedSeconds", session.groundServiceElapsedMs / 1000.0);
        value.put("penaltyAssessedMinor", assessedFine); value.put("penaltyChargedMinor", chargedFine);
        value.put("netMinor", rewards - chargedFine);
        value.put("collectedDeliveryPointIds", new ArrayList<>(session.collectedDeliveryPointIds));
        value.put("collectedDiamondIds", new ArrayList<>(session.collectedDiamondIds));
        value.put("forfeitedDiamondIds", new ArrayList<>(session.forfeitedDiamondIds));
        value.put("activeIncursions", active);
        value.put("finePolicy", Map.of("baseMinor", AIRSPACE_FINE_BASE_MINOR, "perSecondMinor", AIRSPACE_FINE_PER_SECOND_MINOR,
                "maximumPerIncursionMinor", AIRSPACE_FINE_MAX_MINOR));
        value.put("recentTransactions", transactions.stream().skip(Math.max(0, transactions.size() - 20L)).toList());
        return value;
    }

    private Map<String, Object> activeIncursionView(DemoSession session, DemoSession.AirspaceIncursion incursion) {
        Map<String, Object> volume = mapList(castMap(definition(session).get("airspace")).get("volumes")).stream()
                .filter(item -> incursion.volumeId.equals(String.valueOf(item.get("id")))).findFirst().orElse(Map.of());
        Map<String, Object> policy = penaltyPolicy(volume);
        long estimated;
        if ("FIXED_ON_ENTRY".equals(policy.get("type")))
            estimated = policy.get("amountMinor") instanceof Number value ? value.longValue() : 0;
        else {
            long seconds = Math.max(1, (long) Math.ceil(incursion.exposureMs / 1000.0));
            long base = policy.get("baseMinor") instanceof Number value ? value.longValue() : AIRSPACE_FINE_BASE_MINOR;
            long perSecond = policy.get("perSecondMinor") instanceof Number value ? value.longValue() : AIRSPACE_FINE_PER_SECOND_MINOR;
            long maximum = policy.get("maximumMinor") instanceof Number value ? value.longValue() : AIRSPACE_FINE_MAX_MINOR;
            estimated = Math.min(maximum, base + seconds * perSecond);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", incursion.id); view.put("volumeId", incursion.volumeId); view.put("actorId", incursion.actorId);
        view.put("entrySimulationMs", incursion.entrySimulationMs); view.put("exposureMs", incursion.exposureMs);
        view.put("estimatedFineMinor", estimated); view.put("penaltyType", policy.get("type"));
        return view;
    }

    @SuppressWarnings("unchecked")
    private double[] actorPosition(DemoSession session, Map<String, Object> actor) {
        String deviceId = String.valueOf(actor.get("id"));
        boolean drone = "UAV".equals(String.valueOf(actor.get("kind")));
        String routeId = String.valueOf(actor.get("routeId"));
        Map<String, Object> actorRoute = route(session, routeId);
        String carrierActorId = nullableString(actor.get("carrierActorId"));
        Map<String, Object> carrier = drone ? actor(session, carrierActorId) : actor;
        String carrierRouteId = String.valueOf(carrier.get("routeId"));
        List<double[]> ground = points(session, carrierRouteId);
        List<double[]> air = drone ? points(session, routeId) : List.of();
        double progress = session.progress / 100.0;
        double vehicleProgress = session.routeProgress.getOrDefault(drone ? carrierActorId : deviceId,
                MissionMath.clamp(progress / .9, 0, 1) * 100);
        double airProgress = drone ? session.routeProgress.getOrDefault(deviceId, 0.0) : 0;
        String uavState = drone ? session.uavStates.getOrDefault(deviceId, session.taskInstanceId == null ? "BASELINE" : "ON_CARRIER") : "";
        boolean carried = drone && ("ON_CARRIER".equals(uavState) || "RECOVERED".equals(uavState)) && !session.independentAirRoute;
        if (session.taskInstanceId != null && !drone) return MissionMath.sample(ground, vehicleProgress / 100.0);
        if (session.taskInstanceId != null && carried) return addHeight(MissionMath.sample(ground, vehicleProgress / 100.0), 2.0);
        if (session.taskInstanceId != null && airProgress < 5)
            return MissionMath.smooth(recoveryPoint(actorRoute, "launchPoint"), air.get(0), airProgress / 5, 8);
        if (session.taskInstanceId != null && airProgress < 95) return MissionMath.sample(air, (airProgress - 5) / 90.0);
        if (session.taskInstanceId != null) return MissionMath.smooth(air.get(air.size() - 1), recovery(actorRoute), (airProgress - 95) / 5, 6);
        if (!drone) return MissionMath.sample(ground, vehicleProgress / 100.0);
        if (session.independentAirRoute && progress < .90) return MissionMath.sample(air, progress / .90);
        if (session.independentAirRoute) return recovery(actorRoute);
        if (progress < .18) return addHeight(MissionMath.sample(ground, progress / .9), 2.0);
        if (progress < .24) return MissionMath.smooth(addHeight(MissionMath.sample(ground, .18), 2.0), air.get(0), (progress - .18) / .06, 8);
        if (progress < .78) return MissionMath.sample(air, (progress - .24) / .54);
        if (progress < .90) return MissionMath.smooth(air.get(air.size() - 1), recovery(actorRoute), (progress - .78) / .12, 6);
        return recovery(actorRoute);
    }

    @SuppressWarnings("unchecked")
    private void publishTelemetry(DemoSession session, Map<String, Object> actor, Instant now) {
        String deviceId = String.valueOf(actor.get("id"));
        boolean drone = "UAV".equals(String.valueOf(actor.get("kind")));
        String routeId = String.valueOf(actor.get("routeId"));
        Map<String, Object> actorRoute = route(session, routeId);
        String carrierActorId = nullableString(actor.get("carrierActorId"));
        Map<String, Object> carrier = drone ? actor(session, carrierActorId) : actor;
        String carrierRouteId = String.valueOf(carrier.get("routeId"));
        Map<String, Object> vehicleRoute = route(session, carrierRouteId);
        List<double[]> ground = points(session, carrierRouteId);
        Map<String, Object> airRoute = drone ? actorRoute : null;
        List<double[]> air = drone ? points(session, routeId) : List.of();
        double p = session.progress / 100.0;
        double vehicleRouteProgress = session.routeProgress.getOrDefault(drone ? carrierActorId : deviceId, MissionMath.clamp(p / .9, 0, 1) * 100);
        double airRouteProgress = drone ? session.routeProgress.getOrDefault(deviceId, 0.0) : 0;
        String uavState = drone ? session.uavStates.getOrDefault(deviceId, session.taskInstanceId == null ? "BASELINE" : "ON_CARRIER") : "";
        boolean independentlyRouted = drone && session.independentAirRoute;
        boolean carriedState = ("ON_CARRIER".equals(uavState) || "RECOVERED".equals(uavState)) && !independentlyRouted;
        double[] point = actorPosition(session, actor);
        double previousVehicleProgress = Math.max(0, vehicleRouteProgress - .2);
        double[] previous = drone && session.taskInstanceId != null && !carriedState
                ? MissionMath.sample(air, MissionMath.clamp((airRouteProgress - 5.2) / 90.0, 0, 1))
                : drone && independentlyRouted
                    ? MissionMath.sample(air, MissionMath.clamp(p / .90 - .002, 0, 1))
                : MissionMath.sample(ground, previousVehicleProgress / 100.0);
        double direction = MissionMath.bearing(previous, point);
        double deviation = 0;
        double coverage = drone ? (session.taskInstanceId == null ? MissionMath.clamp((session.progress - 24) / 54 * 100, 0, 100) : MissionMath.clamp((airRouteProgress - 8) / 72 * 100, 0, 100)) : 0;
        Map<String, Object> simulation = actor.get("simulation") instanceof Map<?, ?> value ? (Map<String, Object>) value : Map.of();
        List<Number> weakRange = simulation.get("linkWeakProgress") instanceof List<?> values ? (List<Number>) values : List.of();
        boolean weakLink = weakRange.size() == 2 && session.progress >= weakRange.get(0).doubleValue() && session.progress <= weakRange.get(1).doubleValue();
        double link = weakLink ? number(simulation.get("linkWeakValue"), 64) : 96 - deviation;
        Map<String, Object> metrics = new LinkedHashMap<>();
        Map<String, Object> trafficStop = drone ? null : session.trafficStops.get(deviceId);
        boolean vehicleFinished = !drone && vehicleRouteProgress >= 100;
        boolean droneStopped = drone && session.taskInstanceId != null && ("ON_CARRIER".equals(uavState) || "RECOVERED".equals(uavState) || airRouteProgress >= 100);
        metrics.put("speed", trafficStop != null || vehicleFinished || droneStopped
                || (!drone && session.groundBatteryDepleted) || (drone && session.airBatteryDepleted)
                || session.progress >= 100 ? 0 : Number.class.cast(drone ? airRoute.get("nominalSpeedKph") : actorRoute.get("nominalSpeedKph")).doubleValue());
        metrics.put("direction", direction); metrics.put("missionPhase", session.missionPhase); metrics.put("routeProgress", drone && session.taskInstanceId != null ? airRouteProgress : session.progress);
        if (!drone) {
            metrics.put("routeProgress", vehicleRouteProgress);
            boolean waitingForUav = trafficStop != null && "RENDEZVOUS_COORDINATION".equals(trafficStop.get("source"));
            metrics.put("trafficRuleState", trafficStop == null ? "MOVING" : waitingForUav ? "WAITING_FOR_UAV" : "WAITING_FOR_SIGNAL");
            if (trafficStop != null) {
                if (trafficStop.get("linkId") != null) metrics.put("trafficLightLinkId", trafficStop.get("linkId"));
                if (trafficStop.get("lampStatus") != null) metrics.put("trafficLightStatus", trafficStop.get("lampStatus"));
                if (trafficStop.get("countdown") != null) metrics.put("trafficLightCountdown", trafficStop.get("countdown"));
                metrics.put("stopReason", trafficStop.get("reason"));
            }
        }
        metrics.put("routeDeviationMeters", deviation); metrics.put("battery", drone
                ? session.taskInstanceId == null ? number(actor.get("initialBattery"), 98) - session.progress * .22 : session.airBatteryPercent
                : session.taskInstanceId == null ? number(actor.get("initialBattery"), 92) : session.groundBatteryPercent);
        metrics.put("linkQuality", link); metrics.put("deliveryProgressPercent", coverage); metrics.put("deliveryActive", drone && ("SERVICING".equals(uavState) || "DELIVERING".equals(session.missionPhase)));
        if (drone) {
            metrics.put("boundVehicleId", independentlyRouted ? null : carrierActorId);
            metrics.put("routeMode", independentlyRouted ? "INDEPENDENT" : "CARRIER_COORDINATED");
            if (session.taskInstanceId != null) metrics.put("uavState", uavState);
            metrics.put("energyMultiplier", session.airEnergyMultipliers.getOrDefault(deviceId, 1.0));
            metrics.put("riskVolumeId", session.airRiskVolumeIds.get(deviceId));
            metrics.put("extraBatteryUsedPercent", session.airExtraBatteryUsedPercent.getOrDefault(deviceId, 0.0));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", session.id); payload.put("deviceId", deviceId); payload.put("deviceType", actor.get("deviceType"));
        payload.put("deviceName", actor.get("name")); payload.put("actorKind", actor.get("kind")); payload.put("actorRole", actor.get("role"));
        payload.put("capabilities", actor.getOrDefault("capabilities", List.of()));
        payload.put("formationId", actor.get("formationId")); payload.put("assignmentId", actor.get("assignmentId"));
        payload.put("commandCapabilities", actor.getOrDefault("commandCapabilities", List.of())); payload.put("commandTransport", unavailableCommandTransport());
        payload.put("eventTime", now.toString()); payload.put("longitude", point[0]); payload.put("latitude", point[1]); payload.put("altitude", point[2]); payload.put("metrics", metrics);
        try { mqtt.publish("urban-air-ground/sessions/" + session.id + "/" + payload.get("deviceType") + "/" + deviceId + "/telemetry", mapper.writeValueAsString(payload)); }
        catch (Exception error) { finish(session, "FAILED"); }
    }

    @SuppressWarnings("unchecked")
    private void acceptTelemetry(String topic, String json) {
        try {
            Map<String, Object> payload = mapper.readValue(json, new TypeReference<>() {});
            DemoSession session = sessions.get(String.valueOf(payload.get("sessionId")));
            if (session == null || !"RUNNING".equals(session.status)) return;
            String deviceId = String.valueOf(payload.get("deviceId")); String deviceType = String.valueOf(payload.get("deviceType"));
            Map<String, Object> metrics = (Map<String, Object>) payload.get("metrics");
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("longitude", payload.get("longitude")); point.put("latitude", payload.get("latitude")); point.put("altitude", payload.get("altitude")); point.put("eventTime", payload.get("eventTime")); point.put("metrics", metrics);
            point.put("simulationTimeMs", session.simulationElapsedMs);
            session.tracks.computeIfAbsent(deviceId, ignored -> Collections.synchronizedList(new ArrayList<>())).add(point);
            if (session.tracks.get(deviceId).size() > 500) session.tracks.get(deviceId).remove(0);
            Map<String, Object> device = new LinkedHashMap<>();
            device.put("deviceId", deviceId); device.put("deviceType", deviceType); device.put("deviceName", payload.get("deviceName"));
            copyActorMetadata(payload, device);
            device.put("longitude", payload.get("longitude")); device.put("latitude", payload.get("latitude")); device.put("altitude", payload.get("altitude")); device.put("sensorData", metrics);
            session.devices.put(deviceId, device);
            jdbc.update("INSERT INTO demo_telemetry(session_id,device_id,device_type,event_time,simulation_time_ms,timeline_epoch,longitude,latitude,altitude,heading,metrics_json) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    session.id, deviceId, deviceType, java.sql.Timestamp.from(Instant.parse(String.valueOf(payload.get("eventTime")))), session.simulationElapsedMs, session.timelineEpoch,
                    payload.get("longitude"), payload.get("latitude"), payload.get("altitude"), metrics.get("direction"), mapper.writeValueAsString(metrics));
            broadcastDelta(session, "track-delta", Map.of("device", device, "point", point));
        } catch (Exception ignored) {}
    }

    private Map<String, Object> snapshot(DemoSession session, boolean includeTracks) {
        Map<String, Object> mission = definition(session);
        mission.put("state", session.status); mission.put("status", session.status); mission.put("progress", session.progress); mission.put("missionPhase", session.missionPhase); mission.put("simulationId", session.id); mission.put("routeVersion", mission.get("version"));
        if (session.terminalReason != null) mission.put("terminalReason", session.terminalReason);
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) mission.get("routes");
        routes.forEach(route -> {
            route.put("actualPoints", includeTracks ? safeTrackCopy(session.tracks.get(String.valueOf(route.get("deviceId")))) : List.of());
            List<List<Number>> override = session.airRouteOverrides.get(String.valueOf(route.get("routeId")));
            if (override != null) { route.put("effectivePoints", override); route.put("runtimeRouteOverride", true); }
        });
        @SuppressWarnings("unchecked") List<Map<String, Object>> events = (List<Map<String, Object>>) mission.get("events");
        events.forEach(event -> event.put("reached", "COMPLETE".equals(String.valueOf(event.get("type")))
                ? session.progress >= Number.class.cast(event.get("progress")).doubleValue()
                : event.get("simulationTimeSeconds") instanceof Number seconds
                    ? session.simulationElapsedMs >= Math.round(seconds.doubleValue() * 1000)
                    : session.progress >= Number.class.cast(event.get("progress")).doubleValue()));
        addTrafficLightView(mission, session);
        mission.put("airspace", airspaceRuntime(session, mission));
        if (session.taskInstanceId != null) mission.put("economy", economyView(session));
        mission.put("timeline", timelineView(session));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("revision", session.revision.get()); result.put("session", session.publicView()); result.put("mission", mission);
        result.put("devices", session.devices.isEmpty() ? standbyDevices(session) : session.deviceList());
        result.put("signals", signalViews(session, events));
        return result;
    }

    private List<Map<String, Object>> standbyDevices(DemoSession session) {
        List<Map<String, Object>> result = new ArrayList<>();
        List<Map<String, Object>> sourceActors = session == null
                ? catalog.actors(catalog.definitionId(), catalog.definitionVersion())
                : actors(session);
        for (Map<String, Object> actor : sourceActors) {
            String actorId = String.valueOf(actor.get("id"));
            boolean drone = "UAV".equals(String.valueOf(actor.get("kind")));
            String anchorActorId = drone ? String.valueOf(actor.get("carrierActorId")) : actorId;
            Map<String, Object> anchor = session == null
                    ? catalog.actor(catalog.definitionId(), catalog.definitionVersion(), anchorActorId)
                    : actor(session, anchorActorId);
            double[] point = session == null
                    ? catalog.points(catalog.definitionId(), catalog.definitionVersion(), String.valueOf(anchor.get("routeId"))).get(0).clone()
                    : points(session, String.valueOf(anchor.get("routeId"))).get(0).clone();
            if (drone) point[2] += 2;
            Map<String, Object> metrics = new LinkedHashMap<>(); metrics.put("missionPhase", "DOCKED"); metrics.put("routeProgress", 0); metrics.put("routeDeviationMeters", 0); metrics.put("battery", !drone && session != null && session.taskInstanceId != null ? session.groundBatteryPercent : number(actor.get("initialBattery"), drone ? 98 : 92)); metrics.put("linkQuality", 100); metrics.put("deliveryProgressPercent", 0);
            Map<String, Object> device = new LinkedHashMap<>(); device.put("deviceId", actorId); device.put("deviceType", actor.get("deviceType")); device.put("deviceName", actor.get("name"));
            copyActorMetadata(actor, device); device.put("commandTransport", unavailableCommandTransport());
            device.put("longitude", point[0]); device.put("latitude", point[1]); device.put("altitude", point[2]); device.put("sensorData", metrics); result.add(device);
        }
        return result;
    }

    private DemoSession owned(String id, String visitorHash) {
        DemoSession session = sessions.get(id);
        if (session == null) throw new DemoException(HttpStatus.NOT_FOUND, "任务不存在或已清理");
        if (!session.visitorHash.equals(visitorHash)) throw new DemoException(HttpStatus.FORBIDDEN, "无权访问其他访客的任务");
        return session;
    }

    private void touch(DemoSession session) { session.lastSeenAt = Instant.now(); jdbc.update("UPDATE demo_session SET last_seen_at=NOW(3) WHERE id=?", session.id); }
    private void start(DemoSession session) { session.status = "RUNNING"; session.startedAt = Instant.now(); session.queuePosition = 0; }
    private void finish(DemoSession session, String status) {
        synchronized (lock) {
            if (TERMINAL.contains(session.status) && !session.status.equals(status)) return;
            if (session.taskInstanceId != null && !session.activeAirspaceIncursions.isEmpty()) {
                try { settleAllIncursions(session); } catch (Exception ignored) {}
            }
            session.status = status; queue.remove(session.id);
            jdbc.update("UPDATE demo_session SET status=?,completed_at=NOW(3),queue_position=NULL,ground_service_elapsed_ms=?,terminal_reason=? WHERE id=?",
                    status, session.groundServiceElapsedMs, session.terminalReason, session.id);
            if (session.groundAssetId != null) fleet.releaseRunBinding(session.visitorHash, session.id);
            broadcastSnapshot(session, "session-end"); promoteQueue();
        }
    }
    private void promoteQueue() {
        synchronized (lock) {
            long active = sessions.values().stream().filter(item -> "RUNNING".equals(item.status)).count();
            while (active < maxActive && !queue.isEmpty()) { DemoSession next = sessions.get(queue.removeFirst()); if (next == null || !"QUEUED".equals(next.status)) continue; start(next); jdbc.update("UPDATE demo_session SET status='RUNNING',started_at=NOW(3),queue_position=NULL WHERE id=?", next.id); active++; broadcastSnapshot(next, "snapshot"); }
            int position = 1; for (String id : queue) { DemoSession queued = sessions.get(id); if (queued != null) { queued.queuePosition = position; jdbc.update("UPDATE demo_session SET queue_position=? WHERE id=?", position++, id); broadcastSnapshot(queued, "snapshot"); } }
        }
    }
    private void broadcastMissionDelta(DemoSession session) {
        Map<String, Object> mission = new LinkedHashMap<>();
        mission.put("state", session.status); mission.put("status", session.status); mission.put("progress", session.progress);
        mission.put("missionPhase", session.missionPhase); mission.put("simulationId", session.id);
        if (session.terminalReason != null) mission.put("terminalReason", session.terminalReason);
        addTrafficLightView(mission, session);
        mission.put("airspace", airspaceRuntime(session, definition(session)));
        mission.put("economy", economyView(session));
        mission.put("timeline", timelineView(session));
        broadcastDelta(session, "mission-delta", Map.of("session", session.publicView(), "mission", mission,
                "signals", signalViews(session, events(session))));
    }
    private void broadcastSnapshot(DemoSession session, String event) {
        long revision = session.revision.incrementAndGet();
        for (SseEmitter emitter : emitters.getOrDefault(session.id, new CopyOnWriteArrayList<>()))
            if (!send(emitter, event, snapshot(session, true), revision)) removeEmitter(session.id, emitter);
    }
    private void broadcastDelta(DemoSession session, String event, Map<String, Object> data) {
        long revision = session.revision.incrementAndGet();
        Map<String, Object> payload = new LinkedHashMap<>(data); payload.put("revision", revision);
        session.recordDelta(revision, event, payload);
        for (SseEmitter emitter : emitters.getOrDefault(session.id, new CopyOnWriteArrayList<>()))
            if (!send(emitter, event, payload, revision)) removeEmitter(session.id, emitter);
    }
    private void removeEmitter(String sessionId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> listeners = emitters.get(sessionId);
        if (listeners == null) return;
        listeners.remove(emitter);
        if (listeners.isEmpty() && emitters.remove(sessionId, listeners)) {
            // Start the disconnect grace period when the last stream actually
            // closes. Otherwise the regular four-minute SSE renewal can expire
            // a healthy long-running mission before the browser reconnects.
            DemoSession session = sessions.get(sessionId);
            if (session != null && "RUNNING".equals(session.status)) session.lastSeenAt = Instant.now();
        }
    }
    private boolean send(SseEmitter emitter, String event, Object data, long revision) { try { emitter.send(SseEmitter.event().id(String.valueOf(revision)).name(event).data(data)); if ("session-end".equals(event)) emitter.complete(); return true; } catch (Exception error) { return false; } }
    private void checkRate(String source) { Deque<Instant> starts = startsBySource.computeIfAbsent(source, ignored -> new ConcurrentLinkedDeque<>()); Instant cutoff = Instant.now().minusSeconds(600); while (!starts.isEmpty() && starts.peekFirst().isBefore(cutoff)) starts.removeFirst(); if (starts.size() >= 3) throw new DemoException(HttpStatus.TOO_MANY_REQUESTS, "同一来源10分钟内最多创建3次任务"); starts.addLast(Instant.now()); }
    private void cleanupRateLimits(Instant now) { startsBySource.entrySet().removeIf(entry -> entry.getValue().isEmpty() || entry.getValue().peekLast().isBefore(now.minusSeconds(600))); }
    private void restoreRecentSessions() {
        jdbc.query("SELECT s.* FROM demo_session s JOIN (SELECT visitor_hash,MAX(created_at) newest FROM demo_session WHERE created_at > NOW() - INTERVAL 24 HOUR GROUP BY visitor_hash) latest ON latest.visitor_hash=s.visitor_hash AND latest.newest=s.created_at",
                result -> {
                    String taskInstanceId = result.getString("task_instance_id");
                    Map<String, Object> plan = taskInstanceId == null ? null : taskInstances.loadPlan(taskInstanceId);
                    DemoSession session = new DemoSession(result.getString("id"), result.getString("visitor_hash"), result.getString("status"),
                            result.getString("definition_id"), result.getString("definition_version"), taskInstanceId, result.getInt("run_no"),
                            result.getString("engine_version"), plan, result.getTimestamp("created_at").toInstant());
                    session.timeScale = result.getDouble("time_scale");
                    session.progress = result.getDouble("progress");
                    session.missionPhase = result.getString("mission_phase");
                    session.simulationElapsedMs = result.getLong("simulation_elapsed_ms");
                    session.groundServiceElapsedMs = result.getLong("ground_service_elapsed_ms");
                    session.timelineEpoch = result.getInt("timeline_epoch");
                    session.terminalReason = result.getString("terminal_reason");
                    session.queuePosition = result.getInt("queue_position");
                    if (result.wasNull()) session.queuePosition = 0;
                    if (result.getTimestamp("started_at") != null) session.startedAt = result.getTimestamp("started_at").toInstant();
                    if (result.getTimestamp("last_seen_at") != null) session.lastSeenAt = result.getTimestamp("last_seen_at").toInstant();
                    sessions.put(session.id, session);
                });
        jdbc.query("SELECT c.* FROM demo_rewind_checkpoint c JOIN demo_session s ON s.id=c.session_id WHERE s.created_at > NOW() - INTERVAL 24 HOUR ORDER BY c.simulation_time_ms",
                result -> {
                    DemoSession session = sessions.get(result.getString("session_id"));
                    if (session == null) return;
                    try {
                        Map<String, Object> state = mapper.readValue(result.getString("state_json"), new TypeReference<>() {});
                        java.sql.Timestamp used = result.getTimestamp("used_at");
                        DemoSession.RewindCheckpoint checkpoint = new DemoSession.RewindCheckpoint(
                                result.getString("id"), result.getString("volume_id"), result.getInt("timeline_epoch"),
                                result.getLong("simulation_time_ms"), result.getDouble("mission_progress"),
                                result.getTimestamp("created_at").toInstant(), state, result.getString("status"),
                                used == null ? null : used.toInstant(), result.getString("rewind_id"));
                        session.rewindCheckpoints.put(checkpoint.id, checkpoint);
                    } catch (Exception ignored) {}
                });
        jdbc.query("SELECT g.* FROM demo_signal g JOIN demo_session s ON s.id=g.session_id WHERE s.created_at > NOW() - INTERVAL 24 HOUR AND g.superseded_by_rewind_id IS NULL ORDER BY g.detected_at",
                result -> {
                    DemoSession session = sessions.get(result.getString("session_id"));
                    if (session == null) return;
                    Map<String, Object> source;
                    try { source = mapper.readValue(result.getString("payload_json"), new TypeReference<>() {}); }
                    catch (Exception ignored) { source = new LinkedHashMap<>(); }
                    DemoSignal signal = new DemoSignal(result.getString("id"), result.getString("signal_key"), result.getString("signal_type"),
                            result.getString("severity"), result.getString("status"), result.getString("phase_id"), result.getString("actor_id"),
                            result.getDouble("progress"), result.getTimestamp("detected_at").toInstant(), result.getTimestamp("updated_at").toInstant(), source);
                    session.signals.put(signal.id, signal);
                    session.triggeredEvents.add(signal.key);
                });
        jdbc.query("SELECT t.* FROM demo_telemetry t JOIN demo_session s ON s.id=t.session_id WHERE s.created_at > NOW() - INTERVAL 24 HOUR AND t.superseded_by_rewind_id IS NULL ORDER BY t.id",
                result -> {
                    DemoSession session = sessions.get(result.getString("session_id"));
                    if (session == null) return;
                    String deviceId = result.getString("device_id");
                    String deviceType = result.getString("device_type");
                    Map<String, Object> metrics;
                    try { metrics = mapper.readValue(result.getString("metrics_json"), new TypeReference<>() {}); }
                    catch (Exception ignored) { metrics = new LinkedHashMap<>(); }
                    Map<String, Object> point = new LinkedHashMap<>();
                    point.put("longitude", result.getDouble("longitude")); point.put("latitude", result.getDouble("latitude")); point.put("altitude", result.getDouble("altitude"));
                    point.put("eventTime", result.getTimestamp("event_time").toInstant().toString()); point.put("metrics", metrics);
                    List<Map<String, Object>> track = session.tracks.computeIfAbsent(deviceId, ignored -> Collections.synchronizedList(new ArrayList<>()));
                    track.add(point); if (track.size() > 500) track.remove(0);
                    Map<String, Object> device = new LinkedHashMap<>();
                    Map<String, Object> actor = actorMetadata(session, deviceId);
                    device.put("deviceId", deviceId); device.put("deviceType", deviceType); device.put("deviceName", actor.getOrDefault("name", deviceId));
                    copyActorMetadata(actor, device); device.putIfAbsent("commandTransport", unavailableCommandTransport());
                    device.put("longitude", point.get("longitude")); device.put("latitude", point.get("latitude")); device.put("altitude", point.get("altitude")); device.put("sensorData", metrics);
                    session.devices.put(deviceId, device);
                    if (metrics.get("routeProgress") instanceof Number progress) session.routeProgress.put(deviceId, progress.doubleValue());
                    if (metrics.get("uavState") != null) session.uavStates.put(deviceId, String.valueOf(metrics.get("uavState")));
                    if ("VEHICLE".equals(actor.get("kind")) && metrics.get("battery") instanceof Number battery)
                        session.groundBatteryPercent = battery.doubleValue();
                    if ("UAV".equals(actor.get("kind")) && metrics.get("battery") instanceof Number battery)
                        session.airBatteryPercent = battery.doubleValue();
                    session.previousActorPositions.put(deviceId, new double[]{
                            result.getDouble("longitude"), result.getDouble("latitude"), result.getDouble("altitude")});
                });
        jdbc.query("SELECT i.* FROM demo_airspace_incursion i JOIN demo_session s ON s.id=i.session_id WHERE s.created_at > NOW() - INTERVAL 24 HOUR AND i.superseded_by_rewind_id IS NULL ORDER BY i.sequence_no",
                result -> {
                    DemoSession session = sessions.get(result.getString("session_id"));
                    if (session == null) return;
                    String volumeId = result.getString("volume_id"), actorId = result.getString("actor_id");
                    String key = actorId + "|" + volumeId;
                    int sequence = result.getInt("sequence_no");
                    session.airspaceIncursionSequences.merge(key, sequence, Math::max);
                    if ("ACTIVE".equals(result.getString("status"))) {
                        session.activeAirspaceIncursions.put(key, new DemoSession.AirspaceIncursion(
                                result.getString("id"), volumeId, actorId, sequence,
                                result.getLong("entry_simulation_ms"), result.getLong("exposure_ms")));
                    }
                });
        jdbc.query("SELECT c.* FROM demo_workflow_command c JOIN demo_session s ON s.id=c.session_id WHERE s.created_at > NOW() - INTERVAL 24 HOUR AND c.superseded_by_rewind_id IS NULL ORDER BY c.requested_at",
                result -> {
                    DemoSession session = sessions.get(result.getString("session_id"));
                    if (session == null) return;
                    DemoSignal signal = session.signals.get(result.getString("signal_id"));
                    if (signal == null) return;
                    signal.recordStatus(result.getString("result_signal_status"), result.getDouble("mission_progress"),
                            result.getTimestamp("acknowledged_at").toInstant());
                });
        jdbc.query("SELECT * FROM demo_airspace_action WHERE superseded_by_rewind_id IS NULL ORDER BY created_at", result -> {
            DemoSession session = sessions.get(result.getString("session_id"));
            if (session == null) return;
            try {
                Map<String, Object> response = mapper.readValue(result.getString("response_json"), new TypeReference<>() {});
                session.airspaceActions.put(result.getString("volume_id"), response);
                String routeJson = result.getString("route_override_json");
                String actorId = result.getString("actor_id");
                String volumeId = result.getString("volume_id");
                if (routeJson != null) {
                    List<List<Number>> override = mapper.readValue(routeJson, new TypeReference<>() {});
                    actors(session).stream().filter(actor -> actorId.equals(String.valueOf(actor.get("id"))))
                            .findFirst().ifPresent(actor -> session.airRouteOverrides.put(String.valueOf(actor.get("routeId")), override));
                }
                if ("WAIT_UNTIL_CLEAR".equals(result.getString("action_type"))) {
                    Object waitUntil = response.get("waitUntilSimulationMs");
                    if (waitUntil instanceof Number until) {
                        session.uavWaitUntilMs.put(actorId, until.longValue());
                        session.temporaryAirspaceClearanceUntilMs.put(volumeId, Long.MAX_VALUE);
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    private Map<String, Object> actorMetadata(DemoSession session, String actorId) {
        try { return actor(session, actorId); }
        catch (Exception ignored) { return Map.of(); }
    }
    private void recordEvents(DemoSession session, Instant now) {
        int eventIndex = 0;
        for (Map<String, Object> event : events(session)) {
            String type = String.valueOf(event.get("type"));
            String key = String.valueOf(event.getOrDefault("id", type.toLowerCase(Locale.ROOT) + "-" + (++eventIndex)));
            double threshold = Number.class.cast(event.get("progress")).doubleValue();
            boolean reached = "COMPLETE".equals(type) ? session.progress >= threshold
                    : event.get("simulationTimeSeconds") instanceof Number seconds
                        ? session.simulationElapsedMs >= Math.round(seconds.doubleValue() * 1000)
                        : session.progress >= threshold;
            if (!reached || !session.triggeredEvents.add(key)) continue;
            try {
                String eventInstanceId = signalId(session.id, session.timelineEpoch, key);
                jdbc.update("INSERT INTO demo_event(session_id,event_instance_id,event_type,event_time,simulation_time_ms,timeline_epoch,progress,payload_json) VALUES(?,?,?,?,?,?,?,?)",
                        session.id, eventInstanceId, type, java.sql.Timestamp.from(now), session.simulationElapsedMs, session.timelineEpoch, session.progress, mapper.writeValueAsString(event));
                String signalId = signalId(session.id, session.timelineEpoch, key);
                String severity = String.valueOf(event.getOrDefault("severity", "INFO")).toUpperCase(Locale.ROOT);
                Map<String, Object> signalSource = new LinkedHashMap<>(event);
                String actorId = nullableString(event.get("deviceId"));
                Map<String, Object> actorAtDetection = actorId == null ? null : session.devices.get(actorId);
                if (actorAtDetection != null && actorAtDetection.get("longitude") instanceof Number longitude
                        && actorAtDetection.get("latitude") instanceof Number latitude) {
                    Map<String, Object> location = new LinkedHashMap<>();
                    location.put("longitude", longitude.doubleValue()); location.put("latitude", latitude.doubleValue());
                    location.put("altitude", number(actorAtDetection.get("altitude"), 0)); location.put("capturedAt", now.toString());
                    signalSource.put("location", location);
                }
                DemoSignal signal = new DemoSignal(signalId, key, type, severity, "DETECTED",
                        nullableString(event.get("phaseId")), actorId, threshold, now, now, signalSource);
                jdbc.update("INSERT INTO demo_signal(id,session_id,signal_key,signal_type,severity,status,phase_id,actor_id,progress,timeline_epoch,detected_at,updated_at,payload_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        signal.id, session.id, signal.key, signal.type, signal.severity, signal.status, signal.phaseId, signal.actorId,
                        signal.progress, session.timelineEpoch, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), mapper.writeValueAsString(signalSource));
                session.signals.put(signal.id, signal);
            } catch (Exception error) {
                session.triggeredEvents.remove(key);
            }
        }
    }

    private void advanceTaskSimulation(DemoSession session, double simulationStepSeconds, Instant now) {
        List<Map<String, Object>> vehicles = actors(session).stream().filter(actor -> "VEHICLE".equals(String.valueOf(actor.get("kind")))).toList();
        List<Map<String, Object>> uavs = actors(session).stream().filter(actor -> "UAV".equals(String.valueOf(actor.get("kind")))).toList();
        Map<String, Object> groundVehicle = castMap(definition(session).get("groundVehicle"));
        double fullRangeKm = Math.max(.001, number(groundVehicle.get("fullRangeKm"), 1));
        Map<String, Object> airVehicle = castMap(definition(session).get("airVehicle"));
        double airFullRangeKm = Math.max(.001, number(airVehicle.get("fullRangeKm"), 1));
        Map<String, Object> rendezvous = definition(session).get("rendezvousPlan") instanceof Map<?, ?> value
                ? mapper.convertValue(value, new TypeReference<>() {}) : Map.of();
        double launchProgress = number(rendezvous.get("launchRouteProgress"), 18);
        double recoveryProgress = number(rendezvous.get("recoveryRouteProgress"), 85);

        for (Map<String, Object> actor : vehicles) {
            String actorId = String.valueOf(actor.get("id"));
            String routeId = String.valueOf(actor.get("routeId"));
            Map<String, Object> route = route(session, routeId);
            double routeDistance = Math.max(1, number(route.get("distanceMeters"), MissionMath.polylineDistance(points(session, routeId))));
            double current = session.routeProgress.getOrDefault(actorId, 0.0);
            double speedMetersPerSecond = Math.max(.1, number(route.get("nominalSpeedKph"), 18) / 3.6);
            double proposed = MissionMath.clamp(current + speedMetersPerSecond * simulationStepSeconds / routeDistance * 100, 0, 100);
            boolean waitingForUav = uavs.stream().filter(uav -> actorId.equals(String.valueOf(uav.get("carrierActorId"))))
                    .anyMatch(uav -> session.routeProgress.getOrDefault(String.valueOf(uav.get("id")), 0.0) < 100);
            boolean rendezvousBlockedAtStart = waitingForUav && current >= recoveryProgress - 1e-6;
            if (waitingForUav && current <= recoveryProgress) proposed = Math.min(proposed, recoveryProgress);
            TrafficRuleEngine.Decision decision = trafficLights.govern(definition(session), routeId, current, proposed, routeDistance, session.simulationElapsedMs);
            double requestedMeters = Math.max(0, decision.nextProgress() - current) / 100 * routeDistance;
            FleetService.BatteryUse batteryUse = fleet.consumeGroundDistance(session.visitorHash, session.id,
                    session.groundAssetId, requestedMeters, fullRangeKm);
            double nextProgress = MissionMath.clamp(current + batteryUse.movedMeters() / routeDistance * 100, 0, 100);
            session.groundBatteryPercent = batteryUse.batteryPercent();
            session.routeProgress.put(actorId, nextProgress);
            if (!rendezvousBlockedAtStart && current < 100) {
                double countedSeconds = simulationStepSeconds;
                if (batteryUse.depleted() && !decision.stopped())
                    countedSeconds = Math.min(simulationStepSeconds, batteryUse.movedMeters() / speedMetersPerSecond);
                else if (waitingForUav && nextProgress >= recoveryProgress - 1e-6 && !decision.stopped())
                    countedSeconds = Math.min(simulationStepSeconds, batteryUse.movedMeters() / speedMetersPerSecond);
                else if (nextProgress >= 100 && !decision.stopped())
                    countedSeconds = Math.min(simulationStepSeconds, batteryUse.movedMeters() / speedMetersPerSecond);
                session.groundServiceElapsedMs += Math.max(0, Math.round(countedSeconds * 1000));
            }
            if (batteryUse.depleted() && nextProgress < 100 - 1e-6) session.groundBatteryDepleted = true;
            if (decision.stopped() && decision.signal() != null) {
                Map<String, Object> stop = new LinkedHashMap<>();
                stop.put("routeId", routeId); stop.put("deviceId", actorId); stop.put("linkId", decision.signal().linkId());
                stop.put("lampStatus", decision.signal().status()); stop.put("countdown", decision.signal().countdown());
                stop.put("routeProgress", decision.signal().routeProgress()); stop.put("movement", decision.signal().movement().name());
                stop.put("reason", decision.reason()); stop.put("source", "LOCAL_INTERSECTION_SIMULATION");
                session.trafficStops.put(actorId, stop);
            } else if (waitingForUav && nextProgress >= recoveryProgress - 1e-6) {
                Map<String, Object> stop = new LinkedHashMap<>();
                stop.put("routeId", routeId); stop.put("deviceId", actorId);
                stop.put("routeProgress", recoveryProgress); stop.put("reason", "WAITING_FOR_UAV");
                stop.put("source", "RENDEZVOUS_COORDINATION");
                session.trafficStops.put(actorId, stop);
            } else session.trafficStops.remove(actorId);
        }

        for (Map<String, Object> actor : uavs) {
            String actorId = String.valueOf(actor.get("id"));
            String carrierId = String.valueOf(actor.get("carrierActorId"));
            double carrierProgress = session.routeProgress.getOrDefault(carrierId, 0.0);
            String state = session.uavStates.getOrDefault(actorId, "ON_CARRIER");
            double airProgress = session.routeProgress.getOrDefault(actorId, 0.0);
            if ("ON_CARRIER".equals(state) && (session.independentAirRoute || carrierProgress >= launchProgress)) state = "TAKEOFF";
            if (!"ON_CARRIER".equals(state) && !"RECOVERED".equals(state)) {
                long waitUntil = session.uavWaitUntilMs.getOrDefault(actorId, 0L);
                if (session.simulationElapsedMs < waitUntil) {
                    session.uavStates.put(actorId, "HOLDING");
                    continue;
                }
                String routeId = String.valueOf(actor.get("routeId"));
                Map<String, Object> route = route(session, routeId);
                double routeDistance = Math.max(1, number(route.get("distanceMeters"), MissionMath.polylineDistance(points(session, routeId))));
                double speedMetersPerSecond = Math.max(.1, number(route.get("nominalSpeedKph"), 20) / 3.6);
                // Only 5..95% represents travel along the executable polyline.
                // Advancing by 90 percentage points keeps physical speed equal to
                // nominal speed before and after a longer detour/climb override.
                double proposedProgress = advanceAirSortieProgress(airProgress, speedMetersPerSecond,
                        simulationStepSeconds, routeDistance);
                double currentRouteProgress = executableAirRouteProgress(airProgress);
                double proposedRouteProgress = executableAirRouteProgress(proposedProgress);
                double requestedMeters = Math.max(0, proposedRouteProgress - currentRouteProgress) / 100 * routeDistance;
                double[] requestedFrom = MissionMath.sample(points(session, routeId), currentRouteProgress / 100);
                double[] requestedTo = MissionMath.sample(points(session, routeId), proposedRouteProgress / 100);
                EnergyExposure energyExposure = airEnergyExposure(session, requestedFrom, requestedTo);
                FleetService.BatteryUse batteryUse = fleet.consumeAirDistance(session.visitorHash, session.id,
                        session.airAssetId, requestedMeters, airFullRangeKm, energyExposure.multiplier());
                session.airEnergyMultipliers.put(actorId, energyExposure.multiplier());
                if (energyExposure.volumeId() == null) session.airRiskVolumeIds.remove(actorId);
                else session.airRiskVolumeIds.put(actorId, energyExposure.volumeId());
                if (batteryUse.extraBatteryPercent() > 0)
                    session.airExtraBatteryUsedPercent.merge(actorId, batteryUse.extraBatteryPercent(), Double::sum);
                if (requestedMeters > 0 && batteryUse.movedMeters() + 1e-6 < requestedMeters) {
                    double reachedRouteProgress = MissionMath.clamp(currentRouteProgress
                            + batteryUse.movedMeters() / routeDistance * 100, 0, 100);
                    airProgress = sortieProgressForAirRoute(reachedRouteProgress);
                } else airProgress = proposedProgress;
                session.airBatteryPercent = batteryUse.batteryPercent();
                if (batteryUse.depleted() && executableAirRouteProgress(airProgress) < 100 - 1e-6)
                    session.airBatteryDepleted = true;
                if (airProgress < 8) state = "TAKEOFF";
                else if (airProgress < 80) state = "SERVICING";
                else state = "RETURNING";
                if (airProgress >= 100 && (session.independentAirRoute || carrierProgress >= recoveryProgress)) state = "RECOVERED";
                session.routeProgress.put(actorId, airProgress);
            }
            session.uavStates.put(actorId, state);
        }

        double vehicleProgress = vehicles.stream().mapToDouble(actor -> session.routeProgress.getOrDefault(String.valueOf(actor.get("id")), 0.0)).average().orElse(100);
        double uavProgress = uavs.stream().mapToDouble(actor -> session.routeProgress.getOrDefault(String.valueOf(actor.get("id")), 0.0)).average().orElse(100);
        boolean vehiclesComplete = vehicles.stream().allMatch(actor -> session.routeProgress.getOrDefault(String.valueOf(actor.get("id")), 0.0) >= 100);
        boolean uavsRecovered = uavs.stream().allMatch(actor -> "RECOVERED".equals(session.uavStates.getOrDefault(String.valueOf(actor.get("id")), "ON_CARRIER")));
        session.progress = vehiclesComplete && uavsRecovered ? 100 : MissionMath.clamp(vehicleProgress * .6 + uavProgress * .4, 0, 99.99);
        String uavState = uavs.stream().map(actor -> session.uavStates.getOrDefault(String.valueOf(actor.get("id")), "ON_CARRIER")).findFirst().orElse("RECOVERED");
        session.missionPhase = switch (uavState) {
            case "TAKEOFF" -> "TAKEOFF";
            case "SERVICING" -> "DELIVERING";
            case "HOLDING" -> "DELIVERING";
            case "RETURNING" -> "RETURNING";
            case "RECOVERED" -> vehiclesComplete ? "DOCKED" : "RETURNING";
            default -> "DEPART";
        };
    }

    private void advanceGroundVehicles(DemoSession session, double missionProgressStep, Instant now) {
        List<Map<String, Object>> vehicles = actors(session).stream()
                .filter(actor -> "VEHICLE".equals(String.valueOf(actor.get("kind"))))
                .toList();
        boolean allRoutesComplete = !vehicles.isEmpty() && vehicles.stream()
                .allMatch(actor -> session.routeProgress.getOrDefault(String.valueOf(actor.get("id")), 0.0) >= 100);
        if (session.progress >= 90 && allRoutesComplete) {
            session.progress = MissionMath.clamp(session.progress + missionProgressStep, 0, 100);
            session.trafficStops.clear();
            return;
        }

        double minimumRouteProgress = 100;
        for (Map<String, Object> actor : vehicles) {
            String actorId = String.valueOf(actor.get("id"));
            String routeId = String.valueOf(actor.get("routeId"));
            Map<String, Object> route = route(session, routeId);
            double routeDistance = number(route.get("distanceMeters"), 1);
            double current = session.routeProgress.getOrDefault(actorId, MissionMath.clamp(session.progress / .9, 0, 100));
            double proposed = MissionMath.clamp(current + missionProgressStep / .9, 0, 100);
            TrafficRuleEngine.Decision decision = trafficLights.govern(definition(session), routeId, current, proposed, routeDistance, session.simulationElapsedMs);
            session.routeProgress.put(actorId, decision.nextProgress());
            minimumRouteProgress = Math.min(minimumRouteProgress, decision.nextProgress());
            if (decision.stopped() && decision.signal() != null) {
                Map<String, Object> stop = new LinkedHashMap<>();
                stop.put("routeId", routeId);
                stop.put("deviceId", actorId);
                stop.put("linkId", decision.signal().linkId());
                stop.put("lampStatus", decision.signal().status());
                stop.put("countdown", decision.signal().countdown());
                stop.put("routeProgress", decision.signal().routeProgress());
                stop.put("movement", decision.signal().movement().name());
                stop.put("reason", decision.reason());
                stop.put("source", "LOCAL_INTERSECTION_SIMULATION");
                session.trafficStops.put(actorId, stop);
            } else {
                session.trafficStops.remove(actorId);
            }
        }
        session.progress = MissionMath.clamp(Math.min(90, minimumRouteProgress * .9), 0, 100);
    }

    private void addTrafficLightView(Map<String, Object> mission, DemoSession session) {
        Map<String, Object> trafficSource = mission;
        if (session != null && !mission.containsKey("trafficLightStatus") && !mission.containsKey("trafficLights")) {
            // Mission deltas are intentionally sparse. Resolve traffic rules from
            // the frozen task plan so a disabled campus plan cannot fall back to
            // the fallback city-wide signal simulation while the run is active.
            trafficSource = definition(session);
        }
        mission.put("trafficLights", trafficLights.lights(trafficSource, session == null ? 0 : session.simulationElapsedMs));
        mission.put("trafficLightStatus", trafficLights.status(trafficSource));
        mission.put("trafficStops", session == null ? List.of() : new ArrayList<>(session.trafficStops.values()));
    }

    private static List<Map<String, Object>> safeTrackCopy(List<Map<String, Object>> track) {
        if (track == null) return List.of();
        synchronized (track) { return new ArrayList<>(track); }
    }
    private String phase(DemoSession session, double progress) {
        @SuppressWarnings("unchecked") Map<String, List<Number>> schedule = (Map<String, List<Number>>) definition(session).getOrDefault("phaseSchedule", Map.of());
        return schedule.entrySet().stream()
                .filter(entry -> progress >= entry.getValue().get(0).doubleValue() && (progress < entry.getValue().get(1).doubleValue() || progress >= 100))
                .map(Map.Entry::getKey).findFirst().orElse(session.missionPhase);
    }

    private List<Map<String, Object>> signalViews(DemoSession session, List<Map<String, Object>> events) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < events.size(); index++) {
            Map<String, Object> event = events.get(index);
            String type = String.valueOf(event.getOrDefault("type", "EVENT"));
            String key = String.valueOf(event.getOrDefault("id", type.toLowerCase(Locale.ROOT) + "-" + (index + 1)));
            String id = signalId(session.id, session.timelineEpoch, key);
            DemoSignal detected = session.signals.get(id);
            if (detected != null) { result.add(detected.publicView(session.id)); continue; }
            Map<String, Object> scheduled = new LinkedHashMap<>();
            scheduled.put("id", id); scheduled.put("key", key); scheduled.put("type", type);
            scheduled.put("label", String.valueOf(event.getOrDefault("label", type)));
            scheduled.put("severity", String.valueOf(event.getOrDefault("severity", "INFO")));
            scheduled.put("confidence", event.get("confidence")); scheduled.put("status", "SCHEDULED");
            scheduled.put("missionId", session.id); scheduled.put("phaseId", event.get("phaseId"));
            Object actorId = event.get("deviceId"); scheduled.put("actorIds", actorId == null ? List.of() : List.of(String.valueOf(actorId)));
            scheduled.put("progress", event.getOrDefault("progress", 0)); scheduled.put("detectedAt", null); scheduled.put("updatedAt", null);
            scheduled.put("requiresAction", false); scheduled.put("allowedActions", List.of()); scheduled.put("source", "MISSION_EVENT");
            result.add(scheduled);
        }
        return result;
    }

    private Map<String, Object> existingCommand(String commandId) {
        List<Map<String, Object>> rows = jdbc.query("SELECT * FROM demo_workflow_command WHERE id=? AND superseded_by_rewind_id IS NULL", (result, row) -> commandView(
                result.getString("id"), result.getString("session_id"), result.getString("signal_id"), result.getString("command_type"),
                result.getString("expected_signal_status"), result.getString("result_signal_status"), result.getDouble("mission_progress"), result.getString("status"),
                result.getTimestamp("requested_at").toInstant(), result.getTimestamp("acknowledged_at").toInstant(), result.getString("message")), commandId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Map<String, Object> commandView(String id, String sessionId, String signalId, String type, String expected,
                                                    String result, double missionProgress, String status, Instant requestedAt, Instant acknowledgedAt, String message) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id); value.put("sessionId", sessionId); value.put("signalId", signalId); value.put("kind", "WORKFLOW");
        value.put("type", type); value.put("expectedSignalStatus", expected); value.put("resultSignalStatus", result); value.put("missionProgress", missionProgress); value.put("status", status);
        value.put("requestedAt", requestedAt.toString()); value.put("acknowledgedAt", acknowledgedAt.toString()); value.put("message", message);
        return value;
    }

    private static String signalId(String sessionId, int timelineEpoch, String key) {
        String epoch = timelineEpoch == 0 ? "" : "E" + timelineEpoch + "-";
        return "SIG-" + sessionId + "-" + epoch + key.replaceAll("[^A-Za-z0-9_-]", "-");
    }
    private static String nullableString(Object value) { return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value); }
    private static double number(Object value, double fallback) { return value instanceof Number number ? number.doubleValue() : fallback; }
    static long timelinessRewardMinor(long baseGroundRewardMinor, double referenceGroundSeconds,
                                      double actualGroundSeconds) {
        double factor = TaskInstanceService.timelinessFactor(referenceGroundSeconds, actualGroundSeconds);
        return Math.round(baseGroundRewardMinor * factor / 100.0) * 100;
    }
    static double executableAirRouteProgress(double sortieProgress) {
        return MissionMath.clamp((sortieProgress - 5) / 90 * 100, 0, 100);
    }
    static double sortieProgressForAirRoute(double routeProgress) {
        return 5 + MissionMath.clamp(routeProgress, 0, 100) * .9;
    }
    static double advanceAirSortieProgress(double current, double speedMetersPerSecond,
                                            double simulationStepSeconds, double routeDistanceMeters) {
        double distance = Math.max(1, routeDistanceMeters);
        return MissionMath.clamp(current + Math.max(0, speedMetersPerSecond)
                * Math.max(0, simulationStepSeconds) / distance * 90, 0, 100);
    }
    private EnergyExposure airEnergyExposure(DemoSession session, double[] from, double[] to) {
        double multiplier = 1;
        String volumeId = null;
        for (Map<String, Object> volume : mapList(castMap(definition(session).get("airspace")).get("volumes"))) {
            if (!"RISK_AIRSPACE".equals(volume.get("ruleType"))
                    || !AirspaceGeometry.active(volume, session.simulationElapsedMs)) continue;
            double fraction = AirspaceGeometry.segmentExposureFraction(from, to, volume);
            double candidate = 1 + fraction * Math.max(0, number(volume.get("energyMultiplier"), 2.5) - 1);
            if (candidate > multiplier + 1e-9) {
                multiplier = candidate;
                volumeId = String.valueOf(volume.get("id"));
            }
        }
        return new EnergyExposure(multiplier, volumeId);
    }

    private record EnergyExposure(double multiplier, String volumeId) {}

    static boolean diamondActionEligible(Map<String, Object> diamond, String actorId,
                                         Map<String, Object> selectedAction) {
        if (diamond == null || !Objects.equals(String.valueOf(diamond.get("actorId")), actorId)) return false;
        String challengeType = String.valueOf(diamond.getOrDefault("challengeType",
                diamond.containsKey("linkedVolumeId") ? "AIRSPACE" : "ROUTE"));
        if ("ROUTE".equals(challengeType)) return true;
        // A straight-line orange challenge is earned by the actual trajectory.
        // Keeping the original route requires no command, so the bound UAV may
        // collect it whether or not the optional CONTINUE_DIRECT card was used.
        if ("CONTINUE_DIRECT".equals(String.valueOf(diamond.get("requiredAction")))) return true;
        return selectedAction != null
                && Objects.equals(String.valueOf(diamond.get("linkedVolumeId")), String.valueOf(selectedAction.get("volumeId")))
                && Objects.equals(String.valueOf(diamond.get("requiredAction")), String.valueOf(selectedAction.get("actionType")))
                && "APPLIED".equals(String.valueOf(selectedAction.get("status")));
    }
    private static void synchronizeDeviceRouteProgress(DemoSession session, String actorId, double routeProgress) {
        Map<String, Object> existing = session.devices.get(actorId);
        if (existing == null) return;
        Map<String, Object> device = new LinkedHashMap<>(existing);
        Map<String, Object> metrics = new LinkedHashMap<>(castMap(existing.get("sensorData")));
        metrics.put("routeProgress", routeProgress);
        device.put("sensorData", metrics);
        session.devices.put(actorId, device);
    }
    private static Map<String, Object> unavailableCommandTransport() {
        return Map.of("status", "UNAVAILABLE", "reason", "未接入真实设备命令与 ACK 链路");
    }
    private Map<String, Object> definition(DemoSession session) {
        Map<String, Object> source = session.plan != null ? session.plan : catalog.copy(session.definitionId, session.definitionVersion);
        return mapper.convertValue(source, new TypeReference<>() {});
    }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> actors(DemoSession session) {
        return (List<Map<String, Object>>) (session.plan != null ? session.plan : catalog.copy(session.definitionId, session.definitionVersion)).getOrDefault("actors", List.of());
    }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> routes(DemoSession session) {
        return (List<Map<String, Object>>) (session.plan != null ? session.plan : catalog.copy(session.definitionId, session.definitionVersion)).getOrDefault("routes", List.of());
    }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> events(DemoSession session) {
        return (List<Map<String, Object>>) (session.plan != null ? session.plan : catalog.copy(session.definitionId, session.definitionVersion)).getOrDefault("events", List.of());
    }
    private Map<String, Object> actor(DemoSession session, String actorId) {
        return actors(session).stream().filter(value -> actorId.equals(String.valueOf(value.get("id")))).findFirst().orElseThrow();
    }
    private Map<String, Object> route(DemoSession session, String routeId) {
        Map<String, Object> route = routes(session).stream().filter(value -> routeId.equals(String.valueOf(value.get("routeId")))).findFirst().orElseThrow();
        List<List<Number>> override = session.airRouteOverrides.get(routeId);
        if (override == null) return route;
        Map<String, Object> effective = new LinkedHashMap<>(route);
        effective.put("points", override); effective.put("effectivePoints", override);
        effective.put("distanceMeters", MissionMath.polylineDistance(TaskInstanceService.points(effective)));
        effective.put("runtimeRouteOverride", true);
        return effective;
    }
    private List<double[]> points(DemoSession session, String routeId) { return TaskInstanceService.points(route(session, routeId)); }
    private int missionDurationSeconds(DemoSession session) {
        Object value = (session.plan != null ? session.plan : Map.of()).get("durationSeconds");
        return Math.max(1, value instanceof Number number ? (int) Math.ceil(number.doubleValue()) : durationSeconds);
    }
    private static void copyActorMetadata(Map<String, Object> source, Map<String, Object> target) {
        Map<String, String> fields = Map.of(
                "kind", "actorKind", "role", "actorRole", "formationId", "formationId", "assignmentId", "assignmentId",
                "capabilities", "capabilities", "commandCapabilities", "commandCapabilities");
        fields.forEach((sourceKey, targetKey) -> { if (source.containsKey(sourceKey) && source.get(sourceKey) != null) target.put(targetKey, source.get(sourceKey)); });
        if (source.containsKey("actorKind")) target.put("actorKind", source.get("actorKind"));
        if (source.containsKey("actorRole")) target.put("actorRole", source.get("actorRole"));
        if (source.containsKey("commandTransport")) target.put("commandTransport", source.get("commandTransport"));
    }
    private static Instant parseHistoryInstant(String value, Instant fallback, String field) {
        if (value == null || value.isBlank()) return fallback;
        try { return Instant.parse(value); }
        catch (Exception error) { throw new DemoException(HttpStatus.BAD_REQUEST, field + " 必须是 ISO-8601 时间"); }
    }
    private static Optional<Long> parseLastEventId(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        try { return Optional.of(Long.parseLong(value)); }
        catch (NumberFormatException ignored) { return Optional.of(-1L); }
    }
    private static double[] recovery(Map<String, Object> route) { return recoveryPoint(route, "recoveryPoint"); }
    @SuppressWarnings("unchecked") private static double[] recoveryPoint(Map<String, Object> route, String field) { List<Number> point = (List<Number>) route.get(field); return new double[]{point.get(0).doubleValue(), point.get(1).doubleValue(), point.get(2).doubleValue()}; }
    private static double[] addHeight(double[] point, double height) { return new double[]{point[0], point[1], point[2] + height}; }
    private static double[] coordinate(Object value) {
        if (!(value instanceof List<?> point) || point.size() < 2
                || !(point.get(0) instanceof Number longitude) || !(point.get(1) instanceof Number latitude)) return null;
        double altitude = point.size() > 2 && point.get(2) instanceof Number number ? number.doubleValue() : 0;
        return new double[]{longitude.doubleValue(), latitude.doubleValue(), altitude};
    }

    private Map<String, Object> airspaceRuntime(DemoSession session, Map<String, Object> mission) {
        Map<String, Object> source = castMap(mission.get("airspace"));
        Map<String, Object> result = new LinkedHashMap<>(source);
        List<Map<String, Object>> runtimeVolumes = new ArrayList<>(), conflicts = new ArrayList<>();
        Map<String, Object> uav = actors(session).stream().filter(actor -> "UAV".equals(String.valueOf(actor.get("kind")))).findFirst().orElse(null);
        List<double[]> airRoute = uav == null ? List.of() : points(session, String.valueOf(uav.get("routeId")));
        double routeProgress = uav == null ? 0 : executableAirRouteProgress(
                session.routeProgress.getOrDefault(String.valueOf(uav.get("id")), 0.0));
        double speed = uav == null ? 5.5 : number(route(session, String.valueOf(uav.get("routeId"))).get("nominalSpeedKph"), 20) / 3.6;
        double currentAltitude = uav == null ? 0 : actorPosition(session, uav)[2];
        boolean uavAirborne = uav != null && !Set.of("ON_CARRIER", "RECOVERED").contains(
                session.uavStates.getOrDefault(String.valueOf(uav.get("id")), "ON_CARRIER"));
        for (Map<String, Object> raw : mapList(source.get("volumes"))) {
            Map<String, Object> volume = new LinkedHashMap<>(raw);
            long from = raw.get("activeFromSimulationMs") instanceof Number value ? value.longValue() : 0;
            long until = raw.get("activeUntilSimulationMs") instanceof Number value ? value.longValue() : Long.MAX_VALUE;
            long lead = raw.get("activationLeadMs") instanceof Number value ? value.longValue() : 0;
            long expansion = Math.max(1, raw.get("expansionDurationMs") instanceof Number value ? value.longValue() : 1);
            String state;
            double activationRatio;
            AirspaceGeometry.Conflict prospectiveConflict = uav == null ? null
                    : AirspaceGeometry.conflict(airRoute, raw, routeProgress);
            boolean approachManaged = Boolean.TRUE.equals(raw.get("dynamic"))
                    && "TEMPORARY_NO_FLY".equals(raw.get("ruleType"));
            boolean currentlyActive = false;
            boolean clearanceActive = false;
            if (approachManaged) {
                String volumeId = String.valueOf(raw.get("id"));
                TemporaryAirspaceLifecycle lifecycle = temporaryAirspaceLifecycle(
                        session.simulationElapsedMs,
                        Math.round(number(raw.get("cycleAnchorSimulationMs"), from)));
                clearanceActive = temporaryAirspaceClearanceActive(session, volumeId, prospectiveConflict);
                state = lifecycle.state(); activationRatio = lifecycle.activationRatio();
                from = lifecycle.activeFromMs(); until = lifecycle.activeUntilMs();
                currentlyActive = lifecycle.currentlyActive() && !clearanceActive;
                if (clearanceActive) { state = "SCHEDULED"; activationRatio = 0; }
            } else if (session.simulationElapsedMs >= until) { state = "EXPIRED"; activationRatio = 0; }
            else if (session.simulationElapsedMs < from - lead) { state = "SCHEDULED"; activationRatio = 0; }
            else if (session.simulationElapsedMs < from) { state = "ACTIVATING"; activationRatio = MissionMath.clamp(1 - (from - session.simulationElapsedMs) / (double) Math.max(lead, expansion), .05, .95); }
            else {
                state = "ACTIVE";
                activationRatio = Boolean.TRUE.equals(raw.get("dynamic"))
                        ? Math.min(1, (session.simulationElapsedMs - from) / (double) expansion)
                        : 1;
            }
            if (!approachManaged) currentlyActive = "ACTIVE".equals(state);
            volume.put("state", state); volume.put("activationRatio", activationRatio);
            volume.put("activeFromSimulationMs", from); volume.put("activeUntilSimulationMs", until);
            volume.put("startsInMs", clearanceActive ? null : Math.max(0, from - session.simulationElapsedMs));
            volume.put("remainingMs", until == Long.MAX_VALUE ? null : Math.max(0, until - session.simulationElapsedMs));
            volume.put("currentlyActive", currentlyActive); volume.put("clearanceActive", clearanceActive);
            if ("ALTITUDE_CORRIDOR".equals(volume.get("ruleType"))) volume.put("currentAltitudeMeters", currentAltitude);
            volume.put("selectedAction", session.airspaceActions.get(volume.get("id")));
            Map<String, Object> applied = session.airspaceActions.get(String.valueOf(volume.get("id")));
            boolean acceptedRisk = applied != null && "ACCEPT_RISK".equals(applied.get("actionType"));
            double etaSeconds = prospectiveConflict == null ? Double.POSITIVE_INFINITY
                    : prospectiveConflict.distanceMeters() / Math.max(.1, speed);
            boolean temporaryPrediction = approachManaged && uavAirborne
                    && etaSeconds <= number(raw.get("approachWarningSeconds"), TEMPORARY_AIRSPACE_APPROACH_SECONDS);
            AirspaceGeometry.Conflict conflict = !acceptedRisk && prospectiveConflict != null
                    && (temporaryPrediction || (!approachManaged && currentlyActive)) ? prospectiveConflict : null;
            String threat = "NORMAL";
            if (conflict != null) {
                Map<String, Object> view = AirspaceGeometry.view(conflict, volume, speed);
                view.put("actorId", uav == null ? null : uav.get("id"));
                view.put("currentlyActive", currentlyActive); view.put("predictive", approachManaged && !currentlyActive);
                view.put("lifecycleState", state); view.put("clearanceActive", clearanceActive);
                if (approachManaged && !currentlyActive && view.get("availableActions") instanceof List<?> actions) {
                    view.put("availableActions", actions.stream().map(String::valueOf)
                            .filter(action -> !"WAIT_UNTIL_CLEAR".equals(action)).toList());
                }
                long eta = ((Number) view.get("estimatedEntrySeconds")).longValue();
                boolean insideNow = currentlyActive && conflict.startProgress() <= routeProgress + .25;
                threat = insideNow ? "VIOLATION" : eta <= 15 ? "IMMINENT" : eta <= 60 ? "CONFLICT" : "NEAR";
                view.put("threatLevel", threat); conflicts.add(view);
            } else if ("ACTIVATING".equals(state)) threat = "NEAR";
            volume.put("threatLevel", threat); runtimeVolumes.add(volume);
        }
        result.put("runtimeVolumes", runtimeVolumes); result.put("volumes", mapList(source.get("volumes")));
        result.put("conflicts", conflicts); result.put("actions", new ArrayList<>(session.airspaceActions.values()));
        result.put("simulationTimeMs", session.simulationElapsedMs);
        return result;
    }

    static TemporaryAirspaceLifecycle temporaryAirspaceLifecycle(long simulationTimeMs, long cycleAnchorMs) {
        if (simulationTimeMs < cycleAnchorMs)
            return new TemporaryAirspaceLifecycle("SCHEDULED", 0, cycleAnchorMs,
                    cycleAnchorMs + TEMPORARY_AIRSPACE_ACTIVE_MS, false);
        long cursor = Math.floorMod(simulationTimeMs - cycleAnchorMs, TEMPORARY_AIRSPACE_CYCLE_MS);
        long cycleStart = simulationTimeMs - cursor;
        long activeUntil = cycleStart + TEMPORARY_AIRSPACE_ACTIVE_MS;
        if (cursor < TEMPORARY_AIRSPACE_EXPANSION_MS)
            return new TemporaryAirspaceLifecycle("ACTIVATING",
                    MissionMath.clamp(cursor / (double) TEMPORARY_AIRSPACE_EXPANSION_MS, .05, .95),
                    cycleStart, activeUntil, true);
        if (cursor < TEMPORARY_AIRSPACE_ACTIVE_MS - TEMPORARY_AIRSPACE_CONTRACTION_MS)
            return new TemporaryAirspaceLifecycle("ACTIVE", 1, cycleStart, activeUntil, true);
        if (cursor < TEMPORARY_AIRSPACE_ACTIVE_MS)
            return new TemporaryAirspaceLifecycle("CLEARING",
                    MissionMath.clamp((TEMPORARY_AIRSPACE_ACTIVE_MS - cursor)
                            / (double) TEMPORARY_AIRSPACE_CONTRACTION_MS, .05, .95),
                    cycleStart, activeUntil, true);
        long nextStart = cycleStart + TEMPORARY_AIRSPACE_CYCLE_MS;
        return new TemporaryAirspaceLifecycle("SCHEDULED", 0, nextStart,
                nextStart + TEMPORARY_AIRSPACE_ACTIVE_MS, false);
    }

    record TemporaryAirspaceLifecycle(String state, double activationRatio, long activeFromMs,
                                      long activeUntilMs, boolean currentlyActive) {}

    private boolean temporaryAirspaceClearanceActive(DemoSession session, String volumeId,
                                                       AirspaceGeometry.Conflict remainingConflict) {
        Long clearUntil = session.temporaryAirspaceClearanceUntilMs.get(volumeId);
        if (clearUntil == null) return false;
        if (clearUntil == Long.MAX_VALUE) {
            if (remainingConflict != null) return true;
            session.temporaryAirspaceClearanceUntilMs.put(volumeId,
                    session.simulationElapsedMs + TEMPORARY_AIRSPACE_POST_PASS_GRACE_MS);
            return true;
        }
        if (session.simulationElapsedMs < clearUntil) return true;
        session.temporaryAirspaceClearanceUntilMs.remove(volumeId, clearUntil);
        return false;
    }

    private boolean isAirspaceActive(DemoSession session, Map<String, Object> volume) {
        if (Boolean.TRUE.equals(volume.get("dynamic")) && "TEMPORARY_NO_FLY".equals(volume.get("ruleType"))) {
            String volumeId = String.valueOf(volume.get("id"));
            if (session.temporaryAirspaceClearanceUntilMs.containsKey(volumeId)) return false;
            long anchor = Math.round(number(volume.get("cycleAnchorSimulationMs"),
                    number(volume.get("activeFromSimulationMs"), 0)));
            return temporaryAirspaceLifecycle(session.simulationElapsedMs, anchor).currentlyActive();
        }
        return AirspaceGeometry.active(volume, session.simulationElapsedMs);
    }

    private String writeJson(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    private static List<List<Number>> coordinateValues(List<double[]> points) {
        return points.stream().map(point -> List.<Number>of(point[0], point[1], point[2])).toList();
    }

    private List<double[]> safeReturnRoute(DemoSession session, List<double[]> requested) {
        List<double[]> route = new ArrayList<>(requested.stream().map(double[]::clone).toList());
        for (Map<String, Object> volume : mapList(castMap(definition(session).get("airspace")).get("volumes"))) {
            if (!AirspaceGeometry.blocking(volume) || !isAirspaceActive(session, volume)) continue;
            if (AirspaceGeometry.conflict(route, volume, 0) != null) route = AirspaceGeometry.detour(route, volume, 0);
        }
        return route;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
