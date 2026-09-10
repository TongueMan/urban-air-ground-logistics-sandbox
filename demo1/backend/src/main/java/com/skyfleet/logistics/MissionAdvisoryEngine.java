package com.skyfleet.logistics;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class MissionAdvisoryEngine {
    private static final List<String> OBJECTIVES = List.of("BALANCED", "FASTEST", "SAFEST");
    private static final double MIN_BATTERY = 30;
    private static final double MIN_LINK_QUALITY = 50;
    private static final double UAV_CRUISE_METERS_PER_SECOND = 8;
    private static final int DELIVERY_SERVICE_SECONDS = 120;

    public Map<String, Object> recommend(DemoSignal signal, List<Map<String, Object>> devices) {
        return recommend(signal, devices, "BALANCED");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> recommend(DemoSignal signal, List<Map<String, Object>> devices, String objectiveValue) {
        String objective = normalizeObjective(objectiveValue);
        double[] target = targetPoint(signal, devices);
        List<Map<String, Object>> options = new ArrayList<>();

        for (Map<String, Object> device : devices) {
            List<String> capabilities = device.get("capabilities") instanceof List<?> values
                    ? values.stream().map(String::valueOf).toList() : List.of();
            if (!capabilities.contains("DELIVERY")) continue;

            Map<String, Object> telemetry = device.get("sensorData") instanceof Map<?, ?> value
                    ? (Map<String, Object>) value : Map.of();
            double battery = number(telemetry.get("battery"), 0);
            double linkQuality = number(telemetry.get("linkQuality"), 0);
            boolean hasPosition = device.get("longitude") instanceof Number && device.get("latitude") instanceof Number;
            boolean eligible = battery >= MIN_BATTERY && linkQuality >= MIN_LINK_QUALITY && hasPosition && target != null;
            double distance = hasPosition && target != null ? haversineMeters(
                    number(device.get("longitude"), 0), number(device.get("latitude"), 0), target[0], target[1]) : 0;
            boolean keepsContext = String.valueOf(device.get("deviceId")).equals(signal.actorId);
            long duration = Math.round(DELIVERY_SERVICE_SECONDS + distance / UAV_CRUISE_METERS_PER_SECOND);
            double batteryCost = round(1.5 + distance * 0.0011);
            long missionDelay = duration + (keepsContext ? 30 : 90);

            Map<String, Object> impact = new LinkedHashMap<>();
            impact.put("estimate", true);
            impact.put("distanceMeters", Math.round(distance));
            impact.put("estimatedDurationSeconds", duration);
            impact.put("batteryCostPercent", batteryCost);
            impact.put("missionDelaySeconds", missionDelay);

            String actorId = String.valueOf(device.get("deviceId"));
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("id", "OPTION-" + actorId);
            option.put("actorId", actorId);
            option.put("actorName", String.valueOf(device.getOrDefault("deviceName", actorId)));
            option.put("eligible", eligible);
            option.put("rank", 0);
            option.put("battery", round(battery));
            option.put("linkQuality", round(linkQuality));
            option.put("impact", impact);
            option.put("facts", List.of(
                    "设备具备无人机配送能力",
                    keepsContext ? "保留当前 Signal 的任务上下文" : "需要跨编组切换任务上下文",
                    "距离与影响值由服务端规则估算"));
            List<String> risks = new ArrayList<>();
            if (battery < MIN_BATTERY) risks.add("电量低于 30% 安全阈值");
            if (linkQuality < MIN_LINK_QUALITY) risks.add("链路质量低于 50% 安全阈值");
            if (!hasPosition || target == null) risks.add("缺少可用于距离估算的位置");
            if (!keepsContext) risks.add("跨编组响应会增加任务切换成本");
            if (eligible && battery - batteryCost < 30) risks.add("预计任务后剩余电量接近安全阈值");
            option.put("risks", risks);
            option.put("exclusionReason", eligible ? null : firstRisk(risks));
            option.put("sortScore", sortScore(objective, duration, missionDelay, batteryCost, battery, linkQuality, keepsContext));
            options.add(option);
        }

        options.sort(Comparator
                .comparing((Map<String, Object> value) -> Boolean.TRUE.equals(value.get("eligible"))).reversed()
                .thenComparingDouble(value -> number(value.get("sortScore"), Double.MAX_VALUE))
                .thenComparing(value -> String.valueOf(value.get("actorId"))));
        int rank = 1;
        for (Map<String, Object> option : options) {
            option.remove("sortScore");
            if (Boolean.TRUE.equals(option.get("eligible"))) option.put("rank", rank++);
        }
        if (options.size() > 3) options = new ArrayList<>(options.subList(0, 3));
        Map<String, Object> selected = options.stream().filter(value -> Boolean.TRUE.equals(value.get("eligible"))).findFirst().orElse(null);

        Map<String, Object> recommendation = new LinkedHashMap<>();
        recommendation.put("action", "REDELIVER");
        recommendation.put("objective", objective);
        recommendation.put("title", selected == null ? "当前没有满足安全阈值的补充配送 Actor" : "首选方案：由 " + selected.get("actorName") + " 执行补充配送");
        recommendation.put("recommendedActorId", selected == null ? null : selected.get("actorId"));
        recommendation.put("recommendedOptionId", selected == null ? null : selected.get("id"));
        recommendation.put("options", options);
        recommendation.put("candidates", options);
        recommendation.put("facts", selected == null
                ? List.of("候选 Actor 未通过能力、位置、电量与链路安全门禁", "建议人工检查 Fleet 状态")
                : List.of("所有影响值均为服务端确定性估算", "Objective 只改变合格方案排序", "首选方案已通过基础安全阈值"));
        recommendation.put("constraints", List.of("需要操作员批准", "真实补充配送命令链路尚未接入", "语言模型不能修改方案数值或资格"));
        recommendation.put("requiresHumanApproval", true);
        recommendation.put("executable", false);
        recommendation.put("executionBlockReason", "未接入真实设备命令与 ACK 链路");
        return recommendation;
    }

    public static String normalizeObjective(String value) {
        String objective = value == null || value.isBlank() ? "BALANCED" : value.trim().toUpperCase(Locale.ROOT);
        if (!OBJECTIVES.contains(objective)) throw new IllegalArgumentException("objective 仅支持 BALANCED、FASTEST、SAFEST");
        return objective;
    }

    private static double[] targetPoint(DemoSignal signal, List<Map<String, Object>> devices) {
        Object locationValue = signal.source.get("location");
        if (locationValue instanceof Map<?, ?> location
                && location.get("longitude") instanceof Number longitude
                && location.get("latitude") instanceof Number latitude) {
            return new double[]{longitude.doubleValue(), latitude.doubleValue()};
        }
        if (signal.source.get("longitude") instanceof Number longitude
                && signal.source.get("latitude") instanceof Number latitude) {
            return new double[]{longitude.doubleValue(), latitude.doubleValue()};
        }
        return devices.stream()
                .filter(device -> String.valueOf(device.get("deviceId")).equals(signal.actorId))
                .filter(device -> device.get("longitude") instanceof Number && device.get("latitude") instanceof Number)
                .map(device -> new double[]{number(device.get("longitude"), 0), number(device.get("latitude"), 0)})
                .findFirst().orElse(null);
    }

    private static double sortScore(String objective, long duration, long delay, double batteryCost,
                                    double battery, double linkQuality, boolean keepsContext) {
        return switch (objective) {
            case "FASTEST" -> duration + (keepsContext ? 0 : 5);
            case "SAFEST" -> -(battery - batteryCost) * .65 - linkQuality * .35 + (keepsContext ? 0 : 3);
            default -> duration + delay * .35 + batteryCost * 18 + (keepsContext ? -30 : 0);
        };
    }

    private static double haversineMeters(double lon1, double lat1, double lon2, double lat2) {
        double radius = 6_371_000;
        double latDelta = Math.toRadians(lat2 - lat1);
        double lonDelta = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDelta / 2) * Math.sin(latDelta / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDelta / 2) * Math.sin(lonDelta / 2);
        return radius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static String firstRisk(List<String> risks) { return risks.isEmpty() ? "未通过候选资格校验" : risks.get(0); }
    private static double number(Object value, double fallback) { return value instanceof Number number ? number.doubleValue() : fallback; }
    private static double round(double value) { return Math.round(value * 10.0) / 10.0; }
}
