package com.example.tests.api.performance.requests;

import com.example.tests.api.performance.config.PerfConfig;
import com.example.tests.api.performance.config.Setting;
import com.example.tests.api.performance.data.OwnedPolicy;
import com.example.tests.api.performance.data.PolicyKind;
import com.example.tests.api.performance.data.PolicyName;
import com.example.tests.api.performance.simulations.SimulationRuntime;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.CheckBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import io.gatling.javaapi.http.HttpRequestActionBuilder;

import java.util.Objects;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.responseTimeInMillis;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * The measured requests (R1-R8 of the scenario document) as Gatling chains.
 * Each request clears the session's failure flag first, saves its status and response
 * time, checks status 200 and the response-time limit, and reports to the SafetyMonitor.
 * Silent requests are sent and counted by the throttle and safety net, but kept out of reports.
 */
public final class PolicyRequests {

    /** Session key: ID of the policy created in this virtual user's current action. */
    public static final String POLICY_ID = "policyId";
    /** Session key: {@link PolicyName} of that policy. */
    public static final String POLICY_NAME = "policyName";
    /** Session key: ID of the seeded policy picked for this action. */
    public static final String SEED_ID = "seedPolicyId";
    /** Session key: {@link PolicyName} of that seeded policy. */
    public static final String SEED_NAME = "seedPolicyName";
    /** Session key: next-page token from the last list response, if any. */
    public static final String NEXT_TOKEN = "nextToken";

    private static final String PAGE_TOKEN = "pageToken";
    private static final String STATUS = "lastHttpStatus";
    private static final String RESPONSE_TIME = "lastResponseTimeMs";
    private static final String POLICIES_PATH = "/policies";
    private static final String JSON = "application/json";
    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER = "Bearer ";
    private static final int HTTP_OK = 200;

    private final SimulationRuntime runtime;
    private final String policyIdField;
    private final String nextTokenField;
    private final String nextTokenParam;
    private final String searchParam;
    private final int maxResponseTimeMs;

    public PolicyRequests(SimulationRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        PerfConfig config = runtime.config();
        this.policyIdField = config.getString(Setting.POLICY_ID_FIELD);
        this.nextTokenField = config.getString(Setting.LIST_NEXT_TOKEN_FIELD);
        this.nextTokenParam = config.getString(Setting.LIST_NEXT_TOKEN_PARAM);
        this.searchParam = config.getString(Setting.LIST_SEARCH_PARAM);
        this.maxResponseTimeMs = config.getInt(Setting.MAX_RESPONSE_TIME_MS);
    }

    /** HTTP protocol shared by every request: base URL, JSON, current bearer token. */
    public HttpProtocolBuilder protocol() {
        return http.baseUrl(runtime.config().apiBaseUrl())
                .acceptHeader(JSON)
                .header(AUTHORIZATION, session -> BEARER + runtime.tokens().current())
                .disableCaching();
    }

    /** True if the last list response carried a non-empty next-page token. */
    public static boolean hasNextToken(Session session) {
        return session.contains(NEXT_TOKEN) && !session.getString(NEXT_TOKEN).isBlank();
    }

    /** Picks the next seeded policy into {@link #SEED_ID} and {@link #SEED_NAME}. */
    public ChainBuilder pickSeed() {
        return exec(session -> {
            OwnedPolicy seed = runtime.seeds().next();
            return session.set(SEED_ID, seed.id()).set(SEED_NAME, seed.name());
        });
    }

    /** R1 / R3: first page of the list, unfiltered or filtered to this run's seeds. */
    public ChainBuilder listFirstPage(RequestName name, boolean filtered) {
        HttpRequestActionBuilder request = http(name.reportName()).get(POLICIES_PATH);
        if (filtered) {
            request = request.queryParam(searchParam, session -> seedSearchText());
        }
        return exec(session -> session.remove(NEXT_TOKEN))
                .exec(measured(request, false, nextTokenCheck()));
    }

