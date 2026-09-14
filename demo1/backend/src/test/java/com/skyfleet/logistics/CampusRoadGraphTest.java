package com.skyfleet.logistics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CampusRoadGraphTest {
    @Test
    void routesViaLatestTargetAndPreservesMandatoryNodeOrder() {
        Map<String, Object> west = Map.of("points", List.of(
                List.of(117.2100, 31.7800, .35), List.of(117.2100, 31.7780, .35),
                List.of(117.2100, 31.7760, .35), List.of(117.2100, 31.7740, .35)));
        Map<String, Object> east = Map.of("points", List.of(
                List.of(117.2100, 31.7800, .35), List.of(117.2120, 31.7780, .35),
                List.of(117.2100, 31.7760, .35)));
        CampusRoadGraph graph = new CampusRoadGraph(List.of(west, east));

        CampusRoadGraph.Path path = graph.route(List.of(117.2100, 31.7800, .35),
                List.of(117.2120, 31.7780, .35),
                List.of(List.of(117.2100, 31.7760, .35), List.of(117.2100, 31.7740, .35)), 40);

        assertNotNull(path);
        assertTrue(path.distanceMeters() > 0);
        assertArrayEquals(new double[]{117.2120, 31.7780, .35}, path.snappedTarget(), 1e-6);
        assertArrayEquals(new double[]{117.2100, 31.7740, .35}, path.points().get(path.points().size() - 1), 1e-6);
    }

    @Test
    void rejectsPointsOutsideTheConfiguredRoadSnapRadius() {
        CampusRoadGraph graph = new CampusRoadGraph(List.of(Map.of("points", List.of(
                List.of(117.2100, 31.7800, .35), List.of(117.2100, 31.7780, .35)))));
        assertNull(graph.route(List.of(117.2100, 31.7800, .35),
                List.of(117.2300, 31.7900, .35), List.of(), 40));
    }

    @Test
    void returnToBaselineKeepsTheSelectedBaselineSuffix() {
        List<List<Number>> selectedBaseline = List.of(
                List.of(117.2100, 31.7800, .35),
                List.of(117.2100, 31.7790, .35),
                List.of(117.2090, 31.7780, .35),
                List.of(117.2100, 31.7770, .35),
                List.of(117.2095, 31.7760, .35),
                List.of(117.2100, 31.7750, .35));
        List<List<Number>> otherCandidate = List.of(
                List.of(117.2100, 31.7800, .35),
                List.of(117.2100, 31.7790, .35),
                List.of(117.2110, 31.7780, .35),
                List.of(117.2100, 31.7770, .35),
                List.of(117.2101, 31.7760, .35),
                List.of(117.2100, 31.7750, .35));
        CampusRoadGraph graph = new CampusRoadGraph(List.of(
                Map.of("points", selectedBaseline), Map.of("points", otherCandidate)));

        CampusRoadGraph.Path path = graph.returnToBaseline(
                List.of(117.2110, 31.7780, .35), selectedBaseline,
                List.of(117.2100, 31.7790, .35), List.of(117.2100, 31.7770, .35), 40);

        assertNotNull(path);
        assertTrue(path.points().stream().anyMatch(point ->
                MissionMath.distance(point, new double[]{117.2095, 31.7760, .35}) < .2),
                "the selected baseline suffix must remain in the returned route");
        assertFalse(path.points().stream().anyMatch(point ->
                MissionMath.distance(point, new double[]{117.2101, 31.7760, .35}) < .2),
                "returning must not switch to another candidate after rejoining");
        assertArrayEquals(new double[]{117.2100, 31.7750, .35},
                path.points().get(path.points().size() - 1), 1e-6);
    }
}
