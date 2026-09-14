package com.skyfleet.logistics;

import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class DemoSession {
    public final String id;
    public final String visitorHash;
    public final String definitionId;
    public final String definitionVersion;
    public final String taskInstanceId;
    public final int runNo;
    public final String engineVersion;
    public final String groundAssetId;
    public final String airAssetId;
    public final Instant createdAt;
    public volatile String status;
    public volatile double timeScale = 1.0;
    public volatile double progress;
    public volatile String missionPhase = "DOCKED";
    public volatile int queuePosition;
    public volatile Instant startedAt;
    public volatile Instant lastSeenAt = Instant.now();
    public volatile long simulationElapsedMs;
    public volatile long groundServiceElapsedMs;
    public volatile int timelineEpoch;
    public volatile double groundBatteryPercent = 100;
    public volatile boolean groundBatteryDepleted;
    public volatile double airBatteryPercent = 100;
    public volatile boolean airBatteryDepleted;
    public volatile String terminalReason;
    public volatile String planningMode = "BASIC";
    public volatile String baselineRouteCandidateId;
    public volatile GroundRoutingState groundRouting;
    public volatile PaceState paceState;
    public volatile long latestGroundCommandSequence;
    public volatile boolean groundRouteRequestInFlight;
    public volatile GroundRouteCommand pendingGroundRouteCommand;
    public volatile long lastGroundRouteRequestAtMs;
    public volatile double groundRouteRequestTokens = 3;
    public volatile long groundRouteTokenRefillAtMs = System.currentTimeMillis();
    public final Map<String, Object> tutorialEvidence = new ConcurrentHashMap<>();
    /** True while the visitor's current air deployment is a self-routed VTOL. */
    public volatile boolean independentAirRoute;
    public volatile Map<String, Object> plan;
    public final Map<String, List<Map<String, Object>>> tracks = new ConcurrentHashMap<>();
    public final Map<String, Map<String, Object>> devices = new ConcurrentHashMap<>();
    public final Map<String, Double> routeProgress = new ConcurrentHashMap<>();
    public final Map<String, String> uavStates = new ConcurrentHashMap<>();
    public final Map<String, Map<String, Object>> trafficStops = new ConcurrentHashMap<>();
    public final Set<String> triggeredEvents = ConcurrentHashMap.newKeySet();
    public final Map<String, DemoSignal> signals = new ConcurrentHashMap<>();
    /** Runtime-only route substitutions; the frozen TaskInstance plan is never overwritten. */
    public final Map<String, List<List<Number>>> airRouteOverrides = new ConcurrentHashMap<>();
    public final Map<String, Map<String, Object>> airspaceActions = new ConcurrentHashMap<>();
    public final Map<String, Long> uavWaitUntilMs = new ConcurrentHashMap<>();
    /** WAIT_UNTIL_CLEAR suppresses a temporary zone until the UAV passes it, followed by a short grace period. */
    public final Map<String, Long> temporaryAirspaceClearanceUntilMs = new ConcurrentHashMap<>();
    public final Set<String> collectedDeliveryPointIds = ConcurrentHashMap.newKeySet();
    public final Set<String> collectedDiamondIds = ConcurrentHashMap.newKeySet();
    public final Set<String> forfeitedDiamondIds = ConcurrentHashMap.newKeySet();
    public final Set<String> airspaceWarningVolumeIds = ConcurrentHashMap.newKeySet();
    public final Map<String, Double> airEnergyMultipliers = new ConcurrentHashMap<>();
    public final Map<String, String> airRiskVolumeIds = new ConcurrentHashMap<>();
    public final Map<String, Double> airExtraBatteryUsedPercent = new ConcurrentHashMap<>();
    public final Map<String, double[]> previousActorPositions = new ConcurrentHashMap<>();
    public final Map<String, AirspaceIncursion> activeAirspaceIncursions = new ConcurrentHashMap<>();
    public final Map<String, Integer> airspaceIncursionSequences = new ConcurrentHashMap<>();
    public final Map<String, RewindCheckpoint> rewindCheckpoints = new ConcurrentHashMap<>();
    public final AtomicLong revision = new AtomicLong();
    private final Deque<DeltaEvent> deltaEvents = new ArrayDeque<>();
    private static final int MAX_DELTA_EVENTS = 512;

    public DemoSession(String id, String visitorHash, String status, String definitionId, String definitionVersion) {
        this(id, visitorHash, status, definitionId, definitionVersion, null, 1, "demo-simulator/2.0.0", null, Instant.now());
    }

    public DemoSession(String id, String visitorHash, String status, String definitionId, String definitionVersion, Instant createdAt) {
        this(id, visitorHash, status, definitionId, definitionVersion, null, 1, "demo-simulator/2.0.0", null, createdAt);
    }

    public DemoSession(String id, String visitorHash, String status, String definitionId, String definitionVersion,
                       String taskInstanceId, int runNo, String engineVersion, Map<String, Object> plan, Instant createdAt) {
        this.id = id;
        this.visitorHash = visitorHash;
        this.status = status;
        this.definitionId = definitionId;
        this.definitionVersion = definitionVersion;
        this.taskInstanceId = taskInstanceId;
        this.runNo = runNo;
        this.engineVersion = engineVersion;
        this.plan = plan;
        Map<?, ?> groundVehicle = plan != null && plan.get("groundVehicle") instanceof Map<?, ?> value ? value : Map.of();
        this.groundAssetId = groundVehicle.get("assetId") == null ? null : String.valueOf(groundVehicle.get("assetId"));
        if (groundVehicle.get("batteryPercent") instanceof Number battery) this.groundBatteryPercent = battery.doubleValue();
        Map<?, ?> airVehicle = plan != null && plan.get("airVehicle") instanceof Map<?, ?> value ? value : Map.of();
        this.airAssetId = airVehicle.get("assetId") == null ? null : String.valueOf(airVehicle.get("assetId"));
        if (airVehicle.get("batteryPercent") instanceof Number battery) this.airBatteryPercent = battery.doubleValue();
        this.independentAirRoute = Boolean.TRUE.equals(airVehicle.get("independentRoute"));
        if (plan != null) {
            this.planningMode = String.valueOf(plan.getOrDefault("planningMode", "BASIC"));
            Object selected = plan.get("selectedBaselineRouteCandidateId");
            this.baselineRouteCandidateId = selected == null ? null : String.valueOf(selected);
        }
        this.createdAt = createdAt;
    }

    public Map<String, Object> publicView() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("definitionId", definitionId);
        value.put("definitionVersion", definitionVersion);
        if (taskInstanceId != null) value.put("taskInstanceId", taskInstanceId);
        value.put("runNo", runNo);
        value.put("engineVersion", engineVersion);
        value.put("simulationElapsedMs", simulationElapsedMs);
        value.put("groundServiceElapsedMs", groundServiceElapsedMs);
        value.put("timelineEpoch", timelineEpoch);
        value.put("paused", "RUNNING".equals(status) && timeScale == 0);
        if (groundAssetId != null) value.put("groundAssetId", groundAssetId);
        if (airAssetId != null) value.put("airAssetId", airAssetId);
        value.put("planningMode", planningMode);
        if (baselineRouteCandidateId != null) value.put("baselineRouteCandidateId", baselineRouteCandidateId);
        value.put("status", status);
        value.put("timeScale", timeScale);
        value.put("progress", progress);
        value.put("missionPhase", missionPhase);
        if (terminalReason != null) value.put("terminalReason", terminalReason);
        if (queuePosition > 0) value.put("queuePosition", queuePosition);
        value.put("createdAt", createdAt.toString());
        if (startedAt != null) value.put("startedAt", startedAt.toString());
        return value;
    }

    public List<Map<String, Object>> deviceList() {
        return new ArrayList<>(devices.values());
    }

    public synchronized void recordDelta(long eventRevision, String eventType, Map<String, Object> data) {
        deltaEvents.addLast(new DeltaEvent(eventRevision, eventType, new LinkedHashMap<>(data)));
        while (deltaEvents.size() > MAX_DELTA_EVENTS) deltaEvents.removeFirst();
    }

    /** Returns null when the requested revision cannot be resumed safely. */
    public synchronized List<DeltaEvent> deltasAfter(long lastEventRevision) {
        long current = revision.get();
        if (lastEventRevision < 0 || lastEventRevision > current) return null;
        if (lastEventRevision == current) return List.of();
        if (deltaEvents.isEmpty() || deltaEvents.peekFirst().revision() > lastEventRevision + 1) return null;
        List<DeltaEvent> result = deltaEvents.stream().filter(event -> event.revision() > lastEventRevision).toList();
        if (result.isEmpty() || result.get(0).revision() != lastEventRevision + 1 || result.get(result.size() - 1).revision() != current) return null;
        for (int index = 1; index < result.size(); index++) {
            if (result.get(index).revision() != result.get(index - 1).revision() + 1) return null;
        }
        return result;
    }

    public record DeltaEvent(long revision, String eventType, Map<String, Object> data) {}

    public static final class GroundRoutingState {
        public final String actorId;
        public final List<List<Number>> baselinePoints;
        public volatile List<List<Number>> activeRemainingPoints;
        public volatile double distanceAlongActiveMeters;
        public volatile double cumulativeActualMeters;
        public volatile int routeVersion = 1;
        public volatile int nextMandatoryNodeIndex;
        public final List<List<Number>> mandatoryNodes;
        public volatile Map<String, Object> activeTemporaryTarget;
        public volatile String routeStatus = "ACTIVE";
        public volatile String routeMessage = "沿计划路线行驶";

        public GroundRoutingState(String actorId, List<List<Number>> baselinePoints,
                                  List<List<Number>> activeRemainingPoints, List<List<Number>> mandatoryNodes) {
            this.actorId = actorId;
            this.baselinePoints = new ArrayList<>(baselinePoints);
            this.activeRemainingPoints = new ArrayList<>(activeRemainingPoints);
            this.mandatoryNodes = new ArrayList<>(mandatoryNodes);
        }
    }

    public static final class PaceState {
        public final String actorId;
        public final List<List<Number>> routePoints;
        public final double startDelaySeconds;
        public final double deadlineSeconds;
        public final double speedMetersPerSecond;
        public volatile double distanceAlongRouteMeters;

        public PaceState(String actorId, List<List<Number>> routePoints, double startDelaySeconds,
                         double deadlineSeconds, double speedMetersPerSecond) {
            this.actorId = actorId; this.routePoints = new ArrayList<>(routePoints);
            this.startDelaySeconds = startDelaySeconds; this.deadlineSeconds = deadlineSeconds;
            this.speedMetersPerSecond = speedMetersPerSecond;
        }
    }

    public record GroundRouteCommand(String id, long sequence, String type, String sourceType,
                                     String targetId, List<Number> requestedPosition) {}

    public static final class RewindCheckpoint {
        public final String id;
        public final String volumeId;
        public final int timelineEpoch;
        public final long simulationTimeMs;
        public final double missionProgress;
        public final Instant createdAt;
        public final Map<String, Object> state;
        public volatile String status;
        public volatile Instant usedAt;
        public volatile String rewindId;

        public RewindCheckpoint(String id, String volumeId, int timelineEpoch, long simulationTimeMs,
                                double missionProgress, Instant createdAt, Map<String, Object> state,
                                String status, Instant usedAt, String rewindId) {
            this.id = id; this.volumeId = volumeId; this.timelineEpoch = timelineEpoch;
            this.simulationTimeMs = simulationTimeMs; this.missionProgress = missionProgress;
            this.createdAt = createdAt; this.state = state; this.status = status;
            this.usedAt = usedAt; this.rewindId = rewindId;
        }

        public Map<String, Object> publicView() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", id); value.put("volumeId", volumeId); value.put("timelineEpoch", timelineEpoch);
            value.put("simulationTimeMs", simulationTimeMs); value.put("progress", missionProgress);
            value.put("status", status); value.put("available", "AVAILABLE".equals(status));
            value.put("createdAt", createdAt.toString());
            if (usedAt != null) value.put("usedAt", usedAt.toString());
            return value;
        }
    }

    public static final class AirspaceIncursion {
        public final String id;
        public final String volumeId;
        public final String actorId;
        public final int sequence;
        public final long entrySimulationMs;
        public volatile long exposureMs;

        public AirspaceIncursion(String id, String volumeId, String actorId, int sequence,
                                 long entrySimulationMs, long exposureMs) {
            this.id = id; this.volumeId = volumeId; this.actorId = actorId; this.sequence = sequence;
            this.entrySimulationMs = entrySimulationMs; this.exposureMs = exposureMs;
        }
    }
}
