package com.skyfleet.logistics;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TrafficRuleEngineTest {
    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

    @Test
    void emptyRealResultMeansThereIsNoTrafficLightConstraint() {
        var decision = TrafficRuleEngine.decide(20, 21, 2000, 10, Duration.ofSeconds(5), NOW, List.of());
        assertThat(decision.stopped()).isFalse();
        assertThat(decision.nextProgress()).isEqualTo(21);
    }

    @Test
    void redLightStopsBeforeTheIntersectionAndGreenReleasesTheVehicle() {
        var red = new TrafficRuleEngine.Signal("GROUND-A", "123", 25, "RED", 18, NOW);
        var stopped = TrafficRuleEngine.decide(24.4, 25.2, 2000, 10, Duration.ofSeconds(5), NOW, List.of(red));
        assertThat(stopped.stopped()).isTrue();
        assertThat(stopped.nextProgress()).isCloseTo(24.5, within(.00001));

        var green = new TrafficRuleEngine.Signal("GROUND-A", "123", 25, "GREEN", 12, NOW);
        var released = TrafficRuleEngine.decide(stopped.nextProgress(), 25.1, 2000, 10, Duration.ofSeconds(5), NOW, List.of(green));
        assertThat(released.stopped()).isFalse();
        assertThat(released.nextProgress()).isEqualTo(25.1);
    }

    @Test
    void staleOrAlreadyPassedSignalsNeverStopTheVehicle() {
        var stale = new TrafficRuleEngine.Signal("GROUND-A", "123", 25, "RED", 18, NOW.minusSeconds(10));
        assertThat(TrafficRuleEngine.decide(24.5, 25, 2000, 10, Duration.ofSeconds(5), NOW, List.of(stale)).stopped()).isFalse();

        var passed = new TrafficRuleEngine.Signal("GROUND-A", "123", 20, "RED", 18, NOW);
        assertThat(TrafficRuleEngine.decide(21, 22, 2000, 10, Duration.ofSeconds(5), NOW, List.of(passed)).stopped()).isFalse();
    }

    @Test
    void circularRedAllowsRightTurnOnlyWhenTheTurnIsLegalAndClear() {
        var clearRight = signal("RED", TrafficRuleEngine.Movement.RIGHT,
                TrafficRuleEngine.SignalType.CIRCULAR, false, true, false, false, false, true);
        assertThat(decide(clearRight).stopped()).isFalse();

        var arrowRed = signal("RED", TrafficRuleEngine.Movement.RIGHT,
                TrafficRuleEngine.SignalType.DIRECTIONAL, false, true, false, false, false, true);
        assertThat(decide(arrowRed).reason()).isEqualTo("RED_DIRECTIONAL_SIGNAL");

        var prohibited = signal("RED", TrafficRuleEngine.Movement.RIGHT,
                TrafficRuleEngine.SignalType.CIRCULAR, true, true, false, false, false, true);
        assertThat(decide(prohibited).reason()).isEqualTo("RED_SIGNAL");

        var pedestrian = signal("RED", TrafficRuleEngine.Movement.RIGHT,
                TrafficRuleEngine.SignalType.CIRCULAR, false, true, false, true, false, true);
        assertThat(decide(pedestrian).reason()).isEqualTo("YIELD_TO_PEDESTRIAN");
    }

    @Test
    void greenDoesNotAuthorizeEnteringABlockedJunctionOrIgnoringPriorityTraffic() {
        var blocked = signal("GREEN", TrafficRuleEngine.Movement.STRAIGHT,
                TrafficRuleEngine.SignalType.CIRCULAR, false, true, true, false, false, true);
        assertThat(decide(blocked).reason()).isEqualTo("INTERSECTION_BLOCKED");

        var yieldingLeft = signal("GREEN", TrafficRuleEngine.Movement.LEFT,
                TrafficRuleEngine.SignalType.CIRCULAR, false, true, false, false, true, true);
        assertThat(decide(yieldingLeft).reason()).isEqualTo("YIELD_TO_PRIORITY_TRAFFIC");
    }

    @Test
    void yellowStopsBeforeTheLineButNeverStopsAVehicleThatAlreadyCrossedIt() {
        var yellow = signal("YELLOW", TrafficRuleEngine.Movement.STRAIGHT,
                TrafficRuleEngine.SignalType.CIRCULAR, false, true, false, false, false, true);
        assertThat(decide(yellow).reason()).isEqualTo("YELLOW_BEFORE_STOP_LINE");
        assertThat(TrafficRuleEngine.decide(25.01, 25.4, 2000, 10, Duration.ofSeconds(5), NOW, List.of(yellow)).stopped()).isFalse();
    }

    @Test
    void prohibitedLaneMovementAndUTurnOverrideAProceedSignal() {
        var wrongLane = signal("GREEN", TrafficRuleEngine.Movement.RIGHT,
                TrafficRuleEngine.SignalType.CIRCULAR, false, false, false, false, false, true);
        assertThat(decide(wrongLane).reason()).isEqualTo("LANE_MOVEMENT_PROHIBITED");

        var uTurn = signal("GREEN", TrafficRuleEngine.Movement.U_TURN,
                TrafficRuleEngine.SignalType.DIRECTIONAL, false, true, false, false, false, false);
        assertThat(decide(uTurn).reason()).isEqualTo("U_TURN_PROHIBITED");
    }

    private static TrafficRuleEngine.Signal signal(String status, TrafficRuleEngine.Movement movement,
                                                    TrafficRuleEngine.SignalType signalType, boolean noTurnOnRed,
                                                    boolean laneAllowsMovement, boolean blocked, boolean pedestrian,
                                                    boolean conflictingTraffic, boolean uTurnAllowed) {
        return new TrafficRuleEngine.Signal("GROUND-A", "123", 25, status, 18, NOW, movement, signalType,
                noTurnOnRed, laneAllowsMovement, blocked, pedestrian, conflictingTraffic, uTurnAllowed);
    }

    private static TrafficRuleEngine.Decision decide(TrafficRuleEngine.Signal signal) {
        return TrafficRuleEngine.decide(24.4, 25.2, 2000, 10, Duration.ofSeconds(5), NOW, List.of(signal));
    }
}
