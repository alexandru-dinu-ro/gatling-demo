package com.example.tests.api.performance.simulations;

import com.example.tests.api.performance.scenarios.IsolatedAction;

/** Stress run of the isolated CREATE action (section 8.4). */
public class StressCreateSimulation extends StressIsolatedSimulation {

    @Override
    protected IsolatedAction action() {
        return IsolatedAction.CREATE;
    }
}
