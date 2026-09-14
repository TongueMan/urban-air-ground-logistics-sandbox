package com.skyfleet.logistics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Small deterministic road graph used by the campus advanced-routing prototype. */
final class CampusRoadGraph {
    private final List<double[]> nodes = new ArrayList<>();
    private final Map<Integer, List<Edge>> edges = new HashMap<>();
    private final List<Segment> segments = new ArrayList<>();

    CampusRoadGraph(List<Map<String, Object>> routeCandidates) {
        for (Map<String, Object> candidate : routeCandidates) {
            List<double[]> points = TaskInstanceService.points(candidate);
            for (int index = 1; index < points.size(); index++) {
                int left = node(points.get(index - 1));
                int right = node(points.get(index));
                double distance = MissionMath.distance(nodes.get(left), nodes.get(right));
                if (distance < .2) continue;
                edges.computeIfAbsent(left, ignored -> new ArrayList<>()).add(new Edge(right, distance));
                edges.computeIfAbsent(right, ignored -> new ArrayList<>()).add(new Edge(left, distance));
                segments.add(new Segment(left, right, distance));
            }
        }
    }

    Path route(List<Number> fromValue, List<Number> targetValue, List<List<Number>> mandatory,
               double snapLimitMeters) {
        double[] cursor = coordinate(fromValue);
        List<double[]> result = new ArrayList<>();
        result.add(cursor.clone());
        double total = 0;
        double[] snappedTarget = null;
        List<List<Number>> goals = new ArrayList<>();
        if (targetValue != null) goals.add(targetValue);
        goals.addAll(mandatory);
        for (int index = 0; index < goals.size(); index++) {
            Projection goal = nearest(coordinate(goals.get(index)));
            Projection start = nearest(cursor);
            if (start == null || goal == null || start.distanceMeters > snapLimitMeters || goal.distanceMeters > snapLimitMeters) return null;
            List<double[]> leg = shortest(start, goal);
            if (leg.isEmpty()) return null;
            if (index == 0 && targetValue != null) snappedTarget = goal.point.clone();
            for (double[] point : leg) appendDistinct(result, point);
            total += MissionMath.polylineDistance(leg);
            cursor = goal.point;
        }
        return new Path(result, total, snappedTarget);
    }

    /**
     * Reconnects the current position to the selected baseline inside the current mandatory-node
     * window, then preserves the selected baseline verbatim for the rest of the mission.
     */
    Path returnToBaseline(List<Number> fromValue, List<List<Number>> baselineValues,
                          List<Number> windowStartValue, List<Number> windowEndValue,
                          double snapLimitMeters) {
        List<double[]> baseline = new ArrayList<>();
        for (List<Number> value : baselineValues) baseline.add(coordinate(value));
        if (baseline.size() < 2) return null;

        double baselineDistance = MissionMath.polylineDistance(baseline);
        BaselineProjection windowStart = windowStartValue == null
                ? new BaselineProjection(baseline.get(0).clone(), 0, 0)
                : nearestOnBaseline(coordinate(windowStartValue), baseline, 0, baselineDistance);
        BaselineProjection windowEnd = windowEndValue == null
                ? new BaselineProjection(baseline.get(baseline.size() - 1).clone(), baselineDistance,
                baseline.size() - 2)
                : nearestOnBaseline(coordinate(windowEndValue), baseline, 0, baselineDistance);
        if (windowStart == null || windowEnd == null || windowStart.distanceAlong > windowEnd.distanceAlong + .2)
            return null;

        double[] current = coordinate(fromValue);
        BaselineProjection rejoin = nearestOnBaseline(current, baseline,
                windowStart.distanceAlong, windowEnd.distanceAlong);
        Projection start = nearest(current);
        Projection goal = rejoin == null ? null : nearest(rejoin.point);
        if (start == null || goal == null || start.distanceMeters > snapLimitMeters
                || goal.distanceMeters > snapLimitMeters) return null;

        List<double[]> connector = shortest(start, goal);
        if (connector.isEmpty()) return null;
        List<double[]> result = new ArrayList<>();
        for (double[] point : connector) appendDistinct(result, point);
        appendDistinct(result, rejoin.point);
        for (int index = rejoin.segmentIndex + 1; index < baseline.size(); index++)
            appendDistinct(result, baseline.get(index));
        return new Path(result, MissionMath.polylineDistance(result), null);
    }

    Projection snap(List<Number> value) { return nearest(coordinate(value)); }

    private static BaselineProjection nearestOnBaseline(double[] value, List<double[]> baseline,
                                                          double minimumDistance, double maximumDistance) {
        BaselineProjection best = null;
        double cumulative = 0;
        for (int index = 1; index < baseline.size(); index++) {
            double[] left = baseline.get(index - 1), right = baseline.get(index);
            double segmentDistance = MissionMath.distance(left, right);
            if (segmentDistance < .001) continue;
            double allowedStart = Math.max(cumulative, minimumDistance);
            double allowedEnd = Math.min(cumulative + segmentDistance, maximumDistance);
            if (allowedStart > allowedEnd + 1e-6) {
                cumulative += segmentDistance;
                continue;
            }
            double[] projected = project(value, left, right);
            double projectedDistance = cumulative + MissionMath.distance(left, projected);
            double distanceAlong = Math.max(allowedStart, Math.min(allowedEnd, projectedDistance));
            double ratio = (distanceAlong - cumulative) / segmentDistance;
            double[] point = new double[]{left[0] + (right[0] - left[0]) * ratio,
                    left[1] + (right[1] - left[1]) * ratio,
                    left[2] + (right[2] - left[2]) * ratio};
            double distanceToValue = MissionMath.distance(value, point);
            if (best == null || distanceToValue < best.distanceToValue)
                best = new BaselineProjection(point, distanceAlong, index - 1, distanceToValue);
            cumulative += segmentDistance;
        }
        return best;
    }

