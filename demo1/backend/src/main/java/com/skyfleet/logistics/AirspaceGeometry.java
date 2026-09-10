package com.skyfleet.logistics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic 2.5D airspace calculations shared by planning and simulation. */
public final class AirspaceGeometry {
    private static final int SAMPLE_COUNT = 400;
    private static final int MAX_MANEUVER_PASSES = 12;
    private static final List<String> BLOCKING_RULES = List.of(
            "ABSOLUTE_NO_FLY", "TEMPORARY_NO_FLY", "DANGER_AIRSPACE", "ALTITUDE_CORRIDOR");

    private AirspaceGeometry() {}

    public static boolean active(Map<String, Object> volume, long simulationTimeMs) {
        long from = longNumber(volume.get("activeFromSimulationMs"), 0);
        long until = longNumber(volume.get("activeUntilSimulationMs"), Long.MAX_VALUE);
        return simulationTimeMs >= from && simulationTimeMs < until;
    }

    public static boolean blocking(Map<String, Object> volume) {
        return Boolean.TRUE.equals(volume.get("blocking")) || BLOCKING_RULES.contains(String.valueOf(volume.get("ruleType")));
    }

    public static Conflict conflict(List<double[]> route, Map<String, Object> volume, double fromRouteProgress) {
        List<double[]> polygon = footprint(volume);
        if (route.size() < 2 || polygon.size() < 3) return null;
        double floor = number(volume.get("floorMeters"), 0);
        double ceiling = number(volume.get("ceilingMeters"), Double.POSITIVE_INFINITY);
        double start = -1, end = -1;
        for (int index = 0; index <= SAMPLE_COUNT; index++) {
            double progress = index * 100.0 / SAMPLE_COUNT;
            if (progress + 1e-9 < fromRouteProgress) continue;
            double[] point = MissionMath.sample(route, progress / 100);
            boolean inside = contains(volume, point);
            if (inside && start < 0) start = progress;
            if (inside) end = progress;
            else if (start >= 0) break;
        }
        if (start < 0) return null;
        double[] entry = MissionMath.sample(route, start / 100);
        return new Conflict(start, Math.max(start, end), entry,
                Math.max(0, MissionMath.polylineDistance(route) * (start - fromRouteProgress) / 100));
    }

    public static List<double[]> detour(List<double[]> route, Map<String, Object> volume) {
        return detour(route, volume, 0);
    }

    /** Builds a detour without rewriting the already-flown prefix. */
    public static List<double[]> detour(List<double[]> route, Map<String, Object> volume, double fromRouteProgress) {
        List<double[]> result = route;
        double cursor = MissionMath.clamp(fromRouteProgress, 0, 100);
        for (int pass = 0; pass < MAX_MANEUVER_PASSES; pass++) {
            if (conflict(result, volume, cursor) == null) return result;
            double[] resumePoint = MissionMath.sample(result, cursor / 100);
            boolean escapingCurrentIncursion = contains(volume, resumePoint);
            result = detourOnce(result, volume, cursor);
            cursor = closestRouteProgress(result, resumePoint);
            if (escapingCurrentIncursion) cursor = progressAfterLeavingCurrentIncursion(result, volume, cursor);
        }
        return result;
    }

