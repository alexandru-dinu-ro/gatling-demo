package com.example.tests.api.performance.simulations;

import com.example.tests.api.performance.config.PerfConfig;
import com.example.tests.api.performance.config.Setting;
import com.example.tests.api.performance.requests.PolicyRequests;
import com.example.tests.api.performance.scenarios.PolicyScenarios;
import com.example.tests.api.performance.scenarios.TrafficShapes;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.Simulation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.Objects;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.holdFor;
import static io.gatling.javaapi.core.CoreDsl.reachRps;
import static io.gatling.javaapi.core.CoreDsl.scenario;

/**
 * Parent of every performance simulation. Subclasses only describe their {@link Plan};
 * selection guard, runtime, throttle, assertions, window report, setup and teardown live here.
 *
 * <p>{@link #plan()} is called from this constructor, so subclasses must not rely on
 * their own instance fields inside it (use constants instead).
 */
public abstract class BasePolicySimulation extends Simulation {

    private static final Logger LOG = LogManager.getLogger(BasePolicySimulation.class);
    private static final String SKIPPED_SCENARIO = "skipped: not explicitly selected";
    /** Extra time on top of a plan's length before Gatling force-stops the run. */
    private static final Duration MAX_DURATION_MARGIN = Duration.ofMinutes(2);
    private static final Duration THROTTLE_RAMP = Duration.ofSeconds(1);

    /**
     * What a simulation runs.
     *
     * @param population     the injected scenario(s)
     * @param needsSeed      whether seeded policies are created before the run
     * @param plannedLength  expected injection length (throttle and max duration are based on it)
     * @param withAssertions whether the standard end-of-run assertions apply (false for stress)
     * @param report         time windows whose p95 is logged at the end
     */
    protected record Plan(PopulationBuilder population, boolean needsSeed, Duration plannedLength,
                          boolean withAssertions, WindowReport report) {

        public Plan {
            Objects.requireNonNull(population, "population");
            Objects.requireNonNull(plannedLength, "plannedLength");
            Objects.requireNonNull(report, "report");
        }

        /** A plan without a window report. */
        public Plan(PopulationBuilder population, boolean needsSeed, Duration plannedLength, boolean withAssertions) {
            this(population, needsSeed, plannedLength, withAssertions, WindowReport.none());
        }
    }

    private final boolean selected = SimulationSelection.isSelected(getClass());
    private SimulationRuntime runtime;
    private PolicyRequests requests;
    private PolicyScenarios scenarios;
    private TrafficShapes shapes;
    private boolean needsSeed;
    private WindowReport report = WindowReport.none();

    protected BasePolicySimulation() {
        if (!selected) {
            LOG.info("{} skipped: not explicitly selected with -D{}",
                    getClass().getSimpleName(), SimulationSelection.PROPERTY);
            setUp(scenario(SKIPPED_SCENARIO).exec(session -> session).injectOpen(atOnceUsers(1)));
            return;
        }
        PerfConfig config = PerfConfig.get();
        runtime = SimulationRuntime.create(config, getClass().getSimpleName());
        requests = new PolicyRequests(runtime);
        scenarios = new PolicyScenarios(runtime, requests);
        shapes = new TrafficShapes(config);

        Plan plan = plan();
        needsSeed = plan.needsSeed();
        report = plan.report();
        int rpsCap = (int) Math.floor(config.getDouble(Setting.MAX_RPS));
        SetUp setUp = setUp(plan.population())
                .protocols(requests.protocol())
                .throttle(reachRps(rpsCap).in(THROTTLE_RAMP), holdFor(plan.plannedLength().plus(MAX_DURATION_MARGIN)))
                .maxDuration(plan.plannedLength().plus(MAX_DURATION_MARGIN));
        if (plan.withAssertions()) {
            setUp.assertions(
                    global().failedRequests().percent().lte(config.getDouble(Setting.MAX_ERROR_PERCENT)),
                    global().responseTime().max().lt(config.getInt(Setting.MAX_RESPONSE_TIME_MS)));
        }
        LOG.info("{}: throttle {} req/s, planned length {}, seed {}, assertions {}", getClass().getSimpleName(),
                rpsCap, plan.plannedLength(), needsSeed ? "yes" : "no", plan.withAssertions() ? "on" : "off");
    }

    /** The simulation's plan. Called once, from the constructor. */
    protected abstract Plan plan();

    protected final SimulationRuntime runtime() {
        return runtime;
    }

    protected final PolicyRequests requests() {
        return requests;
    }

    protected final PolicyScenarios scenarios() {
        return scenarios;
    }

    protected final TrafficShapes shapes() {
        return shapes;
    }

    @Override
    public void before() {
        if (selected) {
            runtime.start(needsSeed);
        }
    }

    @Override
    public void after() {
        if (selected) {
            report.log(runtime.monitor());
            runtime.stop();
        }
    }
}
