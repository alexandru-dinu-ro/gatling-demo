package com.example.tests.api.performance.scenarios;

import org.testng.annotations.Test;

import java.time.Duration;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.expectThrows;

public class TrafficMathTest {

    @Test
    public void usersPerSecondDividesByRequestsPerAction() {
        assertEquals(TrafficMath.usersPerSecond(2, 1), 2.0);
        assertEquals(TrafficMath.usersPerSecond(2, 2), 1.0);
        assertEquals(TrafficMath.usersPerSecond(10, 2.5), 4.0);
    }

    @Test
    public void defaultStressHasTenLevelsOfTwentyMinutes() {
        int levels = TrafficMath.stressLevels(1, 1, 10);

        assertEquals(levels, 10);
        assertEquals(TrafficMath.stressLength(levels, Duration.ofMinutes(2)), Duration.ofMinutes(20));
    }

    @Test
    public void unevenStepsNeverPassTheCap() {
        assertEquals(TrafficMath.stressLevels(1, 2, 10), 5, "1, 3, 5, 7, 9");
        assertEquals(TrafficMath.stressLevels(1, 3, 10), 4, "1, 4, 7, 10");
        assertEquals(TrafficMath.stressLevels(10, 1, 10), 1, "start at the cap");
    }

    @Test
    public void invalidInputsAreRejected() {
        expectThrows(IllegalArgumentException.class, () -> TrafficMath.usersPerSecond(0, 1));
        expectThrows(IllegalArgumentException.class, () -> TrafficMath.usersPerSecond(1, 0.5));
        expectThrows(IllegalArgumentException.class, () -> TrafficMath.stressLevels(11, 1, 10));
        expectThrows(IllegalArgumentException.class, () -> TrafficMath.stressLevels(1, 0, 10));
    }
}
