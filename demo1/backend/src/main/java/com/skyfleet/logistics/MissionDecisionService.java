package com.skyfleet.logistics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class MissionDecisionService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public MissionDecisionService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public Map<String, Object> create(String decisionId, DemoSession session, DemoSignal signal, String advisoryId,
                                      String typeValue, String optionIdValue, String expectedStatusValue) {
        String id = normalizeId(decisionId, "decisionId");
        String normalizedAdvisoryId = normalizeId(advisoryId, "advisoryId");
        String type = normalizeType(typeValue);
        String optionId = normalizeId(optionIdValue, "optionId");
        String expectedStatus = String.valueOf(expectedStatusValue == null ? "" : expectedStatusValue).trim().toUpperCase(Locale.ROOT);
        if (expectedStatus.isBlank()) throw new IllegalArgumentException("expectedAdvisoryStatus 不能为空");

        Map<String, Object> priorById = existingById(id);
        if (priorById != null) {
            if (!sameRequest(priorById, session.id, signal.id, normalizedAdvisoryId, type, optionId, expectedStatus))
                throw new IllegalStateException("decisionId 已被不同内容使用");
            return priorById;
        }

        Map<String, Object> advisory = advisory(normalizedAdvisoryId, session.id, signal.id);
        String advisoryStatus = String.valueOf(advisory.get("status")).toUpperCase(Locale.ROOT);
        if (!expectedStatus.equals(advisoryStatus)) throw new IllegalStateException("Advisory 状态已变化，请刷新后重试");
        Map<String, Object> option = findOption(advisory, optionId);
        if (option == null) throw new IllegalArgumentException("optionId 不属于当前 Advisory");
        if ("APPROVE".equals(type) && !Boolean.TRUE.equals(option.get("eligible")))
            throw new IllegalStateException("不能批准未通过安全门禁的方案");

        Instant now = Instant.now();
        String status = "APPROVE".equals(type) ? "PLAN_APPROVED" : "REJECTED";
        String message = "APPROVE".equals(type)
                ? "任务方案已批准；真实设备命令链路未接入，执行仍被阻断"
                : "任务方案已拒绝；未创建任何设备命令";
        int inserted = jdbc.update("INSERT INTO demo_ai_decision(id,advisory_id,session_id,signal_id,option_id,decision_type,expected_advisory_status,advisory_status,status,execution_status,timeline_epoch,requested_at,acknowledged_at,message) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
                id, normalizedAdvisoryId, session.id, signal.id, optionId, type, expectedStatus, advisoryStatus,
                status, "BLOCKED", session.timelineEpoch, Timestamp.from(now), Timestamp.from(now), message);
        if (inserted != 1) {
            Map<String, Object> concurrentById = existingById(id);
            if (concurrentById != null && sameRequest(concurrentById, session.id, signal.id, normalizedAdvisoryId, type, optionId, expectedStatus))
                return concurrentById;
            throw new IllegalStateException("当前 Advisory 已有最终人工决策");
        }
        return view(id, normalizedAdvisoryId, session.id, signal.id, optionId, type, expectedStatus,
                advisoryStatus, status, "BLOCKED", now, now, message);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> advisory(String advisoryId, String sessionId, String signalId) {
        List<Map<String, Object>> rows = jdbc.query("SELECT response_json FROM demo_ai_advisory WHERE id=? AND session_id=? AND signal_id=? AND superseded_by_rewind_id IS NULL",
                (result, row) -> {
                    try { return mapper.readValue(result.getString("response_json"), new TypeReference<Map<String, Object>>() {}); }
                    catch (Exception error) { throw new IllegalStateException("Advisory 审计记录无法读取", error); }
                }, advisoryId, sessionId, signalId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Advisory 不存在或不属于当前 Signal");
        return rows.get(0);
    }

    private static Map<String, Object> findOption(Map<String, Object> advisory, String optionId) {
        Object recommendationValue = advisory.get("recommendation");
        if (!(recommendationValue instanceof Map<?, ?> recommendation)) return null;
        Object optionsValue = recommendation.get("options");
        if (!(optionsValue instanceof List<?>)) optionsValue = recommendation.get("candidates");
        if (!(optionsValue instanceof List<?> options)) return null;
        for (Object value : options) {
            if (value instanceof Map<?, ?> option && optionId.equals(String.valueOf(option.get("id")))) {
                Map<String, Object> copy = new LinkedHashMap<>();
                option.forEach((key, item) -> copy.put(String.valueOf(key), item));
                return copy;
            }
        }
        return null;
    }

    private Map<String, Object> existingById(String id) {
        List<Map<String, Object>> rows = jdbc.query("SELECT * FROM demo_ai_decision WHERE id=? AND superseded_by_rewind_id IS NULL",
                (result, row) -> view(result.getString("id"), result.getString("advisory_id"), result.getString("session_id"),
                        result.getString("signal_id"), result.getString("option_id"), result.getString("decision_type"),
                        result.getString("expected_advisory_status"), result.getString("advisory_status"), result.getString("status"),
                        result.getString("execution_status"), result.getTimestamp("requested_at").toInstant(),
                        result.getTimestamp("acknowledged_at").toInstant(), result.getString("message")), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static boolean sameRequest(Map<String, Object> value, String sessionId, String signalId, String advisoryId,
                                       String type, String optionId, String expectedStatus) {
        return sessionId.equals(value.get("sessionId")) && signalId.equals(value.get("signalId"))
                && advisoryId.equals(value.get("advisoryId")) && type.equals(value.get("type"))
                && optionId.equals(value.get("optionId")) && expectedStatus.equals(value.get("expectedAdvisoryStatus"));
    }

    public static String normalizeType(String value) {
        String type = String.valueOf(value == null ? "" : value).trim().toUpperCase(Locale.ROOT);
        if (!List.of("APPROVE", "REJECT").contains(type)) throw new IllegalArgumentException("type 仅支持 APPROVE、REJECT");
        return type;
    }

    private static String normalizeId(String value, String field) {
        String id = String.valueOf(value == null ? "" : value).trim();
        if (!id.matches("[A-Za-z0-9._:-]{1,80}")) throw new IllegalArgumentException(field + " 格式无效");
        return id;
    }

    static Map<String, Object> view(String id, String advisoryId, String sessionId, String signalId, String optionId,
                                    String type, String expectedStatus, String advisoryStatus, String status,
                                    String executionStatus, Instant requestedAt, Instant acknowledgedAt, String message) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id); value.put("kind", "HUMAN_DECISION"); value.put("advisoryId", advisoryId);
        value.put("sessionId", sessionId); value.put("signalId", signalId); value.put("optionId", optionId);
        value.put("type", type); value.put("expectedAdvisoryStatus", expectedStatus); value.put("advisoryStatus", advisoryStatus);
        value.put("status", status); value.put("executionStatus", executionStatus);
        value.put("requestedAt", requestedAt.toString()); value.put("acknowledgedAt", acknowledgedAt.toString()); value.put("message", message);
        return value;
    }
}
