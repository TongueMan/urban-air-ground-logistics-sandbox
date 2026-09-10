package com.skyfleet.logistics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class BaiduRouteProvider {
    public static final String CONTRACT_VERSION = "baidu-direction-v2/1";
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final String apiKey;
    private final String baseUrl;
    private final Duration responseTimeout;

    public BaiduRouteProvider(ObjectMapper mapper,
                              @Value("${mission.routing.baidu.api-key:}") String apiKey,
                              @Value("${mission.routing.baidu.base-url:https://api.map.baidu.com}") String baseUrl,
                              @Value("${mission.routing.baidu.connect-timeout-seconds:3}") int connectTimeout,
                              @Value("${mission.routing.baidu.response-timeout-seconds:6}") int responseTimeout) {
        this.mapper = mapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.responseTimeout = Duration.ofSeconds(Math.max(1, responseTimeout));
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.max(1, connectTimeout))).build();
    }

    public ProviderResult driving(List<double[]> anchors) {
        long started = System.nanoTime();
        if (apiKey.isBlank()) return ProviderResult.failure("NOT_CONFIGURED", elapsed(started));
        if (anchors.size() < 2) return ProviderResult.failure("INVALID_ANCHORS", elapsed(started));
        try {
            StringBuilder url = new StringBuilder(baseUrl).append("/direction/v2/driving?")
                    .append("origin=").append(encoded(coordinate(anchors.get(0))))
                    .append("&destination=").append(encoded(coordinate(anchors.get(anchors.size() - 1))))
                    .append("&coord_type=bd09ll&ret_coordtype=bd09ll&tactics=2&alternatives=0&output=json");
            if (anchors.size() > 2) {
                String waypoints = anchors.subList(1, anchors.size() - 1).stream().map(BaiduRouteProvider::coordinate)
                        .collect(java.util.stream.Collectors.joining("|"));
                url.append("&waypoints=").append(encoded(waypoints));
            }
            url.append("&ak=").append(encoded(apiKey));
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString())).timeout(responseTimeout).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) return ProviderResult.failure("HTTP_" + response.statusCode(), elapsed(started));
            Map<String, Object> body = mapper.readValue(response.body(), new TypeReference<>() {});
            if (number(body.get("status"), -1) != 0) return ProviderResult.failure("BAIDU_STATUS_" + number(body.get("status"), -1), elapsed(started));
            Map<String, Object> route = firstRoute(body);
            List<List<Number>> points = routePoints(route);
            if (points.size() < 2) return ProviderResult.failure("EMPTY_PATH", elapsed(started));
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("points", points);
            normalized.put("distanceMeters", number(route.get("distance"), polyline(points)));
            normalized.put("durationSeconds", number(route.get("duration"), 0));
            normalized.put("trafficLightCount", number(route.get("traffic_light"), 0));
            normalized.put("providerRouteId", String.valueOf(route.getOrDefault("route_id", "")));
            return ProviderResult.success(normalized, elapsed(started));
        } catch (java.net.http.HttpTimeoutException error) {
            return ProviderResult.failure("TIMEOUT", elapsed(started));
        } catch (Exception error) {
            return ProviderResult.failure("PROVIDER_ERROR", elapsed(started));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstRoute(Map<String, Object> body) {
        Object resultValue = body.get("result");
        if (!(resultValue instanceof Map<?, ?> result)) return Map.of();
        Object routesValue = result.get("routes");
        if (!(routesValue instanceof List<?> routes) || routes.isEmpty() || !(routes.get(0) instanceof Map<?, ?> route)) return Map.of();
        return (Map<String, Object>) route;
    }

    @SuppressWarnings("unchecked")
    private static List<List<Number>> routePoints(Map<String, Object> route) {
        List<List<Number>> result = new ArrayList<>();
        Object stepsValue = route.get("steps");
        if (!(stepsValue instanceof List<?> steps)) return result;
        for (Object stepValue : steps) {
            if (!(stepValue instanceof Map<?, ?> step)) continue;
            String path = String.valueOf(step.get("path") == null ? "" : step.get("path"));
            for (String raw : path.split(";")) {
                String[] pair = raw.trim().split(",");
                if (pair.length < 2) continue;
                try {
                    double longitude = Double.parseDouble(pair[0]);
                    double latitude = Double.parseDouble(pair[1]);
                    if (!Double.isFinite(longitude) || !Double.isFinite(latitude)) continue;
                    List<Number> point = List.of(longitude, latitude, .35);
                    if (result.isEmpty() || MissionMath.distance(toArray(result.get(result.size() - 1)), toArray(point)) > .2) result.add(point);
                } catch (NumberFormatException ignored) {}
            }
        }
        return result;
    }

    private static double polyline(List<List<Number>> points) {
        return MissionMath.polylineDistance(points.stream().map(BaiduRouteProvider::toArray).toList());
    }
    private static double[] toArray(List<? extends Number> point) { return new double[]{point.get(0).doubleValue(), point.get(1).doubleValue(), point.size() > 2 ? point.get(2).doubleValue() : 0}; }
    private static String coordinate(double[] point) { return String.format(java.util.Locale.ROOT, "%.6f,%.6f", point[1], point[0]); }
    private static String encoded(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static long elapsed(long started) { return Math.max(0, (System.nanoTime() - started) / 1_000_000); }
    private static double number(Object value, double fallback) { return value instanceof Number number ? number.doubleValue() : fallback; }

    public record ProviderResult(boolean success, Map<String, Object> route, String failureCode, long latencyMs) {
        static ProviderResult success(Map<String, Object> route, long latencyMs) { return new ProviderResult(true, route, null, latencyMs); }
        static ProviderResult failure(String code, long latencyMs) { return new ProviderResult(false, Map.of(), code, latencyMs); }
    }
}
