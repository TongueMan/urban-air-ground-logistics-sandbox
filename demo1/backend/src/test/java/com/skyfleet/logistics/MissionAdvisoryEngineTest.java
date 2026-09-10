package com.skyfleet.logistics;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MissionAdvisoryEngineTest {
    private final MissionAdvisoryEngine engine = new MissionAdvisoryEngine();

    @Test
    void recommendsEligibleDeliveryActorAndNeverMarksItExecutable() {
        DemoSignal signal = new DemoSignal("SIG-1", "road", "ROAD_OBSTACLE", "WARNING", "DETECTED",
                "DELIVERING", "UAV-A", 55, Instant.now(), Instant.now(), Map.of("label", "道路障碍"));
        var recommendation = engine.recommend(signal, List.of(
                actor("UAV-A", 72, 91, 117.0, 31.0, List.of("DELIVERY", "FLIGHT")),
                actor("UAV-B", 90, 95, 117.02, 31.0, List.of("DELIVERY", "FLIGHT")),
                actor("VEH-1", 92, 100, 117.0, 31.0, List.of("MOBILITY"))));

        assertEquals("REDELIVER", recommendation.get("action"));
        assertEquals("UAV-A", recommendation.get("recommendedActorId"));
        assertEquals(true, recommendation.get("requiresHumanApproval"));
        assertEquals(false, recommendation.get("executable"));
        assertEquals(2, ((List<?>) recommendation.get("candidates")).size());
        assertEquals(true, ((Map<?, ?>) ((List<?>) recommendation.get("options")).get(0)).get("eligible"));
    }

    @Test
    void excludesActorsBelowSafetyThresholds() {
        DemoSignal signal = new DemoSignal("SIG-1", "road", "ROAD_OBSTACLE", "WARNING", "DETECTED",
                "DELIVERING", null, 55, Instant.now(), Instant.now(), Map.of());
        var recommendation = engine.recommend(signal, List.of(actor("UAV-A", 20, 90, 117, 31, List.of("DELIVERY"))));
        assertNull(recommendation.get("recommendedActorId"));
        assertTrue(String.valueOf(recommendation.get("title")).contains("没有"));
    }

    @Test
    void objectivesProduceDeterministicAndDifferentRankings() {
        DemoSignal signal = new DemoSignal("SIG-1", "road", "ROAD_OBSTACLE", "WARNING", "DETECTED",
                "DELIVERING", "UAV-A", 55, Instant.now(), Instant.now(), Map.of("longitude", 117.0, "latitude", 31.0));
        List<Map<String, Object>> actors = List.of(
                actor("UAV-A", 55, 70, 117.001, 31.0, List.of("DELIVERY")),
                actor("UAV-B", 96, 99, 117.01, 31.0, List.of("DELIVERY")));

        var fastest = engine.recommend(signal, actors, "FASTEST");
        var safest = engine.recommend(signal, actors, "SAFEST");
        assertEquals("UAV-A", fastest.get("recommendedActorId"));
        assertEquals("UAV-B", safest.get("recommendedActorId"));
        assertEquals(fastest, engine.recommend(signal, actors, "FASTEST"));
        Map<?, ?> impact = (Map<?, ?>) ((Map<?, ?>) ((List<?>) fastest.get("options")).get(0)).get("impact");
        assertEquals(true, impact.get("estimate"));
        assertTrue(((Number) impact.get("distanceMeters")).longValue() > 0);
    }

    @Test
    void rejectsUnknownObjective() {
        DemoSignal signal = new DemoSignal("SIG-1", "road", "ROAD_OBSTACLE", "WARNING", "DETECTED",
                "DELIVERING", "UAV-A", 55, Instant.now(), Instant.now(), Map.of());
        assertThrows(IllegalArgumentException.class, () -> engine.recommend(signal, List.of(), "CHEAPEST"));
    }

    private static Map<String, Object> actor(String id, double battery, double link, double longitude, double latitude, List<String> capabilities) {
        return Map.of("deviceId", id, "deviceName", id, "capabilities", capabilities,
                "longitude", longitude, "latitude", latitude,
                "sensorData", Map.of("battery", battery, "linkQuality", link));
    }
}
