package com.skyfleet.logistics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TaskTrafficEngine {
    private final double stopBufferMeters;
    private final SimulatedTrafficLightService fallbackLights;

    public TaskTrafficEngine(@Value("${mission.traffic.simulation.stop-buffer-meters:10}") double stopBufferMeters,
                             SimulatedTrafficLightService fallbackLights) {
        this.stopBufferMeters = Math.max(0, stopBufferMeters);
        this.fallbackLights = fallbackLights;
    }

    public TrafficRuleEngine.Decision govern(Map<String, Object> mission, String routeId, double currentProgress,
                                             double proposedProgress, double routeDistanceMeters, long simulationTimeMs) {
        Instant simulationNow = Instant.EPOCH.plusMillis(Math.max(0, simulationTimeMs));
        List<TrafficRuleEngine.Signal> signals = lights(mission, simulationTimeMs).stream()
                .filter(light -> routeId.equals(String.valueOf(light.get("routeId"))))
                .map(light -> new TrafficRuleEngine.Signal(routeId, String.valueOf(light.get("id")), number(light.get("routeProgress"), 0),
                        String.valueOf(light.get("lampStatus")), (int) number(light.get("countdown"), 0), simulationNow,
                        movement(light.get("movement")), signalType(light.get("signalType")), false, true, false, false, false, true))
                .toList();
        return TrafficRuleEngine.decide(currentProgress, proposedProgress, routeDistanceMeters, stopBufferMeters,
                Duration.ofSeconds(2), simulationNow, signals);
    }

    public List<Map<String, Object>> lights(Map<String, Object> mission, long simulationTimeMs) {
        if (disabled(mission)) return List.of();
        List<Map<String, Object>> sources = listOfMaps(mission.get("trafficLights"));
        if (sources.isEmpty() && !mission.containsKey("trafficLightStatus") && fallbackLights != null) return fallbackLights.publicLights();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> source : sources) {
            Phase phase = phase(source, simulationTimeMs / 1000);
            Map<String, Object> view = new LinkedHashMap<>(source);
            view.put("linkId", source.get("id"));
            view.put("lampStatus", phase.status());
            view.put("countdown", phase.countdown());
            view.put("updatedAt", Instant.EPOCH.plusMillis(Math.max(0, simulationTimeMs)).toString());
            result.add(view);
        }
        return result;
    }

    public Map<String, Object> status(Map<String, Object> mission) {
        if (disabled(mission)) return Map.of("provider", "TASK_INSTANCE_SEEDED", "state", "DISABLED",
                "message", "本次任务未启用交通信号灯", "routeCount", 0, "liveLightCount", 0, "simulated", true);
        int count = listOfMaps(mission.get("trafficLights")).size();
        if (count == 0 && !mission.containsKey("trafficLightStatus") && fallbackLights != null) return fallbackLights.statusView();
        return Map.of("provider", "TASK_INSTANCE_SEEDED", "state", "READY", "message", "任务级信号计划运行中",
                "routeCount", count == 0 ? 0 : 1, "liveLightCount", count, "simulated", true);
    }

    private static Phase phase(Map<String, Object> light, long second) {
        int red = (int) number(light.get("redDurationSeconds"), 35);
        int green = (int) number(light.get("greenDurationSeconds"), 30);
        int yellow = (int) number(light.get("yellowDurationSeconds"), 3);
        int offset = (int) number(light.get("phaseOffsetSeconds"), 0);
        int cycle = Math.max(1, red + green + yellow);
        int cursor = Math.floorMod((int) second + offset, cycle);
        if (cursor < green) return new Phase("GREEN", green - cursor);
        if (cursor < green + yellow) return new Phase("YELLOW", green + yellow - cursor);
        return new Phase("RED", cycle - cursor);
    }

    private static TrafficRuleEngine.Movement movement(Object value) {
        try { return TrafficRuleEngine.Movement.valueOf(String.valueOf(value)); }
        catch (Exception ignored) { return TrafficRuleEngine.Movement.STRAIGHT; }
    }
    private static TrafficRuleEngine.SignalType signalType(Object value) {
        try { return TrafficRuleEngine.SignalType.valueOf(String.valueOf(value)); }
        catch (Exception ignored) { return TrafficRuleEngine.SignalType.CIRCULAR; }
    }
    @SuppressWarnings("unchecked")
    private static boolean disabled(Map<String, Object> mission) {
        Object raw = mission.get("trafficLightStatus");
        return raw instanceof Map<?, ?> status && "DISABLED".equalsIgnoreCase(String.valueOf(status.get("state")));
    }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> listOfMaps(Object value) { return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of(); }
    private static double number(Object value, double fallback) { return value instanceof Number number ? number.doubleValue() : fallback; }
    private record Phase(String status, int countdown) {}
}
