package com.example.tests.api.performance.scenarios;

/**
 * The isolated actions of section 8.1. Each simulation that targets one endpoint
 * repeats exactly one of these.
 */
public enum IsolatedAction {
    /** 50/50 unfiltered or filtered list, each with paging. Measured: R1-R4. */
    LIST(true),
    /** Get one seeded policy. Measured: R5. */
    GET(true),
    /** Create, then a silent delete. Measured: R6. */
    CREATE(false),
    /** Update one seeded policy. Measured: R7. */
    UPDATE(true),
    /** A silent create, then delete. Measured: R8. */
    DELETE(false);

    private final boolean needsSeed;

    IsolatedAction(boolean needsSeed) {
        this.needsSeed = needsSeed;
    }

    public boolean needsSeed() {
        return needsSeed;
    }
}
