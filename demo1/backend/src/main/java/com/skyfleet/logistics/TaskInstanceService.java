package com.skyfleet.logistics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class TaskInstanceService {
    public static final String GENERATOR_VERSION = "mission-generator/2.1.3";
    public static final String RULESET_VERSION = "delivery-economy/2.0.0";
    private static final long DIAMOND_REWARD_MINOR = 480_000L;
    private static final List<Long> REWARD_DENOMINATIONS_MINOR = List.of(30_000L, 50_000L, 80_000L, 120_000L, 180_000L);
    private static final double DELIVERY_AIRSPACE_BUFFER_METERS = 35;
    private static final double DIAMOND_MIN_SPACING_METERS = 45;
    private static final int MAX_ATTEMPTS = 20;
    private static final int MAX_AUTOMATIC_SEEDS = 4;
    private final ScenarioTemplateCatalog templates;
    private final BaiduRouteProvider baidu;
    private final SimulatedTrafficLightService intersectionCatalog;
    private final FleetService fleet;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public TaskInstanceService(ScenarioTemplateCatalog templates, BaiduRouteProvider baidu,
                               SimulatedTrafficLightService intersectionCatalog, FleetService fleet,
                               JdbcTemplate jdbc, ObjectMapper mapper) {
        this.templates = templates;
        this.baidu = baidu;
        this.intersectionCatalog = intersectionCatalog;
        this.fleet = fleet;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<Map<String, Object>> templateSummaries() { return templates.summaries(); }

    @Transactional
    public Map<String, Object> generate(String visitorHash, String templateId, String seedInput, Map<String, Object> rawParameters) {
        ScenarioTemplateCatalog.Descriptor descriptor;
        try { descriptor = templates.descriptor(templateId); }
        catch (IllegalArgumentException error) { throw new DemoException(HttpStatus.BAD_REQUEST, error.getMessage()); }
        boolean automaticSeed = seedInput == null || seedInput.isBlank();
        Map<String, Object> parameters = parameters(descriptor, rawParameters == null ? Map.of() : rawParameters);
        FleetService.GroundVehicle groundVehicle = fleet.freezeGroundVehicle(visitorHash);
        FleetService.AirVehicle airVehicle = fleet.freezeAirVehicle(visitorHash);
        List<Map<String, Object>> trace = List.of();
        Object lastReasons = List.of();
        int seedLimit = automaticSeed ? MAX_AUTOMATIC_SEEDS : 1;

        for (int seedAttempt = 1; seedAttempt <= seedLimit; seedAttempt++) {
            String seed = normalizeSeed(automaticSeed ? null : seedInput);
            trace = new ArrayList<>();
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                String attemptDomain = "attempt/" + attempt;
                try {
                    Generated generated = candidate(descriptor, seed, parameters, attemptDomain, groundVehicle, airVehicle);
                    List<Map<String, Object>> violations = validate(generated.plan(), parameters);
                    if (!violations.isEmpty()) {
                        trace.add(trace(attempt, seed, violations.stream().map(v -> String.valueOf(v.get("code"))).toList(), "REJECTED"));
                        continue;
                    }
                    trace.add(trace(attempt, seed, List.of(), "ACCEPTED"));
                    Map<String, Object> validation = new LinkedHashMap<>();
                    validation.put("status", "PASSED");
                    validation.put("validatorVersion", RULESET_VERSION);
                    validation.put("checks", List.of("GROUND_CONTINUITY", "GROUND_ANCHORS", "AIRSPACE_DECLARED_ZONES", "ALTITUDE",
                            "FLIGHT_RANGE", "RENDEZVOUS", "DURATION", "TRAFFIC_ON_ROUTE"));
                    validation.put("buildingCollision", "NOT_EVALUATED");
                    validation.put("violations", List.of());
                    String taskId = id("TASK");
                    Map<String, Object> plan = new LinkedHashMap<>(generated.plan());
                    plan.put("seed", seed);
                    plan.put("generatorVersion", GENERATOR_VERSION);
                    plan.put("rulesetVersion", RULESET_VERSION);
                    plan.put("randomVersion", DeterministicRandom.VERSION);
                    plan.put("resolvedParameters", parameters);
                    String planHash = planHash(plan);
                    plan.put("taskId", taskId);
                    plan.put("planHash", planHash);
                    Instant now = Instant.now();
                    Instant expiresAt = now.plusSeconds(24 * 3600);
                    jdbc.update("INSERT INTO demo_task_instance(id,visitor_hash,scenario_template_id,scenario_template_version,generator_version,ruleset_version,seed_value,resolved_parameters_json,plan_json,validation_json,generation_trace_json,plan_hash,route_artifact_id,lifecycle_status,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            taskId, visitorHash, descriptor.id(), ScenarioTemplateCatalog.VERSION, GENERATOR_VERSION, RULESET_VERSION, seed,
                            json(parameters), json(plan), json(validation), json(trace), planHash, generated.routeArtifactId(), "READY",
                            Timestamp.from(now), Timestamp.from(expiresAt));
                    return view(taskId, descriptor.id(), ScenarioTemplateCatalog.VERSION, GENERATOR_VERSION, RULESET_VERSION,
                            seed, parameters, plan, validation, trace,
                            planHash, generated.routeArtifactId(), "READY", now, expiresAt, null);
                } catch (CandidateRejected rejected) {
                    trace.add(trace(attempt, seed, List.of(rejected.code), "REJECTED"));
                }
            }
            lastReasons = trace.isEmpty() ? List.of() : trace.get(trace.size() - 1).get("reasonCodes");
        }
        throw new DemoException(HttpStatus.UNPROCESSABLE_ENTITY,
                (automaticSeed ? "自动更换 " + MAX_AUTOMATIC_SEEDS + " 个 Seed 后仍无法生成任务，请调整任务参数"
                        : MAX_ATTEMPTS + " 次确定性生成均未通过校验，请调整任务参数或更换 Seed")
                        + "（最后原因：" + lastReasons + "）");
    }

    public Map<String, Object> getOwned(String taskId, String visitorHash) {
        List<Map<String, Object>> rows = jdbc.query("SELECT * FROM demo_task_instance WHERE id=? AND visitor_hash=?", (rs, row) -> rowView(rs), taskId, visitorHash);
        if (rows.isEmpty()) throw new DemoException(HttpStatus.NOT_FOUND, "任务实例不存在或已清理");
        return rows.get(0);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> loadPlanOwned(String taskId, String visitorHash) {
        return (Map<String, Object>) getOwned(taskId, visitorHash).get("plan");
    }

    public Map<String, Object> loadPlan(String taskId) {
        String value = jdbc.queryForObject("SELECT plan_json FROM demo_task_instance WHERE id=?", String.class, taskId);
        return readMap(value);
    }

    @Transactional
    public void markStarted(String taskId, String visitorHash) {
        Map<String, Object> task = getOwned(taskId, visitorHash);
        if (!"READY".equals(task.get("status"))) throw new DemoException(HttpStatus.CONFLICT, "该任务实例已经执行或不可用");
        Instant expires = Instant.parse(String.valueOf(task.get("expiresAt")));
        if (expires.isBefore(Instant.now())) throw new DemoException(HttpStatus.CONFLICT, "任务预览已过期，请重新生成");
        int updated = jdbc.update("UPDATE demo_task_instance SET lifecycle_status='STARTED',started_at=NOW(3) WHERE id=? AND visitor_hash=? AND lifecycle_status='READY' AND expires_at>NOW(3)", taskId, visitorHash);
        if (updated != 1) throw new DemoException(HttpStatus.CONFLICT, "任务实例状态已经变化，请刷新后重试");
    }

    public Map<String, Object> history(String visitorHash, String cursor, int limit) {
        int safeLimit = Math.max(1, Math.min(50, limit));
        Timestamp cursorTimestamp = null;
        if (cursor != null && !cursor.isBlank()) {
            try { cursorTimestamp = Timestamp.from(Instant.parse(cursor)); }
            catch (Exception error) { throw new DemoException(HttpStatus.BAD_REQUEST, "历史游标格式无效"); }
        }
        String sql = "SELECT t.*,s.id run_id,s.status run_status,s.terminal_reason,s.started_at run_started_at,s.completed_at run_completed_at FROM demo_task_instance t JOIN demo_session s ON s.task_instance_id=t.id WHERE t.visitor_hash=? AND t.lifecycle_status='STARTED' AND t.created_at>NOW()-INTERVAL 30 DAY"
                + (cursorTimestamp == null ? "" : " AND t.created_at<?") + " ORDER BY t.created_at DESC LIMIT ?";
        Object[] arguments = cursorTimestamp == null ? new Object[]{visitorHash, safeLimit + 1} : new Object[]{visitorHash, cursorTimestamp, safeLimit + 1};
        List<Map<String, Object>> items = jdbc.query(sql,
                (rs, row) -> {
                    Map<String, Object> task = rowView(rs);
                    Map<String, Object> plan = castMap(task.get("plan"));
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("taskId", task.get("taskId"));
                    summary.put("runId", rs.getString("run_id"));
                    summary.put("name", plan.get("name"));
                    summary.put("scenarioTemplateId", task.get("scenarioTemplateId"));
                    summary.put("scenarioTemplateVersion", task.get("scenarioTemplateVersion"));
                    summary.put("seed", task.get("seed"));
                    summary.put("planHash", task.get("planHash"));
                    summary.put("routeSource", plan.get("routeSource"));
                    summary.put("estimatedDurationSeconds", plan.get("estimatedDurationSeconds"));
                    summary.put("status", rs.getString("run_status"));
                    if (rs.getString("terminal_reason") != null) summary.put("terminalReason", rs.getString("terminal_reason"));
                    summary.put("createdAt", task.get("createdAt"));
                    if (rs.getTimestamp("run_started_at") != null) summary.put("startedAt", rs.getTimestamp("run_started_at").toInstant().toString());
                    if (rs.getTimestamp("run_completed_at") != null) summary.put("completedAt", rs.getTimestamp("run_completed_at").toInstant().toString());
                    return summary;
                }, arguments);
        boolean hasMore = items.size() > safeLimit;
        if (hasMore) items = new ArrayList<>(items.subList(0, safeLimit));
        items.forEach(item -> {
            Map<String, Object> task = getOwned(String.valueOf(item.get("taskId")), visitorHash);
            Map<String, Object> economy = economyForRun(visitorHash, String.valueOf(item.get("runId")), castMap(task.get("plan")));
            item.put("economy", economy);
            item.put("grossRewardMinor", economy.get("grossRewardMinor"));
            item.put("collectedRewardMinor", economy.get("collectedRewardMinor"));
            item.put("penaltyChargedMinor", economy.get("penaltyChargedMinor"));
            item.put("netMinor", economy.get("netMinor"));
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items); result.put("limit", safeLimit); result.put("retentionDays", 30);
        result.put("nextCursor", hasMore && !items.isEmpty() ? items.get(items.size() - 1).get("createdAt") : null);
        return result;
    }

    public Map<String, Object> replay(String runId, String visitorHash) {
        List<Map<String, Object>> runs = jdbc.query("SELECT s.*,t.plan_json,t.id task_id FROM demo_session s JOIN demo_task_instance t ON t.id=s.task_instance_id WHERE s.id=? AND t.visitor_hash=?",
                (rs, row) -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("runId", rs.getString("id")); value.put("taskId", rs.getString("task_id"));
                    value.put("status", rs.getString("status")); value.put("progress", rs.getDouble("progress"));
                    value.put("missionPhase", rs.getString("mission_phase")); value.put("timeScale", rs.getDouble("time_scale"));
                    value.put("simulationElapsedMs", rs.getLong("simulation_elapsed_ms"));
                    value.put("groundServiceElapsedMs", rs.getLong("ground_service_elapsed_ms"));
                    value.put("timelineEpoch", rs.getInt("timeline_epoch"));
                    value.put("groundAssetId", rs.getString("ground_asset_id"));
                    value.put("terminalReason", rs.getString("terminal_reason"));
                    value.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    if (rs.getTimestamp("started_at") != null) value.put("startedAt", rs.getTimestamp("started_at").toInstant().toString());
                    if (rs.getTimestamp("completed_at") != null) value.put("completedAt", rs.getTimestamp("completed_at").toInstant().toString());
                    value.put("plan", readMap(rs.getString("plan_json"))); return value;
                }, runId, visitorHash);
        if (runs.isEmpty()) throw new DemoException(HttpStatus.NOT_FOUND, "运行记录不存在或已清理");
        Map<String, Object> run = runs.get(0);
        Map<String, Object> plan = mapper.convertValue(run.get("plan"), new TypeReference<>() {});
        Map<String, List<Map<String, Object>>> tracksByActor = new LinkedHashMap<>();
        List<Map<String, Object>> telemetry = jdbc.query("SELECT * FROM demo_telemetry WHERE session_id=? AND superseded_by_rewind_id IS NULL ORDER BY simulation_time_ms,event_time,id LIMIT 10000", (rs, row) -> {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("actorId", rs.getString("device_id")); point.put("deviceType", rs.getString("device_type"));
            point.put("eventTime", rs.getTimestamp("event_time").toInstant().toString());
            point.put("simulationTimeMs", rs.getObject("simulation_time_ms") == null ? null : rs.getLong("simulation_time_ms"));
            point.put("longitude", rs.getDouble("longitude")); point.put("latitude", rs.getDouble("latitude"));
            point.put("altitude", rs.getDouble("altitude")); point.put("heading", rs.getDouble("heading"));
            point.put("metrics", readMap(rs.getString("metrics_json"))); return point;
        }, runId);
        telemetry.forEach(point -> tracksByActor.computeIfAbsent(String.valueOf(point.get("actorId")), ignored -> new ArrayList<>()).add(point));
        for (Map<String, Object> route : castListOfMaps(plan.get("routes"))) route.put("actualPoints", tracksByActor.getOrDefault(String.valueOf(route.get("deviceId")), List.of()));
        List<Map<String, Object>> events = jdbc.query("SELECT * FROM demo_event WHERE session_id=? AND superseded_by_rewind_id IS NULL ORDER BY simulation_time_ms,event_time,id", (rs, row) -> {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("id", rs.getString("event_instance_id")); event.put("type", rs.getString("event_type"));
            event.put("eventTime", rs.getTimestamp("event_time").toInstant().toString());
            event.put("simulationTimeMs", rs.getObject("simulation_time_ms") == null ? null : rs.getLong("simulation_time_ms"));
            event.put("progress", rs.getDouble("progress")); event.put("payload", readMap(rs.getString("payload_json"))); return event;
        }, runId);
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("id", runId); session.put("taskInstanceId", run.get("taskId")); session.put("status", run.get("status"));
        session.put("progress", run.get("progress")); session.put("missionPhase", run.get("missionPhase"));
        session.put("timeScale", run.get("timeScale")); session.put("simulationElapsedMs", run.get("simulationElapsedMs"));
        session.put("groundServiceElapsedMs", run.get("groundServiceElapsedMs"));
        session.put("timelineEpoch", run.get("timelineEpoch"));
        if (run.get("groundAssetId") != null) session.put("groundAssetId", run.get("groundAssetId"));
        if (run.get("terminalReason") != null) session.put("terminalReason", run.get("terminalReason"));
        session.put("createdAt", run.get("createdAt")); if (run.containsKey("startedAt")) session.put("startedAt", run.get("startedAt"));
        plan.put("plannedDurationSeconds", plan.get("durationSeconds"));
        plan.put("durationSeconds", Math.max(0, ((Number) run.get("simulationElapsedMs")).longValue() / 1000.0));
        plan.put("replayDurationMs", run.get("simulationElapsedMs"));
        plan.put("state", run.get("status")); plan.put("status", run.get("status")); plan.put("progress", run.get("progress"));
        plan.put("missionPhase", run.get("missionPhase")); plan.put("simulationId", runId);
        if (run.get("terminalReason") != null) plan.put("terminalReason", run.get("terminalReason"));
        plan.put("economy", economyForRun(visitorHash, runId, plan));
        List<Map<String, Object>> checkpoints = jdbc.query("SELECT id,volume_id,timeline_epoch,simulation_time_ms,mission_progress,status,created_at,used_at FROM demo_rewind_checkpoint WHERE session_id=? ORDER BY simulation_time_ms",
                (rs, row) -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("id", rs.getString("id")); value.put("volumeId", rs.getString("volume_id"));
                    value.put("timelineEpoch", rs.getInt("timeline_epoch")); value.put("simulationTimeMs", rs.getLong("simulation_time_ms"));
                    value.put("progress", rs.getDouble("mission_progress")); value.put("status", rs.getString("status"));
                    value.put("available", "AVAILABLE".equals(rs.getString("status")));
                    value.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    if (rs.getTimestamp("used_at") != null) value.put("usedAt", rs.getTimestamp("used_at").toInstant().toString());
                    return value;
                }, runId);
        plan.put("timeline", Map.of("liveProgress", run.get("progress"), "liveSimulationTimeMs", run.get("simulationElapsedMs"),
                "timelineEpoch", run.get("timelineEpoch"), "paused", false, "checkpoints", checkpoints, "rewindEligible", false));
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("session", session); snapshot.put("mission", plan); snapshot.put("devices", latestDevices(plan, telemetry));
        snapshot.put("signals", List.of()); snapshot.put("advisories", List.of()); snapshot.put("decisions", List.of());
        return Map.of("taskId", run.get("taskId"), "run", run, "snapshot", snapshot, "telemetry", telemetry, "events", events,
                "durationMs", run.get("simulationElapsedMs"));
    }

    private Map<String, Object> economyForRun(String visitorHash, String runId, Map<String, Object> plan) {
        List<Map<String, Object>> transactions = fleet.missionTransactions(visitorHash, runId);
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
        long assessed = transactions.stream().filter(item -> "AIRSPACE_FINE".equals(item.get("entryType")))
                .mapToLong(item -> Math.abs(((Number) item.getOrDefault("assessedAmountMinor", 0)).longValue())).sum();
        long charged = transactions.stream().filter(item -> "AIRSPACE_FINE".equals(item.get("entryType")))
                .mapToLong(item -> Math.abs(((Number) item.getOrDefault("amountMinor", 0)).longValue())).sum();
        long balance = transactions.isEmpty() ? ((Number) fleet.companyView(visitorHash).getOrDefault("balanceMinor", 0)).longValue()
                : ((Number) transactions.get(transactions.size() - 1).getOrDefault("balanceAfterMinor", 0)).longValue();
        List<String> collected = transactions.stream().filter(item -> "DELIVERY_REWARD".equals(item.get("entryType")))
                .map(item -> String.valueOf(item.get("referenceId"))).toList();
        List<String> collectedDiamonds = transactions.stream().filter(item -> "DIAMOND_REWARD".equals(item.get("entryType")))
                .map(item -> String.valueOf(item.get("referenceId"))).toList();
        Set<String> violatedVolumes = transactions.stream().filter(item -> "AIRSPACE_FINE".equals(item.get("entryType")))
                .map(item -> String.valueOf(item.get("referenceId"))).collect(java.util.stream.Collectors.toSet());
        List<String> forfeitedDiamonds = castListOfMaps(plan.get("rewardDiamonds")).stream()
                .filter(item -> "TRANSIT_CORRIDOR".equals(item.get("requiredAction")))
                .filter(item -> violatedVolumes.contains(String.valueOf(item.get("linkedVolumeId"))))
                .map(item -> String.valueOf(item.get("id"))).toList();
        Map<String, Object> quote = castMap(plan.get("economyQuote"));
        Map<String, Object> economy = new LinkedHashMap<>();
        economy.put("currency", "CNY"); economy.put("balanceMinor", balance);
        economy.put("grossRewardMinor", quote.getOrDefault("grossRewardMinor", 0));
        economy.put("collectedRewardMinor", rewards); economy.put("collectedCoinMinor", coinRewards);
        economy.put("groundCargoRewardMinor", groundCargoRewards); economy.put("airCoinRewardMinor", airCoinRewards);
        economy.put("timelinessRewardMinor", timelinessRewards);
        economy.put("collectedDiamondMinor", diamondRewards); economy.put("penaltyAssessedMinor", assessed);
        economy.put("penaltyChargedMinor", charged); economy.put("netMinor", rewards - charged);
        economy.put("collectedDeliveryPointIds", collected); economy.put("collectedDiamondIds", collectedDiamonds);
        economy.put("forfeitedDiamondIds", forfeitedDiamonds);
        economy.put("activeIncursions", List.of());
        economy.put("finePolicy", quote.getOrDefault("airspaceFine", Map.of())); economy.put("recentTransactions", transactions);
        return economy;
    }

    private static List<Map<String, Object>> latestDevices(Map<String, Object> plan, List<Map<String, Object>> telemetry) {
        Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
        for (Map<String, Object> point : telemetry) latest.put(String.valueOf(point.get("actorId")), point);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> actor : castListOfMaps(plan.get("actors"))) {
            String actorId = String.valueOf(actor.get("id")); Map<String, Object> point = latest.get(actorId);
            Map<String, Object> device = new LinkedHashMap<>();
            device.put("deviceId", actorId); device.put("deviceType", actor.get("deviceType")); device.put("deviceName", actor.get("name"));
            device.put("actorKind", actor.get("kind")); device.put("actorRole", actor.get("role"));
            device.put("capabilities", actor.getOrDefault("capabilities", List.of())); device.put("mediaSources", actor.getOrDefault("mediaSources", List.of()));
            if (point != null) {
                device.put("longitude", point.get("longitude")); device.put("latitude", point.get("latitude")); device.put("altitude", point.get("altitude"));
                device.put("sensorData", point.get("metrics"));
            }
            result.add(device);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Generated candidate(ScenarioTemplateCatalog.Descriptor descriptor, String seed, Map<String, Object> parameters,
                                String attemptDomain, FleetService.GroundVehicle groundVehicle,
                                FleetService.AirVehicle airVehicle) {
        Map<String, Object> plan = templates.materializeBase(descriptor.id());
        plan.put("groundVehicle", groundVehicle.view());
        plan.put("airVehicle", airVehicle.view());
        castListOfMaps(plan.get("actors")).stream()
                .filter(actor -> descriptor.vehicleId().equals(String.valueOf(actor.get("id"))))
                .findFirst().ifPresent(actor -> {
                    actor.put("name", groundVehicle.name());
                    actor.put("initialBattery", groundVehicle.batteryPercent());
                    actor.put("fleetAssetId", groundVehicle.assetId());
                    actor.put("fleetTypeId", groundVehicle.typeId());
                });
        castListOfMaps(plan.get("actors")).stream()
                .filter(actor -> descriptor.uavId().equals(String.valueOf(actor.get("id"))))
                .findFirst().ifPresent(actor -> {
                    actor.put("name", airVehicle.name());
                    actor.put("initialBattery", airVehicle.batteryPercent());
                    actor.put("fleetAssetId", airVehicle.assetId());
                    actor.put("fleetTypeId", airVehicle.typeId());
                });
        Map<String, Object> baseGround = templates.route(plan, descriptor.groundRouteId());
        Map<String, Object> fallbackAir = templates.route(plan, descriptor.airRouteId());
        DeterministicRandom groundRandom = new DeterministicRandom(DeterministicRandom.derive(seed, attemptDomain + "/ground"));
        List<ScenarioTemplateCatalog.GroundVariant> groundVariants = templates.groundVariants(descriptor.id(), baseGround);
        ScenarioTemplateCatalog.GroundVariant groundVariant = groundVariants.get(groundRandom.nextInt(groundVariants.size()));
        Map<String, Object> fallbackGround = new LinkedHashMap<>(baseGround);
        fallbackGround.put("points", groundVariant.points());
        fallbackGround.put("distanceMeters", MissionMath.polylineDistance(points(fallbackGround)));
        fallbackGround.put("groundRouteVariantId", groundVariant.id());
        fallbackGround.put("groundRouteVariantName", groundVariant.name());
        List<double[]> fallbackGroundPoints = points(fallbackGround);
        DeterministicRandom targetRandom = new DeterministicRandom(DeterministicRandom.derive(seed, attemptDomain + "/targets"));
        boolean reverse = !descriptor.fixedGroundEndpoints() && targetRandom.nextBoolean();
        List<Double> middle = new ArrayList<>(List.of(.24, .38, .52, .68, .78));
        deterministicShuffle(middle, targetRandom);
        int anchorCount = switch (String.valueOf(parameters.get("deliveryDensity"))) { case "LOW" -> 1; case "HIGH" -> 3; default -> 2; };
        List<Double> selected = new ArrayList<>(middle.subList(0, anchorCount));
        selected.sort(Double::compareTo);
        double startFraction = descriptor.fixedGroundEndpoints() ? 0 : List.of(0.0, .08, .14).get(targetRandom.nextInt(3));
        double endFraction = descriptor.fixedGroundEndpoints() ? 1 : List.of(.86, .93, 1.0).get(targetRandom.nextInt(3));
        List<Double> fractions = new ArrayList<>();
        fractions.add(startFraction); fractions.addAll(selected); fractions.add(endFraction);
        if (reverse) { Collections.reverse(fractions); }
        List<double[]> anchors = fractions.stream().map(value -> MissionMath.sample(fallbackGroundPoints, value)).toList();

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("templateId", descriptor.id()); request.put("templateVersion", ScenarioTemplateCatalog.VERSION);
        request.put("contractVersion", BaiduRouteProvider.CONTRACT_VERSION); request.put("coordType", "bd09ll");
        request.put("tactics", 2); request.put("alternatives", 0); request.put("anchors", coordinateLists(anchors));
        request.put("groundRouteVariantId", groundVariant.id());
        request.put("endpointPolicy", "EXACT_TASK_ANCHORS_V1");
        RouteArtifact artifact = resolveRouteArtifact(descriptor, request, fallbackGround, reverse);
        Map<String, Object> ground = new LinkedHashMap<>(fallbackGround);
        ground.putAll(artifact.route());
        ground.put("routeSource", artifact.source());
        ground.put("routeArtifactId", artifact.id());
        ground.put("coordinateSystem", "BD09LL");
        ground.put("elevationMode", "CLAMP_TO_GROUND");
        ground.put("nominalSpeedKph", groundVehicle.speedKph());
        ground.put("fleetAssetId", groundVehicle.assetId());
        List<double[]> groundPoints = points(ground);
        if (groundPoints.size() < 2) throw new CandidateRejected("GROUND_EMPTY");

        double altitude = number(parameters.get("flightAltitudeMeters"), descriptor.defaultAltitude());
        double launchFraction = List.of(.15, .18, .22).get(targetRandom.nextInt(3));
        double recoveryFraction = List.of(.80, .85, .88).get(targetRandom.nextInt(3));
        double[] launch = MissionMath.sample(groundPoints, launchFraction);
        double[] recovery = MissionMath.sample(groundPoints, recoveryFraction);
        launch[2] = 2.35; recovery[2] = 2.35;
        Map<String, Object> air = generateAirRoute(fallbackAir, launch, recovery, altitude, seed, attemptDomain);
        air.put("nominalSpeedKph", airVehicle.speedKph());
        air.put("fleetAssetId", airVehicle.assetId());
        replaceRoute(plan, descriptor.groundRouteId(), ground);
        replaceRoute(plan, descriptor.airRouteId(), air);

        List<double[]> airPoints = points(air);
        plan.put("groundAnchors", coordinateLists(anchors));
        plan.put("launchPoint", List.of(launch[0], launch[1], launch[2]));
        plan.put("recoveryPoint", List.of(recovery[0], recovery[1], recovery[2]));
        int airspaceThemeCount = (int) number(parameters.get("airspaceThemeCount"), 2);
        Map<String, Object> airspace = airspace(points(fallbackAir), points(air), seed, attemptDomain,
                airspaceThemeCount);
        plan.put("airspace", airspace);
        plan.put("airspaceProfile", airspace.get("airspaceProfile"));
        List<Map<String, Object>> deliveryPoints = deliveryPoints(descriptor, ground, air, selected, airspace,
                String.valueOf(parameters.get("deliveryDensity")), groundVehicle.cargoMultiplier(),
                airVehicle.cargoMultiplier());
        plan.put("deliveryPoints", deliveryPoints);
        List<Map<String, Object>> rewardDiamonds = rewardDiamonds(descriptor, air, airspace, deliveryPoints, seed, attemptDomain);
        plan.put("rewardDiamonds", rewardDiamonds);
        plan.put("deliveryTargets", deliveryPoints.stream().map(point -> Map.of(
                "id", point.get("id"), "kind", point.get("kind"), "position", point.get("position"))).toList());
        long baseGroundRewardMinor = deliveryPoints.stream().filter(point -> "GROUND".equals(point.get("kind")))
                .mapToLong(point -> ((Number) point.get("baseRewardMinor")).longValue()).sum();
        long groundCargoRewardMinor = deliveryPoints.stream().filter(point -> "GROUND".equals(point.get("kind")))
                .mapToLong(point -> ((Number) point.get("rewardMinor")).longValue()).sum();
        long baseAirRewardMinor = deliveryPoints.stream().filter(point -> "AIR".equals(point.get("kind")))
                .mapToLong(point -> ((Number) point.get("baseRewardMinor")).longValue()).sum();
        long airCoinRewardMinor = deliveryPoints.stream().filter(point -> "AIR".equals(point.get("kind")))
                .mapToLong(point -> ((Number) point.get("rewardMinor")).longValue()).sum();
        long coinRewardMinor = groundCargoRewardMinor + airCoinRewardMinor;
        long diamondPotentialMinor = rewardDiamonds.stream().mapToLong(point -> ((Number) point.get("rewardMinor")).longValue()).sum();
        long grossRewardMinor = coinRewardMinor + diamondPotentialMinor;
        Map<String, Object> economyQuote = new LinkedHashMap<>();
        economyQuote.put("currency", "CNY"); economyQuote.put("grossRewardMinor", grossRewardMinor);
        economyQuote.put("coinRewardMinor", coinRewardMinor); economyQuote.put("diamondPotentialMinor", diamondPotentialMinor);
        economyQuote.put("baseGroundRewardMinor", baseGroundRewardMinor);
        economyQuote.put("groundCargoRewardMinor", groundCargoRewardMinor);
        economyQuote.put("baseAirRewardMinor", baseAirRewardMinor);
        economyQuote.put("airCargoRewardMinor", airCoinRewardMinor);
        economyQuote.put("airCoinRewardMinor", airCoinRewardMinor);
        economyQuote.put("deliveryPointCount", deliveryPoints.size()); economyQuote.put("diamondCount", rewardDiamonds.size());
        economyQuote.put("ruleVersion", RULESET_VERSION);
        economyQuote.put("airspaceFine", Map.of("baseMinor", 120_000, "perSecondMinor", 12_000, "maximumPerIncursionMinor", 480_000));
        economyQuote.put("airspacePenalties", castListOfMaps(airspace.get("volumes")).stream().map(volume -> Map.of(
                "volumeId", volume.get("id"), "ruleType", volume.get("ruleType"),
                "penaltyPolicy", volume.getOrDefault("penaltyPolicy", Map.of()))).toList());
        plan.put("economyQuote", economyQuote);
        boolean trafficSignalsEnabled = Boolean.TRUE.equals(parameters.get("trafficSignalsEnabled"));
        List<Map<String, Object>> plannedTrafficLights = trafficSignalsEnabled
                ? trafficLights(ground, descriptor, seed, attemptDomain) : List.of();
        plan.put("trafficLights", plannedTrafficLights);
        plan.put("trafficLightStatus", trafficSignalsEnabled
                ? Map.of("provider", "TASK_INSTANCE_SEEDED", "state", "READY", "message", "任务级路口信号计划已冻结",
                        "simulated", true, "liveLightCount", plannedTrafficLights.size())
                : Map.of("provider", "TASK_INSTANCE_SEEDED", "state", "DISABLED", "message", "本次任务未启用交通信号灯",
                        "simulated", true, "liveLightCount", 0));
        plan.put("routeSource", artifact.source());
        plan.put("routeArtifactId", artifact.id());
        plan.put("groundRouteVariantId", groundVariant.id());
        plan.put("groundRouteVariantName", groundVariant.name());
        plan.put("uavRouteMode", air.get("routeMode"));
        plan.put("uavWaypointCount", points(air).size());
        plan.put("noFlyZoneCount", castListOfMaps(airspace.get("volumes")).stream().filter(AirspaceGeometry::blocking).count());
        plan.put("deliveryPointCount", deliveryPoints.size());
        plan.put("diamondCount", rewardDiamonds.size());
        plan.put("grossRewardMinor", grossRewardMinor);
        plan.put("airspaceVolumeCount", rawList(airspace.get("volumes")).size());
        plan.put("coordinateSystem", "BD09LL");
        double groundDistanceMeters = number(ground.get("distanceMeters"), MissionMath.polylineDistance(groundPoints));
        double groundTravelSeconds = groundServiceSeconds(groundDistanceMeters,
                number(ground.get("nominalSpeedKph"), 18), 0);
        double signalDelaySeconds = trafficDelaySeconds(castListOfMaps(plan.get("trafficLights")));
        List<Map<String, Object>> airspaceVolumes = castListOfMaps(airspace.get("volumes"));
        List<double[]> safeCompletion = safeCompletionRoute(airPoints, airspaceVolumes);
        if (safeCompletion.isEmpty()) throw new CandidateRejected("NO_SAFE_AIRSPACE_ACTION");
        List<double[]> riskDetourCompletion = detourRiskVolumes(safeCompletion, airspaceVolumes);
        double acceptRiskEquivalentMeters = MissionMath.polylineDistance(safeCompletion)
                + yellowEquivalentExtraMeters(safeCompletion, airspaceVolumes);
        double riskDetourEquivalentMeters = MissionMath.polylineDistance(riskDetourCompletion)
                + yellowEquivalentExtraMeters(riskDetourCompletion, airspaceVolumes);
        double minimumAirEquivalentMeters = Math.min(acceptRiskEquivalentMeters, riskDetourEquivalentMeters);
        double maximumAirEquivalentMeters = Math.max(acceptRiskEquivalentMeters, riskDetourEquivalentMeters);
        double airSortieSeconds = Math.max(MissionMath.polylineDistance(safeCompletion), MissionMath.polylineDistance(riskDetourCompletion))
                / (number(air.get("nominalSpeedKph"), 20) / 3.6) + 40;
        double launchAtSeconds = groundTravelSeconds * launchFraction + signalDelaySeconds / 3;
        double vehicleAtRecoverySeconds = groundTravelSeconds * recoveryFraction + signalDelaySeconds * 2 / 3;
        double uavAtRecoverySeconds = launchAtSeconds + airSortieSeconds;
        double rendezvousWaitSeconds = Math.abs(vehicleAtRecoverySeconds - uavAtRecoverySeconds);
        double rendezvousAtSeconds = Math.max(vehicleAtRecoverySeconds, uavAtRecoverySeconds);
        double estimated = Math.ceil(Math.max(groundTravelSeconds + signalDelaySeconds,
                rendezvousAtSeconds + groundTravelSeconds * (1 - recoveryFraction)));
        double referenceGroundSeconds = groundServiceSeconds(groundDistanceMeters, 18, signalDelaySeconds);
        double estimatedGroundSeconds = groundServiceSeconds(groundDistanceMeters,
                number(ground.get("nominalSpeedKph"), 18), signalDelaySeconds);
        double estimatedTimelinessFactor = timelinessFactor(referenceGroundSeconds, estimatedGroundSeconds);
        long estimatedTimelinessRewardMinor = moneyRound(baseGroundRewardMinor * estimatedTimelinessFactor);
        long maximumTimelinessRewardMinor = moneyRound(baseGroundRewardMinor * 2.5);
        long estimatedGrossRewardMinor = groundCargoRewardMinor + airCoinRewardMinor + diamondPotentialMinor + estimatedTimelinessRewardMinor;
        long maximumGrossRewardMinor = groundCargoRewardMinor + airCoinRewardMinor + diamondPotentialMinor + maximumTimelinessRewardMinor;
        double estimatedBatteryUsePercent = number(ground.get("distanceMeters"), MissionMath.polylineDistance(groundPoints))
                / Math.max(.001, groundVehicle.fullRangeKm() * 1000) * 100;
        double estimatedAirBatteryUsePercentMin = minimumAirEquivalentMeters
                / Math.max(.001, airVehicle.fullRangeKm() * 1000) * 100;
        double estimatedAirBatteryUsePercentMax = maximumAirEquivalentMeters
                / Math.max(.001, airVehicle.fullRangeKm() * 1000) * 100;
        double yellowExtraBatteryUsePercent = yellowEquivalentExtraMeters(safeCompletion, airspaceVolumes)
                / Math.max(.001, airVehicle.fullRangeKm() * 1000) * 100;
        economyQuote.put("referenceGroundSeconds", round(referenceGroundSeconds, 1));
        economyQuote.put("estimatedGroundSeconds", round(estimatedGroundSeconds, 1));
        economyQuote.put("estimatedTimelinessFactor", round(estimatedTimelinessFactor, 3));
        economyQuote.put("estimatedTimelinessRewardMinor", estimatedTimelinessRewardMinor);
        economyQuote.put("maximumTimelinessRewardMinor", maximumTimelinessRewardMinor);
        economyQuote.put("estimatedGrossRewardMinor", estimatedGrossRewardMinor);
        economyQuote.put("maximumGrossRewardMinor", maximumGrossRewardMinor);
        economyQuote.put("estimatedBatteryUsePercent", round(estimatedBatteryUsePercent, 1));
        economyQuote.put("estimatedGroundBatteryUsePercent", round(estimatedBatteryUsePercent, 1));
        economyQuote.put("estimatedAirBatteryUsePercent", round(estimatedAirBatteryUsePercentMax, 1));
        economyQuote.put("estimatedAirBatteryUsePercentMin", round(estimatedAirBatteryUsePercentMin, 1));
        economyQuote.put("estimatedAirBatteryUsePercentMax", round(estimatedAirBatteryUsePercentMax, 1));
        economyQuote.put("estimatedAirBatteryUseRangePercent", Map.of("minimum", round(estimatedAirBatteryUsePercentMin, 1),
                "maximum", round(estimatedAirBatteryUsePercentMax, 1)));
        economyQuote.put("yellowRiskExtraBatteryUsePercent", round(yellowExtraBatteryUsePercent, 1));
        double returnReservePercent = number(parameters.get("returnReservePercent"), 25);
        economyQuote.put("batterySufficient", groundVehicle.batteryPercent() + 1e-6
                >= estimatedBatteryUsePercent + returnReservePercent);
        economyQuote.put("groundBatterySufficient", groundVehicle.batteryPercent() + 1e-6
                >= estimatedBatteryUsePercent + returnReservePercent);
        economyQuote.put("airBatterySufficient", airVehicle.batteryPercent() + 1e-6
                >= estimatedAirBatteryUsePercentMin + returnReservePercent);
        economyQuote.put("grossRewardMinor", maximumGrossRewardMinor);
        plan.put("grossRewardMinor", maximumGrossRewardMinor);
        plan.put("rendezvousPlan", Map.of("launchRouteProgress", Math.round(launchFraction * 100), "recoveryRouteProgress", Math.round(recoveryFraction * 100),
                "vehicleArrivalSeconds", Math.ceil(vehicleAtRecoverySeconds), "uavArrivalSeconds", Math.ceil(uavAtRecoverySeconds),
                "maximumWaitSeconds", 180, "estimatedWaitSeconds", Math.ceil(rendezvousWaitSeconds)));
        plan.put("estimatedDurationSeconds", estimated);
        plan.put("durationSeconds", estimated);
        scheduleDynamicAirspace(airspace, launchAtSeconds, airSortieSeconds, estimated);
        plan.put("phaseSchedule", Map.of("DEPART", List.of(0, 18), "TAKEOFF", List.of(18, 24), "DELIVERING", List.of(24, 78), "RETURNING", List.of(78, 90), "DOCKED", List.of(90, 100)));
        plan.put("events", generatedEvents(descriptor, estimated));
        return new Generated(plan, artifact.id());
    }

    private static List<Map<String, Object>> deliveryPoints(ScenarioTemplateCatalog.Descriptor descriptor,
                                                              Map<String, Object> ground, Map<String, Object> air,
                                                              List<Double> selectedGroundFractions,
                                                              Map<String, Object> airspace, String intensity,
                                                              double groundCargoMultiplier,
                                                              double airCargoMultiplier) {
        int desired = switch (intensity) { case "LOW" -> 1; case "HIGH" -> 3; default -> 2; };
        List<Map<String, Object>> airspaceVolumes = castListOfMaps(airspace.get("volumes"));
        List<double[]> groundPoints = points(ground), airPoints = points(air);
        double groundDistance = number(ground.get("distanceMeters"), MissionMath.polylineDistance(groundPoints));
        double airDistance = number(air.get("distanceMeters"), MissionMath.polylineDistance(airPoints));
        List<Map<String, Object>> result = new ArrayList<>();

        double previousProgress = 0;
        for (int index = 0; index < Math.min(desired, selectedGroundFractions.size()); index++) {
            double progress = safeDeliveryProgress(groundPoints, selectedGroundFractions.get(index), previousProgress + .025,
                    .92, .35, airspaceVolumes);
            double legMeters = groundDistance * Math.max(0, progress - previousProgress);
            long reward = rewardDenomination(Math.max(30_000, Math.min(120_000, Math.round(26_000 + legMeters * 38))));
            long cargoReward = moneyRound(reward * groundCargoMultiplier);
            result.add(deliveryPoint("DELIVERY-GROUND-" + String.format("%02d", index + 1), "GROUND",
                    descriptor.vehicleId(), descriptor.groundRouteId(), MissionMath.sample(groundPoints, progress),
                    progress, reward, cargoReward, 14, 0));
            previousProgress = progress;
        }

        double lastInteractionEnd = airspaceVolumes.stream()
                .map(volume -> AirspaceGeometry.conflict(airPoints, volume, 0))
                .filter(Objects::nonNull).mapToDouble(AirspaceGeometry.Conflict::endProgress).max().orElse(65);
        double postConflict = Math.min(.92, lastInteractionEnd / 100 + .075);
        List<Double> requested = switch (desired) {
            case 1 -> List.of(postConflict);
            case 3 -> List.of(.16, .38, postConflict);
            default -> List.of(.26, postConflict);
        };
        List<Double> ordered = new ArrayList<>(requested); ordered.sort(Double::compareTo);
        previousProgress = 0;
        for (int index = 0; index < ordered.size(); index++) {
            boolean downstreamReward = index == ordered.size() - 1;
            double minimum = downstreamReward ? lastInteractionEnd / 100 + .04 : previousProgress + .025;
            double progress = safeDeliveryProgress(airPoints, ordered.get(index), minimum, .94,
                    MissionMath.sample(airPoints, ordered.get(index))[2], airspaceVolumes);
            double legMeters = airDistance * Math.max(0, progress - previousProgress);
            long raw = Math.round(42_000 + legMeters * 62 + (downstreamReward ? 18_000 : 0));
            long reward = rewardDenomination(Math.max(50_000, Math.min(180_000, raw)));
            long cargoReward = moneyRound(reward * airCargoMultiplier);
            result.add(deliveryPoint("DELIVERY-AIR-" + String.format("%02d", index + 1), "AIR",
                    descriptor.uavId(), descriptor.airRouteId(), MissionMath.sample(airPoints, progress),
                    progress, reward, cargoReward, 20, 18));
            previousProgress = progress;
        }
        if (result.stream().noneMatch(point -> "GROUND".equals(point.get("kind")))
                || result.stream().noneMatch(point -> "AIR".equals(point.get("kind"))))
            throw new CandidateRejected("DELIVERY_POINTS_EMPTY");
        return result;
    }

    private static double safeDeliveryProgress(List<double[]> route, double requested, double minimum, double maximum,
                                               double altitude, List<Map<String, Object>> blockingVolumes) {
        double start = MissionMath.clamp(Math.max(requested, minimum), minimum, maximum);
        for (int step = 0; step <= 48; step++) {
            for (double direction : step == 0 ? List.of(0.0) : List.of(1.0, -1.0)) {
                double progress = MissionMath.clamp(start + direction * step * .01, minimum, maximum);
                double[] point = MissionMath.sample(route, progress); point[2] = altitude;
                boolean safe = blockingVolumes.stream().allMatch(volume ->
                        AirspaceGeometry.distanceToFootprintMeters(volume, point) >= DELIVERY_AIRSPACE_BUFFER_METERS);
                if (safe) return progress;
            }
        }
        throw new CandidateRejected("DELIVERY_POINT_IN_NO_FLY_BUFFER");
    }

    private static Map<String, Object> deliveryPoint(String id, String kind, String actorId, String routeId,
                                                     double[] point, double progress, long baseRewardMinor, long rewardMinor,
                                                     double triggerRadiusMeters, double altitudeToleranceMeters) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id); value.put("kind", kind); value.put("actorId", actorId); value.put("routeId", routeId);
        value.put("position", List.of(point[0], point[1], "GROUND".equals(kind) ? .35 : point[2]));
        value.put("routeProgress", Math.round(progress * 10_000) / 100.0);
        value.put("baseRewardMinor", baseRewardMinor); value.put("rewardMinor", rewardMinor);
        value.put("visualTier", rewardMinor <= 50_000 ? "SMALL" : rewardMinor <= 120_000 ? "MEDIUM" : "LARGE");
        value.put("triggerRadiusMeters", triggerRadiusMeters); value.put("altitudeToleranceMeters", altitudeToleranceMeters);
        return value;
    }

    private static long rewardDenomination(long rawMinor) {
        return REWARD_DENOMINATIONS_MINOR.stream()
                .min(Comparator.comparingLong(value -> Math.abs(value - rawMinor))).orElse(50_000L);
    }

    private static long moneyRound(double rawMinor) { return Math.round(rawMinor / 100.0) * 100; }
    static double groundServiceSeconds(double distanceMeters, double speedKph, double signalDelaySeconds) {
        return Math.max(0, distanceMeters) / (Math.max(.1, speedKph) / 3.6) + Math.max(0, signalDelaySeconds);
    }
    static double timelinessFactor(double referenceGroundSeconds, double actualGroundSeconds) {
        return MissionMath.clamp(Math.max(1, referenceGroundSeconds) / Math.max(1, actualGroundSeconds), .5, 2.5);
    }
    private static double round(double value, int digits) {
        double scale = Math.pow(10, digits);
        return Math.round(value * scale) / scale;
    }

    /** Creates 2-5 reproducible diamonds, prioritising one manoeuvre challenge per airspace. */
    private static List<Map<String, Object>> rewardDiamonds(ScenarioTemplateCatalog.Descriptor descriptor,
                                                            Map<String, Object> air, Map<String, Object> airspace,
                                                            List<Map<String, Object>> deliveryPoints,
                                                            String seed, String attemptDomain) {
        List<double[]> originalRoute = points(air);
        DeterministicRandom random = new DeterministicRandom(DeterministicRandom.derive(seed, attemptDomain + "/diamond"));
        List<Map<String, Object>> volumes = new ArrayList<>(castListOfMaps(airspace.get("volumes")));
        volumes.sort(Comparator.comparingDouble(volume -> number(volume.get("routeProgress"), 0)));
        int minimum = Math.max(2, volumes.size());
        int desired = minimum + random.nextInt(6 - minimum);
        List<Map<String, Object>> diamonds = new ArrayList<>();
        List<double[]> collectionRoute = new ArrayList<>(originalRoute.stream().map(double[]::clone).toList());
        for (Map<String, Object> volume : volumes) {
            ChallengeDiamondPlacement placement = airspaceChallengeDiamond(descriptor, collectionRoute, volume,
                    "DIAMOND-AIRSPACE-" + String.format("%02d", diamonds.size() + 1), random);
            diamonds.add(placement.diamond());
            collectionRoute = placement.actionRoute();
        }

        double originalDistance = Math.max(1, MissionMath.polylineDistance(originalRoute));
        int routeRewards = desired - diamonds.size();
        double routeMinimum = Math.max(.025, Math.min(.07, 30 / originalDistance));
        double routeLimit = 1 - routeMinimum;
        for (int index = 0; index < routeRewards; index++) {
            double requested = routeMinimum + random.nextDouble() * (routeLimit - routeMinimum);
            double progress = safeRouteDiamondProgress(originalRoute, collectionRoute, requested, routeMinimum, routeLimit,
                    volumes, deliveryPoints, diamonds);
            double[] target = MissionMath.sample(originalRoute, progress);
            Map<String, Object> diamond = baseDiamond("DIAMOND-ROUTE-" + String.format("%02d", index + 1), descriptor, target,
                    Math.round(progress * 10_000) / 100.0, 13, 9);
            diamond.put("challengeType", "ROUTE"); diamond.put("actionLabel", "按航线飞行");
            diamonds.add(diamond);
        }
        if (diamonds.size() != desired) throw new CandidateRejected("DIAMOND_COUNT_INVALID");
        List<double[]> guaranteedCollectionRoute = collectionRoute;
        if (diamonds.stream().anyMatch(diamond -> !routePassesReward(guaranteedCollectionRoute,
                coordinate(diamond.get("position")), number(diamond.get("triggerRadiusMeters"), 13),
                number(diamond.get("altitudeToleranceMeters"), 9))))
            throw new CandidateRejected("DIAMOND_COLLECTION_ROUTE_INVALID");
        return diamonds;
    }

    private static ChallengeDiamondPlacement airspaceChallengeDiamond(ScenarioTemplateCatalog.Descriptor descriptor,
                                                                       List<double[]> sourceRoute,
                                                                       Map<String, Object> volume, String id,
                                                                       DeterministicRandom random) {
        String rule = String.valueOf(volume.get("ruleType"));
        if ("ALTITUDE_CORRIDOR".equals(rule)) {
            List<double[]> transit = AirspaceGeometry.transitCorridor(sourceRoute, volume);
            double[] center = polygonCenter(castCoordinatePolygon(volume.get("footprint")));
            double[] target = new double[]{center[0], center[1], number(volume.get("targetAltitudeMeters"), 72)};
            List<double[]> detour = AirspaceGeometry.detour(sourceRoute, volume);
            if (AirspaceGeometry.conflict(transit, volume, 0) != null
                    || !routePassesReward(transit, target, 12, 5)
                    || routePassesReward(sourceRoute, target, 12, 5)
                    || routePassesReward(detour, target, 12, 5))
                throw new CandidateRejected("CORRIDOR_DIAMOND_UNREACHABLE");
            return new ChallengeDiamondPlacement(airspaceDiamond(id, descriptor, volume, target, transit,
                    "TRANSIT_CORRIDOR", "穿越高度走廊", 12, 5), transit);
        }

        List<double[]> detour = AirspaceGeometry.detour(sourceRoute, volume);
        boolean detourAvailable = AirspaceGeometry.conflict(detour, volume, 0) == null;
        if ("TEMPORARY_NO_FLY".equals(rule)) {
            double climbAltitude = number(volume.get("ceilingMeters"), 100) + 12;
            List<double[]> climb = climbAltitude <= 100
                    ? AirspaceGeometry.climbOver(sourceRoute, volume, climbAltitude) : List.of();
            boolean climbAvailable = !climb.isEmpty() && AirspaceGeometry.conflict(climb, volume, 0) == null;
            if (!detourAvailable && !climbAvailable) throw new CandidateRejected("NO_DIAMOND_ACTION_ROUTE");
            String action = climbAvailable && (!detourAvailable || random.nextBoolean()) ? "CLIMB_OVER" : "DETOUR";
            List<double[]> actionRoute = "CLIMB_OVER".equals(action) ? climb : detour;
            double[] target = "CLIMB_OVER".equals(action)
                    ? climbDiamondTarget(actionRoute, climbAltitude)
                    : detourDiamondTarget(sourceRoute, actionRoute, volume);
            List<double[]> wrong = "CLIMB_OVER".equals(action) ? detour : climb;
            if (AirspaceGeometry.contains(volume, target)
                    || !routePassesReward(actionRoute, target, 10, 8)
                    || routePassesReward(sourceRoute, target, 10, 8)
                    || (!wrong.isEmpty() && routePassesReward(wrong, target, 10, 8)))
                throw new CandidateRejected("DIAMOND_ACTION_NOT_EXCLUSIVE");
            return new ChallengeDiamondPlacement(airspaceDiamond(id, descriptor, volume, target, actionRoute, action,
                    "CLIMB_OVER".equals(action) ? "爬升越过" : "从侧面绕飞", 10, 8), actionRoute);
        }

        if (!detourAvailable) throw new CandidateRejected("NO_DIAMOND_ACTION_ROUTE_" + rule);
        double[] target = detourDiamondTarget(sourceRoute, detour, volume);
        if (AirspaceGeometry.contains(volume, target)
                || !routePassesReward(detour, target, 10, 8)
                || routePassesReward(sourceRoute, target, 10, 8))
            throw new CandidateRejected("DIAMOND_ACTION_NOT_EXCLUSIVE");
        return new ChallengeDiamondPlacement(airspaceDiamond(id, descriptor, volume, target, detour,
                "DETOUR", "从侧面绕飞", 10, 8), detour);
    }

    private record ChallengeDiamondPlacement(Map<String, Object> diamond, List<double[]> actionRoute) {}

    private static Map<String, Object> airspaceDiamond(String id, ScenarioTemplateCatalog.Descriptor descriptor,
                                                        Map<String, Object> volume, double[] target,
                                                        List<double[]> actionRoute, String action, String label,
                                                        double triggerRadius, double altitudeTolerance) {
        Map<String, Object> diamond = baseDiamond(id, descriptor, target,
                Math.round(AirspaceGeometry.closestRouteProgress(actionRoute, target) * 100) / 100.0,
                triggerRadius, altitudeTolerance);
        diamond.put("challengeType", "AIRSPACE"); diamond.put("linkedVolumeId", volume.get("id"));
        diamond.put("linkedVolumeLabel", volume.get("label")); diamond.put("requiredAction", action);
        diamond.put("actionLabel", label);
        return diamond;
    }

    private static Map<String, Object> baseDiamond(String id, ScenarioTemplateCatalog.Descriptor descriptor,
                                                    double[] target, double routeProgress,
                                                    double triggerRadius, double altitudeTolerance) {
        Map<String, Object> diamond = new LinkedHashMap<>();
        diamond.put("id", id); diamond.put("rewardType", "DIAMOND"); diamond.put("kind", "AIR");
        diamond.put("actorId", descriptor.uavId()); diamond.put("routeId", descriptor.airRouteId());
        diamond.put("position", List.of(target[0], target[1], target[2])); diamond.put("routeProgress", routeProgress);
        diamond.put("rewardMinor", DIAMOND_REWARD_MINOR); diamond.put("visualTier", "DIAMOND");
        diamond.put("triggerRadiusMeters", triggerRadius); diamond.put("altitudeToleranceMeters", altitudeTolerance);
        return diamond;
    }

    private static double[] climbDiamondTarget(List<double[]> route, double altitude) {
        List<double[]> crest = route.stream().filter(point -> point[2] >= altitude - .01).toList();
        if (crest.size() < 2) throw new CandidateRejected("DIAMOND_CLIMB_CREST_MISSING");
        double[] first = crest.get(0), last = crest.get(crest.size() - 1);
        return new double[]{(first[0] + last[0]) / 2, (first[1] + last[1]) / 2, altitude};
    }

    private static double[] detourDiamondTarget(List<double[]> originalRoute, List<double[]> detour,
                                                 Map<String, Object> volume) {
        AirspaceGeometry.Conflict conflict = AirspaceGeometry.conflict(originalRoute, volume, 0);
        if (conflict == null) throw new CandidateRejected("DIAMOND_DETOUR_POINT_MISSING");
        double[] rejoin = MissionMath.sample(originalRoute, Math.min(100, conflict.endProgress() + 1.5) / 100);
        int rejoinIndex = java.util.stream.IntStream.range(1, detour.size())
                .filter(index -> MissionMath.distance(detour.get(index), rejoin) < .1).findFirst().orElse(-1);
        if (rejoinIndex < 1) throw new CandidateRejected("DIAMOND_DETOUR_ANCHOR_MISSING");
        return detour.get(rejoinIndex - 1).clone();
    }

    private static double safeRouteDiamondProgress(List<double[]> route, List<double[]> collectionRoute,
                                                    double requested, double minimum, double maximum,
                                                    List<Map<String, Object>> volumes,
                                                    List<Map<String, Object>> deliveryPoints,
                                                    List<Map<String, Object>> existing) {
        for (int step = 0; step <= 40; step++) {
            for (double direction : step == 0 ? List.of(0.0) : List.of(1.0, -1.0)) {
                double progress = MissionMath.clamp(requested + direction * step * .003, minimum, maximum);
                double[] point = MissionMath.sample(route, progress);
                boolean clearVolumes = volumes.stream().allMatch(volume ->
                        AirspaceGeometry.distanceToFootprintMeters(volume, point) >= DELIVERY_AIRSPACE_BUFFER_METERS);
                boolean clearDiamonds = existing.stream().map(item -> coordinate(item.get("position"))).filter(Objects::nonNull)
                        .allMatch(other -> horizontalDistance(point, other) >= DIAMOND_MIN_SPACING_METERS);
                boolean clearDelivery = deliveryPoints.stream().map(item -> coordinate(item.get("position"))).filter(Objects::nonNull)
                        .allMatch(other -> horizontalDistance(point, other) >= DELIVERY_AIRSPACE_BUFFER_METERS);
                boolean collectible = routePassesReward(collectionRoute, point, 13, 9);
                if (clearVolumes && clearDiamonds && clearDelivery && collectible
                        && outsideAirspaceManeuverWindows(route, progress, volumes)) return progress;
            }
        }
        throw new CandidateRejected("ROUTE_DIAMOND_BUFFER_INVALID");
    }

    private static boolean outsideAirspaceManeuverWindows(List<double[]> route, double progress,
                                                            List<Map<String, Object>> volumes) {
        double distance = Math.max(1, MissionMath.polylineDistance(route));
        for (Map<String, Object> volume : volumes) {
            AirspaceGeometry.Conflict conflict = AirspaceGeometry.conflict(route, volume, 0);
            if (conflict == null) continue;
            double clearanceMeters = "ALTITUDE_CORRIDOR".equals(volume.get("ruleType")) ? 140 : 75;
            double clearance = clearanceMeters / distance;
            if (progress >= conflict.startProgress() / 100 - clearance
                    && progress <= conflict.endProgress() / 100 + clearance) return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static List<List<Number>> castCoordinatePolygon(Object value) {
        return value instanceof List<?> list ? (List<List<Number>>) list : List.of();
    }

    private static boolean routePassesReward(List<double[]> route, double[] target, double horizontalRadius,
                                             double altitudeTolerance) {
        for (int index = 1; index < route.size(); index++) {
            if (AirspaceGeometry.segmentPassesPoint(route.get(index - 1), route.get(index), target,
                    horizontalRadius, altitudeTolerance, true)) return true;
        }
        return false;
    }

    private static List<double[]> validationActionRoute(List<double[]> route, Map<String, Object> volume,
                                                         String action) {
        return switch (action) {
            case "TRANSIT_CORRIDOR" -> AirspaceGeometry.transitCorridor(route, volume);
            case "DETOUR" -> AirspaceGeometry.detour(route, volume);
            case "CLIMB_OVER" -> {
                double target = number(volume.get("ceilingMeters"), 100) + 12;
                yield target <= 100 ? AirspaceGeometry.climbOver(route, volume, target) : List.of();
            }
            default -> List.of();
        };
    }

    /** Builds the one complete route that collects every airspace challenge reward in flight order. */
    static List<double[]> diamondCollectionRoute(List<double[]> original, List<Map<String, Object>> sourceVolumes,
                                                  List<Map<String, Object>> diamonds) {
        List<double[]> route = new ArrayList<>(original.stream().map(double[]::clone).toList());
        List<Map<String, Object>> volumes = new ArrayList<>(sourceVolumes);
        volumes.sort(Comparator.comparingDouble(volume -> number(volume.get("routeProgress"), 0)));
        for (Map<String, Object> volume : volumes) {
            String volumeId = String.valueOf(volume.get("id"));
            Map<String, Object> challenge = diamonds.stream()
                    .filter(diamond -> "AIRSPACE".equals(String.valueOf(diamond.get("challengeType"))))
                    .filter(diamond -> volumeId.equals(String.valueOf(diamond.get("linkedVolumeId"))))
                    .findFirst().orElse(null);
            if (challenge == null) return List.of();
            route = validationActionRoute(route, volume, String.valueOf(challenge.get("requiredAction")));
            if (route.isEmpty() || AirspaceGeometry.conflict(route, volume, 0) != null) return List.of();
        }
        return route;
    }

    /** Deterministically simulates one complete safe handling sequence. */
    private static List<double[]> safeCompletionRoute(List<double[]> original,
                                                       List<Map<String, Object>> sourceVolumes) {
        List<double[]> route = new ArrayList<>(original.stream().map(double[]::clone).toList());
        List<Map<String, Object>> volumes = new ArrayList<>(sourceVolumes);
        volumes.sort(Comparator.comparingDouble(volume -> number(volume.get("routeProgress"), 0)));
        for (Map<String, Object> volume : volumes) {
            String rule = String.valueOf(volume.get("ruleType"));
            if ("RISK_AIRSPACE".equals(rule)) continue;
            if (AirspaceGeometry.conflict(route, volume, 0) == null) continue;
            route = "ALTITUDE_CORRIDOR".equals(rule)
                    ? AirspaceGeometry.transitCorridor(route, volume)
                    : AirspaceGeometry.detour(route, volume);
            if (AirspaceGeometry.conflict(route, volume, 0) != null) return List.of();
        }
        return route;
    }

    private static double yellowEquivalentExtraMeters(List<double[]> route, List<Map<String, Object>> volumes) {
        double distance = MissionMath.polylineDistance(route);
        double extra = 0;
        for (Map<String, Object> volume : volumes) {
            if (!"RISK_AIRSPACE".equals(volume.get("ruleType"))) continue;
            AirspaceGeometry.Conflict conflict = AirspaceGeometry.conflict(route, volume, 0);
            if (conflict != null) extra += distance * (conflict.endProgress() - conflict.startProgress()) / 100
                    * Math.max(0, number(volume.get("energyMultiplier"), 2.5) - 1);
        }
        return extra;
    }

    private static List<double[]> detourRiskVolumes(List<double[]> source, List<Map<String, Object>> volumes) {
        List<double[]> route = new ArrayList<>(source.stream().map(double[]::clone).toList());
        for (Map<String, Object> volume : volumes) {
            if (!"RISK_AIRSPACE".equals(volume.get("ruleType"))
                    || AirspaceGeometry.conflict(route, volume, 0) == null) continue;
            List<double[]> detour = AirspaceGeometry.detour(route, volume);
            if (AirspaceGeometry.conflict(detour, volume, 0) == null) route = detour;
        }
        return route;
    }

    private RouteArtifact resolveRouteArtifact(ScenarioTemplateCatalog.Descriptor descriptor, Map<String, Object> request,
                                               Map<String, Object> fallbackGround, boolean reverse) {
        String requestHash = sha256(canonicalJson(request));
        List<RouteArtifact> existing = jdbc.query("SELECT * FROM demo_route_artifact WHERE request_hash=?", (rs, row) ->
                new RouteArtifact(rs.getString("id"), rs.getString("source"), readMap(rs.getString("route_json"))), requestHash);
        if (!existing.isEmpty()) return existing.get(0);
        List<double[]> anchors = pointsFromCoordinateLists((List<?>) request.get("anchors"));
        BaiduRouteProvider.ProviderResult provider = baidu.driving(anchors);
        Map<String, Object> route;
        String source;
        String providerStatus;
        String failureCode;
        if (provider.success()) {
            route = new LinkedHashMap<>(provider.route());
            List<double[]> providerPoints = new ArrayList<>(points(route));
            if (providerPoints.size() < 2) throw new CandidateRejected("PROVIDER_ROUTE_EMPTY");
            providerPoints.set(0, anchors.get(0).clone());
            providerPoints.set(providerPoints.size() - 1, anchors.get(anchors.size() - 1).clone());
            route.put("points", coordinateLists(providerPoints));
            route.put("distanceMeters", MissionMath.polylineDistance(providerPoints));
            source = "BAIDU_LIVE"; providerStatus = "SUCCESS"; failureCode = null;
        } else {
            route = new LinkedHashMap<>(fallbackGround);
            List<List<Number>> fallbackPoints = coordinateLists(points(fallbackGround));
            if (reverse) Collections.reverse(fallbackPoints);
            route.put("points", fallbackPoints);
            route.put("distanceMeters", MissionMath.polylineDistance(points(route)));
            source = "TEMPLATE_FALLBACK"; providerStatus = "FALLBACK"; failureCode = provider.failureCode();
        }
        route.put("routeSource", source);
        String id = id("ROUTE");
        String routeHash = sha256(canonicalJson(route));
        try {
            jdbc.update("INSERT INTO demo_route_artifact(id,request_hash,scenario_template_id,scenario_template_version,provider_contract_version,source,request_json,route_json,route_hash,provider_status,provider_latency_ms,failure_code) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, requestHash, descriptor.id(), ScenarioTemplateCatalog.VERSION, BaiduRouteProvider.CONTRACT_VERSION, source,
                    json(request), json(route), routeHash, providerStatus, provider.latencyMs(), failureCode);
            return new RouteArtifact(id, source, route);
        } catch (DuplicateKeyException race) {
            return jdbc.query("SELECT * FROM demo_route_artifact WHERE request_hash=?", (rs, row) ->
                    new RouteArtifact(rs.getString("id"), rs.getString("source"), readMap(rs.getString("route_json"))), requestHash).get(0);
        }
    }

    private Map<String, Object> generateAirRoute(Map<String, Object> fallback, double[] launch, double[] recovery,
                                                 double altitude, String seed, String domain) {
        DeterministicRandom random = new DeterministicRandom(DeterministicRandom.derive(seed, domain + "/air"));
        String mode = List.of("POINT_TO_POINT", "MULTI_WAYPOINT", "AREA_COVERAGE").get(random.nextInt(3));
        List<double[]> curated = points(fallback);
        List<double[]> inner = new ArrayList<>();
        if (curated.size() > 2) inner.addAll(curated.subList(1, curated.size() - 1));
        if (inner.isEmpty()) throw new CandidateRejected("AIR_WAYPOINT_CATALOG_EMPTY");
        boolean reversed = random.nextBoolean();
        if (reversed) Collections.reverse(inner);
        List<double[]> route = new ArrayList<>();
        route.add(new double[]{launch[0], launch[1], altitude});
        if ("POINT_TO_POINT".equals(mode)) {
            int count = Math.min(3, inner.size());
            int start = random.nextInt(inner.size() - count + 1);
            for (int index = 0; index < count; index++) {
                double[] point = inner.get(start + index).clone(); point[2] = altitude; route.add(point);
            }
        } else if ("MULTI_WAYPOINT".equals(mode)) {
            int count = Math.min(inner.size(), 4 + random.nextInt(Math.min(3, Math.max(1, inner.size() - 3))));
            int start = random.nextInt(inner.size() - count + 1);
            for (int index = 0; index < count; index++) {
                double[] point = inner.get(start + index).clone(); point[2] = altitude; route.add(point);
            }
        } else {
            for (double[] raw : inner) { double[] point = raw.clone(); point[2] = altitude; route.add(point); }
        }
        route.add(new double[]{recovery[0], recovery[1], altitude});
        Map<String, Object> air = new LinkedHashMap<>(fallback);
        air.put("points", coordinateLists(route));
        air.put("launchPoint", List.of(launch[0], launch[1], launch[2]));
        air.put("recoveryPoint", List.of(recovery[0], recovery[1], recovery[2]));
        air.put("routeMode", mode);
        air.put("routeSource", "SEEDED_" + mode);
        air.put("routePlanningStrategy", "CONSTRAINED_WAYPOINT_GRAPH");
        air.put("canonicalExecutablePolyline", true);
        air.put("waypointOrder", reversed ? "REVERSED" : "FORWARD");
        air.put("distanceMeters", MissionMath.polylineDistance(route));
        air.put("flightAltitudeMeters", altitude);
        return air;
    }

    private List<Map<String, Object>> validate(Map<String, Object> plan, Map<String, Object> parameters) {
        List<Map<String, Object>> violations = new ArrayList<>();
        List<Map<String, Object>> routes = castListOfMaps(plan.get("routes"));
        Map<String, Object> ground = routes.stream().filter(route -> "GROUND".equals(route.get("kind"))).findFirst().orElse(Map.of());
        Map<String, Object> air = routes.stream().filter(route -> "AIR".equals(route.get("kind"))).findFirst().orElse(Map.of());
        List<double[]> groundPoints = points(ground), airPoints = points(air);
        if (groundPoints.size() < 2) violations.add(violation("GROUND_EMPTY", "地面路线为空"));
        if (airPoints.size() < 2) violations.add(violation("AIR_EMPTY", "无人机航线为空"));
        if (!validRawPoints(ground) || !geographicCoordinates(groundPoints)) violations.add(violation("GROUND_COORDINATES_INVALID", "地面路线包含无效坐标"));
        if (!validRawPoints(air) || !geographicCoordinates(airPoints)) violations.add(violation("AIR_COORDINATES_INVALID", "无人机航线包含无效坐标"));
        checkContinuity(groundPoints, 1200, "GROUND_DISCONTINUITY", violations);
        checkContinuity(airPoints, 1800, "AIR_DISCONTINUITY", violations);
        Object groundAnchors = plan.getOrDefault("groundAnchors", List.of());
        if (groundAnchors instanceof List<?> rawAnchors) for (double[] anchor : pointsFromCoordinateLists(rawAnchors)) {
            if (distanceToPolyline(anchor, groundPoints) > 120) {
                violations.add(violation("GROUND_ANCHOR_MISSED", "地面路线未经过选定任务锚点"));
                break;
            }
        }
        double altitude = number(parameters.get("flightAltitudeMeters"), 70);
        if (altitude < 60 || altitude > 100 || airPoints.stream().anyMatch(point -> point[2] < 60 || point[2] > 100))
            violations.add(violation("ALTITUDE_OUT_OF_RANGE", "飞行高度超出模板范围"));
        double distance = MissionMath.polylineDistance(airPoints);
        Map<String, Object> airVehicle = castMap(plan.get("airVehicle"));
        double fullRangeMeters = Math.max(1, number(airVehicle.get("fullRangeKm"), 5) * 1000);
        double maximum = fullRangeMeters * (1 - number(parameters.get("returnReservePercent"), 25) / 100);
        if (distance > maximum) violations.add(violation("FLIGHT_RANGE_EXCEEDED", "无人机航程超过返航余量约束"));
        double estimated = number(plan.get("estimatedDurationSeconds"), 0);
        if (estimated > number(parameters.get("maxDurationMinutes"), 15) * 60)
            violations.add(violation("DURATION_EXCEEDED", "预计任务时长超过限制"));
        Map<String, Object> airspace = castMap(plan.get("airspace"));
        List<List<double[]>> allowedZones = polygons(airspace.get("allowedZones"));
        if (allowedZones.isEmpty() || airPoints.stream().anyMatch(point -> allowedZones.stream().noneMatch(zone -> pointInPolygon(point, zone))))
            violations.add(violation("OUTSIDE_ALLOWED_AIRSPACE", "航线超出声明的允许飞行区"));
        for (Map<String, Object> volume : castListOfMaps(airspace.get("volumes"))) {
            List<double[]> footprint = AirspaceGeometry.footprint(volume);
            double floor = number(volume.get("floorMeters"), -1);
            double ceiling = number(volume.get("ceilingMeters"), -1);
            if (String.valueOf(volume.get("id")).isBlank() || footprint.size() < 3 || floor < 0 || ceiling <= floor
                    || !"AGL".equals(volume.get("altitudeReference"))) {
                violations.add(violation("AIRSPACE_VOLUME_INVALID", "空域体积的边界、高度或基准无效"));
                continue;
            }
            long activeFrom = volume.get("activeFromSimulationMs") instanceof Number value ? value.longValue() : 0;
            long activeUntil = volume.get("activeUntilSimulationMs") instanceof Number value ? value.longValue() : Long.MAX_VALUE;
            if (activeFrom < 0 || activeUntil <= activeFrom) {
                violations.add(violation("AIRSPACE_LIFECYCLE_INVALID", "空域生效时间窗口无效"));
                continue;
            }
            if ("ALTITUDE_CORRIDOR".equals(volume.get("ruleType"))) {
                double corridorFloor = number(volume.get("corridorFloorMeters"), -1);
                double corridorCeiling = number(volume.get("corridorCeilingMeters"), -1);
                double target = number(volume.get("targetAltitudeMeters"), -1);
                if (corridorFloor < 60 || corridorCeiling > 100 || corridorCeiling <= corridorFloor
                        || target < corridorFloor || target > corridorCeiling
                        || castListOfMaps(volume.get("blockedAltitudeBands")).size() != 2)
                    violations.add(violation("ALTITUDE_CORRIDOR_INVALID", "高度走廊的上下禁入层或合法高度带无效"));
            }
        }
        List<Map<String, Object>> deliveryPoints = castListOfMaps(plan.get("deliveryPoints"));
        if (deliveryPoints.stream().noneMatch(point -> "GROUND".equals(point.get("kind")))
                || deliveryPoints.stream().noneMatch(point -> "AIR".equals(point.get("kind"))))
            violations.add(violation("DELIVERY_POINTS_EMPTY", "任务必须同时包含地面和空中配送点"));
        List<Map<String, Object>> airspaceVolumes = castListOfMaps(airspace.get("volumes"));
        List<Map<String, Object>> blockingVolumes = airspaceVolumes.stream().filter(AirspaceGeometry::blocking).toList();
        for (Map<String, Object> deliveryPoint : deliveryPoints) {
            double[] point = coordinate(deliveryPoint.get("position"));
            if (point == null || airspaceVolumes.stream().anyMatch(volume ->
                    AirspaceGeometry.distanceToFootprintMeters(volume, point) < DELIVERY_AIRSPACE_BUFFER_METERS)) {
                violations.add(violation("DELIVERY_POINT_IN_NO_FLY_BUFFER", "配送金币位于禁飞区或其安全缓冲内"));
                break;
            }
        }
        List<Map<String, Object>> rewardDiamonds = castListOfMaps(plan.get("rewardDiamonds"));
        if (rewardDiamonds.size() < Math.max(2, airspaceVolumes.size()) || rewardDiamonds.size() > 5) {
            violations.add(violation("DIAMOND_REWARD_COUNT_INVALID", "任务必须包含 2—5 枚粉钻，且优先覆盖全部互动空域"));
        }
        Set<String> challengeVolumeIds = rewardDiamonds.stream()
                .filter(diamond -> "AIRSPACE".equals(String.valueOf(diamond.get("challengeType"))))
                .map(diamond -> String.valueOf(diamond.get("linkedVolumeId")))
                .collect(java.util.stream.Collectors.toSet());
        if (airspaceVolumes.stream().anyMatch(volume ->
                !challengeVolumeIds.contains(String.valueOf(volume.get("id")))))
            violations.add(violation("AIRSPACE_DIAMOND_MISSING", "每个互动空域都必须优先安排一个操纵挑战粉钻"));
        for (Map<String, Object> diamond : rewardDiamonds) {
            double[] target = coordinate(diamond.get("position"));
            if (target == null || number(diamond.get("rewardMinor"), 0) != DIAMOND_REWARD_MINOR) {
                violations.add(violation("DIAMOND_REWARD_INVALID", "粉钻位置或固定价值无效"));
                continue;
            }
            boolean insideProhibited = airspaceVolumes.stream().anyMatch(volume -> AirspaceGeometry.contains(volume, target));
            boolean outsideAllowedZone = allowedZones.stream().noneMatch(zone -> pointInPolygon(target, zone));
            String challengeType = String.valueOf(diamond.getOrDefault("challengeType",
                    diamond.containsKey("linkedVolumeId") ? "AIRSPACE" : "ROUTE"));
            if (insideProhibited || outsideAllowedZone) {
                violations.add(violation("DIAMOND_POSITION_INVALID", "粉钻位于禁入体积或允许空域之外"));
            } else if ("ROUTE".equals(challengeType)) {
                boolean tooCloseToAirspace = airspaceVolumes.stream().anyMatch(volume ->
                        AirspaceGeometry.distanceToFootprintMeters(volume, target) < DELIVERY_AIRSPACE_BUFFER_METERS);
                if (diamond.containsKey("linkedVolumeId") || diamond.containsKey("requiredAction")
                        || !routePassesReward(airPoints, target,
                        number(diamond.get("triggerRadiusMeters"), 13), number(diamond.get("altitudeToleranceMeters"), 9))
                        || tooCloseToAirspace)
                    violations.add(violation("ROUTE_DIAMOND_INVALID", "普通粉钻必须随机位于空域缓冲外的正常航段"));
            } else {
                String linkedVolumeId = String.valueOf(diamond.get("linkedVolumeId"));
                String requiredAction = String.valueOf(diamond.get("requiredAction"));
                Map<String, Object> linkedVolume = airspaceVolumes.stream()
                        .filter(volume -> linkedVolumeId.equals(String.valueOf(volume.get("id"))))
                        .findFirst().orElse(null);
                List<double[]> actionRoute = linkedVolume == null ? List.of()
                        : validationActionRoute(airPoints, linkedVolume, requiredAction);
                if (linkedVolume == null || actionRoute.isEmpty()
                        || AirspaceGeometry.conflict(actionRoute, linkedVolume, 0) != null
                        || !rawList(linkedVolume.get("availableActions")).contains(requiredAction))
                    violations.add(violation("DIAMOND_ACTION_NOT_EXCLUSIVE", "挑战粉钻没有绑定可执行且真实可达的安全动作"));
            }
        }
        List<double[]> diamondRoute = diamondCollectionRoute(airPoints, airspaceVolumes, rewardDiamonds);
        if (diamondRoute.isEmpty() || rewardDiamonds.stream().anyMatch(diamond -> !routePassesReward(diamondRoute,
                coordinate(diamond.get("position")), number(diamond.get("triggerRadiusMeters"), 13),
                number(diamond.get("altitudeToleranceMeters"), 9))))
            violations.add(violation("DIAMOND_COLLECTION_ROUTE_INVALID", "本局粉钻不能在同一条完整可执行航线上全部领取"));
        Map<String, Object> profile = castMap(plan.get("airspaceProfile"));
        int themeCount = (int) number(profile.get("themeCount"), airspaceVolumes.size());
        if (themeCount < 2 || themeCount > 4 || airspaceVolumes.size() != themeCount
                || rawList(profile.get("themes")).stream().distinct().count() != themeCount)
            violations.add(violation("AIRSPACE_PROFILE_INVALID", "本局空域主题数量或去重结果无效"));
        if (airspaceVolumes.stream().anyMatch(volume -> AirspaceGeometry.conflict(airPoints, volume, 0) == null))
            violations.add(violation("AIRSPACE_INTERACTION_MISSING", "所有抽中的互动空域都必须与预期航线相交"));
        List<double[]> safeRoute = safeCompletionRoute(airPoints, airspaceVolumes);
        if (safeRoute.isEmpty() || safeRoute.stream().anyMatch(point -> point[2] < 60 || point[2] > 100)
                || safeRoute.stream().anyMatch(point -> allowedZones.stream().noneMatch(zone -> pointInPolygon(point, zone))))
            violations.add(violation("NO_SAFE_AIRSPACE_ACTION", "多空域连续处置没有完整合法路线"));
        double lastConflictEnd = airspaceVolumes.stream().map(volume -> AirspaceGeometry.conflict(airPoints, volume, 0))
                .filter(Objects::nonNull).mapToDouble(AirspaceGeometry.Conflict::endProgress).max().orElse(0);
        if (deliveryPoints.stream().filter(point -> "AIR".equals(point.get("kind")))
                .noneMatch(point -> number(point.get("routeProgress"), 0) > lastConflictEnd + 3))
            violations.add(violation("NO_POST_CONFLICT_REWARD", "最后一个互动空域后没有可达的无人机配送金币"));
        double[] launch = coordinate(plan.get("launchPoint"));
        double[] recovery = coordinate(plan.get("recoveryPoint"));
        if (launch == null || distanceToPolyline(launch, groundPoints) > 20) violations.add(violation("LAUNCH_POINT_INVALID", "起飞点不在配送车路线附近"));
        if (recovery == null || distanceToPolyline(recovery, groundPoints) > 20) violations.add(violation("RECOVERY_POINT_INVALID", "返航点不在配送车路线附近"));
        if (launch != null && !airPoints.isEmpty() && horizontalDistance(launch, airPoints.get(0)) > 10)
            violations.add(violation("AIR_ROUTE_LAUNCH_MISMATCH", "无人机航线起点与起飞点不一致"));
        if (recovery != null && !airPoints.isEmpty() && horizontalDistance(recovery, airPoints.get(airPoints.size() - 1)) > 10)
            violations.add(violation("AIR_ROUTE_RECOVERY_MISMATCH", "无人机航线终点与返航点不一致"));
        Map<String, Object> rendezvous = castMap(plan.get("rendezvousPlan"));
        if (number(rendezvous.get("estimatedWaitSeconds"), Double.POSITIVE_INFINITY) > number(rendezvous.get("maximumWaitSeconds"), 180))
            violations.add(violation("RENDEZVOUS_WAIT_EXCEEDED", "车机返航会合等待时间超过限制"));
        String groundRouteId = String.valueOf(ground.get("routeId"));
        for (Map<String, Object> light : castListOfMaps(plan.get("trafficLights"))) {
            double[] point = coordinate(light.get("longitude"), light.get("latitude"), 0);
            if (!groundRouteId.equals(String.valueOf(light.get("routeId"))) || point == null || distanceToPolyline(point, groundPoints) > 30) {
                violations.add(violation("TRAFFIC_NODE_OFF_ROUTE", "交通控制节点不在本次地面路线附近"));
                break;
            }
        }
        return violations;
    }

    private static double trafficDelaySeconds(List<Map<String, Object>> lights) {
        return lights.stream().mapToDouble(light -> number(light.get("redDurationSeconds"), 35) / 2).sum();
    }

    private static boolean validRawPoints(Map<String, Object> route) {
        Object value = route.get("points");
        if (!(value instanceof List<?> raw) || raw.size() < 2) return false;
        return raw.stream().allMatch(item -> item instanceof List<?> point && point.size() >= 2
                && point.get(0) instanceof Number longitude && Double.isFinite(longitude.doubleValue())
                && point.get(1) instanceof Number latitude && Double.isFinite(latitude.doubleValue())
                && (point.size() < 3 || point.get(2) instanceof Number altitude && Double.isFinite(altitude.doubleValue())));
    }

    private static boolean geographicCoordinates(List<double[]> points) {
        return points.stream().allMatch(point -> point[0] >= -180 && point[0] <= 180 && point[1] >= -90 && point[1] <= 90);
    }

    private static double[] coordinate(Object value) {
        if (!(value instanceof List<?> point) || point.size() < 2 || !(point.get(0) instanceof Number longitude) || !(point.get(1) instanceof Number latitude)) return null;
        return new double[]{longitude.doubleValue(), latitude.doubleValue(), point.size() > 2 && point.get(2) instanceof Number altitude ? altitude.doubleValue() : 0};
    }

    private static double[] coordinate(Object longitudeValue, Object latitudeValue, Object altitudeValue) {
        if (!(longitudeValue instanceof Number longitude) || !(latitudeValue instanceof Number latitude)) return null;
        return new double[]{longitude.doubleValue(), latitude.doubleValue(), altitudeValue instanceof Number altitude ? altitude.doubleValue() : 0};
    }

    private static List<List<double[]>> polygons(Object value) {
        if (!(value instanceof List<?> rawPolygons)) return List.of();
        List<List<double[]>> result = new ArrayList<>();
        for (Object rawPolygon : rawPolygons) {
            if (!(rawPolygon instanceof List<?> rawPoints)) continue;
            List<double[]> polygon = rawPoints.stream().map(TaskInstanceService::coordinate).filter(java.util.Objects::nonNull).toList();
            if (polygon.size() >= 3) result.add(polygon);
        }
        return result;
    }

    private static boolean pointInPolygon(double[] point, List<double[]> polygon) {
        boolean inside = false;
        for (int index = 0, previous = polygon.size() - 1; index < polygon.size(); previous = index++) {
            double[] a = polygon.get(index), b = polygon.get(previous);
            if (distanceToSegment(point, a, b) < .5) return true;
            boolean crosses = (a[1] > point[1]) != (b[1] > point[1])
                    && point[0] < (b[0] - a[0]) * (point[1] - a[1]) / (b[1] - a[1]) + a[0];
            if (crosses) inside = !inside;
        }
        return inside;
    }

    private static boolean polylineIntersectsPolygons(List<double[]> line, List<List<double[]>> polygons) {
        for (List<double[]> polygon : polygons) {
            if (line.stream().anyMatch(point -> pointInPolygon(point, polygon))) return true;
            for (int lineIndex = 1; lineIndex < line.size(); lineIndex++) for (int edge = 0; edge < polygon.size(); edge++) {
                if (segmentsIntersect(line.get(lineIndex - 1), line.get(lineIndex), polygon.get(edge), polygon.get((edge + 1) % polygon.size()))) return true;
            }
        }
        return false;
    }

    private static boolean segmentsIntersect(double[] a, double[] b, double[] c, double[] d) {
        double o1 = orientation(a, b, c), o2 = orientation(a, b, d), o3 = orientation(c, d, a), o4 = orientation(c, d, b);
        return o1 * o2 < 0 && o3 * o4 < 0;
    }

    private static double orientation(double[] a, double[] b, double[] c) {
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
    }

    private static double horizontalDistance(double[] a, double[] b) {
        return MissionMath.distance(new double[]{a[0], a[1], 0}, new double[]{b[0], b[1], 0});
    }

    private static double distanceToPolyline(double[] point, List<double[]> line) {
        if (line.isEmpty()) return Double.POSITIVE_INFINITY;
        double closest = horizontalDistance(point, line.get(0));
        for (int index = 1; index < line.size(); index++) closest = Math.min(closest, distanceToSegment(point, line.get(index - 1), line.get(index)));
        return closest;
    }

    private static double distanceToSegment(double[] point, double[] a, double[] b) {
        double referenceLat = Math.toRadians((a[1] + b[1] + point[1]) / 3);
        double scaleX = 111_320 * Math.cos(referenceLat), scaleY = 110_540;
        double ax = a[0] * scaleX, ay = a[1] * scaleY, bx = b[0] * scaleX, by = b[1] * scaleY, px = point[0] * scaleX, py = point[1] * scaleY;
        double dx = bx - ax, dy = by - ay;
        double denominator = dx * dx + dy * dy;
        double t = denominator == 0 ? 0 : MissionMath.clamp(((px - ax) * dx + (py - ay) * dy) / denominator, 0, 1);
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    private static void checkContinuity(List<double[]> points, double maximumGap, String code, List<Map<String, Object>> violations) {
        for (int index = 1; index < points.size(); index++) if (MissionMath.distance(points.get(index - 1), points.get(index)) > maximumGap) {
            violations.add(violation(code, "路线存在过大的相邻点间距")); return;
        }
    }

    private static Map<String, Object> airspace(List<double[]> points, List<double[]> airRoute, String seed,
                                                String domain, int themeCount) {
        double minLng = points.stream().mapToDouble(p -> p[0]).min().orElse(0) - .002;
        double minLat = points.stream().mapToDouble(p -> p[1]).min().orElse(0) - .002;
        double maxLng = points.stream().mapToDouble(p -> p[0]).max().orElse(0) + .002;
        double maxLat = points.stream().mapToDouble(p -> p[1]).max().orElse(0) + .002;
        DeterministicRandom random = new DeterministicRandom(DeterministicRandom.derive(seed, domain + "/airspace"));
        random.nextDouble(); // Preserve the stable seeded sequence after removing the random theme-count roll.
        List<String> themes = new ArrayList<>(List.of("RED", "YELLOW", "PURPLE", "ORANGE"));
        deterministicShuffle(themes, random);
        themes = new ArrayList<>(themes.subList(0, themeCount));
        if (themeCount == 4 && "1204".equals(seed)) {
            // Seed 1204 is the versioned prologue fixture. Keep its absolute
            // no-fly lesson first in every operating area while the remaining
            // seeded geometry and theme order stay deterministic.
            themes.remove("RED");
            themes.add(0, "RED");
        }
        List<Double> progresses = switch (themeCount) {
            case 2 -> List.of(.24 + random.nextDouble() * .08, .66 + random.nextDouble() * .12);
            case 3 -> List.of(.20 + random.nextDouble() * .06, .47 + random.nextDouble() * .08,
                    .76 + random.nextDouble() * .06);
            default -> List.of(.20 + random.nextDouble() * .04, .39 + random.nextDouble() * .04,
                    .58 + random.nextDouble() * .04, .77 + random.nextDouble() * .04);
        };
        double baseHalfWidth = Math.max(.00024, Math.min(.00048, (maxLng - minLng) * .035));
        double baseHalfHeight = Math.max(.00022, Math.min(.00042, (maxLat - minLat) * .035));
        List<List<List<Number>>> noFlyZones = new ArrayList<>();
        List<Map<String, Object>> details = new ArrayList<>();
        List<Map<String, Object>> volumes = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        double cruiseAltitude = airRoute.isEmpty() ? 72 : airRoute.get(0)[2];
        for (int index = 0; index < themeCount; index++) {
            String theme = themes.get(index);
            double progress = progresses.get(index);
            double[] center = MissionMath.sample(airRoute, progress);
            double widthScale = "ORANGE".equals(theme) ? .95 + random.nextDouble() * .35 : .62 + random.nextDouble() * .28;
            double heightScale = "ORANGE".equals(theme) ? .90 + random.nextDouble() * .38 : .62 + random.nextDouble() * .28;
            List<List<Number>> footprint = rotatedFootprint(center, baseHalfWidth * widthScale,
                    baseHalfHeight * heightScale, random.nextDouble() * Math.PI,
                    "PURPLE".equals(theme) || random.nextBoolean() ? 4 : 6);
            Map<String, Object> created;
            if ("RED".equals(theme)) {
                String id = "NFZ-001";
                String label = "绝对禁飞保护空域";
                created = volume(id, label, "ABSOLUTE_NO_FLY", footprint, 0, 120,
                        0L, null, "PROTECTED_FACILITY", "SEEDED_SCENARIO", false, true);
                created.put("penaltyPolicy", Map.of("type", "FIXED_ON_ENTRY", "amountMinor", 800_000L,
                        "repeatMode", "PER_INCURSION"));
                created.put("availableActions", List.of("DETOUR", "RETURN_TO_RECOVERY"));
                noFlyZones.add(footprint);
                details.add(Map.of("id", id, "label", label, "type", "ABSOLUTE",
                        "position", List.of(center[0], center[1], 0)));
            } else if ("YELLOW".equals(theme)) {
                created = volume("RISK-001", "低空扰动风险区", "RISK_AIRSPACE", footprint, 0, 120,
                        0L, null, "WIND_DISTURBANCE", "SEEDED_EVENT", false, false);
                created.put("energyMultiplier", 2.5);
                created.put("penaltyPolicy", Map.of("type", "NONE"));
                created.put("availableActions", List.of("ACCEPT_RISK", "DETOUR"));
            } else if ("PURPLE".equals(theme)) {
                double corridorWidth = 14 + random.nextInt(5);
                double halfCorridorWidth = corridorWidth / 2;
                int maximumUpwardOffset = (int) Math.floor(100 - halfCorridorWidth - cruiseAltitude);
                int maximumDownwardOffset = (int) Math.floor(cruiseAltitude - halfCorridorWidth - 60);
                boolean upwardAllowed = maximumUpwardOffset >= 12;
                boolean downwardAllowed = maximumDownwardOffset >= 12;
                boolean upward = upwardAllowed && (!downwardAllowed || random.nextBoolean());
                int maximumOffset = upward ? maximumUpwardOffset : maximumDownwardOffset;
                double offset = 12 + random.nextInt(Math.max(1, Math.min(20, maximumOffset) - 11));
                double target = cruiseAltitude + (upward ? offset : -offset);
                double corridorFloor = target - corridorWidth / 2;
                double corridorCeiling = target + corridorWidth / 2;
                created = volume("CORRIDOR-001", "高度通行走廊", "ALTITUDE_CORRIDOR", footprint, 0, 120,
                        0L, null, "ALTITUDE_SEPARATION", "SEEDED_SCENARIO", false, true);
                created.put("blockedAltitudeBands", List.of(
                        Map.of("floorMeters", 0, "ceilingMeters", corridorFloor),
                        Map.of("floorMeters", corridorCeiling, "ceilingMeters", 120)));
                created.put("corridorFloorMeters", corridorFloor);
                created.put("corridorCeilingMeters", corridorCeiling);
                created.put("targetAltitudeMeters", target);
                created.put("penaltyPolicy", Map.of("type", "FIXED_ON_ENTRY", "amountMinor", 300_000L,
                        "repeatMode", "ONCE_PER_VOLUME"));
                created.put("availableActions", List.of("TRANSIT_CORRIDOR", "DETOUR", "RETURN_TO_RECOVERY"));
            } else {
                List<String> dynamicLabels = List.of("临时作业禁飞空域", "大型活动临时禁飞空域", "低空作业临时禁飞空域");
                List<String> dynamicReasons = List.of("TEMPORARY_RESTRICTION", "PUBLIC_EVENT", "LOW_ALTITUDE_OPERATION");
                int variant = random.nextInt(dynamicLabels.size());
                double ceiling = Math.min(96, Math.max(78, cruiseAltitude + 6 + random.nextInt(9)));
                created = volume("TNFZ-001", dynamicLabels.get(variant), "TEMPORARY_NO_FLY", footprint, 0,
                        ceiling, -1L, -1L, dynamicReasons.get(variant), "SEEDED_EVENT", true, true);
                List<String> actions = new ArrayList<>(List.of("DETOUR"));
                if (ceiling + 12 <= 100) actions.add("CLIMB_OVER");
                actions.add("WAIT_UNTIL_CLEAR"); actions.add("RETURN_TO_RECOVERY");
                created.put("availableActions", actions);
                created.put("penaltyPolicy", Map.of("type", "DURATION", "baseMinor", 120_000L,
                        "perSecondMinor", 12_000L, "maximumMinor", 480_000L));
            }
            created.put("routeProgress", Math.round(progress * 10_000) / 100.0);
            created.put("theme", theme);
            volumes.add(created);
            labels.add(switch (theme) {
                case "RED" -> "红色绝对禁飞";
                case "YELLOW" -> "黄色风险耗电";
                case "PURPLE" -> "紫色高度走廊";
                default -> "橙色临时禁飞";
            });
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bounds", List.of(minLng, minLat, maxLng, maxLat));
        result.put("allowedZones", List.of(List.of(List.of(minLng, minLat), List.of(maxLng, minLat),
                List.of(maxLng, maxLat), List.of(minLng, maxLat), List.of(minLng, minLat))));
        result.put("noFlyZones", noFlyZones); result.put("noFlyZoneDetails", details); result.put("volumes", volumes);
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("themeCount", themeCount); profile.put("themes", themes); profile.put("themeLabels", labels);
        profile.put("interactiveVolumeCount", volumes.size());
        result.put("airspaceProfile", profile);
        result.put("geometryModel", "FOOTPRINT_HEIGHT_2_5D"); result.put("altitudeReference", "AGL");
        result.put("buildingCollision", "NOT_EVALUATED"); result.put("generationMode", "SEEDED_CONSTRAINED");
        return result;
    }

    private static List<List<Number>> rotatedFootprint(double[] center, double halfWidth, double halfHeight,
                                                        double angle, int sides) {
        List<List<Number>> result = new ArrayList<>();
        if (sides <= 4) {
            for (double[] corner : List.of(new double[]{-1, -1}, new double[]{1, -1}, new double[]{1, 1}, new double[]{-1, 1})) {
                double x = corner[0] * halfWidth, y = corner[1] * halfHeight;
                result.add(List.of(center[0] + x * Math.cos(angle) - y * Math.sin(angle),
                        center[1] + x * Math.sin(angle) + y * Math.cos(angle)));
            }
        } else for (int index = 0; index < sides; index++) {
            double theta = angle + Math.PI * 2 * index / sides;
            result.add(List.of(center[0] + Math.cos(theta) * halfWidth, center[1] + Math.sin(theta) * halfHeight));
        }
        result.add(result.get(0));
        return List.copyOf(result);
    }

    private static Map<String, Object> volume(String id, String label, String ruleType, List<List<Number>> footprint,
                                               double floor, double ceiling, Long activeFrom, Long activeUntil,
                                               String reason, String source, boolean dynamic, boolean blocking) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id); value.put("label", label); value.put("ruleType", ruleType); value.put("shapeType", "POLYGON");
        value.put("footprint", footprint); value.put("floorMeters", floor); value.put("ceilingMeters", ceiling);
        value.put("altitudeReference", "AGL"); value.put("activeFromSimulationMs", activeFrom); value.put("activeUntilSimulationMs", activeUntil);
        value.put("reason", reason); value.put("source", source); value.put("dynamic", dynamic); value.put("blocking", blocking);
        return value;
    }

    private static void scheduleDynamicAirspace(Map<String, Object> airspace, double launchAtSeconds,
                                                double airSortieSeconds, double durationSeconds) {
        for (Map<String, Object> volume : castListOfMaps(airspace.get("volumes"))) {
            if (!Boolean.TRUE.equals(volume.get("dynamic"))) continue;
            double routeProgress = number(volume.get("routeProgress"), 55) / 100;
            double expectedEntrySeconds = launchAtSeconds + Math.max(0, airSortieSeconds - 40) * routeProgress;
            long from = Math.round(Math.max(launchAtSeconds, expectedEntrySeconds - 22) * 1000);
            long until = Math.round(Math.min(durationSeconds, expectedEntrySeconds + 38) * 1000);
            volume.put("activeFromSimulationMs", from); volume.put("activeUntilSimulationMs", Math.max(from + 30_000, until));
            volume.put("activationLeadMs", 8_000); volume.put("expansionDurationMs", 5_000);
        }
    }

    private static double[] polygonCenter(List<? extends List<? extends Number>> polygon) {
        double longitude = 0, latitude = 0; int count = 0;
        for (int index = 0; index < polygon.size(); index++) {
            List<? extends Number> point = polygon.get(index);
            if (index == polygon.size() - 1 && polygon.size() > 1 && point.equals(polygon.get(0))) continue;
            longitude += point.get(0).doubleValue(); latitude += point.get(1).doubleValue(); count++;
        }
        return new double[]{longitude / Math.max(1, count), latitude / Math.max(1, count), 0};
    }

    private List<Map<String, Object>> trafficLights(Map<String, Object> route, ScenarioTemplateCatalog.Descriptor descriptor,
                                                    String seed, String domain) {
        List<double[]> points = points(route);
        DeterministicRandom random = new DeterministicRandom(DeterministicRandom.derive(seed, domain + "/traffic"));
        List<Map<String, Object>> result = new ArrayList<>();
        List<Map<String, Object>> templateNodes = intersectionCatalog.templateNodes(descriptor.groundRouteId());
        if (templateNodes.isEmpty() && ScenarioTemplateCatalog.CAMPUS_TEMPLATE_ID.equals(descriptor.id()))
            templateNodes = campusTrafficNodes(descriptor);
        for (Map<String, Object> templateNode : templateNodes) {
            double[] templatePoint = coordinate(templateNode.get("longitude"), templateNode.get("latitude"), 0);
            Projection projection = projectToPolyline(templatePoint, points);
            if (projection == null || projection.distanceMeters() > 45) continue;
            double progress = projection.routeProgress();
            double[] point = projection.point();
            TrafficRuleEngine.Movement movement = SimulatedTrafficLightService.movementAt(
                    points, progress, number(route.get("distanceMeters"), MissionMath.polylineDistance(points)));
            Map<String, Object> light = new LinkedHashMap<>();
            light.put("id", "TASK-" + templateNode.get("id"));
            light.put("routeId", descriptor.groundRouteId()); light.put("deviceId", descriptor.vehicleId());
            light.put("longitude", point[0]); light.put("latitude", point[1]); light.put("coordinateSystem", "BD09LL");
            light.put("routeProgress", progress); light.put("movement", movement.name());
            light.put("signalType", movement == TrafficRuleEngine.Movement.LEFT || movement == TrafficRuleEngine.Movement.U_TURN
                    ? "DIRECTIONAL" : "CIRCULAR");
            light.put("redDurationSeconds", 30 + random.nextInt(11)); light.put("greenDurationSeconds", 25 + random.nextInt(16));
            light.put("yellowDurationSeconds", 3); light.put("phaseOffsetSeconds", random.nextInt(45));
            light.put("source", "TEMPLATE_INTERSECTION_CATALOG"); result.add(light);
        }
        result.sort(Comparator.comparingDouble(light -> number(light.get("routeProgress"), 0)));
        return result;
    }

    private static List<Map<String, Object>> campusTrafficNodes(ScenarioTemplateCatalog.Descriptor descriptor) {
        double[][] nodes = {
                {117.2115590,31.7802008},{117.2111256,31.7785921},{117.2069977,31.7767395},
                {117.2069996,31.7755949},{117.2069849,31.7743240},{117.2101362,31.7754444},
                {117.2106541,31.7741304},{117.2131727,31.7756667},{117.2126171,31.7741254},
                {117.2113271,31.7731678}
        };
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < nodes.length; index++) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "CAMPUS-JUNCTION-" + String.format("%02d", index + 1));
            node.put("routeId", descriptor.groundRouteId()); node.put("deviceId", descriptor.vehicleId());
            node.put("longitude", nodes[index][0]); node.put("latitude", nodes[index][1]);
            result.add(node);
        }
        return result;
    }

    private static Projection projectToPolyline(double[] point, List<double[]> line) {
        if (point == null || line.isEmpty()) return null;
        double total = MissionMath.polylineDistance(line);
        if (line.size() == 1 || total <= 0) return new Projection(0, line.get(0).clone(), horizontalDistance(point, line.get(0)));
        Projection best = null;
        double traversed = 0;
        for (int index = 1; index < line.size(); index++) {
            double[] a = line.get(index - 1), b = line.get(index);
            double segmentLength = MissionMath.distance(a, b);
            double referenceLat = Math.toRadians((a[1] + b[1] + point[1]) / 3);
            double scaleX = 111_320 * Math.cos(referenceLat), scaleY = 110_540;
            double ax = a[0] * scaleX, ay = a[1] * scaleY, bx = b[0] * scaleX, by = b[1] * scaleY;
            double px = point[0] * scaleX, py = point[1] * scaleY, dx = bx - ax, dy = by - ay;
            double denominator = dx * dx + dy * dy;
            double ratio = denominator == 0 ? 0 : MissionMath.clamp(((px - ax) * dx + (py - ay) * dy) / denominator, 0, 1);
            double[] projected = new double[]{a[0] + (b[0] - a[0]) * ratio, a[1] + (b[1] - a[1]) * ratio, 0};
            double distance = horizontalDistance(point, projected);
            double progress = (traversed + segmentLength * ratio) / total * 100;
            if (best == null || distance < best.distanceMeters()) best = new Projection(progress, projected, distance);
            traversed += segmentLength;
        }
        return best;
    }

    private static List<Map<String, Object>> generatedEvents(ScenarioTemplateCatalog.Descriptor descriptor, double durationSeconds) {
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(event("deploy-point-reached", 18, durationSeconds * .18, "TAKEOFF", "到达放飞点", descriptor.uavId(), "INFO", false, "TAKEOFF"));
        result.add(event("cooperative-delivery-started", 24, durationSeconds * .24, "DELIVERY_START", "开始协同配送", descriptor.uavId(), "INFO", false, "DELIVERING"));
        result.add(event("return-started", 78, durationSeconds * .78, "RETURN", "无人机开始返航", descriptor.uavId(), "INFO", false, "RETURNING"));
        result.add(event("mission-completed", 100, durationSeconds, "COMPLETE", "协同配送完成", descriptor.uavId(), "INFO", false, "DOCKED"));
        result.sort(Comparator.comparingDouble(value -> number(value.get("simulationTimeSeconds"), 0)));
        return result;
    }

    private static Map<String, Object> event(String id, double progress, double simulationTimeSeconds, String type, String label, String deviceId,
                                             String severity, boolean requiresAction, String phaseId) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id); value.put("progress", progress); value.put("type", type); value.put("label", label);
        value.put("simulationTimeSeconds", Math.ceil(simulationTimeSeconds));
        value.put("deviceId", deviceId); value.put("severity", severity); value.put("requiresAction", requiresAction); value.put("phaseId", phaseId);
        return value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rowView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return view(rs.getString("id"), rs.getString("scenario_template_id"), rs.getString("scenario_template_version"),
                rs.getString("generator_version"), rs.getString("ruleset_version"), rs.getString("seed_value"),
                readMap(rs.getString("resolved_parameters_json")), readMap(rs.getString("plan_json")), readMap(rs.getString("validation_json")),
                readListOfMaps(rs.getString("generation_trace_json")), rs.getString("plan_hash"), rs.getString("route_artifact_id"),
                rs.getString("lifecycle_status"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant());
    }

    private static Map<String, Object> view(String id, String templateId, String templateVersion,
                                             String generatorVersion, String rulesetVersion, String seed,
                                            Map<String, Object> parameters, Map<String, Object> plan, Map<String, Object> validation,
                                            List<Map<String, Object>> trace, String planHash, String routeArtifactId, String status,
                                            Instant createdAt, Instant expiresAt, Instant startedAt) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("taskId", id); value.put("scenarioTemplateId", templateId); value.put("scenarioTemplateVersion", templateVersion);
        value.put("generatorVersion", generatorVersion); value.put("rulesetVersion", rulesetVersion); value.put("seed", seed);
        value.put("resolvedParameters", parameters); value.put("plan", plan); value.put("validation", validation); value.put("generationTrace", trace);
        value.put("planHash", planHash); value.put("routeArtifactId", routeArtifactId); value.put("status", status);
        value.put("createdAt", createdAt.toString()); value.put("expiresAt", expiresAt.toString());
        if (startedAt != null) value.put("startedAt", startedAt.toString());
        return value;
    }

    private Map<String, Object> parameters(ScenarioTemplateCatalog.Descriptor descriptor, Map<String, Object> raw) {
        String intensity = String.valueOf(raw.getOrDefault("deliveryDensity", "STANDARD")).toUpperCase(Locale.ROOT);
        if (!List.of("LOW", "STANDARD", "HIGH").contains(intensity)) throw new DemoException(HttpStatus.BAD_REQUEST, "订单密度仅支持 LOW、STANDARD、HIGH");
        double altitude = ranged(raw.get("flightAltitudeMeters"), descriptor.defaultAltitude(), 60, 100, "飞行高度");
        double duration = ranged(raw.get("maxDurationMinutes"), 15,
                ScenarioTemplateCatalog.minimumDurationMinutes(descriptor.id()), 30, "最长时限");
        double reserve = ranged(raw.get("returnReservePercent"), 25, 20, 40, "返航电量余量");
        double airspaceThemeCount = ranged(raw.get("airspaceThemeCount"), 2, 2, 4, "禁飞区数量");
        if (airspaceThemeCount != Math.rint(airspaceThemeCount))
            throw new DemoException(HttpStatus.BAD_REQUEST, "禁飞区数量必须是 2、3 或 4");
        boolean trafficSignalsEnabled = ScenarioTemplateCatalog.CAMPUS_TEMPLATE_ID.equals(descriptor.id())
                ? false : booleanParameter(raw.get("trafficSignalsEnabled"), true, "交通信号灯");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deliveryDensity", intensity); result.put("flightAltitudeMeters", altitude);
        result.put("maxDurationMinutes", duration); result.put("returnReservePercent", reserve);
        result.put("airspaceThemeCount", (int) airspaceThemeCount);
        result.put("trafficSignalsEnabled", trafficSignalsEnabled); return result;
    }

    private static boolean booleanParameter(Object raw, boolean fallback, String label) {
        if (raw == null) return fallback;
        if (raw instanceof Boolean value) return value;
        throw new DemoException(HttpStatus.BAD_REQUEST, label + "必须是布尔值");
    }

    private static double ranged(Object raw, double fallback, double min, double max, String label) {
        double value = raw instanceof Number number ? number.doubleValue() : fallback;
        if (!Double.isFinite(value) || value < min || value > max) throw new DemoException(HttpStatus.BAD_REQUEST, label + "必须位于 " + min + "–" + max + " 范围内");
        return value;
    }

    private String normalizeSeed(String raw) {
        if (raw == null || raw.isBlank()) return Long.toUnsignedString(secureRandom.nextLong());
        try { Long.parseUnsignedLong(raw.trim()); return raw.trim(); }
        catch (NumberFormatException error) { throw new DemoException(HttpStatus.BAD_REQUEST, "Seed 必须是无符号 64 位十进制整数"); }
    }

    @SuppressWarnings("unchecked")
    public static List<double[]> points(Map<String, Object> route) {
        Object value = route.get("points");
        if (!(value instanceof List<?> raw)) return List.of();
        List<double[]> result = new ArrayList<>();
        for (Object pointValue : raw) if (pointValue instanceof List<?> point && point.size() >= 2 && point.get(0) instanceof Number longitude && point.get(1) instanceof Number latitude) {
            double altitude = point.size() > 2 && point.get(2) instanceof Number number ? number.doubleValue() : 0;
            result.add(new double[]{longitude.doubleValue(), latitude.doubleValue(), altitude});
        }
        return result;
    }

    private static List<double[]> pointsFromCoordinateLists(List<?> raw) {
        Map<String, Object> route = new LinkedHashMap<>(); route.put("points", raw); return points(route);
    }
    private static List<List<Number>> coordinateLists(List<double[]> points) {
        return points.stream().map(point -> List.<Number>of(point[0], point[1], point.length > 2 ? point[2] : 0)).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> castListOfMaps(Object value) { return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of(); }
    @SuppressWarnings("unchecked") private static Map<String, Object> castMap(Object value) { return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of(); }
    private static List<?> rawList(Object value) { return value instanceof List<?> list ? list : List.of(); }
    private static void replaceRoute(Map<String, Object> plan, String routeId, Map<String, Object> route) {
        List<Map<String, Object>> routes = castListOfMaps(plan.get("routes"));
        for (int index = 0; index < routes.size(); index++) if (routeId.equals(String.valueOf(routes.get(index).get("routeId")))) { routes.set(index, route); return; }
    }
    private static Map<String, Object> violation(String code, String message) { return Map.of("code", code, "message", message); }
    private static Map<String, Object> trace(int attempt, String seed, List<String> reasons, String status) {
        return Map.of("attempt", attempt, "attemptSeedHash", sha256(seed + ":" + attempt).substring(0, 16), "status", status, "reasonCodes", reasons);
    }
    private String canonicalJson(Object value) { try { return mapper.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value); } catch (Exception error) { throw new IllegalStateException(error); } }
    @SuppressWarnings("unchecked") private String planHash(Map<String, Object> plan) {
        Map<String, Object> stable = mapper.convertValue(plan, new TypeReference<>() {});
        stable.remove("taskId"); stable.remove("planHash"); stable.remove("routeArtifactId");
        for (Map<String, Object> route : castListOfMaps(stable.get("routes"))) route.remove("routeArtifactId");
        return sha256(canonicalJson(stable));
    }
    private static <T> void deterministicShuffle(List<T> values, DeterministicRandom random) {
        for (int index = values.size() - 1; index > 0; index--) Collections.swap(values, index, random.nextInt(index + 1));
    }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception error) { throw new IllegalStateException(error); } }
    private Map<String, Object> readMap(String value) { try { return mapper.readValue(value, new TypeReference<>() {}); } catch (Exception error) { throw new IllegalStateException(error); } }
    private List<Map<String, Object>> readListOfMaps(String value) { try { return mapper.readValue(value, new TypeReference<>() {}); } catch (Exception error) { throw new IllegalStateException(error); } }
    private static String id(String prefix) { return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT); }
    private static String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception error) { throw new IllegalStateException(error); } }
    private static double number(Object value, double fallback) { return value instanceof Number number ? number.doubleValue() : fallback; }

    private record Generated(Map<String, Object> plan, String routeArtifactId) {}
    private record RouteArtifact(String id, String source, Map<String, Object> route) {}
    private record Projection(double routeProgress, double[] point, double distanceMeters) {}
    private static final class CandidateRejected extends RuntimeException { final String code; CandidateRejected(String code) { this.code = code; } }
}
