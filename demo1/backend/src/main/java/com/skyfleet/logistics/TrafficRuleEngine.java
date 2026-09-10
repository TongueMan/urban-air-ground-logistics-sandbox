package com.skyfleet.logistics;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public final class TrafficRuleEngine {
    private TrafficRuleEngine() {}

    public enum Movement { STRAIGHT, LEFT, RIGHT, U_TURN }
    public enum SignalType { CIRCULAR, DIRECTIONAL }

    public record Signal(String routeId, String linkId, double routeProgress, String status,
                         Integer countdown, Instant updatedAt, Movement movement, SignalType signalType,
                         boolean noTurnOnRed, boolean laneAllowsMovement, boolean intersectionBlocked,
                         boolean pedestrianConflict, boolean conflictingTraffic, boolean uTurnAllowed) {
        public Signal {
            movement = movement == null ? Movement.STRAIGHT : movement;
            signalType = signalType == null ? SignalType.CIRCULAR : signalType;
        }

        public Signal(String routeId, String linkId, double routeProgress, String status,
                      Integer countdown, Instant updatedAt) {
            this(routeId, linkId, routeProgress, status, countdown, updatedAt,
                    Movement.STRAIGHT, SignalType.CIRCULAR, false, true,
                    false, false, false, true);
        }
    }

    public record Decision(double nextProgress, boolean stopped, Signal signal, String reason) {
        static Decision proceed(double nextProgress) { return new Decision(nextProgress, false, null, "CLEAR"); }
    }

    public static Decision decide(double currentProgress, double proposedProgress, double routeDistanceMeters,
                                  double stopBufferMeters, Duration freshness, Instant now, List<Signal> signals) {
        double current = MissionMath.clamp(currentProgress, 0, 100);
        double proposed = MissionMath.clamp(Math.max(current, proposedProgress), 0, 100);
        double bufferPercent = Math.max(0, stopBufferMeters) / Math.max(1, routeDistanceMeters) * 100;
        // Once the front of the vehicle has crossed the stop line, a newly
        // changed yellow/red indication must not make it stop inside the junction.
        double passTolerance = .05 / Math.max(1, routeDistanceMeters) * 100;
        Signal next = signals.stream()
                .filter(signal -> signal != null && signal.updatedAt() != null)
                .filter(signal -> !signal.updatedAt().isBefore(now.minus(freshness)))
                .filter(signal -> signal.routeProgress() + passTolerance >= current)
                .filter(signal -> stopReason(signal) != null)
                .min(Comparator.comparingDouble(Signal::routeProgress))
                .orElse(null);
        if (next == null) return Decision.proceed(proposed);

        double stopAt = Math.max(0, next.routeProgress() - bufferPercent);
        if (proposed + 1e-9 < stopAt) return Decision.proceed(proposed);
        return new Decision(Math.min(proposed, Math.max(current, stopAt)), true, next, stopReason(next));
    }

    static String stopReason(Signal signal) {
        if (!signal.laneAllowsMovement()) return "LANE_MOVEMENT_PROHIBITED";
        if (signal.movement() == Movement.U_TURN && !signal.uTurnAllowed()) return "U_TURN_PROHIBITED";
        if (signal.intersectionBlocked()) return "INTERSECTION_BLOCKED";

        boolean turning = signal.movement() != Movement.STRAIGHT;
        if (turning && signal.pedestrianConflict()) return "YIELD_TO_PEDESTRIAN";
        if (turning && signal.conflictingTraffic()) return "YIELD_TO_PRIORITY_TRAFFIC";

        String status = String.valueOf(signal.status()).toUpperCase();
        if ("YELLOW".equals(status)) return "YELLOW_BEFORE_STOP_LINE";
        if (!"RED".equals(status)) return null;

        // A circular red permits a right turn only when no sign/arrow prohibits
        // it and the turn will not impede released vehicles or pedestrians.
        if (signal.movement() == Movement.RIGHT
                && signal.signalType() == SignalType.CIRCULAR
                && !signal.noTurnOnRed()) return null;
        return signal.signalType() == SignalType.DIRECTIONAL
                ? "RED_DIRECTIONAL_SIGNAL" : "RED_SIGNAL";
    }
}
