package com.example.tests.api.performance.scenarios;

import com.example.tests.api.performance.config.PerfConfig;
import com.example.tests.api.performance.config.Setting;
import io.gatling.javaapi.core.OpenInjectionStep;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.incrementUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;

/**
 * The traffic shapes of section 8, built from the settings. Rates are in requests per
 * second; each shape converts them to virtual users per second for the action's size.
 */
public final class TrafficShapes {

    /**
     * Injection steps plus the planned length of the whole shape.
     *
     * @param steps  Gatling open-model injection steps
     * @param length total planned injection length
     */
    public record Shape(List<OpenInjectionStep> steps, Duration length) {

        public Shape {
            steps = List.copyOf(steps);
            Objects.requireNonNull(length, "length");
        }
    }

    /**
     * A spike shape plus where its phases start, measured from the injection start.
     *
     * @param shape         the injection steps and total length
     * @param spikeStart    offset at which the spike begins (end of warm-up)
     * @param recoveryStart offset at which recovery begins (end of the spike)
     */
    public record SpikeShape(Shape shape, Duration spikeStart, Duration recoveryStart) {
    }

    private final double normalRate;
    private final double stressStartRate;
    private final double stressStepIncrement;
    private final double maxRps;
    private final double spikeRate;
    private final Duration rampUp;
    private final Duration stressLevelLength;
    private final Duration spikeWarmup;
    private final Duration spikeLength;
    private final Duration spikeRecovery;
    private final Duration soakLength;

    public TrafficShapes(PerfConfig config) {
        this.normalRate = config.getDouble(Setting.NORMAL_RATE);
        this.stressStartRate = config.getDouble(Setting.STRESS_START_RATE);
        this.stressStepIncrement = config.getDouble(Setting.STRESS_STEP_INCREMENT);
        this.maxRps = config.getDouble(Setting.MAX_RPS);
        this.spikeRate = config.getDouble(Setting.SPIKE_RATE);
        this.rampUp = Duration.ofMinutes(config.getInt(Setting.RAMP_UP_MIN));
        this.stressLevelLength = Duration.ofMinutes(config.getInt(Setting.STRESS_STEP_DURATION_MIN));
        this.spikeWarmup = Duration.ofMinutes(config.getInt(Setting.SPIKE_WARMUP_MIN));
        this.spikeLength = Duration.ofSeconds(config.getInt(Setting.SPIKE_DURATION_SEC));
        this.spikeRecovery = Duration.ofMinutes(config.getInt(Setting.SPIKE_RECOVERY_MIN));
        this.soakLength = Duration.ofMinutes(config.getInt(Setting.SOAK_DURATION_MIN));
    }

    /** Ramp to {@code normalRate} over {@code rampUpMin}, then hold for {@code hold}. */
    public Shape load(double requestsPerAction, Duration hold) {
        double users = TrafficMath.usersPerSecond(normalRate, requestsPerAction);
        List<OpenInjectionStep> steps = new ArrayList<>();
        if (!rampUp.isZero()) {
            steps.add(rampUsersPerSec(0).to(users).during(rampUp));
        }
        steps.add(constantUsersPerSec(users).during(hold));
        return new Shape(steps, rampUp.plus(hold));
    }

    /** {@code normalRate} for {@code length}, with no ramp (phases that follow a running phase). */
    public Shape steady(double requestsPerAction, Duration length) {
        double users = TrafficMath.usersPerSecond(normalRate, requestsPerAction);
        return new Shape(List.of(constantUsersPerSec(users).during(length)), length);
    }

    /** Load shape held for {@code soakDurationMin}. */
    public Shape soak(double requestsPerAction) {
        return load(requestsPerAction, soakLength);
    }

    /** Levels from {@code stressStartRate} by {@code stressStepIncrement} up to {@code maxRps}. */
    public Shape stress(double requestsPerAction) {
        int levels = TrafficMath.stressLevels(stressStartRate, stressStepIncrement, maxRps);
        OpenInjectionStep stairs = incrementUsersPerSec(TrafficMath.usersPerSecond(stressStepIncrement, requestsPerAction))
                .times(levels)
                .eachLevelLasting(stressLevelLength)
                .startingFrom(TrafficMath.usersPerSecond(stressStartRate, requestsPerAction));
        return new Shape(List.of(stairs), TrafficMath.stressLength(levels, stressLevelLength));
    }

    /** {@code normalRate} warm-up, an instant jump to {@code spikeRate}, then {@code normalRate} recovery. */
    public SpikeShape spike(double requestsPerAction) {
        double normalUsers = TrafficMath.usersPerSecond(normalRate, requestsPerAction);
        double spikeUsers = TrafficMath.usersPerSecond(spikeRate, requestsPerAction);
        Shape shape = new Shape(List.of(
                constantUsersPerSec(normalUsers).during(spikeWarmup),
                constantUsersPerSec(spikeUsers).during(spikeLength),
                constantUsersPerSec(normalUsers).during(spikeRecovery)),
                spikeWarmup.plus(spikeLength).plus(spikeRecovery));
        return new SpikeShape(shape, spikeWarmup, spikeWarmup.plus(spikeLength));
    }
}
