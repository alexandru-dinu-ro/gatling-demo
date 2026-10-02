package com.example.tests.api.performance.data;

import com.example.tests.api.performance.config.PerfConfig;
import com.example.tests.api.performance.config.Setting;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.Optional;

/**
 * Where list results keep each policy's ID and name.
 *
 * @param idPointer   JSON pointer to the policy ID, e.g. {@code /metadata/policyId}
 * @param namePointer JSON pointer to the policy name, e.g. {@code /metadata/name}
 */
public record PolicyFields(JsonPointer idPointer, JsonPointer namePointer) {

    public PolicyFields {
        Objects.requireNonNull(idPointer, "idPointer");
        Objects.requireNonNull(namePointer, "namePointer");
    }

    public static PolicyFields from(PerfConfig config) {
        return of(config.getString(Setting.LIST_ITEM_ID_POINTER), config.getString(Setting.LIST_ITEM_NAME_POINTER));
    }

    public static PolicyFields of(String idPointer, String namePointer) {
        return new PolicyFields(JsonPointer.compile(idPointer), JsonPointer.compile(namePointer));
    }

    /** The item's ID and name, or empty if either is missing, blank or not text. */
    public Optional<PolicySummary> read(JsonNode item) {
        if (item == null) {
            return Optional.empty();
        }
        JsonNode id = item.at(idPointer);
        JsonNode name = item.at(namePointer);
        if (!isNonBlankText(id) || !isNonBlankText(name)) {
            return Optional.empty();
        }
        return Optional.of(new PolicySummary(id.asText(), name.asText()));
    }

    private static boolean isNonBlankText(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank();
    }
}
