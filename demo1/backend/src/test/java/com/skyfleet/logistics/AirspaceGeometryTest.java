package com.skyfleet.logistics;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AirspaceGeometryTest {
    private static Map<String, Object> volume(double floor, double ceiling, long from, Long until) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", "TNFZ-TEST"); value.put("ruleType", "TEMPORARY_NO_FLY"); value.put("blocking", true);
        value.put("footprint", List.of(List.of(.004, -.001), List.of(.006, -.001), List.of(.006, .001), List.of(.004, .001), List.of(.004, -.001)));
        value.put("floorMeters", floor); value.put("ceilingMeters", ceiling);
        value.put("activeFromSimulationMs", from); value.put("activeUntilSimulationMs", until);
        return value;
    }

    private static Map<String, Object> corridor() {
        Map<String, Object> value = volume(0, 120, 0, null);
        value.put("id", "CORRIDOR-TEST"); value.put("ruleType", "ALTITUDE_CORRIDOR");
        value.put("corridorFloorMeters", 78.0); value.put("corridorCeilingMeters", 94.0);
        value.put("targetAltitudeMeters", 86.0);
        value.put("blockedAltitudeBands", List.of(
                Map.of("floorMeters", 0, "ceilingMeters", 78),
                Map.of("floorMeters", 94, "ceilingMeters", 120)));
        return value;
    }

    @Test void conflictRequiresHorizontalAndAltitudeOverlap() {
        List<double[]> route = List.of(new double[]{0, 0, 70}, new double[]{.01, 0, 70});
        assertNotNull(AirspaceGeometry.conflict(route, volume(0, 80, 0, null), 0));
        assertNull(AirspaceGeometry.conflict(route, volume(0, 55, 0, null), 0));
    }

    @Test void lifecycleUsesSimulationTime() {
        Map<String, Object> volume = volume(0, 80, 20_000, 40_000L);
        assertFalse(AirspaceGeometry.active(volume, 19_999));
        assertTrue(AirspaceGeometry.active(volume, 20_000));
        assertFalse(AirspaceGeometry.active(volume, 40_000));
    }

    @Test void deterministicDetourClearsRectangularBlockingVolume() {
        List<double[]> route = List.of(new double[]{0, 0, 70}, new double[]{.01, 0, 70});
        List<double[]> detour = AirspaceGeometry.detour(route, volume(0, 80, 0, null));
        assertTrue(detour.size() > route.size());
        assertNull(AirspaceGeometry.conflict(detour, volume(0, 80, 0, null), 0));
    }

    @Test void runtimeDetourPreservesTheAlreadyFlownPositionWhenProgressIsRemapped() {
        List<double[]> route = List.of(new double[]{0, 0, 70}, new double[]{.003, 0, 70},
                new double[]{.007, 0, 70}, new double[]{.01, 0, 70});
        double currentProgress = 24;
        double[] beforeAction = MissionMath.sample(route, currentProgress / 100);

        List<double[]> detour = AirspaceGeometry.detour(route, volume(0, 80, 0, null), currentProgress);
        double remapped = AirspaceGeometry.closestRouteProgress(detour, beforeAction);

        assertEquals(0, MissionMath.distance(beforeAction, MissionMath.sample(detour, remapped / 100)), .05);
        assertNull(AirspaceGeometry.conflict(detour, volume(0, 80, 0, null), remapped));
    }

    @Test void northboundDetourNeverBacktracksBeforeRejoiningTheRoute() {
        List<double[]> route = List.of(new double[]{.005, -.005, 70}, new double[]{.005, .005, 70});

        List<double[]> detour = AirspaceGeometry.detour(route, volume(0, 80, 0, null), 24);

        assertTrue(detour.size() > route.size());
        for (int index = 1; index < detour.size(); index++) {
            assertTrue(detour.get(index)[1] + 1e-12 >= detour.get(index - 1)[1],
                    "northbound detour reversed between waypoints " + (index - 1) + " and " + index);
        }
        assertNull(AirspaceGeometry.conflict(detour, volume(0, 80, 0, null),
                AirspaceGeometry.closestRouteProgress(detour, MissionMath.sample(route, .24))));
    }

    @Test void detourStartedInsideTheVolumeEscapesOnceWithoutOscillating() {
        List<double[]> route = List.of(new double[]{.005, -.005, 70}, new double[]{.005, -.002, 70},
                new double[]{.005, .002, 70}, new double[]{.005, .005, 70});
        double currentProgress = 50;
        double[] currentPoint = MissionMath.sample(route, currentProgress / 100);
        assertTrue(AirspaceGeometry.contains(volume(0, 80, 0, null), currentPoint));

        List<double[]> detour = AirspaceGeometry.detour(route, volume(0, 80, 0, null), currentProgress);
        double remapped = AirspaceGeometry.closestRouteProgress(detour, currentPoint);

        assertTrue(detour.size() <= route.size() + 6, "a late action must add only one escape manoeuvre");
        assertEquals(0, MissionMath.distance(currentPoint, MissionMath.sample(detour, remapped / 100)), .05);
        for (int index = 1; index < detour.size(); index++) {
            assertTrue(detour.get(index)[1] + 1e-12 >= detour.get(index - 1)[1],
                    "late northbound detour reversed between waypoints " + (index - 1) + " and " + index);
        }

        boolean reachedClearAir = false;
        for (int index = 0; index <= 400; index++) {
            double progress = remapped + (100 - remapped) * index / 400;
            boolean inside = AirspaceGeometry.contains(volume(0, 80, 0, null),
                    MissionMath.sample(detour, progress / 100));
            if (!inside) reachedClearAir = true;
            else if (reachedClearAir) fail("late detour re-entered the volume after escaping");
        }
        assertTrue(reachedClearAir);
    }

    @Test void sweptSegmentDetectsACompleteHighSpeedCrossing() {
        Map<String, Object> restricted = volume(0, 80, 0, null);
        double[] before = new double[]{0, 0, 70};
        double[] after = new double[]{.01, 0, 70};
        assertFalse(AirspaceGeometry.contains(restricted, before));
        assertFalse(AirspaceGeometry.contains(restricted, after));
        assertTrue(AirspaceGeometry.segmentExposureFraction(before, after, restricted) > 0);
    }

    @Test void sweptDeliveryHitUsesHorizontalDistanceAndAirAltitudeTolerance() {
        double[] before = new double[]{0, 0, 70};
        double[] after = new double[]{.01, 0, 70};
        assertTrue(AirspaceGeometry.segmentPassesPoint(before, after, new double[]{.005, .00002, 72}, 10, 4, true));
        assertFalse(AirspaceGeometry.segmentPassesPoint(before, after, new double[]{.005, .00002, 90}, 10, 4, true));
        assertTrue(AirspaceGeometry.segmentPassesPoint(before, after, new double[]{.005, .00002, 0}, 10, 0, false));
    }

    @Test void routeSectionSweepKeepsDetourCornersAtHighSimulationSpeed() {
        List<double[]> route = List.of(
                new double[]{0, 0, 70},
                new double[]{.005, 0, 70},
                new double[]{.005, .005, 70});
        double[] before = new double[]{.004, 0, 70};
        double[] after = new double[]{.005, .004, 70};
        double[] reward = new double[]{.005, .001, 70};

        assertFalse(AirspaceGeometry.segmentPassesPoint(before, after, reward, 10, 4, true));
        assertTrue(AirspaceGeometry.routeSectionPassesPoint(route, before, after, reward, 10, 4, true));
        assertFalse(AirspaceGeometry.routeSectionPassesPoint(route, before, after,
                new double[]{.005, .001, 90}, 10, 4, true));
    }

    @Test void climbOverIsLocalAndRejoinsOriginalAltitude() {
        List<double[]> route = List.of(new double[]{0, 0, 70}, new double[]{.003, 0, 70}, new double[]{.007, 0, 70}, new double[]{.01, 0, 70});
        List<double[]> climbed = AirspaceGeometry.climbOver(route, volume(0, 80, 0, null), 92);
        assertTrue(climbed.stream().anyMatch(point -> point[2] >= 92));
        assertEquals(70, climbed.get(0)[2], 1e-9);
        assertEquals(70, climbed.get(climbed.size() - 1)[2], 1e-9);
        assertNull(AirspaceGeometry.conflict(climbed, volume(0, 80, 0, null), 0));
    }

    @Test void runtimeClimbPreservesTheAlreadyFlownPositionWhenProgressIsRemapped() {
        List<double[]> route = List.of(new double[]{0, 0, 70}, new double[]{.003, 0, 70},
                new double[]{.007, 0, 70}, new double[]{.01, 0, 70});
        double currentProgress = 24;
        double[] beforeAction = MissionMath.sample(route, currentProgress / 100);

        List<double[]> climbed = AirspaceGeometry.climbOver(route, volume(0, 80, 0, null), 92, currentProgress);
        double remapped = AirspaceGeometry.closestRouteProgress(climbed, beforeAction);

        assertEquals(0, MissionMath.distance(beforeAction, MissionMath.sample(climbed, remapped / 100)), .05);
        assertNull(AirspaceGeometry.conflict(climbed, volume(0, 80, 0, null), remapped));
    }

    @Test void footprintDistanceIncludesClosingEdgeForOpenPolygons() {
        Map<String, Object> open = volume(0, 80, 0, null);
        open.put("footprint", List.of(List.of(.004, -.001), List.of(.006, -.001),
                List.of(.006, .001), List.of(.004, .001)));
        assertEquals(0, AirspaceGeometry.distanceToFootprintMeters(open, new double[]{.004, 0, 70}), .001);
    }

    @Test void altitudeCorridorBlocksBothOuterBandsButKeepsGapBoundariesLegal() {
        Map<String, Object> corridor = corridor();
        assertTrue(AirspaceGeometry.contains(corridor, new double[]{.005, 0, 70}));
        assertTrue(AirspaceGeometry.contains(corridor, new double[]{.005, 0, 102}));
        assertFalse(AirspaceGeometry.contains(corridor, new double[]{.005, 0, 78}));
        assertFalse(AirspaceGeometry.contains(corridor, new double[]{.005, 0, 86}));
        assertFalse(AirspaceGeometry.contains(corridor, new double[]{.005, 0, 94}));
    }

    @Test void transitCorridorStartsEarlyHitsTheGapAndRestoresCruiseAltitude() {
        List<double[]> route = List.of(new double[]{0, 0, 70}, new double[]{.01, 0, 70});
        Map<String, Object> corridor = corridor();
        AirspaceGeometry.Conflict originalConflict = AirspaceGeometry.conflict(route, corridor, 0);
        List<double[]> transit = AirspaceGeometry.transitCorridor(route, corridor);

        assertNotNull(originalConflict);
        assertNull(AirspaceGeometry.conflict(transit, corridor, 0));
        assertTrue(transit.stream().anyMatch(point -> Math.abs(point[2] - 86) < 1e-9));
        assertEquals(70, transit.get(0)[2], 1e-9);
        assertEquals(70, transit.get(transit.size() - 1)[2], 1e-9);
        int firstElevated = java.util.stream.IntStream.range(0, transit.size())
                .filter(index -> transit.get(index)[2] > 70).findFirst().orElseThrow();
        double climbStartProgress = AirspaceGeometry.closestRouteProgress(route, transit.get(firstElevated - 1));
        assertTrue((originalConflict.startProgress() - climbStartProgress) / 100
                * MissionMath.polylineDistance(route) >= 115);
    }

    @Test void corridorDetourClearsBothBlockedLayersAndLateTransitIsNotOffered() {
        List<double[]> route = List.of(new double[]{0, 0, 70}, new double[]{.01, 0, 70});
        Map<String, Object> corridor = corridor();
        List<double[]> detour = AirspaceGeometry.detour(route, corridor);
        assertNull(AirspaceGeometry.conflict(detour, corridor, 0));
        AirspaceGeometry.Conflict late = AirspaceGeometry.conflict(route, corridor, 39);
        Map<String, Object> view = AirspaceGeometry.view(late, corridor, 5);
        assertFalse(((List<?>) view.get("availableActions")).contains("TRANSIT_CORRIDOR"));
        assertTrue(((List<?>) view.get("availableActions")).contains("DETOUR"));
    }
}
