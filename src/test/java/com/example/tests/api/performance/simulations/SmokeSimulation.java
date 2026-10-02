package com.example.tests.api.performance.simulations;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.scenario;

/**
 * Smoke test (section 8.2): one user runs every step once, in order, stopping at the
 * first failure. Must pass before any other simulation is run.
 *
 * <pre>mvn gatling:test -Dgatling.simulationClass=com.example.tests.api.performance.simulations.SmokeSimulation</pre>
 */
public class SmokeSimulation extends BasePolicySimulation {

    private static final String SCENARIO_NAME = "Smoke";
    private static final Duration PLANNED_LENGTH = Duration.ofMinutes(5);

    @Override
    protected Plan plan() {
        return new Plan(
                scenario(SCENARIO_NAME).exec(scenarios().smoke()).injectOpen(atOnceUsers(1)),
                true,
                PLANNED_LENGTH,
                true);
    }
}
