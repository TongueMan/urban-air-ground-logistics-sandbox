package com.skyfleet.logistics;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FleetServiceTest {
    private static final String VISITOR_A = "a".repeat(64);
    private static final String VISITOR_B = "b".repeat(64);

    @Test
    void initializesCompanyAndStarterAssetsExactlyOnce() {
        Fixture fixture = fixture(true);
        Map<String, Object> first = fixture.service.snapshot(VISITOR_A);
        Map<String, Object> second = fixture.service.snapshot(VISITOR_A);

        assertThat(company(first).get("balanceMinor")).isEqualTo(10_000_000L);
        assertThat(assets(first)).extracting(asset -> asset.get("typeId"))
                .containsExactlyInAnyOrder("smart-city-drone", "tricycle");
        assertThat(assets(first)).extracting(asset -> asset.get("status"))
                .containsOnly("DEPLOYED");
        assertThat(fixture.service.latestDeployedTypeId(VISITOR_A, "GROUND")).isEqualTo("tricycle");
        assertThat(fixture.service.latestDeployedTypeId(VISITOR_A, "AIR")).isEqualTo("smart-city-drone");
        assertThat(assets(second)).hasSize(2);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM fleet_account_ledger WHERE visitor_hash=?", Integer.class, VISITOR_A)).isEqualTo(1);
        assertThat(assets(first)).allSatisfy(asset -> {
            assertThat(asset.get("batteryPercent")).isEqualTo(100.0);
            assertThat(asset.get("charging")).isEqualTo(false);
            assertThat(asset.get("stateVersion")).isEqualTo(0L);
        });
    }

    @Test
    void catalogPublishesExactGroundAndAirGameplayStats() {
        Map<String, Object> snapshot = fixture(true).service.snapshot(VISITOR_A);
        assertGroundType(snapshot, "tricycle", List.of(2, 5, 2, 2), 18, 5, 1.5);
        assertGroundType(snapshot, "ford-f350-utility", List.of(3, 4, 4, 3), 34, 8, 2.5);
        assertGroundType(snapshot, "ural-truck-vehicle-only", List.of(5, 3, 2, 4), 18, 13, 5);
        assertGroundType(snapshot, "cybertruck-fun-size", List.of(4, 4, 5, 5), 42, 21, 3.5);
        assertGroundType(snapshot, "peterbilt-379-optimus-prime", List.of(5, 5, 5, 5), 42, 21, 5);
        assertAirType(snapshot, "smart-city-drone", List.of(1, 2, 3, 2), 28, 3, 1, 8);
        assertAirType(snapshot, "vtol-air-taxi", List.of(5, 5, 5, 5), 52, 9, 3, 2);
        assertThat(snapshot.get("catalogVersion")).isEqualTo("fleet-catalog/2.1.0");
    }

    @Test
    void airBatteryUsesPhysicalDistanceAndStopsAtItsExactRange() {
        Fixture fixture = fixture(true);
        Map<String, Object> initial = fixture.service.snapshot(VISITOR_A);
        String assetId = assets(initial).stream().filter(asset -> "smart-city-drone".equals(asset.get("typeId")))
                .map(asset -> String.valueOf(asset.get("assetId"))).findFirst().orElseThrow();
        FleetService.AirVehicle frozen = fixture.service.freezeAirVehicle(VISITOR_A);
        FleetService.AirVehicle bound = fixture.service.bindAirVehicle(VISITOR_A, "RUN-AIR-BATTERY", assetId, frozen.stateVersion());

        assertThat(bound.speedKph()).isEqualTo(28);
        FleetService.BatteryUse first = fixture.service.consumeAirDistance(VISITOR_A, "RUN-AIR-BATTERY", assetId, 2_000, 3);
        assertThat(first.movedMeters()).isEqualTo(2_000);
        assertThat(first.batteryPercent()).isCloseTo(33.3333, org.assertj.core.data.Offset.offset(.001));
        FleetService.BatteryUse depleted = fixture.service.consumeAirDistance(VISITOR_A, "RUN-AIR-BATTERY", assetId, 2_000, 3);
        assertThat(depleted.movedMeters()).isCloseTo(1_000, org.assertj.core.data.Offset.offset(.01));
        assertThat(depleted.batteryPercent()).isZero();
        assertThat(depleted.depleted()).isTrue();
    }

    @Test
    void yellowRiskMultiplierChargesEquivalentDistanceWithoutChangingActualMovement() {
        Fixture fixture = fixture(true);
        String assetId = assets(fixture.service.snapshot(VISITOR_A)).stream()
                .filter(asset -> "smart-city-drone".equals(asset.get("typeId")))
                .map(asset -> String.valueOf(asset.get("assetId"))).findFirst().orElseThrow();
        FleetService.AirVehicle frozen = fixture.service.freezeAirVehicle(VISITOR_A);
        fixture.service.bindAirVehicle(VISITOR_A, "RUN-AIR-RISK", assetId, frozen.stateVersion());

        FleetService.BatteryUse use = fixture.service.consumeAirDistance(
                VISITOR_A, "RUN-AIR-RISK", assetId, 1_000, 3, 2.5);

        assertThat(use.movedMeters()).isEqualTo(1_000);
        assertThat(use.equivalentMeters()).isEqualTo(2_500);
        assertThat(use.extraBatteryPercent()).isEqualTo(50);
        assertThat(use.batteryPercent()).isCloseTo(16.6667, org.assertj.core.data.Offset.offset(.001));
    }

    @Test
    void groundBatteryStopsAtExactRangeAndRecallChargesLinearlyForSixtySeconds() {
        Fixture fixture = fixture(true);
        Map<String, Object> initial = fixture.service.snapshot(VISITOR_A);
        String assetId = assets(initial).stream().filter(asset -> "tricycle".equals(asset.get("typeId")))
                .map(asset -> String.valueOf(asset.get("assetId"))).findFirst().orElseThrow();
        FleetService.GroundVehicle frozen = fixture.service.freezeGroundVehicle(VISITOR_A);
        FleetService.GroundVehicle bound = fixture.service.bindGroundVehicle(VISITOR_A, "RUN-BATTERY", assetId, frozen.stateVersion());

        assertThat(bound.assetId()).isEqualTo(assetId);
        assertThatThrownBy(() -> fixture.service.changeStatus(VISITOR_A, assetId, "recall-bound", "GARAGED"))
                .isInstanceOf(DemoException.class).hasMessageContaining("正在执行任务");
        assertThatThrownBy(() -> fixture.service.sell(VISITOR_A, "sell-bound", assetId))
                .isInstanceOf(DemoException.class).hasMessageContaining("执行任务");

        FleetService.BatteryUse halfway = fixture.service.consumeGroundDistance(VISITOR_A, "RUN-BATTERY", assetId, 2_500, 5);
        assertThat(halfway.movedMeters()).isEqualTo(2_500);
        assertThat(halfway.batteryPercent()).isEqualTo(50);
        assertThat(halfway.depleted()).isFalse();
        FleetService.BatteryUse depleted = fixture.service.consumeGroundDistance(VISITOR_A, "RUN-BATTERY", assetId, 3_000, 5);
        assertThat(depleted.movedMeters()).isEqualTo(2_500);
        assertThat(depleted.batteryPercent()).isZero();
        assertThat(depleted.depleted()).isTrue();

        fixture.service.releaseRunBinding(VISITOR_A, "RUN-BATTERY");
        Map<String, Object> recalled = fleet(fixture.service.changeStatus(VISITOR_A, assetId, "recall-depleted", "GARAGED"));
        Map<String, Object> charging = asset(recalled, assetId);
        assertThat(charging.get("charging")).isEqualTo(true);
        assertThat(charging.get("chargingCompletesAt")).isNotNull();

        fixture.jdbc.update("UPDATE fleet_asset SET battery_basis_points=0,charging_from_basis_points=0,charging_started_at=DATEADD('SECOND',-30,CURRENT_TIMESTAMP) WHERE id=?", assetId);
        Map<String, Object> halfwayCharged = asset(fixture.service.snapshot(VISITOR_A), assetId);
        assertThat(((Number) halfwayCharged.get("batteryPercent")).doubleValue()).isBetween(49.0, 52.0);
        Map<String, Object> partialDeployment = fleet(fixture.service.changeStatus(VISITOR_A, assetId, "deploy-partial", "DEPLOYED"));
        assertThat(((Number) asset(partialDeployment, assetId).get("batteryPercent")).doubleValue()).isBetween(49.0, 52.0);
        assertThat(asset(partialDeployment, assetId).get("charging")).isEqualTo(false);

        fixture.service.changeStatus(VISITOR_A, assetId, "recall-partial", "GARAGED");
        fixture.jdbc.update("UPDATE fleet_asset SET charging_started_at=DATEADD('SECOND',-61,CURRENT_TIMESTAMP) WHERE id=?", assetId);
        Map<String, Object> fullyCharged = asset(fixture.service.snapshot(VISITOR_A), assetId);
        assertThat(fullyCharged.get("batteryPercent")).isEqualTo(100.0);
        assertThat(fullyCharged.get("charging")).isEqualTo(false);
    }

    @Test
    void taskFreezeRequiresADeployedGroundVehicleAndBecomesStaleAfterSwitchingVehicles() {
        Fixture fixture = fixture(true);
        Map<String, Object> initial = fixture.service.snapshot(VISITOR_A);
        String tricycleId = assets(initial).stream().filter(asset -> "tricycle".equals(asset.get("typeId")))
                .map(asset -> String.valueOf(asset.get("assetId"))).findFirst().orElseThrow();
        FleetService.GroundVehicle frozen = fixture.service.freezeGroundVehicle(VISITOR_A);
        Map<String, Object> purchase = fixture.service.purchase(VISITOR_A, "buy-switch", "ford-f350-utility", "DEV");
        String f350Id = String.valueOf(purchase.get("assetId"));
        fixture.jdbc.update("UPDATE fleet_asset SET battery_basis_points=4000 WHERE id=?", tricycleId);
        Map<String, Object> switched = fixture.service.changeStatus(VISITOR_A, f350Id, "deploy-switch", "DEPLOYED");
        assertThat(switched.get("autoRecalledAssetIds")).isEqualTo(List.of(tricycleId));
        assertThat(asset(fleet(switched), tricycleId)).containsEntry("status", "GARAGED").containsEntry("charging", true);
        assertThat(asset(fleet(switched), f350Id)).containsEntry("status", "DEPLOYED");
        assertThat(assets(fleet(switched))).filteredOn(item -> "DEPLOYED".equals(item.get("status"))).hasSize(2);
        assertThatThrownBy(() -> fixture.service.bindGroundVehicle(VISITOR_A, "RUN-STALE", tricycleId, frozen.stateVersion()))
                .isInstanceOf(DemoException.class).hasMessageContaining("重新生成");

        fixture.service.changeStatus(VISITOR_A, f350Id, "recall-f350", "GARAGED");
        assertThatThrownBy(() -> fixture.service.freezeGroundVehicle(VISITOR_A))
                .isInstanceOf(DemoException.class).hasMessageContaining("没有地面运输车出站");
    }

    @Test
    void deploymentCannotAutoRecallSameCategoryAssetBoundToRunningMission() {
        Fixture fixture = fixture(true);
        Map<String, Object> initial = fixture.service.snapshot(VISITOR_A);
        String tricycleId = assets(initial).stream().filter(asset -> "tricycle".equals(asset.get("typeId")))
                .map(asset -> String.valueOf(asset.get("assetId"))).findFirst().orElseThrow();
        FleetService.GroundVehicle frozen = fixture.service.freezeGroundVehicle(VISITOR_A);
        fixture.service.bindGroundVehicle(VISITOR_A, "RUN-SWITCH-GUARD", tricycleId, frozen.stateVersion());
        String f350Id = String.valueOf(fixture.service.purchase(VISITOR_A, "buy-switch-guard", "ford-f350-utility", "DEV").get("assetId"));

        assertThatThrownBy(() -> fixture.service.changeStatus(VISITOR_A, f350Id, "deploy-switch-guard", "DEPLOYED"))
                .isInstanceOf(DemoException.class).hasMessageContaining("正在执行任务");
        Map<String, Object> unchanged = fixture.service.snapshot(VISITOR_A);
        assertThat(asset(unchanged, tricycleId)).containsEntry("status", "DEPLOYED");
        assertThat(asset(unchanged, f350Id)).containsEntry("status", "GARAGED");
    }

    @Test
    void missionRewardsAndFinesShareAnIdempotentNonNegativeLedger() {
        Fixture fixture = fixture(true);
        fixture.service.snapshot(VISITOR_A);

        Map<String, Object> reward = fixture.service.applyMissionTransaction(VISITOR_A,
                "DELIVERY:RUN-1:POINT-1", "DELIVERY_REWARD", 50_000,
                "RUN-1", "POINT-1", "UAV-1", 5_000, "delivery-economy/1.0.0", Map.of("tier", "SMALL"));
        Map<String, Object> replay = fixture.service.applyMissionTransaction(VISITOR_A,
                "DELIVERY:RUN-1:POINT-1", "DELIVERY_REWARD", 50_000,
                "RUN-1", "POINT-1", "UAV-1", 5_000, "delivery-economy/1.0.0", Map.of("tier", "SMALL"));
        Map<String, Object> fine = fixture.service.applyMissionTransaction(VISITOR_A,
                "AIRSPACE_FINE:RUN-1:ZONE-1:1", "AIRSPACE_FINE", -20_000_000,
                "RUN-1", "ZONE-1", "UAV-1", 12_000, "delivery-economy/1.0.0", Map.of("durationSeconds", 30));

        assertThat(reward.get("replayed")).isEqualTo(false);
        assertThat(replay.get("replayed")).isEqualTo(true);
        assertThat(fixture.service.companyView(VISITOR_A).get("balanceMinor")).isEqualTo(0L);
        assertThat(fixture.service.missionTransactions(VISITOR_A, "RUN-1")).hasSize(2);
        @SuppressWarnings("unchecked") Map<String, Object> fineTransaction = (Map<String, Object>) fine.get("transaction");
        assertThat(fineTransaction.get("assessedAmountMinor")).isEqualTo(-20_000_000L);
        assertThat(fineTransaction.get("amountMinor")).isEqualTo(-10_050_000L);
        assertThat(fineTransaction.get("balanceBeforeMinor")).isEqualTo(10_050_000L);
        assertThat(fineTransaction.get("balanceAfterMinor")).isEqualTo(0L);
    }

    @Test
    void rewindSupersedesOnlyTheFutureBranchAndAllowsItsRewardsAgainInANewEpoch() {
        Fixture fixture = fixture(true);
        fixture.service.snapshot(VISITOR_A);
        fixture.service.applyMissionTransaction(VISITOR_A,
                "DELIVERY:RUN-REWIND:E0:BEFORE", "DELIVERY_REWARD", 100_000,
                "RUN-REWIND", "BEFORE", "UAV-1", 2_000, "delivery-economy/2.0.0", 0, Map.of());
        fixture.service.applyMissionTransaction(VISITOR_A,
                "DELIVERY:RUN-REWIND:E0:FUTURE", "DELIVERY_REWARD", 50_000,
                "RUN-REWIND", "FUTURE", "UAV-1", 5_000, "delivery-economy/2.0.0", 0, Map.of());
        fixture.service.applyMissionTransaction(VISITOR_A,
                "AIRSPACE_FINE:RUN-REWIND:E0:ZONE", "AIRSPACE_FINE", -20_000,
                "RUN-REWIND", "ZONE", "UAV-1", 6_000, "delivery-economy/2.0.0", 0, Map.of());

        Map<String, Object> rewind = fixture.service.rewindMissionTransactions(
                VISITOR_A, "RUN-REWIND", 0, 1, 4_000, "RWC-1", "rewind-1");

        assertThat(rewind.get("discardedTransactionCount")).isEqualTo(2);
        assertThat(rewind.get("adjustmentMinor")).isEqualTo(-30_000L);
        assertThat(fixture.service.companyView(VISITOR_A).get("balanceMinor")).isEqualTo(10_100_000L);
        assertThat(fixture.jdbc.queryForObject(
                "SELECT COUNT(*) FROM fleet_account_ledger WHERE run_id='RUN-REWIND' AND superseded_by_rewind_id='rewind-1'",
                Integer.class)).isEqualTo(2);
        assertThat(fixture.service.missionTransactions(VISITOR_A, "RUN-REWIND"))
                .extracting(item -> item.get("entryType"))
                .containsExactly("DELIVERY_REWARD", "REWIND_ADJUSTMENT");

        Map<String, Object> replayedReward = fixture.service.applyMissionTransaction(VISITOR_A,
                "DELIVERY:RUN-REWIND:E1:FUTURE", "DELIVERY_REWARD", 50_000,
                "RUN-REWIND", "FUTURE", "UAV-1", 5_000, "delivery-economy/2.0.0", 1, Map.of());
        assertThat(replayedReward.get("replayed")).isEqualTo(false);
        assertThat(fixture.service.companyView(VISITOR_A).get("balanceMinor")).isEqualTo(10_150_000L);
        assertThat(fixture.service.missionTransactions(VISITOR_A, "RUN-REWIND"))
                .filteredOn(item -> "DELIVERY_REWARD".equals(item.get("entryType")))
                .extracting(item -> item.get("referenceId"))
                .containsExactly("BEFORE", "FUTURE");
    }

    @Test
    void rewindRestoresBothBoundDeviceBatteriesAndRejectsUnavailableDevices() {
        Fixture fixture = fixture(true);
        Map<String, Object> initial = fixture.service.snapshot(VISITOR_A);
        String groundAssetId = assets(initial).stream().filter(asset -> "tricycle".equals(asset.get("typeId")))
                .map(asset -> String.valueOf(asset.get("assetId"))).findFirst().orElseThrow();
        String airAssetId = assets(initial).stream().filter(asset -> "smart-city-drone".equals(asset.get("typeId")))
                .map(asset -> String.valueOf(asset.get("assetId"))).findFirst().orElseThrow();
        FleetService.GroundVehicle ground = fixture.service.freezeGroundVehicle(VISITOR_A);
        FleetService.AirVehicle air = fixture.service.freezeAirVehicle(VISITOR_A);
        fixture.service.bindGroundVehicle(VISITOR_A, "RUN-ASSETS", groundAssetId, ground.stateVersion());
        fixture.service.bindAirVehicle(VISITOR_A, "RUN-ASSETS", airAssetId, air.stateVersion());
        fixture.service.consumeGroundDistance(VISITOR_A, "RUN-ASSETS", groundAssetId, 1_000, ground.fullRangeKm());
        fixture.service.consumeAirDistance(VISITOR_A, "RUN-ASSETS", airAssetId, 1_000, air.fullRangeKm());

        fixture.service.restoreRunAssets(VISITOR_A, "RUN-ASSETS", groundAssetId, 73.5, airAssetId, 64.25);

        Map<String, Object> restored = fixture.service.snapshot(VISITOR_A);
        assertThat(asset(restored, groundAssetId).get("batteryPercent")).isEqualTo(73.5);
        assertThat(asset(restored, airAssetId).get("batteryPercent")).isEqualTo(64.3);
        assertThat(fixture.jdbc.queryForObject("SELECT active_run_id FROM fleet_asset WHERE id=?", String.class, groundAssetId))
                .isEqualTo("RUN-ASSETS");
        assertThat(fixture.jdbc.queryForObject("SELECT active_run_id FROM fleet_asset WHERE id=?", String.class, airAssetId))
                .isEqualTo("RUN-ASSETS");

        fixture.service.releaseRunBinding(VISITOR_A, "RUN-ASSETS");
        fixture.service.changeStatus(VISITOR_A, groundAssetId, "garage-after-failure", "GARAGED");
        assertThatThrownBy(() -> fixture.service.restoreRunAssets(
                VISITOR_A, "RUN-ASSETS", groundAssetId, 73.5, airAssetId, 64.25))
                .isInstanceOf(DemoException.class).hasMessageContaining("已入库");
    }

    @Test
    void paidPurchaseDebitsAccountAndKeepsLedgerConserved() {
        Fixture fixture = fixture(true);
        fixture.service.snapshot(VISITOR_A);
        Map<String, Object> result = fixture.service.purchase(VISITOR_A, "buy-pickup", "ford-f350-utility", "NORMAL");
        Map<String, Object> fleet = fleet(result);

        assertThat(result.get("chargedMinor")).isEqualTo(3_600_000L);
        assertThat(company(fleet).get("balanceMinor")).isEqualTo(6_400_000L);
        assertThat(assets(fleet)).hasSize(3);
        assertThat(fixture.jdbc.queryForObject("SELECT SUM(amount_minor) FROM fleet_account_ledger WHERE visitor_hash=?", Long.class, VISITOR_A)).isEqualTo(6_400_000L);
    }

    @Test
    void purchaseIsIdempotentAndRejectsCommandReuseWithDifferentPayload() {
        Fixture fixture = fixture(true);
        Map<String, Object> first = fixture.service.purchase(VISITOR_A, "same-command", "tricycle", "NORMAL");
        Map<String, Object> replay = fixture.service.purchase(VISITOR_A, "same-command", "tricycle", "NORMAL");

        assertThat(replay.get("replayed")).isEqualTo(true);
        assertThat(replay.get("assetId")).isEqualTo(first.get("assetId"));
        assertThat(assets(fleet(replay))).hasSize(3);
        assertThatThrownBy(() -> fixture.service.purchase(VISITOR_A, "same-command", "ford-f350-utility", "NORMAL"))
                .isInstanceOf(DemoException.class).hasMessageContaining("不同");
    }

    @Test
    void rejectsInsufficientFundsWithoutChangingFleet() {
        Fixture fixture = fixture(true);
        fixture.service.purchase(VISITOR_A, "buy-peterbilt", "peterbilt-379-optimus-prime", "NORMAL");
        assertThatThrownBy(() -> fixture.service.purchase(VISITOR_A, "buy-drone", "smart-city-drone", "NORMAL"))
                .isInstanceOf(DemoException.class).hasMessageContaining("余额不足");
        Map<String, Object> snapshot = fixture.service.snapshot(VISITOR_A);
        assertThat(company(snapshot).get("balanceMinor")).isEqualTo(200_000L);
        assertThat(assets(snapshot)).hasSize(3);
    }

    @Test
    void omitsUnlistedAssetTypesFromFleetSnapshotsWithoutDeletingRecords() {
        Fixture fixture = fixture(true);
        fixture.service.snapshot(VISITOR_A);
        fixture.jdbc.update("INSERT INTO fleet_asset(id,visitor_hash,type_id,asset_status,acquisition_source,acquisition_price_minor) VALUES(?,?,?,?,?,?)",
                "FLT-UNLISTED-ASSET", VISITOR_A, "unlisted-ground-vehicle", "GARAGED", "PURCHASE", 1_800_000L);

        Map<String, Object> snapshot = fixture.service.snapshot(VISITOR_A);
        assertThat(catalog(snapshot))
                .extracting(type -> type.get("typeId"))
                .containsExactly("tricycle", "ford-f350-utility", "ural-truck-vehicle-only", "cybertruck-fun-size",
                        "peterbilt-379-optimus-prime", "smart-city-drone", "vtol-air-taxi");
        assertThat(catalog(snapshot)).filteredOn(type -> type.get("typeId").equals("cybertruck-fun-size"))
                .extracting(type -> type.get("priceMinor")).containsExactly(7_600_000L);
        assertThat(catalog(snapshot)).filteredOn(type -> type.get("typeId").equals("peterbilt-379-optimus-prime"))
                .extracting(type -> type.get("priceMinor")).containsExactly(9_800_000L);
        assertThat(assets(snapshot)).extracting(asset -> asset.get("typeId"))
                .doesNotContain("unlisted-ground-vehicle");
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM fleet_asset WHERE id='FLT-UNLISTED-ASSET'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void devPricingMustBeAllowedAndCreatesFreeOwnedAsset() {
        Fixture blocked = fixture(false);
        assertThatThrownBy(() -> blocked.service.purchase(VISITOR_A, "dev-blocked", "vtol-air-taxi", "DEV"))
                .isInstanceOfSatisfying(DemoException.class, error -> assertThat(error.status).isEqualTo(HttpStatus.BAD_REQUEST));

        Fixture allowed = fixture(true);
        Map<String, Object> result = allowed.service.purchase(VISITOR_A, "dev-free", "vtol-air-taxi", "DEV");
        assertThat(result.get("chargedMinor")).isEqualTo(0L);
        assertThat(company(fleet(result)).get("balanceMinor")).isEqualTo(10_000_000L);
        assertThat(assets(fleet(result))).anySatisfy(asset -> {
            assertThat(asset.get("typeId")).isEqualTo("vtol-air-taxi");
            assertThat(asset.get("acquisitionPriceMinor")).isEqualTo(0L);
        });
    }

    @Test
    void saleCreditsCatalogPriceAndPreservesAssetHistory() {
        Fixture fixture = fixture(true);
        Map<String, Object> purchase = fixture.service.purchase(VISITOR_A, "buy-sale-pickup", "ford-f350-utility", "NORMAL");
        String assetId = String.valueOf(purchase.get("assetId"));

        Map<String, Object> result = fixture.service.sell(VISITOR_A, "sell-pickup", assetId);
        Map<String, Object> fleet = fleet(result);

        assertThat(result.get("creditedMinor")).isEqualTo(3_600_000L);
        assertThat(company(fleet).get("balanceMinor")).isEqualTo(10_000_000L);
        assertThat(assets(fleet)).extracting(asset -> asset.get("assetId")).doesNotContain(assetId);
        assertThat(fixture.jdbc.queryForObject("SELECT sale_price_minor FROM fleet_asset WHERE id=?", Long.class, assetId))
                .isEqualTo(3_600_000L);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM fleet_asset WHERE id=? AND sold_at IS NOT NULL", Integer.class, assetId))
                .isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject("SELECT SUM(amount_minor) FROM fleet_account_ledger WHERE visitor_hash=?", Long.class, VISITOR_A))
                .isEqualTo(10_000_000L);
    }

    @Test
    void saleIsIdempotentAndCreditsOriginalPriceAfterDevPurchase() {
        Fixture fixture = fixture(true);
        Map<String, Object> purchase = fixture.service.purchase(VISITOR_A, "dev-vtol", "vtol-air-taxi", "DEV");
        String assetId = String.valueOf(purchase.get("assetId"));

        Map<String, Object> first = fixture.service.sell(VISITOR_A, "sell-vtol", assetId);
        Map<String, Object> replay = fixture.service.sell(VISITOR_A, "sell-vtol", assetId);

        assertThat(first.get("creditedMinor")).isEqualTo(8_800_000L);
        assertThat(replay.get("replayed")).isEqualTo(true);
        assertThat(replay.get("creditedMinor")).isEqualTo(8_800_000L);
        assertThat(company(fleet(replay)).get("balanceMinor")).isEqualTo(18_800_000L);
        assertThatThrownBy(() -> fixture.service.sell(VISITOR_A, "sell-vtol-again", assetId))
                .isInstanceOf(DemoException.class).hasMessageContaining("已经售出");
    }

    @Test
    void saleRejectsDeployedAndForeignAssets() {
        Fixture fixture = fixture(true);
        Map<String, Object> initial = fixture.service.snapshot(VISITOR_A);
        String assetId = assets(initial).stream()
                .filter(asset -> asset.get("typeId").equals("tricycle"))
                .map(asset -> String.valueOf(asset.get("assetId"))).findFirst().orElseThrow();
        assertThatThrownBy(() -> fixture.service.sell(VISITOR_A, "sell-deployed", assetId))
                .isInstanceOf(DemoException.class).hasMessageContaining("先召回");
        fixture.service.snapshot(VISITOR_B);
        assertThatThrownBy(() -> fixture.service.sell(VISITOR_B, "sell-foreign", assetId))
                .isInstanceOfSatisfying(DemoException.class, error -> assertThat(error.status).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void statusTransitionsAreOwnedAndLimitedToTwoRealStates() {
        Fixture fixture = fixture(true);
        Map<String, Object> initial = fixture.service.snapshot(VISITOR_A);
        String assetId = String.valueOf(assets(initial).get(0).get("assetId"));
        Map<String, Object> garaged = fixture.service.changeStatus(VISITOR_A, assetId, "garage-1", "GARAGED");
        assertThat(assets(fleet(garaged))).filteredOn(asset -> asset.get("assetId").equals(assetId))
                .extracting(asset -> asset.get("status")).containsExactly("GARAGED");
        Map<String, Object> deployed = fixture.service.changeStatus(VISITOR_A, assetId, "deploy-1", "DEPLOYED");
        assertThat(assets(fleet(deployed))).filteredOn(asset -> asset.get("assetId").equals(assetId))
                .extracting(asset -> asset.get("status")).containsExactly("DEPLOYED");
        Map<String, Object> replay = fixture.service.changeStatus(VISITOR_A, assetId, "deploy-1", "DEPLOYED");
        assertThat(replay.get("replayed")).isEqualTo(true);
        assertThatThrownBy(() -> fixture.service.changeStatus(VISITOR_A, assetId, "deploy-2", "DEPLOYED"))
                .isInstanceOf(DemoException.class).hasMessageContaining("已经处于");
        assertThatThrownBy(() -> fixture.service.changeStatus(VISITOR_A, assetId, "invalid", "MAINTENANCE"))
                .isInstanceOf(DemoException.class).hasMessageContaining("GARAGED");
        fixture.service.snapshot(VISITOR_B);
        assertThatThrownBy(() -> fixture.service.changeStatus(VISITOR_B, assetId, "steal", "GARAGED"))
                .isInstanceOfSatisfying(DemoException.class, error -> assertThat(error.status).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void latestAirDeploymentControlsIndependentRouteCapability() {
        Fixture fixture = fixture(true);
        Map<String, Object> initial = fixture.service.snapshot(VISITOR_A);
        String starterDroneId = assets(initial).stream()
                .filter(asset -> asset.get("typeId").equals("smart-city-drone"))
                .map(asset -> String.valueOf(asset.get("assetId"))).findFirst().orElseThrow();
        FleetService.AirVehicle frozen = fixture.service.freezeAirVehicle(VISITOR_A);
        assertThat(fixture.service.latestDeployedTypeId(VISITOR_A, "AIR")).isEqualTo("smart-city-drone");
        assertThat(fixture.service.usesIndependentAirRoute(VISITOR_A)).isFalse();

        Map<String, Object> purchase = fixture.service.purchase(VISITOR_A, "buy-independent-air", "vtol-air-taxi", "DEV");
        String vtolId = String.valueOf(purchase.get("assetId"));
        Map<String, Object> switched = fixture.service.changeStatus(VISITOR_A, vtolId, "deploy-independent-air", "DEPLOYED");
        assertThat(switched.get("autoRecalledAssetIds")).isEqualTo(List.of(starterDroneId));
        assertThat(asset(fleet(switched), starterDroneId)).containsEntry("status", "GARAGED");

        assertThat(fixture.service.latestDeployedTypeId(VISITOR_A, "AIR")).isEqualTo("vtol-air-taxi");
        assertThat(fixture.service.usesIndependentAirRoute(VISITOR_A)).isTrue();
        assertThatThrownBy(() -> fixture.service.bindAirVehicle(VISITOR_A, "RUN-STALE-AIR", starterDroneId, frozen.stateVersion()))
                .isInstanceOf(DemoException.class).hasMessageContaining("重新生成");
        fixture.service.changeStatus(VISITOR_A, vtolId, "recall-vtol", "GARAGED");
        assertThatThrownBy(() -> fixture.service.freezeAirVehicle(VISITOR_A))
                .isInstanceOf(DemoException.class).hasMessageContaining("没有空中运输设备出站");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> assets(Map<String, Object> snapshot) { return (List<Map<String, Object>>) snapshot.get("assets"); }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> catalog(Map<String, Object> snapshot) { return (List<Map<String, Object>>) snapshot.get("catalog"); }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> company(Map<String, Object> snapshot) { return (Map<String, Object>) snapshot.get("company"); }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> fleet(Map<String, Object> result) { return (Map<String, Object>) result.get("fleet"); }

    private static Map<String, Object> asset(Map<String, Object> snapshot, String assetId) {
        return assets(snapshot).stream().filter(item -> assetId.equals(item.get("assetId"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static void assertGroundType(Map<String, Object> snapshot, String typeId, List<Integer> ratings,
                                         double speedKph, double fullRangeKm, double cargoMultiplier) {
        Map<String, Object> type = catalog(snapshot).stream().filter(item -> typeId.equals(item.get("typeId"))).findFirst().orElseThrow();
        Map<String, Integer> actualRatings = (Map<String, Integer>) type.get("demoRatings");
        assertThat(List.of(actualRatings.get("capacity"), actualRatings.get("agility"), actualRatings.get("speed"), actualRatings.get("endurance")))
                .isEqualTo(ratings);
        Map<String, Object> gameplay = (Map<String, Object>) type.get("gameplayStats");
        assertThat(((Number) gameplay.get("speedKph")).doubleValue()).isEqualTo(speedKph);
        assertThat(((Number) gameplay.get("fullRangeKm")).doubleValue()).isEqualTo(fullRangeKm);
        assertThat(((Number) gameplay.get("cargoMultiplier")).doubleValue()).isEqualTo(cargoMultiplier);
    }

    @SuppressWarnings("unchecked")
    private static void assertAirType(Map<String, Object> snapshot, String typeId, List<Integer> ratings,
                                      double speedKph, double fullRangeKm, double cargoMultiplier,
                                      double maneuverDelaySeconds) {
        assertGroundType(snapshot, typeId, ratings, speedKph, fullRangeKm, cargoMultiplier);
        Map<String, Object> type = catalog(snapshot).stream().filter(item -> typeId.equals(item.get("typeId"))).findFirst().orElseThrow();
        Map<String, Object> gameplay = (Map<String, Object>) type.get("gameplayStats");
        assertThat(((Number) gameplay.get("maneuverDelaySeconds")).doubleValue()).isEqualTo(maneuverDelaySeconds);
    }

    private static Fixture fixture(boolean devAllowed) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:fleet_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE fleet_company(visitor_hash CHAR(64) PRIMARY KEY,balance_minor BIGINT NOT NULL,currency CHAR(3) NOT NULL DEFAULT 'CNY',created_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3),updated_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3))");
        jdbc.execute("CREATE TABLE fleet_asset(id VARCHAR(48) PRIMARY KEY,visitor_hash CHAR(64) NOT NULL,type_id VARCHAR(64) NOT NULL,asset_status VARCHAR(16) NOT NULL,battery_basis_points DECIMAL(9,4) NOT NULL DEFAULT 10000.0000,charging_from_basis_points DECIMAL(9,4),charging_started_at TIMESTAMP(3),active_run_id VARCHAR(40),state_version BIGINT NOT NULL DEFAULT 0,acquisition_source VARCHAR(24) NOT NULL,acquisition_price_minor BIGINT NOT NULL,sale_price_minor BIGINT,initial_key VARCHAR(32),acquired_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3),sold_at TIMESTAMP(3),updated_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3),UNIQUE(visitor_hash,initial_key))");
        jdbc.execute("CREATE TABLE fleet_account_ledger(id VARCHAR(48) PRIMARY KEY,visitor_hash CHAR(64) NOT NULL,entry_key VARCHAR(96) NOT NULL,entry_type VARCHAR(32) NOT NULL,amount_minor BIGINT NOT NULL,balance_before_minor BIGINT, balance_after_minor BIGINT NOT NULL,assessed_amount_minor BIGINT,asset_id VARCHAR(48),run_id VARCHAR(40),reference_id VARCHAR(96),actor_id VARCHAR(64),simulation_time_ms BIGINT,timeline_epoch INT NOT NULL DEFAULT 0,superseded_by_rewind_id VARCHAR(64),rule_version VARCHAR(40),metadata_json JSON,created_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3),UNIQUE(visitor_hash,entry_key))");
        jdbc.execute("CREATE TABLE fleet_command(visitor_hash CHAR(64) NOT NULL,command_id VARCHAR(72) NOT NULL,command_type VARCHAR(24) NOT NULL,request_fingerprint VARCHAR(180) NOT NULL,target_asset_id VARCHAR(48),amount_minor BIGINT NOT NULL,created_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3),PRIMARY KEY(visitor_hash,command_id))");
        return new Fixture(jdbc, new FleetService(jdbc, new FleetCatalog(), devAllowed));
    }

    private record Fixture(JdbcTemplate jdbc, FleetService service) {}
}
