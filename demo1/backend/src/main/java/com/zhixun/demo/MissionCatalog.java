package com.zhixun.demo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        try (InputStream input = new ClassPathResource("mission/patrol-mission.json").getInputStream()) {
            definition = mapper.readValue(input, new TypeReference<>() {});
        }
        jdbc.update("INSERT INTO demo_mission_definition(scenario_key,version_no,coordinate_system,definition_json,source_label) VALUES(?,?,?,?,?) " +
                        "ON DUPLICATE KEY UPDATE version_no=VALUES(version_no),coordinate_system=VALUES(coordinate_system),definition_json=VALUES(definition_json),source_label=VALUES(source_label)",
                definition.get("scenarioKey"), definition.get("version"), definition.get("coordinateSystem"), mapper.writeValueAsString(definition),
                "百度路线规划结果·人工校核");
    }

    public Map<String, Object> copy() {
        return mapper.convertValue(definition, new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> routes() {
        return (List<Map<String, Object>>) definition.getOrDefault("routes", List.of());
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
    public List<Map<String, Object>> pairs() {
        return (List<Map<String, Object>>) definition.getOrDefault("pairs", List.of());
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> events() {
        return (List<Map<String, Object>>) definition.getOrDefault("events", List.of());
    }

    public Map<String, Object> standbySnapshot() {
        Map<String, Object> mission = copy();
        mission.put("state", "STANDBY");
        mission.put("status", "STANDBY");
        mission.put("progress", 0);
        mission.put("missionPhase", "DOCKED");
        mission.put("routeVersion", definition.get("version"));
        mission.put("routes", new ArrayList<>(routes().stream().map(route -> {
            Map<String, Object> copy = new LinkedHashMap<>(route);
            copy.put("actualPoints", List.of());
            return copy;
        }).toList()));
        return mission;
    }
}
