package com.example.tests.api.performance.simulations;

import com.example.tests.api.performance.config.Setting;
import com.example.tests.api.performance.scenarios.CrudPool;
import com.example.tests.api.performance.scenarios.PolicyScenarios;
import com.example.tests.api.performance.scenarios.TrafficShapes;
import io.gatling.javaapi.core.PopulationBuilder;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.scenario;

/**
 * Load L2 (section 8.3): phased CRUD run. Create, then get, then update, then delete the same
 * policies, one phase after another, each at {@code normalRate}. Every request is measured.
 */
public class LoadCrudSimulation extends BasePolicySimulation {

    /** Single-request actions in every phase. */
    private static final double REQUESTS_PER_ACTION = 1;
    /** The delete phase runs a little longer so every created policy is taken. */
    private static final Duration DELETE_EXTRA = Duration.ofMinutes(1);

    @Override
    protected Plan plan() {
        Duration hold = Duration.ofMinutes(runtime().config().getInt(Setting.ISOLATED_LOAD_DURATION_MIN));
        PolicyScenarios chains = scenarios();
        CrudPool pool = new CrudPool();

        TrafficShapes.Shape create = shapes().load(REQUESTS_PER_ACTION, hold);
        TrafficShapes.Shape get = shapes().steady(REQUESTS_PER_ACTION, hold);
        TrafficShapes.Shape update = shapes().steady(REQUESTS_PER_ACTION, hold);
        TrafficShapes.Shape delete = shapes().steady(REQUESTS_PER_ACTION, hold.plus(DELETE_EXTRA));

        PopulationBuilder phases = scenario("CRUD 1 create").exec(chains.crudCreate(pool, true))
                .injectOpen(create.steps())
                .andThen(scenario("CRUD 2 get").exec(chains.crudGet(pool, true))
                        .injectOpen(get.steps())
                        .andThen(scenario("CRUD 3 update").exec(chains.crudUpdate(pool, true))
                                .injectOpen(update.steps())
                                .andThen(scenario("CRUD 4 delete").exec(chains.crudDelete(pool, true))
                                        .injectOpen(delete.steps()))));

        Duration length = create.length().plus(get.length()).plus(update.length()).plus(delete.length());
        return new Plan(phases, false, length, true);
    }
}
