package com.example.tests.api.performance.simulations;

/**
 * Decides whether a performance simulation was explicitly selected with
 * {@code -Dgatling.simulationClass=<fully qualified class name>}. The framework runs
 * Gatling with {@code runMultipleSimulations=true}, so unselected simulations must
 * do nothing at all, and must not even read the configuration.
 */
public final class SimulationSelection {

    static final String PROPERTY = "gatling.simulationClass";

    private SimulationSelection() {
    }

    /** True if the system property names exactly this class. */
    public static boolean isSelected(Class<?> simulationClass) {
        return isSelected(simulationClass, System.getProperty(PROPERTY));
    }

    static boolean isSelected(Class<?> simulationClass, String propertyValue) {
        return propertyValue != null && propertyValue.trim().equals(simulationClass.getName());
    }
}
