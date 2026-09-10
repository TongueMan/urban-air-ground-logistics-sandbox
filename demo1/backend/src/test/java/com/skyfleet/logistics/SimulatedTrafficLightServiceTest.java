package com.skyfleet.logistics;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatedTrafficLightServiceTest {
    @Test
    void generatedCyclesUseReasonableIndependentDurations() {
        Set<Integer> redDurations = new HashSet<>();
        Set<Integer> greenDurations = new HashSet<>();
        for (int index = 0; index < 30; index++) {
            var phase = SimulatedTrafficLightService.phaseAt("SIM-GROUND-A-" + index, 1_800_000_000L);
            assertThat(phase.redDuration()).isBetween(30, 40);
            assertThat(phase.greenDuration()).isBetween(25, 40);
            assertThat(phase.yellowDuration()).isEqualTo(3);
            assertThat(phase.countdown()).isPositive();
            redDurations.add(phase.redDuration());
            greenDurations.add(phase.greenDuration());
        }
        assertThat(redDurations.size()).isGreaterThan(3);
        assertThat(greenDurations.size()).isGreaterThan(3);
    }

    @Test
    void countdownReachesOneBeforeEachPhaseChanges() {
        String id = "SIM-GROUND-B-03";
        String previous = null;
        boolean observedBoundary = false;
        for (long second = 1_800_000_000L; second < 1_800_000_200L; second++) {
            var phase = SimulatedTrafficLightService.phaseAt(id, second);
            if (previous != null && !previous.equals(phase.status())) observedBoundary = true;
            if (phase.countdown() == 1) {
                var next = SimulatedTrafficLightService.phaseAt(id, second + 1);
                assertThat(next.status()).isNotEqualTo(phase.status());
            }
            previous = phase.status();
        }
        assertThat(observedBoundary).isTrue();
    }

    @Test
    void routeGeometryDeterminesStraightLeftRightAndUTurnMovements() {
        List<double[]> right = List.of(point(0, 0), point(0, .001), point(.001, .001));
        List<double[]> left = List.of(point(0, 0), point(0, .001), point(-.001, .001));
        List<double[]> straight = List.of(point(0, 0), point(0, .001), point(0, .002));

        assertThat(SimulatedTrafficLightService.movementAt(right, 50, 222)).isEqualTo(TrafficRuleEngine.Movement.RIGHT);
        assertThat(SimulatedTrafficLightService.movementAt(left, 50, 222)).isEqualTo(TrafficRuleEngine.Movement.LEFT);
        assertThat(SimulatedTrafficLightService.movementAt(straight, 50, 222)).isEqualTo(TrafficRuleEngine.Movement.STRAIGHT);
    }

    private static double[] point(double longitude, double latitude) {
        return new double[]{longitude, latitude, 0};
    }
}
