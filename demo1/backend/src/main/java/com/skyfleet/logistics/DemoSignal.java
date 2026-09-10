package com.skyfleet.logistics;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public final class DemoSignal {
    public final String id;
    public final String key;
    public final String type;
    public final String severity;
    public final String phaseId;
    public final String actorId;
    public final double progress;
    public final Instant detectedAt;
    public final Map<String, Object> source;
    public volatile String status;
    public volatile Instant updatedAt;
    public final List<Map<String, Object>> statusHistory = new CopyOnWriteArrayList<>();

    public DemoSignal(String id, String key, String type, String severity, String status, String phaseId,
                      String actorId, double progress, Instant detectedAt, Instant updatedAt,
                      Map<String, Object> source) {
        this.id = id;
        this.key = key;
        this.type = type;
        this.severity = severity;
        this.status = status;
        this.phaseId = phaseId;
        this.actorId = actorId;
        this.progress = progress;
        this.detectedAt = detectedAt;
        this.updatedAt = updatedAt;
        this.source = new LinkedHashMap<>(source);
        recordStatus("DETECTED", progress, detectedAt);
    }

    public void recordStatus(String nextStatus, double missionProgress, Instant at) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("status", nextStatus); entry.put("progress", missionProgress); entry.put("at", at.toString());
        statusHistory.add(entry);
    }

    public Map<String, Object> publicView(String missionId) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("key", key);
        value.put("type", type);
        value.put("label", String.valueOf(source.getOrDefault("label", type)));
        value.put("severity", severity);
        value.put("confidence", source.get("confidence"));
        value.put("status", status);
        value.put("missionId", missionId);
        value.put("phaseId", phaseId);
        value.put("actorIds", actorId == null || actorId.isBlank() ? List.of() : List.of(actorId));
        if (source.get("location") instanceof Map<?, ?> location) value.put("location", location);
        value.put("progress", progress);
        value.put("detectedAt", detectedAt.toString());
        value.put("updatedAt", updatedAt.toString());
        value.put("requiresAction", Boolean.TRUE.equals(source.get("requiresAction")) && SignalStateMachine.requiresAction(status));
        value.put("allowedActions", Boolean.TRUE.equals(source.get("requiresAction")) ? SignalStateMachine.allowedActions(status) : List.of());
        value.put("source", "MISSION_EVENT");
        value.put("statusHistory", new ArrayList<>(statusHistory));
        return value;
    }
}
