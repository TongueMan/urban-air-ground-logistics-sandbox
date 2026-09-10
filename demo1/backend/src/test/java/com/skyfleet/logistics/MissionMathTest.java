package com.skyfleet.logistics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MissionMathTest {
    @Test
    void samplesRouteByDistanceAndClampsEndpoints() {
        List<double[]> points = List.of(new double[]{117.0, 31.0, 0}, new double[]{117.001, 31.0, 0}, new double[]{117.001, 31.002, 0});
        assertThat(MissionMath.sample(points, -1)).containsExactly(117.0, 31.0, 0);
        assertThat(MissionMath.sample(points, 2)).containsExactly(117.001, 31.002, 0);
        double[] middle = MissionMath.sample(points, .5);
        assertThat(middle[0]).isCloseTo(117.001, within(.000001));
        assertThat(middle[1]).isBetween(31.0001, 31.0010);
    }

    @Test
    void createsSmoothTakeoffArcWithExactEndpoints() {
        double[] from = {117, 31, 2};
        double[] to = {117.001, 31.001, 20};
        assertThat(MissionMath.smooth(from, to, 0, 8)).containsExactly(from);
        assertThat(MissionMath.smooth(from, to, 1, 8)).containsExactly(to);
        assertThat(MissionMath.smooth(from, to, .5, 8)[2]).isGreaterThan(18);
    }

    @Test
    void reportsCompassBearings() {
        assertThat(MissionMath.bearing(new double[]{117, 31, 0}, new double[]{117, 32, 0})).isCloseTo(0, within(.001));
        assertThat(MissionMath.bearing(new double[]{117, 31, 0}, new double[]{118, 31, 0})).isCloseTo(90, within(.001));
    }
}
