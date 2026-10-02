package com.example.tests.api.performance.simulations;

import com.example.tests.api.performance.scenarios.TrafficShapes;

import static io.gatling.javaapi.core.CoreDsl.scenario;

/**
 * Stress T7 (section 8.4): the weighted mixed action, stepped from {@code stressStartRate} to
 * {@code maxRps}. No assertions; a safety stop ends the run cleanly. Its slowdown point sets
 * the next {@code normalRate} (about 50% of it).
 */
public class StressMixedSimulation extends BasePolicySimulation {

    private static final String SCENARIO_NAME = "Stress mixed";

    @Override
    protected Plan plan() {
        TrafficShapes.Shape shape = shapes().stress(scenarios().mixedRequestsPerAction());
        return new Plan(
                scenario(SCENARIO_NAME).exec(scenarios().mixed(false)).injectOpen(shape.steps()),
                true, shape.length(), false);
    }
}
