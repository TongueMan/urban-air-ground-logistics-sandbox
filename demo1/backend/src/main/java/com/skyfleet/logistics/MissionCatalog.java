package com.skyfleet.logistics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class MissionCatalog {
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private Map<String, Object> definition;

    public MissionCatalog(ObjectMapper mapper, JdbcTemplate jdbc) {
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    @PostConstruct
    void load() throws Exception {
        try (InputStream input = new ClassPathResource("mission/logistics-mission.json").getInputStream()) {
            definition = mapper.readValue(input, new TypeReference<>() {});
        }
        jdbc.update("INSERT INTO demo_mission_definition(scenario_key,version_no,coordinate_system,definition_json,source_label) VALUES(?,?,?,?,?) " +
                        "ON DUPLICATE KEY UPDATE version_no=VALUES(version_no),coordinate_system=VALUES(coordinate_system),definition_json=VALUES(definition_json),source_label=VALUES(source_label)",
                definition.get("scenarioKey"), definition.get("version"), definition.get("coordinateSystem"), mapper.writeValueAsString(definition),
                "百度路线规划结果·人工校核");
        jdbc.update("INSERT IGNORE INTO demo_mission_definition_version(scenario_key,version_no,coordinate_system,definition_json,source_label) VALUES(?,?,?,?,?)",
                definition.get("scenarioKey"), definition.get("version"), definition.get("coordinateSystem"), mapper.writeValueAsString(definition),
                "百度路线规划结果·人工校核");
    }

    public String definitionId() { return String.valueOf(definition.get("scenarioKey")); }

    public String definitionVersion() { return String.valueOf(definition.get("version")); }

    public Map<String, Object> copy() {
        return mapper.convertValue(definition, new TypeReference<>() {});
    }

    public Map<String, Object> copy(String definitionId, String definitionVersion) {
        if (definitionId().equals(definitionId) && definitionVersion().equals(definitionVersion)) return copy();
        String json = jdbc.queryForObject(
                "SELECT definition_json FROM demo_mission_definition_version WHERE scenario_key=? AND version_no=?",
                String.class, definitionId, definitionVersion);
        try { return mapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception error) { throw new IllegalStateException("任务定义无法读取: " + definitionId + "@" + definitionVersion, error); }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> routes() {
        return (List<Map<String, Object>>) definition.getOrDefault("routes", List.of());
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> actors(String definitionId, String definitionVersion) {
        return (List<Map<String, Object>>) copy(definitionId, definitionVersion).getOrDefault("actors", List.of());
    }

    public Map<String, Object> actor(String definitionId, String definitionVersion, String actorId) {
        return actors(definitionId, definitionVersion).stream()
                .filter(actor -> actorId.equals(String.valueOf(actor.get("id"))))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("任务定义中不存在 Actor: " + actorId));
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> routes(String definitionId, String definitionVersion) {
        return (List<Map<String, Object>>) copy(definitionId, definitionVersion).getOrDefault("routes", List.of());
    }

    public Map<String, Object> route(String definitionId, String definitionVersion, String routeId) {
        return routes(definitionId, definitionVersion).stream()
                .filter(route -> routeId.equals(String.valueOf(route.get("routeId"))))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("任务定义中不存在 Route: " + routeId));
    }

    public Map<String, Object> route(String deviceId) {
        return routes().stream().filter(route -> deviceId.equals(route.get("deviceId"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    public List<double[]> points(String deviceId) {
        List<List<Number>> raw = (List<List<Number>>) route(deviceId).get("points");
        List<double[]> result = new ArrayList<>();
        raw.forEach(point -> result.add(new double[]{point.get(0).doubleValue(), point.get(1).doubleValue(), point.get(2).doubleValue()}));
        return result;
    }

    @SuppressWarnings("unchecked")
    public List<double[]> points(String definitionId, String definitionVersion, String routeId) {
        List<List<Number>> raw = (List<List<Number>>) route(definitionId, definitionVersion, routeId).get("points");
        List<double[]> result = new ArrayList<>();
        raw.forEach(point -> result.add(new double[]{point.get(0).doubleValue(), point.get(1).doubleValue(), point.get(2).doubleValue()}));
        return result;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> pairs() {
        return (List<Map<String, Object>>) definition.getOrDefault("pairs", List.of());
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> events() {
        return (List<Map<String, Object>>) definition.getOrDefault("events", List.of());
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> events(String definitionId, String definitionVersion) {
        return (List<Map<String, Object>>) copy(definitionId, definitionVersion).getOrDefault("events", List.of());
    }

    public Map<String, Object> standbySnapshot() {
        Map<String, Object> mission = copy();
        mission.put("state", "STANDBY");
        mission.put("status", "STANDBY");
        mission.put("progress", 0);
        mission.put("missionPhase", "DOCKED");
        mission.put("routeVersion", definition.get("version"));
        // The catalog is an authoring-time template, not an already-created task.
        // Publishing its task artifacts in STANDBY made a brand-new visitor see
        // two baseline city scenarios before generating anything.
        mission.put("actors", List.of());
        mission.put("formations", List.of());
        mission.put("assignments", List.of());
        mission.put("pairs", List.of());
        mission.put("routes", List.of());
        mission.put("events", List.of());
        mission.put("trafficLights", List.of());
        mission.put("trafficLightStatus", Map.of(
                "provider", "TASK_INSTANCE_SEEDED",
                "state", "DISABLED",
                "message", "尚未创建任务",
                "routeCount", 0,
                "liveLightCount", 0,
                "simulated", true));
        return mission;
    }
}