    private List<double[]> shortest(Projection start, Projection goal) {
        int virtualStart = nodes.size();
        int virtualGoal = virtualStart + 1;
        int count = nodes.size() + 2;
        List<List<Edge>> adjacency = new ArrayList<>(count);
        for (int index = 0; index < count; index++) adjacency.add(new ArrayList<>());
        edges.forEach((key, value) -> adjacency.get(key).addAll(value));
        connectProjection(adjacency, virtualStart, start);
        connectProjection(adjacency, virtualGoal, goal);
        if (start.segment == goal.segment) {
            double direct = MissionMath.distance(start.point, goal.point);
            adjacency.get(virtualStart).add(new Edge(virtualGoal, direct));
            adjacency.get(virtualGoal).add(new Edge(virtualStart, direct));
        }
        double[] distance = new double[count]; Arrays.fill(distance, Double.POSITIVE_INFINITY);
        int[] previous = new int[count]; Arrays.fill(previous, -1);
        distance[virtualStart] = 0;
        PriorityQueue<State> queue = new PriorityQueue<>(Comparator.comparingDouble(State::distance));
        queue.add(new State(virtualStart, 0));
        while (!queue.isEmpty()) {
            State state = queue.remove();
            if (state.distance != distance[state.node]) continue;
            if (state.node == virtualGoal) break;
            for (Edge edge : adjacency.get(state.node)) {
                double next = state.distance + edge.distance;
                if (next + 1e-6 >= distance[edge.to]) continue;
                distance[edge.to] = next; previous[edge.to] = state.node; queue.add(new State(edge.to, next));
            }
        }
        if (!Double.isFinite(distance[virtualGoal])) return List.of();
        List<Integer> indexes = new ArrayList<>();
        for (int cursor = virtualGoal; cursor >= 0; cursor = previous[cursor]) {
            indexes.add(cursor); if (cursor == virtualStart) break;
        }
        java.util.Collections.reverse(indexes);
        List<double[]> points = new ArrayList<>();
        for (int index : indexes) {
            double[] point = index == virtualStart ? start.point : index == virtualGoal ? goal.point : nodes.get(index);
            appendDistinct(points, point);
        }
        return points;
    }

    private void connectProjection(List<List<Edge>> adjacency, int virtual, Projection projection) {
        Segment segment = projection.segment;
        double leftDistance = MissionMath.distance(projection.point, nodes.get(segment.left));
        double rightDistance = MissionMath.distance(projection.point, nodes.get(segment.right));
        adjacency.get(virtual).add(new Edge(segment.left, leftDistance));
        adjacency.get(virtual).add(new Edge(segment.right, rightDistance));
        adjacency.get(segment.left).add(new Edge(virtual, leftDistance));
        adjacency.get(segment.right).add(new Edge(virtual, rightDistance));
    }

    private Projection nearest(double[] value) {
        Projection best = null;
        for (Segment segment : segments) {
            double[] left = nodes.get(segment.left), right = nodes.get(segment.right);
            double[] projected = project(value, left, right);
            double distance = MissionMath.distance(value, projected);
            if (best == null || distance < best.distanceMeters) best = new Projection(projected, distance, segment);
        }
        return best;
    }

    private int node(double[] point) {
        for (int index = 0; index < nodes.size(); index++) if (MissionMath.distance(nodes.get(index), point) <= 1) return index;
        nodes.add(point.clone()); return nodes.size() - 1;
    }

    private static double[] project(double[] point, double[] left, double[] right) {
        double latitudeScale = 111_320;
        double longitudeScale = Math.cos(Math.toRadians((left[1] + right[1]) / 2)) * latitudeScale;
        double ax = left[0] * longitudeScale, ay = left[1] * latitudeScale;
        double bx = right[0] * longitudeScale, by = right[1] * latitudeScale;
        double px = point[0] * longitudeScale, py = point[1] * latitudeScale;
        double dx = bx - ax, dy = by - ay;
        double ratio = dx * dx + dy * dy < 1e-9 ? 0 : ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy);
        ratio = Math.max(0, Math.min(1, ratio));
        return new double[]{left[0] + (right[0] - left[0]) * ratio,
                left[1] + (right[1] - left[1]) * ratio, .35};
    }

    private static double[] coordinate(List<Number> value) {
        return new double[]{value.get(0).doubleValue(), value.get(1).doubleValue(), value.size() > 2 ? value.get(2).doubleValue() : .35};
    }

    private static void appendDistinct(List<double[]> points, double[] point) {
        if (points.isEmpty() || MissionMath.distance(points.get(points.size() - 1), point) > .2) points.add(point.clone());
    }

    record Path(List<double[]> points, double distanceMeters, double[] snappedTarget) {}
    private record BaselineProjection(double[] point, double distanceAlong, int segmentIndex,
                                      double distanceToValue) {
        private BaselineProjection(double[] point, double distanceAlong, int segmentIndex) {
            this(point, distanceAlong, segmentIndex, 0);
        }
    }
    record Projection(double[] point, double distanceMeters, Segment segment) {}
    private record Segment(int left, int right, double distance) {}
    private record Edge(int to, double distance) {}
    private record State(int node, double distance) {}
}
