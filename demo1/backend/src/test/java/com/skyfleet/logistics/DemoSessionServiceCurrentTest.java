package com.skyfleet.logistics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DemoSessionServiceCurrentTest {
    @Test
    @SuppressWarnings("unchecked")
    void completedRunIsHistoryAndDoesNotBecomeTheCurrentWorkspace() {
        MissionCatalog catalog = mock(MissionCatalog.class);
        TaskTrafficEngine traffic = mock(TaskTrafficEngine.class);
        when(catalog.standbySnapshot()).thenReturn(new LinkedHashMap<>(Map.of("status", "STANDBY")));
        when(traffic.lights(any(), anyLong())).thenReturn(List.of());
        when(traffic.status(any())).thenReturn(Map.of("state", "EMPTY"));

        DemoSessionService service = new DemoSessionService(
                catalog, mock(JdbcTemplate.class), new ObjectMapper(), mock(MqttBridge.class), traffic,
                mock(TaskInstanceService.class), mock(MissionAdvisoryService.class),
                mock(MissionDecisionService.class), mock(FleetService.class), 12, 100, 180, 75);
        Map<String, DemoSession> sessions = (Map<String, DemoSession>) ReflectionTestUtils.getField(service, "sessions");
        sessions.put("DONE", new DemoSession("DONE", "visitor", "COMPLETED", "mission", "1.0.0"));

        Map<String, Object> current = service.current("visitor");

        assertThat(current.get("session")).isNull();
        assertThat(current.get("devices")).isEqualTo(List.of());
        assertThat(((Map<?, ?>) current.get("mission")).get("status")).isEqualTo("STANDBY");
    }
}
