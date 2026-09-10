package com.skyfleet.logistics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MissionDecisionServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MissionDecisionService service = new MissionDecisionService(jdbc, new ObjectMapper());
    private final DemoSession session = new DemoSession("SESSION-1", "visitor", "RUNNING", "mission", "1.0.0");
    private final DemoSignal signal = new DemoSignal("SIGNAL-1", "road", "ROAD_OBSTACLE", "WARNING", "DETECTED",
            "DELIVERING", "UAV-A", 55, Instant.now(), Instant.now(), Map.of());

    @Test
    @SuppressWarnings("unchecked")
    void persistsPlanApprovalAsExecutionBlocked() {
        when(jdbc.query(startsWith("SELECT * FROM demo_ai_decision"), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        when(jdbc.query(startsWith("SELECT response_json FROM demo_ai_advisory"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(advisory(true)));
        when(jdbc.update(startsWith("INSERT INTO demo_ai_decision"), any(Object[].class))).thenReturn(1);

        Map<String, Object> result = service.create("DEC-1", session, signal, "ADV-1", "APPROVE", "OPTION-UAV-A", "GENERATED");
        assertEquals("PLAN_APPROVED", result.get("status"));
        assertEquals("BLOCKED", result.get("executionStatus"));
        assertTrue(String.valueOf(result.get("message")).contains("阻断"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsSameDecisionIdempotentlyAndRejectsChangedContent() {
        Map<String, Object> existing = MissionDecisionService.view("DEC-1", "ADV-1", "SESSION-1", "SIGNAL-1", "OPTION-UAV-A",
                "APPROVE", "GENERATED", "GENERATED", "PLAN_APPROVED", "BLOCKED", Instant.now(), Instant.now(), "已批准");
        when(jdbc.query(startsWith("SELECT * FROM demo_ai_decision"), any(RowMapper.class), any(Object[].class))).thenReturn(List.of(existing));

        assertSame(existing, service.create("DEC-1", session, signal, "ADV-1", "APPROVE", "OPTION-UAV-A", "GENERATED"));
        assertThrows(IllegalStateException.class,
                () -> service.create("DEC-1", session, signal, "ADV-1", "REJECT", "OPTION-UAV-A", "GENERATED"));
        verify(jdbc, never()).update(startsWith("INSERT INTO demo_ai_decision"), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void blocksApprovalOfIneligibleOptionAndUnknownTypes() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create("DEC-1", session, signal, "ADV-1", "EXECUTE", "OPTION-UAV-A", "GENERATED"));
        when(jdbc.query(startsWith("SELECT * FROM demo_ai_decision"), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        when(jdbc.query(startsWith("SELECT response_json FROM demo_ai_advisory"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(advisory(false)));
        assertThrows(IllegalStateException.class,
                () -> service.create("DEC-2", session, signal, "ADV-1", "APPROVE", "OPTION-UAV-A", "GENERATED"));
    }

    private static Map<String, Object> advisory(boolean eligible) {
        return Map.of("status", "GENERATED", "recommendation", Map.of("options", List.of(
                Map.of("id", "OPTION-UAV-A", "eligible", eligible))));
    }
}
