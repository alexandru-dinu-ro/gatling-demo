package com.example.tests.api.performance.simulations;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class SimulationSelectionTest {

    private static final Class<?> SIMULATION = SimulationSelectionTest.class;

    @Test
    public void exactClassNameSelects() {
        assertTrue(SimulationSelection.isSelected(SIMULATION, SIMULATION.getName()));
        assertTrue(SimulationSelection.isSelected(SIMULATION, "  " + SIMULATION.getName() + "  "));
    }

    @DataProvider
    public Object[][] notSelecting() {
        return new Object[][]{
                {null},
                {""},
                {SIMULATION.getSimpleName()},
                {"com.example.tests.api.performance.simulations"},
                {"com.example.tests.api.performance.simulations.SomeOtherSimulation"},
                {SIMULATION.getName() + "X"},
        };
    }

    @Test(dataProvider = "notSelecting")
    public void anythingElseDoesNotSelect(String value) {
        assertFalse(SimulationSelection.isSelected(SIMULATION, value), "should not select: " + value);
    }
}
