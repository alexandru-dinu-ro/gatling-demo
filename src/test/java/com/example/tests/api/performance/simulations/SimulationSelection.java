package com.example.tests.api.performance.simulations;

/**
 * Decides whether a performance simulation was explicitly selected with
 * {@code -DperfSimulation=<SimpleClassName>} (the full class name is accepted too).
 *
 * <p>Why not {@code -Dgatling.simulationClass}: the gatling-maven-plugin consumes that
 * property and does not pass it to the forked Gatling process, so a simulation cannot
 * see it (verified on Gatling 3.15.1 / plugin 4.21.11). Other {@code -D} properties do
 * reach the fork. Use {@code perf-run.sh}, which sets both.
 *
 * <p>The framework runs Gatling with {@code runMultipleSimulations=true}, so unselected
 * simulations must do nothing at all, and must not even read the configuration.
 */
public final class SimulationSelection {

    static final String PROPERTY = "perfSimulation";

    private SimulationSelection() {
    }

    /** True if {@code -DperfSimulation} names this class (simple or fully qualified name). */
    public static boolean isSelected(Class<?> simulationClass) {
        return isSelected(simulationClass, System.getProperty(PROPERTY));
    }

    static boolean isSelected(Class<?> simulationClass, String propertyValue) {
        if (propertyValue == null) {
            return false;
        }
        String value = propertyValue.trim();
        return value.equals(simulationClass.getSimpleName()) || value.equals(simulationClass.getName());
    }
}
