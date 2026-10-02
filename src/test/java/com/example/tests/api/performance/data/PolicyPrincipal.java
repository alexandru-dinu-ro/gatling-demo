package com.example.tests.api.performance.data;

import com.example.tests.api.performance.config.PerfConfig;
import com.example.tests.api.performance.config.Setting;

import java.util.stream.Stream;

/**
 * The pre-existing identity the test policies grant access to.
 * Field names match the API's principal object.
 */
public record PolicyPrincipal(String id, String name, String type,
                              String sourceDirectoryName, String sourceDirectoryId) {

    public PolicyPrincipal {
        if (Stream.of(id, name, type, sourceDirectoryName, sourceDirectoryId)
                .anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("every principal field must be set");
        }
    }

    public static PolicyPrincipal from(PerfConfig config) {
        return new PolicyPrincipal(
                config.getString(Setting.PRINCIPAL_ID),
                config.getString(Setting.PRINCIPAL_NAME),
                config.getString(Setting.PRINCIPAL_TYPE),
                config.getString(Setting.PRINCIPAL_SOURCE_DIRECTORY_NAME),
                config.getString(Setting.PRINCIPAL_SOURCE_DIRECTORY_ID));
    }

    /** Never exposes the identity's ID or name. */
    @Override
    public String toString() {
        return "PolicyPrincipal[id=****, name=****, type=" + type + ", sourceDirectoryName=" + sourceDirectoryName + "]";
    }
}
