package com.skyfleet.logistics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ScenarioTemplateCatalog {
    public static final String VERSION = "2.0.0";
    public static final String CAMPUS_TEMPLATE_ID = "hefei-hfut-feicui-campus";
    private final MissionCatalog missions;
    private final ObjectMapper mapper;
    private final Map<String, Descriptor> descriptors = Map.of(
            "hefei-logistics-area-a", new Descriptor("hefei-logistics-area-a", "庐阳·杏花村站物流运营区", "HF-VEH-000001", "HF-UAV-000003", "GROUND-A", "AIR-A", 72, false),
            "hefei-logistics-area-b", new Descriptor("hefei-logistics-area-b", "包河·盛大站物流运营区", "HF-VEH-000002", "HF-UAV-000004", "GROUND-B", "AIR-B", 76, false),
            CAMPUS_TEMPLATE_ID, new Descriptor(CAMPUS_TEMPLATE_ID, "合肥工业大学翡翠湖校区·北门物流运营区", "HF-VEH-000005", "HF-UAV-000006", "GROUND-C", "AIR-C", 78, true)
    );

    private static final List<GroundVariant> CAMPUS_GROUND_VARIANTS = List.of(
            variant("CAMPUS-EAST", "香樟东路", new double[][]{
                    {117.2116751,31.7806513},{117.2117909,31.7801968},{117.2117970,31.7799377},{117.2118454,31.7793856},
                    {117.2121279,31.7788131},{117.2127362,31.7778287},{117.2131181,31.7767392},{117.2131727,31.7756667},
                    {117.2131044,31.7752013},{117.2126171,31.7741254},{117.2114265,31.7727822},{117.2113220,31.7716865}
            }),
            variant("CAMPUS-CENTRAL", "香樟西路—合欢路", new double[][]{
                    {117.2116751,31.7806513},{117.2115779,31.7806503},{117.2115590,31.7802008},{117.2115536,31.7799476},
                    {117.2115348,31.7796372},{117.2112958,31.7788962},{117.2111256,31.7785921},{117.2109000,31.7783127},
                    {117.2104388,31.7775449},{117.2101860,31.7765263},{117.2101362,31.7754444},{117.2106541,31.7741304},
                    {117.2113271,31.7731678},{117.2113220,31.7716865}
            }),
            variant("CAMPUS-WEST", "香樟西路—广玉兰路", new double[][]{
                    {117.2116751,31.7806513},{117.2115779,31.7806503},{117.2115590,31.7802008},{117.2115348,31.7796372},
                    {117.2112958,31.7788962},{117.2111256,31.7785921},{117.2102339,31.7782657},{117.2086697,31.7776966},
                    {117.2081852,31.7772668},{117.2069977,31.7767395},{117.2069996,31.7755949},{117.2069849,31.7743240},
                    {117.2067728,31.7737419},{117.2071531,31.7731302},{117.2079719,31.7727948},{117.2089279,31.7723429},
                    {117.2095675,31.7718650},{117.2106676,31.7718710},{117.2113220,31.7716865}
            })
    );

    private static final List<List<Number>> CAMPUS_AIR_WAYPOINTS = coordinates(new double[][]{
            {117.2116751,31.7806513,78},{117.2102000,31.7795500,78},{117.2084000,31.7783000,78},
            {117.2073000,31.7764000,78},{117.2078000,31.7744000,78},{117.2093000,31.7729000,78},
            {117.2112000,31.7732000,78},{117.2127000,31.7748000,78},{117.2129000,31.7771000,78},
            {117.2118000,31.7791000,78},{117.2113220,31.7716865,78}
    });

    public ScenarioTemplateCatalog(MissionCatalog missions, ObjectMapper mapper) {
        this.missions = missions;
        this.mapper = mapper;
    }

    public List<Map<String, Object>> summaries() {
        return descriptors.values().stream().sorted(java.util.Comparator.comparing(Descriptor::id)).map(descriptor -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", descriptor.id()); value.put("version", VERSION); value.put("name", descriptor.name());
            value.put("coordinateSystem", "BD09LL"); value.put("defaultFlightAltitudeMeters", descriptor.defaultAltitude());
            boolean trafficSignalsSupported = !CAMPUS_TEMPLATE_ID.equals(descriptor.id());
            value.put("fixedGroundStart", descriptor.fixedGroundEndpoints());
            value.put("trafficSignalsSupported", trafficSignalsSupported);
            if (descriptor.fixedGroundEndpoints()) {
                value.put("startLabel", "翡翠湖校区北大门"); value.put("endLabel", "翡翠湖校区南大门");
                value.put("groundRouteVariantCount", CAMPUS_GROUND_VARIANTS.size());
            }
            value.put("parameterDefaults", Map.of("deliveryDensity", "STANDARD", "flightAltitudeMeters", descriptor.defaultAltitude(),
                    "maxDurationMinutes", 15, "returnReservePercent", 25, "trafficSignalsEnabled", trafficSignalsSupported,
                    "airspaceThemeCount", 2));
            value.put("parameterLimits", Map.of("flightAltitudeMeters", List.of(60, 100),
                    "maxDurationMinutes", List.of(minimumDurationMinutes(descriptor.id()), 30),
                    "returnReservePercent", List.of(20, 40), "deliveryDensity", List.of("LOW", "STANDARD", "HIGH"),
                    "trafficSignalsEnabled", List.of(false, true), "airspaceThemeCount", List.of(2, 4)));
            return value;
        }).toList();
    }

    public static int minimumDurationMinutes(String templateId) {
        return "hefei-logistics-area-a".equals(templateId) ? 11 : 8;
    }

    public Descriptor descriptor(String id) {
        Descriptor descriptor = descriptors.get(id);
        if (descriptor == null) throw new IllegalArgumentException("未知场景模板: " + id);
        return descriptor;
    }

    public List<GroundVariant> groundVariants(String id, Map<String, Object> defaultRoute) {
        if (CAMPUS_TEMPLATE_ID.equals(id)) return CAMPUS_GROUND_VARIANTS;
        return List.of(new GroundVariant(id.toUpperCase() + "-DEFAULT", "区域校核路线", coordinateLists(TaskInstanceService.points(defaultRoute))));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> materializeBase(String id) {
        Descriptor descriptor = descriptor(id);
        Map<String, Object> result = mapper.convertValue(missions.copy(), new TypeReference<>() {});
        boolean campus = CAMPUS_TEMPLATE_ID.equals(id);
        String sourceVehicleId = campus ? "HF-VEH-000001" : descriptor.vehicleId();
        String sourceUavId = campus ? "HF-UAV-000003" : descriptor.uavId();
        String sourceGroundRouteId = campus ? "GROUND-A" : descriptor.groundRouteId();
        String sourceAirRouteId = campus ? "AIR-A" : descriptor.airRouteId();
        result.put("scenarioKey", descriptor.id()); result.put("scenarioTemplateId", descriptor.id());
        result.put("scenarioTemplateVersion", VERSION); result.put("version", VERSION);
        result.put("name", descriptor.name() + "·动态车机协同配送"); result.put("routeGenerationMode", "TASK_INSTANCE_FROZEN");

        List<Map<String, Object>> actors = ((List<Map<String, Object>>) result.getOrDefault("actors", List.of())).stream()
                .filter(actor -> List.of(sourceVehicleId, sourceUavId).contains(String.valueOf(actor.get("id"))))
                .<Map<String, Object>>map(actor -> remap(new LinkedHashMap<>(actor), descriptor, campus)).toList();
        if (campus) actors.forEach(actor -> actor.put("name", "UAV".equals(actor.get("kind")) ? "翡翠湖校区配送无人机" : "翡翠湖校区城市配送车"));
        List<Map<String, Object>> routes = ((List<Map<String, Object>>) result.getOrDefault("routes", List.of())).stream()
                .filter(route -> List.of(sourceGroundRouteId, sourceAirRouteId).contains(String.valueOf(route.get("routeId"))))
                .<Map<String, Object>>map(route -> remap(new LinkedHashMap<>(route), descriptor, campus)).toList();
        if (campus) for (Map<String, Object> route : routes) {
            if (descriptor.groundRouteId().equals(route.get("routeId"))) route.put("points", CAMPUS_GROUND_VARIANTS.get(1).points());
            else { route.put("points", CAMPUS_AIR_WAYPOINTS); route.put("nominalSpeedKph", 28); }
            route.put("distanceMeters", MissionMath.polylineDistance(TaskInstanceService.points(route)));
        }
        List<Map<String, Object>> formations = ((List<Map<String, Object>>) result.getOrDefault("formations", List.of())).stream()
                .filter(formation -> sourceVehicleId.equals(String.valueOf(formation.get("leaderActorId"))))
                .<Map<String, Object>>map(formation -> remap(new LinkedHashMap<>(formation), descriptor, campus)).toList();
        List<Map<String, Object>> assignments = ((List<Map<String, Object>>) result.getOrDefault("assignments", List.of())).stream()
                .filter(assignment -> List.of(sourceVehicleId, sourceUavId).contains(String.valueOf(assignment.get("actorId"))))
                .<Map<String, Object>>map(assignment -> remap(new LinkedHashMap<>(assignment), descriptor, campus)).toList();
        List<Map<String, Object>> pairs = ((List<Map<String, Object>>) result.getOrDefault("pairs", List.of())).stream()
                .filter(pair -> sourceVehicleId.equals(String.valueOf(pair.get("vehicleId"))))
                .<Map<String, Object>>map(pair -> remap(new LinkedHashMap<>(pair), descriptor, campus)).toList();
        result.put("actors", new ArrayList<>(actors)); result.put("routes", new ArrayList<>(routes));
        result.put("formations", new ArrayList<>(formations)); result.put("assignments", new ArrayList<>(assignments));
        result.put("pairs", new ArrayList<>(pairs)); result.put("events", new ArrayList<>());
        result.put("constraints", Map.of("flightAltitudeMeters", List.of(60, 100), "maxFlightDistanceMeters", 5000,
                "maxRendezvousWaitSeconds", 180, "buildingCollision", "NOT_EVALUATED"));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> remap(Map<String, Object> source, Descriptor descriptor, boolean enabled) {
        if (!enabled) return source;
        Map<String, String> replacements = Map.of(
                "HF-VEH-000001", descriptor.vehicleId(), "HF-UAV-000003", descriptor.uavId(),
                "GROUND-A", descriptor.groundRouteId(), "AIR-A", descriptor.airRouteId(),
                "FORMATION-A", "FORMATION-C", "ASSIGN-VEHICLE-A", "ASSIGN-VEHICLE-C", "ASSIGN-UAV-A", "ASSIGN-UAV-C");
        source.replaceAll((key, value) -> remapValue(value, replacements));
        return source;
    }

    private static Object remapValue(Object value, Map<String, String> replacements) {
        if (value instanceof String text) return replacements.getOrDefault(text, text);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), remapValue(item, replacements)));
            return copy;
        }
        if (value instanceof List<?> list) return list.stream().map(item -> remapValue(item, replacements)).toList();
        return value;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> route(Map<String, Object> mission, String routeId) {
        return ((List<Map<String, Object>>) mission.getOrDefault("routes", List.of())).stream()
                .filter(route -> routeId.equals(String.valueOf(route.get("routeId")))).findFirst().orElseThrow();
    }

    private static GroundVariant variant(String id, String name, double[][] points) { return new GroundVariant(id, name, coordinates(points)); }
    private static List<List<Number>> coordinates(double[][] points) {
        List<List<Number>> result = new ArrayList<>();
        for (double[] point : points) result.add(List.of(point[0], point[1], point.length > 2 ? point[2] : .35));
        return List.copyOf(result);
    }
    private static List<List<Number>> coordinateLists(List<double[]> points) {
        return points.stream().map(point -> List.<Number>of(point[0], point[1], point.length > 2 ? point[2] : 0)).toList();
    }

    public record Descriptor(String id, String name, String vehicleId, String uavId,
                             String groundRouteId, String airRouteId, int defaultAltitude, boolean fixedGroundEndpoints) {}
    public record GroundVariant(String id, String name, List<List<Number>> points) {}
}
