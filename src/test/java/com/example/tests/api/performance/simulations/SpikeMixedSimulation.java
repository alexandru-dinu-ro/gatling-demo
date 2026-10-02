package com.example.tests.api.performance.simulations;

import com.example.tests.api.performance.config.Setting;
import com.example.tests.api.performance.scenarios.TrafficShapes;

import java.time.Duration;
import java.util.List;

import static io.gatling.javaapi.core.CoreDsl.scenario;

/**
 * Spike P1 (section 8.5): mixed action at {@code normalRate}, an instant jump to {@code spikeRate}
 * for {@code spikeDurationSec}, then {@code normalRate} again. Logs p95 per phase and whether the
 * last minutes of recovery returned to within {@code spikeRecoveryTolerancePct} of the warm-up.
 */
public class SpikeMixedSimulation extends BasePolicySimulation {

    private static final String SCENARIO_NAME = "Spike mixed";
    /** Recovery is judged on its last minutes only (section 8.5), capped at the recovery length. */
    private static final Duration RECOVERY_TAIL = Duration.ofMinutes(3);

    @Override
    protected Plan plan() {
        TrafficShapes.SpikeShape spike = shapes().spike(scenarios().mixedRequestsPerAction());
        Duration end = spike.shape().length();
        Duration recoveryLength = end.minus(spike.recoveryStart());
        Duration tail = RECOVERY_TAIL.compareTo(recoveryLength) < 0 ? RECOVERY_TAIL : recoveryLength;

        WindowReport report = WindowReport.withRecoveryCheck(List.of(
                new WindowReport.Window("warm-up", Duration.ZERO, spike.spikeStart()),
                new WindowReport.Window("spike", spike.spikeStart(), spike.recoveryStart()),
                new WindowReport.Window("recovery (last " + tail.toMinutes() + " min)", end.minus(tail), end)),
                runtime().config().getDouble(Setting.SPIKE_RECOVERY_TOLERANCE_PCT));

        return new Plan(
                scenario(SCENARIO_NAME).exec(scenarios().mixed(true)).injectOpen(spike.shape().steps()),
                true, end, true, report);
    }
}
