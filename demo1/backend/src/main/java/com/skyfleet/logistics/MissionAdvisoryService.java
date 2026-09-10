package com.skyfleet.logistics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MissionAdvisoryService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MissionAdvisoryEngine engine;
    private final DeepSeekAdvisoryClient deepSeek;
    private final int maxPerSession;
    private final int minIntervalSeconds;

    public MissionAdvisoryService(JdbcTemplate jdbc, ObjectMapper mapper, MissionAdvisoryEngine engine, DeepSeekAdvisoryClient deepSeek,
                                  @Value("${mission.ai.max-advisories-per-session:5}") int maxPerSession,
                                  @Value("${mission.ai.min-interval-seconds:10}") int minIntervalSeconds) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.engine = engine;
        this.deepSeek = deepSeek;
        this.maxPerSession = Math.max(1, maxPerSession);
        this.minIntervalSeconds = Math.max(0, minIntervalSeconds);
    }

    public Map<String, Object> providerStatus() {
        return Map.of("provider", deepSeek.provider(), "model", deepSeek.model(), "configured", deepSeek.configured(),
                "role", "ADVISORY_ONLY", "directDeviceControl", false);
    }

    public Map<String, Object> create(String advisoryId, DemoSession session, DemoSignal signal, String objectiveValue) {
        String normalizedId = advisoryId == null ? "" : advisoryId.trim();
        if (!normalizedId.matches("[A-Za-z0-9._:-]{1,80}")) throw new IllegalArgumentException("advisoryId 格式无效");
        String objective = MissionAdvisoryEngine.normalizeObjective(objectiveValue);
        Map<String, Object> existing = existing(normalizedId);
        if (existing != null) {
            if (!session.id.equals(existing.get("sessionId")) || !signal.id.equals(existing.get("signalId")))
                throw new IllegalStateException("advisoryId 已被其他请求使用");
            Object existingRecommendation = existing.get("recommendation");
            if (!(existingRecommendation instanceof Map<?, ?> recommendation)
                    || !objective.equals(String.valueOf(recommendation.get("objective"))))
                throw new IllegalStateException("advisoryId 已被不同 Objective 使用");
            return existing;
        }
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM demo_ai_advisory WHERE session_id=?", Integer.class, session.id);
        if (count != null && count >= maxPerSession) throw new IllegalStateException("当前任务的 AI 建议次数已达上限");
        List<Instant> recent = jdbc.query("SELECT requested_at FROM demo_ai_advisory WHERE session_id=? ORDER BY requested_at DESC LIMIT 1",
                (result, row) -> result.getTimestamp("requested_at").toInstant(), session.id);
        if (!recent.isEmpty() && recent.get(0).plusSeconds(minIntervalSeconds).isAfter(Instant.now()))
            throw new IllegalStateException("AI 建议请求过于频繁，请稍后重试");

        Instant requestedAt = Instant.now();
        Map<String, Object> recommendation = engine.recommend(signal, session.deviceList(), objective);
        Map<String, Object> inputFacts = new LinkedHashMap<>();
        inputFacts.put("mission", Map.of("id", session.id, "phase", session.missionPhase, "progress", session.progress));
        inputFacts.put("signal", Map.of("id", signal.id, "type", signal.type, "severity", signal.severity,
                "status", signal.status, "actorId", signal.actorId == null ? "" : signal.actorId));
        inputFacts.put("objective", objective);
        inputFacts.put("options", recommendation.get("options"));
        inputFacts.put("constraints", recommendation.get("constraints"));

        String status = "DETERMINISTIC_ONLY";
        String source = "DETERMINISTIC";
        Map<String, Object> narrative = Map.of(
                "summary", recommendation.get("title"),
                "rationale", recommendation.get("facts"),
                "risks", recommendation.get("constraints"),
                "observations", List.of("配置 DeepSeek AK 后可生成模型解释；当前未调用外部 AI"));
        if (deepSeek.configured()) {
            try {
                narrative = deepSeek.explain(inputFacts);
                status = "GENERATED";
                source = "DEEPSEEK";
            } catch (Exception ignored) {
                status = "DEGRADED";
                narrative = Map.of(
                        "summary", recommendation.get("title"),
                        "rationale", recommendation.get("facts"),
                        "risks", recommendation.get("constraints"),
                        "observations", List.of("DeepSeek 调用失败，已降级为确定性建议"));
            }
        }

        Instant completedAt = Instant.now();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", normalizedId); response.put("sessionId", session.id); response.put("signalId", signal.id);
        response.put("status", status); response.put("source", source); response.put("provider", deepSeek.provider()); response.put("model", deepSeek.model());
        response.put("providerConfigured", deepSeek.configured()); response.put("recommendation", recommendation); response.put("narrative", narrative);
        response.put("objective", objective);
        response.put("requiresHumanApproval", true); response.put("executable", false);
        response.put("requestedAt", requestedAt.toString()); response.put("completedAt", completedAt.toString());
        try {
            jdbc.update("INSERT INTO demo_ai_advisory(id,session_id,signal_id,provider,model_name,status,timeline_epoch,requested_at,completed_at,input_snapshot_json,response_json) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    normalizedId, session.id, signal.id, deepSeek.provider(), deepSeek.model(), status, session.timelineEpoch,
                    Timestamp.from(requestedAt), Timestamp.from(completedAt), mapper.writeValueAsString(inputFacts), mapper.writeValueAsString(response));
        } catch (Exception error) { throw new IllegalStateException("AI 建议审计记录保存失败", error); }
        return response;
    }

    private Map<String, Object> existing(String advisoryId) {
        List<Map<String, Object>> rows = jdbc.query("SELECT response_json FROM demo_ai_advisory WHERE id=? AND superseded_by_rewind_id IS NULL", (result, row) -> {
            try { return mapper.readValue(result.getString("response_json"), new TypeReference<Map<String, Object>>() {}); }
            catch (Exception error) { throw new IllegalStateException("AI 建议记录无法读取", error); }
        }, advisoryId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
