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
    void tutorialBatteryProtectionUsesVirtualFullBatteryWithoutHidingUsageEstimates() throws Exception {
        TaskInstanceService service = fixture(0, 0);

        Map<String, Object> generated = service.generate(VISITOR, "hefei-logistics-area-a", "115",
                Map.of("deliveryDensity", "STANDARD", "tutorialBatteryProtected", true));
        Map<String, Object> protectedPlan = plan(generated);
        Map<String, Object> quote = map(protectedPlan.get("economyQuote"));

        assertThat(protectedPlan).containsEntry("tutorialBatteryProtected", true);
        assertThat(map(protectedPlan.get("groundVehicle"))).containsEntry("batteryPercent", 100.0);
        assertThat(map(protectedPlan.get("airVehicle"))).containsEntry("batteryPercent", 100.0);
        assertThat(maps(protectedPlan.get("actors")))
                .filteredOn(actor -> "VEHICLE".equals(actor.get("kind")) || "UAV".equals(actor.get("kind")))
                .allSatisfy(actor -> assertThat(actor).containsEntry("initialBattery", 100.0));
        assertThat(quote).containsEntry("groundBatterySufficient", true)
                .containsEntry("airBatterySufficient", true)
                .containsEntry("batterySufficient", true)
                .containsKeys("estimatedGroundBatteryUsePercent", "estimatedAirBatteryUsePercent");
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
        java.util.Set<String> orangeDiamondActions = new java.util.HashSet<>();
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
                    case "TEMPORARY_NO_FLY" -> {
                        assertThat(action).isIn("CONTINUE_DIRECT", "DETOUR", "CLIMB_OVER");
                        orangeDiamondActions.add(action);
                        if ("CONTINUE_DIRECT".equals(action))
                            assertThat(AirspaceGeometry.contains(volume, coordinate(diamond.get("position")))).isTrue();
                    }
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
        assertThat(orangeDiamondActions).containsExactlyInAnyOrder("CONTINUE_DIRECT", "DETOUR", "CLIMB_OVER");
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

    @Test
    void tutorial02UsesItsStableCarrierProfileWhenTheDeployedAircraftIsAnIndependentVtol() throws Exception {
        TaskInstanceService service = fixture(100, 100, true, true);

        Map<String, Object> generated = service.generate(VISITOR, ScenarioTemplateCatalog.CAMPUS_TEMPLATE_ID,
                "2026091202", Map.of("airspaceThemeCount", 2, "tutorialBatteryProtected", true),
                "ADVANCED", TutorialProgressService.GROUND_COOP_ID);
        Map<String, Object> generatedPlan = plan(generated);
        Map<String, Object> tutorialAir = map(generatedPlan.get("airVehicle"));
        Map<String, Object> quote = map(generatedPlan.get("economyQuote"));
        Map<String, Object> rendezvous = map(generatedPlan.get("rendezvousPlan"));

        assertThat(map(generated.get("validation")).get("status")).isEqualTo("PASSED");
        assertThat(tutorialAir).containsEntry("typeId", "smart-city-drone")
                .containsEntry("modelAssetId", "smart-city-drone")
                .containsEntry("independentRoute", false);
        assertThat(maps(map(generatedPlan.get("airspace")).get("volumes"))).isEmpty();
        assertThat(maps(generatedPlan.get("rewardDiamonds"))).isEmpty();
        assertThat(((Number) quote.get("estimatedGrossRewardMinor")).longValue()).isZero();
        assertThat(((Number) quote.get("maximumGrossRewardMinor")).longValue()).isZero();
        assertThat(maps(generatedPlan.get("groundRewards")))
                .filteredOn(reward -> "GROUND_TROPHY".equals(reward.get("rewardType")))
                .hasSizeBetween(2, 5);
        assertThat(maps(generatedPlan.get("groundRewards"))).allSatisfy(reward -> {
            assertThat(((Number) reward.get("baseRewardMinor")).longValue()).isZero();
            assertThat(((Number) reward.get("rewardMinor")).longValue()).isZero();
        });
        assertThat(((Number) quote.get("deliveryPointCount")).intValue())
                .isEqualTo(maps(generatedPlan.get("deliveryPoints")).size());
        assertThat(((Number) rendezvous.get("launchRouteProgress")).doubleValue()).isBetween(0.0, 100.0);
        assertThat(((Number) rendezvous.get("recoveryRouteProgress")).doubleValue()).isBetween(0.0, 100.0);
    }

    @Test
    void advancedPlanningKeepsCarrierNodesForTheLightDrone() throws Exception {
        TaskInstanceService service = fixture(100, 100, false, true);

        Map<String, Object> generated = service.generate(VISITOR, ScenarioTemplateCatalog.CAMPUS_TEMPLATE_ID,
                "2026091344", Map.of("airspaceThemeCount", 2), "ADVANCED", null);
        Map<String, Object> generatedPlan = plan(generated);
        double[] launch = coordinate(generatedPlan.get("launchPoint"));
        double[] recovery = coordinate(generatedPlan.get("recoveryPoint"));

        assertThat(map(generated.get("validation")).get("status")).isEqualTo("PASSED");
        assertThat(map(generatedPlan.get("airVehicle"))).containsEntry("independentRoute", false);
        assertThat(maps(generatedPlan.get("rewardDiamonds"))).allSatisfy(diamond ->
                assertThat(diamond).containsEntry("kind", "AIR").containsEntry("rewardType", "DIAMOND"));
        assertThat(maps(generatedPlan.get("groundRewards"))).allSatisfy(reward -> {
            assertThat(reward).containsEntry("kind", "GROUND");
            assertThat(String.valueOf(reward.get("rewardType"))).isIn("GROUND_COIN", "GROUND_TROPHY");
        });
        assertThat(map(generatedPlan.get("sharedGroundNodes"))).containsKeys("uavLaunch", "uavRecovery");
        assertThat(maps(generatedPlan.get("routeCandidates"))).hasSize(3).allSatisfy(candidate -> {
            List<double[]> route = TaskInstanceService.points(candidate);
            assertThat(nearestHorizontalDistance(route, launch)).isLessThanOrEqualTo(20);
            assertThat(nearestHorizontalDistance(route, recovery)).isLessThanOrEqualTo(20);
            assertThat(longestHorizontalSegment(route))
                    .as("fallback route must stay on the curated campus road graph instead of cutting across the lake")
                    .isLessThan(250);
        });

        Map<String, Object> startPlan = service.preparePlanForStart(String.valueOf(generated.get("taskId")), VISITOR,
                "ROUTE-CANDIDATE-B");
        assertThat((List<?>) startPlan.get("mandatoryGroundNodes")).hasSize(3);
        assertThat(maps(startPlan.get("routes")).stream()
                .filter(route -> "GROUND".equals(route.get("kind"))).findFirst().orElseThrow())
                .containsEntry("color", "#46dff2")
                .containsEntry("selectedBaseline", true);
    }

    @Test
    void advancedPlanningKeepsHeavyTransportIndependentFromGroundRecoveryNodes() throws Exception {
        TaskInstanceService service = fixture(100, 100, true, true);

        for (int seed = 2026091310; seed < 2026091315; seed++) {
            Map<String, Object> generated = service.generate(VISITOR, ScenarioTemplateCatalog.CAMPUS_TEMPLATE_ID,
                    String.valueOf(seed), Map.of("airspaceThemeCount", 2), "ADVANCED", null);
            Map<String, Object> generatedPlan = plan(generated);
            Map<String, Object> airRoute = maps(generatedPlan.get("routes")).stream()
                    .filter(route -> "AIR".equals(route.get("kind"))).findFirst().orElseThrow();
            Map<String, Object> groundRoute = maps(generatedPlan.get("routes")).stream()
                    .filter(route -> "GROUND".equals(route.get("kind"))).findFirst().orElseThrow();
            List<double[]> airPoints = TaskInstanceService.points(airRoute);

            assertThat(map(generated.get("validation")).get("status")).isEqualTo("PASSED");
            assertThat(map(generatedPlan.get("airVehicle"))).containsEntry("independentRoute", true);
            assertThat(maps(generatedPlan.get("rewardDiamonds"))).allSatisfy(diamond ->
                    assertThat(diamond).containsEntry("kind", "AIR").containsEntry("rewardType", "DIAMOND"));
            assertThat(maps(generatedPlan.get("groundRewards"))).allSatisfy(reward -> {
                assertThat(reward).containsEntry("kind", "GROUND");
                assertThat(String.valueOf(reward.get("rewardType"))).isIn("GROUND_COIN", "GROUND_TROPHY");
            });
            assertThat(map(generatedPlan.get("sharedGroundNodes")))
                    .containsKeys("start", "end").doesNotContainKeys("uavLaunch", "uavRecovery");
            assertThat(groundRoute).doesNotContainKeys("launchPoint", "recoveryPoint");
            assertThat(maps(generatedPlan.get("routeCandidates"))).hasSize(3)
                    .allSatisfy(candidate -> assertThat(candidate).doesNotContainKeys("launchPoint", "recoveryPoint"));
            assertThat(horizontalDistance(coordinate(generatedPlan.get("launchPoint")), airPoints.get(0)))
                    .isLessThanOrEqualTo(10);
            assertThat(horizontalDistance(coordinate(generatedPlan.get("recoveryPoint")), airPoints.get(airPoints.size() - 1)))
                    .isLessThanOrEqualTo(10);

            Map<String, Object> startPlan = service.preparePlanForStart(String.valueOf(generated.get("taskId")), VISITOR,
                    "ROUTE-CANDIDATE-B");
            assertThat((List<?>) startPlan.get("mandatoryGroundNodes")).hasSize(1);
        }
    }

    @Test
    void advancedGroundTrophiesAreSeededValuableSpacedAndBalancedAcrossRoutes() throws Exception {
        TaskInstanceService service = fixture(100, 100, false, true);
        java.util.Set<Integer> observedCounts = new java.util.HashSet<>();
        int successfulSeeds = 0;
        String replaySeed = null;
        String firstSuccessfulTaskId = null;
        Map<String, Object> firstSuccessfulPlan = null;

        for (int seed = 2026091400; seed < 2026091480
                && (successfulSeeds < 12 || observedCounts.size() < 4); seed++) {
            Map<String, Object> generated;
            try {
                generated = service.generate(VISITOR, ScenarioTemplateCatalog.CAMPUS_TEMPLATE_ID,
                        String.valueOf(seed), Map.of("airspaceThemeCount", 2), "ADVANCED", null);
            } catch (DemoException rejectedSeed) {
                // A caller-supplied seed is allowed to describe an infeasible airspace
                // combination. The UI uses automatic seed replacement; this test only
                // samples valid deterministic plans.
                continue;
            }
            Map<String, Object> generatedPlan = plan(generated);
            successfulSeeds++;
            if (firstSuccessfulPlan == null) {
                firstSuccessfulPlan = generatedPlan;
                replaySeed = String.valueOf(seed);
                firstSuccessfulTaskId = String.valueOf(generated.get("taskId"));
            }
            List<Map<String, Object>> rewards = maps(generatedPlan.get("groundRewards"));
            List<Map<String, Object>> coins = rewards.stream()
                    .filter(reward -> "GROUND_COIN".equals(reward.get("rewardType"))).toList();
            List<Map<String, Object>> trophies = rewards.stream()
                    .filter(reward -> "GROUND_TROPHY".equals(reward.get("rewardType"))).toList();

            observedCounts.add(trophies.size());
            assertThat(coins).hasSize(2).allSatisfy(coin ->
                    assertThat(((Number) coin.get("rewardMinor")).longValue()).isEqualTo(80_000L));
            assertThat(trophies).hasSizeBetween(2, 5).allSatisfy(trophy -> {
                assertThat(((Number) trophy.get("rewardMinor")).longValue()).isEqualTo(300_000L);
                assertThat(((List<?>) trophy.get("eligibleCandidateIds")).stream().map(String::valueOf).toList())
                        .contains(String.valueOf(trophy.get("placementCandidateId")));
            });
            for (Map<String, Object> trophy : trophies) {
                double[] anchor = coordinate(trophy.get("roadAnchor"));
                rewards.stream().filter(other -> other != trophy).forEach(other ->
                        assertThat(horizontalDistance(anchor, coordinate(other.get("roadAnchor"))))
                                .isGreaterThanOrEqualTo(60));
            }
            Map<String, Long> byRoute = trophies.stream().collect(java.util.stream.Collectors.groupingBy(
                    trophy -> String.valueOf(trophy.get("placementCandidateId")),
                    java.util.stream.Collectors.counting()));
            List<Long> routeCounts = maps(generatedPlan.get("routeCandidates")).stream()
                    .map(candidate -> byRoute.getOrDefault(String.valueOf(candidate.get("candidateId")), 0L)).toList();
            assertThat(java.util.Collections.max(routeCounts) - java.util.Collections.min(routeCounts))
                    .isLessThanOrEqualTo(1);
            long maximumGroundRewardMinor = rewards.stream()
                    .mapToLong(reward -> ((Number) reward.get("rewardMinor")).longValue()).sum();
            assertThat(((Number) map(generatedPlan.get("economyQuote")).get("maximumGroundRewardMinor")).longValue())
                    .isEqualTo(maximumGroundRewardMinor)
                    .isEqualTo(160_000L + trophies.size() * 300_000L);
            Map<String, Object> quote = map(generatedPlan.get("economyQuote"));
            for (Map<String, Object> candidate : maps(generatedPlan.get("routeCandidates"))) {
                String candidateId = String.valueOf(candidate.get("candidateId"));
                List<Map<String, Object>> eligible = rewards.stream().filter(reward ->
                        ((List<?>) reward.get("eligibleCandidateIds")).stream().map(String::valueOf)
                                .anyMatch(candidateId::equals)).toList();
                long expectedGround = eligible.stream()
                        .mapToLong(reward -> ((Number) reward.get("rewardMinor")).longValue()).sum();
                long expectedTrophies = eligible.stream()
                        .filter(reward -> "GROUND_TROPHY".equals(reward.get("rewardType")))
                        .mapToLong(reward -> ((Number) reward.get("rewardMinor")).longValue()).sum();
                assertThat(((Number) map(quote.get("groundRewardMinorByCandidate")).get(candidateId)).longValue())
                        .isEqualTo(expectedGround);
                assertThat(((Number) map(quote.get("trophyRewardMinorByCandidate")).get(candidateId)).longValue())
                        .isEqualTo(expectedTrophies);
            }
        }

        assertThat(observedCounts).containsExactlyInAnyOrder(2, 3, 4, 5);
        assertThat(successfulSeeds).isGreaterThanOrEqualTo(12);
        Map<String, Object> replay = plan(service.generate(VISITOR, ScenarioTemplateCatalog.CAMPUS_TEMPLATE_ID,
                replaySeed, Map.of("airspaceThemeCount", 2), "ADVANCED", null));
        assertThat(replay.get("groundRewards")).isEqualTo(firstSuccessfulPlan.get("groundRewards"));

        String selectedCandidateId = String.valueOf(maps(firstSuccessfulPlan.get("routeCandidates")).get(0).get("candidateId"));
        Map<String, Object> selectedPlan = service.preparePlanForStart(firstSuccessfulTaskId, VISITOR, selectedCandidateId);
        Map<String, Object> selectedQuote = map(selectedPlan.get("economyQuote"));
        long expectedSelectedGround = ((Number) map(selectedQuote.get("groundRewardMinorByCandidate"))
                .get(selectedCandidateId)).longValue();
        long expectedSelectedTrophies = ((Number) map(selectedQuote.get("trophyRewardMinorByCandidate"))
                .get(selectedCandidateId)).longValue();
        assertThat(selectedQuote.get("groundRewardCandidateId")).isEqualTo(selectedCandidateId);
        assertThat(((Number) selectedQuote.get("groundCargoRewardMinor")).longValue()).isEqualTo(expectedSelectedGround);
        assertThat(((Number) selectedQuote.get("groundTrophyRewardMinor")).longValue()).isEqualTo(expectedSelectedTrophies);
        assertThat(((Number) selectedQuote.get("estimatedGrossRewardMinor")).longValue()).isEqualTo(
                expectedSelectedGround
                        + ((Number) selectedQuote.get("airCargoRewardMinor")).longValue()
                        + ((Number) selectedQuote.get("diamondPotentialMinor")).longValue()
                        + ((Number) selectedQuote.get("estimatedTimelinessRewardMinor")).longValue());
    }

    @Test
    void advancedRouteRejectsAProviderLegThatRetracesTheRoadItJustUsed() {
        List<double[]> travelled = List.of(
                new double[]{117.2100, 31.7780, .35},
                new double[]{117.2090, 31.7770, .35},
                new double[]{117.2080, 31.7760, .35});
        List<double[]> uTurn = List.of(
                new double[]{117.2080, 31.7760, .35},
                new double[]{117.2090, 31.7770, .35},
                new double[]{117.2100, 31.7780, .35},
                new double[]{117.2102, 31.7770, .35});
        List<double[]> throughRoute = List.of(
                new double[]{117.2080, 31.7760, .35},
                new double[]{117.2078, 31.7750, .35},
                new double[]{117.2083, 31.7740, .35});

        assertThat(TaskInstanceService.hasSubstantialSegmentBacktrack(travelled, uTurn)).isTrue();
        assertThat(TaskInstanceService.hasSubstantialSegmentBacktrack(travelled, throughRoute)).isFalse();
    }

    private static TaskInstanceService fixture() throws Exception {
        return fixture(92, 100);
    }

    private static TaskInstanceService fixture(double groundBatteryPercent, double airBatteryPercent) throws Exception {
        return fixture(groundBatteryPercent, airBatteryPercent, false, false);
    }

    private static TaskInstanceService fixture(double groundBatteryPercent, double airBatteryPercent,
                                               boolean independentAir, boolean advancedEnabled) throws Exception {
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
                new FleetService.AirVehicle("ASSET-TEST-AIR",
                        independentAir ? "vtol-air-taxi" : "smart-city-drone",
                        independentAir ? "测试重载运输机" : "测试轻型无人机",
                        independentAir ? "vtol-air-taxi" : "smart-city-drone",
                        2, airBatteryPercent, independentAir ? 52 : 28, independentAir ? 9 : 3,
                        independentAir ? 3 : 1, independentAir ? 2 : 8, independentAir, Map.of()));
        if (!advancedEnabled) return new TaskInstanceService(templates, baidu, signals, fleet, jdbc, mapper);
        return new TaskInstanceService(templates, baidu, signals, fleet, jdbc, mapper,
                new AdvancedRoutingProperties(true, true, true, 300, 600, 2, 3, 40, 1500, 1.6, 18));
    }

    private static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE demo_route_artifact (id VARCHAR(40) PRIMARY KEY, request_hash CHAR(64) NOT NULL UNIQUE, " +
                "scenario_template_id VARCHAR(64) NOT NULL, scenario_template_version VARCHAR(16) NOT NULL, " +
                "provider_contract_version VARCHAR(24) NOT NULL, source VARCHAR(32) NOT NULL, request_json CLOB NOT NULL, " +
                "route_json CLOB NOT NULL, route_hash CHAR(64) NOT NULL, provider_status VARCHAR(32) NOT NULL, " +
                "provider_latency_ms BIGINT NOT NULL DEFAULT 0, failure_code VARCHAR(64), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE demo_task_instance (id VARCHAR(40) PRIMARY KEY, visitor_hash CHAR(64) NOT NULL, " +
                "scenario_template_id VARCHAR(64) NOT NULL, scenario_template_version VARCHAR(16) NOT NULL, " +
                "generator_version VARCHAR(32) NOT NULL, ruleset_version VARCHAR(32) NOT NULL, planning_mode VARCHAR(16) NOT NULL DEFAULT 'BASIC', tutorial_id VARCHAR(64), seed_value VARCHAR(20) NOT NULL, " +
                "resolved_parameters_json CLOB NOT NULL, plan_json CLOB NOT NULL, validation_json CLOB NOT NULL, " +
                "generation_trace_json CLOB NOT NULL, plan_hash CHAR(64) NOT NULL, route_artifact_id VARCHAR(40), selected_baseline_route_candidate_id VARCHAR(64), baseline_selected_at TIMESTAMP, " +
                "lifecycle_status VARCHAR(24) NOT NULL, created_at TIMESTAMP NOT NULL, expires_at TIMESTAMP NOT NULL, started_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE demo_task_route_candidate (task_instance_id VARCHAR(40) NOT NULL, candidate_id VARCHAR(64) NOT NULL, " +
                "candidate_order INT NOT NULL, label VARCHAR(64) NOT NULL, color VARCHAR(16) NOT NULL, route_artifact_id VARCHAR(40) NOT NULL, " +
                "route_hash CHAR(64) NOT NULL, distance_meters DECIMAL(12,3) NOT NULL, source VARCHAR(32) NOT NULL, PRIMARY KEY(task_instance_id,candidate_id))");
        jdbc.execute("CREATE TABLE demo_ground_reward (task_instance_id VARCHAR(40) NOT NULL, reward_id VARCHAR(64) NOT NULL, " +
                "reward_type VARCHAR(24) NOT NULL, actor_kind VARCHAR(16) NOT NULL, visual_position_json CLOB NOT NULL, road_anchor_json CLOB NOT NULL, " +
                "reward_minor BIGINT NOT NULL, trigger_radius_meters DECIMAL(8,2) NOT NULL, eligible_candidate_ids_json CLOB NOT NULL, PRIMARY KEY(task_instance_id,reward_id))");
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

    private static double nearestHorizontalDistance(List<double[]> route, double[] target) {
        return route.stream().mapToDouble(point -> horizontalDistance(point, target)).min().orElse(Double.POSITIVE_INFINITY);
    }

    private static double longestHorizontalSegment(List<double[]> route) {
        double longest = 0;
        for (int index = 1; index < route.size(); index++)
            longest = Math.max(longest, horizontalDistance(route.get(index - 1), route.get(index)));
        return longest;
    }

    private static double horizontalDistance(double[] first, double[] second) {
        return MissionMath.distance(new double[]{first[0], first[1], 0}, new double[]{second[0], second[1], 0});
    }
}
