package com.skyfleet.logistics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MissionCatalogTest {
    @Test
    void standbySnapshotContainsNoAuthoredTaskArtifacts() throws Exception {
        MissionCatalog catalog = new MissionCatalog(new ObjectMapper(), mock(JdbcTemplate.class));
        catalog.load();

        Map<String, Object> standby = catalog.standbySnapshot();

        assertThat(standby.get("status")).isEqualTo("STANDBY");
        for (String field : List.of("actors", "formations", "assignments", "pairs", "routes", "events")) {
            assertThat(standby.get(field)).as(field).isEqualTo(List.of());
        }
        assertThat(standby.get("trafficLights")).isEqualTo(List.of());
        assertThat(((Map<?, ?>) standby.get("trafficLightStatus")).get("state")).isEqualTo("DISABLED");
    }
}
