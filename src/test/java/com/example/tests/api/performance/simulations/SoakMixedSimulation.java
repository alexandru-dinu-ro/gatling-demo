package com.example.tests.api.performance.simulations;

import com.example.tests.api.performance.config.Setting;
import com.example.tests.api.performance.scenarios.TrafficShapes;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.scenario;

/**
 * Soak K1 (section 8.5): mixed action ramped to {@code normalRate}, then held for
 * {@code soakDurationMin}. Logs p95 per {@code soakReportWindowMin} window to show drift.
 */
public class SoakMixedSimulation extends BasePolicySimulation {

    private static final String SCENARIO_NAME = "Soak mixed";
    private static final String WINDOW_LABEL = "soak";

    @Override
    protected Plan plan() {
        TrafficShapes.Shape shape = shapes().soak(scenarios().mixedRequestsPerAction());
        Duration window = Duration.ofMinutes(runtime().config().getInt(Setting.SOAK_REPORT_WINDOW_MIN));
        WindowReport report = WindowReport.of(WindowReport.consecutive(WINDOW_LABEL, window, shape.length()));
        return new Plan(
                scenario(SCENARIO_NAME).exec(scenarios().mixed(true)).injectOpen(shape.steps()),
                true, shape.length(), true, report);
    }
}
