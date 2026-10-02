package com.example.tests.api.performance.requests;

/**
 * The fixed request names shown in Gatling reports (section 6 of the scenario document).
 * Never rename one casually: report entries are compared across runs by name.
 */
public enum RequestName {
    LIST_ALL("List policies (all)"),
    LIST_ALL_NEXT("List policies (all, next page)"),
    LIST_FILTERED("List policies (filtered)"),
    LIST_FILTERED_NEXT("List policies (filtered, next page)"),
    GET_POLICY("Get policy"),
    CREATE_POLICY("Create policy"),
    UPDATE_POLICY("Update policy"),
    DELETE_POLICY("Delete policy");

    private final String reportName;

    RequestName(String reportName) {
        this.reportName = reportName;
    }

    /** Name as it appears in the Gatling report. */
    public String reportName() {
        return reportName;
    }
}