    /** R2 / R4: next page, using the token saved by the previous list request. */
    public ChainBuilder listNextPage(RequestName name, boolean filtered) {
        HttpRequestActionBuilder request = http(name.reportName()).get(POLICIES_PATH);
        if (filtered) {
            request = request.queryParam(searchParam, session -> seedSearchText());
        }
        request = request.queryParam(nextTokenParam, session -> session.getString(PAGE_TOKEN));
        return exec(session -> session.set(PAGE_TOKEN, session.getString(NEXT_TOKEN)).remove(NEXT_TOKEN))
                .exec(measured(request, false, nextTokenCheck()));
    }

    /** R5: get the policy whose ID is in the given session key. */
    public ChainBuilder getPolicy(String idKey) {
        return measured(http(RequestName.GET_POLICY.reportName()).get(session -> policyPath(session, idKey)), false);
    }

    /** R6, measured: create a new policy of the given kind. */
    public ChainBuilder createPolicy(PolicyKind kind) {
        return createPolicy(kind, false);
    }

    /** R6: create a new policy of the given kind; saves {@link #POLICY_ID} and registers it. */
    public ChainBuilder createPolicy(PolicyKind kind, boolean silent) {
        HttpRequestActionBuilder request = http(RequestName.CREATE_POLICY.reportName())
                .post(POLICIES_PATH)
                .body(StringBody(session -> runtime.payloads().createBody(session.get(POLICY_NAME))))
                .asJson();
        return exec(session -> session.set(POLICY_NAME, runtime.run().nextName(kind)).remove(POLICY_ID))
                .exec(measured(request, silent, jsonPath("$." + policyIdField).saveAs(POLICY_ID)))
                .exec(session -> {
                    if (!session.isFailed() && session.contains(POLICY_ID)) {
                        runtime.run().registry().created(session.getString(POLICY_ID), session.get(POLICY_NAME));
                    }
                    return session;
                });
    }

    /** R7: update the policy in the given keys, keeping its name. */
    public ChainBuilder updatePolicy(String idKey, String nameKey) {
        return measured(http(RequestName.UPDATE_POLICY.reportName())
                .put(session -> policyPath(session, idKey))
                .body(StringBody(session -> runtime.payloads().updateBody(session.<PolicyName>get(nameKey))))
                .asJson(), false);
    }

    /** R8, measured: delete the policy in the given key. */
    public ChainBuilder deletePolicy(String idKey) {
        return deletePolicy(idKey, false);
    }

    /** R8: delete the policy in the given key; removes it from the registry on success. */
    public ChainBuilder deletePolicy(String idKey, boolean silent) {
        return measured(http(RequestName.DELETE_POLICY.reportName()).delete(session -> policyPath(session, idKey)), silent)
                .exec(session -> {
                    if (!session.isFailed()) {
                        runtime.run().registry().deleted(session.getString(idKey));
                    }
                    return session;
                });
    }

    // ------------------------------------------------------------------ internals

    private ChainBuilder measured(HttpRequestActionBuilder request, boolean silent, CheckBuilder... extraChecks) {
        HttpRequestActionBuilder checked = request.check(
                status().saveAs(STATUS),
                responseTimeInMillis().saveAs(RESPONSE_TIME),
                status().is(HTTP_OK),
                responseTimeInMillis().lte(maxResponseTimeMs));
        if (extraChecks.length > 0) {
            checked = checked.check(extraChecks);
        }
        if (silent) {
            checked = checked.silent();
        }
        return exec(Session::markAsSucceeded)
                .exec(checked)
                .exec(session -> {
                    Integer status = session.contains(STATUS) ? session.getInt(STATUS) : null;
                    Long millis = session.contains(RESPONSE_TIME)
                            ? ((Number) session.get(RESPONSE_TIME)).longValue() : null;
                    runtime.monitor().record(status, !session.isFailed(), millis);
                    return session.remove(STATUS).remove(RESPONSE_TIME);
                });
    }

    private CheckBuilder nextTokenCheck() {
        return jsonPath("$." + nextTokenField).optional().saveAs(NEXT_TOKEN);
    }

    private String seedSearchText() {
        return runtime.run().runPrefix() + PolicyKind.SEED.letter();
    }

    private static String policyPath(Session session, String idKey) {
        return POLICIES_PATH + "/" + session.getString(idKey);
    }
}
