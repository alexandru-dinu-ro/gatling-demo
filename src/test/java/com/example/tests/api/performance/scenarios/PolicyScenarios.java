package com.example.tests.api.performance.scenarios;

import com.example.tests.api.performance.config.PerfConfig;
import com.example.tests.api.performance.config.Setting;
import com.example.tests.api.performance.data.OwnedPolicy;
import com.example.tests.api.performance.data.PolicyKind;
import com.example.tests.api.performance.requests.PolicyRequests;
import com.example.tests.api.performance.requests.RequestName;
import com.example.tests.api.performance.safety.SafetyMonitor;
import com.example.tests.api.performance.simulations.SimulationRuntime;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.Choice;
import io.gatling.javaapi.core.Session;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import static io.gatling.javaapi.core.CoreDsl.crashLoadGeneratorIf;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.percent;
import static io.gatling.javaapi.core.CoreDsl.randomSwitch;
import static io.gatling.javaapi.core.CoreDsl.stopLoadGeneratorIf;

/** Reusable action chains built from {@link PolicyRequests} (section 8 of the scenario document). */
public final class PolicyScenarios {

    private static final String PAGES_READ = "pagesRead";
    private static final String DEFAULT_STOP_MESSAGE = "safety stop";
    private static final double HALF = 50.0;
    private static final double PERCENT = 100.0;
    private static final int SINGLE_REQUEST = 1;
    private static final int PAIR = 2;
    private static final int WRITE_REQUESTS = 3;

    private final SimulationRuntime runtime;
    private final PolicyRequests requests;
    private final int listMaxPages;
    private final int mixListAllPct;
    private final int mixListFilteredPct;
    private final int mixGetPolicyPct;
    private final int mixWritePct;

