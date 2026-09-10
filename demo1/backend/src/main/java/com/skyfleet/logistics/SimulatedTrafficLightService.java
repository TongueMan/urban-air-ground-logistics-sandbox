package com.skyfleet.logistics;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SimulatedTrafficLightService {
    private final MissionCatalog catalog;
    private final double spacingMeters;
    private final double stopBufferMeters;
    private final Map<String, List<Intersection>> intersectionsByRoute = new ConcurrentHashMap<>();

    public SimulatedTrafficLightService(MissionCatalog catalog,
                                        @Value("${mission.traffic.simulation.spacing-meters:360}") double spacingMeters,
                                        @Value("${mission.traffic.simulation.stop-buffer-meters:10}") double stopBufferMeters) {
        this.catalog = catalog;
        this.spacingMeters = Math.max(220, spacingMeters);
        this.stopBufferMeters = Math.max(0, stopBufferMeters);
    }

    @PostConstruct
    void initialize() {
        for (Map<String, Object> route : catalog.routes()) {
            if (!"GROUND".equals(String.valueOf(route.get("kind")))) continue;
            String routeId = String.valueOf(route.get("routeId"));
            String deviceId = String.valueOf(route.get("deviceId"));
            List<double[]> points = catalog.points(catalog.definitionId(), catalog.definitionVersion(), routeId);
            double distance = number(route.get("distanceMeters"), polylineDistance(points));
            intersectionsByRoute.put(routeId, generateIntersections(routeId, deviceId, points, distance));
        }
    }

    public TrafficRuleEngine.Decision govern(String routeId, double currentProgress, double proposedProgress,
                                             double routeDistanceMeters, Instant now) {
        return TrafficRuleEngine.decide(currentProgress, proposedProgress, routeDistanceMeters, stopBufferMeters,
                Duration.ofSeconds(2), now, signalsForRoute(routeId, now));
    }

    public List<Map<String, Object>> publicLights() {
        Instant now = Instant.now();
        List<Map<String, Object>> result = new ArrayList<>();
        intersectionsByRoute.values().stream().flatMap(List::stream)
                .sorted(Comparator.comparing(Intersection::routeId).thenComparingDouble(Intersection::routeProgress))
                .forEach(intersection -> {
                    LightPhase phase = phaseAt(intersection.id(), now.getEpochSecond());
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("id", intersection.id());
                    view.put("routeId", intersection.routeId());
                    view.put("deviceId", intersection.deviceId());
                    view.put("linkId", intersection.id());
                    view.put("longitude", intersection.longitude());
                    view.put("latitude", intersection.latitude());
                    view.put("coordinateSystem", "BD09LL");
                    view.put("routeProgress", intersection.routeProgress());
                    view.put("movement", intersection.movement().name());
                    view.put("signalType", intersection.signalType().name());
                    view.put("noTurnOnRed", intersection.noTurnOnRed());
                    view.put("canTurnOnRed", intersection.movement() == TrafficRuleEngine.Movement.RIGHT
                            && intersection.signalType() == TrafficRuleEngine.SignalType.CIRCULAR
                            && !intersection.noTurnOnRed());
                    view.put("lampStatus", phase.status());
                    view.put("countdown", phase.countdown());
                    view.put("redDurationSeconds", phase.redDuration());
                    view.put("greenDurationSeconds", phase.greenDuration());
                    view.put("yellowDurationSeconds", phase.yellowDuration());
                    view.put("updatedAt", now.toString());
                    view.put("source", "LOCAL_INTERSECTION_SIMULATION");
                    result.add(view);
                });
        return result;
    }

    /**
     * Returns the stable route-aligned intersection catalogue without a live
     * phase. Dynamic task instances project these nodes onto their immutable
     * route artifact and seed only the phase timing, never the map position.
     */
    public List<Map<String, Object>> templateNodes(String routeId) {
        return intersectionsByRoute.getOrDefault(routeId, List.of()).stream()
                .sorted(Comparator.comparingDouble(Intersection::routeProgress))
                .map(intersection -> {
                    Map<String, Object> node = new LinkedHashMap<>();
                    node.put("id", intersection.id());
                    node.put("routeId", intersection.routeId());
                    node.put("deviceId", intersection.deviceId());
                    node.put("longitude", intersection.longitude());
                    node.put("latitude", intersection.latitude());
                    node.put("coordinateSystem", "BD09LL");
                    node.put("routeProgress", intersection.routeProgress());
                    node.put("movement", intersection.movement().name());
                    node.put("signalType", intersection.signalType().name());
                    node.put("noTurnOnRed", intersection.noTurnOnRed());
                    node.put("uTurnAllowed", intersection.uTurnAllowed());
                    node.put("source", "TEMPLATE_INTERSECTION_CATALOG");
                    return node;
                }).toList();
    }

    public Map<String, Object> statusView() {
        int count = intersectionsByRoute.values().stream().mapToInt(List::size).sum();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", "LOCAL_INTERSECTION_SIMULATION");
        result.put("state", "READY");
        result.put("message", "路口信号灯仿真运行中");
        result.put("routeCount", intersectionsByRoute.size());
        result.put("liveLightCount", count);
        result.put("simulated", true);
        result.put("redDurationRangeSeconds", List.of(30, 40));
        result.put("greenDurationRangeSeconds", List.of(25, 40));
        result.put("yellowDurationSeconds", 3);
        return result;
    }

    private List<TrafficRuleEngine.Signal> signalsForRoute(String routeId, Instant now) {
        return intersectionsByRoute.getOrDefault(routeId, List.of()).stream().map(intersection -> {
            LightPhase phase = phaseAt(intersection.id(), now.getEpochSecond());
            return new TrafficRuleEngine.Signal(routeId, intersection.id(), intersection.routeProgress(),
                    phase.status(), phase.countdown(), now, intersection.movement(), intersection.signalType(),
                    intersection.noTurnOnRed(), true, false, false, false, intersection.uTurnAllowed());
        }).toList();
    }

    private List<Intersection> generateIntersections(String routeId, String deviceId, List<double[]> points, double distanceMeters) {
        int count = Math.max(4, Math.min(8, (int) Math.round(distanceMeters / spacingMeters)));
        List<Intersection> result = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            double targetProgress = index * 100.0 / (count + 1);
            double snappedProgress = snapToLikelyJunction(points, targetProgress, distanceMeters);
            double candidateProgress = snappedProgress;
            if (result.stream().anyMatch(item -> Math.abs(item.routeProgress() - candidateProgress) * distanceMeters / 100 < 120)) {
                snappedProgress = targetProgress;
            }
            double[] point = MissionMath.sample(points, snappedProgress / 100);
            TrafficRuleEngine.Movement movement = movementAt(points, snappedProgress, distanceMeters);
            TrafficRuleEngine.SignalType signalType = movement == TrafficRuleEngine.Movement.LEFT
                    || movement == TrafficRuleEngine.Movement.U_TURN
                    ? TrafficRuleEngine.SignalType.DIRECTIONAL : TrafficRuleEngine.SignalType.CIRCULAR;
            result.add(new Intersection("SIM-" + routeId + "-" + String.format("%02d", index), routeId, deviceId,
                    point[0], point[1], snappedProgress, movement, signalType, false,
                    movement != TrafficRuleEngine.Movement.U_TURN || Math.abs(snappedProgress - targetProgress) < 4));
        }
        return List.copyOf(result);
    }

    private static double snapToLikelyJunction(List<double[]> points, double targetProgress, double routeDistanceMeters) {
        if (points.size() < 3) return targetProgress;
        double bestProgress = targetProgress;
        double bestScore = 0;
        double traversed = 0;
        for (int index = 1; index < points.size() - 1; index++) {
            traversed += distance(points.get(index - 1), points.get(index));
            double progress = traversed / Math.max(1, routeDistanceMeters) * 100;
            double distanceFromTarget = Math.abs(progress - targetProgress) * routeDistanceMeters / 100;
            if (distanceFromTarget > 140) continue;
            double turn = Math.abs(headingDelta(bearing(points.get(index - 1), points.get(index)),
                    bearing(points.get(index), points.get(index + 1))));
            double score = Math.max(0, turn - 10) * (1 - distanceFromTarget / 180);
            if (score > bestScore) {
                bestScore = score;
                bestProgress = progress;
            }
        }
        return bestScore >= 8 ? MissionMath.clamp(bestProgress, 4, 96) : targetProgress;
    }

    static LightPhase phaseAt(String intersectionId, long epochSecond) {
        int seed = intersectionId.hashCode() & Integer.MAX_VALUE;
        int red = 30 + seed % 11;
        int green = 25 + (seed / 11) % 16;
        int yellow = 3;
        int cycle = red + green + yellow;
        int offset = (seed / 176) % cycle;
        int second = Math.floorMod(epochSecond + offset, cycle);
        if (second < green) return new LightPhase("GREEN", green - second, red, green, yellow);
        if (second < green + yellow) return new LightPhase("YELLOW", green + yellow - second, red, green, yellow);
        return new LightPhase("RED", cycle - second, red, green, yellow);
    }

    private static double headingDelta(double from, double to) { return ((to - from + 540) % 360) - 180; }

    static TrafficRuleEngine.Movement movementAt(List<double[]> points, double routeProgress, double routeDistanceMeters) {
        if (points.size() < 3) return TrafficRuleEngine.Movement.STRAIGHT;
        double lookAroundPercent = MissionMath.clamp(35 / Math.max(1, routeDistanceMeters) * 100, .25, 3);
        double beforeProgress = MissionMath.clamp(routeProgress - lookAroundPercent, 0, 100);
        double afterProgress = MissionMath.clamp(routeProgress + lookAroundPercent, 0, 100);
        double[] before = MissionMath.sample(points, beforeProgress / 100);
        double[] center = MissionMath.sample(points, routeProgress / 100);
        double[] after = MissionMath.sample(points, afterProgress / 100);
        double delta = headingDelta(bearing(before, center), bearing(center, after));
        if (Math.abs(delta) >= 145) return TrafficRuleEngine.Movement.U_TURN;
        if (delta >= 22) return TrafficRuleEngine.Movement.RIGHT;
        if (delta <= -22) return TrafficRuleEngine.Movement.LEFT;
        return TrafficRuleEngine.Movement.STRAIGHT;
    }

    private static double bearing(double[] from, double[] to) {
        double latitude = Math.toRadians((from[1] + to[1]) / 2);
        return (Math.toDegrees(Math.atan2((to[0] - from[0]) * Math.cos(latitude), to[1] - from[1])) + 360) % 360;
    }

    private static double polylineDistance(List<double[]> points) {
        double total = 0;
        for (int index = 1; index < points.size(); index++) total += distance(points.get(index - 1), points.get(index));
        return total;
    }

    private static double distance(double[] from, double[] to) {
        double latitude = Math.toRadians((from[1] + to[1]) / 2);
        return Math.hypot((to[0] - from[0]) * 111320 * Math.cos(latitude), (to[1] - from[1]) * 110540);
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    record LightPhase(String status, int countdown, int redDuration, int greenDuration, int yellowDuration) {}
    private record Intersection(String id, String routeId, String deviceId, double longitude, double latitude,
                                double routeProgress, TrafficRuleEngine.Movement movement,
                                TrafficRuleEngine.SignalType signalType, boolean noTurnOnRed,
                                boolean uTurnAllowed) {}
}