    private static List<double[]> detourOnce(List<double[]> route, Map<String, Object> volume,
                                              double fromRouteProgress) {
        Conflict conflict = conflict(route, volume, fromRouteProgress);
        List<double[]> polygon = footprint(volume);
        if (conflict == null || polygon.size() < 3) return route;
        double minLng = polygon.stream().mapToDouble(point -> point[0]).min().orElse(0);
        double maxLng = polygon.stream().mapToDouble(point -> point[0]).max().orElse(0);
        double minLat = polygon.stream().mapToDouble(point -> point[1]).min().orElse(0);
        double maxLat = polygon.stream().mapToDouble(point -> point[1]).max().orElse(0);
        double marginLng = Math.max(.00020, (maxLng - minLng) * .40);
        double marginLat = Math.max(.00018, (maxLat - minLat) * .40);
        Conflict referenceConflict = conflict(route, volume, 0);
        if (referenceConflict == null) return route;
        double referenceBeforeProgress = Math.max(0, referenceConflict.startProgress() - 1.5);
        double beforeProgress = Math.max(MissionMath.clamp(fromRouteProgress, 0, 100), referenceBeforeProgress);
        double afterProgress = Math.min(100, conflict.endProgress() + 1.5);
        double[] before = MissionMath.sample(route, beforeProgress / 100);
        double[] after = MissionMath.sample(route, afterProgress / 100);
        double[] referenceBefore = MissionMath.sample(route, referenceBeforeProgress / 100);
        double altitude = Math.max(before[2], after[2]);
        double westLng = minLng - marginLng, eastLng = maxLng + marginLng;
        double southLat = minLat - marginLat, northLat = maxLat + marginLat;
        double[] northWest = new double[]{westLng, northLat, altitude};
        double[] northEast = new double[]{eastLng, northLat, altitude};
        double[] southWest = new double[]{westLng, southLat, altitude};
        double[] southEast = new double[]{eastLng, southLat, altitude};
        List<List<double[]>> candidates = List.of(
                List.of(new double[]{before[0], northLat, altitude}, new double[]{after[0], northLat, altitude}),
                List.of(new double[]{before[0], southLat, altitude}, new double[]{after[0], southLat, altitude}),
                List.of(new double[]{westLng, before[1], altitude}, new double[]{westLng, after[1], altitude}),
                List.of(new double[]{eastLng, before[1], altitude}, new double[]{eastLng, after[1], altitude}),
                List.of(northWest, northEast), List.of(northEast, northWest),
                List.of(southWest, southEast), List.of(southEast, southWest),
                List.of(southWest, northWest), List.of(northWest, southWest),
                List.of(southEast, northEast), List.of(northEast, southEast)
        );
        List<List<double[]>> referenceCandidates = List.of(
                List.of(new double[]{referenceBefore[0], northLat, altitude}, new double[]{after[0], northLat, altitude}),
                List.of(new double[]{referenceBefore[0], southLat, altitude}, new double[]{after[0], southLat, altitude}),
                List.of(new double[]{westLng, referenceBefore[1], altitude}, new double[]{westLng, after[1], altitude}),
                List.of(new double[]{eastLng, referenceBefore[1], altitude}, new double[]{eastLng, after[1], altitude}),
                List.of(northWest, northEast), List.of(northEast, northWest),
                List.of(southWest, southEast), List.of(southEast, southWest),
                List.of(southWest, northWest), List.of(northWest, southWest),
                List.of(southEast, northEast), List.of(northEast, southEast)
        );
        // Consider all four sides of the expanded footprint. Keeping each
        // connector level with the join point makes the first manoeuvre lateral
        // instead of sending the aircraft backwards to a rectangle corner.
        // Pick the same side for the entire approach. The actual join point may
        // move forward when the operator reacts late, but stable side selection
        // keeps authored rewards on the resulting manoeuvre route.
        List<Integer> clearCandidates = java.util.stream.IntStream.range(0, candidates.size()).boxed()
                .filter(index -> clearsAfterCurrentIncursion(
                        detourSection(before, candidates.get(index), after), volume))
                .toList();
        List<Integer> forwardCandidates = clearCandidates.stream()
                .filter(index -> progressesForward(referenceBefore, referenceCandidates.get(index), after))
                .toList();
        List<Integer> preferredCandidates = forwardCandidates.isEmpty() ? clearCandidates : forwardCandidates;
        int selected = preferredCandidates.stream()
                .min(java.util.Comparator.comparingDouble(index -> pathLength(referenceBefore, referenceCandidates.get(index), after)))
                .orElseGet(() -> java.util.stream.IntStream.range(0, candidates.size()).boxed()
                        .min(java.util.Comparator.comparingDouble(index -> pathLength(referenceBefore, referenceCandidates.get(index), after)))
                        .orElseThrow());
        List<double[]> around = candidates.get(selected);
        List<double[]> result = new ArrayList<>();
        double total = Math.max(1, MissionMath.polylineDistance(route));
        double traversed = 0;
        result.add(route.get(0).clone());
        for (int index = 1; index < route.size(); index++) {
            traversed += MissionMath.distance(route.get(index - 1), route.get(index));
            if (traversed / total * 100 < beforeProgress) result.add(route.get(index).clone());
        }
        result.add(before); result.addAll(around); result.add(after);
        traversed = 0;
        for (int index = 1; index < route.size(); index++) {
            traversed += MissionMath.distance(route.get(index - 1), route.get(index));
            if (traversed / total * 100 > afterProgress) result.add(route.get(index).clone());
        }
        return result;
    }

