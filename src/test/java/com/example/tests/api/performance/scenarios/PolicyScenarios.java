package com.example.tests.api.performance.scenarios;

import com.example.tests.api.performance.config.Setting;
import com.example.tests.api.performance.data.PolicyKind;
import com.example.tests.api.performance.requests.PolicyRequests;
import com.example.tests.api.performance.requests.RequestName;
import com.example.tests.api.performance.safety.SafetyMonitor;
import com.example.tests.api.performance.simulations.SimulationRuntime;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.Session;

import java.util.Objects;
import java.util.function.Function;

import static io.gatling.javaapi.core.CoreDsl.crashLoadGeneratorIf;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.stopLoadGeneratorIf;

/** Reusable action chains built from {@link PolicyRequests}. */
public final class PolicyScenarios {

    private static final String PAGES_READ = "pagesRead";
    private static final String DEFAULT_STOP_MESSAGE = "safety stop";

    private final SimulationRuntime runtime;
    private final PolicyRequests requests;
    private final int listMaxPages;

    public PolicyScenarios(SimulationRuntime runtime, PolicyRequests requests) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.listMaxPages = runtime.config().getInt(Setting.LIST_MAX_PAGES);
    }

    /** First page, then next pages while a token exists, up to {@code listMaxPages} pages in total. */
    public ChainBuilder listWithPaging(boolean filtered) {
        RequestName first = filtered ? RequestName.LIST_FILTERED : RequestName.LIST_ALL;
        RequestName next = filtered ? RequestName.LIST_FILTERED_NEXT : RequestName.LIST_ALL_NEXT;
        return exec(session -> session.set(PAGES_READ, 1))
                .exec(requests.listFirstPage(first, filtered))
                .asLongAs(session -> PolicyRequests.hasNextToken(session) && session.getInt(PAGES_READ) < listMaxPages)
                .on(exec(requests.listNextPage(next, filtered))
                        .exec(session -> session.set(PAGES_READ, session.getInt(PAGES_READ) + 1)));
    }

    /**
     * Stops the whole run when the SafetyMonitor says so.
     *
     * @param crash true to fail the run (smoke, load, spike, soak); false to stop cleanly (stress)
     */
    public ChainBuilder safetyStop(boolean crash) {
        Function<Session, String> message = session -> runtime.monitor().stopReason()
                .map(SafetyMonitor.StopReason::message).orElse(DEFAULT_STOP_MESSAGE);
        Function<Session, Boolean> mustStop = session -> runtime.monitor().shouldStop();
        return crash ? crashLoadGeneratorIf(message, mustStop) : stopLoadGeneratorIf(message, mustStop);
    }

    /** Smoke test (section 8.2): every step once, in order, stopping at the first failure. */
    public ChainBuilder smoke() {
        return exec(listWithPaging(false)).exitHereIfFailed()
                .exec(listWithPaging(true)).exitHereIfFailed()
                .exec(requests.pickSeed())
                .exec(requests.getPolicy(PolicyRequests.SEED_ID)).exitHereIfFailed()
                .exec(requests.updatePolicy(PolicyRequests.SEED_ID, PolicyRequests.SEED_NAME)).exitHereIfFailed()
                .exec(requests.createPolicy(PolicyKind.SMOKE)).exitHereIfFailed()
                .exec(requests.getPolicy(PolicyRequests.POLICY_ID)).exitHereIfFailed()
                .exec(requests.updatePolicy(PolicyRequests.POLICY_ID, PolicyRequests.POLICY_NAME)).exitHereIfFailed()
                .exec(requests.deletePolicy(PolicyRequests.POLICY_ID)).exitHereIfFailed()
                .exec(safetyStop(true));
    }
}