    public PolicyScenarios(SimulationRuntime runtime, PolicyRequests requests) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.requests = Objects.requireNonNull(requests, "requests");
        PerfConfig config = runtime.config();
        this.listMaxPages = config.getInt(Setting.LIST_MAX_PAGES);
        this.mixListAllPct = config.getInt(Setting.MIX_LIST_ALL_PCT);
        this.mixListFilteredPct = config.getInt(Setting.MIX_LIST_FILTERED_PCT);
        this.mixGetPolicyPct = config.getInt(Setting.MIX_GET_POLICY_PCT);
        this.mixWritePct = config.getInt(Setting.MIX_WRITE_PCT);
    }

    // ------------------------------------------------------------------ building blocks

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

    // ------------------------------------------------------------------ smoke

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

    // ------------------------------------------------------------------ isolated actions (8.1)

    /** One isolated action, then the safety check. */
    public ChainBuilder isolated(IsolatedAction action, boolean crash) {
        ChainBuilder chain = switch (action) {
            case LIST -> randomSwitch().on(
                    percent(HALF).then(listWithPaging(false)),
                    percent(HALF).then(listWithPaging(true)));
            case GET -> exec(requests.pickSeed()).exec(requests.getPolicy(PolicyRequests.SEED_ID));
            case CREATE -> exec(requests.createPolicy(PolicyKind.CREATE_RUN, false))
                    .doIf(PolicyScenarios::hasCreatedPolicy)
                    .then(requests.deletePolicy(PolicyRequests.POLICY_ID, true));
            case UPDATE -> exec(requests.pickSeed())
                    .exec(requests.updatePolicy(PolicyRequests.SEED_ID, PolicyRequests.SEED_NAME));
            case DELETE -> exec(requests.createPolicy(PolicyKind.DELETE_RUN, true))
                    .doIf(PolicyScenarios::hasCreatedPolicy)
                    .then(requests.deletePolicy(PolicyRequests.POLICY_ID, false));
        };
        return chain.exec(safetyStop(crash));
    }

    /** Requests one isolated action sends (list actions budget the maximum page count). */
    public double requestsPerAction(IsolatedAction action) {
        return switch (action) {
            case LIST -> listMaxPages;
            case GET, UPDATE -> SINGLE_REQUEST;
            case CREATE, DELETE -> PAIR;
        };
    }

    // ------------------------------------------------------------------ mixed action (8.6)

    /** One weighted mixed action, then the safety check. */
    public ChainBuilder mixed(boolean crash) {
        List<Choice.WithWeight> choices = new ArrayList<>();
        addChoice(choices, mixListAllPct, listWithPaging(false));
        addChoice(choices, mixListFilteredPct, listWithPaging(true));
        addChoice(choices, mixGetPolicyPct, exec(requests.pickSeed()).exec(requests.getPolicy(PolicyRequests.SEED_ID)));
        addChoice(choices, mixWritePct, write());
        return randomSwitch().on(choices).exec(safetyStop(crash));
    }

    /** Weighted average of requests per mixed action (list actions budget the maximum page count). */
    public double mixedRequestsPerAction() {
        return ((mixListAllPct + mixListFilteredPct) * (double) listMaxPages
                + mixGetPolicyPct * (double) SINGLE_REQUEST
                + mixWritePct * (double) WRITE_REQUESTS) / PERCENT;
    }

    // ------------------------------------------------------------------ phased CRUD (8.3)

    /** CRUD phase 1: create one policy and add it to the pool. */
    public ChainBuilder crudCreate(CrudPool pool, boolean crash) {
        return exec(requests.createPolicy(PolicyKind.CREATE_RUN))
                .exec(session -> {
                    if (!session.isFailed() && session.contains(PolicyRequests.POLICY_ID)) {
                        pool.add(new OwnedPolicy(session.getString(PolicyRequests.POLICY_ID),
                                session.get(PolicyRequests.POLICY_NAME)));
                    }
                    return session;
                })
                .exec(safetyStop(crash));
    }

    /** CRUD phase 2: get one created policy. */
    public ChainBuilder crudGet(CrudPool pool, boolean crash) {
        return exec(session -> pick(session, pool, false))
                .doIf(PolicyScenarios::hasCreatedPolicy).then(requests.getPolicy(PolicyRequests.POLICY_ID))
                .exec(safetyStop(crash));
    }

    /** CRUD phase 3: update one created policy. */
    public ChainBuilder crudUpdate(CrudPool pool, boolean crash) {
        return exec(session -> pick(session, pool, false))
                .doIf(PolicyScenarios::hasCreatedPolicy)
                .then(requests.updatePolicy(PolicyRequests.POLICY_ID, PolicyRequests.POLICY_NAME))
                .exec(safetyStop(crash));
    }

    /** CRUD phase 4: delete one created policy (each exactly once); does nothing when none are left. */
    public ChainBuilder crudDelete(CrudPool pool, boolean crash) {
        return exec(session -> pick(session, pool, true))
                .doIf(PolicyScenarios::hasCreatedPolicy).then(requests.deletePolicy(PolicyRequests.POLICY_ID))
                .exec(safetyStop(crash));
    }

    // ------------------------------------------------------------------ internals

    /** Mixed write: create, then update and delete if the create succeeded (delete even if update failed). */
    private ChainBuilder write() {
        return exec(requests.createPolicy(PolicyKind.MIXED_WRITE))
                .doIf(PolicyScenarios::hasCreatedPolicy).then(
                        exec(requests.updatePolicy(PolicyRequests.POLICY_ID, PolicyRequests.POLICY_NAME))
                                .exec(requests.deletePolicy(PolicyRequests.POLICY_ID)));
    }

    private static void addChoice(List<Choice.WithWeight> choices, int weightPct, ChainBuilder chain) {
        if (weightPct > 0) {
            choices.add(percent(weightPct).then(chain));
        }
    }

    private static Session pick(Session session, CrudPool pool, boolean forDelete) {
        return (forDelete ? pool.nextForDelete() : pool.nextForReuse())
                .map(policy -> session.set(PolicyRequests.POLICY_ID, policy.id())
                        .set(PolicyRequests.POLICY_NAME, policy.name()))
                .orElseGet(() -> session.remove(PolicyRequests.POLICY_ID));
    }

    private static boolean hasCreatedPolicy(Session session) {
        return session.contains(PolicyRequests.POLICY_ID);
    }
}