    /** Builds a local climb-over manoeuvre and rejoins the authored route. */
    public static List<double[]> climbOver(List<double[]> route, Map<String, Object> volume, double targetAltitude) {
        return climbOver(route, volume, targetAltitude, 0);
    }

    /** Builds a local climb without rewriting the already-flown prefix. */
    public static List<double[]> climbOver(List<double[]> route, Map<String, Object> volume, double targetAltitude,
                                           double fromRouteProgress) {
        List<double[]> result = route;
        double cursor = MissionMath.clamp(fromRouteProgress, 0, 100);
        for (int pass = 0; pass < MAX_MANEUVER_PASSES; pass++) {
            if (conflict(result, volume, cursor) == null) return result;
            double[] resumePoint = MissionMath.sample(result, cursor / 100);
            result = climbOverOnce(result, volume, targetAltitude, cursor);
            cursor = closestRouteProgress(result, resumePoint);
        }
        return result;
    }

    private static List<double[]> climbOverOnce(List<double[]> route, Map<String, Object> volume,
                                                 double targetAltitude, double fromRouteProgress) {
        Conflict conflict = conflict(route, volume, fromRouteProgress);
        if (conflict == null || route.size() < 2) return route;
        double beforeProgress = Math.max(MissionMath.clamp(fromRouteProgress, 0, 100), conflict.startProgress() - 2.5);
        double afterProgress = Math.min(100, conflict.endProgress() + 2.5);
        double[] before = MissionMath.sample(route, beforeProgress / 100);
        double[] entry = MissionMath.sample(route, conflict.startProgress() / 100);
        double[] exit = MissionMath.sample(route, conflict.endProgress() / 100);
        double[] after = MissionMath.sample(route, afterProgress / 100);
        List<double[]> result = new ArrayList<>();
        double total = Math.max(1, MissionMath.polylineDistance(route));
        double traversed = 0;
        result.add(route.get(0).clone());
        for (int index = 1; index < route.size(); index++) {
            traversed += MissionMath.distance(route.get(index - 1), route.get(index));
            if (traversed / total * 100 < beforeProgress) result.add(route.get(index).clone());
        }
        result.add(before);
        result.add(new double[]{entry[0], entry[1], Math.max(entry[2], targetAltitude)});
        result.add(new double[]{exit[0], exit[1], Math.max(exit[2], targetAltitude)});
        result.add(after);
        traversed = 0;
        for (int index = 1; index < route.size(); index++) {
            traversed += MissionMath.distance(route.get(index - 1), route.get(index));
            if (traversed / total * 100 > afterProgress) result.add(route.get(index).clone());
        }
        return result;
    }

    /**
     * Builds a smooth altitude transition through the legal gap of one
     * ALTITUDE_CORRIDOR. The approach starts at least 120 physical metres
     * before the blocked footprint whenever enough unflown route remains.
     */
    public static List<double[]> transitCorridor(List<double[]> route, Map<String, Object> volume) {
        return transitCorridor(route, volume, 0);
    }

    public static List<double[]> transitCorridor(List<double[]> route, Map<String, Object> volume,
                                                  double fromRouteProgress) {
        List<double[]> result = route;
        double cursor = MissionMath.clamp(fromRouteProgress, 0, 100);
        for (int pass = 0; pass < MAX_MANEUVER_PASSES; pass++) {
            if (conflict(result, volume, cursor) == null) return result;
            double[] resumePoint = MissionMath.sample(result, cursor / 100);
            result = transitCorridorOnce(result, volume, cursor);
            cursor = closestRouteProgress(result, resumePoint);
        }
        return result;
    }

