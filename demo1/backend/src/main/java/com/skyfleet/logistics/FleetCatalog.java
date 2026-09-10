package com.skyfleet.logistics;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class FleetCatalog {
    public static final String VERSION = "fleet-catalog/2.1.0";

    private final List<Type> types = List.of(
            type("tricycle", "城市货运三轮车", "Urban Cargo Tricycle", "GROUND", "tricycle", 800_000,
                    "短途灵活运力", ratings(2, 5, 2, 2), gameplay(18, 5, 1.5)),
            type("ford-f350-utility", "Ford F-350 多用途皮卡", "Ford F-350 Utility", "GROUND", "ford-f350-utility", 3_600_000,
                    "多用途快速运输", ratings(3, 4, 4, 3), gameplay(34, 8, 2.5)),
            type("ural-truck-vehicle-only", "Ural 重型六轮货车", "Ural Heavy Truck", "GROUND", "ural-truck-vehicle-only", 4_800_000,
                    "复杂路况重载运输", ratings(5, 3, 2, 4), gameplay(18, 13, 5)),
            type("cybertruck-fun-size", "Cybertruck 电动运输车", "Cybertruck Electric Carrier", "GROUND", "cybertruck-fun-size", 7_600_000,
                    "高性能电动平台", ratings(4, 4, 5, 5), gameplay(42, 21, 3.5)),
            type("peterbilt-379-optimus-prime", "Peterbilt 长途牵引车", "Peterbilt Long-haul Tractor", "GROUND", "peterbilt-379-optimus-prime", 9_800_000,
                    "干线大宗运力", ratings(5, 5, 5, 5), gameplay(42, 21, 5)),
            type("smart-city-drone", "轻型配送无人机", "Light Delivery Drone", "AIR", "smart-city-drone", 2_200_000,
                    "小件短程空中配送", ratings(1, 2, 3, 2), airGameplay(28, 3, 1, 8)),
            new Type("vtol-air-taxi", "VTOL 重载运输飞行器", "VTOL Heavy Transport", "AIR", "vtol-air-taxi", 8_800_000,
                    "AVAILABLE", "大型资源 · 按需加载", "跨区高速重载", ratings(5, 5, 5, 5), airGameplay(52, 9, 3, 2))
    );

    public List<Map<String, Object>> view() {
        return types.stream().map(Type::view).toList();
    }

    public Type require(String typeId) {
        return types.stream().filter(type -> type.typeId().equals(typeId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知车型：" + typeId));
    }

    private static Type type(String id, String name, String englishName, String category, String modelAssetId,
                             long priceMinor, String summary, Map<String, Integer> ratings, GameplayStats gameplayStats) {
        return new Type(id, name, englishName, category, modelAssetId, priceMinor, "AVAILABLE", "标准资源", summary, ratings, gameplayStats);
    }

    private static Map<String, Integer> ratings(int capacity, int agility, int speed, int endurance) {
        Map<String, Integer> values = new LinkedHashMap<>();
        values.put("capacity", capacity);
        values.put("agility", agility);
        values.put("speed", speed);
        values.put("endurance", endurance);
        return Map.copyOf(values);
    }

    private static GameplayStats gameplay(double speedKph, double fullRangeKm, double cargoMultiplier) {
        return new GameplayStats(speedKph, fullRangeKm, cargoMultiplier, 0);
    }

    private static GameplayStats airGameplay(double speedKph, double fullRangeKm, double cargoMultiplier,
                                             double maneuverDelaySeconds) {
        return new GameplayStats(speedKph, fullRangeKm, cargoMultiplier, maneuverDelaySeconds);
    }

    public record Type(String typeId, String name, String englishName, String category, String modelAssetId,
                       long priceMinor, String availability, String loadHint, String summary,
                       Map<String, Integer> demoRatings, GameplayStats gameplayStats) {
        Map<String, Object> view() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("typeId", typeId); value.put("name", name); value.put("englishName", englishName);
            value.put("category", category); value.put("modelAssetId", modelAssetId); value.put("priceMinor", priceMinor);
            value.put("availability", availability); value.put("loadHint", loadHint); value.put("summary", summary);
            value.put("demoRatings", demoRatings);
            if (gameplayStats != null) value.put("gameplayStats", gameplayStats.view());
            return value;
        }
    }

    public record GameplayStats(double speedKph, double fullRangeKm, double cargoMultiplier,
                                double maneuverDelaySeconds) {
        Map<String, Object> view() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("speedKph", speedKph); value.put("fullRangeKm", fullRangeKm);
            value.put("cargoMultiplier", cargoMultiplier);
            if (maneuverDelaySeconds > 0) value.put("maneuverDelaySeconds", maneuverDelaySeconds);
            return Map.copyOf(value);
        }
    }
}
