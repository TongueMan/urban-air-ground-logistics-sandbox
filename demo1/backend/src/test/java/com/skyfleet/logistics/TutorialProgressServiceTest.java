package com.skyfleet.logistics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TutorialProgressServiceTest {
    private JdbcTemplate jdbc;
    private TutorialProgressService service;
    private String visitor;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:tutorial-progress-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE demo_tutorial_progress (visitor_hash VARCHAR(64) NOT NULL, tutorial_id VARCHAR(64) NOT NULL, "
                + "tutorial_version VARCHAR(16) NOT NULL, status VARCHAR(16) NOT NULL, source VARCHAR(24) NOT NULL, "
                + "task_instance_id VARCHAR(40), run_id VARCHAR(40), evidence_json CLOB, completed_at TIMESTAMP, "
                + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(visitor_hash,tutorial_id,tutorial_version))");
        service = new TutorialProgressService(jdbc, new ObjectMapper(),
                new AdvancedRoutingProperties(false, true, true, 300, 600, 2, 3, 40, 1500, 1.6, 18));
        visitor = "visitor-" + UUID.randomUUID();
    }

    @Test
    void skippedEarlierChaptersUnlockInSequence() {
        insert(TutorialProgressService.PROLOGUE_ID, TutorialProgressService.PROLOGUE_VERSION, "SKIPPED");
        assertThat(item(TutorialProgressService.FLEET_ID).get("availability")).isEqualTo("AVAILABLE");
        assertThat(item(TutorialProgressService.GROUND_COOP_ID).get("availability")).isEqualTo("LOCKED");

        insert(TutorialProgressService.FLEET_ID, TutorialProgressService.FLEET_VERSION, "SKIPPED");
        assertThat(item(TutorialProgressService.GROUND_COOP_ID).get("availability")).isEqualTo("AVAILABLE");
    }

    @Test
    void existingGroundProgressPreservesLegacyAccess() {
        insert(TutorialProgressService.GROUND_COOP_ID, TutorialProgressService.GROUND_COOP_VERSION, "IN_PROGRESS");
        assertThat(item(TutorialProgressService.GROUND_COOP_ID).get("availability")).isEqualTo("AVAILABLE");
        assertThat(service.groundCoopAvailable(visitor)).isTrue();
    }

    @Test
    void clientMaySkipGroundTutorialButCannotForgeCompletion() {
        insert(TutorialProgressService.FLEET_ID, TutorialProgressService.FLEET_VERSION, "COMPLETED");
        Map<String, Object> skipped = service.syncClientProgress(visitor, TutorialProgressService.GROUND_COOP_ID,
                TutorialProgressService.GROUND_COOP_VERSION, "SKIPPED");
        assertThat(item(skipped, TutorialProgressService.GROUND_COOP_ID).get("status")).isEqualTo("SKIPPED");
        assertThat(((Map<?, ?>) skipped.get("capabilities")).get("advancedGroundRoutingUnlocked")).isEqualTo(true);
        assertThat(service.advancedUnlocked(visitor)).isTrue();

        assertThatThrownBy(() -> service.syncClientProgress(visitor, TutorialProgressService.GROUND_COOP_ID,
                TutorialProgressService.GROUND_COOP_VERSION, "COMPLETED"))
                .isInstanceOf(DemoException.class)
                .hasMessageContaining("运行证据");
    }

    @Test
    void allSevenServerEvidenceItemsAreRequiredForCompletion() {
        Map<String, Object> evidence = new LinkedHashMap<>();
        for (String key : List.of("baselineSelected", "taskStarted", "routeOutsideReward", "returnedToBaseline",
                "uavTakeoff", "uavRecovered")) evidence.put(key, true);
        service.recordGroundCoopEvidence(visitor, "TASK-1", "RUN-1", evidence, true);
        assertThat(item(TutorialProgressService.GROUND_COOP_ID).get("status")).isEqualTo("IN_PROGRESS");

        evidence.put("missionCompleted", true);
        service.recordGroundCoopEvidence(visitor, "TASK-1", "RUN-1", evidence, true);
        Map<String, Object> complete = item(TutorialProgressService.GROUND_COOP_ID);
        assertThat(complete.get("status")).isEqualTo("COMPLETED");
        assertThat(complete.get("runId")).isEqualTo("RUN-1");
        assertThat(castMap(complete.get("evidence"))).containsKeys("baselineSelected", "missionCompleted");
        assertThat(service.advancedUnlocked(visitor)).isTrue();
    }

    private void insert(String tutorialId, String version, String status) {
        jdbc.update("INSERT INTO demo_tutorial_progress(visitor_hash,tutorial_id,tutorial_version,status,source,evidence_json) VALUES(?,?,?,?,?,?)",
                visitor, tutorialId, version, status, "TEST", "{}");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> item(String tutorialId) {
        return item(service.view(visitor), tutorialId);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> item(Map<String, Object> view, String tutorialId) {
        return ((List<Map<String, Object>>) view.get("items")).stream()
                .filter(value -> tutorialId.equals(value.get("tutorialId"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
