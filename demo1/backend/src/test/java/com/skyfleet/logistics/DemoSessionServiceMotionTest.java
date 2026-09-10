package com.skyfleet.logistics;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DemoSessionServiceMotionTest {
    @Test
    void everyInteractiveAirspaceRuleCanCreateAnApproachCheckpoint() {
        List<Map<String, Object>> conflicts = List.of(
                conflict("RED", "ABSOLUTE_NO_FLY", 55),
                conflict("YELLOW", "RISK_AIRSPACE", 45),
                conflict("PURPLE", "ALTITUDE_CORRIDOR", 35),
                conflict("ORANGE", "TEMPORARY_NO_FLY", 25));

        List<Map<String, Object>> candidates = DemoSessionService.rewindCheckpointCandidates(
                conflicts, Set.of(), Set.of());

        assertEquals(List.of("ORANGE", "PURPLE", "YELLOW", "RED"), candidates.stream()
                .map(value -> String.valueOf(value.get("volumeId"))).toList());
    }

    @Test
    void checkpointCandidatesWaitForTheDecisionWindowAndExcludeHandledOrRecordedVolumes() {
        List<Map<String, Object>> conflicts = List.of(
                conflict("TOO-EARLY", "ABSOLUTE_NO_FLY", 61),
                conflict("HANDLED", "RISK_AIRSPACE", 20),
                conflict("RECORDED", "ALTITUDE_CORRIDOR", 10),
                conflict("READY", "TEMPORARY_NO_FLY", 30));

        List<Map<String, Object>> candidates = DemoSessionService.rewindCheckpointCandidates(
                conflicts, Set.of("HANDLED"), Set.of("RECORDED"));

        assertEquals(List.of("READY"), candidates.stream()
                .map(value -> String.valueOf(value.get("volumeId"))).toList());
    }

    @Test
    void newlyActiveNearerZonePreemptsAPrematureCheckpointForALaterZone() {
        assertEquals(true, DemoSessionService.nearerCheckpointMustPreempt(
                conflict("STATIC-LATER", "ABSOLUTE_NO_FLY", 47),
                conflict("DYNAMIC-NEXT", "TEMPORARY_NO_FLY", 22)));
        assertEquals(false, DemoSessionService.nearerCheckpointMustPreempt(
                conflict("CURRENT-NEXT", "RISK_AIRSPACE", 18),
                conflict("LATER", "ALTITUDE_CORRIDOR", 34)));
    }

    private static Map<String, Object> conflict(String id, String ruleType, double etaSeconds) {
        return Map.of("volumeId", id, "ruleType", ruleType, "estimatedEntrySeconds", etaSeconds,
                "availableActions", List.of("DETOUR"));
    }

    @Test
    void executableRouteProgressRoundTripsThroughSortieProgress() {
        for (double progress : new double[]{0, 12.5, 50, 87.5, 100}) {
            assertEquals(progress, DemoSessionService.executableAirRouteProgress(
                    DemoSessionService.sortieProgressForAirRoute(progress)), 1e-9);
        }
    }

    @Test
    void routeProgressAdvanceKeepsNominalPhysicalSpeedOnAnyRouteLength() {
        double routeDistanceMeters = 1_000;
        double speedMetersPerSecond = 5;
        double after = DemoSessionService.advanceAirSortieProgress(5,
                speedMetersPerSecond, 10, routeDistanceMeters);
        double executableProgress = DemoSessionService.executableAirRouteProgress(after);

        assertEquals(50, routeDistanceMeters * executableProgress / 100, 1e-9);
    }

    @Test
    void fasterGroundVehiclesFinishSoonerWhileRedLightDelayIsIncluded() {
        assertEquals(220, TaskInstanceService.groundServiceSeconds(1_000, 18, 20), 1e-9);
        assertEquals(105.7142857, TaskInstanceService.groundServiceSeconds(1_000, 42, 20), 1e-6);
    }

    @Test
    void timelinessRewardIsInverseToSimulationTimeClampedAndWallClockScaleIndependent() {
        assertEquals(2.0, TaskInstanceService.timelinessFactor(100, 50), 1e-9);
        assertEquals(.5, TaskInstanceService.timelinessFactor(100, 400), 1e-9);
        assertEquals(2.5, TaskInstanceService.timelinessFactor(100, 10), 1e-9);
        long atHalfSpeedPlayback = DemoSessionService.timelinessRewardMinor(123_450, 100, 80);
        long atFiveTimesPlayback = DemoSessionService.timelinessRewardMinor(123_450, 100, 80);
        assertEquals(154_300, atHalfSpeedPlayback);
        assertEquals(atHalfSpeedPlayback, atFiveTimesPlayback);
    }

    @Test
    void diamondRequiresTheBoundActorVolumeAndAppliedAction() {
        Map<String, Object> diamond = Map.of("actorId", "UAV-1", "linkedVolumeId", "TNFZ-001",
                "requiredAction", "CLIMB_OVER");
        Map<String, Object> correct = Map.of("actorId", "UAV-1", "volumeId", "TNFZ-001",
                "actionType", "CLIMB_OVER", "status", "APPLIED");
        assertEquals(true, DemoSessionService.diamondActionEligible(diamond, "UAV-1", correct));
        assertEquals(false, DemoSessionService.diamondActionEligible(diamond, "UAV-2", correct));
        assertEquals(false, DemoSessionService.diamondActionEligible(diamond, "UAV-1",
                Map.of("volumeId", "TNFZ-001", "actionType", "DETOUR", "status", "APPLIED")));
        assertEquals(false, DemoSessionService.diamondActionEligible(diamond, "UAV-1",
                Map.of("volumeId", "TNFZ-001", "actionType", "CLIMB_OVER", "status", "PENDING")));
    }

    @Test
    void ordinaryRouteDiamondNeedsTheBoundActorButNoAirspaceAction() {
        Map<String, Object> diamond = Map.of("actorId", "UAV-1", "challengeType", "ROUTE");
        assertEquals(true, DemoSessionService.diamondActionEligible(diamond, "UAV-1", null));
        assertEquals(false, DemoSessionService.diamondActionEligible(diamond, "UAV-2", null));
    }

    @Test
    void corridorDiamondRejectsTheDetourAction() {
        Map<String, Object> diamond = Map.of("actorId", "UAV-1", "challengeType", "AIRSPACE",
                "linkedVolumeId", "CORRIDOR-1", "requiredAction", "TRANSIT_CORRIDOR");
        assertEquals(true, DemoSessionService.diamondActionEligible(diamond, "UAV-1",
                Map.of("volumeId", "CORRIDOR-1", "actionType", "TRANSIT_CORRIDOR", "status", "APPLIED")));
        assertEquals(false, DemoSessionService.diamondActionEligible(diamond, "UAV-1",
                Map.of("volumeId", "CORRIDOR-1", "actionType", "DETOUR", "status", "APPLIED")));
    }

    @Test
    void fixedEntryFinesRepeatForRedButOnlyOnceForACorridor() {
        Map<String, Object> red = Map.of("ruleType", "ABSOLUTE_NO_FLY", "penaltyPolicy",
                Map.of("type", "FIXED_ON_ENTRY", "amountMinor", 800_000L, "repeatMode", "PER_INCURSION"));
        Map<String, Object> purple = Map.of("ruleType", "ALTITUDE_CORRIDOR", "penaltyPolicy",
                Map.of("type", "FIXED_ON_ENTRY", "amountMinor", 300_000L, "repeatMode", "ONCE_PER_VOLUME"));
        Map<String, Object> orange = Map.of("ruleType", "TEMPORARY_NO_FLY");
        assertEquals(800_000, DemoSessionService.fixedFineForEntry(red, 1));
        assertEquals(800_000, DemoSessionService.fixedFineForEntry(red, 2));
        assertEquals(300_000, DemoSessionService.fixedFineForEntry(purple, 1));
        assertEquals(0, DemoSessionService.fixedFineForEntry(purple, 2));
        assertEquals(0, DemoSessionService.fixedFineForEntry(orange, 1));
    }

    @Test
    void orangeDurationFineKeepsItsBaseRateAndMaximum() {
        Map<String, Object> policy = Map.of("baseMinor", 120_000L, "perSecondMinor", 12_000L,
                "maximumMinor", 480_000L);
        assertEquals(132_000, DemoSessionService.durationFineMinor(policy, 1_000));
        assertEquals(480_000, DemoSessionService.durationFineMinor(policy, 60_000));
    }
}
