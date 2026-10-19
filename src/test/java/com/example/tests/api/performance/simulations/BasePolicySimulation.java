package com.example.tests.api.performance.simulations;

import com.example.tests.api.performance.config.PerfConfig;
import com.example.tests.api.performance.config.Setting;
import com.example.tests.api.performance.requests.PolicyRequests;
import com.example.tests.api.performance.scenarios.PolicyScenarios;
import com.example.tests.api.performance.scenarios.TrafficShapes;
import io.gatling.javaapi.core.Assertion;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.Simulation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
    private static final double P95 = 95.0;

    /**
     * What a simulation runs.
     *
     * @param population     the injected scenario(s)
     * @param needsSeed      whether seeded policies are created before the run
     * @param plannedLength  expected injection length (throttle and max duration are based on it)
     * @param withAssertions whether the standard end-of-run assertions apply (false for stress)
     * @param report         time windows whose p95 is logged at the end
     * @param p95Target      setting holding this simulation's p95 target in ms (0 = none), or null for none
     */
    protected record Plan(PopulationBuilder population, boolean needsSeed, Duration plannedLength,
                          boolean withAssertions, WindowReport report, Setting p95Target) {

        public Plan {
            Objects.requireNonNull(population, "population");
            Objects.requireNonNull(plannedLength, "plannedLength");
            Objects.requireNonNull(report, "report");
        }

        /** A plan with a window report and no p95 target. */
        public Plan(PopulationBuilder population, boolean needsSeed, Duration plannedLength, boolean withAssertions,
                    WindowReport report) {
            this(population, needsSeed, plannedLength, withAssertions, report, null);
        }

        /** A plan without a window report or p95 target. */
        public Plan(PopulationBuilder population, boolean needsSeed, Duration plannedLength, boolean withAssertions) {
            this(population, needsSeed, plannedLength, withAssertions, WindowReport.none(), null);
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
        int p95TargetMs = plan.p95Target() == null ? 0 : config.getInt(plan.p95Target());
        if (plan.withAssertions()) {
            List<Assertion> assertions = new ArrayList<>(List.of(
                    global().failedRequests().percent().lte(config.getDouble(Setting.MAX_ERROR_PERCENT)),
                    global().responseTime().max().lt(config.getInt(Setting.MAX_RESPONSE_TIME_MS))));
            if (p95TargetMs > 0) {
                assertions.add(global().responseTime().percentile(P95).lt(p95TargetMs));
            }
            setUp.assertions(assertions);
        }
        LOG.info("{}: throttle {} req/s, planned length {}, seed {}, assertions {}{}", getClass().getSimpleName(),
                rpsCap, plan.plannedLength(), needsSeed ? "yes" : "no", plan.withAssertions() ? "on" : "off",
                plan.withAssertions() && p95TargetMs > 0 ? ", p95 target " + p95TargetMs + " ms" : "");
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
