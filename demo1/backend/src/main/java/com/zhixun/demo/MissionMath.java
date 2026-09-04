package com.zhixun.demo;

import java.util.List;

public final class MissionMath {
    private MissionMath() {}

    public static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }

    public static double[] sample(List<double[]> points, double progress) {
        if (points.isEmpty()) return new double[]{0, 0, 0};
        if (points.size() == 1) return points.get(0).clone();
        double total = 0;
        double[] lengths = new double[points.size() - 1];
        for (int i = 0; i < lengths.length; i++) { lengths[i] = distance(points.get(i), points.get(i + 1)); total += lengths[i]; }
        double target = clamp(progress, 0, 1) * total;
        for (int i = 0; i < lengths.length; i++) {
            if (target <= lengths[i] || i == lengths.length - 1) {
                double ratio = lengths[i] == 0 ? 0 : target / lengths[i];
                return lerp(points.get(i), points.get(i + 1), ratio);
            }
            target -= lengths[i];
        }
        return points.get(points.size() - 1).clone();
    }

    public static double[] smooth(double[] from, double[] to, double progress, double arcHeight) {
        double t = clamp(progress, 0, 1);
        double eased = t * t * (3 - 2 * t);
        double[] result = lerp(from, to, eased);
        result[2] += Math.sin(Math.PI * t) * arcHeight;
        return result;
    }

    public static double bearing(double[] from, double[] to) {
        double latitude = Math.toRadians((from[1] + to[1]) / 2);
        double east = (to[0] - from[0]) * Math.cos(latitude);
        double north = to[1] - from[1];
        return (Math.toDegrees(Math.atan2(east, north)) + 360) % 360;
    }

    private static double[] lerp(double[] a, double[] b, double t) {
        return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    private static double distance(double[] a, double[] b) {
        double lat = Math.toRadians((a[1] + b[1]) / 2);
        double x = (b[0] - a[0]) * 111320 * Math.cos(lat);
        double y = (b[1] - a[1]) * 110540;
        double z = b[2] - a[2];
        return Math.sqrt(x * x + y * y + z * z);
    }
}

