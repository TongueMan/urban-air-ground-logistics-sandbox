package com.skyfleet.logistics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DemoSessionServiceAdvancedRoutingTest {
    @Test
    void temporaryTargetAllowsAProviderUturnBeforeContinuingToMandatoryNodes() {
        BaiduRouteProvider baidu = mock(BaiduRouteProvider.class);
        when(baidu.driving(anyList())).thenAnswer(invocation -> {
            List<double[]> anchors = invocation.getArgument(0);
            return new BaiduRouteProvider.ProviderResult(true,
                    Map.of("points", anchors.stream().map(point -> List.of(point[0], point[1], .35)).toList()),
                    null, 1);
        });
        DemoSessionService service = new DemoSessionService(
                mock(MissionCatalog.class), mock(JdbcTemplate.class), new ObjectMapper(), mock(MqttBridge.class),
                mock(TaskTrafficEngine.class), mock(TaskInstanceService.class), mock(FleetService.class), baidu,
                new AdvancedRoutingProperties(true, true, true, 300, 600, 2, 3, 40, 1500, 1.6, 18),
                mock(TutorialProgressService.class), 12, 100, 180, 75);
        double[] current = {117.211693, 31.773235, .35};
        List<Number> target = List.of(117.211094, 31.773506, .35);
        List<List<Number>> mandatory = List.of(List.of(117.211322, 31.771687, .35));

        ReflectionTestUtils.invokeMethod(service, "resolveBaiduGroundRoute", current, target, mandatory, 40d);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<double[]>> requests = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(baidu, times(2)).driving(requests.capture());
        assertTrue(java.util.Arrays.equals(current, requests.getAllValues().get(0).get(0)));
        assertTrue(java.util.Arrays.equals(new double[]{117.211094, 31.773506, .35},
                requests.getAllValues().get(0).get(1)));
        assertTrue(java.util.Arrays.equals(new double[]{117.211094, 31.773506, .35},
                requests.getAllValues().get(1).get(0)));
        assertTrue(java.util.Arrays.equals(new double[]{117.211322, 31.771687, .35},
                requests.getAllValues().get(1).get(1)));
    }

    @Test
    void adjacentProviderSegmentsAreJoinedWithoutDuplicatingTheTurnaroundPoint() {
        List<double[]> route = new ArrayList<>();
        DemoSessionService.appendGroundRouteSegment(route, List.of(
                new double[]{117.211693, 31.773235, .35}, new double[]{117.211094, 31.773506, .35}));
        DemoSessionService.appendGroundRouteSegment(route, List.of(
                new double[]{117.211094, 31.773506, .35}, new double[]{117.211322, 31.771687, .35}));

        assertTrue(route.size() == 3);
    }

    @Test
    void droneWaitsAtRecoveryPointUntilCarrierPhysicallyArrives() {
        int recoveryIndex = 1;

        assertFalse(DemoSessionService.carrierReadyForRecovery(false, recoveryIndex, recoveryIndex, false));
        assertTrue(DemoSessionService.carrierReadyForRecovery(false, recoveryIndex, recoveryIndex, true));
        assertFalse(DemoSessionService.carrierReadyForRecovery(false, recoveryIndex + 1, recoveryIndex, false));
        assertTrue(DemoSessionService.carrierReadyForRecovery(true, 0, recoveryIndex, false));
    }

    @Test
    void tutorialCollectsPassedCoinWithoutCreatingLedgerTransaction() {
        FleetService fleet = mock(FleetService.class);
        when(fleet.missionTransactions("visitor", "RUN-TUTORIAL")).thenReturn(List.of());
        when(fleet.companyView("visitor")).thenReturn(Map.of("currency", "CNY", "balanceMinor", 0));
        DemoSessionService service = new DemoSessionService(
                mock(MissionCatalog.class), mock(JdbcTemplate.class), new ObjectMapper(), mock(MqttBridge.class),
                mock(TaskTrafficEngine.class), mock(TaskInstanceService.class), fleet, 12, 100, 180, 75);

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("economySuppressed", true);
        plan.put("actors", List.of(Map.of("id", "VEH-1", "kind", "VEHICLE", "routeId", "GROUND-1")));
        plan.put("routes", List.of(Map.of("routeId", "GROUND-1", "points", List.of(
                List.of(116.0, 39.0, 0.0), List.of(116.001, 39.0, 0.0)))));
        plan.put("deliveryPoints", List.of(Map.of(
                "id", "COIN-1", "actorId", "VEH-1", "kind", "GROUND",
                "routeId", "GROUND-1", "routeProgress", 0, "visualTier", "SMALL",
                "position", List.of(116.0, 39.0, 0.0), "triggerRadiusMeters", 14, "rewardMinor", 100)));
        DemoSession session = new DemoSession("RUN-TUTORIAL", "visitor", "RUNNING", "tutorial", "1.0.0",
                "TASK-TUTORIAL", 1, "test", plan, Instant.now());

        ReflectionTestUtils.invokeMethod(service, "processMissionEconomy", session, 100L);

        assertTrue(session.collectedDeliveryPointIds.contains("COIN-1"));
        assertTrue(session.deltasAfter(0).get(0).data().containsKey("economy"));
        verify(fleet, never()).applyMissionTransaction(anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyString(), anyString(), anyLong(), anyString(), anyInt(), anyMap());
    }

    @Test
    void groundRewardWithoutRouteProgressDoesNotTerminateEconomyProcessing() {
        FleetService fleet = mock(FleetService.class);
        when(fleet.applyMissionTransaction(anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyString(), anyString(), anyLong(), anyString(), anyInt(), anyMap()))
                .thenReturn(Map.of("transaction", Map.of("id", "LED-1"), "replayed", false));
        when(fleet.missionTransactions("visitor", "RUN-GROUND-REWARD")).thenReturn(List.of());
        when(fleet.companyView("visitor")).thenReturn(Map.of("currency", "CNY", "balanceMinor", 0));
        DemoSessionService service = new DemoSessionService(
                mock(MissionCatalog.class), mock(JdbcTemplate.class), new ObjectMapper(), mock(MqttBridge.class),
                mock(TaskTrafficEngine.class), mock(TaskInstanceService.class), fleet, 12, 100, 180, 75);

        Map<String, Object> reward = new LinkedHashMap<>();
        reward.put("id", "GROUND-REWARD-01");
        reward.put("actorId", "VEH-1");
        reward.put("kind", "GROUND");
        reward.put("routeId", "GROUND-1");
        reward.put("position", List.of(116.0, 39.0, 0.0));
        reward.put("triggerRadiusMeters", 14);
        reward.put("rewardMinor", 80_000);
        reward.put("visualTier", "LARGE");
        // Advanced ground rewards are candidate-specific road anchors and do not
        // contain the optional routeProgress field used by air rewards.

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("actors", List.of(Map.of("id", "VEH-1", "kind", "VEHICLE", "routeId", "GROUND-1")));
        plan.put("routes", List.of(Map.of("routeId", "GROUND-1", "points", List.of(
                List.of(116.0, 39.0, 0.0), List.of(116.001, 39.0, 0.0)))));
        plan.put("deliveryPoints", List.of(reward));
        DemoSession session = new DemoSession("RUN-GROUND-REWARD", "visitor", "RUNNING", "advanced", "2.0.0",
                "TASK-GROUND-REWARD", 1, "test", plan, Instant.now());

        ReflectionTestUtils.invokeMethod(service, "processMissionEconomy", session, 1_000L);

        assertTrue(session.collectedDeliveryPointIds.contains("GROUND-REWARD-01"));
        verify(fleet).applyMissionTransaction(eq("visitor"), contains("GROUND-REWARD-01"),
                eq("DELIVERY_REWARD"), eq(80_000L), eq("RUN-GROUND-REWARD"), eq("GROUND-REWARD-01"),
                eq("VEH-1"), anyLong(), anyString(), eq(0), argThat(metadata ->
                        "GROUND".equals(metadata.get("kind"))
                                && "GROUND-1".equals(metadata.get("routeId"))
                                && !metadata.containsKey("routeProgress")));
    }
}
