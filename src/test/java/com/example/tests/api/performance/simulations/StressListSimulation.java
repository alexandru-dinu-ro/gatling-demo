package com.example.tests.api.performance.simulations;

import com.example.tests.api.performance.scenarios.IsolatedAction;

/** Stress run of the isolated LIST action (section 8.4). */
public class StressListSimulation extends StressIsolatedSimulation {

    @Override
    protected IsolatedAction action() {
        return IsolatedAction.LIST;
    }
}
