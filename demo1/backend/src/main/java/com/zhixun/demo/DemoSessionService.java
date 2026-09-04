package com.zhixun.demo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
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
    private static final Set<Double> SPEEDS = Set.of(0.5, 1.0, 2.0, 5.0);
    private static final List<String> DEVICE_IDS = List.of("HF-VEH-000001", "HF-UAV-000003", "HF-VEH-000002", "HF-UAV-000004");
    private final MissionCatalog catalog;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MqttBridge mqtt;
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
                              @Value("${demo.max-active-sessions}") int maxActive,
                              @Value("${demo.max-queue-size}") int maxQueue,
                              @Value("${demo.session-duration-seconds}") int durationSeconds,
                              @Value("${demo.disconnect-expiry-seconds}") int disconnectExpirySeconds) {
        this.catalog = catalog; this.jdbc = jdbc; this.mapper = mapper; this.mqtt = mqtt;
        this.maxActive = maxActive; this.maxQueue = maxQueue; this.durationSeconds = durationSeconds;
        this.disconnectExpirySeconds = disconnectExpirySeconds;
    }

    @PostConstruct
    void initialize() {
        jdbc.update("UPDATE demo_session SET status='EXPIRED',completed_at=NOW(3) WHERE status IN ('RUNNING','QUEUED')");
        restoreRecentSessions();
        mqtt.setListener(this::acceptTelemetry);
    }

    public Map<String, Object> current(String visitorHash) {
        DemoSession session = sessions.values().stream().filter(item -> item.visitorHash.equals(visitorHash))
                .max(Comparator.comparing(item -> item.createdAt)).orElse(null);
        if (session != null) return snapshot(session, true);
        Map<String, Object> standby = new LinkedHashMap<>();
        standby.put("session", null);
        standby.put("mission", catalog.standbySnapshot());
        standby.put("devices", standbyDevices());
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
            DemoSession session = new DemoSession(id, visitorHash, active < maxActive ? "RUNNING" : "QUEUED");
            if ("RUNNING".equals(session.status)) start(session); else { queue.addLast(id); session.queuePosition = queue.size(); }
            sessions.put(id, session);
            jdbc.update("INSERT INTO demo_session(id,visitor_hash,status,time_scale,progress,mission_phase,queue_position,started_at) VALUES(?,?,?,?,?,?,?,?)",
                    id, visitorHash, session.status, session.timeScale, 0, session.missionPhase, session.queuePosition == 0 ? null : session.queuePosition,
                    session.startedAt == null ? null : java.sql.Timestamp.from(session.startedAt));
            return snapshot(session, true);
        }
    }

    public Map<String, Object> mission(String id, String visitorHash) { DemoSession session = owned(id, visitorHash); touch(session); return snapshot(session, true); }

    public Map<String, Object> speed(String id, String visitorHash, double timeScale) {
        DemoSession session = owned(id, visitorHash);
        if (!SPEEDS.contains(timeScale)) throw new DemoException(HttpStatus.BAD_REQUEST, "倍速仅支持 0.5、1、2、5");
        if (!"RUNNING".equals(session.status)) throw new DemoException(HttpStatus.CONFLICT, "只有运行中的任务可以改变倍速");
        session.timeScale = timeScale; touch(session);
        jdbc.update("UPDATE demo_session SET time_scale=?,last_seen_at=NOW(3) WHERE id=?", timeScale, id);
        return snapshot(session, true);
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

    public SseEmitter events(String id, String visitorHash) {
        DemoSession session = owned(id, visitorHash); touch(session);
        SseEmitter emitter = new SseEmitter(240_000L);
        emitters.computeIfAbsent(id, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable remove = () -> removeEmitter(id, emitter);
        emitter.onCompletion(remove); emitter.onTimeout(remove); emitter.onError(error -> remove.run());
        send(emitter, TERMINAL.contains(session.status) ? "session-end" : "snapshot", snapshot(session, true));
        return emitter;
    }

    @Scheduled(fixedRate = 1000)
    public void tick() {
        Instant now = Instant.now();
        for (DemoSession session : new ArrayList<>(sessions.values())) {
            if (!"RUNNING".equals(session.status)) continue;
            if (!emitters.containsKey(session.id) && Duration.between(session.lastSeenAt, now).toSeconds() > disconnectExpirySeconds) { finish(session, "EXPIRED"); continue; }
            session.progress = MissionMath.clamp(session.progress + (100.0 / durationSeconds) * session.timeScale, 0, 100);
            session.missionPhase = phase(session.progress);
            recordEvents(session, now);
            for (String deviceId : DEVICE_IDS) publishTelemetry(session, deviceId, now);
            if (!"RUNNING".equals(session.status)) continue;
            jdbc.update("UPDATE demo_session SET progress=?,mission_phase=?,status=?,last_seen_at=last_seen_at,completed_at=? WHERE id=?",
                    session.progress, session.missionPhase, session.progress >= 100 ? "COMPLETED" : "RUNNING",
                    session.progress >= 100 ? java.sql.Timestamp.from(now) : null, session.id);
            if (session.progress >= 100) finish(session, "COMPLETED");
        }
        promoteQueue();
        cleanupRateLimits(now);
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void cleanupHistory() {
        jdbc.update("DELETE FROM demo_session WHERE status IN ('COMPLETED','STOPPED','EXPIRED','FAILED') AND completed_at < NOW() - INTERVAL 24 HOUR");
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        sessions.entrySet().removeIf(entry -> TERMINAL.contains(entry.getValue().status) && entry.getValue().createdAt.isBefore(cutoff));
    }

    private void publishTelemetry(DemoSession session, String deviceId, Instant now) {
        boolean drone = deviceId.contains("UAV");
        String pair = deviceId.endsWith("1") || deviceId.endsWith("3") ? "A" : "B";
        String vehicleId = pair.equals("A") ? "HF-VEH-000001" : "HF-VEH-000002";
        Map<String, Object> vehicleRoute = catalog.route(vehicleId);
        List<double[]> ground = catalog.points(vehicleId);
        Map<String, Object> airRoute = catalog.route(pair.equals("A") ? "HF-UAV-000003" : "HF-UAV-000004");
        List<double[]> air = catalog.points(String.valueOf(airRoute.get("deviceId")));
        double p = session.progress / 100.0;
        double[] point;
        if (!drone) point = MissionMath.sample(ground, MissionMath.clamp(p / .9, 0, 1));
        else if (p < .10) point = addHeight(MissionMath.sample(ground, p / .9), 2.0);
        else if (p < .20) point = MissionMath.smooth(addHeight(MissionMath.sample(ground, .10 / .9), 2.0), air.get(0), (p - .10) / .10, 8);
        else if (p < .75) point = MissionMath.sample(air, (p - .20) / .55);
        else if (p < .90) point = MissionMath.smooth(air.get(air.size() - 1), recovery(airRoute), (p - .75) / .15, 6);
        else point = recovery(airRoute);
        double previousP = Math.max(0, p - .006);
        double[] previous = drone ? point : MissionMath.sample(ground, MissionMath.clamp(previousP / .9, 0, 1));
        double direction = MissionMath.bearing(previous, point);
        double deviation = Math.abs(Math.sin((session.progress + deviceId.hashCode() % 17) * .13)) * (drone ? 2.6 : 5.5);
        double coverage = drone ? MissionMath.clamp((session.progress - 20) / 55 * 100, 0, 100) : 0;
        double link = drone && deviceId.endsWith("4") && session.progress >= 62 && session.progress <= 69 ? 64 : 96 - deviation;
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("speed", session.progress >= 90 ? 0 : Number.class.cast(drone ? airRoute.get("nominalSpeedKph") : vehicleRoute.get("nominalSpeedKph")).doubleValue());
        metrics.put("direction", direction); metrics.put("missionPhase", session.missionPhase); metrics.put("routeProgress", session.progress);
        metrics.put("routeDeviationMeters", deviation); metrics.put("battery", drone ? 98 - session.progress * .22 : 92);
        metrics.put("linkQuality", link); metrics.put("coveragePercent", coverage); metrics.put("scanActive", drone && "SCANNING".equals(session.missionPhase));
        if (drone) metrics.put("boundVehicleId", vehicleId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", session.id); payload.put("deviceId", deviceId); payload.put("deviceType", drone ? "smart_drone" : "patrol_car");
        payload.put("deviceName", drone ? (pair.equals("A") ? "一号巡检无人机" : "二号巡检无人机") : (pair.equals("A") ? "一号城市巡检车" : "二号城市巡检车"));
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
            session.tracks.computeIfAbsent(deviceId, ignored -> Collections.synchronizedList(new ArrayList<>())).add(point);
            if (session.tracks.get(deviceId).size() > 500) session.tracks.get(deviceId).remove(0);
            Map<String, Object> device = new LinkedHashMap<>();
            device.put("deviceId", deviceId); device.put("deviceType", deviceType); device.put("deviceName", payload.get("deviceName"));
            device.put("longitude", payload.get("longitude")); device.put("latitude", payload.get("latitude")); device.put("altitude", payload.get("altitude")); device.put("sensorData", metrics);
            session.devices.put(deviceId, device);
            jdbc.update("INSERT INTO demo_telemetry(session_id,device_id,device_type,event_time,longitude,latitude,altitude,heading,metrics_json) VALUES(?,?,?,?,?,?,?,?,?)",
                    session.id, deviceId, deviceType, java.sql.Timestamp.from(Instant.parse(String.valueOf(payload.get("eventTime")))), payload.get("longitude"), payload.get("latitude"), payload.get("altitude"), metrics.get("direction"), mapper.writeValueAsString(metrics));
            if (deviceId.endsWith("4")) broadcast(session, "snapshot");
        } catch (Exception ignored) {}
    }

    private Map<String, Object> snapshot(DemoSession session, boolean includeTracks) {
        Map<String, Object> mission = catalog.copy();
        mission.put("state", session.status); mission.put("status", session.status); mission.put("progress", session.progress); mission.put("missionPhase", session.missionPhase); mission.put("simulationId", session.id); mission.put("routeVersion", mission.get("version"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> routes = (List<Map<String, Object>>) mission.get("routes");
        routes.forEach(route -> route.put("actualPoints", includeTracks ? safeTrackCopy(session.tracks.get(String.valueOf(route.get("deviceId")))) : List.of()));
        @SuppressWarnings("unchecked") List<Map<String, Object>> events = (List<Map<String, Object>>) mission.get("events");
        events.forEach(event -> event.put("reached", session.progress >= Number.class.cast(event.get("progress")).doubleValue()));
        return Map.of("session", session.publicView(), "mission", mission, "devices", session.devices.isEmpty() ? standbyDevices() : session.deviceList());
    }

    private List<Map<String, Object>> standbyDevices() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String deviceId : DEVICE_IDS) {
            boolean drone = deviceId.contains("UAV"); String vehicleId = deviceId.endsWith("1") || deviceId.endsWith("3") ? "HF-VEH-000001" : "HF-VEH-000002";
            double[] point = catalog.points(vehicleId).get(0).clone(); if (drone) point[2] += 2;
            Map<String, Object> metrics = new LinkedHashMap<>(); metrics.put("missionPhase", "DOCKED"); metrics.put("routeProgress", 0); metrics.put("routeDeviationMeters", 0); metrics.put("battery", drone ? 98 : 92); metrics.put("linkQuality", 100); metrics.put("coveragePercent", 0);
            Map<String, Object> device = new LinkedHashMap<>(); device.put("deviceId", deviceId); device.put("deviceType", drone ? "smart_drone" : "patrol_car"); device.put("deviceName", drone ? "巡检无人机" : "城市巡检车"); device.put("longitude", point[0]); device.put("latitude", point[1]); device.put("altitude", point[2]); device.put("sensorData", metrics); result.add(device);
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
        synchronized (lock) { if (TERMINAL.contains(session.status) && !session.status.equals(status)) return; session.status = status; queue.remove(session.id); jdbc.update("UPDATE demo_session SET status=?,completed_at=NOW(3),queue_position=NULL WHERE id=?", status, session.id); broadcast(session, "session-end"); promoteQueue(); }
    }
    private void promoteQueue() {
        synchronized (lock) {
            long active = sessions.values().stream().filter(item -> "RUNNING".equals(item.status)).count();
            while (active < maxActive && !queue.isEmpty()) { DemoSession next = sessions.get(queue.removeFirst()); if (next == null || !"QUEUED".equals(next.status)) continue; start(next); jdbc.update("UPDATE demo_session SET status='RUNNING',started_at=NOW(3),queue_position=NULL WHERE id=?", next.id); active++; broadcast(next, "snapshot"); }
            int position = 1; for (String id : queue) { DemoSession queued = sessions.get(id); if (queued != null) { queued.queuePosition = position; jdbc.update("UPDATE demo_session SET queue_position=? WHERE id=?", position++, id); broadcast(queued, "snapshot"); } }
        }
    }
    private void broadcast(DemoSession session, String event) { for (SseEmitter emitter : emitters.getOrDefault(session.id, new CopyOnWriteArrayList<>())) if (!send(emitter, event, snapshot(session, true))) removeEmitter(session.id, emitter); }
    private void removeEmitter(String sessionId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> listeners = emitters.get(sessionId);
        if (listeners == null) return;
        listeners.remove(emitter);
        if (listeners.isEmpty()) emitters.remove(sessionId, listeners);
    }
    private boolean send(SseEmitter emitter, String event, Object data) { try { emitter.send(SseEmitter.event().name(event).data(data)); if ("session-end".equals(event)) emitter.complete(); return true; } catch (Exception error) { return false; } }
    private void checkRate(String source) { Deque<Instant> starts = startsBySource.computeIfAbsent(source, ignored -> new ConcurrentLinkedDeque<>()); Instant cutoff = Instant.now().minusSeconds(600); while (!starts.isEmpty() && starts.peekFirst().isBefore(cutoff)) starts.removeFirst(); if (starts.size() >= 3) throw new DemoException(HttpStatus.TOO_MANY_REQUESTS, "同一来源10分钟内最多创建3次任务"); starts.addLast(Instant.now()); }
    private void cleanupRateLimits(Instant now) { startsBySource.entrySet().removeIf(entry -> entry.getValue().isEmpty() || entry.getValue().peekLast().isBefore(now.minusSeconds(600))); }
    private void restoreRecentSessions() {
        jdbc.query("SELECT s.* FROM demo_session s JOIN (SELECT visitor_hash,MAX(created_at) newest FROM demo_session WHERE created_at > NOW() - INTERVAL 24 HOUR GROUP BY visitor_hash) latest ON latest.visitor_hash=s.visitor_hash AND latest.newest=s.created_at",
                result -> {
                    DemoSession session = new DemoSession(result.getString("id"), result.getString("visitor_hash"), result.getString("status"), result.getTimestamp("created_at").toInstant());
                    session.timeScale = result.getDouble("time_scale");
                    session.progress = result.getDouble("progress");
                    session.missionPhase = result.getString("mission_phase");
                    session.queuePosition = result.getInt("queue_position");
                    if (result.wasNull()) session.queuePosition = 0;
                    if (result.getTimestamp("started_at") != null) session.startedAt = result.getTimestamp("started_at").toInstant();
                    if (result.getTimestamp("last_seen_at") != null) session.lastSeenAt = result.getTimestamp("last_seen_at").toInstant();
                    sessions.put(session.id, session);
                });
        jdbc.query("SELECT t.* FROM demo_telemetry t JOIN demo_session s ON s.id=t.session_id WHERE s.created_at > NOW() - INTERVAL 24 HOUR ORDER BY t.id",
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
                    device.put("deviceId", deviceId); device.put("deviceType", deviceType); device.put("deviceName", deviceName(deviceId));
                    device.put("longitude", point.get("longitude")); device.put("latitude", point.get("latitude")); device.put("altitude", point.get("altitude")); device.put("sensorData", metrics);
                    session.devices.put(deviceId, device);
                });
    }
    private static String deviceName(String deviceId) {
        return switch (deviceId) {
            case "HF-VEH-000001" -> "一号城市巡检车";
            case "HF-UAV-000003" -> "一号巡检无人机";
            case "HF-VEH-000002" -> "二号城市巡检车";
            case "HF-UAV-000004" -> "二号巡检无人机";
            default -> deviceId;
        };
    }
    private void recordEvents(DemoSession session, Instant now) {
        for (Map<String, Object> event : catalog.events()) {
            String type = String.valueOf(event.get("type"));
            double threshold = Number.class.cast(event.get("progress")).doubleValue();
            if (session.progress < threshold || !session.triggeredEvents.add(type)) continue;
            try {
                jdbc.update("INSERT INTO demo_event(session_id,event_type,event_time,progress,payload_json) VALUES(?,?,?,?,?)",
                        session.id, type, java.sql.Timestamp.from(now), session.progress, mapper.writeValueAsString(event));
            } catch (Exception ignored) {}
        }
    }
    private static List<Map<String, Object>> safeTrackCopy(List<Map<String, Object>> track) {
        if (track == null) return List.of();
        synchronized (track) { return new ArrayList<>(track); }
    }
    private static String phase(double progress) { if (progress < 10) return "DEPART"; if (progress < 20) return "TAKEOFF"; if (progress < 75) return "SCANNING"; if (progress < 90) return "RETURNING"; return "DOCKED"; }
    @SuppressWarnings("unchecked") private static double[] recovery(Map<String, Object> route) { List<Number> point = (List<Number>) route.get("recoveryPoint"); return new double[]{point.get(0).doubleValue(), point.get(1).doubleValue(), point.get(2).doubleValue()}; }
    private static double[] addHeight(double[] point, double height) { return new double[]{point[0], point[1], point[2] + height}; }
}
