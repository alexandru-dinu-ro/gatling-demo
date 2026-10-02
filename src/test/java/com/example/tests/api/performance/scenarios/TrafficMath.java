package com.example.tests.api.performance.scenarios;

import java.time.Duration;

/** Pure calculations behind the traffic shapes (sections 7.1 and 8.4 of the scenario document). */
public final class TrafficMath {

    private TrafficMath() {
    }

    /**
     * New virtual users per second that produce {@code targetRps} requests per second when
     * each user performs one action of {@code requestsPerAction} requests.
     */
    public static double usersPerSecond(double targetRps, double requestsPerAction) {
        if (targetRps <= 0) {
            throw new IllegalArgumentException("targetRps must be greater than 0, was " + targetRps);
        }
        if (requestsPerAction < 1) {
            throw new IllegalArgumentException("requestsPerAction must be at least 1, was " + requestsPerAction);
        }
        return targetRps / requestsPerAction;
    }

    /**
     * Number of stress levels: start, start+step, ... up to and including the cap.
     * Example: start 1, step 1, cap 10 gives 10 levels.
     */
    public static int stressLevels(double startRps, double stepRps, double capRps) {
        if (startRps <= 0 || stepRps <= 0) {
            throw new IllegalArgumentException("start and step must be greater than 0");
        }
        if (startRps > capRps) {
            throw new IllegalArgumentException("start " + startRps + " is above the cap " + capRps);
        }
        return (int) Math.floor((capRps - startRps) / stepRps + 1e-9) + 1;
    }

    /** Total length of a stress run: every level lasts {@code levelLength}. */
    public static Duration stressLength(int levels, Duration levelLength) {
        return levelLength.multipliedBy(levels);
    }
}
