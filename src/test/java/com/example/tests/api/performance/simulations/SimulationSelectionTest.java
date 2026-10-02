package com.example.tests.api.performance.simulations;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class SimulationSelectionTest {

    private static final Class<?> SIMULATION = SimulationSelectionTest.class;

    @Test
    public void simpleOrFullNameSelects() {
        assertTrue(SimulationSelection.isSelected(SIMULATION, SIMULATION.getSimpleName()));
        assertTrue(SimulationSelection.isSelected(SIMULATION, SIMULATION.getName()));
        assertTrue(SimulationSelection.isSelected(SIMULATION, "  " + SIMULATION.getSimpleName() + "  "));
    }

    @DataProvider
    public Object[][] notSelecting() {
        return new Object[][]{
                {null},
                {""},
                {"SomeOtherSimulation"},
                {SIMULATION.getSimpleName().toLowerCase()},
                {SIMULATION.getSimpleName() + "X"},
                {"com.example.tests.api.performance.simulations"},
                {"com.example.tests.api.performance.simulations.SomeOtherSimulation"},
        };
    }

    @Test(dataProvider = "notSelecting")
    public void anythingElseDoesNotSelect(String value) {
        assertFalse(SimulationSelection.isSelected(SIMULATION, value), "should not select: " + value);
    }
}
