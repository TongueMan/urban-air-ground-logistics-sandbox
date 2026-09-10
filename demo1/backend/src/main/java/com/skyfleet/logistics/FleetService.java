package com.skyfleet.logistics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class FleetService {
    static final long INITIAL_BALANCE_MINOR = 10_000_000L;
    static final double FULL_BATTERY_BASIS_POINTS = 10_000.0;
    static final long CHARGE_DURATION_SECONDS = 60;
    private final JdbcTemplate jdbc;
    private final FleetCatalog catalog;
    private final boolean devPricingEnabled;

    public FleetService(JdbcTemplate jdbc, FleetCatalog catalog,
                        @Value("${demo.fleet.dev-pricing-enabled:false}") boolean devPricingEnabled) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.devPricingEnabled = devPricingEnabled;
    }

    @Transactional
    public Map<String, Object> snapshot(String visitorHash) {
        initialize(visitorHash);
        return snapshotInternal(visitorHash);
    }

    @Transactional
    public Map<String, Object> purchase(String visitorHash, String commandId, String typeId, String pricingMode) {
        String safeCommandId = commandId(commandId);
        String safeTypeId = required(typeId, "请选择采购车型");
        String safePricingMode = required(pricingMode, "请选择价格模式").toUpperCase(Locale.ROOT);
        if (!List.of("NORMAL", "DEV").contains(safePricingMode)) throw badRequest("价格模式无效");
        if ("DEV".equals(safePricingMode) && !devPricingEnabled) throw badRequest("当前环境未开放开发定价");
        FleetCatalog.Type type;
        try { type = catalog.require(safeTypeId); }
        catch (IllegalArgumentException error) { throw badRequest(error.getMessage()); }

        initialize(visitorHash);
        lockCompany(visitorHash);
        String fingerprint = "PURCHASE|" + safeTypeId + "|" + safePricingMode;
        ExistingCommand existing = command(visitorHash, safeCommandId);
        if (existing != null) return replay(visitorHash, safeCommandId, fingerprint, existing, "PURCHASE_REPLAYED");

        long chargedMinor = "DEV".equals(safePricingMode) ? 0 : type.priceMinor();
        long balance = companyBalance(visitorHash);
        if (balance < chargedMinor) {
            throw new DemoException(HttpStatus.CONFLICT,
                    "余额不足：当前可用 ¥" + money(balance) + "，采购需要 ¥" + money(chargedMinor));
        }
        String assetId = id("FLT");
        long nextBalance = balance - chargedMinor;
        if (chargedMinor > 0) {
            int updated = jdbc.update("UPDATE fleet_company SET balance_minor=?,updated_at=CURRENT_TIMESTAMP(3) WHERE visitor_hash=? AND balance_minor=?",
                    nextBalance, visitorHash, balance);
            if (updated != 1) throw new DemoException(HttpStatus.CONFLICT, "账户余额已经变化，请刷新后重试");
        }
        jdbc.update("INSERT INTO fleet_asset(id,visitor_hash,type_id,asset_status,acquisition_source,acquisition_price_minor) VALUES(?,?,?,?,?,?)",
                assetId, visitorHash, safeTypeId, "GARAGED", "DEV".equals(safePricingMode) ? "DEV_PURCHASE" : "PURCHASE", chargedMinor);
        jdbc.update("INSERT INTO fleet_account_ledger(id,visitor_hash,entry_key,entry_type,amount_minor,balance_after_minor,asset_id) VALUES(?,?,?,?,?,?,?)",
                id("LED"), visitorHash, "PURCHASE:" + safeCommandId, "PURCHASE", -chargedMinor, nextBalance, assetId);
        jdbc.update("INSERT INTO fleet_command(visitor_hash,command_id,command_type,request_fingerprint,target_asset_id,amount_minor) VALUES(?,?,?,?,?,?)",
                visitorHash, safeCommandId, "PURCHASE", fingerprint, assetId, chargedMinor);
        return result(visitorHash, safeCommandId, "PURCHASED", false, assetId, chargedMinor);
    }

    @Transactional
    public Map<String, Object> sell(String visitorHash, String commandId, String assetId) {
        String safeCommandId = commandId(commandId);
        String safeAssetId = required(assetId, "设备编号不能为空");

        initialize(visitorHash);
        lockCompany(visitorHash);
        String fingerprint = "SALE|" + safeAssetId;
        ExistingCommand existing = command(visitorHash, safeCommandId);
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) {
                throw new DemoException(HttpStatus.CONFLICT, "commandId 已用于不同的车队操作");
            }
            return saleResult(visitorHash, safeCommandId, "SALE_REPLAYED", true, existing.assetId(), existing.amountMinor());
        }

        List<SaleAsset> rows = jdbc.query("SELECT type_id,asset_status,sold_at,active_run_id FROM fleet_asset WHERE id=? AND visitor_hash=? FOR UPDATE",
                (rs, row) -> new SaleAsset(rs.getString("type_id"), rs.getString("asset_status"), rs.getTimestamp("sold_at") != null, rs.getString("active_run_id")),
                safeAssetId, visitorHash);
        if (rows.isEmpty()) throw new DemoException(HttpStatus.NOT_FOUND, "设备不存在或不属于当前公司");
        SaleAsset asset = rows.get(0);
        if (asset.sold()) throw new DemoException(HttpStatus.CONFLICT, "设备已经售出");
        if (asset.activeRunId() != null) throw new DemoException(HttpStatus.CONFLICT, "设备正在执行任务，结束任务后才能出售");
        if (!"GARAGED".equals(asset.status())) throw new DemoException(HttpStatus.CONFLICT, "已出站设备不能出售，请先召回车库");

        FleetCatalog.Type type;
        try { type = catalog.require(asset.typeId()); }
        catch (IllegalArgumentException error) { throw badRequest("该车型已退役，暂不支持出售"); }
        long creditedMinor = type.priceMinor();
        long balance = companyBalance(visitorHash);
        long nextBalance;
        try { nextBalance = Math.addExact(balance, creditedMinor); }
        catch (ArithmeticException error) { throw new DemoException(HttpStatus.CONFLICT, "公司账户余额超出允许范围"); }

        int accountUpdated = jdbc.update("UPDATE fleet_company SET balance_minor=?,updated_at=CURRENT_TIMESTAMP(3) WHERE visitor_hash=? AND balance_minor=?",
                nextBalance, visitorHash, balance);
        if (accountUpdated != 1) throw new DemoException(HttpStatus.CONFLICT, "账户余额已经变化，请刷新后重试");
        int assetUpdated = jdbc.update("UPDATE fleet_asset SET sale_price_minor=?,sold_at=CURRENT_TIMESTAMP(3),updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND visitor_hash=? AND sold_at IS NULL",
                creditedMinor, safeAssetId, visitorHash);
        if (assetUpdated != 1) throw new DemoException(HttpStatus.CONFLICT, "设备状态已经变化，请刷新后重试");
        jdbc.update("INSERT INTO fleet_account_ledger(id,visitor_hash,entry_key,entry_type,amount_minor,balance_after_minor,asset_id) VALUES(?,?,?,?,?,?,?)",
                id("LED"), visitorHash, "SALE:" + safeCommandId, "SALE", creditedMinor, nextBalance, safeAssetId);
        jdbc.update("INSERT INTO fleet_command(visitor_hash,command_id,command_type,request_fingerprint,target_asset_id,amount_minor) VALUES(?,?,?,?,?,?)",
                visitorHash, safeCommandId, "SALE", fingerprint, safeAssetId, creditedMinor);
        return saleResult(visitorHash, safeCommandId, "SOLD", false, safeAssetId, creditedMinor);
    }

    @Transactional
    public Map<String, Object> changeStatus(String visitorHash, String assetId, String commandId, String targetStatus) {
        String safeCommandId = commandId(commandId);
        String safeAssetId = required(assetId, "设备编号不能为空");
        String safeTarget = required(targetStatus, "目标状态不能为空").toUpperCase(Locale.ROOT);
        if (!List.of("GARAGED", "DEPLOYED").contains(safeTarget)) throw badRequest("设备状态只允许 GARAGED 或 DEPLOYED");

        initialize(visitorHash);
        lockCompany(visitorHash);
        String fingerprint = "STATUS|" + safeAssetId + "|" + safeTarget;
        ExistingCommand existing = command(visitorHash, safeCommandId);
        if (existing != null) return replay(visitorHash, safeCommandId, fingerprint, existing, "STATUS_REPLAYED");

        List<AssetState> assets = jdbc.query("SELECT id,type_id,asset_status,battery_basis_points,charging_from_basis_points,charging_started_at,active_run_id,state_version,updated_at FROM fleet_asset WHERE id=? AND visitor_hash=? AND sold_at IS NULL FOR UPDATE",
                (rs, row) -> assetState(rs), safeAssetId, visitorHash);
        if (assets.isEmpty()) throw new DemoException(HttpStatus.NOT_FOUND, "设备不存在或不属于当前公司");
        AssetState asset = assets.get(0);
        String current = asset.status();
        if (current.equals(safeTarget)) throw new DemoException(HttpStatus.CONFLICT, "设备已经处于 " + safeTarget + " 状态");
        if (!("GARAGED".equals(current) && "DEPLOYED".equals(safeTarget))
                && !("DEPLOYED".equals(current) && "GARAGED".equals(safeTarget))) {
            throw new DemoException(HttpStatus.CONFLICT, "不允许从 " + current + " 切换到 " + safeTarget);
        }
        if (asset.activeRunId() != null) throw new DemoException(HttpStatus.CONFLICT, "设备正在执行任务，请先结束当前任务");
        if ("GARAGED".equals(safeTarget)) {
            double battery = clampBattery(asset.batteryBasisPoints());
            jdbc.update("UPDATE fleet_asset SET asset_status='GARAGED',battery_basis_points=?,charging_from_basis_points=?,charging_started_at=?,active_run_id=NULL,state_version=state_version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND visitor_hash=?",
                    battery, battery >= FULL_BATTERY_BASIS_POINTS ? null : battery,
                    battery >= FULL_BATTERY_BASIS_POINTS ? null : Timestamp.from(Instant.now()), safeAssetId, visitorHash);
        } else {
            double battery = effectiveBattery(asset, Instant.now());
            jdbc.update("UPDATE fleet_asset SET asset_status='DEPLOYED',battery_basis_points=?,charging_from_basis_points=NULL,charging_started_at=NULL,active_run_id=NULL,state_version=state_version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND visitor_hash=?",
                    battery, safeAssetId, visitorHash);
        }
        jdbc.update("INSERT INTO fleet_command(visitor_hash,command_id,command_type,request_fingerprint,target_asset_id,amount_minor) VALUES(?,?,?,?,?,0)",
                visitorHash, safeCommandId, "STATUS", fingerprint, safeAssetId);
        return result(visitorHash, safeCommandId, "STATUS_CHANGED", false, safeAssetId, 0);
    }

    @Transactional
    public GroundVehicle freezeGroundVehicle(String visitorHash) {
        initialize(visitorHash);
        settleCompletedCharges(visitorHash);
        return latestGroundVehicle(visitorHash, false);
    }

    @Transactional
    public AirVehicle freezeAirVehicle(String visitorHash) {
        initialize(visitorHash);
        settleCompletedCharges(visitorHash);
        return latestAirVehicle(visitorHash, false);
    }

    @Transactional
    public GroundVehicle bindGroundVehicle(String visitorHash, String runId, String assetId, long expectedStateVersion) {
        initialize(visitorHash);
        List<AssetState> rows = jdbc.query("SELECT id,type_id,asset_status,battery_basis_points,charging_from_basis_points,charging_started_at,active_run_id,state_version,updated_at FROM fleet_asset WHERE id=? AND visitor_hash=? AND sold_at IS NULL FOR UPDATE",
                (rs, row) -> assetState(rs), assetId, visitorHash);
        if (rows.isEmpty()) throw new DemoException(HttpStatus.CONFLICT, "任务绑定车辆已不存在，请重新生成方案");
        AssetState state = rows.get(0);
        GroundVehicle latest = latestGroundVehicle(visitorHash, true);
        if (!assetId.equals(latest.assetId()) || !"DEPLOYED".equals(state.status()) || state.stateVersion() != expectedStateVersion)
            throw new DemoException(HttpStatus.CONFLICT, "出站车辆或车辆状态已经变化，请重新生成任务方案");
        if (state.activeRunId() != null && !runId.equals(state.activeRunId()))
            throw new DemoException(HttpStatus.CONFLICT, "该车辆正在执行其他任务");
        int updated = jdbc.update("UPDATE fleet_asset SET active_run_id=?,state_version=state_version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND visitor_hash=? AND active_run_id IS NULL AND state_version=?",
                runId, assetId, visitorHash, expectedStateVersion);
        if (updated != 1) throw new DemoException(HttpStatus.CONFLICT, "车辆状态已经变化，请重新生成任务方案");
        return groundVehicle(new AssetState(state.id(), state.typeId(), state.status(), state.batteryBasisPoints(), null,
                null, runId, state.stateVersion() + 1, Instant.now()));
    }

    @Transactional
    public AirVehicle bindAirVehicle(String visitorHash, String runId, String assetId, long expectedStateVersion) {
        initialize(visitorHash);
        List<AssetState> rows = jdbc.query("SELECT id,type_id,asset_status,battery_basis_points,charging_from_basis_points,charging_started_at,active_run_id,state_version,updated_at FROM fleet_asset WHERE id=? AND visitor_hash=? AND sold_at IS NULL FOR UPDATE",
                (rs, row) -> assetState(rs), assetId, visitorHash);
        if (rows.isEmpty()) throw new DemoException(HttpStatus.CONFLICT, "任务绑定空中设备已不存在，请重新生成方案");
        AssetState state = rows.get(0);
        AirVehicle latest = latestAirVehicle(visitorHash, true);
        if (!assetId.equals(latest.assetId()) || !"DEPLOYED".equals(state.status()) || state.stateVersion() != expectedStateVersion)
            throw new DemoException(HttpStatus.CONFLICT, "出站空中设备或设备状态已经变化，请重新生成任务方案");
        if (state.activeRunId() != null && !runId.equals(state.activeRunId()))
            throw new DemoException(HttpStatus.CONFLICT, "该空中设备正在执行其他任务");
        int updated = jdbc.update("UPDATE fleet_asset SET active_run_id=?,state_version=state_version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND visitor_hash=? AND active_run_id IS NULL AND state_version=?",
                runId, assetId, visitorHash, expectedStateVersion);
        if (updated != 1) throw new DemoException(HttpStatus.CONFLICT, "空中设备状态已经变化，请重新生成任务方案");
        return airVehicle(new AssetState(state.id(), state.typeId(), state.status(), state.batteryBasisPoints(), null,
                null, runId, state.stateVersion() + 1, Instant.now()));
    }

    @Transactional
    public BatteryUse consumeGroundDistance(String visitorHash, String runId, String assetId,
                                            double requestedMeters, double fullRangeKm) {
        return consumeDistance(visitorHash, runId, assetId, requestedMeters, fullRangeKm);
    }

    @Transactional
    public BatteryUse consumeAirDistance(String visitorHash, String runId, String assetId,
                                         double requestedMeters, double fullRangeKm) {
        return consumeDistance(visitorHash, runId, assetId, requestedMeters, fullRangeKm);
    }

    @Transactional
    public BatteryUse consumeAirDistance(String visitorHash, String runId, String assetId,
                                         double requestedMeters, double fullRangeKm, double energyMultiplier) {
        return consumeDistance(visitorHash, runId, assetId, requestedMeters, fullRangeKm, energyMultiplier);
    }

    private BatteryUse consumeDistance(String visitorHash, String runId, String assetId,
                                       double requestedMeters, double fullRangeKm) {
        return consumeDistance(visitorHash, runId, assetId, requestedMeters, fullRangeKm, 1);
    }

    private BatteryUse consumeDistance(String visitorHash, String runId, String assetId,
                                       double requestedMeters, double fullRangeKm, double energyMultiplier) {
        double multiplier = Math.max(1, energyMultiplier);
        if (requestedMeters <= 0) return new BatteryUse(0, currentBatteryForRun(visitorHash, runId, assetId), false, 0, 0);
        List<AssetState> rows = jdbc.query("SELECT id,type_id,asset_status,battery_basis_points,charging_from_basis_points,charging_started_at,active_run_id,state_version,updated_at FROM fleet_asset WHERE id=? AND visitor_hash=? AND sold_at IS NULL FOR UPDATE",
                (rs, row) -> assetState(rs), assetId, visitorHash);
        if (rows.isEmpty()) throw new DemoException(HttpStatus.CONFLICT, "任务设备不存在");
        AssetState state = rows.get(0);
        if (!"DEPLOYED".equals(state.status()) || !runId.equals(state.activeRunId()))
            throw new DemoException(HttpStatus.CONFLICT, "任务设备不再处于可运行状态");
        double battery = clampBattery(state.batteryBasisPoints());
        double rangeMeters = Math.max(1, fullRangeKm * 1000.0);
        double availableEquivalentMeters = battery / FULL_BATTERY_BASIS_POINTS * rangeMeters;
        double movedMeters = Math.min(Math.max(0, requestedMeters), availableEquivalentMeters / multiplier);
        double equivalentMeters = movedMeters * multiplier;
        double remaining = clampBattery(battery - equivalentMeters / rangeMeters * FULL_BATTERY_BASIS_POINTS);
        if (remaining < .0001) remaining = 0;
        jdbc.update("UPDATE fleet_asset SET battery_basis_points=?,state_version=state_version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND visitor_hash=? AND active_run_id=?",
                remaining, assetId, visitorHash, runId);
        double extraBatteryPercent = Math.max(0, equivalentMeters - movedMeters) / rangeMeters * 100;
        return new BatteryUse(movedMeters, remaining / 100.0, remaining <= 0, equivalentMeters, extraBatteryPercent);
    }

    @Transactional
    public void releaseRunBinding(String visitorHash, String runId) {
        jdbc.update("UPDATE fleet_asset SET active_run_id=NULL,state_version=state_version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE visitor_hash=? AND active_run_id=?",
                visitorHash, runId);
    }

    @Transactional
    public void releaseInactiveBindings() {
        List<Map<String, Object>> bindings = jdbc.query("SELECT visitor_hash,active_run_id FROM fleet_asset WHERE active_run_id IS NOT NULL",
                (rs, row) -> Map.of("visitorHash", rs.getString("visitor_hash"), "runId", rs.getString("active_run_id")));
        for (Map<String, Object> binding : bindings) {
            Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM demo_session WHERE id=? AND status IN ('RUNNING','QUEUED')",
                    Integer.class, binding.get("runId"));
            if (active == null || active == 0) releaseRunBinding(String.valueOf(binding.get("visitorHash")), String.valueOf(binding.get("runId")));
        }
    }

    /**
     * Applies one authoritative mission-side balance change. The entry key is
     * the idempotency boundary shared by rewards, airspace fines and SSE retry.
     */
    @Transactional
    public Map<String, Object> applyMissionTransaction(String visitorHash, String entryKey, String entryType,
                                                        long assessedDeltaMinor, String runId, String referenceId,
                                                        String actorId, long simulationTimeMs, String ruleVersion,
                                                        Map<String, Object> metadata) {
        return applyMissionTransaction(visitorHash, entryKey, entryType, assessedDeltaMinor, runId, referenceId,
                actorId, simulationTimeMs, ruleVersion, 0, metadata);
    }

    @Transactional
    public Map<String, Object> applyMissionTransaction(String visitorHash, String entryKey, String entryType,
                                                        long assessedDeltaMinor, String runId, String referenceId,
                                                        String actorId, long simulationTimeMs, String ruleVersion,
                                                        int timelineEpoch, Map<String, Object> metadata) {
        String safeKey = required(entryKey, "账本键不能为空");
        String safeType = required(entryType, "账本类型不能为空").toUpperCase(Locale.ROOT);
        if (safeKey.length() > 96 || safeType.length() > 32) throw badRequest("任务账本标识过长");
        initialize(visitorHash);
        lockCompany(visitorHash);
        List<Map<String, Object>> existing = jdbc.query(
                "SELECT * FROM fleet_account_ledger WHERE visitor_hash=? AND entry_key=?",
                (rs, row) -> ledgerView(rs), visitorHash, safeKey);
        if (!existing.isEmpty()) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("transaction", existing.get(0)); response.put("replayed", true);
            response.put("company", companyViewInternal(visitorHash));
            return response;
        }

        long balance = companyBalance(visitorHash);
        long appliedDelta = assessedDeltaMinor < 0 ? -Math.min(balance, Math.abs(assessedDeltaMinor)) : assessedDeltaMinor;
        long nextBalance;
        try { nextBalance = Math.addExact(balance, appliedDelta); }
        catch (ArithmeticException error) { throw new DemoException(HttpStatus.CONFLICT, "公司账户余额超出允许范围"); }
        int updated = jdbc.update(
                "UPDATE fleet_company SET balance_minor=?,updated_at=CURRENT_TIMESTAMP(3) WHERE visitor_hash=? AND balance_minor=?",
                nextBalance, visitorHash, balance);
        if (updated != 1) throw new DemoException(HttpStatus.CONFLICT, "账户余额已经变化，请稍后重试");
        String ledgerId = id("LED");
        String metadataJson;
        try { metadataJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(metadata == null ? Map.of() : metadata); }
        catch (Exception error) { throw new IllegalArgumentException("任务账本明细无法序列化", error); }
        jdbc.update("INSERT INTO fleet_account_ledger(id,visitor_hash,entry_key,entry_type,amount_minor,balance_before_minor,balance_after_minor,assessed_amount_minor,run_id,reference_id,actor_id,simulation_time_ms,timeline_epoch,rule_version,metadata_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                ledgerId, visitorHash, safeKey, safeType, appliedDelta, balance, nextBalance, assessedDeltaMinor,
                runId, referenceId, actorId, simulationTimeMs, timelineEpoch, ruleVersion, metadataJson);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("transaction", missionTransactions(visitorHash, runId).stream()
                .filter(item -> ledgerId.equals(item.get("id"))).findFirst().orElse(Map.of()));
        response.put("replayed", false); response.put("company", companyViewInternal(visitorHash));
        return response;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> missionTransactions(String visitorHash, String runId) {
        if (runId == null || runId.isBlank()) return List.of();
        return jdbc.query("SELECT * FROM fleet_account_ledger WHERE visitor_hash=? AND run_id=? AND superseded_by_rewind_id IS NULL ORDER BY simulation_time_ms,created_at,id",
                (rs, row) -> ledgerView(rs), visitorHash, runId);
    }

    @Transactional
    public Map<String, Object> rewindMissionTransactions(String visitorHash, String runId, int sourceEpoch,
                                                          int targetEpoch, long checkpointSimulationMs,
                                                          String checkpointId, String rewindId) {
        initialize(visitorHash);
        lockCompany(visitorHash);
        List<Map<String, Object>> discarded = jdbc.query(
                "SELECT * FROM fleet_account_ledger WHERE visitor_hash=? AND run_id=? AND timeline_epoch=? AND simulation_time_ms>=? AND superseded_by_rewind_id IS NULL FOR UPDATE",
                (rs, row) -> ledgerView(rs), visitorHash, runId, sourceEpoch, checkpointSimulationMs);
        long discardedDelta = discarded.stream()
                .mapToLong(item -> ((Number) item.getOrDefault("amountMinor", 0)).longValue()).sum();
        long balance = companyBalance(visitorHash);
        long restoredBalance;
        try { restoredBalance = Math.subtractExact(balance, discardedDelta); }
        catch (ArithmeticException error) { throw new DemoException(HttpStatus.CONFLICT, "回溯后的账户余额超出允许范围"); }
        if (restoredBalance < 0)
            throw new DemoException(HttpStatus.CONFLICT, "检查点后的奖励已被使用，当前无法完整恢复账户余额");
        jdbc.update("UPDATE fleet_account_ledger SET superseded_by_rewind_id=? WHERE visitor_hash=? AND run_id=? AND timeline_epoch=? AND simulation_time_ms>=? AND superseded_by_rewind_id IS NULL",
                rewindId, visitorHash, runId, sourceEpoch, checkpointSimulationMs);
        if (discardedDelta != 0) {
            int updated = jdbc.update("UPDATE fleet_company SET balance_minor=?,updated_at=CURRENT_TIMESTAMP(3) WHERE visitor_hash=? AND balance_minor=?",
                    restoredBalance, visitorHash, balance);
            if (updated != 1) throw new DemoException(HttpStatus.CONFLICT, "账户余额已经变化，请稍后重试");
            String metadataJson = "{\"reason\":\"TIMELINE_REWIND\"}";
            jdbc.update("INSERT INTO fleet_account_ledger(id,visitor_hash,entry_key,entry_type,amount_minor,balance_before_minor,balance_after_minor,assessed_amount_minor,run_id,reference_id,simulation_time_ms,timeline_epoch,rule_version,metadata_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id("LED"), visitorHash, "REWIND:" + runId + ":" + checkpointId, "REWIND_ADJUSTMENT",
                    -discardedDelta, balance, restoredBalance, -discardedDelta, runId, checkpointId,
                    checkpointSimulationMs, targetEpoch, "timeline-rewind/1.0.0", metadataJson);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("discardedTransactionCount", discarded.size());
        result.put("adjustmentMinor", -discardedDelta);
        result.put("balanceMinor", restoredBalance);
        return result;
    }

    @Transactional
    public void restoreRunAssets(String visitorHash, String runId,
                                 String groundAssetId, double groundBatteryPercent,
                                 String airAssetId, double airBatteryPercent) {
        initialize(visitorHash);
        restoreRunAsset(visitorHash, runId, groundAssetId, groundBatteryPercent);
        restoreRunAsset(visitorHash, runId, airAssetId, airBatteryPercent);
    }

    private void restoreRunAsset(String visitorHash, String runId, String assetId, double batteryPercent) {
        if (assetId == null || assetId.isBlank()) return;
        List<AssetState> rows = jdbc.query("SELECT id,type_id,asset_status,battery_basis_points,charging_from_basis_points,charging_started_at,active_run_id,state_version,updated_at FROM fleet_asset WHERE id=? AND visitor_hash=? AND sold_at IS NULL FOR UPDATE",
                (rs, row) -> assetState(rs), assetId, visitorHash);
        if (rows.isEmpty()) throw new DemoException(HttpStatus.CONFLICT, "检查点绑定设备已不存在，无法恢复任务");
        AssetState state = rows.get(0);
        if (!"DEPLOYED".equals(state.status())) throw new DemoException(HttpStatus.CONFLICT, "检查点绑定设备已入库，无法恢复任务");
        if (state.activeRunId() != null && !runId.equals(state.activeRunId()))
            throw new DemoException(HttpStatus.CONFLICT, "检查点绑定设备正在执行其他任务");
        double battery = clampBattery(batteryPercent * 100.0);
        jdbc.update("UPDATE fleet_asset SET battery_basis_points=?,charging_from_basis_points=NULL,charging_started_at=NULL,active_run_id=?,state_version=state_version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND visitor_hash=?",
                battery, runId, assetId, visitorHash);
    }

    @Transactional
    public Map<String, Object> companyView(String visitorHash) {
        initialize(visitorHash);
        return companyViewInternal(visitorHash);
    }

    String latestDeployedTypeId(String visitorHash, String category) {
        String safeCategory = String.valueOf(category == null ? "" : category).toUpperCase(Locale.ROOT);
        return jdbc.query("SELECT type_id FROM fleet_asset WHERE visitor_hash=? AND asset_status='DEPLOYED' AND sold_at IS NULL ORDER BY updated_at DESC,id DESC",
                        (rs, row) -> rs.getString(1), visitorHash).stream()
                .filter(typeId -> {
                    try { return Objects.equals(catalog.require(typeId).category(), safeCategory); }
                    catch (IllegalArgumentException ignored) { return false; }
                })
                .findFirst().orElse(null);
    }

    boolean usesIndependentAirRoute(String visitorHash) {
        return "vtol-air-taxi".equals(latestDeployedTypeId(visitorHash, "AIR"));
    }

    private void initialize(String visitorHash) {
        String safeVisitorHash = required(visitorHash, "访客身份无效");
        jdbc.update("INSERT IGNORE INTO fleet_company(visitor_hash,balance_minor,currency) VALUES(?,?,'CNY')", safeVisitorHash, INITIAL_BALANCE_MINOR);
        String prefix = safeVisitorHash.substring(0, Math.min(16, safeVisitorHash.length())).toUpperCase(Locale.ROOT);
        jdbc.update("INSERT IGNORE INTO fleet_account_ledger(id,visitor_hash,entry_key,entry_type,amount_minor,balance_after_minor) VALUES(?,?,?,?,?,?)",
                "LED-INIT-" + prefix, safeVisitorHash, "INITIAL_CAPITAL", "INITIAL_CAPITAL", INITIAL_BALANCE_MINOR, INITIAL_BALANCE_MINOR);
        jdbc.update("INSERT IGNORE INTO fleet_asset(id,visitor_hash,type_id,asset_status,acquisition_source,acquisition_price_minor,initial_key) VALUES(?,?,?,?,?,0,?)",
                "FLT-INIT-G-" + prefix, safeVisitorHash, "tricycle", "DEPLOYED", "INITIAL", "GROUND_STARTER");
        jdbc.update("INSERT IGNORE INTO fleet_asset(id,visitor_hash,type_id,asset_status,acquisition_source,acquisition_price_minor,initial_key) VALUES(?,?,?,?,?,0,?)",
                "FLT-INIT-A-" + prefix, safeVisitorHash, "smart-city-drone", "DEPLOYED", "INITIAL", "AIR_STARTER");
    }

    private void lockCompany(String visitorHash) {
        jdbc.queryForObject("SELECT balance_minor FROM fleet_company WHERE visitor_hash=? FOR UPDATE", Long.class, visitorHash);
    }

    private long companyBalance(String visitorHash) {
        Long value = jdbc.queryForObject("SELECT balance_minor FROM fleet_company WHERE visitor_hash=?", Long.class, visitorHash);
        return value == null ? 0 : value;
    }

    private ExistingCommand command(String visitorHash, String commandId) {
        List<ExistingCommand> rows = jdbc.query("SELECT request_fingerprint,target_asset_id,amount_minor FROM fleet_command WHERE visitor_hash=? AND command_id=?",
                (rs, row) -> new ExistingCommand(rs.getString("request_fingerprint"), rs.getString("target_asset_id"), rs.getLong("amount_minor")),
                visitorHash, commandId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> replay(String visitorHash, String commandId, String fingerprint,
                                       ExistingCommand existing, String action) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw new DemoException(HttpStatus.CONFLICT, "commandId 已用于不同的车队操作");
        }
        return result(visitorHash, commandId, action, true, existing.assetId(), existing.amountMinor());
    }

    private Map<String, Object> result(String visitorHash, String commandId, String action, boolean replayed,
                                       String assetId, long chargedMinor) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("commandId", commandId); response.put("action", action); response.put("replayed", replayed);
        response.put("assetId", assetId); response.put("chargedMinor", chargedMinor);
        response.put("fleet", snapshotInternal(visitorHash));
        return response;
    }

    private Map<String, Object> saleResult(String visitorHash, String commandId, String action, boolean replayed,
                                           String assetId, long creditedMinor) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("commandId", commandId); response.put("action", action); response.put("replayed", replayed);
        response.put("assetId", assetId); response.put("creditedMinor", creditedMinor);
        response.put("fleet", snapshotInternal(visitorHash));
        return response;
    }

    private Map<String, Object> snapshotInternal(String visitorHash) {
        settleCompletedCharges(visitorHash);
        Map<String, Object> company = companyViewInternal(visitorHash);
        Instant now = Instant.now();
        List<Map<String, Object>> assets = jdbc.query("SELECT id,type_id,asset_status,acquisition_source,acquisition_price_minor,acquired_at,updated_at,battery_basis_points,charging_from_basis_points,charging_started_at,active_run_id,state_version FROM fleet_asset WHERE visitor_hash=? AND sold_at IS NULL ORDER BY acquired_at,id",
                (rs, row) -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("assetId", rs.getString("id")); value.put("typeId", rs.getString("type_id"));
                    value.put("status", rs.getString("asset_status")); value.put("acquisitionSource", rs.getString("acquisition_source"));
                    value.put("acquisitionPriceMinor", rs.getLong("acquisition_price_minor"));
                    value.put("acquiredAt", instant(rs, "acquired_at")); value.put("updatedAt", instant(rs, "updated_at"));
                    AssetState state = assetState(rs);
                    double battery = effectiveBattery(state, now);
                    boolean charging = "GARAGED".equals(state.status()) && state.chargingStartedAt() != null
                            && battery < FULL_BATTERY_BASIS_POINTS;
                    value.put("batteryPercent", round(battery / 100.0, 1)); value.put("charging", charging);
                    value.put("chargingFromPercent", charging && state.chargingFromBasisPoints() != null
                            ? round(state.chargingFromBasisPoints() / 100.0, 1) : null);
                    value.put("chargingStartedAt", charging ? state.chargingStartedAt().toString() : null);
                    value.put("chargingCompletesAt", charging ? state.chargingStartedAt().plusSeconds(CHARGE_DURATION_SECONDS).toString() : null);
                    value.put("activeRunId", state.activeRunId()); value.put("stateVersion", state.stateVersion());
                    return value;
                }, visitorHash).stream().filter(asset -> {
                    try { catalog.require(String.valueOf(asset.get("typeId"))); return true; }
                    catch (IllegalArgumentException ignored) { return false; }
                }).toList();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("catalogVersion", FleetCatalog.VERSION); snapshot.put("devModeAllowed", devPricingEnabled);
        snapshot.put("company", company); snapshot.put("assets", assets); snapshot.put("catalog", catalog.view());
        return snapshot;
    }

    private GroundVehicle latestGroundVehicle(String visitorHash, boolean forUpdate) {
        String sql = "SELECT id,type_id,asset_status,battery_basis_points,charging_from_basis_points,charging_started_at,active_run_id,state_version,updated_at FROM fleet_asset WHERE visitor_hash=? AND asset_status='DEPLOYED' AND sold_at IS NULL ORDER BY updated_at DESC,id DESC"
                + (forUpdate ? " FOR UPDATE" : "");
        return jdbc.query(sql, (rs, row) -> assetState(rs), visitorHash).stream()
                .filter(state -> {
                    try { return "GROUND".equals(catalog.require(state.typeId()).category()); }
                    catch (IllegalArgumentException ignored) { return false; }
                })
                .map(this::groundVehicle)
                .findFirst()
                .orElseThrow(() -> new DemoException(HttpStatus.CONFLICT, "没有地面运输车出站，请先前往车队中心出站车辆"));
    }

    private AirVehicle latestAirVehicle(String visitorHash, boolean forUpdate) {
        String sql = "SELECT id,type_id,asset_status,battery_basis_points,charging_from_basis_points,charging_started_at,active_run_id,state_version,updated_at FROM fleet_asset WHERE visitor_hash=? AND asset_status='DEPLOYED' AND sold_at IS NULL ORDER BY updated_at DESC,id DESC"
                + (forUpdate ? " FOR UPDATE" : "");
        return jdbc.query(sql, (rs, row) -> assetState(rs), visitorHash).stream()
                .filter(state -> {
                    try { return "AIR".equals(catalog.require(state.typeId()).category()); }
                    catch (IllegalArgumentException ignored) { return false; }
                })
                .map(this::airVehicle)
                .findFirst()
                .orElseThrow(() -> new DemoException(HttpStatus.CONFLICT, "没有空中运输设备出站，请先前往车队中心出站设备"));
    }

    private GroundVehicle groundVehicle(AssetState state) {
        FleetCatalog.Type type = catalog.require(state.typeId());
        FleetCatalog.GameplayStats gameplay = type.gameplayStats();
        if (!"GROUND".equals(type.category()) || gameplay == null)
            throw new DemoException(HttpStatus.CONFLICT, "当前出站设备不是可执行运输任务的地面车辆");
        return new GroundVehicle(state.id(), type.typeId(), type.name(), type.modelAssetId(), state.stateVersion(),
                round(effectiveBattery(state, Instant.now()) / 100.0, 1), gameplay.speedKph(), gameplay.fullRangeKm(),
                gameplay.cargoMultiplier(), type.demoRatings());
    }

    private AirVehicle airVehicle(AssetState state) {
        FleetCatalog.Type type = catalog.require(state.typeId());
        FleetCatalog.GameplayStats gameplay = type.gameplayStats();
        if (!"AIR".equals(type.category()) || gameplay == null)
            throw new DemoException(HttpStatus.CONFLICT, "当前出站设备不是可执行运输任务的空中设备");
        return new AirVehicle(state.id(), type.typeId(), type.name(), type.modelAssetId(), state.stateVersion(),
                round(effectiveBattery(state, Instant.now()) / 100.0, 1), gameplay.speedKph(), gameplay.fullRangeKm(),
                gameplay.cargoMultiplier(), gameplay.maneuverDelaySeconds(), "vtol-air-taxi".equals(type.typeId()),
                type.demoRatings());
    }

    private double currentBatteryForRun(String visitorHash, String runId, String assetId) {
        return jdbc.query("SELECT battery_basis_points FROM fleet_asset WHERE id=? AND visitor_hash=? AND active_run_id=?",
                (rs, row) -> rs.getDouble(1) / 100.0, assetId, visitorHash, runId).stream().findFirst().orElse(0.0);
    }

    private void settleCompletedCharges(String visitorHash) {
        Instant now = Instant.now();
        List<AssetState> charging = jdbc.query("SELECT id,type_id,asset_status,battery_basis_points,charging_from_basis_points,charging_started_at,active_run_id,state_version,updated_at FROM fleet_asset WHERE visitor_hash=? AND asset_status='GARAGED' AND charging_started_at IS NOT NULL AND sold_at IS NULL",
                (rs, row) -> assetState(rs), visitorHash);
        for (AssetState state : charging) {
            if (state.chargingStartedAt().plusSeconds(CHARGE_DURATION_SECONDS).isAfter(now)) continue;
            jdbc.update("UPDATE fleet_asset SET battery_basis_points=?,charging_from_basis_points=NULL,charging_started_at=NULL,state_version=state_version+1,updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND visitor_hash=? AND charging_started_at=?",
                    FULL_BATTERY_BASIS_POINTS, state.id(), visitorHash, Timestamp.from(state.chargingStartedAt()));
        }
    }

    private static AssetState assetState(ResultSet rs) throws SQLException {
        Timestamp chargingStarted = rs.getTimestamp("charging_started_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        Object chargingFrom = rs.getObject("charging_from_basis_points");
        return new AssetState(rs.getString("id"), rs.getString("type_id"), rs.getString("asset_status"),
                rs.getDouble("battery_basis_points"), chargingFrom == null ? null : rs.getDouble("charging_from_basis_points"),
                chargingStarted == null ? null : chargingStarted.toInstant(), rs.getString("active_run_id"),
                rs.getLong("state_version"), updatedAt == null ? Instant.EPOCH : updatedAt.toInstant());
    }

    private static double effectiveBattery(AssetState state, Instant now) {
        double stored = clampBattery(state.batteryBasisPoints());
        if (!"GARAGED".equals(state.status()) || state.chargingStartedAt() == null) return stored;
        double from = clampBattery(state.chargingFromBasisPoints() == null ? stored : state.chargingFromBasisPoints());
        double ratio = MissionMath.clamp(Duration.between(state.chargingStartedAt(), now).toMillis()
                / (CHARGE_DURATION_SECONDS * 1000.0), 0, 1);
        return clampBattery(from + (FULL_BATTERY_BASIS_POINTS - from) * ratio);
    }

    private static double clampBattery(double value) { return Math.max(0, Math.min(FULL_BATTERY_BASIS_POINTS, value)); }
    private static double round(double value, int digits) {
        double scale = Math.pow(10, digits);
        return Math.round(value * scale) / scale;
    }

    private Map<String, Object> companyViewInternal(String visitorHash) {
        return jdbc.queryForObject("SELECT balance_minor,currency,created_at,updated_at FROM fleet_company WHERE visitor_hash=?",
                (rs, row) -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("balanceMinor", rs.getLong("balance_minor")); value.put("currency", rs.getString("currency"));
                    value.put("createdAt", instant(rs, "created_at")); value.put("updatedAt", instant(rs, "updated_at"));
                    return value;
                }, visitorHash);
    }

    private static Map<String, Object> ledgerView(ResultSet rs) throws SQLException {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", rs.getString("id")); value.put("entryKey", rs.getString("entry_key"));
        value.put("entryType", rs.getString("entry_type")); value.put("amountMinor", rs.getLong("amount_minor"));
        value.put("balanceBeforeMinor", rs.getObject("balance_before_minor") == null ? null : rs.getLong("balance_before_minor"));
        value.put("balanceAfterMinor", rs.getLong("balance_after_minor"));
        value.put("assessedAmountMinor", rs.getObject("assessed_amount_minor") == null ? rs.getLong("amount_minor") : rs.getLong("assessed_amount_minor"));
        value.put("runId", rs.getString("run_id")); value.put("referenceId", rs.getString("reference_id"));
        value.put("actorId", rs.getString("actor_id"));
        value.put("simulationTimeMs", rs.getObject("simulation_time_ms") == null ? null : rs.getLong("simulation_time_ms"));
        value.put("timelineEpoch", rs.getInt("timeline_epoch"));
        value.put("supersededByRewindId", rs.getString("superseded_by_rewind_id"));
        value.put("ruleVersion", rs.getString("rule_version")); value.put("createdAt", instant(rs, "created_at"));
        String metadata = rs.getString("metadata_json");
        if (metadata != null) {
            try { value.put("metadata", new com.fasterxml.jackson.databind.ObjectMapper().readValue(metadata, Map.class)); }
            catch (Exception ignored) { value.put("metadata", Map.of()); }
        } else value.put("metadata", Map.of());
        return value;
    }

    private static String instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant().toString();
    }

    private static String id(String prefix) { return prefix + "-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT); }
    private static String required(String value, String message) {
        if (value == null || value.isBlank()) throw badRequest(message);
        return value.trim();
    }
    private static String commandId(String value) {
        String id = required(value, "commandId 不能为空");
        if (id.length() > 72) throw badRequest("commandId 过长");
        return id;
    }
    private static DemoException badRequest(String message) { return new DemoException(HttpStatus.BAD_REQUEST, message); }
    private static String money(long minor) { return String.format(Locale.ROOT, "%,.2f", minor / 100.0); }
    public record GroundVehicle(String assetId, String typeId, String name, String modelAssetId, long stateVersion,
                                double batteryPercent, double speedKph, double fullRangeKm, double cargoMultiplier,
                                Map<String, Integer> ratings) {
        public Map<String, Object> view() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("assetId", assetId); value.put("typeId", typeId); value.put("name", name);
            value.put("modelAssetId", modelAssetId); value.put("stateVersion", stateVersion);
            value.put("batteryPercent", batteryPercent); value.put("speedKph", speedKph);
            value.put("fullRangeKm", fullRangeKm); value.put("cargoMultiplier", cargoMultiplier);
            value.put("ratings", ratings);
            return value;
        }
    }
    public record AirVehicle(String assetId, String typeId, String name, String modelAssetId, long stateVersion,
                             double batteryPercent, double speedKph, double fullRangeKm, double cargoMultiplier,
                             double maneuverDelaySeconds, boolean independentRoute,
                             Map<String, Integer> ratings) {
        public Map<String, Object> view() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("assetId", assetId); value.put("typeId", typeId); value.put("name", name);
            value.put("modelAssetId", modelAssetId); value.put("stateVersion", stateVersion);
            value.put("batteryPercent", batteryPercent); value.put("speedKph", speedKph);
            value.put("fullRangeKm", fullRangeKm); value.put("cargoMultiplier", cargoMultiplier);
            value.put("maneuverDelaySeconds", maneuverDelaySeconds); value.put("independentRoute", independentRoute);
            value.put("ratings", ratings);
            return value;
        }
    }
    public record BatteryUse(double movedMeters, double batteryPercent, boolean depleted,
                             double equivalentMeters, double extraBatteryPercent) {}
    private record AssetState(String id, String typeId, String status, double batteryBasisPoints,
                              Double chargingFromBasisPoints, Instant chargingStartedAt, String activeRunId,
                              long stateVersion, Instant updatedAt) {}
    private record SaleAsset(String typeId, String status, boolean sold, String activeRunId) {}
    private record ExistingCommand(String fingerprint, String assetId, long amountMinor) {}
}