    private static List<double[]> transitCorridorOnce(List<double[]> route, Map<String, Object> volume,
                                                       double fromRouteProgress) {
        Conflict conflict = conflict(route, volume, fromRouteProgress);
        if (conflict == null || route.size() < 2) return route;
        double total = Math.max(1, MissionMath.polylineDistance(route));
        double approachPercent = 120.0 / total * 100;
        double earliest = MissionMath.clamp(fromRouteProgress, 0, 100);
        double beforeProgress = Math.max(earliest, conflict.startProgress() - approachPercent);
        double afterProgress = Math.min(100, conflict.endProgress() + Math.max(2.5, approachPercent * .2));
        double targetAltitude = number(volume.get("targetAltitudeMeters"),
                (number(volume.get("corridorFloorMeters"), 60)
                        + number(volume.get("corridorCeilingMeters"), 80)) / 2);
        double[] before = MissionMath.sample(route, beforeProgress / 100);
        double[] entry = MissionMath.sample(route, conflict.startProgress() / 100);
        double[] exit = MissionMath.sample(route, conflict.endProgress() / 100);
        double[] after = MissionMath.sample(route, afterProgress / 100);
        double approachMidProgress = beforeProgress + (conflict.startProgress() - beforeProgress) * .55;
        double departureMidProgress = conflict.endProgress() + (afterProgress - conflict.endProgress()) * .45;
        double[] approachMid = MissionMath.sample(route, approachMidProgress / 100);
        double[] departureMid = MissionMath.sample(route, departureMidProgress / 100);
        approachMid[2] = before[2] + (targetAltitude - before[2]) * .65;
        entry[2] = targetAltitude;
        exit[2] = targetAltitude;
        departureMid[2] = targetAltitude + (after[2] - targetAltitude) * .65;

        List<double[]> result = new ArrayList<>();
        double traversed = 0;
        result.add(route.get(0).clone());
        for (int index = 1; index < route.size(); index++) {
            traversed += MissionMath.distance(route.get(index - 1), route.get(index));
            if (traversed / total * 100 < beforeProgress) result.add(route.get(index).clone());
        }
        result.add(before); result.add(approachMid); result.add(entry); result.add(exit); result.add(departureMid); result.add(after);
        traversed = 0;
        for (int index = 1; index < route.size(); index++) {
            traversed += MissionMath.distance(route.get(index - 1), route.get(index));
            if (traversed / total * 100 > afterProgress) result.add(route.get(index).clone());
        }
        return result;
    }

    public static boolean contains(Map<String, Object> volume, double[] point) {
        double floor = number(volume.get("floorMeters"), 0);
        double ceiling = number(volume.get("ceilingMeters"), Double.POSITIVE_INFINITY);
        if (point == null || point.length < 3 || !pointInPolygon(point, footprint(volume))) return false;
        if ("ALTITUDE_CORRIDOR".equals(String.valueOf(volume.get("ruleType")))) {
            double corridorFloor = number(volume.get("corridorFloorMeters"), floor);
            double corridorCeiling = number(volume.get("corridorCeilingMeters"), ceiling);
            // The two boundary planes belong to the legal corridor. Only the
            // volume below and above the gap is prohibited.
            return point[2] >= floor && point[2] <= ceiling
                    && (point[2] < corridorFloor - 1e-9 || point[2] > corridorCeiling + 1e-9);
        }
        return point[2] >= floor && point[2] <= ceiling;
    }

    /**
     * Fraction of a telemetry step spent inside a 2.5D volume. Midpoint
     * sampling is deliberate: a complete high-speed crossing is detected even
     * when both tick endpoints are outside the polygon.
     */
    public static double segmentExposureFraction(double[] from, double[] to, Map<String, Object> volume) {
        if (from == null || to == null) return 0;
        int samples = Math.max(16, Math.min(96, (int) Math.ceil(MissionMath.distance(from, to) / 3.0)));
        int inside = 0;
        for (int index = 0; index < samples; index++) {
            double ratio = (index + .5) / samples;
            double[] point = new double[]{
                    from[0] + (to[0] - from[0]) * ratio,
                    from[1] + (to[1] - from[1]) * ratio,
                    from[2] + (to[2] - from[2]) * ratio
            };
            if (contains(volume, point)) inside++;
        }
        return inside / (double) samples;
    }

