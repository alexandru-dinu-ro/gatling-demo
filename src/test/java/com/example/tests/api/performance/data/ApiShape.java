package com.example.tests.api.performance.data;

import com.example.tests.api.performance.config.PerfConfig;
import com.example.tests.api.performance.config.Setting;

/**
 * Where the policies API lives and how its requests and responses are shaped.
 *
 * @param baseUrl        API base URL without trailing slash, e.g. {@code https://x.uap.cyberark.cloud/api}
 * @param policyIdField  response field holding the policy ID (create response)
 * @param itemsField     list response field holding the array of policies
 * @param nextTokenField list response field holding the next-page token
 * @param nextTokenParam query parameter that sends the token back
 * @param searchParam    query parameter for the free-text name search
 */
public record ApiShape(String baseUrl, String policyIdField, String itemsField,
                       String nextTokenField, String nextTokenParam, String searchParam) {

    public static ApiShape from(PerfConfig config) {
        return new ApiShape(
                config.apiBaseUrl(),
                config.getString(Setting.POLICY_ID_FIELD),
                config.getString(Setting.LIST_ITEMS_FIELD),
                config.getString(Setting.LIST_NEXT_TOKEN_FIELD),
                config.getString(Setting.LIST_NEXT_TOKEN_PARAM),
                config.getString(Setting.LIST_SEARCH_PARAM));
    }
}
