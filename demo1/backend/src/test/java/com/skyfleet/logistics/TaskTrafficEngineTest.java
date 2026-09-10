package com.skyfleet.logistics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TaskTrafficEngineTest {
    private final TaskTrafficEngine engine = new TaskTrafficEngine(10, null);
    private final Map<String, Object> mission = Map.of("trafficLights", List.of(Map.of(
            "id", "SIGNAL-1", "routeId", "GROUND-A", "routeProgress", 25,
            "movement", "STRAIGHT", "signalType", "CIRCULAR",
            "greenDurationSeconds", 30, "yellowDurationSeconds", 3,
            "redDurationSeconds", 35, "phaseOffsetSeconds", 7
    )));

    @Test
    void signalSequenceDependsOnlyOnFrozenPlanAndSimulationTime() {
        assertThat(engine.lights(mission, 12_000)).isEqualTo(engine.lights(mission, 12_000));
        assertThat(engine.lights(mission, 12_000).get(0).get("lampStatus")).isEqualTo("GREEN");
        assertThat(engine.lights(mission, 30_000).get(0).get("lampStatus")).isEqualTo("RED");
    }

    @Test
    void redPhaseStopsTheVehicleAtTheFrozenRouteNode() {
        var decision = engine.govern(mission, "GROUND-A", 24.4, 25.2, 2_000, 30_000);
        assertThat(decision.stopped()).isTrue();
        assertThat(decision.signal().linkId()).isEqualTo("SIGNAL-1");
    }

    @Test
    void disabledTrafficPlanHasNoLightsAndNeverStopsTheVehicle() {
        Map<String, Object> disabled = Map.of(
                "trafficLights", mission.get("trafficLights"),
                "trafficLightStatus", Map.of("state", "DISABLED")
        );
        assertThat(engine.lights(disabled, 30_000)).isEmpty();
        assertThat(engine.govern(disabled, "GROUND-A", 24.4, 25.2, 2_000, 30_000).stopped()).isFalse();
        assertThat(engine.status(disabled).get("state")).isEqualTo("DISABLED");
    }
}
