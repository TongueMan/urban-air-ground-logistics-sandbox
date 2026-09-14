package com.skyfleet.logistics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TutorialProgressService {
    public static final String PROLOGUE_ID = "prologue";
    public static final String PROLOGUE_VERSION = "3";
    public static final String FLEET_ID = "fleet-center";
    public static final String FLEET_VERSION = "1";
    public static final String GROUND_COOP_ID = "TUTORIAL-02-GROUND-COOP";
    public static final String GROUND_COOP_VERSION = "1.0";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AdvancedRoutingProperties properties;

    public TutorialProgressService(JdbcTemplate jdbc, ObjectMapper mapper, AdvancedRoutingProperties properties) {
        this.jdbc = jdbc; this.mapper = mapper; this.properties = properties;
    }

    public Map<String, Object> view(String visitorHash) {
        Map<String, Map<String, Object>> saved = new LinkedHashMap<>();
        jdbc.query("SELECT * FROM demo_tutorial_progress WHERE visitor_hash=?", result -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tutorialId", result.getString("tutorial_id"));
            item.put("tutorialVersion", result.getString("tutorial_version"));
            item.put("status", result.getString("status"));
            item.put("taskId", result.getString("task_instance_id"));
            item.put("runId", result.getString("run_id"));
            try {
                String evidenceJson = result.getString("evidence_json");
                item.put("evidence", evidenceJson == null || evidenceJson.isBlank()
                        ? Map.of() : mapper.readValue(evidenceJson, Map.class));
            } catch (Exception ignored) {
                item.put("evidence", Map.of());
            }
            if (result.getTimestamp("completed_at") != null) item.put("completedAt", result.getTimestamp("completed_at").toInstant().toString());
            saved.put(result.getString("tutorial_id"), item);
        }, visitorHash);
        String prologueStatus = status(saved.get(PROLOGUE_ID), PROLOGUE_VERSION);
        String fleetStatus = status(saved.get(FLEET_ID), FLEET_VERSION);
        String groundStatus = status(saved.get(GROUND_COOP_ID), GROUND_COOP_VERSION);
        boolean prologuePassed = passed(prologueStatus);
        boolean fleetPassed = passed(fleetStatus);
        boolean groundAccessPreserved = List.of("IN_PROGRESS", "COMPLETED", "SKIPPED").contains(groundStatus);
        boolean advancedUnlocked = passed(groundStatus);
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(chapter(PROLOGUE_ID, PROLOGUE_VERSION, "AVAILABLE", saved.get(PROLOGUE_ID)));
        items.add(chapter(FLEET_ID, FLEET_VERSION, prologuePassed ? "AVAILABLE" : "LOCKED", saved.get(FLEET_ID)));
        items.add(chapter(GROUND_COOP_ID, GROUND_COOP_VERSION,
                properties.tutorial02Enabled && (fleetPassed || groundAccessPreserved) ? "AVAILABLE" : "LOCKED", saved.get(GROUND_COOP_ID)));
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("technicalPreviewEnabled", properties.technicalPreviewEnabled);
        capabilities.put("tutorial02Enabled", properties.tutorial02Enabled);
        capabilities.put("advancedRoutingEnabled", properties.advancedEnabled);
        capabilities.put("advancedGroundRoutingUnlocked", properties.advancedEnabled && advancedUnlocked);
        capabilities.put("advancedRegionIds", List.of(ScenarioTemplateCatalog.CAMPUS_TEMPLATE_ID));
        capabilities.put("ruleVersion", AdvancedRoutingProperties.RULE_VERSION);
        return Map.of("items", items, "capabilities", capabilities);
    }

    public boolean advancedUnlocked(String visitorHash) {
        return properties.advancedEnabled && jdbc.queryForObject(
                "SELECT COUNT(*) FROM demo_tutorial_progress WHERE visitor_hash=? AND tutorial_id=? AND tutorial_version=? AND status IN ('COMPLETED','SKIPPED')",
                Integer.class, visitorHash, GROUND_COOP_ID, GROUND_COOP_VERSION) > 0;
    }

    public boolean groundCoopAvailable(String visitorHash) {
        if (!properties.tutorial02Enabled) return false;
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM demo_tutorial_progress WHERE visitor_hash=? AND ((tutorial_id=? AND tutorial_version=? AND status IN ('COMPLETED','SKIPPED')) OR (tutorial_id=? AND tutorial_version=? AND status IN ('IN_PROGRESS','COMPLETED','SKIPPED')))",
                Integer.class, visitorHash, FLEET_ID, FLEET_VERSION, GROUND_COOP_ID, GROUND_COOP_VERSION) > 0;
    }

    @Transactional
    public Map<String, Object> syncClientProgress(String visitorHash, String tutorialId, String tutorialVersion, String status) {
        boolean groundSkip = GROUND_COOP_ID.equals(tutorialId) && "SKIPPED".equalsIgnoreCase(String.valueOf(status));
        if (!List.of(PROLOGUE_ID, FLEET_ID).contains(tutorialId) && !groundSkip)
            throw new DemoException(HttpStatus.FORBIDDEN, "教程 02 完成状态必须由任务运行证据确认");
        String expectedVersion = PROLOGUE_ID.equals(tutorialId) ? PROLOGUE_VERSION
                : FLEET_ID.equals(tutorialId) ? FLEET_VERSION : GROUND_COOP_VERSION;
        if (!expectedVersion.equals(tutorialVersion)) throw new DemoException(HttpStatus.BAD_REQUEST, "教程版本无效");
        String normalized = String.valueOf(status).toUpperCase();
        if (!List.of("IN_PROGRESS", "COMPLETED", "SKIPPED").contains(normalized))
            throw new DemoException(HttpStatus.BAD_REQUEST, "教程状态无效");
        if (GROUND_COOP_ID.equals(tutorialId) && !groundCoopAvailable(visitorHash))
            throw new DemoException(HttpStatus.FORBIDDEN, "教程 02 尚未解锁");
        upsert(visitorHash, tutorialId, tutorialVersion, normalized,
                GROUND_COOP_ID.equals(tutorialId) ? "CLIENT_SKIP" : "CLIENT_MIGRATION", null, null, Map.of());
        return view(visitorHash);
    }

    @Transactional
    public void recordGroundCoopEvidence(String visitorHash, String taskId, String runId, Map<String, Object> evidence, boolean completed) {
        boolean checklistComplete = completed && List.of("baselineSelected", "taskStarted", "routeOutsideReward",
                        "returnedToBaseline", "uavTakeoff", "uavRecovered", "missionCompleted")
                .stream().allMatch(key -> Boolean.TRUE.equals(evidence.get(key)));
        upsert(visitorHash, GROUND_COOP_ID, GROUND_COOP_VERSION, checklistComplete ? "COMPLETED" : "IN_PROGRESS",
                "SERVER_EVIDENCE", taskId, runId, evidence);
    }

    private void upsert(String visitorHash, String tutorialId, String version, String status, String source,
                        String taskId, String runId, Map<String, Object> evidence) {
        Instant now = Instant.now();
        String evidenceJson;
        try { evidenceJson = mapper.writeValueAsString(evidence); }
        catch (Exception error) { throw new IllegalStateException(error); }
        Timestamp completedAt = "COMPLETED".equals(status) ? Timestamp.from(now) : null;
        int updated = updateExisting(visitorHash, tutorialId, version, status, source, taskId, runId, evidenceJson, completedAt);
        if (updated > 0) return;
        try {
            jdbc.update("INSERT INTO demo_tutorial_progress(visitor_hash,tutorial_id,tutorial_version,status,source,task_instance_id,run_id,evidence_json,completed_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    visitorHash, tutorialId, version, status, source, taskId, runId, evidenceJson, completedAt);
        } catch (DuplicateKeyException concurrentInsert) {
            updateExisting(visitorHash, tutorialId, version, status, source, taskId, runId, evidenceJson, completedAt);
        }
    }

    private int updateExisting(String visitorHash, String tutorialId, String version, String status, String source,
                               String taskId, String runId, String evidenceJson, Timestamp completedAt) {
        return jdbc.update("UPDATE demo_tutorial_progress SET status=CASE WHEN status='COMPLETED' THEN status ELSE ? END,"
                        + "source=?,task_instance_id=COALESCE(?,task_instance_id),run_id=COALESCE(?,run_id),"
                        + "evidence_json=?,completed_at=COALESCE(completed_at,?) WHERE visitor_hash=? AND tutorial_id=? AND tutorial_version=?",
                status, source, taskId, runId, evidenceJson, completedAt, visitorHash, tutorialId, version);
    }

    private static Map<String, Object> chapter(String id, String version, String availability, Map<String, Object> saved) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tutorialId", id); result.put("tutorialVersion", version); result.put("availability", availability);
        result.put("status", saved == null ? "NEW" : saved.getOrDefault("status", "NEW"));
        if (saved != null && saved.get("completedAt") != null) result.put("completedAt", saved.get("completedAt"));
        if (saved != null && saved.get("taskId") != null) result.put("taskId", saved.get("taskId"));
        if (saved != null && saved.get("runId") != null) result.put("runId", saved.get("runId"));
        if (saved != null) result.put("evidence", saved.getOrDefault("evidence", Map.of()));
        return result;
    }

    private static String status(Map<String, Object> value, String version) {
        if (value == null || !version.equals(String.valueOf(value.get("tutorialVersion")))) return "NEW";
        return String.valueOf(value.getOrDefault("status", "NEW"));
    }

    private static boolean passed(String status) {
        return "COMPLETED".equals(status) || "SKIPPED".equals(status);
    }
}
