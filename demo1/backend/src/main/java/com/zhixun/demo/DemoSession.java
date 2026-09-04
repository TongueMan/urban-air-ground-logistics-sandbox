package com.zhixun.demo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class DemoSession {
    public final String id;
    public final String visitorHash;
    public final Instant createdAt;
    public volatile String status;
    public volatile double timeScale = 1.0;
    public volatile double progress;
    public volatile String missionPhase = "DOCKED";
    public volatile int queuePosition;
    public volatile Instant startedAt;
    public volatile Instant lastSeenAt = Instant.now();
    public final Map<String, List<Map<String, Object>>> tracks = new ConcurrentHashMap<>();
    public final Map<String, Map<String, Object>> devices = new ConcurrentHashMap<>();
    public final Set<String> triggeredEvents = ConcurrentHashMap.newKeySet();

    public DemoSession(String id, String visitorHash, String status) {
        this(id, visitorHash, status, Instant.now());
    }

    public DemoSession(String id, String visitorHash, String status, Instant createdAt) {
        this.id = id;
        this.visitorHash = visitorHash;
        this.status = status;
        this.createdAt = createdAt;
    }

    public Map<String, Object> publicView() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("status", status);
        value.put("timeScale", timeScale);
        value.put("progress", progress);
        value.put("missionPhase", missionPhase);
        if (queuePosition > 0) value.put("queuePosition", queuePosition);
        value.put("createdAt", createdAt.toString());
        if (startedAt != null) value.put("startedAt", startedAt.toString());
        return value;
    }

    public List<Map<String, Object>> deviceList() {
        return new ArrayList<>(devices.values());
    }
}
