package com.skyfleet.logistics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskInstanceServiceEconomyTest {
    private static final String VISITOR = "a".repeat(64);
    private static final Set<Long> DENOMINATIONS = Set.of(30_000L, 50_000L, 80_000L, 120_000L, 180_000L);

    @Test
    void sameSeedIsReproducibleAndDifferentSeedsVaryAirspace() throws Exception {
        TaskInstanceService service = fixture();

        Map<String, Object> first = service.generate(VISITOR, ScenarioTemplateCatalog.CAMPUS_TEMPLATE_ID,
                "9255908406400508081", Map.of("deliveryDensity", "STANDARD"));
        Map<String, Object> replay = service.generate(VISITOR, ScenarioTemplateCatalog.CAMPUS_TEMPLATE_ID,
                "9255908406400508081", Map.of("deliveryDensity", "STANDARD"));
        Map<String, Object> different = service.generate(VISITOR, ScenarioTemplateCatalog.CAMPUS_TEMPLATE_ID,
                "14385922599688823724", Map.of("deliveryDensity", "STANDARD"));

        assertThat(replay.get("planHash")).isEqualTo(first.get("planHash"));
        assertThat(map(plan(replay).get("airspaceProfile"))).isEqualTo(map(plan(first).get("airspaceProfile")));
        assertThat(maps(map(plan(replay).get("airspace")).get("volumes")))
                .isEqualTo(maps(map(plan(first).get("airspace")).get("volumes")));
        assertThat(maps(map(plan(different).get("airspace")).get("volumes")))
                .isNotEqualTo(maps(map(plan(first).get("airspace")).get("volumes")));
    }

    @Test
    void playerSelectedAirspaceCountIsExactAndFourIncludesEveryTheme() throws Exception {
        TaskInstanceService service = fixture();
        Map<String, Object> fourPlan = null;

        for (int requested : List.of(2, 3, 4)) {
            Map<String, Object> generated = service.generate(VISITOR, ScenarioTemplateCatalog.CAMPUS_TEMPLATE_ID,
                    String.valueOf(1200 + requested), Map.of("airspaceThemeCount", requested));
            Map<String, Object> generatedPlan = plan(generated);
            Map<String, Object> profile = map(generatedPlan.get("airspaceProfile"));
            assertThat(((Number) profile.get("themeCount")).intValue()).isEqualTo(requested);
            assertThat(maps(map(generatedPlan.get("airspace")).get("volumes"))).hasSize(requested);
            assertThat((List<?>) profile.get("themes")).hasSize(requested).doesNotHaveDuplicates();
            if (requested == 4) fourPlan = generatedPlan;
        }

        assertThat(fourPlan).isNotNull();
        assertThat(((List<?>) map(fourPlan.get("airspaceProfile")).get("themes")).stream()
                .map(String::valueOf).toList())
                .containsExactlyInAnyOrder("RED", "YELLOW", "PURPLE", "ORANGE");
    }

    @Test
    void tutorialSeedProducesTheFourThemesWithAnEarlyRedDetourDiamondInEveryArea() throws Exception {
        TaskInstanceService service = fixture();

        for (String templateId : List.of("hefei-logistics-area-a", "hefei-logistics-area-b",
                ScenarioTemplateCatalog.CAMPUS_TEMPLATE_ID)) {
            Map<String, Object> generatedPlan = plan(service.generate(VISITOR, templateId, "1204",
                    Map.of("airspaceThemeCount", 4)));
            List<Map<String, Object>> volumes = maps(map(generatedPlan.get("airspace")).get("volumes"));
            Map<String, Object> red = volumes.stream()
                    .filter(volume -> "ABSOLUTE_NO_FLY".equals(volume.get("ruleType"))).findFirst().orElseThrow();
            double earliestProgress = volumes.stream()
                    .mapToDouble(volume -> ((Number) volume.get("routeProgress")).doubleValue()).min().orElseThrow();

            assertThat(volumes).hasSize(4);
            assertThat(volumes).extracting(volume -> String.valueOf(volume.get("theme")))
                    .containsExactlyInAnyOrder("RED", "YELLOW", "PURPLE", "ORANGE");
            assertThat(((Number) red.get("routeProgress")).doubleValue()).isEqualTo(earliestProgress);
            assertThat(((List<?>) red.get("availableActions")).stream().map(String::valueOf).toList())
                    .contains("DETOUR");
            assertThat(maps(generatedPlan.get("rewardDiamonds"))).anySatisfy(diamond -> {
                assertThat(diamond).containsEntry("linkedVolumeId", red.get("id"))
                        .containsEntry("requiredAction", "DETOUR")
                        .containsEntry("rewardMinor", 480_000L);
            });
        }
    }

    @Test
    void generatedRewardsUseDeclaredTiersStayOutsideNoFlyBuffersAndRemainReachableAfterConflict() throws Exception {
        TaskInstanceService service = fixture();
        Map<String, Object> generated = service.generate(VISITOR, ScenarioTemplateCatalog.CAMPUS_TEMPLATE_ID,
                "9255908406400508081", Map.of("deliveryDensity", "STANDARD"));
        Map<String, Object> plan = plan(generated);
        List<Map<String, Object>> rewards = maps(plan.get("deliveryPoints"));
        List<Map<String, Object>> diamonds = maps(plan.get("rewardDiamonds"));
        List<Map<String, Object>> volumes = maps(map(plan.get("airspace")).get("volumes"));
        Map<String, Object> dynamic = volumes.get(0);
        Map<String, Object> airRoute = maps(plan.get("routes")).stream()
                .filter(route -> "AIR".equals(route.get("kind"))).findFirst().orElseThrow();
        AirspaceGeometry.Conflict conflict = AirspaceGeometry.conflict(TaskInstanceService.points(airRoute), dynamic, 0);

        assertThat(rewards).filteredOn(point -> "GROUND".equals(point.get("kind"))).hasSize(2);
        assertThat(rewards).filteredOn(point -> "AIR".equals(point.get("kind"))).hasSize(2);
        assertThat(rewards).allSatisfy(point -> {
            long baseReward = ((Number) point.get("baseRewardMinor")).longValue();
            long actualReward = ((Number) point.get("rewardMinor")).longValue();
            assertThat(baseReward).isIn(DENOMINATIONS);
            if ("GROUND".equals(point.get("kind"))) assertThat(actualReward).isEqualTo(Math.round(baseReward * 1.5 / 100.0) * 100);
            else assertThat(actualReward).isEqualTo(baseReward);
            assertThat(volumes).allSatisfy(volume -> assertThat(AirspaceGeometry.distanceToFootprintMeters(
                    volume, coordinate(point.get("position")))).isGreaterThanOrEqualTo(35));
        });
        assertThat(conflict).isNotNull();
        assertThat(volumes).hasSizeBetween(2, 3).allSatisfy(volume ->
                assertThat(AirspaceGeometry.conflict(TaskInstanceService.points(airRoute), volume, 0)).isNotNull());
        assertThat(rewards).anySatisfy(point -> {
            assertThat(point.get("kind")).isEqualTo("AIR");
            assertThat(((Number) point.get("routeProgress")).doubleValue()).isGreaterThan(conflict.endProgress() + 4);
        });
        long coinTotal = rewards.stream().mapToLong(point -> ((Number) point.get("rewardMinor")).longValue()).sum();
        long baseGroundTotal = rewards.stream().filter(point -> "GROUND".equals(point.get("kind")))
                .mapToLong(point -> ((Number) point.get("baseRewardMinor")).longValue()).sum();
        long groundCargoTotal = rewards.stream().filter(point -> "GROUND".equals(point.get("kind")))
                .mapToLong(point -> ((Number) point.get("rewardMinor")).longValue()).sum();
        long baseAirTotal = rewards.stream().filter(point -> "AIR".equals(point.get("kind")))
                .mapToLong(point -> ((Number) point.get("baseRewardMinor")).longValue()).sum();
        long diamondTotal = diamonds.stream().mapToLong(item -> ((Number) item.get("rewardMinor")).longValue()).sum();
        assertThat(((Number) map(plan.get("economyQuote")).get("coinRewardMinor")).longValue()).isEqualTo(coinTotal);
        assertThat(((Number) map(plan.get("economyQuote")).get("baseGroundRewardMinor")).longValue()).isEqualTo(baseGroundTotal);
        assertThat(((Number) map(plan.get("economyQuote")).get("groundCargoRewardMinor")).longValue()).isEqualTo(groundCargoTotal);
        assertThat(map(plan.get("groundVehicle"))).containsEntry("assetId", "ASSET-TEST-GROUND")
                .containsEntry("speedKph", 18.0).containsEntry("fullRangeKm", 5.0).containsEntry("cargoMultiplier", 1.5);
        assertThat(map(plan.get("airVehicle"))).containsEntry("assetId", "ASSET-TEST-AIR")
                .containsEntry("speedKph", 28.0).containsEntry("fullRangeKm", 3.0)
                .containsEntry("cargoMultiplier", 1.0).containsEntry("maneuverDelaySeconds", 8.0);
        assertThat(maps(plan.get("routes")).stream().filter(route -> "GROUND".equals(route.get("kind"))).findFirst().orElseThrow())
                .containsEntry("nominalSpeedKph", 18.0);
        assertThat(airRoute).containsEntry("nominalSpeedKph", 28.0).containsEntry("fleetAssetId", "ASSET-TEST-AIR");
        assertThat(((Number) map(plan.get("economyQuote")).get("baseAirRewardMinor")).longValue()).isEqualTo(baseAirTotal);
        assertThat(((Number) map(plan.get("economyQuote")).get("airCargoRewardMinor")).longValue()).isEqualTo(baseAirTotal);
        assertThat(((Number) map(plan.get("economyQuote")).get("estimatedAirBatteryUsePercent")).doubleValue()).isPositive();
        assertThat(((Number) map(plan.get("economyQuote")).get("diamondPotentialMinor")).longValue()).isEqualTo(diamondTotal);
        long maximumTimelinessRewardMinor = ((Number) map(plan.get("economyQuote"))
                .get("maximumTimelinessRewardMinor")).longValue();
        assertThat(((Number) map(plan.get("economyQuote")).get("grossRewardMinor")).longValue())
                .isEqualTo(coinTotal + diamondTotal + maximumTimelinessRewardMinor);
        assertThat(diamonds).hasSizeBetween(2, 5).allSatisfy(diamond -> {
            assertThat(((Number) diamond.get("rewardMinor")).longValue()).isEqualTo(480_000L);
            assertThat(volumes).allSatisfy(volume -> assertThat(AirspaceGeometry.contains(volume,
                    coordinate(diamond.get("position")))).isFalse());
            assertThat(diamond.get("challengeType")).isIn("AIRSPACE", "ROUTE");
        });
        List<Map<String, Object>> airspaceDiamonds = diamonds.stream()
                .filter(diamond -> "AIRSPACE".equals(diamond.get("challengeType"))).toList();
        assertThat(airspaceDiamonds).hasSize(volumes.size());
        assertThat(airspaceDiamonds).extracting(diamond -> String.valueOf(diamond.get("linkedVolumeId")))
                .containsExactlyInAnyOrderElementsOf(volumes.stream()
                        .map(volume -> String.valueOf(volume.get("id"))).toList());
    }

    @Test
    void rewardCountsFollowLowStandardAndHighIntensity() throws Exception {
        TaskInstanceService service = fixture();

        assertRewardCounts(service, "11", "LOW", 1);
        assertRewardCounts(service, "12", "STANDARD", 2);
        assertRewardCounts(service, "13", "HIGH", 3);
        Map<String, Object> low = plan(service.generate(VISITOR, ScenarioTemplateCatalog.CAMPUS_TEMPLATE_ID,
                "4242", Map.of("deliveryDensity", "LOW")));
        Map<String, Object> high = plan(service.generate(VISITOR, ScenarioTemplateCatalog.CAMPUS_TEMPLATE_ID,
                "4242", Map.of("deliveryDensity", "HIGH")));
        assertThat(low.get("airspaceProfile")).isEqualTo(high.get("airspaceProfile"));
    }

    @Test
    void representativeSeedsKeepEveryCoinOutsideEveryVisibleAirspaceFootprint() throws Exception {
        TaskInstanceService service = fixture();
        for (String intensity : List.of("LOW", "STANDARD", "HIGH")) {
            for (long seed = 20; seed < 25; seed++) {
                Map<String, Object> plan = plan(service.generate(VISITOR,
                        ScenarioTemplateCatalog.CAMPUS_TEMPLATE_ID, String.valueOf(seed),
                        Map.of("deliveryDensity", intensity)));
                List<Map<String, Object>> volumes = maps(map(plan.get("airspace")).get("volumes"));
                assertThat(maps(plan.get("deliveryPoints"))).allSatisfy(point ->
                        assertThat(volumes).allSatisfy(volume ->
                                assertThat(AirspaceGeometry.distanceToFootprintMeters(volume,
                                        coordinate(point.get("position")))).isGreaterThanOrEqualTo(35)));
            }
        }
    }

    @Test
    void fixedSeedBatchValidatesAllThreeTemplates() throws Exception {
        TaskInstanceService service = fixture();
        for (String templateId : List.of("hefei-logistics-area-a", "hefei-logistics-area-b",
                ScenarioTemplateCatalog.CAMPUS_TEMPLATE_ID)) {
            for (long seed = 710; seed < 716; seed++) {
                Map<String, Object> generatedPlan;
                try {
                    generatedPlan = plan(service.generate(VISITOR, templateId, String.valueOf(seed),
                            Map.of("deliveryDensity", "STANDARD")));
                } catch (DemoException error) {
                    throw new AssertionError("template=" + templateId + ", seed=" + seed, error);
                }
                Map<String, Object> profile = map(generatedPlan.get("airspaceProfile"));
                int count = ((Number) profile.get("themeCount")).intValue();
                assertThat(count).isBetween(2, 3);
                assertThat((List<?>) profile.get("themes")).hasSize(count).doesNotHaveDuplicates();
                List<Map<String, Object>> diamonds = maps(generatedPlan.get("rewardDiamonds"));
                assertThat(diamonds).hasSizeBetween(count, 5)
                        .allSatisfy(diamond -> assertThat(diamond.get("rewardMinor")).isEqualTo(480_000L));
                assertThat(diamonds).filteredOn(diamond -> "AIRSPACE".equals(diamond.get("challengeType")))
                        .hasSize(count);
            }
        }
    }

    @Test
    void luyangSeedsWithOpenRouteEdgeCasesStillGenerateValidPlans() throws Exception {
        TaskInstanceService service = fixture();
        for (String seed : List.of("57", "73", "93", "105", "115", "159")) {
            Map<String, Object> generated;
            try {
                generated = service.generate(VISITOR, "hefei-logistics-area-a", seed,
                        Map.of("deliveryDensity", "STANDARD"));
            } catch (DemoException error) {
                throw new AssertionError("Luyang generation failed for seed=" + seed, error);
            }
            assertThat(map(generated.get("validation")).get("status")).isEqualTo("PASSED");
        }
    }

    @Test
    void depletedVehiclesProduceAWarningQuoteInsteadOfBlockingTaskGeneration() throws Exception {
        TaskInstanceService service = fixture(0, 0);

        Map<String, Object> generated = service.generate(VISITOR, "hefei-logistics-area-a", "115",
                Map.of("deliveryDensity", "STANDARD"));
        Map<String, Object> quote = map(plan(generated).get("economyQuote"));

        assertThat(map(generated.get("validation")).get("status")).isEqualTo("PASSED");
        assertThat(quote).containsEntry("groundBatterySufficient", false)
                .containsEntry("airBatterySufficient", false)
                .containsEntry("batterySufficient", false);
    }

    @Test
    void readinessQuoteIncludesTheRequestedReturnReserve() throws Exception {
        TaskInstanceService service = fixture(50, 50);

        Map<String, Object> quote = map(plan(service.generate(VISITOR, "hefei-logistics-area-a", "115",
                Map.of("deliveryDensity", "STANDARD", "returnReservePercent", 25))).get("economyQuote"));
        double groundUse = ((Number) quote.get("estimatedGroundBatteryUsePercent")).doubleValue();
        double airUse = ((Number) quote.get("estimatedAirBatteryUsePercentMin")).doubleValue();

        assertThat(quote.get("groundBatterySufficient")).isEqualTo(50 + 1e-6 >= groundUse + 25);
        assertThat(quote.get("airBatterySufficient")).isEqualTo(50 + 1e-6 >= airUse + 25);
    }

    @Test
    void luyangRejectsAnImpossibleEightMinuteLimitBeforeGeneration() throws Exception {
        TaskInstanceService service = fixture();

        assertThatThrownBy(() -> service.generate(VISITOR, "hefei-logistics-area-a", "1",
                Map.of("maxDurationMinutes", 8)))
                .isInstanceOf(DemoException.class)
                .hasMessageContaining("11.0–30.0");
        assertThat(service.templateSummaries().stream()
                .filter(item -> "hefei-logistics-area-a".equals(item.get("id")))
                .map(item -> map(item.get("parameterLimits")))
                .map(limits -> (List<?>) limits.get("maxDurationMinutes"))
                .findFirst().orElseThrow()).isEqualTo(List.of(11, 30));
    }

    @Test
    void representativeSeedsCoverAllThemesVaryOrderAndKeepDiamondRules() throws Exception {
        TaskInstanceService service = fixture();
        java.util.Set<String> themes = new java.util.HashSet<>();
        java.util.Set<List<?>> orders = new java.util.HashSet<>();
        java.util.Set<Integer> diamondCounts = new java.util.HashSet<>();
        for (long seed = 30; seed < 70; seed++) {
            Map<String, Object> plan = plan(service.generate(VISITOR,
                    ScenarioTemplateCatalog.CAMPUS_TEMPLATE_ID, String.valueOf(seed),
                    Map.of("deliveryDensity", "STANDARD", "flightAltitudeMeters", 72)));
            Map<String, Object> profile = map(plan.get("airspaceProfile"));
            List<?> selected = (List<?>) profile.get("themes");
            assertThat(selected).hasSizeBetween(2, 3).doesNotHaveDuplicates();
            themes.addAll(selected.stream().map(String::valueOf).toList());
            orders.add(selected);
            List<Map<String, Object>> diamonds = maps(plan.get("rewardDiamonds"));
            diamondCounts.add(diamonds.size());
            assertThat(diamonds).hasSizeBetween(selected.size(), 5)
                    .allSatisfy(diamond -> assertThat(diamond.get("rewardMinor")).isEqualTo(480_000L));
            assertThat(diamonds).filteredOn(diamond -> "AIRSPACE".equals(diamond.get("challengeType")))
                    .hasSize(selected.size());
            Map<String, Map<String, Object>> volumesById = maps(map(plan.get("airspace")).get("volumes")).stream()
                    .collect(java.util.stream.Collectors.toMap(volume -> String.valueOf(volume.get("id")), volume -> volume));
            diamonds.stream().filter(diamond -> "AIRSPACE".equals(diamond.get("challengeType"))).forEach(diamond -> {
                Map<String, Object> volume = volumesById.get(String.valueOf(diamond.get("linkedVolumeId")));
                assertThat(volume).isNotNull();
                String action = String.valueOf(diamond.get("requiredAction"));
                switch (String.valueOf(volume.get("ruleType"))) {
                    case "ABSOLUTE_NO_FLY", "RISK_AIRSPACE" -> assertThat(action).isEqualTo("DETOUR");
                    case "ALTITUDE_CORRIDOR" -> assertThat(action).isEqualTo("TRANSIT_CORRIDOR");
                    case "TEMPORARY_NO_FLY" -> assertThat(action).isIn("DETOUR", "CLIMB_OVER");
                    default -> throw new AssertionError("unexpected rule " + volume.get("ruleType"));
                }
            });
            assertThat(diamonds).filteredOn(diamond -> "ROUTE".equals(diamond.get("challengeType")))
                    .allSatisfy(diamond -> {
                        assertThat(diamond).doesNotContainKeys("linkedVolumeId", "requiredAction");
                        assertThat(maps(map(plan.get("airspace")).get("volumes"))).allSatisfy(volume ->
                                assertThat(AirspaceGeometry.distanceToFootprintMeters(volume,
                                        coordinate(diamond.get("position")))).isGreaterThanOrEqualTo(35));
                    });
        }
        assertThat(themes).containsExactlyInAnyOrder("RED", "YELLOW", "PURPLE", "ORANGE");
        assertThat(orders.size()).isGreaterThan(4);
        assertThat(diamondCounts).containsExactlyInAnyOrder(2, 3, 4, 5);
    }

    @Test
    void everyDiamondIsReachableOnOneCompleteChallengeRoute() throws Exception {
        TaskInstanceService service = fixture();
        for (String templateId : List.of("hefei-logistics-area-a", "hefei-logistics-area-b",
                ScenarioTemplateCatalog.CAMPUS_TEMPLATE_ID)) {
            for (long seed = 900; seed < 940; seed++) {
                long currentSeed = seed;
                Map<String, Object> generatedPlan = plan(service.generate(VISITOR, templateId, String.valueOf(seed),
                        Map.of("deliveryDensity", "STANDARD", "flightAltitudeMeters", 72)));
                Map<String, Object> airRoute = maps(generatedPlan.get("routes")).stream()
                        .filter(route -> "AIR".equals(route.get("kind"))).findFirst().orElseThrow();
                List<Map<String, Object>> diamonds = maps(generatedPlan.get("rewardDiamonds"));
                List<Map<String, Object>> volumes = maps(map(generatedPlan.get("airspace")).get("volumes"));
                List<double[]> originalRoute = TaskInstanceService.points(airRoute);
                List<double[]> executableRoute = TaskInstanceService.diamondCollectionRoute(
                        originalRoute, volumes, diamonds);
                assertThat(executableRoute).isNotEmpty();
                assertThat(diamonds).allSatisfy(diamond -> assertThat(routePasses(executableRoute,
                        coordinate(diamond.get("position")),
                        ((Number) diamond.get("triggerRadiusMeters")).doubleValue(),
                        ((Number) diamond.get("altitudeToleranceMeters")).doubleValue()))
                        .as("template=%s seed=%s diamond=%s must be collectible on one complete route",
                                templateId, currentSeed, diamond.get("id"))
                        .isTrue());
                assertThat(diamonds).filteredOn(diamond -> "ROUTE".equals(diamond.get("challengeType")))
                        .allSatisfy(diamond -> assertThat(routePasses(originalRoute,
                                coordinate(diamond.get("position")),
                                ((Number) diamond.get("triggerRadiusMeters")).doubleValue(),
                                ((Number) diamond.get("altitudeToleranceMeters")).doubleValue())).isTrue());
            }
        }
    }

    private static void assertDiamondRouteIsExclusive(Map<String, Object> airRoute, Map<String, Object> volume,
                                                       Map<String, Object> diamond) {
        List<double[]> original = TaskInstanceService.points(airRoute);
        double[] target = coordinate(diamond.get("position"));
        double altitude = ((Number) volume.get("ceilingMeters")).doubleValue() + 12;
        List<double[]> detour = AirspaceGeometry.detour(original, volume);
        List<double[]> climb = altitude <= 100 ? AirspaceGeometry.climbOver(original, volume, altitude) : List.of();
        String action = String.valueOf(diamond.get("requiredAction"));
        List<double[]> expected = "DETOUR".equals(action) ? detour : climb;
        List<double[]> wrong = "DETOUR".equals(action) ? climb : detour;
        double radius = ((Number) diamond.get("triggerRadiusMeters")).doubleValue();
        double tolerance = ((Number) diamond.get("altitudeToleranceMeters")).doubleValue();
        assertThat(routePasses(expected, target, radius, tolerance)).isTrue();
        assertThat(routePasses(original, target, radius, tolerance)).isFalse();
        if (!wrong.isEmpty()) assertThat(routePasses(wrong, target, radius, tolerance)).isFalse();
    }

    private static void assertDiamondRemainsOnRuntimeActionRoute(Map<String, Object> airRoute,
                                                                  Map<String, Object> volume,
                                                                  Map<String, Object> diamond) {
        List<double[]> original = TaskInstanceService.points(airRoute);
        AirspaceGeometry.Conflict conflict = AirspaceGeometry.conflict(original, volume, 0);
        double[] target = coordinate(diamond.get("position"));
        double radius = ((Number) diamond.get("triggerRadiusMeters")).doubleValue();
        double tolerance = ((Number) diamond.get("altitudeToleranceMeters")).doubleValue();
        double firstActionProgress = Math.max(0, conflict.startProgress() - 8);
        double latestSafeActionProgress = Math.max(firstActionProgress, conflict.startProgress() - 2.5);
        for (double progress = firstActionProgress; progress <= latestSafeActionProgress; progress += 1) {
            List<double[]> runtimeRoute = "DETOUR".equals(diamond.get("requiredAction"))
                    ? AirspaceGeometry.detour(original, volume, progress)
                    : AirspaceGeometry.climbOver(original, volume,
                    ((Number) volume.get("ceilingMeters")).doubleValue() + 12, progress);
            assertThat(routePasses(runtimeRoute, target, radius, tolerance))
                    .as("diamond remains collectible when action is applied at route progress %.2f", progress).isTrue();
        }
    }

    private static boolean routePasses(List<double[]> route, double[] target, double radius, double tolerance) {
        for (int index = 1; index < route.size(); index++) {
            if (AirspaceGeometry.segmentPassesPoint(route.get(index - 1), route.get(index), target,
                    radius, tolerance, true)) return true;
        }
        return false;
    }

    private static void assertRewardCounts(TaskInstanceService service, String seed, String intensity, int expected) {
        List<Map<String, Object>> rewards = maps(plan(service.generate(VISITOR,
                ScenarioTemplateCatalog.CAMPUS_TEMPLATE_ID, seed, Map.of("deliveryDensity", intensity))).get("deliveryPoints"));
        assertThat(rewards).filteredOn(point -> "GROUND".equals(point.get("kind"))).hasSize(expected);
        assertThat(rewards).filteredOn(point -> "AIR".equals(point.get("kind"))).hasSize(expected);
    }

    private static TaskInstanceService fixture() throws Exception {
        return fixture(92, 100);
    }

    private static TaskInstanceService fixture(double groundBatteryPercent, double airBatteryPercent) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> base;
        try (InputStream input = new ClassPathResource("mission/logistics-mission.json").getInputStream()) {
            base = mapper.readValue(input, new TypeReference<>() {});
        }
        MissionCatalog missions = mock(MissionCatalog.class);
        when(missions.copy()).thenAnswer(ignored -> mapper.convertValue(base, new TypeReference<Map<String, Object>>() {}));
        ScenarioTemplateCatalog templates = new ScenarioTemplateCatalog(missions, mapper);
        BaiduRouteProvider baidu = new BaiduRouteProvider(mapper, "", "https://api.map.baidu.com", 1, 1);
        SimulatedTrafficLightService signals = mock(SimulatedTrafficLightService.class);
        when(signals.templateNodes(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:task-economy-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        createSchema(jdbc);
        FleetService fleet = mock(FleetService.class);
        when(fleet.freezeGroundVehicle(org.mockito.ArgumentMatchers.anyString())).thenReturn(
                new FleetService.GroundVehicle("ASSET-TEST-GROUND", "tricycle", "测试配送车",
                        "tricycle", 1, groundBatteryPercent, 18, 5, 1.5, Map.of()));
        when(fleet.freezeAirVehicle(org.mockito.ArgumentMatchers.anyString())).thenReturn(
                new FleetService.AirVehicle("ASSET-TEST-AIR", "smart-city-drone", "测试轻型无人机",
                        "smart-city-drone", 2, airBatteryPercent, 28, 3, 1, 8, false, Map.of()));
        return new TaskInstanceService(templates, baidu, signals, fleet, jdbc, mapper);
    }

    private static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE demo_route_artifact (id VARCHAR(40) PRIMARY KEY, request_hash CHAR(64) NOT NULL UNIQUE, " +
                "scenario_template_id VARCHAR(64) NOT NULL, scenario_template_version VARCHAR(16) NOT NULL, " +
                "provider_contract_version VARCHAR(24) NOT NULL, source VARCHAR(32) NOT NULL, request_json CLOB NOT NULL, " +
                "route_json CLOB NOT NULL, route_hash CHAR(64) NOT NULL, provider_status VARCHAR(32) NOT NULL, " +
                "provider_latency_ms BIGINT NOT NULL DEFAULT 0, failure_code VARCHAR(64), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE demo_task_instance (id VARCHAR(40) PRIMARY KEY, visitor_hash CHAR(64) NOT NULL, " +
                "scenario_template_id VARCHAR(64) NOT NULL, scenario_template_version VARCHAR(16) NOT NULL, " +
                "generator_version VARCHAR(32) NOT NULL, ruleset_version VARCHAR(32) NOT NULL, seed_value VARCHAR(20) NOT NULL, " +
                "resolved_parameters_json CLOB NOT NULL, plan_json CLOB NOT NULL, validation_json CLOB NOT NULL, " +
                "generation_trace_json CLOB NOT NULL, plan_hash CHAR(64) NOT NULL, route_artifact_id VARCHAR(40) NOT NULL, " +
                "lifecycle_status VARCHAR(24) NOT NULL, created_at TIMESTAMP NOT NULL, expires_at TIMESTAMP NOT NULL, started_at TIMESTAMP)");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> plan(Map<String, Object> generated) { return (Map<String, Object>) generated.get("plan"); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) { return (List<Map<String, Object>>) value; }

    private static Map<String, Object> dynamicVolume(Map<String, Object> plan) {
        return maps(map(plan.get("airspace")).get("volumes")).stream()
                .filter(volume -> Boolean.TRUE.equals(volume.get("dynamic"))).findFirst().orElseThrow();
    }

    private static double[] coordinate(Object value) {
        List<?> point = (List<?>) value;
        return new double[]{((Number) point.get(0)).doubleValue(), ((Number) point.get(1)).doubleValue(), ((Number) point.get(2)).doubleValue()};
    }
}