    public static boolean segmentPassesPoint(double[] from, double[] to, double[] target,
                                             double horizontalRadiusMeters, double altitudeToleranceMeters,
                                             boolean useAltitude) {
        if (from == null || to == null || target == null) return false;
        double latitude = Math.toRadians((from[1] + to[1] + target[1]) / 3.0);
        double scaleX = 111_320 * Math.cos(latitude), scaleY = 110_540;
        double ax = (from[0] - target[0]) * scaleX, ay = (from[1] - target[1]) * scaleY;
        double bx = (to[0] - target[0]) * scaleX, by = (to[1] - target[1]) * scaleY;
        double dx = bx - ax, dy = by - ay;
        double denominator = dx * dx + dy * dy;
        double ratio = denominator <= 1e-9 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / denominator));
        double nearestX = ax + dx * ratio, nearestY = ay + dy * ratio;
        if (Math.hypot(nearestX, nearestY) > horizontalRadiusMeters) return false;
        if (!useAltitude) return true;
        double altitude = from[2] + (to[2] - from[2]) * ratio;
        return Math.abs(altitude - target[2]) <= altitudeToleranceMeters;
    }

    /**
     * Checks the portion of an executable route swept between two telemetry
     * positions. Unlike a single endpoint chord, this preserves intermediate
     * route corners, so high simulation speeds cannot skip a reward placed on
     * a detour or climb-over bend.
     */
    public static boolean routeSectionPassesPoint(List<double[]> route, double[] from, double[] to, double[] target,
                                                  double horizontalRadiusMeters, double altitudeToleranceMeters,
                                                  boolean useAltitude) {
        if (segmentPassesPoint(from, to, target, horizontalRadiusMeters, altitudeToleranceMeters, useAltitude)) return true;
        if (route == null || route.size() < 2 || from == null || to == null) return false;
        double total = MissionMath.polylineDistance(route);
        if (total <= 1e-9) return false;
        double fromDistance = closestRouteProgress(route, from) / 100 * total;
        double toDistance = closestRouteProgress(route, to) / 100 * total;
        boolean forward = toDistance >= fromDistance;
        double lower = Math.min(fromDistance, toDistance), upper = Math.max(fromDistance, toDistance);
        List<double[]> swept = new ArrayList<>();
        swept.add(from);
        double traversed = 0;
        if (forward) {
            for (int index = 1; index < route.size(); index++) {
                traversed += MissionMath.distance(route.get(index - 1), route.get(index));
                if (traversed > lower + 1e-6 && traversed < upper - 1e-6) swept.add(route.get(index));
            }
        } else {
            double[] cumulative = new double[route.size()];
            for (int index = 1; index < route.size(); index++)
                cumulative[index] = cumulative[index - 1] + MissionMath.distance(route.get(index - 1), route.get(index));
            for (int index = route.size() - 2; index > 0; index--)
                if (cumulative[index] > lower + 1e-6 && cumulative[index] < upper - 1e-6) swept.add(route.get(index));
        }
        swept.add(to);
        for (int index = 1; index < swept.size(); index++)
            if (segmentPassesPoint(swept.get(index - 1), swept.get(index), target,
                    horizontalRadiusMeters, altitudeToleranceMeters, useAltitude)) return true;
        return false;
    }

    public static double distanceToFootprintMeters(Map<String, Object> volume, double[] point) {
        List<double[]> polygon = footprint(volume);
        if (polygon.size() < 2 || point == null) return Double.POSITIVE_INFINITY;
        if (pointInPolygon(point, polygon)) return 0;
        double best = Double.POSITIVE_INFINITY;
        for (int index = 0; index < polygon.size(); index++) {
            best = Math.min(best, segmentDistanceMeters(point, polygon.get(index), polygon.get((index + 1) % polygon.size())));
        }
        return best;
    }

    /** Returns the distance-based route percentage nearest to a physical point. */
    public static double closestRouteProgress(List<double[]> route, double[] point) {
        if (route == null || route.size() < 2 || point == null) return 0;
        double total = MissionMath.polylineDistance(route);
        if (total <= 1e-9) return 0;
        double bestSquared = Double.POSITIVE_INFINITY;
        double bestDistance = 0;
        double traversed = 0;
        for (int index = 1; index < route.size(); index++) {
            double[] start = route.get(index - 1), end = route.get(index);
            double latitude = Math.toRadians((start[1] + end[1] + point[1]) / 3.0);
            double scaleX = 111_320 * Math.cos(latitude), scaleY = 110_540;
            double ax = (start[0] - point[0]) * scaleX, ay = (start[1] - point[1]) * scaleY, az = start[2] - point[2];
            double bx = (end[0] - point[0]) * scaleX, by = (end[1] - point[1]) * scaleY, bz = end[2] - point[2];
            double dx = bx - ax, dy = by - ay, dz = bz - az;
            double denominator = dx * dx + dy * dy + dz * dz;
            double ratio = denominator <= 1e-9 ? 0 : MissionMath.clamp(-(ax * dx + ay * dy + az * dz) / denominator, 0, 1);
            double px = ax + dx * ratio, py = ay + dy * ratio, pz = az + dz * ratio;
            double squared = px * px + py * py + pz * pz;
            double segmentLength = MissionMath.distance(start, end);
            if (squared < bestSquared) {
                bestSquared = squared;
                bestDistance = traversed + segmentLength * ratio;
            }
            traversed += segmentLength;
        }
        return MissionMath.clamp(bestDistance / total * 100, 0, 100);
    }

    public static Map<String, Object> view(Conflict conflict, Map<String, Object> volume, double speedMetersPerSecond) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", "CONFLICT-" + volume.get("id")); value.put("volumeId", volume.get("id"));
        value.put("ruleType", volume.get("ruleType")); value.put("blocking", blocking(volume));
        value.put("startRouteProgress", conflict.startProgress()); value.put("endRouteProgress", conflict.endProgress());
        value.put("entryPoint", List.of(conflict.entryPoint()[0], conflict.entryPoint()[1], conflict.entryPoint()[2]));
        value.put("distanceMeters", Math.round(conflict.distanceMeters()));
        value.put("estimatedEntrySeconds", Math.ceil(conflict.distanceMeters() / Math.max(.1, speedMetersPerSecond)));
        List<String> configured = stringList(volume.get("availableActions"));
        List<String> actions = new ArrayList<>(configured.isEmpty() ? defaultActions(volume) : configured);
        if ("ALTITUDE_CORRIDOR".equals(String.valueOf(volume.get("ruleType")))
                && conflict.distanceMeters() + 1e-6 < 120)
            actions.remove("TRANSIT_CORRIDOR");
        value.put("availableActions", actions);
        return value;
    }

    private static List<String> defaultActions(Map<String, Object> volume) {
        return switch (String.valueOf(volume.get("ruleType"))) {
            case "ABSOLUTE_NO_FLY" -> List.of("DETOUR", "RETURN_TO_RECOVERY");
            case "RISK_AIRSPACE" -> List.of("ACCEPT_RISK", "DETOUR");
            case "ALTITUDE_CORRIDOR" -> List.of("TRANSIT_CORRIDOR", "DETOUR", "RETURN_TO_RECOVERY");
            case "ALTITUDE_RESTRICTED" -> List.of("DETOUR", "CLIMB_OVER", "RETURN_TO_RECOVERY");
            default -> blocking(volume)
                    ? List.of("DETOUR", "CLIMB_OVER", "WAIT_UNTIL_CLEAR", "RETURN_TO_RECOVERY")
                    : List.of("ACCEPT_RISK", "DETOUR");
        };
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : list) if (item != null && !String.valueOf(item).isBlank()) result.add(String.valueOf(item));
        return result;
    }

    public static List<double[]> footprint(Map<String, Object> volume) {
        Object raw = volume.get("footprint");
        if (!(raw instanceof List<?> list)) return List.of();
        List<double[]> result = new ArrayList<>();
        for (Object item : list) if (item instanceof List<?> point && point.size() >= 2
                && point.get(0) instanceof Number longitude && point.get(1) instanceof Number latitude)
            result.add(new double[]{longitude.doubleValue(), latitude.doubleValue(), 0});
        return result;
    }

    static boolean pointInPolygon(double[] point, List<double[]> polygon) {
        boolean inside = false;
        for (int index = 0, previous = polygon.size() - 1; index < polygon.size(); previous = index++) {
            double[] a = polygon.get(index), b = polygon.get(previous);
            boolean crosses = (a[1] > point[1]) != (b[1] > point[1])
                    && point[0] < (b[0] - a[0]) * (point[1] - a[1]) / (b[1] - a[1]) + a[0];
            if (crosses) inside = !inside;
        }
        return inside;
    }

    private static double segmentDistanceMeters(double[] point, double[] start, double[] end) {
        double latitude = Math.toRadians(point[1]);
        double scaleX = 111_320 * Math.cos(latitude), scaleY = 110_540;
        double ax = (start[0] - point[0]) * scaleX, ay = (start[1] - point[1]) * scaleY;
        double bx = (end[0] - point[0]) * scaleX, by = (end[1] - point[1]) * scaleY;
        double dx = bx - ax, dy = by - ay;
        double ratio = dx * dx + dy * dy <= 1e-9 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / (dx * dx + dy * dy)));
        return Math.hypot(ax + dx * ratio, ay + dy * ratio);
    }

    private static List<double[]> detourSection(double[] before, List<double[]> middle, double[] after) {
        List<double[]> result = new ArrayList<>();
        result.add(before); result.addAll(middle); result.add(after);
        return result;
    }
    /**
     * A late detour can legitimately start inside the volume. Treat that first
     * contiguous exposure as the escape leg, but reject any candidate that
     * enters the volume again after it has reached clear air.
     */
    private static boolean clearsAfterCurrentIncursion(List<double[]> route, Map<String, Object> volume) {
        boolean reachedClearAir = false;
        for (int index = 0; index <= SAMPLE_COUNT; index++) {
            boolean inside = contains(volume, MissionMath.sample(route, index / (double) SAMPLE_COUNT));
            if (!inside) reachedClearAir = true;
            else if (reachedClearAir) return false;
        }
        return reachedClearAir;
    }

    /** Returns a cursor just beyond the incursion containing {@code progress}. */
    private static double progressAfterLeavingCurrentIncursion(List<double[]> route, Map<String, Object> volume,
                                                                double progress) {
        double start = MissionMath.clamp(progress, 0, 100);
        if (!contains(volume, MissionMath.sample(route, start / 100))) return start;
        for (int index = 1; index <= SAMPLE_COUNT; index++) {
            double candidate = start + (100 - start) * index / SAMPLE_COUNT;
            if (!contains(volume, MissionMath.sample(route, candidate / 100))) return candidate;
        }
        return 100;
    }

    /**
     * Prefer a side whose waypoints keep advancing along the authored route's
     * local direction. This prevents an escape point beyond the rejoin point
     * from making the aircraft visibly double back.
     */
    private static boolean progressesForward(double[] before, List<double[]> middle, double[] after) {
        double latitude = Math.toRadians((before[1] + after[1]) / 2);
        double scaleX = 111_320 * Math.cos(latitude), scaleY = 110_540;
        double directionX = (after[0] - before[0]) * scaleX;
        double directionY = (after[1] - before[1]) * scaleY;
        double directionZ = after[2] - before[2];
        double magnitude = Math.sqrt(directionX * directionX + directionY * directionY + directionZ * directionZ);
        if (magnitude <= 1e-9) return true;
        double previousProjection = 0;
        List<double[]> points = new ArrayList<>(middle);
        points.add(after);
        for (double[] point : points) {
            double projection = ((point[0] - before[0]) * scaleX * directionX
                    + (point[1] - before[1]) * scaleY * directionY
                    + (point[2] - before[2]) * directionZ) / magnitude;
            if (projection + .05 < previousProjection) return false;
            previousProjection = projection;
        }
        return true;
    }

    private static double pathLength(double[] before, List<double[]> middle, double[] after) {
        return MissionMath.distance(before, middle.get(0)) + MissionMath.distance(middle.get(0), middle.get(1))
                + MissionMath.distance(middle.get(1), after);
    }
    private static double number(Object value, double fallback) { return value instanceof Number number ? number.doubleValue() : fallback; }
    private static long longNumber(Object value, long fallback) { return value instanceof Number number ? number.longValue() : fallback; }

    public record Conflict(double startProgress, double endProgress, double[] entryPoint, double distanceMeters) {}
}
