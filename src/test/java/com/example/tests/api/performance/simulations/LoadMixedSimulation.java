package com.example.tests.api.performance.simulations;

import com.example.tests.api.performance.config.Setting;
import com.example.tests.api.performance.scenarios.TrafficShapes;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.scenario;

/**
 * Load L4 (section 8.3): the weighted mixed action at {@code normalRate},
 * ramped over {@code rampUpMin}, then held for {@code loadDurationMin}.
 */
public class LoadMixedSimulation extends BasePolicySimulation {

    private static final String SCENARIO_NAME = "Load mixed";

    @Override
    protected Plan plan() {
        Duration hold = Duration.ofMinutes(runtime().config().getInt(Setting.LOAD_DURATION_MIN));
        TrafficShapes.Shape shape = shapes().load(scenarios().mixedRequestsPerAction(), hold);
        return new Plan(
                scenario(SCENARIO_NAME).exec(scenarios().mixed(true)).injectOpen(shape.steps()),
                true, shape.length(), true);
    }
}
