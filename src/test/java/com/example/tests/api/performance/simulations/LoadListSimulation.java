package com.example.tests.api.performance.simulations;

import com.example.tests.api.performance.config.Setting;
import com.example.tests.api.performance.scenarios.IsolatedAction;
import com.example.tests.api.performance.scenarios.TrafficShapes;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.scenario;

/**
 * Load L1 (section 8.3): the isolated list action at {@code normalRate},
 * ramped over {@code rampUpMin}, then held for {@code isolatedLoadDurationMin}.
 */
public class LoadListSimulation extends BasePolicySimulation {

    private static final String SCENARIO_NAME = "Load list";

    @Override
    protected Plan plan() {
        IsolatedAction action = IsolatedAction.LIST;
        Duration hold = Duration.ofMinutes(runtime().config().getInt(Setting.ISOLATED_LOAD_DURATION_MIN));
        TrafficShapes.Shape shape = shapes().load(scenarios().requestsPerAction(action), hold);
        return new Plan(
                scenario(SCENARIO_NAME).exec(scenarios().isolated(action, true)).injectOpen(shape.steps()),
                action.needsSeed(), shape.length(), true);
    }
}
