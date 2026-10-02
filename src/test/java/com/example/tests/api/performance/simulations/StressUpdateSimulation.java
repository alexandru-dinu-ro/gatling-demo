package com.example.tests.api.performance.simulations;

import com.example.tests.api.performance.scenarios.IsolatedAction;

/** Stress run of the isolated UPDATE action (section 8.4). */
public class StressUpdateSimulation extends StressIsolatedSimulation {

    @Override
    protected IsolatedAction action() {
        return IsolatedAction.UPDATE;
    }
}
