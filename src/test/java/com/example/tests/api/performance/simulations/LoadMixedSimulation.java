package com.example.tests.api.performance.simulations;

import com.example.tests.api.performance.config.Setting;
import com.example.tests.api.performance.scenarios.TrafficShapes;

import java.time.Duration;
import com.example.tests.api.performance.requests.RequestName;
import java.util.Map;

import static io.gatling.javaapi.core.CoreDsl.scenario;

/**
 * Load L4 (section 8.3): the weighted mixed action at {@code normalRate},
 * ramped over {@code rampUpMin}, then held for {@code loadDurationMin}.
 */
public class LoadMixedSimulation extends BasePolicySimulation {

    private static final String SCENARIO_NAME = "Load mixed";

    @Override
    protected Plan plan() {
        Duration hold = Duration.ofMinutes(runtime().config().getInt(Setting.LOAD_DURATION_MIN));
        TrafficShapes.Shape shape = shapes().load(scenarios().mixedRequestsPerAction(), hold);
        return new Plan(
                scenario(SCENARIO_NAME).exec(scenarios().mixed(true)).injectOpen(shape.steps()),
                true, shape.length(), true, WindowReport.none(), Setting.P95_TARGET_LOAD_MIXED_MS,
                Map.of(RequestName.LIST_ALL, Setting.P95_TARGET_LOAD_MIXED_LIST_ALL_MS,
                        RequestName.LIST_ALL_NEXT, Setting.P95_TARGET_LOAD_MIXED_LIST_ALL_NEXT_MS,
                        RequestName.LIST_FILTERED, Setting.P95_TARGET_LOAD_MIXED_LIST_FILTERED_MS,
                        RequestName.LIST_FILTERED_NEXT, Setting.P95_TARGET_LOAD_MIXED_LIST_FILTERED_NEXT_MS,
                        RequestName.GET_POLICY, Setting.P95_TARGET_LOAD_MIXED_GET_MS,
                        RequestName.CREATE_POLICY, Setting.P95_TARGET_LOAD_MIXED_CREATE_MS,
                        RequestName.UPDATE_POLICY, Setting.P95_TARGET_LOAD_MIXED_UPDATE_MS,
                        RequestName.DELETE_POLICY, Setting.P95_TARGET_LOAD_MIXED_DELETE_MS));
    }
}
