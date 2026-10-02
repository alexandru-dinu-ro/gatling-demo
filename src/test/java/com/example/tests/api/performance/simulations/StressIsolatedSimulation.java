package com.example.tests.api.performance.simulations;

import com.example.tests.api.performance.scenarios.IsolatedAction;
import com.example.tests.api.performance.scenarios.TrafficShapes;

import static io.gatling.javaapi.core.CoreDsl.scenario;

/**
 * Stress run of one isolated action (section 8.4): stepped from {@code stressStartRate} to
 * {@code maxRps}. No assertions; a safety stop ends the run cleanly with the limit recorded.
 */
public abstract class StressIsolatedSimulation extends BasePolicySimulation {

    /** The action this stress run repeats. Must return a constant (called from the constructor). */
    protected abstract IsolatedAction action();

    @Override
    protected Plan plan() {
        IsolatedAction action = action();
        TrafficShapes.Shape shape = shapes().stress(scenarios().requestsPerAction(action));
        return new Plan(
                scenario("Stress " + action.name().toLowerCase()).exec(scenarios().isolated(action, false))
                        .injectOpen(shape.steps()),
                action.needsSeed(), shape.length(), false);
    }
}
